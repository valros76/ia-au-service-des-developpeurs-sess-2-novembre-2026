# TP 4 : Chaîne RAG sans framework et workflow agentique combiné (JavaScript Vanilla / Node.js 22+)

**Durée estimée :** 1h00  
**Objectif :** Construire un moteur RAG complet en JavaScript Vanilla (ES2024+, `fetch` natif, zéro dépendance externe) en combinant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération, puis orchestrer un agent dans votre IDE couplant ce corpus documentaire à votre serveur MCP `mcp-db-explorer` (TP 3).

---

## Partie A : Moteur RAG minimaliste en JavaScript pur (`tp4-rag/`)

### Étape 1 : Initialisation et configuration (5 min)
1. À la racine de votre dépôt Git, créez un dossier `tp4-rag/` contenant un sous-dossier `docs/`.
2. Initialisez un projet ES Modules (`npm init -y` puis `npm pkg set type="module"`). Aucune dépendance externe (`npm install`) n'est autorisée : utilisez uniquement `node:fs/promises`, `node:path` et `fetch` natif (Node.js 22+ avec `--env-file=.env`).
3. Vérifiez que votre fichier `.env` (exclu dans `.gitignore`) contient vos deux clés :
   - `JINA_API_KEY=...`
   - `GROQ_API_KEY=...`

### Étape 2 : Rédaction du corpus métier (15 min)
Créez **3 fichiers Markdown** dans `tp4-rag/docs/` détaillant les règles métier fictives de votre application e-commerce (cohérentes avec les tables `clients`, `produits` et `commandes` de votre base `entreprise.db` du TP 3) :
1. `docs/politique-remboursement.md` : délais de rétractation, conditions de remboursement selon le statut de la commande (`LIVREE`, `EXPEDIEE`, `BLOQUEE`), seuils financiers nécessitant une validation manager.
2. `docs/architecture-api.md` : endpoints principaux, formats d'échange JSON, règles de pagination et gestion des codes d'erreur HTTP.
3. `docs/guide-securite.md` : règles strictes d'intégrité des données en base (ex. : interdiction absolue des stocks négatifs, règle de traitement des commandes au statut `BLOQUEE` dépassant un certain montant ou liées à un produit en rupture, règles de validation des adresses email clients, politique de conservation).

> **Contrainte :** Chaque fichier doit contenir au minimum **1 200 caractères** (environ 3 à 4 paragraphes structurés avec des titres `##`) afin de générer plusieurs morceaux (*chunks*) par fichier et de tester efficacement le chevauchement.

### Étape 3 : Découpage et validation du *Chunking* (10 min)
1. Créez un module `chunker.js` exportant une fonction pure `chunkText(text, chunkSize = 500, overlap = 75)` qui découpe une chaîne de caractères en conservant un chevauchement de `overlap` caractères entre deux segments consécutifs.
2. **Validation locale (sans appel API) :** Exécutez un test rapide affichant pour chaque fichier le nombre de morceaux générés et vérifiant que les 75 derniers caractères du morceau `N` correspondent bien aux 75 premiers caractères du morceau `N+1`.

### Étape 4 : Script d'indexation `indexer.js` (10 min)
Écrivez le script `indexer.js` qui :
1. Parcourt tous les fichiers `.md` du dossier `docs/`.
2. Découpe chaque document via `chunkText()`.
3. Appelle l'API d'embeddings Jina AI (`POST https://api.jina.ai/v1/embeddings`) avec le modèle `jina-embeddings-v3` (et `"task": "retrieval.passage"`). Ajoutez une courte pause (`await new Promise(r => setTimeout(r, 200))`) entre les appels pour respecter les limites de débit.
4. Enregistre l'index complet dans `vector-store.json` sous la forme d'un tableau d'objets :
   `{ "id": "guide-securite.md#0", "source": "guide-securite.md", "content": "...", "embedding": [...] }`.
5. Affiche en fin d'exécution le nombre total de vecteurs générés, la dimension d'un vecteur et le temps total d'indexation (`performance.now()`).

### Étape 5 : Script d'interrogation sémantique `ask-rag.js` (10 min)
Écrivez le script `ask-rag.js` exécutable en ligne de commande (`node --env-file=.env ask-rag.js "Votre question"`) qui :
1. Récupère la question passée en argument (`process.argv[2]`).
2. Vectorise la question via Jina AI (`"task": "retrieval.query"`).
3. Implémente la fonction mathématique `cosineSimilarity(vecA, vecB)` et calcule le score de similarité entre la question et chaque entrée de `vector-store.json`.
4. Trie les résultats, affiche dans le terminal les **3 meilleurs morceaux (Top-K = 3)** avec leur fichier source et leur score de similarité, et applique un **seuil de pertinence minimal** (`score < 0.35` : rejet immédiat sans même appeler le LLM).
5. Envoie les extraits retenus à l'API Groq (`POST https://api.groq.com/openai/v1/chat/completions`, modèle `llama-3.3-70b-versatile`, `temperature: 0.0`) avec un prompt système strict :
   - Interdiction absolue d'utiliser des connaissances externes au contexte fourni.
   - Obligation de citer explicitement le nom du fichier source (`[source: nom-fichier.md]`).
   - Obligation de répondre *"Je ne dispose pas de cette information dans la documentation fournie."* si la réponse n'est pas dans les extraits.
6. **Banc d'essai de robustesse :** Testez 2 questions précises issues de vos règles métier, puis 1 question totalement hors sujet (*"Quelle est la recette de la pâte à crêpes ?"*).

---

## Partie B : Orchestration agentique dans l'IDE (10 min)

Dans votre IDE (avec votre serveur MCP `mcp-db-explorer` du TP 3 et le serveur `@modelcontextprotocol/server-filesystem` actifs), confiez à l'assistant une mission d'audit autonome en plusieurs étapes :

> *"Consulte les règles métier dans `tp4-rag/docs/guide-securite.md`, interroge ensuite notre base SQLite `entreprise.db` via le serveur MCP pour identifier tous les enregistrements qui enfreignent ces règles, et génère un fichier `AUDIT_CONFORMITE.md` à la racine du projet listant les anomalies détectées et les requêtes SQL correctives (sans les exécuter)."*

---

## Livrables attendus
- Dossier `tp4-rag/` contenant `docs/` (les 3 fichiers Markdown rédigés par vos soins), `chunker.js`, `indexer.js`, `ask-rag.js` et `vector-store.json` généré.
- Fichier `AUDIT_CONFORMITE.md` généré par l'agent à la racine du dépôt.
- Mise à jour de `STATS.md` consignant : les scores de similarité observés (question pertinente vs hors sujet), la trace des appels d'outils MCP effectués par l'agent et le bilan de tokens.