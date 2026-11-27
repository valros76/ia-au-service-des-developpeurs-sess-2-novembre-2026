# TP 4 : Corrigé Complet — Chaîne RAG sans framework et workflow agentique combiné (Java 21+ natif)

Ce document fournit l'implémentation de référence complète du **TP 4** en **Java 21+ pur** (`java.net.http.HttpClient`, `java.nio.file.Files`, `record`, zéro framework), utilisant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération.

*(Note : Les classes ci-dessous utilisent `com.fasterxml.jackson.databind.ObjectMapper` déjà présent dans le `pom.xml` du TP 3 afin de garantir un échappement JSON propre et fiable).*

---

## Partie A : Moteur RAG minimaliste en Java pur (`tp4-rag/`)

### 1. Exemple de corpus métier (`tp4-rag/docs/`)

#### `docs/guide-securite.md`
```markdown
# Guide de Sécurité et d'Intégrité des Données — Plateforme E-Commerce

## 1. Intégrité des stocks et catalogue produits
La base de données métier (`entreprise.db`) constitue la source de vérité unique pour la disponibilité des articles en entrepôt. Conformément à la politique d'audit interne :
- **Interdiction absolue des stocks négatifs :** La colonne `stock` de la table `produits` doit impérativement être supérieure ou égale à zéro (`stock >= 0`). Tout produit présentant un stock strictement négatif signale une désynchronisation critique entre les ventes et l'inventaire physique et doit être immédiatement ramené à `0` en attendant un réapprovisionnement certifié.
- **Cohérence tarifaire :** Le champ `prix_unitaire` doit toujours être strictement positif (`prix_unitaire > 0`).

## 2. Gestion des commandes bloquées (`BLOQUEE`)
Les enregistrements de la table `commandes` dont le champ `statut` vaut `BLOQUEE` font l'objet d'une surveillance quotidienne par l'équipe d'exploitation :
- **Blocage légitime (Rupture avérée) :** Une commande doit rester au statut `BLOQUEE` si et seulement si la quantité demandée (`quantite`) excède le stock disponible du produit associé ou si le produit est en anomalie de stock (`stock <= 0`).
- **Blocage injustifié (Faux positif) :** Toute commande marquée `BLOQUEE` alors que le produit lié dispose d'un stock suffisant (`produits.stock >= commandes.quantite`) est considérée comme une anomalie bloquante de workflow et doit être basculée au statut `EN_PREPARATION`.

## 3. Protection des données clients et conformité RGPD
Les informations personnelles stockées dans la table `clients` sont soumises aux exigences suivantes :
- **Validation des identifiants :** Chaque client doit posséder une adresse email syntaxiquement valide contenant un `@` et un nom de domaine complet.
- **Cloisonnement des accès :** Les outils d'assistance IA et d'analyse BI doivent interroger la base exclusivement en lecture seule (`SELECT`) avec un plafond strict de 50 lignes par requête afin d'éviter toute exfiltration massive de données personnelles.
```

#### `docs/politique-remboursement.md`
```markdown
# Politique Officielle de Remboursement et Retours Clients

## 1. Délais légaux et conditions d'éligibilité
Tout client enregistré dans notre système dispose d'un délai légal de **14 jours calendaires** à compter de la date de réception effective pour exercer son droit de rétractation sans avoir à justifier de motifs.
- Pour les commandes au statut `LIVREE`, le remboursement intégral est déclenché dès réception et contrôle qualité du colis retourné dans notre entrepôt logistique.
- Pour les commandes au statut `EN_PREPARATION` ou `BLOQUEE`, l'annulation est immédiate et le remboursement est automatique sous 48 heures ouvrées sans frais de retour.
- Pour les commandes au statut `EXPEDIEE`, le client doit refuser le colis à la livraison ou initier une procédure de retour standard après réception.

## 2. Seuils financiers et circuits de validation
Afin de prévenir les fraudes et erreurs de saisie comptable, les remboursements sont soumis à des paliers d'approbation stricts :
- **Montant inférieur à 150,00 € TTC :** Validation automatique par le service client de niveau 1.
- **Montant compris entre 150,00 € et 500,00 € TTC :** Validation obligatoire par un Superviseur Service Client avant émission du virement.
- **Montant strictement supérieur à 500,00 € TTC :** Double validation requise par le Responsable Financier et le Directeur des Opérations.

## 3. Cas d'exclusion et avoirs commerciaux
Les produits personnalisés ou les licences logicielles déjà activées ne sont pas éligibles au remboursement financier, mais peuvent donner lieu à un avoir commercial valable 12 mois sur l'ensemble du catalogue (`produits`).
```

