#!/usr/bin/env python3
"""
Serveur MCP Unifié en Python (Skills .mdr + SQLite entreprise.db + RAG SQLite Jina/Groq)
- Nécessite GROQ_API_KEY dans l'environnement ou .env
- Nécessite JINA_API_KEY pour https://api.jina.ai/v1/embeddings (modèle jina-embeddings-v3)
- S'appuie uniquement sur le corpus documentaire stocké dans entreprise.db (rag_documents / rag_chunks)

Exemples CLI :
- python server_mcp.py sql "SELECT * FROM produits"
- python server_mcp.py rag-index
- python server_mcp.py rag-ask "Quelles sont les règles sur les commandes BLOQUEE ?"
- python server_mcp.py mon-skill "Entrée de test" --prompt
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

JINA_EMBEDDINGS_URL = "https://api.jina.ai/v1/embeddings"
JINA_EMBEDDINGS_MODEL = "jina-embeddings-v3"
GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"
RAG_TOP_K = 3
RAG_MIN_SCORE = 0.35

SCRIPT_DIR = Path(__file__).resolve().parent


def find_project_root(start_dir: Path) -> Path:
    """Recherche la racine contenant .prompts, .env ou entreprise.db en priorité sur le dossier du script."""
    markers = (".prompts", ".env", "entreprise.db")
    if any((SCRIPT_DIR / m).exists() for m in markers):
        return SCRIPT_DIR

    current = start_dir.resolve()
    while True:
        if any((current / m).exists() for m in markers):
            return current
        if current.parent == current:
            return start_dir.resolve()
        current = current.parent


def load_dotenv(env_path: Path) -> None:
    """Charge un fichier .env sans écraser les variables déjà présentes dans os.environ."""
    if not env_path.is_file():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, val = line.split("=", 1)
        key = key.strip()
        val = val.strip()
        if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
            val = val[1:-1]
        if key and key not in os.environ:
            os.environ[key] = val


def open_enterprise_db(project_root: Path) -> sqlite3.Connection | None:
    """Ouvre entreprise.db si présente."""
    candidates = [
        SCRIPT_DIR / "entreprise.db",
        SCRIPT_DIR / "enterprise.db",
        project_root / "entreprise.db",
    ]
    for path in candidates:
        if path.is_file():
            conn = sqlite3.connect(str(path))
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA foreign_keys = ON;")
            return conn
    return None


def execute_safe_select(conn: sqlite3.Connection | None, raw_sql: Any) -> list[dict[str, Any]]:
    """Exécute uniquement une requête SQL SELECT en lecture seule avec garde-fous."""
    if conn is None:
        raise RuntimeError("Base entreprise.db introuvable dans le projet.")
    if not isinstance(raw_sql, str) or len(raw_sql.strip()) < 10:
        raise ValueError("Paramètre sql invalide (minimum 10 caractères requis).")

    cleaned = re.sub(r";+$", "", raw_sql.strip())
    if ";" in cleaned:
        raise RuntimeError("Sécurité : requêtes multiples interdites.")
    if not re.match(r"^SELECT\b", cleaned, flags=re.IGNORECASE):
        raise RuntimeError("Sécurité : seul SELECT est autorisé.")

    forbidden = re.compile(
        r"\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b",
        flags=re.IGNORECASE,
    )
    if forbidden.search(cleaned):
        raise RuntimeError("Sécurité : mot-clé SQL interdit.")

    final_sql = cleaned if re.search(r"\bLIMIT\s+\d+", cleaned, flags=re.IGNORECASE) else f"{cleaned} LIMIT 50"
    cursor = conn.execute(final_sql)
    return [dict(row) for row in cursor.fetchall()]


def get_database_schema(conn: sqlite3.Connection | None) -> str:
    """Retourne le schéma DDL des tables applicatives de entreprise.db."""
    if conn is None:
        return ""
    rows = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    statements = []
    for row in rows:
        sql_text = row["sql"]
        if sql_text:
            statements.append(str(sql_text).rstrip(";") + ";")
    return "\n\n".join(statements)


DEFAULT_DB_DOCS: list[dict[str, str]] = [
    {
        "titre": "politique-remboursement.md",
        "categorie": "politique-remboursement",
        "contenu": (
            "## Délais de rétractation et remboursement\n"
            "Le client dispose d'un délai légal de 14 jours calendaires à compter de la réception de la commande "
            "pour demander un remboursement ou un retour produit, sous réserve que l'article soit retourné dans son état d'origine.\n\n"
            "## Règles selon le statut des commandes\n"
            "Les commandes au statut LIVREE peuvent être remboursées après contrôle de réception. "
            "Les commandes EXPEDIEE font l'objet d'un suivi transporteur avant remboursement. "
            "Les commandes au statut EN_COURS peuvent être annulées sans frais avant préparation logistique. "
            "Toute commande au statut BLOQUEE ne peut pas être remboursée automatiquement tant que l'anomalie métier ou le litige n'a pas été levé.\n\n"
            "## Validation financière et seuils\n"
            "Tout remboursement ou avoir supérieur à 500 EUR nécessite une double validation manager et une vérification préalable des stocks et factures associées."
        ),
    },
    {
        "titre": "architecture-api.md",
        "categorie": "architecture-api",
        "contenu": (
            "## Endpoints et formats JSON\n"
            "Les services internes exposent des endpoints REST au format JSON UTF-8 avec pagination explicite (limit et offset) et horodatage ISO-8601.\n\n"
            "## Gestion des codes d'erreur HTTP\n"
            "Les erreurs de validation retournent un statut 400. Les authentifications invalides retournent 401. "
            "Les dépassements de quota ou de débit (rate limit) retournent HTTP 429 (Too Many Requests) et doivent être traités avec un délai d'attente progressif (backoff). "
            "Les erreurs internes inattendues retournent HTTP 500.\n\n"
            "## Résilience et intégration MCP\n"
            "Les connecteurs MCP doivent respecter la lecture seule pour les requêtes d'exploration et ne jamais exposer de secrets ou clés API dans les réponses JSON."
        ),
    },
    {
        "titre": "guide-securite.md",
        "categorie": "guide-securite",
        "contenu": (
            "## Intégrité des stocks en base\n"
            "Aucune ligne de la table produits ne doit présenter un stock négatif ou nul non maîtrisé. "
            "Un stock négatif (par exemple -3 sur un écran ou un stock nul sur une webcam) constitue une anomalie critique d'inventaire à auditer sans modifier la base à l'aveugle.\n\n"
            "## Traitement des commandes BLOQUEE\n"
            "Toute commande enregistrée avec le statut BLOQUEE (en majuscules) doit être isolée dans le rapport d'audit de conformité "
            "avec son identifiant, son client, son produit, sa quantité et sa date_commande.\n\n"
            "## Conformité clients et requêtes SQL\n"
            "Les adresses email des clients doivent être valides et non dupliquées, et les segments clients respectent B2B ou B2C. "
            "Toute inspection de entreprise.db doit s'appuyer exclusivement sur des requêtes SELECT en lecture seule."
        ),
    },
]


def ensure_rag_corpus_in_db(conn: sqlite3.Connection | None) -> dict[str, Any]:
    """Crée les tables rag_documents et rag_chunks dans entreprise.db et initialise le corpus si vide."""
    if conn is None:
        return {"initialized": False, "documents": []}

    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS rag_documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            titre TEXT UNIQUE NOT NULL,
            categorie TEXT NOT NULL,
            contenu TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS rag_chunks (
            id TEXT PRIMARY KEY,
            document_id INTEGER NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
            source TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            content TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            embedding_json TEXT
        );
        """
    )

    total = int(conn.execute("SELECT COUNT(*) AS total FROM rag_documents").fetchone()["total"])
    if total == 0:
        now_iso = datetime.now(timezone.utc).isoformat()
        for doc in DEFAULT_DB_DOCS:
            digest = hashlib.sha256(doc["contenu"].encode("utf-8")).hexdigest()
            conn.execute(
                """
                INSERT OR IGNORE INTO rag_documents (titre, categorie, contenu, sha256, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (doc["titre"], doc["categorie"], doc["contenu"], digest, now_iso),
            )
        conn.commit()

    docs = [dict(r) for r in conn.execute("SELECT id, titre, categorie, contenu, sha256, updated_at FROM rag_documents ORDER BY id ASC").fetchall()]
    return {"initialized": True, "documents": docs}


def chunk_corpus_text(text: str, chunk_size: int = 500, overlap: int = 75) -> list[str]:
    """Découpe un texte documentaire avec chevauchement."""
    normalized = (text or "").replace("\r\n", "\n").strip()
    if not normalized:
        return []
    if len(normalized) <= chunk_size:
        return [normalized]

    safe_overlap = max(0, min(overlap, chunk_size - 1))
    step = max(1, chunk_size - safe_overlap)
    chunks: list[str] = []
    start = 0

    while start < len(normalized):
        slice_txt = normalized[start : start + chunk_size].strip()
        if slice_txt:
            chunks.append(slice_txt)
        if start + chunk_size >= len(normalized):
            break
        start += step

    return chunks


def cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float:
    """Calcule la similarité cosinus entre deux vecteurs."""
    if not vec_a or not vec_b or len(vec_a) != len(vec_b):
        return 0.0
    dot = 0.0
    norm_a = 0.0
    norm_b = 0.0
    for a, b in zip(vec_a, vec_b):
        fa = float(a)
        fb = float(b)
        dot += fa * fb
        norm_a += fa * fa
        norm_b += fb * fb
    if norm_a <= 0.0 or norm_b <= 0.0:
        return 0.0
    return dot / (math.sqrt(norm_a) * math.sqrt(norm_b))


def post_json(url: str, bearer_token: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Exécute une requête HTTP POST JSON via la bibliothèque standard urllib."""
    if not bearer_token:
        raise RuntimeError("Clé API manquante dans l'environnement.")
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url=url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {bearer_token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            raw_data = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Erreur HTTP {exc.code} sur {url} : {err_body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Erreur réseau sur {url} : {exc.reason}") from exc

    parsed = json.loads(raw_data)
    if not isinstance(parsed, dict):
        raise RuntimeError("Réponse JSON invalide.")
    return parsed


def embed_with_jina(jina_api_key: str, inputs: list[str], task: str = "retrieval.passage") -> list[list[float]]:
    """Appelle https://api.jina.ai/v1/embeddings avec jina-embeddings-v3."""
    if not jina_api_key:
        raise RuntimeError("Variable JINA_API_KEY absente dans .env (requise pour https://api.jina.ai/v1/embeddings).")

    data = post_json(
        JINA_EMBEDDINGS_URL,
        jina_api_key,
        {
            "model": JINA_EMBEDDINGS_MODEL,
            "task": task,
            "input": inputs,
        },
    )
    items = data.get("data")
    if not isinstance(items, list) or len(items) != len(inputs):
        raise RuntimeError("Réponse Jina Embeddings incomplète.")

    sorted_items = sorted(items, key=lambda x: int(x.get("index", 0)))
    vectors: list[list[float]] = []
    for item in sorted_items:
        emb = item.get("embedding")
        if not isinstance(emb, list):
            raise RuntimeError("Vecteur manquant dans la réponse Jina.")
        vectors.append([float(v) for v in emb])
    return vectors


def ask_groq(groq_api_key: str, prompt: str, model: str = DEFAULT_GROQ_MODEL) -> str:
    """Envoie un prompt à Groq Chat Completions."""
    if not groq_api_key:
        raise RuntimeError("Variable GROQ_API_KEY absente dans .env.")

    data = post_json(
        GROQ_CHAT_URL,
        groq_api_key,
        {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
        },
    )
    choices = data.get("choices") or []
    if not choices:
        return "Réponse vide de Groq."
    content = choices[0].get("message", {}).get("content")
    return str(content).strip() if content else "Réponse vide de Groq."


def index_db_corpus_rag(conn: sqlite3.Connection | None, jina_api_key: str, force_reembed: bool = False) -> dict[str, Any]:
    """Indexe dans entreprise.db les embeddings des extraits issus uniquement de rag_documents."""
    if conn is None:
        raise RuntimeException("Base entreprise.db introuvable : RAG SQLite indisponible.")

    corpus = ensure_rag_corpus_in_db(conn)
    documents = corpus["documents"]

    total_chunks = 0
    embedded_now = 0
    reused_from_db = 0
    dimension = 0

    for doc in documents:
        chunks = chunk_corpus_text(str(doc["contenu"]), 500, 75)
        pending: list[dict[str, Any]] = []

        for idx, chunk_txt in enumerate(chunks):
            total_chunks += 1
            chunk_id = f"{doc['titre']}#{idx}"
            chunk_hash = hashlib.sha256(chunk_txt.encode("utf-8")).hexdigest()

            existing = conn.execute(
                "SELECT sha256, embedding_json FROM rag_chunks WHERE id = ?",
                (chunk_id,),
            ).fetchone()

            if not force_reembed and existing and existing["sha256"] == chunk_hash and existing["embedding_json"]:
                reused_from_db += 1
                if dimension == 0:
                    try:
                        parsed_vec = json.loads(existing["embedding_json"])
                        if isinstance(parsed_vec, list):
                            dimension = len(parsed_vec)
                    except json.JSONDecodeError:
                        pending.append(
                            {
                                "id": chunk_id,
                                "document_id": int(doc["id"]),
                                "source": str(doc["titre"]),
                                "chunk_index": idx,
                                "content": chunk_txt,
                                "sha256": chunk_hash,
                            }
                        )
                        reused_from_db -= 1
            else:
                pending.append(
                    {
                        "id": chunk_id,
                        "document_id": int(doc["id"]),
                        "source": str(doc["titre"]),
                        "chunk_index": idx,
                        "content": chunk_txt,
                        "sha256": chunk_hash,
                    }
                )

        if pending:
            vectors = embed_with_jina(jina_api_key, [p["content"] for p in pending], task="retrieval.passage")
            for item, vec in zip(pending, vectors):
                if dimension == 0:
                    dimension = len(vec)
                conn.execute(
                    """
                    INSERT INTO rag_chunks (id, document_id, source, chunk_index, content, sha256, embedding_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        document_id = excluded.document_id,
                        source = excluded.source,
                        chunk_index = excluded.chunk_index,
                        content = excluded.content,
                        sha256 = excluded.sha256,
                        embedding_json = excluded.embedding_json
                    """,
                    (
                        item["id"],
                        item["document_id"],
                        item["source"],
                        item["chunk_index"],
                        item["content"],
                        item["sha256"],
                        json.dumps(vec),
                    ),
                )
                embedded_now += 1
            conn.commit()
            time.sleep(0.15)

    return {
        "sourceStore": "entreprise.db (tables rag_documents & rag_chunks)",
        "model": JINA_EMBEDDINGS_MODEL,
        "documentsCount": len(documents),
        "totalChunks": total_chunks,
        "embeddedNow": embedded_now,
        "reusedFromDb": reused_from_db,
        "dimension": dimension,
    }


def query_db_corpus_rag(
    conn: sqlite3.Connection | None,
    jina_api_key: str,
    groq_api_key: str,
    question: str,
    top_k: int = RAG_TOP_K,
    min_score: float = RAG_MIN_SCORE,
) -> dict[str, Any]:
    """Interroge le RAG en piochant uniquement dans le corpus documentaire de entreprise.db."""
    if conn is None:
        raise RuntimeException("Base entreprise.db introuvable : RAG SQLite indisponible.")
    clean_question = (question or "").strip()
    if not clean_question:
        raise ValueError("La question RAG ne peut pas être vide.")

    ensure_rag_corpus_in_db(conn)
    rows = conn.execute("SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks").fetchall()

    if not rows or any(not r["embedding_json"] for r in rows):
        index_db_corpus_rag(conn, jina_api_key, force_reembed=False)
        rows = conn.execute("SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks").fetchall()

    [query_vector] = embed_with_jina(jina_api_key, [clean_question], task="retrieval.query")

    scored: list[dict[str, Any]] = []
    for row in rows:
        try:
            vec = json.loads(row["embedding_json"] or "[]")
        except json.JSONDecodeError:
            vec = []
        score = cosine_similarity(query_vector, vec if isinstance(vec, list) else [])
        scored.append(
            {
                "id": str(row["id"]),
                "source": str(row["source"]),
                "chunkIndex": int(row["chunk_index"]),
                "content": str(row["content"]),
                "score": score,
            }
        )

    scored.sort(key=lambda x: x["score"], reverse=True)
    top_chunks = scored[: max(1, int(top_k))]
    best_score = top_chunks[0]["score"] if top_chunks else 0.0

    if not top_chunks or best_score < min_score:
        return {
            "question": clean_question,
            "rejected": True,
            "bestScore": best_score,
            "minScore": min_score,
            "sources": top_chunks,
            "answer": "Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db.",
        }

    context_block = "\n\n".join(
        f"[Extrait #{i + 1} | source: {c['source']} | score: {c['score']:.4f}]\n{c['content']}"
        for i, c in enumerate(top_chunks)
    )

    prompt = "\n".join(
        [
            "Tu es un assistant strictement basé sur le corpus documentaire extrait de entreprise.db.",
            "Règles impératives :",
            "1. Utilise UNIQUEMENT les extraits fournis ci-dessous. N’invente aucune information externe.",
            "2. Cite explicitement le nom du document source sous la forme [source: nom-fichier.md].",
            '3. Si la réponse ne figure pas dans les extraits, réponds exactement : "Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db."',
            "",
            "EXTRAITS DU CORPUS (entreprise.db) :",
            context_block,
            "",
            f"QUESTION : {clean_question}",
        ]
    )

    answer = ask_groq(groq_api_key, prompt)
    return {
        "question": clean_question,
        "rejected": False,
        "bestScore": best_score,
        "minScore": min_score,
        "sources": top_chunks,
        "answer": answer,
    }


def load_mdr_skills(root_dir: Path) -> dict[str, dict[str, str]]:
    """Charge les skills .mdr présents dans .prompts/."""
    prompts_dir = root_dir / ".prompts"
    if not prompts_dir.is_dir():
        return {}

    skills: dict[str, dict[str, str]] = {}
    for file in sorted(prompts_dir.glob("*.mdr")):
        raw = file.read_text(encoding="utf-8").strip()
        if not raw:
            continue
        safe_name = re.sub(r"[^a-zA-Z0-9_-]", "_", file.stem)
        description = f"Skill {safe_name} (.mdr)"
        template = raw

        fm_match = re.match(r"^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n([\s\S]*)$", raw)
        if fm_match:
            fm_block, template_part = fm_match.group(1), fm_match.group(2)
            template = template_part.strip()
            desc_match = re.search(r"^description:\s*(.+)$", fm_block, flags=re.MULTILINE)
            if desc_match:
                description = desc_match.group(1).strip()

        skills[safe_name] = {
            "name": safe_name,
            "description": description,
            "template": template,
        }
    return skills


def compile_skill(skill: dict[str, str], user_input: str) -> str:
    """Compile le template d'un skill avec la donnée fournie."""
    template = skill["template"]
    if "{{input}}" in template:
        return template.replace("{{input}}", user_input)
    return f"{template}\n\n{user_input}".strip()


def send_rpc(msg_id: Any, result: Any = None, error: dict[str, Any] | None = None) -> None:
    """Écrit une trame JSON-RPC 2.0 sur stdout."""
    frame: dict[str, Any] = {"jsonrpc": "2.0", "id": msg_id}
    if error is not None:
        frame["error"] = error
    else:
        frame["result"] = result
    sys.stdout.write(json.dumps(frame, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> int:
    project_root = find_project_root(Path.cwd())
    load_dotenv(project_root / ".env")

    groq_api_key = os.environ.get("GROQ_API_KEY", "")
    jina_api_key = os.environ.get("JINA_API_KEY", "")
    conn = open_enterprise_db(project_root)
    skills = load_mdr_skills(project_root)

    cli_args = sys.argv[1:]
    if cli_args:
        is_prompt = "--prompt" in cli_args
        clean_args = [a for a in cli_args if a != "--prompt"]
        cmd = clean_args[0] if len(clean_args) > 0 else ""
        target = clean_args[1] if len(clean_args) > 1 else ""

        if cmd == "sql":
            try:
                rows = execute_safe_select(conn, target)
                print(json.dumps(rows, indent=2, ensure_ascii=False))
                return 0
            except Exception as exc:
                print(f"[Erreur SQL] {exc}", file=sys.stderr)
                return 1

        if cmd == "rag-index":
            try:
                force = "--force" in clean_args
                stats = index_db_corpus_rag(conn, jina_api_key, force_reembed=force)
                print(json.dumps(stats, indent=2, ensure_ascii=False))
                return 0
            except Exception as exc:
                print(f"[Erreur RAG Index] {exc}", file=sys.stderr)
                return 1

        if cmd == "rag-ask":
            try:
                res = query_db_corpus_rag(conn, jina_api_key, groq_api_key, target, RAG_TOP_K, RAG_MIN_SCORE)
                print(json.dumps(res, indent=2, ensure_ascii=False))
                return 0
            except Exception as exc:
                print(f"[Erreur RAG Ask] {exc}", file=sys.stderr)
                return 1

        if cmd not in skills:
            avail = ", ".join(skills.keys()) or "(aucun .mdr dans .prompts/)"
            extra = ", sql, rag-index, rag-ask" if conn is not None else ""
            print(f"Skill introuvable : '{cmd}'\nDisponibles : {avail}{extra}", file=sys.stderr)
            return 1

        target_path = Path(target)
        input_text = target_path.read_text(encoding="utf-8") if (target and target_path.is_file()) else target
        compiled = compile_skill(skills[cmd], input_text)
        if is_prompt:
            print(compiled)
            return 0

        try:
            print(ask_groq(groq_api_key, compiled))
            return 0
        except Exception as exc:
            print(f"[Erreur Groq] {exc}", file=sys.stderr)
            return 1

    # Boucle serveur JSON-RPC 2.0 / MCP sur stdin/stdout
    for line in sys.stdin:
        trimmed = line.strip()
        if not trimmed:
            continue
        try:
            req = json.loads(trimmed)
        except json.JSONDecodeError:
            continue
        if not isinstance(req, dict):
            continue

        msg_id = req.get("id")
        method = str(req.get("method", ""))
        params = req.get("params") if isinstance(req.get("params"), dict) else {}

        try:
            if method == "initialize":
                send_rpc(
                    msg_id,
                    {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {"prompts": {}, "tools": {}, "resources": {}},
                        "serverInfo": {"name": "mcp-unified-python", "version": "1.1.0"},
                    },
                )
            elif method == "notifications/initialized":
                pass
            elif method == "prompts/list":
                prompts = [
                    {
                        "name": s["name"],
                        "description": s["description"],
                        "arguments": [{"name": "input", "description": "Contexte {{input}}", "required": False}],
                    }
                    for s in skills.values()
                ]
                send_rpc(msg_id, {"prompts": prompts})
            elif method == "prompts/get":
                p_name = str(params.get("name", ""))
                if p_name not in skills:
                    send_rpc(msg_id, None, {"code": -32601, "message": f"Prompt introuvable : {p_name}"})
                else:
                    args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
                    compiled = compile_skill(skills[p_name], str(args.get("input", "")))
                    send_rpc(
                        msg_id,
                        {
                            "description": skills[p_name]["description"],
                            "messages": [{"role": "user", "content": {"type": "text", "text": compiled}}],
                        },
                    )
            elif method == "tools/list":
                tools = [
                    {
                        "name": s["name"],
                        "description": f"[Groq] {s['description']}",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"input": {"type": "string", "description": "Données à analyser"}},
                            "required": ["input"],
                        },
                    }
                    for s in skills.values()
                ]
                if conn is not None:
                    tools.extend(
                        [
                            {
                                "name": "query_database",
                                "description": "Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {"sql": {"type": "string", "minLength": 10, "description": "Requête SQL SELECT valide"}},
                                    "required": ["sql"],
                                },
                            },
                            {
                                "name": "rag_search_db_corpus",
                                "description": "Recherche RAG (Jina jina-embeddings-v3 + Groq) uniquement dans le corpus documentaire de entreprise.db.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "question": {"type": "string", "minLength": 3, "description": "Question métier à vérifier dans le corpus de entreprise.db"},
                                        "topK": {"type": "number", "description": "Nombre maximum d'extraits retournés (défaut: 3)"},
                                    },
                                    "required": ["question"],
                                },
                            },
                            {
                                "name": "rag_index_db_corpus",
                                "description": "Indexe ou met à jour les embeddings Jina (jina-embeddings-v3) du corpus documentaire de entreprise.db.",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {"force": {"type": "boolean", "description": "Force le recalcul complet (défaut: false)"}},
                                    "required": [],
                                },
                            },
                        ]
                    )
                send_rpc(msg_id, {"tools": tools})
            elif method == "tools/call":
                t_name = str(params.get("name", ""))
                t_args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}

                if t_name == "query_database":
                    try:
                        rows = execute_safe_select(conn, t_args.get("sql", ""))
                        send_rpc(msg_id, {"content": [{"type": "text", "text": json.dumps(rows, indent=2, ensure_ascii=False)}]})
                    except Exception as exc:
                        send_rpc(msg_id, {"isError": True, "content": [{"type": "text", "text": str(exc)}]})
                elif t_name == "rag_search_db_corpus":
                    try:
                        raw_topk = t_args.get("topK")
                        top_k = max(1, int(raw_topk)) if isinstance(raw_topk, (int, float)) else RAG_TOP_K
                        res = query_db_corpus_rag(conn, jina_api_key, groq_api_key, str(t_args.get("question", "")), top_k, RAG_MIN_SCORE)
                        send_rpc(msg_id, {"content": [{"type": "text", "text": json.dumps(res, indent=2, ensure_ascii=False)}]})
                    except Exception as exc:
                        send_rpc(msg_id, {"isError": True, "content": [{"type": "text", "text": str(exc)}]})
                elif t_name == "rag_index_db_corpus":
                    try:
                        stats = index_db_corpus_rag(conn, jina_api_key, force_reembed=bool(t_args.get("force", False)))
                        send_rpc(msg_id, {"content": [{"type": "text", "text": json.dumps(stats, indent=2, ensure_ascii=False)}]})
                    except Exception as exc:
                        send_rpc(msg_id, {"isError": True, "content": [{"type": "text", "text": str(exc)}]})
                elif t_name in skills:
                    try:
                        compiled = compile_skill(skills[t_name], str(t_args.get("input", "")))
                        reply = ask_groq(groq_api_key, compiled)
                        send_rpc(msg_id, {"content": [{"type": "text", "text": reply}]})
                    except Exception as exc:
                        send_rpc(msg_id, {"isError": True, "content": [{"type": "text", "text": str(exc)}]})
                else:
                    send_rpc(msg_id, None, {"code": -32601, "message": f"Outil introuvable : {t_name}"})
            elif method == "resources/list":
                resources = [
                    {
                        "uri": f"skill://{s['name']}",
                        "name": f"{s['name']}.mdr",
                        "description": s["description"],
                        "mimeType": "text/markdown",
                    }
                    for s in skills.values()
                ]
                if conn is not None:
                    resources.extend(
                        [
                            {
                                "uri": "db://schema",
                                "name": "Schéma SQLite (entreprise.db)",
                                "description": "Structure DDL (CREATE TABLE) de entreprise.db",
                                "mimeType": "text/plain",
                            },
                            {
                                "uri": "rag://db-corpus",
                                "name": "Corpus Documentaire SQLite (entreprise.db)",
                                "description": "Documents métier stockés dans entreprise.db pour le moteur RAG",
                                "mimeType": "application/json",
                            },
                        ]
                    )
                send_rpc(msg_id, {"resources": resources})
            elif method == "resources/templates/list":
                send_rpc(msg_id, {"resourceTemplates": []})
            elif method == "resources/read":
                uri = str(params.get("uri", ""))
                if uri == "db://schema" and conn is not None:
                    send_rpc(msg_id, {"contents": [{"uri": "db://schema", "mimeType": "text/plain", "text": get_database_schema(conn)}]})
                elif uri == "rag://db-corpus" and conn is not None:
                    corpus = ensure_rag_corpus_in_db(conn)
                    send_rpc(
                        msg_id,
                        {
                            "contents": [
                                {
                                    "uri": "rag://db-corpus",
                                    "mimeType": "application/json",
                                    "text": json.dumps(corpus["documents"], indent=2, ensure_ascii=False),
                                }
                            ]
                        },
                    )
                elif uri.startswith("skill://") and uri[8:] in skills:
                    s_name = uri[8:]
                    send_rpc(msg_id, {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": skills[s_name]["template"]}]})
                else:
                    send_rpc(msg_id, None, {"code": -32602, "message": f"Ressource inconnue : {uri}"})
            elif method == "ping":
                send_rpc(msg_id, {})
            else:
                if msg_id is not None:
                    send_rpc(msg_id, None, {"code": -32601, "message": f"Méthode inconnue : {method}"})
        except Exception as exc:
            if msg_id is not None:
                send_rpc(msg_id, None, {"code": -32603, "message": str(exc)})

    if conn is not None:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())