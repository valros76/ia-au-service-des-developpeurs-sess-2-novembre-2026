# TP 4 : Corrigé Complet — Chaîne RAG sans framework et workflow agentique combiné (PHP 8.3+ natif)

Ce document fournit l'implémentation de référence complète du **TP 4** en **PHP 8.3+ pur** (`curl`, `mbstring`, `declare(strict_types=1)`, zéro dépendance externe), utilisant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération.

---

## Partie A : Moteur RAG minimaliste en PHP pur (`tp4-rag/`)

### 1. Exemple de corpus métier (`tp4-rag/docs/`)

Voici un exemple de rédaction conforme aux contraintes (> 1 200 caractères par fichier et aligné avec les tables `clients`, `produits` et `commandes` du TP 3).

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

### 2. Utilitaire de variables d'environnement `tp4-rag/env.php`

```php
<?php

declare(strict_types=1);

function loadEnv(string $envPath): void
{
    if (!file_exists($envPath)) {
        return;
    }

    $lines = file($envPath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if ($lines === false) {
        return;
    }

    foreach ($lines as $line) {
        $trimmed = trim($line);
        if ($trimmed === '' || str_starts_with($trimmed, '#') || !str_contains($trimmed, '=')) {
            continue;
        }

        [$key, $value] = explode('=', $trimmed, 2);
        $key = trim($key);
        $value = trim($value, " \t\n\r\0\x0B\"'");

        if ($key !== '' && getenv($key) === false) {
            putenv("{$key}={$value}");
            $_ENV[$key] = $value;
        }
    }
}
```

---

### 3. Module de découpage UTF-8 `tp4-rag/chunker.php`

```php
<?php

declare(strict_types=1);

/**
 * Découpe un texte UTF-8 en segments de taille maximale $chunkSize
 * en conservant un chevauchement de $overlap caractères.
 *
 * @return list<string>
 */
function chunkText(string $text, int $chunkSize = 500, int $overlap = 75): array
{
    $cleanText = trim(str_replace("\r\n", "\n", $text));
    if ($cleanText === '') {
        return [];
    }

    if ($overlap >= $chunkSize) {
        throw new InvalidArgumentException('Le chevauchement ($overlap) doit être strictement inférieur à $chunkSize.');
    }

    $length = mb_strlen($cleanText, 'UTF-8');
    $chunks = [];
    $start = 0;

    while ($start < $length) {
        $slice = mb_substr($cleanText, $start, $chunkSize, 'UTF-8');
        $chunks[] = $slice;

        if ($start + $chunkSize >= $length) {
            break;
        }

        $start += ($chunkSize - $overlap);
    }

    return $chunks;
}

// Validation locale (sans appel API) : php chunker.php
if (realpath($_SERVER['SCRIPT_FILENAME'] ?? '') === __FILE__) {
    $files = glob(__DIR__ . '/docs/*.md') ?: [];
    sort($files);

    foreach ($files as $filePath) {
        $content = file_get_contents($filePath) ?: '';
        $chunks = chunkText($content, 500, 75);
        $fileName = basename($filePath);
        echo sprintf("Fichier \"%s\" (%d car.) -> %d morceaux\n", $fileName, mb_strlen($content, 'UTF-8'), count($chunks));

        for ($i = 0; $i < count($chunks) - 1; $i++) {
            $tail = mb_substr($chunks[$i], -75, null, 'UTF-8');
            $head = mb_substr($chunks[$i + 1], 0, 75, 'UTF-8');
            if ($tail !== $head) {
                fwrite(STDERR, "Erreur de chevauchement entre #{$i} et #" . ($i + 1) . " dans {$fileName}\n");
            }
        }
    }
    echo "Validation du chevauchement UTF-8 (75 caractères) réussie !\n";
}
```

---

### 4. Script d'indexation `tp4-rag/indexer.php`

Exécution : `php indexer.php`

