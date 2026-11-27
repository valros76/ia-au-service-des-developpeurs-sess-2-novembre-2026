# TP 4 : Chaîne RAG sans framework et workflow agentique combiné (Java 21+ natif)

**Durée estimée :** 1h00  
**Objectif :** Construire un moteur RAG complet en Java 21+ pur (`java.net.http.HttpClient`, `java.nio.file`, `record`, zéro framework type Spring AI ou LangChain4j) en combinant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération, puis orchestrer un agent dans votre IDE couplant ce corpus documentaire à votre serveur MCP `mcp-db-explorer` (TP 3).

---

## Partie A : Moteur RAG minimaliste en Java pur (`tp4-rag/`)

### Étape 1 : Initialisation et configuration (5 min)
1. À la racine de votre dépôt Git, créez un dossier `tp4-rag/` contenant `src/` et `docs/`.
2. Utilisez uniquement la bibliothèque standard **Java 21+** (`java.net.http.HttpClient`, `java.nio.file.Files`). Pour la sérialisation JSON de `vector-store.json`, vous pouvez au choix utiliser **Jackson** (déjà configuré au TP 3) ou écrire un utilitaire JSON dédié en Java pur.
3. Vérifiez que votre fichier `.env` contient vos clés (et écrivez une petite méthode `loadEnv()` de 12 lignes pour les charger) :
   - `JINA_API_KEY=...`
   - `GROQ_API_KEY=...`

### Étape 2 : Rédaction du corpus métier (15 min)
Créez **3 fichiers Markdown** dans `tp4-rag/docs/` détaillant les règles métier fictives de votre application e-commerce (cohérentes avec les tables `clients`, `produits` et `commandes` de votre base `entreprise.db` du TP 3) :
1. `docs/politique-remboursement.md` : délais de rétractation, conditions de remboursement selon le statut de la commande (`LIVREE`, `EXPEDIEE`, `BLOQUEE`), seuils financiers nécessitant une validation manager.
2. `docs/architecture-api.md` : endpoints principaux, formats d'échange JSON, règles de pagination et gestion des codes d'erreur HTTP.
3. `docs/guide-securite.md` : règles strictes d'intégrité des données en base (ex. : interdiction absolue des stocks négatifs, règle de traitement des commandes au statut `BLOQUEE` dépassant un certain montant ou liées à un produit en rupture, règles de validation des adresses email clients, politique de conservation).

> **Contrainte :** Chaque fichier doit contenir au minimum **1 200 caractères** (environ 3 à 4 paragraphes structurés avec des titres `##`) afin de générer plusieurs morceaux (*chunks*) par fichier et de tester efficacement le chevauchement.

### Étape 3 : Découpage et validation du *Chunking* (10 min)
1. Créez une classe `Chunker.java` avec la méthode `public static List<String> chunkText(String text, int chunkSize, int overlap)` (valeurs par défaut : `500` et `75`).
2. **Validation locale (sans appel API) :** Exécutez une méthode `main` de test affichant le nombre de segments générés par document et vérifiant que les `overlap` derniers caractères d'un segment sont identiques aux `overlap` premiers caractères du segment suivant.

### Étape 4 : Programme d'indexation `Indexer.java` (10 min)
Écrivez le programme `Indexer.java` (modélisant chaque entrée par un `record DocumentChunk(String id, String source, String content, double[] embedding)`) qui :
1. Parcourt les fichiers `.md` du dossier `docs/` via `Files.list()`.
2. Découpe chaque fichier avec `Chunker.chunkText()`.
3. Appelle l'API d'embeddings Jina AI (`POST https://api.jina.ai/v1/embeddings`) via `HttpClient.newHttpClient()` avec le modèle `jina-embeddings-v3` (`"task": "retrieval.passage"`). Ajoutez un `Thread.sleep(200)` entre chaque appel.
4. Écrit le résultat dans `vector-store.json` et affiche le nombre total de vecteurs générés, leur dimension et le temps d'exécution (`System.nanoTime()`).

### Étape 5 : Programme d'interrogation sémantique `AskRag.java` (10 min)
Écrivez le programme `AskRag.java` exécutable via `java AskRag "Votre question"` qui :
1. Récupère la question depuis `args[0]`.
2. Vectorise la question via Jina AI (`"task": "retrieval.query"`).
3. Implémente `public static double cosineSimilarity(double[] vecA, double[] vecB)` et trie les entrées de `vector-store.json` par score décroissant.
4. Affiche dans la console les **3 meilleurs morceaux (Top-K = 3)** avec leur score, et applique un **seuil de pertinence** (`score < 0.35` : refus immédiat sans appeler Groq).
5. Envoie les 3 extraits à l'API Groq (`POST https://api.groq.com/openai/v1/chat/completions`, modèle `llama-3.3-70b-versatile`, `temperature: 0.0`) avec un prompt système interdisant toute invention et exigeant la citation du fichier source (`[source: nom-fichier.md]`).
6. **Banc d'essai de robustesse :** Testez 2 questions métier issues de vos documents, puis 1 question hors sujet (*"Quelle est la recette de la pâte à crêpes ?"*).

---

## Partie B : Orchestration agentique dans l'IDE (10 min)

Dans votre IDE (avec votre serveur MCP `mcp-db-explorer` du TP 3 et le serveur `@modelcontextprotocol/server-filesystem` actifs), confiez à l'assistant une mission d'audit autonome en plusieurs étapes :

> *"Consulte les règles métier dans `tp4-rag/docs/guide-securite.md`, interroge ensuite notre base SQLite `entreprise.db` via le serveur MCP pour identifier tous les enregistrements qui enfreignent ces règles, et génère un fichier `AUDIT_CONFORMITE.md` à la racine du projet listant les anomalies détectées et les requêtes SQL correctives (sans les exécuter)."*

---

## Livrables attendus
- Dossier `tp4-rag/` contenant `docs/` (les 3 fichiers Markdown rédigés par vos soins), `src/` (`Chunker.java`, `Indexer.java`, `AskRag.java`) et `vector-store.json`.
- Fichier `AUDIT_CONFORMITE.md` généré par l'agent à la racine du projet.
- Mise à jour de `STATS.md` consignant les scores cosinus observés, les appels d'outils MCP effectués et le bilan de tokens.