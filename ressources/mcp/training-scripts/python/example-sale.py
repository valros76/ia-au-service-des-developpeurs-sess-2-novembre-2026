#!/usr/bin/env python3

"""
- Nécessite une clé env : GROQ_API_KEY="votreCleGroq" (pour les skills .mdr)
- Si entreprise.db est présente, active aussi les outils SQLite du TP 3 (db://schema + query_database)

- Pour tester le serveur en CLI :
- exécution skill : python3 server-mcp.py revue_python exemple_sale.py
- prévisualisation : python3 server-mcp.py revue_python exemple_sale.py --prompt
- requête SQLite  : python3 server-mcp.py sql "SELECT * FROM produits"
"""

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import urllib.error
import urllib.request

# ============================================================================
# 1. RECHERCHE RACINE PROJET & CHARGEMENT DU .ENV
# ============================================================================
def find_project_root(start_dir: Path) -> Path:
    for d in [start_dir, *start_dir.parents]:
        if (d / ".prompts").is_dir() or (d / ".env").is_file() or (d / "entreprise.db").is_file():
            return d
    return start_dir

PROJECT_ROOT = find_project_root(Path.cwd())
env_file = PROJECT_ROOT / ".env"

if env_file.is_file():
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip("\"'"))

GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")

# ============================================================================
# 2. MODULE SQLITE (TP 3 - actif automatiquement si entreprise.db existe)
# ============================================================================
DB_PATH = PROJECT_ROOT / "entreprise.db"
HAS_DB = DB_PATH.is_file()
FORBIDDEN_KEYWORDS = re.compile(
    r"\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b",
    re.IGNORECASE,
)

def open_readonly_db() -> sqlite3.Connection:
    if not HAS_DB:
        raise RuntimeError("Base entreprise.db introuvable dans le projet.")
    conn = sqlite3.connect(f"file:{DB_PATH.as_posix()}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn

def execute_safe_select(raw_sql: str) -> list[dict]:
    if not isinstance(raw_sql, str) or len(raw_sql.strip()) < 10:
        raise ValueError("Paramètre sql invalide (minimum 10 caractères requis).")
    cleaned = raw_sql.strip().rstrip(";")
    if ";" in cleaned:
        raise ValueError("Sécurité : requêtes multiples interdites.")
    if not re.match(r"^SELECT\b", cleaned, re.IGNORECASE):
        raise ValueError("Sécurité : seul SELECT est autorisé.")
    if FORBIDDEN_KEYWORDS.search(cleaned):
        raise ValueError("Sécurité : mot-clé SQL interdit.")

    final_sql = cleaned if re.search(r"\bLIMIT\s+\d+", cleaned, re.IGNORECASE) else f"{cleaned} LIMIT 50"
    with open_readonly_db() as conn:
        rows = conn.execute(final_sql).fetchall()
        return [dict(row) for row in rows]

def get_database_schema() -> str:
    if not HAS_DB:
        return ""
    with open_readonly_db() as conn:
        rows = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        ).fetchall()
        return "\n\n".join(f"{r['sql']};" for r in rows if r["sql"])

# ============================================================================
# 3. MODULE SKILLS .MDR + GROQ (Base initiale conservée)
# ============================================================================
@dataclass(frozen=True)
class Skill:
    name: str
    description: str
    template: str

    def compile(self, user_input: str) -> str:
        if "{{input}}" in self.template:
            return self.template.replace("{{input}}", user_input)
        return f"{self.template}\n\n{user_input}".strip()

