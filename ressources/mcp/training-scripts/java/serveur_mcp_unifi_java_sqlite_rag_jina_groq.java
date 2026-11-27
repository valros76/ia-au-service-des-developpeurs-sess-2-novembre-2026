import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serveur MCP Unifié en Java 21+ (Skills .mdr + SQLite entreprise.db + RAG SQLite Jina/Groq).
 * Note : Requiert le pilote JDBC SQLite (org.xerial:sqlite-jdbc) dans le classpath si entreprise.db est utilisée.
 *
 * Exemples CLI :
 * - java ServerMcp.java sql "SELECT * FROM produits"
 * - java ServerMcp.java rag-index
 * - java ServerMcp.java rag-ask "Quelles sont les règles sur les commandes BLOQUEE ?"
 * - java ServerMcp.java mon-skill "Entrée de test" --prompt
 */
public class ServerMcp {

    private static final String JINA_EMBEDDINGS_URL = "https://api.jina.ai/v1/embeddings";
    private static final String JINA_EMBEDDINGS_MODEL = "jina-embeddings-v3";
    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b";
    private static final int RAG_TOP_K = 3;
    private static final double RAG_MIN_SCORE = 0.35;

    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "\\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private record Skill(String name, String description, String template) {
        String compile(String input) {
            String safeInput = input == null ? "" : input;
            if (template.contains("{{input}}")) {
                return template.replace("{{input}}", safeInput);
            }
            return (template + "\n\n" + safeInput).trim();
        }
    }

    private record RagChunk(String id, String source, int chunkIndex, String content, double score) {}

    public static void main(String[] args) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path projectRoot = findProjectRoot(cwd);
        Map<String, String> env = loadEnvironment(projectRoot.resolve(".env"));

        String groqApiKey = env.getOrDefault("GROQ_API_KEY", "");
        String jinaApiKey = env.getOrDefault("JINA_API_KEY", "");

        Connection db = openEnterpriseDatabase(projectRoot);
        Map<String, Skill> skills = loadMdrSkills(projectRoot);

        if (args.length > 0) {
            int exitCode = handleCli(args, db, skills, jinaApiKey, groqApiKey);
            closeQuietly(db);
            System.exit(exitCode);
        }

        runMcpStdioLoop(db, skills, jinaApiKey, groqApiKey);
        closeQuietly(db);
    }

    private static Path findProjectRoot(Path startDir) {
        String[] markers = {".prompts", ".env", "entreprise.db"};
        for (String m : markers) {
            if (Files.exists(startDir.resolve(m))) {
                return startDir;
            }
        }
        Path current = startDir;
        while (current != null) {
            for (String m : markers) {
                if (Files.exists(current.resolve(m))) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return startDir;
    }

    private static Map<String, String> loadEnvironment(Path envPath) {
        Map<String, String> map = new LinkedHashMap<>();
        System.getenv().forEach(map::put);
        if (Files.isRegularFile(envPath)) {
            try {
                for (String rawLine : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    if (!k.isEmpty()) {
                        map.putIfAbsent(k, v);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return map;
    }

    private static Connection openEnterpriseDatabase(Path projectRoot) {
        List<Path> candidates = List.of(
                projectRoot.resolve("entreprise.db"),
                projectRoot.resolve("enterprise.db"),
                Paths.get("entreprise.db").toAbsolutePath().normalize()
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    Class.forName("org.sqlite.JDBC");
                    Connection conn = DriverManager.getConnection("jdbc:sqlite:" + candidate);
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA foreign_keys = ON;");
                    }
                    return conn;
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private static List<Map<String, Object>> executeSafeSelect(Connection db, String rawSql) throws SQLException {
        if (db == null) {
            throw new IllegalStateException("Base entreprise.db introuvable ou pilote SQLite absent.");
        }
        if (rawSql == null || rawSql.trim().length() < 10) {
            throw new IllegalArgumentException("Paramètre sql invalide (minimum 10 caractères requis).");
        }
        String cleaned = rawSql.trim().replaceAll(";+$", "");
        if (cleaned.contains(";")) {
            throw new SecurityException("Sécurité : requêtes multiples interdites.");
        }
        if (!cleaned.regionMatches(true, 0, "SELECT", 0, 6)) {
            throw new SecurityException("Sécurité : seul SELECT est autorisé.");
        }
        if (FORBIDDEN_SQL.matcher(cleaned).find()) {
            throw new SecurityException("Sécurité : mot-clé SQL interdit.");
        }
        String finalSql = cleaned.matches("(?is).*\\bLIMIT\\s+\\d+.*") ? cleaned : cleaned + " LIMIT 50";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(finalSql)) {
            int colCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String getDatabaseSchema(Connection db) throws SQLException {
        if (db == null) {
            return "";
        }
        List<String> ddl = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                String sql = rs.getString("sql");
                if (sql != null && !sql.isBlank()) {
                    String trimmed = sql.trim();
                    ddl.add(trimmed.endsWith(";") ? trimmed : trimmed + ";");
                }
            }
        }
        return String.join("\n\n", ddl);
    }

    private record CorpusDoc(String titre, String categorie, String contenu) {}

    private static List<CorpusDoc> defaultDbDocuments() {
        return List.of(
                new CorpusDoc(
                        "politique-remboursement.md",
                        "politique-remboursement",
                        "## Délais de rétractation et remboursement\n"
                                + "Le client dispose d'un délai légal de 14 jours calendaires à compter de la réception de la commande "
                                + "pour demander un remboursement ou un retour produit, sous réserve que l'article soit retourné dans son état d'origine.\n\n"
                                + "## Règles selon le statut des commandes\n"
                                + "Les commandes au statut LIVREE peuvent être remboursées après contrôle de réception. "
                                + "Les commandes EXPEDIEE font l'objet d'un suivi transporteur avant remboursement. "
                                + "Les commandes au statut EN_COURS peuvent être annulées sans frais avant préparation logistique. "
                                + "Toute commande au statut BLOQUEE ne peut pas être remboursée automatiquement tant que l'anomalie métier ou le litige n'a pas été levé.\n\n"
                                + "## Validation financière et seuils\n"
                                + "Tout remboursement ou avoir supérieur à 500 EUR nécessite une double validation manager et une vérification préalable des stocks et factures associées."
                ),
                new CorpusDoc(
                        "architecture-api.md",
                        "architecture-api",
                        "## Endpoints et formats JSON\n"
                                + "Les services internes exposent des endpoints REST au format JSON UTF-8 avec pagination explicite (limit et offset) et horodatage ISO-8601.\n\n"
                                + "## Gestion des codes d'erreur HTTP\n"
                                + "Les erreurs de validation retournent un statut 400. Les authentifications invalides retournent 401. "
                                + "Les dépassements de quota ou de débit (rate limit) retournent HTTP 429 (Too Many Requests) et doivent être traités avec un délai d'attente progressif (backoff). "
                                + "Les erreurs internes inattendues retournent HTTP 500.\n\n"
                                + "## Résilience et intégration MCP\n"
                                + "Les connecteurs MCP doivent respecter la lecture seule pour les requêtes d'exploration et ne jamais exposer de secrets ou clés API dans les réponses JSON."
                ),
                new CorpusDoc(
                        "guide-securite.md",
                        "guide-securite",
                        "## Intégrité des stocks en base\n"
                                + "Aucune ligne de la table produits ne doit présenter un stock négatif ou nul non maîtrisé. "
                                + "Un stock négatif (par exemple -3 sur un écran ou un stock nul sur une webcam) constitue une anomalie critique d'inventaire à auditer sans modifier la base à l'aveugle.\n\n"
                                + "## Traitement des commandes BLOQUEE\n"
                                + "Toute commande enregistrée avec le statut BLOQUEE (en majuscules) doit être isolée dans le rapport d'audit de conformité "
                                + "avec son identifiant, son client, son produit, sa quantité et sa date_commande.\n\n"
                                + "## Conformité clients et requêtes SQL\n"
                                + "Les adresses email des clients doivent être valides et non dupliquées, et les segments clients respectent B2B ou B2C. "
                                + "Toute inspection de entreprise.db doit s'appuyer exclusivement sur des requêtes SELECT en lecture seule."
                )
        );
    }

    private static List<Map<String, Object>> ensureRagCorpusInDb(Connection db) throws SQLException {
        if (db == null) {
            return List.of();
        }
        try (Statement st = db.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS rag_documents ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "titre TEXT UNIQUE NOT NULL,"
                    + "categorie TEXT NOT NULL,"
                    + "contenu TEXT NOT NULL,"
                    + "sha256 TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS rag_chunks ("
                    + "id TEXT PRIMARY KEY,"
                    + "document_id INTEGER NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,"
                    + "source TEXT NOT NULL,"
                    + "chunk_index INTEGER NOT NULL,"
                    + "content TEXT NOT NULL,"
                    + "sha256 TEXT NOT NULL,"
                    + "embedding_json TEXT)");
        }

        int count = 0;
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) AS total FROM rag_documents")) {
            if (rs.next()) {
                count = rs.getInt("total");
            }
        }

        if (count == 0) {
            String nowIso = Instant.now().toString();
            String sqlInsert = "INSERT OR IGNORE INTO rag_documents (titre, categorie, contenu, sha256, updated_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = db.prepareStatement(sqlInsert)) {
                for (CorpusDoc doc : defaultDbDocuments()) {
                    ps.setString(1, doc.titre());
                    ps.setString(2, doc.categorie());
                    ps.setString(3, doc.contenu());
                    ps.setString(4, sha256Hex(doc.contenu()));
                    ps.setString(5, nowIso);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        List<Map<String, Object>> docs = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, titre, categorie, contenu, sha256, updated_at FROM rag_documents ORDER BY id ASC")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("titre", rs.getString("titre"));
                m.put("categorie", rs.getString("categorie"));
                m.put("contenu", rs.getString("contenu"));
                m.put("sha256", rs.getString("sha256"));
                m.put("updated_at", rs.getString("updated_at"));
                docs.add(m);
            }
        }
        return docs;
    }

    private static List<String> chunkCorpusText(String text, int chunkSize, int overlap) {
        if (text == null) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }
        int safeOverlap = Math.max(0, Math.min(overlap, chunkSize - 1));
        int step = Math.max(1, chunkSize - safeOverlap);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            String slice = normalized.substring(start, end).trim();
            if (!slice.isEmpty()) {
                chunks.add(slice);
            }
            if (end >= normalized.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Erreur SHA-256 : " + e.getMessage(), e);
        }
    }

    private static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double va = a.get(i);
            double vb = b.get(i);
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String httpPostJson(String url, String bearerToken, String jsonBody) throws IOException, InterruptedException {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalStateException("Clé API manquante dans l'environnement.");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Erreur HTTP " + response.statusCode() + " sur " + url + " : " + response.body());
        }
        return response.body();
    }

    private static List<List<Double>> embedWithJina(String jinaApiKey, List<String> inputs, String task)
            throws IOException, InterruptedException {
        if (jinaApiKey == null || jinaApiKey.isBlank()) {
            throw new IllegalStateException("Variable JINA_API_KEY absente dans .env (requise pour https://api.jina.ai/v1/embeddings).");
        }
        StringBuilder arrBuilder = new StringBuilder("[");
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) arrBuilder.append(",");
            arrBuilder.append(jsonString(inputs.get(i)));
        }
        arrBuilder.append("]");

        String payload = "{\"model\":" + jsonString(JINA_EMBEDDINGS_MODEL)
                + ",\"task\":" + jsonString(task)
                + ",\"input\":" + arrBuilder + "}";

        String rawResp = httpPostJson(JINA_EMBEDDINGS_URL, jinaApiKey, payload);
        return extractEmbeddingsFromJinaResponse(rawResp, inputs.size());
    }

    private static String askGroq(String groqApiKey, String prompt) throws IOException, InterruptedException {
        if (groqApiKey == null || groqApiKey.isBlank()) {
            throw new IllegalStateException("Variable GROQ_API_KEY absente dans .env.");
        }
        String payload = "{\"model\":" + jsonString(DEFAULT_GROQ_MODEL)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}]}";
        String rawResp = httpPostJson(GROQ_CHAT_URL, groqApiKey, payload);
        String content = extractGroqAssistantContent(rawResp);
        return (content == null || content.isBlank()) ? "Réponse vide de Groq." : content;
    }

    private static Map<String, Object> indexDbCorpusRag(Connection db, String jinaApiKey, boolean forceReembed)
            throws SQLException, IOException, InterruptedException {
        if (db == null) {
            throw new IllegalStateException("Base entreprise.db introuvable : RAG SQLite indisponible.");
        }
        List<Map<String, Object>> docs = ensureRagCorpusInDb(db);

        String upsertSql = "INSERT INTO rag_chunks (id, document_id, source, chunk_index, content, sha256, embedding_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET "
                + "document_id = excluded.document_id, source = excluded.source, chunk_index = excluded.chunk_index, "
                + "content = excluded.content, sha256 = excluded.sha256, embedding_json = excluded.embedding_json";

        int totalChunks = 0;
        int embeddedNow = 0;
        int reusedFromDb = 0;
        int dimension = 0;

        for (Map<String, Object> doc : docs) {
            int docId = ((Number) doc.get("id")).intValue();
            String titre = String.valueOf(doc.get("titre"));
            String contenu = String.valueOf(doc.get("contenu"));
            List<String> chunks = chunkCorpusText(contenu, 500, 75);

            record PendingChunk(String id, int docId, String source, int index, String content, String hash) {}
            List<PendingChunk> pending = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                totalChunks++;
                String chunkId = titre + "#" + i;
                String chunkText = chunks.get(i);
                String hash = sha256Hex(chunkText);

                String existingHash = null;
                String existingEmb = null;
                try (PreparedStatement sel = db.prepareStatement("SELECT sha256, embedding_json FROM rag_chunks WHERE id = ?")) {
                    sel.setString(1, chunkId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            existingHash = rs.getString("sha256");
                            existingEmb = rs.getString("embedding_json");
                        }
                    }
                }

                if (!forceReembed && hash.equals(existingHash) && existingEmb != null && !existingEmb.isBlank()) {
                    reusedFromDb++;
                    if (dimension == 0) {
                        List<Double> parsed = parseDoubleArray(existingEmb);
                        dimension = parsed.size();
                    }
                } else {
                    pending.add(new PendingChunk(chunkId, docId, titre, i, chunkText, hash));
                }
            }

            if (!pending.isEmpty()) {
                List<String> texts = pending.stream().map(PendingChunk::content).toList();
                List<List<Double>> vectors = embedWithJina(jinaApiKey, texts, "retrieval.passage");
                try (PreparedStatement up = db.prepareStatement(upsertSql)) {
                    for (int k = 0; k < pending.size(); k++) {
                        PendingChunk item = pending.get(k);
                        List<Double> vec = vectors.get(k);
                        if (dimension == 0) {
                            dimension = vec.size();
                        }
                        up.setString(1, item.id());
                        up.setInt(2, item.docId());
                        up.setString(3, item.source());
                        up.setInt(4, item.index());
                        up.setString(5, item.content());
                        up.setString(6, item.hash());
                        up.setString(7, formatDoubleList(vec));
                        up.addBatch();
                        embeddedNow++;
                    }
                    up.executeBatch();
                }
                Thread.sleep(150);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceStore", "entreprise.db (tables rag_documents & rag_chunks)");
        result.put("model", JINA_EMBEDDINGS_MODEL);
        result.put("documentsCount", docs.size());
        result.put("totalChunks", totalChunks);
        result.put("embeddedNow", embeddedNow);
        result.put("reusedFromDb", reusedFromDb);
        result.put("dimension", dimension);
        return result;
    }

    private static Map<String, Object> queryDbCorpusRag(
            Connection db,
            String jinaApiKey,
            String groqApiKey,
            String question,
            int topK,
            double minScore
    ) throws SQLException, IOException, InterruptedException {
        if (db == null) {
            throw new IllegalStateException("Base entreprise.db introuvable : RAG SQLite indisponible.");
        }
        String cleanQuestion = question == null ? "" : question.trim();
        if (cleanQuestion.isEmpty()) {
            throw new IllegalArgumentException("La question RAG ne peut pas être vide.");
        }

        ensureRagCorpusInDb(db);
        List<Map<String, Object>> rows = loadAllRagChunks(db);
        boolean needsIndex = rows.isEmpty() || rows.stream().anyMatch(r -> {
            Object emb = r.get("embedding_json");
            return emb == null || emb.toString().isBlank();
        });
        if (needsIndex) {
            indexDbCorpusRag(db, jinaApiKey, false);
            rows = loadAllRagChunks(db);
        }

        List<Double> queryVector = embedWithJina(jinaApiKey, List.of(cleanQuestion), "retrieval.query").get(0);

        List<RagChunk> scored = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String embStr = r.get("embedding_json") == null ? "[]" : r.get("embedding_json").toString();
            List<Double> vec = parseDoubleArray(embStr);
            double score = cosineSimilarity(queryVector, vec);
            scored.add(new RagChunk(
                    String.valueOf(r.get("id")),
                    String.valueOf(r.get("source")),
                    ((Number) r.get("chunk_index")).intValue(),
                    String.valueOf(r.get("content")),
                    score
            ));
        }

        scored.sort(Comparator.comparingDouble(RagChunk::score).reversed());
        List<RagChunk> topChunks = scored.subList(0, Math.min(Math.max(1, topK), scored.size()));
        double bestScore = topChunks.isEmpty() ? 0.0 : topChunks.get(0).score();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("question", cleanQuestion);
        output.put("bestScore", bestScore);
        output.put("minScore", minScore);
        output.put("sources", topChunks.stream().map(c -> Map.of(
                "id", c.id(),
                "source", c.source(),
                "chunkIndex", c.chunkIndex(),
                "content", c.content(),
                "score", c.score()
        )).toList());

        if (topChunks.isEmpty() || bestScore < minScore) {
            output.put("rejected", true);
            output.put("answer", "Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db.");
            return output;
        }

        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < topChunks.size(); i++) {
            RagChunk c = topChunks.get(i);
            if (i > 0) ctx.append("\n\n");
            ctx.append(String.format("[Extrait #%d | source: %s | score: %.4f]\n%s", i + 1, c.source(), c.score(), c.content()));
        }

        String prompt = "Tu es un assistant strictement basé sur le corpus documentaire extrait de entreprise.db.\n"
                + "Règles impératives :\n"
                + "1. Utilise UNIQUEMENT les extraits fournis ci-dessous. N’invente aucune information externe.\n"
                + "2. Cite explicitement le nom du document source sous la forme [source: nom-fichier.md].\n"
                + "3. Si la réponse ne figure pas dans les extraits, réponds exactement : \"Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db.\"\n\n"
                + "EXTRAITS DU CORPUS (entreprise.db) :\n"
                + ctx + "\n\n"
                + "QUESTION : " + cleanQuestion;

        String answer = askGroq(groqApiKey, prompt);
        output.put("rejected", false);
        output.put("answer", answer);
        return output;
    }

    private static List<Map<String, Object>> loadAllRagChunks(Connection db) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("source", rs.getString("source"));
                m.put("chunk_index", rs.getInt("chunk_index"));
                m.put("content", rs.getString("content"));
                m.put("embedding_json", rs.getString("embedding_json"));
                list.add(m);
            }
        }
        return list;
    }

    private static Map<String, Skill> loadMdrSkills(Path rootDir) {
        Path promptsDir = rootDir.resolve(".prompts");
        Map<String, Skill> map = new LinkedHashMap<>();
        if (!Files.isDirectory(promptsDir)) {
            return map;
        }
        try (var stream = Files.list(promptsDir)) {
            List<Path> mdrFiles = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".mdr"))
                    .sorted()
                    .toList();
            for (Path file : mdrFiles) {
                String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (raw.isEmpty()) continue;
                String baseName = file.getFileName().toString().replaceFirst("\\.mdr$", "");
                String safeName = baseName.replaceAll("[^a-zA-Z0-9_-]", "_");
                String description = "Skill " + safeName + " (.mdr)";
                String template = raw;

                Matcher fm = Pattern.compile("^---\\s*\\R([\\s\\S]*?)\\R---\\s*\\R([\\s\\S]*)$").matcher(raw);
                if (fm.matches()) {
                    String header = fm.group(1);
                    template = fm.group(2).trim();
                    Matcher desc = Pattern.compile("^description:\\s*(.+)$", Pattern.MULTILINE).matcher(header);
                    if (desc.find()) {
                        description = desc.group(1).trim();
                    }
                }
                map.put(safeName, new Skill(safeName, description, template));
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    private static int handleCli(
            String[] args,
            Connection db,
            Map<String, Skill> skills,
            String jinaApiKey,
            String groqApiKey
    ) {
        boolean isPrompt = false;
        List<String> clean = new ArrayList<>();
        for (String a : args) {
            if ("--prompt".equals(a)) {
                isPrompt = true;
            } else {
                clean.add(a);
            }
        }
        String cmd = clean.size() > 0 ? clean.get(0) : "";
        String target = clean.size() > 1 ? clean.get(1) : "";

        try {
            if ("sql".equals(cmd)) {
                System.out.println(toJson(executeSafeSelect(db, target)));
                return 0;
            }
            if ("rag-index".equals(cmd)) {
                boolean force = clean.contains("--force");
                System.out.println(toJson(indexDbCorpusRag(db, jinaApiKey, force)));
                return 0;
            }
            if ("rag-ask".equals(cmd)) {
                System.out.println(toJson(queryDbCorpusRag(db, jinaApiKey, groqApiKey, target, RAG_TOP_K, RAG_MIN_SCORE)));
                return 0;
            }
            if (!skills.containsKey(cmd)) {
                String avail = skills.isEmpty() ? "(aucun .mdr dans .prompts/)" : String.join(", ", skills.keySet());
                String extra = db != null ? ", sql, rag-index, rag-ask" : "";
                System.err.println("Skill introuvable : '" + cmd + "'\nDisponibles : " + avail + extra);
                return 1;
            }

            String input = target;
            if (!target.isEmpty()) {
                Path p = Paths.get(target);
                if (Files.isRegularFile(p)) {
                    input = Files.readString(p, StandardCharsets.UTF_8);
                }
            }
            String compiled = skills.get(cmd).compile(input);
            if (isPrompt) {
                System.out.println(compiled);
                return 0;
            }
            System.out.println(askGroq(groqApiKey, compiled));
            return 0;
        } catch (Exception e) {
            System.err.println("[Erreur] " + e.getMessage());
            return 1;
        }
    }

    private static void runMcpStdioLoop(Connection db, Map<String, Skill> skills, String jinaApiKey, String groqApiKey) {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String method = extractJsonString(trimmed, "method");
                if (method == null) continue;
                String idRaw = extractJsonIdRaw(trimmed);

                try {
                    switch (method) {
                        case "initialize" -> sendRpc(out, idRaw,
                                "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"prompts\":{},\"tools\":{},\"resources\":{}},\"serverInfo\":{\"name\":\"mcp-unified-java\",\"version\":\"1.1.0\"}}",
                                null);
                        case "notifications/initialized" -> {
                        }
                        case "prompts/list" -> {
                            List<Map<String, Object>> prompts = new ArrayList<>();
                            for (Skill s : skills.values()) {
                                prompts.add(Map.of(
                                        "name", s.name(),
                                        "description", s.description(),
                                        "arguments", List.of(Map.of("name", "input", "description", "Contexte {{input}}", "required", false))
                                ));
                            }
                            sendRpc(out, idRaw, toJson(Map.of("prompts", prompts)), null);
                        }
                        case "prompts/get" -> {
                            String pName = extractParamString(trimmed, "name");
                            if (pName == null || !skills.containsKey(pName)) {
                                sendRpc(out, idRaw, null, "{\"code\":-32601,\"message\":" + jsonString("Prompt introuvable : " + pName) + "}");
                            } else {
                                String argInput = extractArgumentString(trimmed, "input");
                                String compiled = skills.get(pName).compile(argInput == null ? "" : argInput);
                                String res = toJson(Map.of(
                                        "description", skills.get(pName).description(),
                                        "messages", List.of(Map.of("role", "user", "content", Map.of("type", "text", "text", compiled)))
                                ));
                                sendRpc(out, idRaw, res, null);
                            }
                        }
                        case "tools/list" -> {
                            List<Map<String, Object>> tools = new ArrayList<>();
                            for (Skill s : skills.values()) {
                                tools.add(Map.of(
                                        "name", s.name(),
                                        "description", "[Groq] " + s.description(),
                                        "inputSchema", Map.of(
                                                "type", "object",
                                                "properties", Map.of("input", Map.of("type", "string", "description", "Données à analyser")),
                                                "required", List.of("input")
                                        )
                                ));
                            }
                            if (db != null) {
                                tools.add(Map.of(
                                        "name", "query_database",
                                        "description", "Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).",
                                        "inputSchema", Map.of(
                                                "type", "object",
                                                "properties", Map.of("sql", Map.of("type", "string", "minLength", 10, "description", "Requête SQL SELECT valide")),
                                                "required", List.of("sql")
                                        )
                                ));
                                tools.add(Map.of(
                                        "name", "rag_search_db_corpus",
                                        "description", "Recherche RAG (Jina jina-embeddings-v3 + Groq) uniquement dans le corpus documentaire de entreprise.db.",
                                        "inputSchema", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "question", Map.of("type", "string", "minLength", 3, "description", "Question métier à vérifier dans le corpus de entreprise.db"),
                                                        "topK", Map.of("type", "number", "description", "Nombre maximum d'extraits retournés (défaut: 3)")
                                                ),
                                                "required", List.of("question")
                                        )
                                ));
                                tools.add(Map.of(
                                        "name", "rag_index_db_corpus",
                                        "description", "Indexe ou met à jour les embeddings Jina (jina-embeddings-v3) du corpus documentaire de entreprise.db.",
                                        "inputSchema", Map.of(
                                                "type", "object",
                                                "properties", Map.of("force", Map.of("type", "boolean", "description", "Force le recalcul complet (défaut: false)")),
                                                "required", List.of()
                                        )
                                ));
                            }
                            sendRpc(out, idRaw, toJson(Map.of("tools", tools)), null);
                        }
                        case "tools/call" -> {
                            String tName = extractParamString(trimmed, "name");
                            if ("query_database".equals(tName)) {
                                try {
                                    String sql = extractArgumentString(trimmed, "sql");
                                    List<Map<String, Object>> rows = executeSafeSelect(db, sql);
                                    sendRpc(out, idRaw, toJson(Map.of("content", List.of(Map.of("type", "text", "text", toJson(rows))))), null);
                                } catch (Exception e) {
                                    sendRpc(out, idRaw, toJson(Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", e.getMessage())))), null);
                                }
                            } else if ("rag_search_db_corpus".equals(tName)) {
                                try {
                                    String q = extractArgumentString(trimmed, "question");
                                    Map<String, Object> res = queryDbCorpusRag(db, jinaApiKey, groqApiKey, q, RAG_TOP_K, RAG_MIN_SCORE);
                                    sendRpc(out, idRaw, toJson(Map.of("content", List.of(Map.of("type", "text", "text", toJson(res))))), null);
                                } catch (Exception e) {
                                    sendRpc(out, idRaw, toJson(Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", e.getMessage())))), null);
                                }
                            } else if ("rag_index_db_corpus".equals(tName)) {
                                try {
                                    String forceStr = extractArgumentString(trimmed, "force");
                                    boolean force = "true".equalsIgnoreCase(forceStr);
                                    Map<String, Object> stats = indexDbCorpusRag(db, jinaApiKey, force);
                                    sendRpc(out, idRaw, toJson(Map.of("content", List.of(Map.of("type", "text", "text", toJson(stats))))), null);
                                } catch (Exception e) {
                                    sendRpc(out, idRaw, toJson(Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", e.getMessage())))), null);
                                }
                            } else if (tName != null && skills.containsKey(tName)) {
                                try {
                                    String argInput = extractArgumentString(trimmed, "input");
                                    String compiled = skills.get(tName).compile(argInput == null ? "" : argInput);
                                    String reply = askGroq(groqApiKey, compiled);
                                    sendRpc(out, idRaw, toJson(Map.of("content", List.of(Map.of("type", "text", "text", reply)))), null);
                                } catch (Exception e) {
                                    sendRpc(out, idRaw, toJson(Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", e.getMessage())))), null);
                                }
                            } else {
                                sendRpc(out, idRaw, null, "{\"code\":-32601,\"message\":" + jsonString("Outil introuvable : " + tName) + "}");
                            }
                        }
                        case "resources/list" -> {
                            List<Map<String, Object>> resources = new ArrayList<>();
                            for (Skill s : skills.values()) {
                                resources.add(Map.of(
                                        "uri", "skill://" + s.name(),
                                        "name", s.name() + ".mdr",
                                        "description", s.description(),
                                        "mimeType", "text/markdown"
                                ));
                            }
                            if (db != null) {
                                resources.add(Map.of(
                                        "uri", "db://schema",
                                        "name", "Schéma SQLite (entreprise.db)",
                                        "description", "Structure DDL (CREATE TABLE) de entreprise.db",
                                        "mimeType", "text/plain"
                                ));
                                resources.add(Map.of(
                                        "uri", "rag://db-corpus",
                                        "name", "Corpus Documentaire SQLite (entreprise.db)",
                                        "description", "Documents métier stockés dans entreprise.db pour le moteur RAG",
                                        "mimeType", "application/json"
                                ));
                            }
                            sendRpc(out, idRaw, toJson(Map.of("resources", resources)), null);
                        }
                        case "resources/templates/list" -> sendRpc(out, idRaw, "{\"resourceTemplates\":[]}", null);
                        case "resources/read" -> {
                            String uri = extractParamString(trimmed, "uri");
                            if ("db://schema".equals(uri) && db != null) {
                                String content = toJson(Map.of("contents", List.of(Map.of("uri", "db://schema", "mimeType", "text/plain", "text", getDatabaseSchema(db)))));
                                sendRpc(out, idRaw, content, null);
                            } else if ("rag://db-corpus".equals(uri) && db != null) {
                                List<Map<String, Object>> docs = ensureRagCorpusInDb(db);
                                String content = toJson(Map.of("contents", List.of(Map.of("uri", "rag://db-corpus", "mimeType", "application/json", "text", toJson(docs)))));
                                sendRpc(out, idRaw, content, null);
                            } else if (uri != null && uri.startsWith("skill://") && skills.containsKey(uri.substring(8))) {
                                Skill s = skills.get(uri.substring(8));
                                String content = toJson(Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "text/markdown", "text", s.template()))));
                                sendRpc(out, idRaw, content, null);
                            } else {
                                sendRpc(out, idRaw, null, "{\"code\":-32602,\"message\":" + jsonString("Ressource inconnue : " + uri) + "}");
                            }
                        }
                        case "ping" -> sendRpc(out, idRaw, "{}", null);
                        default -> {
                            if (idRaw != null) {
                                sendRpc(out, idRaw, null, "{\"code\":-32601,\"message\":" + jsonString("Méthode inconnue : " + method) + "}");
                            }
                        }
                    }
                } catch (Exception e) {
                    if (idRaw != null) {
                        sendRpc(out, idRaw, null, "{\"code\":-32603,\"message\":" + jsonString(e.getMessage()) + "}");
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void sendRpc(PrintWriter out, String idRaw, String resultJson, String errorJson) {
        String effectiveId = (idRaw == null || idRaw.isBlank()) ? "null" : idRaw;
        if (errorJson != null) {
            out.println("{\"jsonrpc\":\"2.0\",\"id\":" + effectiveId + ",\"error\":" + errorJson + "}");
        } else {
            out.println("{\"jsonrpc\":\"2.0\",\"id\":" + effectiveId + ",\"result\":" + (resultJson == null ? "null" : resultJson) + "}");
        }
    }

    private static List<List<Double>> extractEmbeddingsFromJinaResponse(String rawJson, int expectedCount) {
        List<List<Double>> list = new ArrayList<>();
        Matcher m = Pattern.compile("\"embedding\"\\s*:\\s*\\[([^\\]]+)\\]").matcher(rawJson);
        while (m.find()) {
            list.add(parseDoubleArray("[" + m.group(1) + "]"));
        }
        if (list.size() != expectedCount) {
            throw new IllegalStateException("Nombre de vecteurs Jina inattendu : " + list.size() + " / " + expectedCount);
        }
        return list;
    }

    private static String extractGroqAssistantContent(String rawJson) {
        Matcher m = Pattern.compile("\"message\"\\s*:\\s*\\{[\\s\\S]*?\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(rawJson);
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        return null;
    }

    private static List<Double> parseDoubleArray(String jsonArray) {
        List<Double> res = new ArrayList<>();
        if (jsonArray == null) return res;
        String inner = jsonArray.trim();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1).trim();
        }
        if (inner.isEmpty()) return res;
        for (String part : inner.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                try {
                    res.add(Double.parseDouble(p));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return res;
    }

    private static String formatDoubleList(List<Double> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(vec.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String extractJsonIdRaw(String json) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?|true|false|null)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    private static String extractParamString(String json, String key) {
        Matcher paramsBlock = Pattern.compile("\"params\"\\s*:\\s*\\{([\\s\\S]*)\\}").matcher(json);
        String scope = paramsBlock.find() ? paramsBlock.group(1) : json;
        return extractJsonString(scope, key);
    }

    private static String extractArgumentString(String json, String key) {
        Matcher argsBlock = Pattern.compile("\"arguments\"\\s*:\\s*\\{([\\s\\S]*?)\\}").matcher(json);
        String scope = argsBlock.find() ? argsBlock.group(1) : json;
        return extractJsonString(scope, key);
    }

    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                out.append("\\u").append(hex);
                                i += 4;
                            }
                        }
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
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
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return jsonString(s);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(jsonString(String.valueOf(entry.getKey()))).append(":").append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return jsonString(String.valueOf(value));
    }
}