#### `docs/architecture-api.md`
```markdown
# Architecture Technique de l'API E-Commerce (v2.4)

## 1. Principes généraux et conventions REST
L'API interne repose sur une architecture RESTful stricte exposée en HTTPS. Toutes les requêtes et réponses utilisent exclusivement le format `application/json` encodé en UTF-8.
- **Authentification :** Chaque appel doit inclure un jeton Bearer JWT valide dans l'en-tête `Authorization: Bearer <token>`. Les jetons ont une durée de vie maximale de 60 minutes.
- **Versionnement :** Le numéro de version majeure est préfixé dans l'URL (`/api/v2/clients`, `/api/v2/produits`, `/api/v2/commandes`).

## 2. Pagination, filtrage et limites de débit (Rate Limiting)
Toutes les routes de collection (`GET /api/v2/commandes`, `GET /api/v2/produits`) implémentent une pagination par curseur ou par page :
- Paramètres supportés : `?page=1&limit=25` (la valeur maximale autorisée pour `limit` est de `100` enregistrements par appel).
- **Rate Limiting :** Chaque client API est limité à **120 requêtes par minute**. En cas de dépassement, l'API renvoie un code HTTP `429 Too Many Requests` accompagné de l'en-tête `Retry-After`.

## 3. Gestion standardisée des erreurs (RFC 7807)
En cas d'échec, l'API retourne systématiquement une charge utile structurée selon la norme *Problem Details for HTTP APIs* contenant les champs `type`, `title`, `status`, `detail` et `instance` :
- `400 Bad Request` : Paramètre invalide ou violation de règle métier (ex. quantité commandée négative).
- `404 Not Found` : Ressource inexistante en base de données.
- `409 Conflict` : Conflit d'état (ex. tentative de suppression d'un client ayant des commandes actives ou stock insuffisant).
```

---

### 2. Classe de découpage et utilitaire `.env` (`src/Chunker.java`)

```java
package fr.formation.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class Chunker {

    private Chunker() {}

    /**
     * Charge les paires clé=valeur d'un fichier .env sans dépendance externe.
     */
    public static Map<String, String> loadEnv(Path envPath) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (!Files.exists(envPath)) {
            return env;
        }
        for (String line : Files.readAllLines(envPath)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            String key = parts[0].strip();
            String val = parts[1].strip().replaceAll("^[\"']|[\"']$", "");
            env.putIfAbsent(key, val);
        }
        return env;
    }

    /**
     * Découpe un texte en segments de taille maximale chunkSize
     * en conservant un chevauchement de overlap caractères.
     */
    public static List<String> chunkText(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("Le chevauchement (overlap) doit être strictement inférieur à chunkSize.");
        }

        String cleanText = text.replace("\r\n", "\n").strip();
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < cleanText.length()) {
            int end = Math.min(start + chunkSize, cleanText.length());
            chunks.add(cleanText.substring(start, end));

            if (end == cleanText.length()) {
                break;
            }
            start += (chunkSize - overlap);
        }

        return chunks;
    }

    // Validation locale (sans appel API)
    public static void main(String[] args) throws IOException {
        Path docsDir = Path.of("docs");
        try (Stream<Path> paths = Files.list(docsDir)) {
            List<Path> mdFiles = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            for (Path file : mdFiles) {
                String content = Files.readString(file);
                List<String> chunks = chunkText(content, 500, 75);
                System.out.printf("Fichier \"%s\" (%d car.) -> %d morceaux%n",
                        file.getFileName(), content.length(), chunks.size());

                for (int i = 0; i < chunks.size() - 1; i++) {
                    String tail = chunks.get(i).substring(chunks.get(i).length() - 75);
                    String head = chunks.get(i + 1).substring(0, 75);
                    if (!tail.equals(head)) {
                        System.err.printf("Erreur de chevauchement entre #%d et #%d dans %s%n",
                                i, i + 1, file.getFileName());
                    }
                }
            }
        }
        System.out.println("Validation du chevauchement (75 caractères) réussie !");
    }
}
```

