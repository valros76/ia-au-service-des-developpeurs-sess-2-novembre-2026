# TP 1 : Corrigé — Banc d'essai économique et paramètres de génération (Java)

Ce document présente la correction de référence complète (étapes 1, 2 et 3) pour un environnement **Java 21+ en local** (sans Docker).

---

## Étape 1 : Initialisation sécurisée du projet

### 1. Initialisation du dépôt et de l'arborescence Maven
```bash
mkdir -p formation-ia-dev/src/main/java/fr/formation/ia/tp1
cd formation-ia-dev
git init
```

### 2. Configuration du fichier `pom.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>fr.formation</groupId>
    <artifactId>ia-dev</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.18.2</version>
        </dependency>
        <dependency>
            <groupId>io.github.cdimascio</groupId>
            <artifactId>dotenv-java</artifactId>
            <version>3.1.0</version>
        </dependency>
    </dependencies>
</project>
```

### 3. Fichier `.gitignore`
```gitignore
target/
.gradle/
build/
.idea/
*.iml
.vscode/
.env
```

### 4. Fichiers `.env.example` et `.env`
**`.env.example`** :
```env
MISTRAL_API_KEY=
GROQ_API_KEY=
```

**`.env`** :
```env
MISTRAL_API_KEY=votre_cle_api_ici
```

---

## Étape 2 : Exploration des paramètres dans Continue

### 1. Configuration de `temperature` dans Continue (`~/.continue/config.json`)
```json
{
  "models": [
    {
      "title": "Mistral Codestral",
      "provider": "mistral",
      "model": "codestral-latest",
      "apiKey": "VOTRE_CLE_API",
      "defaultCompletionOptions": {
        "temperature": 0.0
      }
    }
  ]
}
```

### 2. Implémentation de référence attendue (à `temperature = 0.0`)
```java
package fr.formation.ia.validator;

import java.util.regex.Pattern;

public final class SiretValidator {

    private static final Pattern SIRET_PATTERN = Pattern.compile("^\\d{14}$");
    private static final String LA_POSTE_SIREN = "356000000";

    private SiretValidator() {}

