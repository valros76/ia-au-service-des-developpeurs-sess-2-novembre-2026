# TP 4 : Chaîne RAG sans framework et workflow agentique combiné (Python 3.11+ standard)

**Durée estimée :** 1h00  
**Objectif :** Construire un moteur RAG complet en Python 3.11+ pur (bibliothèque standard uniquement : `urllib.request`, `json`, `math`, `pathlib`, sans LangChain, LlamaIndex ni NumPy) en combinant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération, puis orchestrer un agent dans votre IDE couplant ce corpus documentaire à votre serveur MCP `mcp-db-explorer` (TP 3).

---

## Partie A : Moteur RAG minimaliste en Python pur (`tp4-rag/`)

### Étape 1 : Initialisation et configuration (5 min)
1. À la racine de votre dépôt Git, créez un dossier `tp4-rag/` contenant un sous-dossier `docs/`.
2. Aucune installation `pip` n'est requise : vous utiliserez exclusivement la bibliothèque standard Python (`urllib.request`, `json`, `math`, `os`, `sys`, `time`, `pathlib`).
3. Écrivez une fonction utilitaire `load_env(".env")` (10 lignes) pour charger vos clés depuis `.env` dans `os.environ` :
   - `JINA_API_KEY=...`
   - `GROQ_API_KEY=...`

### Étape 2 : Rédaction du corpus métier (15 min)
Créez **3 fichiers Markdown** dans `tp4-rag/docs/` détaillant les règles métier fictives de votre application e-commerce (cohérentes avec les tables `clients`, `produits` et `commandes` de votre base `entreprise.db` du TP 3) :
1. `docs/politique-remboursement.md` : délais de rétractation, conditions de remboursement selon le statut de la commande (`LIVREE`, `EXPEDIEE`, `BLOQUEE`), seuils financiers nécessitant une validation manager.
2. `docs/architecture-api.md` : endpoints principaux, formats d'échange JSON, règles de pagination et gestion des codes d'erreur HTTP.
3. `docs/guide-securite.md` : règles strictes d'intégrité des données en base (ex. : interdiction absolue des stocks négatifs, règle de traitement des commandes au statut `BLOQUEE` dépassant un certain montant ou liées à un produit en rupture, règles de validation des adresses email clients, politique de conservation).

> **Contrainte :** Chaque fichier doit contenir au minimum **1 200 caractères** (environ 3 à 4 paragraphes structurés avec des titres `##`) afin de générer plusieurs morceaux (*chunks*) par fichier et de tester efficacement le chevauchement.

### Étape 3 : Découpage et validation du *Chunking* (10 min)
1. Créez un module `chunker.py` définissant la fonction typée `def chunk_text(text: str, chunk_size: int = 500, overlap: int = 75) -> list[str]:` utilisant le découpage par tranches (*slicing*) Python.
2. **Validation locale (sans appel API) :** Exécutez `python chunker.py` pour afficher le nombre de morceaux produits par fichier et vérifier par assertion que `chunk[i][-overlap:] == chunk[i+1][:overlap]`.

### Étape 4 : Script d'indexation `indexer.py` (10 min)
Écrivez le script `indexer.py` qui :
1. Lit tous les fichiers `.md` dans `Path(__file__).parent / "docs"`.
2. Découpe chaque texte avec `chunk_text()`.
3. Appelle l'API d'embeddings Jina AI (`POST https://api.jina.ai/v1/embeddings`) via `urllib.request.Request` avec le modèle `jina-embeddings-v3` (`"task": "retrieval.passage"`). Ajoutez un `time.sleep(0.2)` entre chaque requête.
4. Écrit le résultat dans `vector-store.json` (`ensure_ascii=False, indent=2`) avec la structure :
   `{"id": "guide-securite.md#0", "source": "guide-securite.md", "content": "...", "embedding": [...]}`.
5. Affiche le nombre total de morceaux indexés, la dimension du vecteur et le temps total (`time.perf_counter()`).

### Étape 5 : Script d'interrogation sémantique `ask_rag.py` (10 min)
Écrivez le script `ask_rag.py` exécutable via `python ask_rag.py "Votre question"` qui :
1. Récupère la question depuis `sys.argv[1]`.
2. Vectorise la question via Jina AI (`"task": "retrieval.query"`).
3. Implémente la fonction `cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float` avec `zip()` et `math.sqrt()`.
4. Extrait les **3 meilleurs morceaux (Top-K = 3)**, affiche leurs scores dans la console et applique un **seuil de confiance minimal** (`score < 0.35` : refus immédiat sans appeler le LLM).
5. Interroge l'API Groq (`POST https://api.groq.com/openai/v1/chat/completions`, modèle `llama-3.3-70b-versatile`, `temperature: 0.0`) avec un prompt système interdisant toute hallucination et imposant la citation du fichier source (`[source: nom-fichier.md]`).
6. **Banc d'essai de robustesse :** Testez 2 questions métier issues de vos documents, puis 1 question hors sujet (*"Quelle est la recette de la pâte à crêpes ?"*).

---

## Partie B : Orchestration agentique dans l'IDE (10 min)

Dans votre IDE (avec votre serveur MCP `mcp-db-explorer` du TP 3 et le serveur `@modelcontextprotocol/server-filesystem` actifs), confiez à l'assistant une mission d'audit autonome en plusieurs étapes :

> *"Consulte les règles métier dans `tp4-rag/docs/guide-securite.md`, interroge ensuite notre base SQLite `entreprise.db` via le serveur MCP pour identifier tous les enregistrements qui enfreignent ces règles, et génère un fichier `AUDIT_CONFORMITE.md` à la racine du projet listant les anomalies détectées et les requêtes SQL correctives (sans les exécuter)."*

---

## Livrables attendus
- Dossier `tp4-rag/` contenant `docs/` (les 3 fichiers Markdown rédigés par vos soins), `chunker.py`, `indexer.py`, `ask_rag.py` et `vector-store.json`.
- Fichier `AUDIT_CONFORMITE.md` généré par l'agent à la racine du projet.
- Mise à jour de `STATS.md` consignant les scores cosinus obtenus, la séquence d'appels d'outils MCP de l'agent et le bilan de tokens.