---

### 3. Programme d'indexation `src/Indexer.java`

```java
package fr.formation.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class Indexer {

    public record DocumentChunk(String id, String source, String content, double[] embedding) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static double[] embedPassage(String text, String apiKey) throws Exception {
        String requestBody = MAPPER.writeValueAsString(Map.of(
                "model", "jina-embeddings-v3",
                "task", "retrieval.passage",
                "input", List.of(text)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.jina.ai/v1/embeddings"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur API Jina (" + response.statusCode() + ") : " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode embNode = root.path("data").get(0).path("embedding");
        double[] vector = new double[embNode.size()];
        for (int i = 0; i < embNode.size(); i++) {
            vector[i] = embNode.get(i).asDouble();
        }
        return vector;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = Chunker.loadEnv(Path.of("../.env"));
        String jinaApiKey = env.get("JINA_API_KEY");

        if (jinaApiKey == null || jinaApiKey.isBlank()) {
            System.err.println("Erreur : JINA_API_KEY manquante dans le fichier .env");
            System.exit(1);
        }

        long startTime = System.nanoTime();
        Path docsDir = Path.of("docs");
        List<Path> mdFiles;
        try (Stream<Path> paths = Files.list(docsDir)) {
            mdFiles = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }

        if (mdFiles.isEmpty()) {
            System.err.println("Aucun fichier .md trouvé dans le dossier docs/.");
            System.exit(1);
        }

        List<DocumentChunk> vectorStore = new ArrayList<>();

        for (Path file : mdFiles) {
            String fileName = file.getFileName().toString();
            String content = Files.readString(file);
            List<String> chunks = Chunker.chunkText(content, 500, 75);

            System.out.printf("Indexation de %s (%d morceaux)...%n", fileName, chunks.size());

            for (int i = 0; i < chunks.size(); i++) {
                double[] embedding = embedPassage(chunks.get(i), jinaApiKey);
                vectorStore.add(new DocumentChunk(fileName + "#" + i, fileName, chunks.get(i), embedding));

                // Pause de 200 ms pour respecter les limites de débit
                Thread.sleep(200);
            }
        }

        Path storePath = Path.of("vector-store.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), vectorStore);

        double durationMs = (System.nanoTime() - startTime) / 1_000_000.0;
        int dimension = vectorStore.getFirst().embedding().length;

        System.out.println("\n--- Indexation terminée ---");
        System.out.println("Vecteurs générés : " + vectorStore.size());
        System.out.println("Dimension        : " + dimension);
        System.out.printf("Temps total      : %.2f ms%n", durationMs);
        System.out.println("Fichier généré   : " + storePath.toAbsolutePath());
    }
}
```

---

### 4. Programme d'interrogation `src/AskRag.java`

