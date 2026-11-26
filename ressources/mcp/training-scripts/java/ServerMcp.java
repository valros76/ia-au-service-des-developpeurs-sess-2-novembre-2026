import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serveur MCP custom en Java (transport stdio, JSON-RPC 2.0) pour le TP 3 :
 * - Ressource : db://schema
 * - Outil    : query_database (SELECT uniquement, >= 10 chars, LIMIT 50 auto, JSON)
 *
 * Prérequis : pilote JDBC SQLite (sqlite-jdbc) dans le classpath.
 * Important : ne jamais écrire de logs sur System.out (réservé au JSON-RPC). Utiliser System.err.
 */
public class ServerJava {

    private static final String SERVER_NAME = "mcp-db-explorer-java";
    private static final String SERVER_VERSION = "1.1.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int DEFAULT_LIMIT = 50;

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "DROP", "DELETE", "UPDATE", "INSERT", "ALTER",
            "PRAGMA", "ATTACH", "DETACH", "REPLACE", "VACUUM", "CREATE"
    );

    public static void main(String[] args) {
        Path dbPath = resolveDatabasePath();
        Connection conn = openReadOnlyConnection(dbPath);

        if (conn == null) {
            System.err.println("[Avertissement] entreprise.db introuvable. Lancez d'abord le script d'initialisation de la base.");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                handleRawMessage(trimmed, conn, writer);
            }
        } catch (IOException e) {
            System.err.println("[Erreur I/O stdio] " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private static Path resolveDatabasePath() {
        List<Path> candidates = new ArrayList<>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            candidates.add(cursor.resolve("entreprise.db"));
            candidates.add(cursor.resolve("tp3-mcp").resolve("entreprise.db"));
            candidates.add(cursor.resolve("tp3-mcp-server").resolve("entreprise.db"));
            candidates.add(cursor.resolve("ressources/mcp/training-scripts/java/entreprise.db"));
            cursor = cursor.getParent();
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        return cwd.resolve("entreprise.db");
    }

    private static Connection openReadOnlyConnection(Path dbPath) {
        if (!Files.isRegularFile(dbPath)) {
            return null;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
            Connection conn = DriverManager.getConnection(url);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA query_only = ON;");
            }
            return conn;
        } catch (ClassNotFoundException e) {
            System.err.println("[Erreur JDBC] Pilote SQLite (org.sqlite.JDBC) introuvable dans le classpath : " + e.getMessage());
            return null;
        } catch (SQLException e) {
            System.err.println("[Erreur SQLite] Impossible d'ouvrir " + dbPath + " : " + e.getMessage());
            return null;
        }
    }

    private static void handleRawMessage(String rawJson, Connection conn, BufferedWriter writer) throws IOException {
        String method = extractJsonString(rawJson, "method");
        Object id = extractJsonId(rawJson);

        if (method == null || method.isEmpty()) {
            return;
        }

        if ("notifications/initialized".equals(method) || method.startsWith("notifications/")) {
            return;
        }

        switch (method) {
            case "initialize" -> {
                String result = """
                        {
                          "protocolVersion": "%s",
                          "capabilities": {
                            "tools": {},
                            "resources": {}
                          },
                          "serverInfo": {
                            "name": "%s",
                            "version": "%s"
                          }
                        }
                        """.formatted(PROTOCOL_VERSION, SERVER_NAME, SERVER_VERSION);
                sendResult(writer, id, result);
            }

            case "ping" -> sendResult(writer, id, "{}");

            case "tools/list" -> {
                String result = """
                        {
                          "tools": [
                            {
                              "name": "query_database",
                              "description": "Exécute une requête SQL SELECT en lecture seule sur entreprise.db (limite automatique à 50 lignes).",
                              "inputSchema": {
                                "type": "object",
                                "properties": {
                                  "sql": {
                                    "type": "string",
                                    "description": "Requête SQL SELECT (minimum 10 caractères, sans modification de données)"
                                  }
                                },
                                "required": ["sql"]
                              }
                            }
                          ]
                        }
                        """;
                sendResult(writer, id, result);
            }

            case "tools/call" -> {
                String toolName = extractNestedString(rawJson, "params", "name");
                if (!"query_database".equals(toolName)) {
                    sendError(writer, id, -32601, "Outil introuvable : " + toolName);
                    return;
                }

                String sqlParam = extractArgumentString(rawJson, "sql");
                try {
                    if (conn == null) {
                        throw new IllegalStateException("Base entreprise.db introuvable ou inaccessible.");
                    }
                    List> rows = executeSafeSelect(conn, sqlParam == null ? "" : sqlParam);
                    String rowsJson = toJsonArray(rows);
                    String contentPayload = """
                            {
                              "content": [
                                {
                                  "type": "text",
                                  "text": %s
                                }
                              ]
                            }
                            """.formatted(jsonEscape(rowsJson));
                    sendResult(writer, id, contentPayload);
                } catch (Exception e) {
                    String errPayload = """
                            {
                              "isError": true,
                              "content": [
                                {
                                  "type": "text",
                                  "text": %s
                                }
                              ]
                            }
                            """.formatted(jsonEscape(e.getMessage() == null ? "Erreur d'exécution SQL" : e.getMessage()));
                    sendResult(writer, id, errPayload);
                }
            }

            case "resources/list" -> {
                String result = """
                        {
                          "resources": [
                            {
                              "uri": "db://schema",
                              "name": "Schéma SQLite (entreprise.db)",
                              "description": "Structure DDL (CREATE TABLE) de la base entreprise.db",
                              "mimeType": "text/plain"
                            }
                          ]
                        }
                        """;
                sendResult(writer, id, result);
            }

            case "resources/templates/list" -> sendResult(writer, id, "{\"resourceTemplates\":[]}");

            case "resources/read" -> {
                String uri = extractNestedString(rawJson, "params", "uri");
                if (!"db://schema".equals(uri)) {
                    sendError(writer, id, -32602, "Ressource inconnue : " + uri);
                    return;
                }
                if (conn == null) {
                    sendError(writer, id, -32000, "Base entreprise.db introuvable.");
                    return;
                }
                try {
                    String ddl = getDatabaseSchema(conn);
                    String resPayload = """
                            {
                              "contents": [
                                {
                                  "uri": "db://schema",
                                  "mimeType": "text/plain",
                                  "text": %s
                                }
                              ]
                            }
                            """.formatted(jsonEscape(ddl));
                    sendResult(writer, id, resPayload);
                } catch (SQLException e) {
                    sendError(writer, id, -32000, "Erreur lecture schéma : " + e.getMessage());
                }
            }

            default -> {
                if (id != null) {
                    sendError(writer, id, -32601, "Méthode non supportée : " + method);
                }
            }
        }
    }

    private static String getDatabaseSchema(Connection conn) throws SQLException {
        String query = """
                SELECT sql
                FROM sqlite_master
                WHERE type IN ('table', 'view', 'index')
                  AND sql IS NOT NULL
                  AND name NOT LIKE 'sqlite_%'
                ORDER BY CASE type WHEN 'table' THEN 1 WHEN 'view' THEN 2 ELSE 3 END, name
                """;
        List<String> statements = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String sql = rs.getString("sql");
                if (sql != null && !sql.isBlank()) {
                    String clean = sql.trim();
                    if (!clean.endsWith(";")) {
                        clean = clean + ";";
                    }
                    statements.add(clean);
                }
            }
        }
        return statements.isEmpty() ? "-- Aucun objet SQL trouvé." : String.join("\n\n", statements);
    }

    private static List> executeSafeSelect(Connection conn, String rawSql) throws SQLException {
        String trimmed = rawSql.trim();
        if (trimmed.length() < 10) {
            throw new IllegalArgumentException("Requête SQL trop courte (minimum 10 caractères).");
        }

        // Retire un éventuel point-virgule final, mais interdit tout autre ';' (requêtes multiples)
        String withoutTrailingSemi = trimmed.replaceAll("[;\\s]+$", "");
        if (withoutTrailingSemi.contains(";")) {
            throw new IllegalArgumentException("Sécurité : plusieurs instructions SQL ne sont pas autorisées.");
        }

        if (!Pattern.compile("^SELECT\\b", Pattern.CASE_INSENSITIVE).matcher(withoutTrailingSemi).find()) {
            throw new IllegalArgumentException("Sécurité : seules les requêtes SELECT sont autorisées.");
        }

        for (String kw : FORBIDDEN_KEYWORDS) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(withoutTrailingSemi).find()) {
                throw new IllegalArgumentException("Sécurité : mot-clé interdit détecté (" + kw + ").");
            }
        }

        boolean hasLimit = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE).matcher(withoutTrailingSemi).find();
        String finalSql = hasLimit ? withoutTrailingSemi : withoutTrailingSemi + " LIMIT " + DEFAULT_LIMIT;

        List> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(finalSql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    row.put(colName, rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void sendResult(BufferedWriter writer, Object id, String resultJson) throws IOException {
        String idPart = formatJsonId(id);
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":" + idPart + ",\"result\":" + resultJson + "}";
        writer.write(frame.replaceAll("\\R+", " "));
        writer.newLine();
        writer.flush();
    }

    private static void sendError(BufferedWriter writer, Object id, int code, String message) throws IOException {
        String idPart = formatJsonId(id);
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":" + idPart + ",\"error\":{\"code\":" + code + ",\"message\":" + jsonEscape(message) + "}}";
        writer.write(frame);
        writer.newLine();
        writer.flush();
    }

    private static String formatJsonId(Object id) {
        if (id == null) {
            return "null";
        }
        if (id instanceof Number) {
            return id.toString();
        }
        return jsonEscape(id.toString());
    }

    private static Object extractJsonId(String json) {
        Matcher numMatcher = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (numMatcher.find()) {
            try {
                return Long.parseLong(numMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher strMatcher = Pattern.compile("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (strMatcher.find()) {
            return unescapeJsonString(strMatcher.group(1));
        }
        return null;
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return m.find() ? unescapeJsonString(m.group(1)) : null;
    }

    private static String extractNestedString(String json, String parentKey, String childKey) {
        int idx = json.indexOf("\"" + parentKey + "\"");
        if (idx < 0) {
            return extractJsonString(json, childKey);
        }
        return extractJsonString(json.substring(idx), childKey);
    }

    private static String extractArgumentString(String json, String argName) {
        int argsIdx = json.indexOf("\"arguments\"");
        String scope = argsIdx >= 0 ? json.substring(argsIdx) : json;
        return extractJsonString(scope, argName);
    }

    private static String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append("\\u").append(hex);
                                i += 4;
                            }
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String toJsonArray(List> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toJsonObject(rows.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String toJsonObject(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonEscape(entry.getKey())).append(':').append(toJsonValue(entry.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String toJsonValue(Object val) {
        if (val == null) {
            return "null";
        }
        if (val instanceof Number || val instanceof Boolean) {
            return val.toString();
        }
        if (val instanceof byte[] bytes) {
            return jsonEscape("[BLOB:" + bytes.length + " bytes]");
        }
        return jsonEscape(val.toString());
    }
}