# TP 1 : Corrigé — Banc d'essai économique et paramètres de génération (PHP)

Ce document présente la correction de référence complète (étapes 1, 2 et 3) pour un environnement **PHP 8.3+ en local** (sans Docker).

---

## Étape 1 : Initialisation sécurisée du projet

### 1. Initialisation du dépôt et des dépendances
```bash
mkdir -p formation-ia-dev/tp1
cd formation-ia-dev
git init
composer require guzzlehttp/guzzle vlucas/phpdotenv
```

### 2. Configuration de `composer.json`
```json
{
    "name": "formation/ia-dev",
    "description": "TP 1 - Banc d'essai économique et paramètres de génération",
    "type": "project",
    "require": {
        "php": "^8.3",
        "guzzlehttp/guzzle": "^7.9",
        "vlucas/phpdotenv": "^5.6"
    },
    "autoload": {
        "psr-4": {
            "App\\": "src/"
        }
    }
}
```
Exécutez ensuite `composer dump-autoload`.

### 3. Fichier `.gitignore`
```gitignore
/vendor/
.env
.idea/
.vscode/
```

### 4. Fichiers `.env.example` et `.env`
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
```php
<?php

declare(strict_types=1);

namespace App\Validator;

final class SiretValidator
{
    /**
     * Valide un numéro de SIRET français via l'algorithme de Luhn.
     * Prend en compte l'exception historique des établissements de La Poste (SIREN 356000000).
     */
    public static function isValid(string $siret): bool
    {
        $cleanSiret = preg_replace('/\s+/', '', $siret);

        if ($cleanSiret === null || preg_match('/^\d{14}$/', $cleanSiret) !== 1) {
            return false;
        }

        // Exception historique : La Poste (SIREN 356000000)
        if (str_starts_with($cleanSiret, '356000000')) {
            $digits = array_map('intval', str_split($cleanSiret));
            return array_sum($digits) % 5 === 0;
        }

        $sum = 0;
        for ($i = 0; $i < 14; $i++) {
            $digit = (int) $cleanSiret[$i];
            if ($i % 2 === 0) {
                $digit *= 2;
                if ($digit > 9) {
                    $digit -= 9;
                }
            }
            $sum += $digit;
        }

        return $sum % 10 === 0;
    }
}
```

### 3. Synthèse des observations attendues

| Critère | `temperature = 0.0` (Déterministe) | `temperature = 1.2` (Créatif / Chaotique) |
| :--- | :--- | :--- |
| **Signature & Typage** | Identique sur les 3 essais (`declare(strict_types=1);`, `string $siret`, retour `bool`). | Variable : fonction procédurale ou classe, parfois avec des types union (`string|int`). |
| **Algorithme** | Boucle `for` classique et lisible. | Tentatives complexes à base de `array_reduce()`, `strrev()` ou regex alambiquées. |
| **Cas limites** | Nettoyage via `preg_replace` et vérification de longueur systématiques. | Oublis fréquents (espaces ou exception La Poste). |
| **Hallucinations** | Aucune. Utilisation standard de PHP 8.3. | Invention possible de fonctions inexistantes (`str_digits()`, `luhn_checksum()`). |

---

## Étape 3 : Script de mesure `tp1/benchmark-tokens.php`