```php
<?php

declare(strict_types=1);

require_once __DIR__ . '/env.php';
require_once __DIR__ . '/chunker.php';

loadEnv(__DIR__ . '/../.env');
$jinaApiKey = getenv('JINA_API_KEY') ?: '';

if ($jinaApiKey === '') {
    fwrite(STDERR, "Erreur : JINA_API_KEY manquante dans le fichier .env\n");
    exit(1);
}

/**
 * Appelle l'API Jina AI (jina-embeddings-v3) pour vectoriser un passage documentaire.
 *
 * @return list<float>
 */
function embedPassage(string $text, string $apiKey): array
{
    $payload = json_encode([
        'model' => 'jina-embeddings-v3',
        'task'  => 'retrieval.passage',
        'input' => [$text],
    ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);

    $ch = curl_init('https://api.jina.ai/v1/embeddings');
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER     => [
            'Authorization: Bearer ' . $apiKey,
            'Content-Type: application/json',
        ],
        CURLOPT_POSTFIELDS     => $payload,
        CURLOPT_TIMEOUT        => 30,
    ]);

    $rawResponse = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlError = curl_error($ch);
    curl_close($ch);

    if ($rawResponse === false || $httpCode !== 200) {
        throw new RuntimeException("Erreur API Jina ({$httpCode}) : {$curlError} {$rawResponse}");
    }

    $data = json_decode($rawResponse, true, 512, JSON_THROW_ON_ERROR);
    return $data['data'][0]['embedding'];
}

$startTime = hrtime(true);
$files = glob(__DIR__ . '/docs/*.md') ?: [];
sort($files);

if (count($files) === 0) {
    fwrite(STDERR, "Aucun fichier .md trouvé dans le dossier docs/.\n");
    exit(1);
}

$vectorStore = [];

foreach ($files as $filePath) {
    $fileName = basename($filePath);
    $content = file_get_contents($filePath) ?: '';
    $chunks = chunkText($content, 500, 75);

    echo sprintf("Indexation de %s (%d morceaux)...\n", $fileName, count($chunks));

    foreach ($chunks as $index => $chunk) {
        $embedding = embedPassage($chunk, $jinaApiKey);
        $vectorStore[] = [
            'id'        => "{$fileName}#{$index}",
            'source'    => $fileName,
            'content'   => $chunk,
            'embedding' => $embedding,
        ];

        // Pause de 200 ms pour respecter les limites de débit
        usleep(200_000);
    }
}

$storePath = __DIR__ . '/vector-store.json';
file_put_contents(
    $storePath,
    json_encode($vectorStore, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR)
);

$durationMs = (hrtime(true) - $startTime) / 1_000_000;
$dimension = count($vectorStore[0]['embedding'] ?? []);

echo "\n--- Indexation terminée ---\n";
echo "Vecteurs générés : " . count($vectorStore) . "\n";
echo "Dimension        : {$dimension}\n";
echo sprintf("Temps total      : %.2f ms\n", $durationMs);
echo "Fichier généré   : {$storePath}\n";
```

---

### 5. Script d'interrogation `tp4-rag/ask-rag.php`

Exécution : `php ask-rag.php "Quel est le seuil de validation d'un remboursement de 250 euros ?"`

