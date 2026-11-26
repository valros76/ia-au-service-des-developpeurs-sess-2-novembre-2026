/**
 * - Nécessite une clé env : GROQ_API_KEY="votreCleGroq"
 */

/**
 * - Pour tester le serveur en CLI : 
 * - exécution (exemple de commande) : java ServerMcp.java revue_php85 exemple_sale.php
 * - 
 * - Prévisualisation (sans consommation API) : java ServerMcp.java revue_php85 exemple_sale.php --prompt
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

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath();
        Map<String, String> env = loadDotEnv(projectRoot.resolve(".env"));

        String groqApiKey = env.getOrDefault("GROQ_API_KEY",
            Objects.requireNonNullElse(System.getenv("GROQ_API_KEY"), ""));

        if (groqApiKey.isBlank()) {
            System.err.println("[Erreur fatale] Variable GROQ_API_KEY absente (vérifie ton .env).");
            System.exit(1);
        }

        Map<String, Skill> skills = loadMdrSkills(projectRoot.resolve(".prompts"));

        // ====================================================================
        // MODE CLI DIRECT (Tests utilisateurs dans le terminal)
        // ====================================================================
        if (args.length > 0) {
            List<String> argList = Arrays.asList(args);
            boolean isPrompt = argList.contains("--prompt");
            List<String> cleanArgs = argList.stream().filter(a -> !a.equals("--prompt")).toList();

            String skillName = cleanArgs.isEmpty() ? "" : cleanArgs.get(0);
            String target = cleanArgs.size() > 1 ? cleanArgs.get(1) : null;

            Skill skill = skills.get(skillName);
            if (skill == null) {
                String avail = skills.isEmpty() ? "(aucun .mdr dans .prompts/)" : String.join(", ", skills.keySet());
                System.err.println("Skill introuvable : '" + skillName + "'\nDisponibles : " + avail);
                System.exit(1);
            }

            String input = "";
            if (target != null && Files.isRegularFile(Path.of(target))) {
                input = Files.readString(Path.of(target));
            } else if (target != null) {
                input = target;
            } else if (System.console() == null) {
                input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String compiled = skill.compile(input);
            if (isPrompt) {
                System.out.println(compiled);
                return;
            }

            System.out.println(askGroq(groqApiKey, compiled));
            return;
        }

        // ====================================================================
        // MOTEUR JSON-RPC 2.0 / MCP (STDIO)
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
                    {"protocolVersion":"2024-11-05","capabilities":{"prompts":{},"tools":{},"resources":{}},"serverInfo":{"name":"mcp-initial-java","version":"1.0.0"}}""");

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
                    String text = s.compile(input);
                    sendResult(id, """
                        {"description":%s,"messages":[{"role":"user","content":{"type":"text","text":%s}}]}"""
                        .formatted(jsonStr(s.description), jsonStr(text)));
                }

                case "tools/list" -> {
                    List<String> items = new ArrayList<>();
                    for (Skill s : skills.values()) {
                        items.add("""
                            {"name":%s,"description":%s,"inputSchema":{"type":"object","properties":{"input":{"type":"string","description":"Données à analyser"}},"required":["input"]}}"""
                            .formatted(jsonStr(s.name), jsonStr("[Groq] " + s.description)));
                    }
                    sendResult(id, "{\"tools\":[" + String.join(",", items) + "]}");
                }

                case "tools/call" -> {
                    String name = extractJsonString(line, "name");
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
                        sendError(id, -32000, e.getMessage());
                    }
                }

                case "resources/list" -> {
                    List<String> items = new ArrayList<>();
                    for (Skill s : skills.values()) {
                        items.add("""
                            {"uri":%s,"name":%s,"description":%s,"mimeType":"text/markdown"}"""
                            .formatted(jsonStr("skill://" + s.name), jsonStr(s.name + ".mdr"), jsonStr(s.description)));
                    }
                    sendResult(id, "{\"resources\":[" + String.join(",", items) + "]}");
                }

                case "resources/read" -> {
                    String uri = Objects.requireNonNullElse(extractJsonString(line, "uri"), "");
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

    private static String askGroq(String apiKey, String prompt) throws Exception {
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
        return m.group(1).replace("\\n", "\n").replace("\\r", "\r")
            .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}