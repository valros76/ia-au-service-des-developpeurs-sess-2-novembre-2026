# TP 4 : Chaîne RAG sans framework et workflow agentique combiné (PHP 8.3+ natif)

**Durée estimée :** 1h00  
**Objectif :** Construire un moteur RAG complet en PHP 8.3+ pur (`curl`, `json_encode`/`json_decode`, `declare(strict_types=1)`, zéro framework ni dépendance Composer) en combinant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération, puis orchestrer un agent dans votre IDE couplant ce corpus documentaire à votre serveur MCP `mcp-db-explorer` (TP 3).

---

## Partie A : Moteur RAG minimaliste en PHP pur (`tp4-rag/`)

### Étape 1 : Initialisation et configuration (5 min)
1. À la racine de votre dépôt Git, créez un dossier `tp4-rag/` contenant un sous-dossier `docs/`.
2. Vérifiez que les extensions `curl` et `mbstring` sont actives (`php -m`). Aucune bibliothèque externe n'est autorisée.
3. Créez un petit utilitaire `env.php` (15 lignes avec `file()` ou `parse_ini_file()`) pour charger vos clés depuis `.env` :
   - `JINA_API_KEY=...`
   - `GROQ_API_KEY=...`

### Étape 2 : Rédaction du corpus métier (15 min)
Créez **3 fichiers Markdown** dans `tp4-rag/docs/` détaillant les règles métier fictives de votre application e-commerce (cohérentes avec les tables `clients`, `produits` et `commandes` de votre base `entreprise.db` du TP 3) :
1. `docs/politique-remboursement.md` : délais de rétractation, conditions de remboursement selon le statut de la commande (`LIVREE`, `EXPEDIEE`, `BLOQUEE`), seuils financiers nécessitant une validation manager.
2. `docs/architecture-api.md` : endpoints principaux, formats d'échange JSON, règles de pagination et gestion des codes d'erreur HTTP.
3. `docs/guide-securite.md` : règles strictes d'intégrité des données en base (ex. : interdiction absolue des stocks négatifs, règle de traitement des commandes au statut `BLOQUEE` dépassant un certain montant ou liées à un produit en rupture, règles de validation des adresses email clients, politique de conservation).

> **Contrainte :** Chaque fichier doit contenir au minimum **1 200 caractères** (environ 3 à 4 paragraphes structurés avec des titres `##`) afin de générer plusieurs morceaux (*chunks*) par fichier et de tester efficacement le chevauchement (pensez à utiliser `mb_substr` et `mb_strlen` pour gérer correctement les accents UTF-8 en PHP).

### Étape 3 : Découpage et validation du *Chunking* (10 min)
1. Créez un fichier `chunker.php` définissant la fonction typée `chunkText(string $text, int $chunkSize = 500, int $overlap = 75): array` qui découpe le texte UTF-8 avec chevauchement.
2. **Validation locale (sans appel API) :** Exécutez un script de vérification affichant le nombre de morceaux par fichier et contrôlant que la fin du morceau `N` recouvre bien le début du morceau `N+1` sans couper les caractères accentués.

### Étape 4 : Script d'indexation `indexer.php` (10 min)
Écrivez le script CLI `indexer.php` qui :
1. Parcourt tous les fichiers `.md` du dossier `docs/` via `glob(__DIR__ . '/docs/*.md')`.
2. Découpe chaque document avec `chunkText()`.
3. Appelle l'API d'embeddings Jina AI (`POST https://api.jina.ai/v1/embeddings`) via `curl_init()` avec le modèle `jina-embeddings-v3` (`"task" => "retrieval.passage"`). Ajoutez un `usleep(200000)` (200 ms) entre les requêtes pour éviter tout *rate limit*.
4. Sauvegarde le résultat dans `vector-store.json` (`JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE`) avec la structure :
   `{ "id": "guide-securite.md#0", "source": "guide-securite.md", "content": "...", "embedding": [...] }`.
5. Affiche le nombre total de vecteurs indexés, leur dimension et la durée totale d'exécution (`hrtime(true)`).

### Étape 5 : Script d'interrogation sémantique `ask-rag.php` (10 min)
Écrivez le script CLI `ask-rag.php` exécutable via `php ask-rag.php "Votre question"` qui :
1. Récupère l'argument `$argv[1]`.
2. Vectorise la question via Jina AI (`"task" => "retrieval.query"`).
3. Implémente la fonction `cosineSimilarity(array $vecA, array $vecB): float` et compare le vecteur de la question avec tous les vecteurs de `vector-store.json`.
4. Trie les scores par ordre décroissant, affiche les **3 meilleurs morceaux (Top-K = 3)** et leur score de similarité, et applique un **seuil de pertinence** (`score < 0.35` : refus immédiat sans appel à Groq).
5. Interroge l'API Groq (`POST https://api.groq.com/openai/v1/chat/completions`, modèle `llama-3.3-70b-versatile`, `temperature: 0.0`) avec un message `system` interdisant toute invention et exigeant la citation du fichier source (`[source: nom-fichier.md]`).
6. **Banc d'essai de robustesse :** Testez 2 questions issues de vos fichiers Markdown, puis 1 question hors sujet (*"Quelle est la recette de la pâte à crêpes ?"*).

---

## Partie B : Orchestration agentique dans l'IDE (10 min)

Dans votre IDE (avec votre serveur MCP `mcp-db-explorer` du TP 3 et le serveur `@modelcontextprotocol/server-filesystem` actifs), confiez à l'assistant une mission d'audit autonome en plusieurs étapes :

> *"Consulte les règles métier dans `tp4-rag/docs/guide-securite.md`, interroge ensuite notre base SQLite `entreprise.db` via le serveur MCP pour identifier tous les enregistrements qui enfreignent ces règles, et génère un fichier `AUDIT_CONFORMITE.md` à la racine du projet listant les anomalies détectées et les requêtes SQL correctives (sans les exécuter)."*

---

## Livrables attendus
- Dossier `tp4-rag/` contenant `docs/` (les 3 fichiers Markdown rédigés par vos soins), `env.php`, `chunker.php`, `indexer.php`, `ask-rag.php` et `vector-store.json`.
- Fichier `AUDIT_CONFORMITE.md` généré par l'agent à la racine du projet.
- Mise à jour de `STATS.md` avec les scores cosinus relevés, les appels d'outils MCP effectués et le bilan de tokens.