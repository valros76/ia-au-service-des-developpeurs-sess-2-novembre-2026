# TP 4 : Corrigé Complet — Chaîne RAG sans framework et workflow agentique combiné (Python 3.11+ standard)

Ce document fournit l'implémentation de référence complète du **TP 4** en **Python 3.11+ pur** (bibliothèque standard uniquement : `urllib.request`, `json`, `math`, `pathlib`, sans LangChain, LlamaIndex ni NumPy), utilisant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération.

---

## Partie A : Moteur RAG minimaliste en Python pur (`tp4-rag/`)

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

### 2. Module de découpage et chargement `.env` (`tp4-rag/chunker.py`)

```python
import os
from pathlib import Path


def load_env(env_path: Path) -> None:
    """Charge les variables d'un fichier .env dans os.environ sans dépendance externe."""
    if not env_path.exists():
        return

    for line in env_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def chunk_text(text: str, chunk_size: int = 500, overlap: int = 75) -> list[str]:
    """Découpe un texte en segments de taille chunk_size avec un chevauchement de overlap caractères."""
    clean_text = text.replace("\r\n", "\n").strip()
    if not clean_text:
        return []

    if overlap >= chunk_size:
        raise ValueError("Le chevauchement (overlap) doit être strictement inférieur à chunk_size.")

    chunks: list[str] = []
    start = 0
    step = chunk_size - overlap

    while start < len(clean_text):
        end = min(start + chunk_size, len(clean_text))
        chunks.append(clean_text[start:end])
        if end == len(clean_text):
            break
        start += step

    return chunks


# Validation locale (sans appel API) : python chunker.py
if __name__ == "__main__":
    docs_dir = Path(__file__).parent / "docs"
    for md_file in sorted(docs_dir.glob("*.md")):
        content = md_file.read_text(encoding="utf-8")
        chunks = chunk_text(content, chunk_size=500, overlap=75)
        print(f'Fichier "{md_file.name}" ({len(content)} car.) -> {len(chunks)} morceaux')

        for i in range(len(chunks) - 1):
            assert chunks[i][-75:] == chunks[i + 1][:75], (
                f"Erreur de chevauchement entre #{i} et #{i + 1} dans {md_file.name}"
            )

    print("Validation du chevauchement (75 caractères) réussie !")
```

---

### 3. Script d'indexation `tp4-rag/indexer.py`

Exécution : `python indexer.py`

```python
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

from chunker import chunk_text, load_env

BASE_DIR = Path(__file__).parent
load_env(BASE_DIR.parent / ".env")

JINA_API_KEY = os.getenv("JINA_API_KEY")
if not JINA_API_KEY:
    print("Erreur : JINA_API_KEY manquante dans le fichier .env", file=sys.stderr)
    sys.exit(1)


def embed_passage(text: str) -> list[float]:
    """Appelle l'API Jina AI (jina-embeddings-v3) pour vectoriser un passage documentaire."""
    payload = json.dumps({
        "model": "jina-embeddings-v3",
        "task": "retrieval.passage",
        "input": [text],
    }).encode("utf-8")

    req = urllib.request.Request(
        "https://api.jina.ai/v1/embeddings",
        data=payload,
        headers={
            "Authorization": f"Bearer {JINA_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=30) as response:
        data = json.loads(response.read().decode("utf-8"))
        return data["data"][0]["embedding"]


def main() -> None:
    start_time = time.perf_counter()
    docs_dir = BASE_DIR / "docs"
    md_files = sorted(docs_dir.glob("*.md"))

    if not md_files:
        print("Aucun fichier .md trouvé dans le dossier docs/.", file=sys.stderr)
        sys.exit(1)

    vector_store: list[dict] = []

    for md_file in md_files:
        content = md_file.read_text(encoding="utf-8")
        chunks = chunk_text(content, chunk_size=500, overlap=75)
        print(f"Indexation de {md_file.name} ({len(chunks)} morceaux)...")

        for idx, chunk in enumerate(chunks):
            embedding = embed_passage(chunk)
            vector_store.append({
                "id": f"{md_file.name}#{idx}",
                "source": md_file.name,
                "content": chunk,
                "embedding": embedding,
            })
            # Pause de 200 ms pour respecter les limites de débit
            time.sleep(0.2)

    store_path = BASE_DIR / "vector-store.json"
    store_path.write_text(json.dumps(vector_store, ensure_ascii=False, indent=2), encoding="utf-8")

    duration_ms = (time.perf_counter() - start_time) * 1000
    dimension = len(vector_store[0]["embedding"]) if vector_store else 0

    print("\n--- Indexation terminée ---")
    print(f"Vecteurs générés : {len(vector_store)}")
    print(f"Dimension        : {dimension}")
    print(f"Temps total      : {duration_ms:.2f} ms")
    print(f"Fichier généré   : {store_path}")


if __name__ == "__main__":
    main()
```

---

### 4. Script d'interrogation `tp4-rag/ask_rag.py`