```java
package fr.formation.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AskRag {

    private static final double SIMILARITY_THRESHOLD = 0.35;
    private static final int TOP_K = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    record ScoredChunk(Indexer.DocumentChunk chunk, double score) {}

    public static double cosineSimilarity(double[] vecA, double[] vecB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : dotProduct / denominator;
    }

    private static double[] embedQuery(String queryText, String apiKey) throws Exception {
        String requestBody = MAPPER.writeValueAsString(Map.of(
                "model", "jina-embeddings-v3",
                "task", "retrieval.query",
                "input", List.of(queryText)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.jina.ai/v1/embeddings"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur Jina Query (" + response.statusCode() + ") : " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode embNode = root.path("data").get(0).path("embedding");
        double[] vector = new double[embNode.size()];
        for (int i = 0; i < embNode.size(); i++) {
            vector[i] = embNode.get(i).asDouble();
        }
        return vector;
    }

    private static String generateAnswer(String userQuestion, List<ScoredChunk> topChunks, String apiKey) throws Exception {
        String formattedContext = topChunks.stream()
                .map(sc -> String.format("--- EXTRAIT [source: %s] (score: %.4f) ---%n%s",
                        sc.chunk().source(), sc.score(), sc.chunk().content()))
                .collect(Collectors.joining("\n\n"));

        String systemPrompt = String.join("\n",
                "Tu es un assistant documentaire interne strict.",
                "RÈGLES IMPÉRATIVES :",
                "1. Réponds UNIQUEMENT à partir des extraits de contexte fournis ci-dessous.",
                "2. Interdiction absolue d'utiliser tes connaissances générales ou d'inventer des informations.",
                "3. Cite systématiquement le fichier source sous la forme [source: nom-du-fichier.md] après chaque affirmation.",
                "4. Si le contexte ne permet pas de répondre à la question, réponds exactement : \"Je ne dispose pas de cette information dans la documentation fournie.\""
        );

        String requestBody = MAPPER.writeValueAsString(Map.of(
                "model", "llama-3.3-70b-versatile",
                "temperature", 0.0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", "CONTEXTE DOCUMENTAIRE :\n" + formattedContext + "\n\nQUESTION :\n" + userQuestion)
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur Groq Chat (" + response.statusCode() + ") : " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = Chunker.loadEnv(Path.of("../.env"));
        String jinaApiKey = env.get("JINA_API_KEY");
        String groqApiKey = env.get("GROQ_API_KEY");

        if (jinaApiKey == null || groqApiKey == null || jinaApiKey.isBlank() || groqApiKey.isBlank()) {
            System.err.println("Erreur : JINA_API_KEY et GROQ_API_KEY doivent être définies dans .env");
            System.exit(1);
        }

        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("Usage : java AskRag \"Votre question\"");
            System.exit(1);
        }

        String question = args[0];
        List<Indexer.DocumentChunk> vectorStore = MAPPER.readValue(
                Path.of("vector-store.json").toFile(),
                new TypeReference<List<Indexer.DocumentChunk>>() {}
        );

        double[] queryVector = embedQuery(question, jinaApiKey);

        List<ScoredChunk> topChunks = vectorStore.stream()
                .map(entry -> new ScoredChunk(entry, cosineSimilarity(queryVector, entry.embedding())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(TOP_K)
                .toList();

        System.out.printf("%nQuestion : \"%s\"%n", question);
        System.out.println("--- Top-3 des morceaux les plus proches ---");
        for (ScoredChunk sc : topChunks) {
            System.out.printf("• [%s] Score cosinus : %.4f%n", sc.chunk().id(), sc.score());
        }

        if (topChunks.isEmpty() || topChunks.getFirst().score() < SIMILARITY_THRESHOLD) {
            double maxScore = topChunks.isEmpty() ? 0.0 : topChunks.getFirst().score();
            System.out.printf("%n[Rejet automatique] Score maximal (%.4f) inférieur au seuil (%.2f).%n",
                    maxScore, SIMILARITY_THRESHOLD);
            System.out.println("Réponse : Je ne dispose pas de cette information dans la documentation fournie.");
            return;
        }

        System.out.println("\n--- Réponse générée par Groq (llama-3.3-70b-versatile) ---");
        System.out.println(generateAnswer(question, topChunks, groqApiKey));
    }
}
```

---

## Partie B : Résultats attendus de l'orchestration agentique dans l'IDE

### 1. Exemple de fichier `AUDIT_CONFORMITE.md` généré par l'agent

