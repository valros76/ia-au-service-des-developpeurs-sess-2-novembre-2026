# TP 1 : Corrigé — Banc d'essai économique et paramètres de génération (Node.js / TypeScript)

Ce document présente la correction de référence complète (étapes 1, 2 et 3) pour un environnement **Node.js 20+ / TypeScript en local** (sans Docker).

---

## Étape 1 : Initialisation sécurisée du projet

### 1. Initialisation du dépôt et configuration ES Modules
```bash
mkdir -p formation-ia-dev/tp1
cd formation-ia-dev
git init
npm init -y
npm pkg set type="module"
```

### 2. Fichier `package.json`
```json
{
  "name": "formation-ia-dev",
  "version": "1.0.0",
  "description": "TP 1 - Banc d'essai économique et paramètres de génération",
  "type": "module",
  "scripts": {
    "benchmark": "node --env-file=.env tp1/benchmark-tokens.js"
  }
}
```
*(Note : Depuis Node.js v20.6+, l'option native `--env-file=.env` et l'API `fetch` intégrée permettent d'exécuter le script sans aucune dépendance externe).*

### 3. Fichier `.gitignore`
```gitignore
node_modules/
.env
dist/
.vscode/
.idea/
```

### 4. Fichiers `.env.example` et `.env`
**`.env.example`** (versionné sur Git) :
```env
MISTRAL_API_KEY=
GROQ_API_KEY=
```

**`.env`** (local, ignoré par Git) :
```env
MISTRAL_API_KEY=votre_cle_api_ici
```

---

## Étape 2 : Exploration des paramètres dans Continue (IDE)

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
```typescript
/**
 * Valide un numéro de SIRET français via l'algorithme de Luhn.
 * Prend en compte l'exception historique des établissements de La Poste (SIREN 356000000).
 *
 * @param siret - Le numéro de SIRET à vérifier (espaces tolérés)
 * @returns true si le numéro de SIRET est valide, false sinon
 */
export function isValidSiret(siret: string): boolean {
  if (typeof siret !== "string") {
    return false;
  }

  const cleanSiret = siret.replace(/\s+/g, "");

  if (!/^\d{14}$/.test(cleanSiret)) {
    return false;
  }

  // Exception historique : La Poste (SIREN 356000000)
  if (cleanSiret.startsWith("356000000")) {
    const sum = cleanSiret
      .split("")
      .reduce((acc, char) => acc + Number.parseInt(char, 10), 0);
    return sum % 5 === 0;
  }

  let total = 0;
  for (let i = 0; i < cleanSiret.length; i++) {
    let digit = Number.parseInt(cleanSiret[i], 10);

    if (i % 2 === 0) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9;
      }
    }

    total += digit;
  }

  return total % 10 === 0;
}
```

### 3. Synthèse des observations attendues

| Critère | `temperature = 0.0` (Déterministe) | `temperature = 1.2` (Créatif / Chaotique) |
| :--- | :--- | :--- |
| **Signature & Typage** | Identique sur les 3 essais (`export function isValidSiret(siret: string): boolean`). | Variable : fonction fléchée, classe `SiretValidator`, types union (`string \| number`) ou retour d'objet. |
| **Algorithme** | Boucle `for` classique claire et prévisible. | Chaînages complexes (`.split('').reverse().map().reduce()`) sujets aux erreurs d'indexation. |
| **Cas limites** | Nettoyage via `.replace(/\s+/g, '')`, regex `/^\d{14}$/` et exception La Poste présents. | Oublis fréquents (absence de gestion des espaces ou oubli de l'exception La Poste). |
| **Hallucinations** | Aucune. Utilisation standard de TypeScript et ES2022+. | Invention possible de méthodes inexistantes (`String.prototype.isDigit()`, `Number.isNumeric()`). |

---

## Étape 3 : Script de mesure `tp1/benchmark-tokens.js`

Exécution : `node --env-file=.env tp1/benchmark-tokens.js` (zéro dépendance externe).

```javascript
const API_KEY = process.env.MISTRAL_API_KEY;

if (!API_KEY) {
  console.error("Erreur : MISTRAL_API_KEY manquante dans le fichier .env");
  process.exit(1);
}

// Tarifs indicatifs Mistral Codestral ($ / 1M tokens) et taux de change
const PRICE_INPUT_PER_1M_USD = 0.30;
const PRICE_OUTPUT_PER_1M_USD = 0.90;
const USD_TO_EUR = 0.92;

async function runBenchmark(label, prompt) {
  const start = performance.now();

  const response = await fetch("https://api.mistral.ai/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "codestral-latest",
      temperature: 0.0,
      messages: [{ role: "user", content: prompt }],
    }),
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Erreur API (${response.status}) : ${errorText}`);
  }

  const data = await response.json();
  const end = performance.now();

  const latencyMs = end - start;
  const promptTokens = data.usage?.prompt_tokens ?? 0;
  const completionTokens = data.usage?.completion_tokens ?? 0;
  const totalTokens = data.usage?.total_tokens ?? promptTokens + completionTokens;

  const costUsd =
    (promptTokens / 1_000_000) * PRICE_INPUT_PER_1M_USD +
    (completionTokens / 1_000_000) * PRICE_OUTPUT_PER_1M_USD;
  const costEur = costUsd * USD_TO_EUR;

  return {
    label,
    latencyMs,
    promptTokens,
    completionTokens,
    totalTokens,
    costUsd,
    costEur,
  };
}

const verbosePrompt =
  "Bonjour ! J'espère que tu vas bien. Je développe actuellement une application métier en TypeScript " +
  "et j'aurais vraiment besoin de ton aide pour écrire une fonction propre, bien commentée avec de la JSDoc " +
  "et strictement typée qui me permettrait de vérifier si un numéro de SIRET français saisi par un utilisateur " +
  "est valide en utilisant l'algorithme de Luhn. Pense bien à gérer les espaces éventuels et le cas de La Poste. Merci d'avance !";

const concisePrompt =
  "TypeScript : fonction `isValidSiret(siret: string): boolean` via Luhn. " +
  "Gérer espaces, longueur 14 chiffres et exception La Poste (356000000). JSDoc concise, code uniquement.";

const results = [
  await runBenchmark("Prompt bavard", verbosePrompt),
  await runBenchmark("Prompt condensé", concisePrompt),
];

for (const res of results) {
  console.log(
    `[${res.label}] Latence: ${res.latencyMs.toFixed(2)} ms | ` +
      `Prompt: ${res.promptTokens} tok | Completion: ${res.completionTokens} tok | ` +
      `Total: ${res.totalTokens} tok | Coût: $${res.costUsd.toFixed(6)} (${res.costEur.toFixed(6)} €)`
  );
}
```

---

## Exemple de livrable `STATS.md`

```markdown
# Relevés Benchmark TP 1 — Modèle : `codestral-latest` (`temperature = 0.0`)

| Version du Prompt | Latence (ms) | Prompt Tokens | Completion Tokens | Total Tokens | Coût ($) | Coût (€) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Prompt bavard** | 1 350.20 | 86 | 290 | 376 | $0.000287 | 0.000264 € |
| **Prompt condensé** | 910.45 | 35 | 198 | 233 | $0.000189 | 0.000174 € |
| **Gain (Condensé vs Bavard)** | **-32.6 %** | **-59.3 %** | **-31.7 %** | **-38.0 %** | **-34.1 %** | **-34.1 %** |
```