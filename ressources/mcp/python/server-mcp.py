#!/usr/bin/env python3

"""
- Nécessite une clé env : GROQ_API_KEY="votreCleGroq"
"""

"""
- Pour tester le serveur en CLI : 
- exécution (exemple de commande) : python3 server-mcp.py revue_php85 exemple_sale.php
- 
- Prévisualisation (sans consommation API) : python3 server-mcp.py revue_php85 exemple_sale.php --prompt
"""

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

# ============================================================================
# 1. CHARGEMENT DU .ENV & CONFIGURATION GROQ
# ============================================================================
PROJECT_ROOT = Path.cwd()
env_file = PROJECT_ROOT / ".env"

if env_file.is_file():
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip("\"'"))

GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")
if not GROQ_API_KEY:
    print("[Erreur fatale] Variable GROQ_API_KEY absente (vérifie ton .env).", file=sys.stderr)
    sys.exit(1)

# ============================================================================
# 2. MODÈLE SKILL & CLIENT GROQ (Bibliothèque standard)
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
            "User-Agent": "mcp-initial-python/1.0",
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
# 3. MODE CLI DIRECT (Tests utilisateurs dans le terminal)
# ============================================================================
if len(sys.argv) > 1:
    args = sys.argv[1:]
    is_prompt = "--prompt" in args
    clean_args = [a for a in args if a != "--prompt"]

    skill_name = clean_args[0] if clean_args else ""
    target = clean_args[1] if len(clean_args) > 1 else None

    if skill_name not in SKILLS:
        avail = ", ".join(SKILLS.keys()) or "(aucun .mdr dans .prompts/)"
        print(f"Skill introuvable : '{skill_name}'\nDisponibles : {avail}", file=sys.stderr)
        sys.exit(1)

    if target and Path(target).is_file():
        user_input = Path(target).read_text(encoding="utf-8")
    elif target is not None:
        user_input = target
    elif not sys.stdin.isatty():
        user_input = sys.stdin.read()
    else:
        user_input = ""

    compiled = SKILLS[skill_name].compile(user_input)
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
# 4. MOTEUR JSON-RPC 2.0 / MCP (STDIO)
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
                "serverInfo": {"name": "mcp-initial-python", "version": "1.0.0"},
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
            send(msg_id, {
                "tools": [
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
            })
        case "tools/call":
            name = params.get("name", "")
            if name not in SKILLS:
                send(msg_id, error={"code": -32601, "message": f"Outil introuvable : {name}"})
            else:
                try:
                    prompt = SKILLS[name].compile(str((params.get("arguments") or {}).get("input", "")))
                    send(msg_id, {"content": [{"type": "text", "text": ask_groq(prompt)}]})
                except Exception as e:
                    send(msg_id, error={"code": -32000, "message": str(e)})
        case "resources/list":
            send(msg_id, {
                "resources": [
                    {"uri": f"skill://{s.name}", "name": f"{s.name}.mdr", "description": s.description, "mimeType": "text/markdown"}
                    for s in SKILLS.values()
                ]
            })
        case "resources/read":
            uri = str(params.get("uri", ""))
            name = uri.removeprefix("skill://") if uri.startswith("skill://") else ""
            if name not in SKILLS:
                send(msg_id, error={"code": -32602, "message": f"Ressource inconnue : {uri}"})
            else:
                send(msg_id, {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": SKILLS[name].template}]})
        case "ping":
            send(msg_id, {})
        case _ if msg_id is not None:
            send(msg_id, error={"code": -32601, "message": f"Méthode inconnue : {method}"})