```php
<?php

declare(strict_types=1);

require_once __DIR__ . '/env.php';

loadEnv(__DIR__ . '/../.env');
$jinaApiKey = getenv('JINA_API_KEY') ?: '';
$groqApiKey = getenv('GROQ_API_KEY') ?: '';

if ($jinaApiKey === '' || $groqApiKey === '') {
    fwrite(STDERR, "Erreur : JINA_API_KEY et GROQ_API_KEY doivent être définies dans .env\n");
    exit(1);
}

$question = trim($argv[1] ?? '');
if ($question === '') {
    fwrite(STDERR, "Usage : php ask-rag.php \"Votre question\"\n");
    exit(1);
}

const SIMILARITY_THRESHOLD = 0.35;
const TOP_K = 3;

/**
 * Calcule la similarité cosinus entre deux vecteurs numériques.
 *
 * @param list<float> $vecA
 * @param list<float> $vecB
 */
function cosineSimilarity(array $vecA, array $vecB): float
{
    $dotProduct = 0.0;
    $normA = 0.0;
    $normB = 0.0;

    $count = count($vecA);
    for ($i = 0; $i < $count; $i++) {
        $dotProduct += $vecA[$i] * $vecB[$i];
        $normA += $vecA[$i] * $vecA[$i];
        $normB += $vecB[$i] * $vecB[$i];
    }

    $denominator = sqrt($normA) * sqrt($normB);
    return $denominator === 0.0 ? 0.0 : $dotProduct / $denominator;
}

/**
 * Vectorise la question utilisateur via Jina AI (task: retrieval.query).
 *
 * @return list<float>
 */
function embedQuery(string $queryText, string $apiKey): array
{
    $payload = json_encode([
        'model' => 'jina-embeddings-v3',
        'task'  => 'retrieval.query',
        'input' => [$queryText],
    ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);

    $ch = curl_init('https://api.jina.ai/v1/embeddings');
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER     => [
            'Authorization: Bearer ' . $apiKey,
            'Content-Type: application/json',
        ],
        CURLOPT_POSTFIELDS     => $payload,
        CURLOPT_TIMEOUT        => 30,
    ]);

    $rawResponse = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($rawResponse === false || $httpCode !== 200) {
        throw new RuntimeException("Erreur Jina Query ({$httpCode}) : {$rawResponse}");
    }

    $data = json_decode($rawResponse, true, 512, JSON_THROW_ON_ERROR);
    return $data['data'][0]['embedding'];
}

/**
 * Interroge Groq (llama-3.3-70b-versatile) uniquement à partir du contexte extrait.
 *
 * @param list<array{id: string, source: string, content: string, score: float}> $topChunks
 */
function generateAnswer(string $userQuestion, array $topChunks, string $apiKey): string
{
    $contextParts = [];
    foreach ($topChunks as $chunk) {
        $scoreFmt = number_format($chunk['score'], 4);
        $contextParts[] = "--- EXTRAIT [source: {$chunk['source']}] (score: {$scoreFmt}) ---\n{$chunk['content']}";
    }
    $formattedContext = implode("\n\n", $contextParts);

    $systemPrompt = implode("\n", [
        'Tu es un assistant documentaire interne strict.',
        'RÈGLES IMPÉRATIVES :',
        '1. Réponds UNIQUEMENT à partir des extraits de contexte fournis ci-dessous.',
        '2. Interdiction absolue d\'utiliser tes connaissances générales ou d\'inventer des informations.',
        '3. Cite systématiquement le fichier source sous la forme [source: nom-du-fichier.md] après chaque affirmation.',
        '4. Si le contexte ne permet pas de répondre à la question, réponds exactement : "Je ne dispose pas de cette information dans la documentation fournie."',
    ]);

    $payload = json_encode([
        'model'       => 'llama-3.3-70b-versatile',
        'temperature' => 0.0,
        'messages'    => [
            ['role' => 'system', 'content' => $systemPrompt],
            ['role' => 'user', 'content' => "CONTEXTE DOCUMENTAIRE :\n{$formattedContext}\n\nQUESTION :\n{$userQuestion}"],
        ],
    ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);

    $ch = curl_init('https://api.groq.com/openai/v1/chat/completions');
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER     => [
            'Authorization: Bearer ' . $apiKey,
            'Content-Type: application/json',
        ],
        CURLOPT_POSTFIELDS     => $payload,
        CURLOPT_TIMEOUT        => 30,
    ]);

    $rawResponse = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($rawResponse === false || $httpCode !== 200) {
        throw new RuntimeException("Erreur Groq Chat ({$httpCode}) : {$rawResponse}");
    }

    $data = json_decode($rawResponse, true, 512, JSON_THROW_ON_ERROR);
    return $data['choices'][0]['message']['content'];
}

// --- Exécution principale ---
$storeJson = file_get_contents(__DIR__ . '/vector-store.json') ?: '[]';
$vectorStore = json_decode($storeJson, true, 512, JSON_THROW_ON_ERROR);
$queryVector = embedQuery($question, $jinaApiKey);

$scoredChunks = [];
foreach ($vectorStore as $entry) {
    $entry['score'] = cosineSimilarity($queryVector, $entry['embedding']);
    $scoredChunks[] = $entry;
}

usort($scoredChunks, fn(array $a, array $b): int => $b['score'] <=> $a['score']);
$topChunks = array_slice($scoredChunks, 0, TOP_K);

echo "\nQuestion : \"{$question}\"\n";
echo "--- Top-3 des morceaux les plus proches ---\n";
foreach ($topChunks as $chunk) {
    echo sprintf("• [%s] Score cosinus : %.4f\n", $chunk['id'], $chunk['score']);
}

if (count($topChunks) === 0 || $topChunks[0]['score'] < SIMILARITY_THRESHOLD) {
    $maxScore = $topChunks[0]['score'] ?? 0.0;
    echo sprintf("\n[Rejet automatique] Score maximal (%.4f) inférieur au seuil (%.2f).\n", $maxScore, SIMILARITY_THRESHOLD);
    echo "Réponse : Je ne dispose pas de cette information dans la documentation fournie.\n";
    exit(0);
}

echo "\n--- Réponse générée par Groq (llama-3.3-70b-versatile) ---\n";
echo generateAnswer($question, $topChunks, $groqApiKey) . "\n";
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
## TP 4 : Chaîne RAG (Jina v3 + Groq) & Orchestration Agentique MCP (PHP)

### 1. Banc d'essai de robustesse du moteur RAG (`ask-rag.php`)

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