def ask_groq(prompt: str, model: str = "llama-3.3-70b-versatile") -> str:
    if not GROQ_API_KEY:
        raise RuntimeError("Variable GROQ_API_KEY absente dans .env.")

    payload = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")

    req = urllib.request.Request(
        "https://api.groq.com/openai/v1/chat/completions",
        data=payload,
        headers={
            "Authorization": f"Bearer {GROQ_API_KEY}",
            "Content-Type": "application/json",
            "User-Agent": "mcp-unified-python/1.0",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Erreur HTTP Groq {e.code} : {e.read().decode('utf-8', errors='ignore')}") from e

def load_mdr_skills(root_dir: Path) -> dict[str, Skill]:
    prompts_dir = root_dir / ".prompts"
    if not prompts_dir.is_dir():
        return {}

    skills: dict[str, Skill] = {}
    for file in sorted(prompts_dir.glob("*.mdr")):
        raw = file.read_text(encoding="utf-8").strip()
        if not raw:
            continue

        name = re.sub(r"[^a-zA-Z0-9_-]", "_", file.stem)
        description = f"Skill {name} (.mdr)"
        body = raw

        if match := re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)$", raw, re.DOTALL):
            body = match.group(2).strip()
            if desc := re.search(r"^description:\s*(.+)$", match.group(1), re.MULTILINE):
                description = desc.group(1).strip()

        skills[name] = Skill(name, description, body)
    return skills

SKILLS = load_mdr_skills(PROJECT_ROOT)

# ============================================================================
# 4. MODE CLI DIRECT
# ============================================================================
if len(sys.argv) > 1:
    args = sys.argv[1:]
    is_prompt = "--prompt" in args
    clean_args = [a for a in args if a != "--prompt"]

    cmd = clean_args[0] if clean_args else ""
    target = clean_args[1] if len(clean_args) > 1 else ""

    if cmd == "sql":
        try:
            print(json.dumps(execute_safe_select(target), indent=2, ensure_ascii=False))
            sys.exit(0)
        except Exception as e:
            print(f"[Erreur SQL] {e}", file=sys.stderr)
            sys.exit(1)

    if cmd not in SKILLS:
        avail = ", ".join(SKILLS.keys()) or "(aucun .mdr dans .prompts/)"
        suffix = ", sql" if HAS_DB else ""
        print(f"Skill introuvable : '{cmd}'\nDisponibles : {avail}{suffix}", file=sys.stderr)
        sys.exit(1)

    user_input = Path(target).read_text(encoding="utf-8") if (target and Path(target).is_file()) else target
    compiled = SKILLS[cmd].compile(user_input)

    if is_prompt:
        print(compiled)
        sys.exit(0)

    try:
        print(ask_groq(compiled))
        sys.exit(0)
    except Exception as e:
        print(f"[Erreur Groq] {e}", file=sys.stderr)
        sys.exit(1)

# ============================================================================
# 5. MOTEUR JSON-RPC 2.0 / MCP UNIFIÉ (MDR + SQLITE)
# ============================================================================
def send(msg_id: int | str | None, result: object = None, error: dict | None = None) -> None:
    frame: dict = {"jsonrpc": "2.0", "id": msg_id}
    if error is not None:
        frame["error"] = error
    else:
        frame["result"] = result
    sys.stdout.write(json.dumps(frame, ensure_ascii=False) + "\n")
    sys.stdout.flush()

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        req = json.loads(line)
    except json.JSONDecodeError:
        continue

    msg_id = req.get("id")
    method = req.get("method")
    params = req.get("params") or {}

    match method:
        case "initialize":
            send(msg_id, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"prompts": {}, "tools": {}, "resources": {}},
                "serverInfo": {"name": "mcp-unified-python", "version": "1.0.0"},
            })
        case "notifications/initialized":
            pass
        case "prompts/list":
            send(msg_id, {
                "prompts": [
                    {
                        "name": s.name,
                        "description": s.description,
                        "arguments": [{"name": "input", "description": "Contexte {{input}}", "required": False}],
                    }
                    for s in SKILLS.values()
                ]
            })
        case "prompts/get":
            name = params.get("name", "")
            if name not in SKILLS:
                send(msg_id, error={"code": -32601, "message": f"Prompt introuvable : {name}"})
            else:
                text = SKILLS[name].compile(str((params.get("arguments") or {}).get("input", "")))
                send(msg_id, {
                    "description": SKILLS[name].description,
                    "messages": [{"role": "user", "content": {"type": "text", "text": text}}],
                })
        case "tools/list":
            tools_list = [
                {
                    "name": s.name,
                    "description": f"[Groq] {s.description}",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"input": {"type": "string", "description": "Données à analyser"}},
                        "required": ["input"],
                    },
                }
                for s in SKILLS.values()
            ]
            if HAS_DB:
                tools_list.append({
                    "name": "query_database",
                    "description": "Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"sql": {"type": "string", "minLength": 10, "description": "Requête SQL SELECT valide"}},
                        "required": ["sql"],
                    },
                })
            send(msg_id, {"tools": tools_list})
        case "tools/call":
            name = params.get("name", "")
            args_dict = params.get("arguments") or {}
            if name == "query_database":
                try:
                    rows = execute_safe_select(str(args_dict.get("sql", "")))
                    send(msg_id, {"content": [{"type": "text", "text": json.dumps(rows, indent=2, ensure_ascii=False)}]})
                except Exception as e:
                    send(msg_id, {"isError": True, "content": [{"type": "text", "text": str(e)}]})
            elif name not in SKILLS:
                send(msg_id, error={"code": -32601, "message": f"Outil introuvable : {name}"})
            else:
                try:
                    prompt = SKILLS[name].compile(str(args_dict.get("input", "")))
                    send(msg_id, {"content": [{"type": "text", "text": ask_groq(prompt)}]})
                except Exception as e:
                    send(msg_id, {"isError": True, "content": [{"type": "text", "text": str(e)}]})
        case "resources/list":
            res_list = [
                {"uri": f"skill://{s.name}", "name": f"{s.name}.mdr", "description": s.description, "mimeType": "text/markdown"}
                for s in SKILLS.values()
            ]
            if HAS_DB:
                res_list.append({
                    "uri": "db://schema",
                    "name": "Schéma SQLite (entreprise.db)",
                    "description": "Structure DDL (CREATE TABLE) de la base entreprise.db",
                    "mimeType": "text/plain",
                })
            send(msg_id, {"resources": res_list})
        case "resources/read":
            uri = str(params.get("uri", ""))
            if uri == "db://schema" and HAS_DB:
                send(msg_id, {"contents": [{"uri": "db://schema", "mimeType": "text/plain", "text": get_database_schema()}]})
            else:
                name = uri.removeprefix("skill://") if uri.startswith("skill://") else ""
                if name not in SKILLS:
                    send(msg_id, error={"code": -32602, "message": f"Ressource inconnue : {uri}"})
                else:
                    send(msg_id, {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": SKILLS[name].template}]})
        case "ping":
            send(msg_id, {})
        case _ if msg_id is not None:
            send(msg_id, error={"code": -32601, "message": f"Méthode inconnue : {method}"})