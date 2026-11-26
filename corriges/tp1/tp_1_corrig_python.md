# TP 1 : Corrigé — Banc d'essai économique et paramètres de génération (Python)

Ce document présente la correction de référence complète (étapes 1, 2 et 3) pour un environnement **Python 3.11+ en local** (sans Docker).

---

## Étape 1 : Initialisation sécurisée du projet

### 1. Initialisation du dépôt, de l'environnement virtuel et des dépendances
```bash
mkdir -p formation-ia-dev/tp1
cd formation-ia-dev
git init
python3 -m venv .venv
source .venv/bin/activate  # Sous Windows : .venv\Scripts\activate
pip install httpx python-dotenv
pip freeze > requirements.txt
```

### 2. Fichier `.gitignore`
```gitignore
.venv/
__pycache__/
*.py[cod]
.env
.idea/
.vscode/
```

### 3. Fichiers `.env.example` et `.env`
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
```python
import re


def is_valid_siret(siret: str) -> bool:
    """Valide un numéro de SIRET français via l'algorithme de Luhn.

    Prend en compte l'exception historique des établissements de La Poste
    (SIREN 356000000).
    """
    if not isinstance(siret, str):
        return False

    clean_siret = re.sub(r"\s+", "", siret)

    if not re.fullmatch(r"\d{14}", clean_siret):
        return False

    # Exception historique : La Poste (SIREN 356000000)
    if clean_siret.startswith("356000000"):
        return sum(int(digit) for digit in clean_siret) % 5 == 0

    total = 0
    for index, char in enumerate(clean_siret):
        digit = int(char)
        if index % 2 == 0:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit

    return total % 10 == 0
```

### 3. Synthèse des observations attendues

| Critère | `temperature = 0.0` (Déterministe) | `temperature = 1.2` (Créatif / Chaotique) |
| :--- | :--- | :--- |
| **Signature & Typage** | Identique sur les 3 essais (`def is_valid_siret(siret: str) -> bool:`). | Variable : tantôt une fonction, tantôt une classe `SiretValidator`, parfois sans type hints. |
| **Algorithme** | Boucle `for` avec `enumerate()` claire et pythonique. | List comprehensions imbriquées illisibles, découpages par slices (`[::-1]`, `[::2]`) sources d'erreurs. |
| **Cas limites** | Nettoyage via `re.sub` et vérification `len() == 14`. | Oublis fréquents (espaces ou exception La Poste). |
| **Hallucinations** | Aucune. Utilisation de la bibliothèque standard Python 3. | Invention possible de méthodes inexistantes (`siret.strip_spaces()`, `math.luhn()`). |

---

## Étape 3 : Script de mesure `tp1/benchmark_tokens.py`

```python
import os
import sys
import time
import httpx
from dotenv import load_dotenv

load_dotenv()

API_KEY = os.getenv("MISTRAL_API_KEY")
if not API_KEY:
    print("Erreur : MISTRAL_API_KEY manquante dans le fichier .env", file=sys.stderr)
    sys.exit(1)

# Tarifs indicatifs Mistral Codestral ($ / 1M tokens) et taux de change
PRICE_INPUT_PER_1M_USD = 0.30
PRICE_OUTPUT_PER_1M_USD = 0.90
USD_TO_EUR = 0.92


def run_benchmark(label: str, prompt: str) -> dict:
    url = "https://api.mistral.ai/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": "codestral-latest",
        "temperature": 0.0,
        "messages": [{"role": "user", "content": prompt}],
    }

    start = time.perf_counter()
    with httpx.Client(timeout=30.0) as client:
        response = client.post(url, headers=headers, json=payload)
        response.raise_for_status()
    end = time.perf_counter()

    latency_ms = (end - start) * 1000
    data = response.json()
    usage = data.get("usage", {})

    prompt_tokens = usage.get("prompt_tokens", 0)
    completion_tokens = usage.get("completion_tokens", 0)
    total_tokens = usage.get("total_tokens", prompt_tokens + completion_tokens)

    cost_usd = (prompt_tokens / 1_000_000) * PRICE_INPUT_PER_1M_USD + (
        completion_tokens / 1_000_000
    ) * PRICE_OUTPUT_PER_1M_USD
    cost_eur = cost_usd * USD_TO_EUR

    return {
        "label": label,
        "latency_ms": latency_ms,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": total_tokens,
        "cost_usd": cost_usd,
        "cost_eur": cost_eur,
    }


if __name__ == "__main__":
    verbose_prompt = (
        "Bonjour ! J'espère que tu vas bien. Je développe actuellement une application métier en Python 3.11 "
        "et j'aurais vraiment besoin de ton aide pour écrire une fonction propre, bien commentée avec une docstring "
        "et strictement typée qui me permettrait de vérifier si un numéro de SIRET français saisi par un utilisateur "
        "est valide en utilisant l'algorithme de Luhn. Pense bien à gérer les espaces éventuels et le cas de La Poste. Merci d'avance !"
    )

    concise_prompt = (
        "Python 3.11+ : fonction `is_valid_siret(siret: str) -> bool` via Luhn. "
        "Gérer espaces, longueur 14 chiffres et exception La Poste (356000000). Docstring concise, code uniquement."
    )

    for label, prompt in [("Prompt bavard", verbose_prompt), ("Prompt condensé", concise_prompt)]:
        res = run_benchmark(label, prompt)
        print(
            f"[{res['label']}] Latence: {res['latency_ms']:.2f} ms | "
            f"Prompt: {res['prompt_tokens']} tok | Completion: {res['completion_tokens']} tok | "
            f"Total: {res['total_tokens']} tok | Coût: ${res['cost_usd']:.6f} ({res['cost_eur']:.6f} €)"
        )
```

---

## Exemple de livrable `STATS.md`

```markdown
# Relevés Benchmark TP 1 — Modèle : `codestral-latest` (`temperature = 0.0`)

| Version du Prompt | Latence (ms) | Prompt Tokens | Completion Tokens | Total Tokens | Coût ($) | Coût (€) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Prompt bavard** | 1 390.40 | 87 | 295 | 382 | $0.000292 | 0.000268 € |
| **Prompt condensé** | 945.10 | 36 | 202 | 238 | $0.000193 | 0.000177 € |
| **Gain (Condensé vs Bavard)** | **-32.0 %** | **-58.6 %** | **-31.5 %** | **-37.7 %** | **-33.9 %** | **-33.9 %** |
```