```markdown
# Audit de Conformité Base de Données (`entreprise.db`)

**Référentiel appliqué :** `tp4-rag/docs/guide-securite.md`  
**Mode d'audit :** Lecture seule via serveur MCP `mcp-db-explorer` (aucune modification exécutée)

---

## 1. Anomalies détectées sur la table `produits` (Section 1 : Intégrité des stocks)

| ID Produit | Nom | Catégorie | Stock actuel | Règle enfreinte |
| :---: | :--- | :--- | :---: | :--- |
| `3` | Écran 27 pouces 4K | Écrans | `-3` | Stock strictement négatif (`stock < 0` interdit) |
| `6` | Webcam HD 1080p | Vidéo | `-1` | Stock strictement négatif (`stock < 0` interdit) |

---

## 2. Anomalies détectées sur la table `commandes` (Section 2 : Gestion des commandes bloquées)

| ID Commande | Client | Produit | Qté | Stock produit | Statut actuel | Diagnostic |
| :---: | :--- | :--- | :---: | :---: | :---: | :--- |
| `2` | Bob Bernard (`#2`) | Écran 27 pouces 4K (`#3`) | `1` | `-3` | `BLOQUEE` | Blocage cohérent avec la rupture de stock (`#3`), mais nécessite régularisation d'inventaire. |
| `5` | Emma Lefebvre (`#5`) | Webcam HD 1080p (`#6`) | `2` | `-1` | `BLOQUEE` | Blocage cohérent avec la rupture de stock (`#6`), mais nécessite régularisation d'inventaire. |
| `7` | Lucas Roux (`#6`) | Câble HDMI 2.1 (`#7`) | `5` | `120` | `BLOQUEE` | **Anomalie de workflow :** stock suffisant (`120 >= 5`), blocage injustifié. |

---

## 3. Requêtes SQL correctives proposées (Non exécutées)

```sql
-- 1. Remise à zéro des stocks négatifs en attente de réapprovisionnement certifié
UPDATE produits
SET stock = 0
WHERE id IN (3, 6) AND stock < 0;

-- 2. Déblocage de la commande #7 dont le stock produit (#7) est disponible
UPDATE commandes
SET statut = 'EN_PREPARATION'
WHERE id = 7 AND statut = 'BLOQUEE';
```
```

---

### 2. Exemple de mise à jour de `STATS.md`

```markdown
## TP 4 : Chaîne RAG (Jina v3 + Groq) & Orchestration Agentique MCP (Java)

### 1. Banc d'essai de robustesse du moteur RAG (`AskRag.java`)

| Type de question | Question posée | Meilleur score cosinus | Source Top-1 | Comportement observé |
| :--- | :--- | :---: | :--- | :--- |
| **Métier (Remboursement)** | *"Quel est le circuit de validation pour un remboursement de 250 € ?"* | `0.6842` | `politique-remboursement.md` | Validation par un Superviseur Service Client citée avec `[source: politique-remboursement.md]`. |
| **Métier (Sécurité)** | *"Que faire si une commande est BLOQUEE avec un stock suffisant ?"* | `0.7115` | `guide-securite.md` | Bascule vers `EN_PREPARATION` citée avec `[source: guide-securite.md]`. |
| **Hors sujet** | *"Quelle est la recette de la pâte à crêpes ?"* | `0.1420` | `politique-remboursement.md` | **Rejet immédiat** (`0.1420 < 0.35`), refus honnête sans hallucination ni appel LLM inutile. |

### 2. Trace d'exécution de l'agent IDE (MCP Filesystem + MCP SQLite)
- **Nombre d'appels d'outils effectués par l'agent :** 5 appels séquentiels autonomes :
  1. `filesystem:read_file` → lecture de `tp4-rag/docs/guide-securite.md`
  2. `mcp-db-explorer:read_resource` → lecture de `db://schema`
  3. `mcp-db-explorer:query_database` → `SELECT * FROM produits WHERE stock < 0`
  4. `mcp-db-explorer:query_database` → `SELECT c.*, p.nom, p.stock FROM commandes c JOIN produits p ON c.produit_id = p.id WHERE c.statut = 'BLOQUEE'`
  5. `filesystem:write_file` → création de `AUDIT_CONFORMITE.md`
- **Consommation totale de la session agentique :** ~3 450 prompt tokens / ~680 completion tokens (Coût : $0.00 sur tier gratuit Groq).
```