Exécution : `python ask_rag.py "Quel est le seuil de validation d'un remboursement de 250 euros ?"`

```python
import json
import math
import os
import sys
import urllib.request
from pathlib import Path

from chunker import load_env

BASE_DIR = Path(__file__).parent
load_env(BASE_DIR.parent / ".env")

JINA_API_KEY = os.getenv("JINA_API_KEY")
GROQ_API_KEY = os.getenv("GROQ_API_KEY")

if not JINA_API_KEY or not GROQ_API_KEY:
    print("Erreur : JINA_API_KEY et GROQ_API_KEY doivent être définies dans .env", file=sys.stderr)
    sys.exit(1)

SIMILARITY_THRESHOLD = 0.35
TOP_K = 3


def cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float:
    """Calcule la similarité cosinus entre deux vecteurs de même dimension."""
    dot_product = sum(a * b for a, b in zip(vec_a, vec_b))
    norm_a = math.sqrt(sum(a * a for a in vec_a))
    norm_b = math.sqrt(sum(b * b for b in vec_b))
    denominator = norm_a * norm_b
    return 0.0 if denominator == 0.0 else dot_product / denominator


def embed_query(query_text: str) -> list[float]:
    """Vectorise la question utilisateur via Jina AI (task: retrieval.query)."""
    payload = json.dumps({
        "model": "jina-embeddings-v3",
        "task": "retrieval.query",
        "input": [query_text],
    }).encode("utf-8")

    req = urllib.request.Request(
        "https://api.jina.ai/v1/embeddings",
        data=payload,
        headers={
            "Authorization": f"Bearer {JINA_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=30) as response:
        data = json.loads(response.read().decode("utf-8"))
        return data["data"][0]["embedding"]


def generate_answer(user_question: str, top_chunks: list[dict]) -> str:
    """Interroge Groq (llama-3.3-70b-versatile) uniquement à partir du contexte extrait."""
    formatted_context = "\n\n".join(
        f"--- EXTRAIT [source: {chunk['source']}] (score: {chunk['score']:.4f}) ---\n{chunk['content']}"
        for chunk in top_chunks
    )

    system_prompt = "\n".join([
        "Tu es un assistant documentaire interne strict.",
        "RÈGLES IMPÉRATIVES :",
        "1. Réponds UNIQUEMENT à partir des extraits de contexte fournis ci-dessous.",
        "2. Interdiction absolue d'utiliser tes connaissances générales ou d'inventer des informations.",
        "3. Cite systématiquement le fichier source sous la forme [source: nom-du-fichier.md] après chaque affirmation.",
        '4. Si le contexte ne permet pas de répondre à la question, réponds exactement : "Je ne dispose pas de cette information dans la documentation fournie."',
    ])

    payload = json.dumps({
        "model": "llama-3.3-70b-versatile",
        "temperature": 0.0,
        "messages": [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": f"CONTEXTE DOCUMENTAIRE :\n{formatted_context}\n\nQUESTION :\n{user_question}",
            },
        ],
    }).encode("utf-8")

    req = urllib.request.Request(
        "https://api.groq.com/openai/v1/chat/completions",
        data=payload,
        headers={
            "Authorization": f"Bearer {GROQ_API_KEY}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=30) as response:
        data = json.loads(response.read().decode("utf-8"))
        return data["choices"][0]["message"]["content"]


def main() -> None:
    if len(sys.argv) < 2 or not sys.argv[1].strip():
        print('Usage : python ask_rag.py "Votre question"', file=sys.stderr)
        sys.exit(1)

    question = sys.argv[1].strip()
    store_path = BASE_DIR / "vector-store.json"
    vector_store = json.loads(store_path.read_text(encoding="utf-8"))

    query_vector = embed_query(question)

    scored_chunks = [
        {**entry, "score": cosine_similarity(query_vector, entry["embedding"])}
        for entry in vector_store
    ]
    scored_chunks.sort(key=lambda x: x["score"], reverse=True)
    top_chunks = scored_chunks[:TOP_K]

    print(f'\nQuestion : "{question}"')
    print("--- Top-3 des morceaux les plus proches ---")
    for chunk in top_chunks:
        print(f"• [{chunk['id']}] Score cosinus : {chunk['score']:.4f}")

    if not top_chunks or top_chunks[0]["score"] < SIMILARITY_THRESHOLD:
        max_score = top_chunks[0]["score"] if top_chunks else 0.0
        print(f"\n[Rejet automatique] Score maximal ({max_score:.4f}) inférieur au seuil ({SIMILARITY_THRESHOLD}).")
        print("Réponse : Je ne dispose pas de cette information dans la documentation fournie.")
        return

    print("\n--- Réponse générée par Groq (llama-3.3-70b-versatile) ---")
    print(generate_answer(question, top_chunks))


if __name__ == "__main__":
    main()
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
## TP 4 : Chaîne RAG (Jina v3 + Groq) & Orchestration Agentique MCP (Python)

### 1. Banc d'essai de robustesse du moteur RAG (`ask_rag.py`)

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