/**
 * - Nécessite une clé env : GROQ_API_KEY="votreCleGroq" (pour les skills .mdr)
 * - Si entreprise.db est présente (avec sqlite3 CLI), active aussi les outils SQLite du TP 3
 *
 * - Pour tester le serveur en CLI :
 * - exécution skill : java ServerMcp.java revue_java ExempleSale.java
 * - prévisualisation : java ServerMcp.java revue_java ExempleSale.java --prompt
 * - requête SQLite  : java ServerMcp.java sql "SELECT * FROM produits"
 */

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

public class ServerMcp {

    record Skill(String name, String description, String template) {
        String compile(String input) {
            return template.contains("{{input}}")
                ? template.replace("{{input}}", input)
                : (template + "\n\n" + input).trim();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
        "\\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public static void main(String[] args) throws Exception {
        Path projectRoot = findProjectRoot(Path.of("").toAbsolutePath());
        Map<String, String> env = loadDotEnv(projectRoot.resolve(".env"));

        String groqApiKey = env.getOrDefault("GROQ_API_KEY",
            Objects.requireNonNullElse(System.getenv("GROQ_API_KEY"), ""));

        Path dbPath = projectRoot.resolve("entreprise.db");
        boolean hasDb = Files.isRegularFile(dbPath);
        Map<String, Skill> skills = loadMdrSkills(projectRoot.resolve(".prompts"));

        // ====================================================================
        // MODE CLI DIRECT
        // ====================================================================
        if (args.length > 0) {
            List<String> argList = Arrays.asList(args);
            boolean isPrompt = argList.contains("--prompt");
            List<String> cleanArgs = argList.stream().filter(a -> !a.equals("--prompt")).toList();

            String cmd = cleanArgs.isEmpty() ? "" : cleanArgs.get(0);
            String target = cleanArgs.size() > 1 ? cleanArgs.get(1) : "";

            if ("sql".equals(cmd)) {
                try {
                    System.out.println(executeSafeSelect(dbPath, target));
                } catch (Exception e) {
                    System.err.println("[Erreur SQL] " + e.getMessage());
                    System.exit(1);
                }
                return;
            }

            Skill skill = skills.get(cmd);
            if (skill == null) {
                String avail = skills.isEmpty() ? "(aucun .mdr dans .prompts/)" : String.join(", ", skills.keySet());
                System.err.println("Skill introuvable : '" + cmd + "'\nDisponibles : " + avail + (hasDb ? ", sql" : ""));
                System.exit(1);
            }

            String input = (!target.isEmpty() && Files.isRegularFile(Path.of(target)))
                ? Files.readString(Path.of(target))
                : target;

            String compiled = skill.compile(input);
            if (isPrompt) {
                System.out.println(compiled);
                return;
            }

            System.out.println(askGroq(groqApiKey, compiled));
            return;
        }

        // ====================================================================
        // MOTEUR JSON-RPC 2.0 / MCP UNIFIÉ (MDR + SQLITE)
        // ====================================================================
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String id = extractJsonRaw(line, "id");
            String method = extractJsonString(line, "method");
            if (method == null) continue;

            switch (method) {
                case "initialize" -> sendResult(id, """
                    {"protocolVersion":"2024-11-05","capabilities":{"prompts":{},"tools":{},"resources":{}},"serverInfo":{"name":"mcp-unified-java","version":"1.0.0"}}""");

                case "notifications/initialized" -> {}

                case "prompts/list" -> {
                    List<String> items = new ArrayList<>();
                    for (Skill s : skills.values()) {
                        items.add("""
                            {"name":%s,"description":%s,"arguments":[{"name":"input","description":"Contexte {{input}}","required":false}]}"""
                            .formatted(jsonStr(s.name), jsonStr(s.description)));
                    }
                    sendResult(id, "{\"prompts\":[" + String.join(",", items) + "]}");
                }

                case "prompts/get" -> {
                    String name = extractJsonString(line, "name");
                    Skill s = skills.get(name);
                    if (s == null) {
                        sendError(id, -32601, "Prompt introuvable : " + name);
                        break;
                    }
                    String input = Objects.requireNonNullElse(extractJsonString(line, "input"), "");
                    sendResult(id, """
                        {"description":%s,"messages":[{"role":"user","content":{"type":"text","text":%s}}]}"""
                        .formatted(jsonStr(s.description), jsonStr(s.compile(input))));
                }

                case "tools/list" -> {
                    List<String> items = new ArrayList<>();
                    for (Skill s : skills.values()) {
                        items.add("""
                            {"name":%s,"description":%s,"inputSchema":{"type":"object","properties":{"input":{"type":"string","description":"Données à analyser"}},"required":["input"]}}"""
                            .formatted(jsonStr(s.name), jsonStr("[Groq] " + s.description)));
                    }
                    if (hasDb) {
                        items.add("""
                            {"name":"query_database","description":"Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).","inputSchema":{"type":"object","properties":{"sql":{"type":"string","minLength":10,"description":"Requête SQL SELECT valide"}},"required":["sql"]}}""");
                    }
                    sendResult(id, "{\"tools\":[" + String.join(",", items) + "]}");
                }

                case "tools/call" -> {
                    String name = extractJsonString(line, "name");
                    if ("query_database".equals(name)) {
                        try {
                            String sql = Objects.requireNonNullElse(extractJsonString(line, "sql"), "");
                            String jsonRows = executeSafeSelect(dbPath, sql);
                            sendResult(id, "{\"content\":[{\"type\":\"text\",\"text\":" + jsonStr(jsonRows) + "}]}");
                        } catch (Exception e) {
                            sendResult(id, "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":" + jsonStr(e.getMessage()) + "}]}");
                        }
                        break;
                    }

                    Skill s = skills.get(name);
                    if (s == null) {
                        sendError(id, -32601, "Outil introuvable : " + name);
                        break;
                    }
                    try {
                        String input = Objects.requireNonNullElse(extractJsonString(line, "input"), "");
                        String answer = askGroq(groqApiKey, s.compile(input));
                        sendResult(id, "{\"content\":[{\"type\":\"text\",\"text\":" + jsonStr(answer) + "}]}");
                    } catch (Exception e) {
                        sendResult(id, "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":" + jsonStr(e.getMessage()) + "}]}");
                    }
                }

                case "resources/list" -> {
                    List<String> items = new ArrayList<>();
                    for (Skill s : skills.values()) {
                        items.add("""
                            {"uri":%s,"name":%s,"description":%s,"mimeType":"text/markdown"}"""
                            .formatted(jsonStr("skill://" + s.name), jsonStr(s.name + ".mdr"), jsonStr(s.description)));
                    }
                    if (hasDb) {
                        items.add("""
                            {"uri":"db://schema","name":"Schéma SQLite (entreprise.db)","description":"Structure DDL (CREATE TABLE) de la base entreprise.db","mimeType":"text/plain"}""");
                    }
                    sendResult(id, "{\"resources\":[" + String.join(",", items) + "]}");
                }

                case "resources/read" -> {
                    String uri = Objects.requireNonNullElse(extractJsonString(line, "uri"), "");
                    if ("db://schema".equals(uri) && hasDb) {
                        String schema = runSqliteCli(dbPath, List.of(), "SELECT sql || ';' FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';");
                        sendResult(id, "{\"contents\":[{\"uri\":\"db://schema\",\"mimeType\":\"text/plain\",\"text\":" + jsonStr(schema) + "}]}");
                        break;
                    }

                    String name = uri.startsWith("skill://") ? uri.substring(8) : "";
                    Skill s = skills.get(name);
                    if (s == null) {
                        sendError(id, -32602, "Ressource inconnue : " + uri);
                        break;
                    }
                    sendResult(id, """
                        {"contents":[{"uri":%s,"mimeType":"text/markdown","text":%s}]}"""
                        .formatted(jsonStr(uri), jsonStr(s.template)));
                }

                case "ping" -> sendResult(id, "{}");

                default -> {
                    if (id != null) sendError(id, -32601, "Méthode non supportée : " + method);
                }
            }
        }
    }

    private static String executeSafeSelect(Path dbPath, String rawSql) throws Exception {
        if (!Files.isRegularFile(dbPath)) throw new RuntimeException("Base entreprise.db introuvable.");
        if (rawSql == null || rawSql.trim().length() < 10) {
            throw new IllegalArgumentException("Paramètre sql invalide (minimum 10 caractères requis).");
        }
        String cleaned = rawSql.trim().replaceAll(";+$", "");
        if (cleaned.contains(";")) throw new IllegalArgumentException("Sécurité : requêtes multiples interdites.");
        if (!cleaned.matches("(?is)^SELECT\\b.*")) throw new IllegalArgumentException("Sécurité : seul SELECT est autorisé.");
        if (FORBIDDEN_SQL.matcher(cleaned).find()) throw new IllegalArgumentException("Sécurité : mot-clé SQL interdit.");

        String finalSql = cleaned.matches("(?is).*\\bLIMIT\\s+\\d+.*") ? cleaned : cleaned + " LIMIT 50";
        String out = runSqliteCli(dbPath, List.of("-json"), finalSql + ";");
        return out.isBlank() ? "[]" : out;
    }

    private static String runSqliteCli(Path dbPath, List<String> flags, String sql) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("sqlite3", "-readonly"));
        cmd.addAll(flags);
        cmd.add(dbPath.toString());
        cmd.add(sql);

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new RuntimeException("Erreur SQLite : " + output);
        return output;
    }

    private static Path findProjectRoot(Path startDir) {
        Path dir = startDir;
        while (dir != null) {
            if (Files.isDirectory(dir.resolve(".prompts")) || Files.isRegularFile(dir.resolve(".env")) || Files.isRegularFile(dir.resolve("entreprise.db"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return startDir;
    }

    private static String askGroq(String apiKey, String prompt) throws Exception {
        if (apiKey.isBlank()) throw new RuntimeException("Variable GROQ_API_KEY absente dans .env.");
        String body = """
            {"model":"llama-3.3-70b-versatile","messages":[{"role":"user","content":%s}]}"""
            .formatted(jsonStr(prompt));

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Erreur HTTP Groq " + resp.statusCode() + " : " + resp.body());
        }
        String content = extractJsonString(resp.body(), "content");
        return content != null ? content : "Réponse vide de Groq.";
    }

    private static Map<String, String> loadDotEnv(Path path) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(path)) return map;
        for (String raw : Files.readAllLines(path)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
            String[] parts = line.split("=", 2);
            map.put(parts[0].trim(), parts[1].trim().replaceAll("^[\"']|[\"']$", ""));
        }
        return map;
    }

    private static Map<String, Skill> loadMdrSkills(Path dir) throws IOException {
        Map<String, Skill> skills = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return skills;

        Pattern fmPattern = Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*\\R(.*)$", Pattern.DOTALL);
        Pattern descPattern = Pattern.compile("^description:\\s*(.+)$", Pattern.MULTILINE);

        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".mdr")).sorted().toList()) {
                String raw = Files.readString(file).trim();
                if (raw.isEmpty()) continue;

                String base = file.getFileName().toString().replaceFirst("\\.mdr$", "");
                String name = base.replaceAll("[^a-zA-Z0-9_-]", "_");
                String desc = "Skill " + name + " (.mdr)";
                String body = raw;

                Matcher m = fmPattern.matcher(raw);
                if (m.find()) {
                    body = m.group(2).trim();
                    Matcher d = descPattern.matcher(m.group(1));
                    if (d.find()) desc = d.group(1).trim();
                }
                skills.put(name, new Skill(name, desc, body));
            }
        }
        return skills;
    }

    private static void sendResult(String id, String resultJson) {
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + (id == null ? "null" : id) + ",\"result\":" + resultJson + "}");
        System.out.flush();
    }

    private static void sendError(String id, int code, String message) {
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + (id == null ? "null" : id) + ",\"error\":{\"code\":" + code + ",\"message\":" + jsonStr(message) + "}}");
        System.out.flush();
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String extractJsonRaw(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|\\d+|null)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!m.find()) return null;
        return m.group(1).replace("\\