```php
<?php

declare(strict_types=1);

require_once __DIR__ . '/../vendor/autoload.php';

use Dotenv\Dotenv;
use GuzzleHttp\Client;

$dotenv = Dotenv::createImmutable(__DIR__ . '/..');
$dotenv->safeLoad();

$apiKey = $_ENV['MISTRAL_API_KEY'] ?? getenv('MISTRAL_API_KEY');
if (empty($apiKey)) {
    fwrite(STDERR, "Erreur : MISTRAL_API_KEY manquante dans le fichier .env\n");
    exit(1);
}

// Tarifs indicatifs Mistral Codestral ($ / 1M tokens) et taux de change
const PRICE_INPUT_PER_1M_USD = 0.30;
const PRICE_OUTPUT_PER_1M_USD = 0.90;
const USD_TO_EUR = 0.92;

$client = new Client([
    'base_uri' => 'https://api.mistral.ai/v1/',
    'timeout'  => 30.0,
]);

function runBenchmark(Client $client, string $apiKey, string $label, string $prompt): array
{
    $start = hrtime(true);

    $response = $client->post('chat/completions', [
        'headers' => [
            'Authorization' => 'Bearer ' . $apiKey,
            'Content-Type'  => 'application/json',
        ],
        'json' => [
            'model'       => 'codestral-latest',
            'temperature' => 0.0,
            'messages'    => [
                ['role' => 'user', 'content' => $prompt],
            ],
        ],
    ]);

    $end = hrtime(true);
    $latencyMs = ($end - $start) / 1_000_000;

    $data = json_decode((string) $response->getBody(), true, 512, JSON_THROW_ON_ERROR);
    $promptTokens = $data['usage']['prompt_tokens'] ?? 0;
    $completionTokens = $data['usage']['completion_tokens'] ?? 0;
    $totalTokens = $data['usage']['total_tokens'] ?? ($promptTokens + $completionTokens);

    $costUsd = ($promptTokens / 1_000_000) * PRICE_INPUT_PER_1M_USD
             + ($completionTokens / 1_000_000) * PRICE_OUTPUT_PER_1M_USD;
    $costEur = $costUsd * USD_TO_EUR;

    return [
        'label'             => $label,
        'latency_ms'        => $latencyMs,
        'prompt_tokens'     => $promptTokens,
        'completion_tokens' => $completionTokens,
        'total_tokens'      => $totalTokens,
        'cost_usd'          => $costUsd,
        'cost_eur'          => $costEur,
    ];
}

$verbosePrompt = "Bonjour ! J'espère que tu vas bien. Je développe actuellement une application métier en PHP 8.3 "
    . "et j'aurais vraiment besoin de ton aide pour écrire une fonction propre, bien commentée avec de la PHPDoc "
    . "et strictement typée qui me permettrait de vérifier si un numéro de SIRET français saisi par un utilisateur "
    . "est valide en utilisant l'algorithme de Luhn. Pense bien à gérer les espaces éventuels et le cas de La Poste. Merci d'avance !";

$concisePrompt = "PHP 8.3 strict_types=1 : fonction `isValidSiret(string \$siret): bool` via Luhn. "
    . "Gérer espaces, longueur 14 chiffres et exception La Poste (356000000). PHPDoc concise, code uniquement.";

$results = [
    runBenchmark($client, $apiKey, 'Prompt bavard', $verbosePrompt),
    runBenchmark($client, $apiKey, 'Prompt condensé', $concisePrompt),
];

foreach ($results as $res) {
    printf(
        "[%s] Latence: %.2f ms | Prompt: %d tok | Completion: %d tok | Total: %d tok | Coût: $%.6f (%.6f €)\n",
        $res['label'],
        $res['latency_ms'],
        $res['prompt_tokens'],
        $res['completion_tokens'],
        $res['total_tokens'],
        $res['cost_usd'],
        $res['cost_eur']
    );
}
```

---

## Exemple de livrable `STATS.md`

```markdown
# Relevés Benchmark TP 1 — Modèle : `codestral-latest` (`temperature = 0.0`)

| Version du Prompt | Latence (ms) | Prompt Tokens | Completion Tokens | Total Tokens | Coût ($) | Coût (€) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Prompt bavard** | 1 420.50 | 89 | 310 | 399 | $0.000306 | 0.000281 € |
| **Prompt condensé** | 980.15 | 38 | 215 | 253 | $0.000205 | 0.000189 € |
| **Gain (Condensé vs Bavard)** | **-31.0 %** | **-57.3 %** | **-30.6 %** | **-36.6 %** | **-33.0 %** | **-33.0 %** |
```