    /**
     * Valide un numéro de SIRET français via l'algorithme de Luhn.
     * Prend en compte l'exception historique des établissements de La Poste (SIREN 356000000).
     */
    public static boolean isValid(String siret) {
        if (siret == null) {
            return false;
        }

        String cleanSiret = siret.replaceAll("\\s+", "");
        if (!SIRET_PATTERN.matcher(cleanSiret).matches()) {
            return false;
        }

        // Exception historique : La Poste (SIREN 356000000)
        if (cleanSiret.startsWith(LA_POSTE_SIREN)) {
            int sum = cleanSiret.chars()
                    .map(Character::getNumericValue)
                    .sum();
            return sum % 5 == 0;
        }

        int total = 0;
        for (int i = 0; i < cleanSiret.length(); i++) {
            int digit = Character.getNumericValue(cleanSiret.charAt(i));
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }

        return total % 10 == 0;
    }
}
```

### 3. Synthèse des observations attendues

| Critère | `temperature = 0.0` (Déterministe) | `temperature = 1.2` (Créatif / Chaotique) |
| :--- | :--- | :--- |
| **Signature & Structure** | Identique sur les 3 essais (`public static boolean isValid(String siret)`), gestion du `null`. | Variable : exceptions (`IllegalArgumentException`), records ou méthodes d'instance. |
| **Algorithme** | Boucle `for` classique avec `Character.getNumericValue()`. | Pipelines `IntStream.range().map().reduce()` complexes et sujets aux erreurs d'index. |
| **Cas limites** | `Pattern` précompilé, espaces et exception La Poste gérés. | Oublis fréquents (`NullPointerException` ou oubli de La Poste). |
| **Hallucinations** | Aucune. Utilisation standard de l'API Java 21. | Invention possible de méthodes inexistantes (`String.isNumeric()`, `Character.toInt()`). |

---

## Étape 3 : Application de mesure `BenchmarkTokens.java`

```java
package fr.formation.ia.tp1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class BenchmarkTokens {

    private static final double PRICE_INPUT_PER_1M_USD = 0.30;
    private static final double PRICE_OUTPUT_PER_1M_USD = 0.90;
    private static final double USD_TO_EUR = 0.92;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public record BenchmarkResult(
            String label,
            double latencyMs,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            double costUsd,
            double costEur
    ) {}

    public static BenchmarkResult runBenchmark(String apiKey, String label, String prompt) throws Exception {
        Map<String, Object> payload = Map.of(
                "model", "codestral-latest",
                "temperature", 0.0,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        String requestBody = MAPPER.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        long end = System.nanoTime();

        if (response.statusCode() != 200) {
            throw new RuntimeException("Erreur API (" + response.statusCode() + ") : " + response.body());
        }

        double latencyMs = (end - start) / 1_000_000.0;
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode usage = root.path("usage");

        int promptTokens = usage.path("prompt_tokens").asInt();
        int completionTokens = usage.path("completion_tokens").asInt();
        int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

        double costUsd = (promptTokens / 1_000_000.0) * PRICE_INPUT_PER_1M_USD
                       + (completionTokens / 1_000_000.0) * PRICE_OUTPUT_PER_1M_USD;
        double costEur = costUsd * USD_TO_EUR;

        return new BenchmarkResult(label, latencyMs, promptTokens, completionTokens, totalTokens, costUsd, costEur);
    }

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String apiKey = dotenv.get("MISTRAL_API_KEY", System.getenv("MISTRAL_API_KEY"));

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Erreur : MISTRAL_API_KEY manquante dans le fichier .env");
            System.exit(1);
        }

        String verbosePrompt = "Bonjour ! J'espère que tu vas bien. Je développe actuellement une application métier en Java 21 "
                + "et j'aurais vraiment besoin de ton aide pour écrire une méthode statique propre, bien commentée avec de la Javadoc "
                + "qui me permettrait de vérifier si un numéro de SIRET français saisi par un utilisateur "
                + "est valide en utilisant l'algorithme de Luhn. Pense bien à gérer les espaces éventuels et le cas de La Poste. Merci d'avance !";

        String concisePrompt = "Java 21 : méthode `public static boolean isValidSiret(String siret)` via Luhn. "
                + "Gérer null, espaces, longueur 14 chiffres et exception La Poste (356000000). Javadoc concise, code uniquement.";

        List<BenchmarkResult> results = List.of(
                runBenchmark(apiKey, "Prompt bavard", verbosePrompt),
                runBenchmark(apiKey, "Prompt condensé", concisePrompt)
        );

        for (BenchmarkResult res : results) {
            System.out.printf(
                    "[%s] Latence: %.2f ms | Prompt: %d tok | Completion: %d tok | Total: %d tok | Coût: $%.6f (%.6f €)%n",
                    res.label(), res.latencyMs(), res.promptTokens(), res.completionTokens(),
                    res.totalTokens(), res.costUsd(), res.costEur()
            );
        }
    }
}
```

---

## Exemple de livrable `STATS.md`

```markdown
# Relevés Benchmark TP 1 — Modèle : `codestral-latest` (`temperature = 0.0`)

| Version du Prompt | Latence (ms) | Prompt Tokens | Completion Tokens | Total Tokens | Coût ($) | Coût (€) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Prompt bavard** | 1 485.30 | 91 | 330 | 421 | $0.000324 | 0.000298 € |
| **Prompt condensé** | 1 010.80 | 39 | 228 | 267 | $0.000217 | 0.000200 € |
| **Gain (Condensé vs Bavard)** | **-31.9 %** | **-57.1 %** | **-30.9 %** | **-36.6 %** | **-33.0 %** | **-33.0 %** |
```