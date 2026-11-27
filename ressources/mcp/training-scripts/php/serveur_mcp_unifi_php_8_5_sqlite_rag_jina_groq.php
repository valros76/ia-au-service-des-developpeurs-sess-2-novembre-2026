<?php
declare(strict_types=1);

/**
 * Serveur MCP Unifié en PHP 8.5 (Skills .mdr + SQLite entreprise.db + RAG SQLite Jina/Groq)
 * - Nécessite GROQ_API_KEY dans l'environnement ou .env
 * - Nécessite JINA_API_KEY pour https://api.jina.ai/v1/embeddings (modèle jina-embeddings-v3)
 * - S'appuie uniquement sur le corpus documentaire stocké dans entreprise.db (rag_documents / rag_chunks)
 *
 * Exemples CLI :
 * - php server_mcp.php sql "SELECT * FROM produits"
 * - php server_mcp.php rag-index
 * - php server_mcp.php rag-ask "Quelles sont les règles sur les commandes BLOQUEE ?"
 * - php server_mcp.php mon-skill "Entrée de test" --prompt
 */

const JINA_EMBEDDINGS_URL = 'https://api.jina.ai/v1/embeddings';
const JINA_EMBEDDINGS_MODEL = 'jina-embeddings-v3';
const GROQ_CHAT_URL = 'https://api.groq.com/openai/v1/chat/completions';
const DEFAULT_GROQ_MODEL = 'openai/gpt-oss-120b';
const RAG_TOP_K = 3;
const RAG_MIN_SCORE = 0.35;

/**
 * Recherche la racine du projet contenant .prompts, .env ou entreprise.db.
 */
function findProjectRoot(string $startDir): string
{
    $scriptDir = __DIR__;
    foreach (['.prompts', '.env', 'entreprise.db'] as $marker) {
        if (file_exists($scriptDir . DIRECTORY_SEPARATOR . $marker)) {
            return $scriptDir;
        }
    }

    $dir = $startDir;
    while (true) {
        foreach (['.prompts', '.env', 'entreprise.db'] as $marker) {
            if (file_exists($dir . DIRECTORY_SEPARATOR . $marker)) {
                return $dir;
            }
        }
        $parent = dirname($dir);
        if ($parent === $dir) {
            return $startDir;
        }
        $dir = $parent;
    }
}

/**
 * Charge les variables d'un fichier .env sans écraser les variables déjà définies.
 */
function loadDotEnv(string $envPath): void
{
    if (!is_file($envPath)) {
        return;
    }
    $lines = file($envPath, FILE_IGNORE_NEW_LINES) ?: [];
    foreach ($lines as $rawLine) {
        $line = trim($rawLine);
        if ($line === '' || str_starts_with($line, '#') || !str_contains($line, '=')) {
            continue;
        }
        [$key, $val] = explode('=', $line, 2);
        $key = trim($key);
        $val = trim($val);
        if ((str_starts_with($val, '"') && str_ends_with($val, '"')) || (str_starts_with($val, "'") && str_ends_with($val, "'"))) {
            $val = substr($val, 1, -1);
        }
        if ($key !== '' && getenv($key) === false) {
            putenv("{$key}={$val}");
            $_ENV[$key] = $val;
        }
    }
}

$projectRoot = findProjectRoot(getcwd() ?: __DIR__);
loadDotEnv($projectRoot . DIRECTORY_SEPARATOR . '.env');

$groqApiKey = (string) (getenv('GROQ_API_KEY') ?: '');
$jinaApiKey = (string) (getenv('JINA_API_KEY') ?: '');

/**
 * Localise et ouvre la base SQLite entreprise.db.
 */
function openEnterpriseDatabase(string $projectRoot): ?PDO
{
    $candidates = [
        __DIR__ . DIRECTORY_SEPARATOR . 'entreprise.db',
        __DIR__ . DIRECTORY_SEPARATOR . 'enterprise.db',
        $projectRoot . DIRECTORY_SEPARATOR . 'entreprise.db',
    ];
    foreach ($candidates as $candidate) {
        if (is_file($candidate)) {
            $pdo = new PDO('sqlite:' . $candidate, null, null, [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            ]);
            $pdo->exec('PRAGMA foreign_keys = ON;');
            return $pdo;
        }
    }
    return null;
}

$db = openEnterpriseDatabase($projectRoot);

/**
 * Exécute uniquement une requête SQL SELECT en lecture seule avec garde-fous.
 */
function executeSafeSelect(?PDO $db, mixed $rawSql): array
{
    if ($db === null) {
        throw new RuntimeException('Base entreprise.db introuvable dans le projet.');
    }
    if (!is_string($rawSql) || mb_strlen(trim($rawSql)) < 10) {
        throw new InvalidArgumentException('Paramètre sql invalide (minimum 10 caractères requis).');
    }

    $cleaned = preg_replace('/;+$/', '', trim($rawSql)) ?? '';
    if (str_contains($cleaned, ';')) {
        throw new RuntimeException('Sécurité : requêtes multiples interdites.');
    }
    if (!preg_match('/^SELECT\b/i', $cleaned)) {
        throw new RuntimeException('Sécurité : seul SELECT est autorisé.');
    }
    $forbidden = '/\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b/i';
    if (preg_match($forbidden, $cleaned)) {
        throw new RuntimeException('Sécurité : mot-clé SQL interdit.');
    }

    $finalSql = preg_match('/\bLIMIT\s+\d+/i', $cleaned) ? $cleaned : $cleaned . ' LIMIT 50';
    $stmt = $db->prepare($finalSql);
    $stmt->execute();
    return $stmt->fetchAll();
}

/**
 * Retourne le schéma DDL des tables de entreprise.db.
 */
function getDatabaseSchema(?PDO $db): string
{
    if ($db === null) {
        return '';
    }
    $stmt = $db->query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'");
    $rows = $stmt ? $stmt->fetchAll() : [];
    $parts = [];
    foreach ($rows as $r) {
        if (!empty($r['sql'])) {
            $parts[] = rtrim((string) $r['sql'], ';') . ';';
        }
    }
    return implode("\n\n", $parts);
}

/**
 * Corpus documentaire par défaut stocké uniquement dans entreprise.db si rag_documents est vide.
 */
function defaultDbDocuments(): array
{
    return [
        [
            'titre' => 'politique-remboursement.md',
            'categorie' => 'politique-remboursement',
            'contenu' => "## Délais de rétractation et remboursement\nLe client dispose d'un délai légal de 14 jours calendaires à compter de la réception de la commande pour demander un remboursement ou un retour produit, sous réserve que l'article soit retourné dans son état d'origine.\n\n## Règles selon le statut des commandes\nLes commandes au statut LIVREE peuvent être remboursées après contrôle de réception. Les commandes EXPEDIEE font l'objet d'un suivi transporteur avant remboursement. Les commandes au statut EN_COURS peuvent être annulées sans frais avant préparation logistique. Toute commande au statut BLOQUEE ne peut pas être remboursée automatiquement tant que l'anomalie métier ou le litige n'a pas été levé.\n\n## Validation financière et seuils\nTout remboursement ou avoir supérieur à 500 EUR nécessite une double validation manager et une vérification préalable des stocks et factures associées.",
        ],
        [
            'titre' => 'architecture-api.md',
            'categorie' => 'architecture-api',
            'contenu' => "## Endpoints et formats JSON\nLes services internes exposent des endpoints REST au format JSON UTF-8 avec pagination explicite (limit et offset) et horodatage ISO-8601.\n\n## Gestion des codes d'erreur HTTP\nLes erreurs de validation retournent un statut 400. Les authentifications invalides retournent 401. Les dépassements de quota ou de débit (rate limit) retournent HTTP 429 (Too Many Requests) et doivent être traités avec un délai d'attente progressif (backoff). Les erreurs internes inattendues retournent HTTP 500.\n\n## Résilience et intégration MCP\nLes connecteurs MCP doivent respecter la lecture seule pour les requêtes d'exploration et ne jamais exposer de secrets ou clés API dans les réponses JSON.",
        ],
        [
            'titre' => 'guide-securite.md',
            'categorie' => 'guide-securite',
            'contenu' => "## Intégrité des stocks en base\nAucune ligne de la table produits ne doit présenter un stock négatif ou nul non maîtrisé. Un stock négatif (par exemple -3 sur un écran ou un stock nul sur une webcam) constitue une anomalie critique d'inventaire à auditer sans modifier la base à l'aveugle.\n\n## Traitement des commandes BLOQUEE\nToute commande enregistrée avec le statut BLOQUEE (en majuscules) doit être isolée dans le rapport d'audit de conformité avec son identifiant, son client, son produit, sa quantité et sa date_commande.\n\n## Conformité clients et requêtes SQL\nLes adresses email des clients doivent être valides et non dupliquées, et les segments clients respectent B2B ou B2C. Toute inspection de entreprise.db doit s'appuyer exclusivement sur des requêtes SELECT en lecture seule.",
        ],
    ];
}

/**
 * Initialise les tables rag_documents et rag_chunks dans entreprise.db si absentes.
 */
function ensureRagCorpusInDb(?PDO $db): array
{
    if ($db === null) {
        return ['initialized' => false, 'documents' => []];
    }

    $db->exec(
        <<<'SQL'
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
        SQL
    );

    $count = (int) ($db->query('SELECT COUNT(*) AS total FROM rag_documents')->fetch()['total'] ?? 0);
    if ($count === 0) {
        $insert = $db->prepare(
            'INSERT OR IGNORE INTO rag_documents (titre, categorie, contenu, sha256, updated_at) VALUES (:titre, :categorie, :contenu, :sha256, :updated_at)'
        );
        $now = gmdate('c');
        foreach (defaultDbDocuments() as $doc) {
            $insert->execute([
                ':titre' => $doc['titre'],
                ':categorie' => $doc['categorie'],
                ':contenu' => $doc['contenu'],
                ':sha256' => hash('sha256', $doc['contenu']),
                ':updated_at' => $now,
            ]);
        }
    }

    $docs = $db->query('SELECT id, titre, categorie, contenu, sha256, updated_at FROM rag_documents ORDER BY id ASC')->fetchAll();
    return ['initialized' => true, 'documents' => $docs];
}

/**
 * Découpe le texte du corpus en segments de taille max avec chevauchement.
 */
function chunkCorpusText(string $text, int $chunkSize = 500, int $overlap = 75): array
{
    $normalized = trim(str_replace("\r\n", "\n", $text));
    if ($normalized === '') {
        return [];
    }
    $len = mb_strlen($normalized, 'UTF-8');
    if ($len <= $chunkSize) {
        return [$normalized];
    }

    $safeOverlap = max(0, min($overlap, $chunkSize - 1));
    $step = max(1, $chunkSize - $safeOverlap);
    $chunks = [];
    $start = 0;

    while ($start < $len) {
        $slice = trim(mb_substr($normalized, $start, $chunkSize, 'UTF-8'));
        if ($slice !== '') {
            $chunks[] = $slice;
        }
        if ($start + $chunkSize >= $len) {
            break;
        }
        $start += $step;
    }

    return $chunks;
}

/**
 * Calcule la similarité cosinus entre deux vecteurs numériques.
 */
function cosineSimilarity(array $vecA, array $vecB): float
{
    $count = count($vecA);
    if ($count === 0 || $count !== count($vecB)) {
        return 0.0;
    }

    $dot = 0.0;
    $normA = 0.0;
    $normB = 0.0;
    for ($i = 0; $i < $count; $i++) {
        $a = (float) $vecA[$i];
        $b = (float) $vecB[$i];
        $dot += $a * $b;
        $normA += $a * $a;
        $normB += $b * $b;
    }

    if ($normA <= 0.0 || $normB <= 0.0) {
        return 0.0;
    }
    return $dot / (sqrt($normA) * sqrt($normB));
}

/**
 * Effectue une requête HTTP POST JSON via cURL.
 */
function httpPostJson(string $url, string $bearerToken, array $payload): array
{
    if ($bearerToken === '') {
        throw new RuntimeException('Clé API manquante dans l’environnement.');
    }
    if (!function_exists('curl_init')) {
        throw new RuntimeException('L’extension PHP curl est requise pour les appels API.');
    }

    $ch = curl_init($url);
    $encoded = json_encode($payload, JSON_UNESCAPED_UNICODE);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $encoded,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 45,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $bearerToken,
            'Content-Type: application/json',
        ],
    ]);

    $rawBody = curl_exec($ch);
    $errno = curl_errno($ch);
    $errorMsg = curl_error($ch);
    $httpCode = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($errno !== 0 || $rawBody === false) {
        throw new RuntimeException("Erreur réseau cURL ({$errno}) : {$errorMsg}");
    }
    if ($httpCode < 200 || $httpCode >= 300) {
        throw new RuntimeException("Erreur HTTP {$httpCode} sur {$url} : {$rawBody}");
    }

    $decoded = json_decode((string) $rawBody, true);
    if (!is_array($decoded)) {
        throw new RuntimeException('Réponse JSON invalide reçue de l’API.');
    }
    return $decoded;
}

/**
 * Appelle https://api.jina.ai/v1/embeddings avec le modèle jina-embeddings-v3.
 */
function embedWithJina(string $jinaApiKey, array $inputs, string $task = 'retrieval.passage'): array
{
    if ($jinaApiKey === '') {
        throw new RuntimeException('Variable JINA_API_KEY absente dans .env (requise pour https://api.jina.ai/v1/embeddings).');
    }

    $data = httpPostJson(JINA_EMBEDDINGS_URL, $jinaApiKey, [
        'model' => JINA_EMBEDDINGS_MODEL,
        'task' => $task,
        'input' => $inputs,
    ]);

    $items = $data['data'] ?? [];
    if (!is_array($items) || count($items) !== count($inputs)) {
        throw new RuntimeException('Réponse Jina Embeddings incomplète.');
    }

    usort($items, static fn(array $a, array $b): int => ((int) ($a['index'] ?? 0)) <=> ((int) ($b['index'] ?? 0)));
    $vectors = [];
    foreach ($items as $item) {
        if (!isset($item['embedding']) || !is_array($item['embedding'])) {
            throw new RuntimeException('Vecteur manquant dans la réponse Jina.');
        }
        $vectors[] = $item['embedding'];
    }
    return $vectors;
}

/**
 * Appelle Groq Chat Completions.
 */
function askGroq(string $groqApiKey, string $prompt, string $model = DEFAULT_GROQ_MODEL): string
{
    if ($groqApiKey === '') {
        throw new RuntimeException('Variable GROQ_API_KEY absente dans .env.');
    }

    $data = httpPostJson(GROQ_CHAT_URL, $groqApiKey, [
        'model' => $model,
        'messages' => [
            ['role' => 'user', 'content' => $prompt],
        ],
    ]);

    $content = $data['choices'][0]['message']['content'] ?? null;
    if (!is_string($content) || trim($content) === '') {
        return 'Réponse vide de Groq.';
    }
    return $content;
}

/**
 * Indexe les extraits du corpus documentaire de entreprise.db avec Jina (jina-embeddings-v3).
 */
function indexDbCorpusRag(?PDO $db, string $jinaApiKey, bool $forceReembed = false): array
{
    if ($db === null) {
        throw new RuntimeException('Base entreprise.db introuvable : RAG SQLite indisponible.');
    }

    $corpus = ensureRagCorpusInDb($db);
    $documents = $corpus['documents'];

    $upsert = $db->prepare(
        <<<'SQL'
        INSERT INTO rag_chunks (id, document_id, source, chunk_index, content, sha256, embedding_json)
        VALUES (:id, :document_id, :source, :chunk_index, :content, :sha256, :embedding_json)
        ON CONFLICT(id) DO UPDATE SET
            document_id = excluded.document_id,
            source = excluded.source,
            chunk_index = excluded.chunk_index,
            content = excluded.content,
            sha256 = excluded.sha256,
            embedding_json = excluded.embedding_json
        SQL
    );

    $selectExisting = $db->prepare('SELECT sha256, embedding_json FROM rag_chunks WHERE id = :id');

    $totalChunks = 0;
    $embeddedNow = 0;
    $reusedFromDb = 0;
    $dimension = 0;

    foreach ($documents as $doc) {
        $chunks = chunkCorpusText((string) $doc['contenu'], 500, 75);
        $pending = [];

        foreach ($chunks as $idx => $chunkText) {
            $totalChunks++;
            $chunkId = (string) $doc['titre'] . '#' . $idx;
            $chunkHash = hash('sha256', $chunkText);

            $selectExisting->execute([':id' => $chunkId]);
            $existing = $selectExisting->fetch();

            if (!$forceReembed && is_array($existing) && ($existing['sha256'] ?? '') === $chunkHash && !empty($existing['embedding_json'])) {
                $reusedFromDb++;
                if ($dimension === 0) {
                    $decodedVec = json_decode((string) $existing['embedding_json'], true);
                    if (is_array($decodedVec)) {
                        $dimension = count($decodedVec);
                    }
                }
            } else {
                $pending[] = [
                    'id' => $chunkId,
                    'document_id' => (int) $doc['id'],
                    'source' => (string) $doc['titre'],
                    'chunk_index' => $idx,
                    'content' => $chunkText,
                    'sha256' => $chunkHash,
                ];
            }
        }

        if ($pending !== []) {
            $texts = array_map(static fn(array $p): string => $p['content'], $pending);
            $vectors = embedWithJina($jinaApiKey, $texts, 'retrieval.passage');
            foreach ($pending as $k => $item) {
                $vec = $vectors[$k];
                if ($dimension === 0) {
                    $dimension = count($vec);
                }
                $upsert->execute([
                    ':id' => $item['id'],
                    ':document_id' => $item['document_id'],
                    ':source' => $item['source'],
                    ':chunk_index' => $item['chunk_index'],
                    ':content' => $item['content'],
                    ':sha256' => $item['sha256'],
                    ':embedding_json' => json_encode($vec),
                ]);
                $embeddedNow++;
            }
            usleep(150000);
        }
    }

    return [
        'sourceStore' => 'entreprise.db (tables rag_documents & rag_chunks)',
        'model' => JINA_EMBEDDINGS_MODEL,
        'documentsCount' => count($documents),
        'totalChunks' => $totalChunks,
        'embeddedNow' => $embeddedNow,
        'reusedFromDb' => $reusedFromDb,
        'dimension' => $dimension,
    ];
}

/**
 * Interroge le moteur RAG uniquement sur le corpus documentaire stocké dans entreprise.db.
 */
function queryDbCorpusRag(?PDO $db, string $jinaApiKey, string $groqApiKey, string $question, int $topK = RAG_TOP_K, float $minScore = RAG_MIN_SCORE): array
{
    if ($db === null) {
        throw new RuntimeException('Base entreprise.db introuvable : RAG SQLite indisponible.');
    }
    $cleanQuestion = trim($question);
    if ($cleanQuestion === '') {
        throw new InvalidArgumentException('La question RAG ne peut pas être vide.');
    }

    ensureRagCorpusInDb($db);
    $rows = $db->query('SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks')->fetchAll();

    $needsIndex = $rows === [];
    if (!$needsIndex) {
        foreach ($rows as $r) {
            if (empty($r['embedding_json'])) {
                $needsIndex = true;
                break;
            }
        }
    }

    if ($needsIndex) {
        indexDbCorpusRag($db, $jinaApiKey, false);
        $rows = $db->query('SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks')->fetchAll();
    }

    [$queryVector] = embedWithJina($jinaApiKey, [$cleanQuestion], 'retrieval.query');

    $scored = [];
    foreach ($rows as $row) {
        $vec = json_decode((string) ($row['embedding_json'] ?? '[]'), true);
        if (!is_array($vec)) {
            $vec = [];
        }
        $score = cosineSimilarity($queryVector, $vec);
        $scored[] = [
            'id' => (string) $row['id'],
            'source' => (string) $row['source'],
            'chunkIndex' => (int) $row['chunk_index'],
            'content' => (string) $row['content'],
            'score' => $score,
        ];
    }

    usort($scored, static fn(array $a, array $b): int => $b['score'] <=> $a['score']);
    $topChunks = array_slice($scored, 0, max(1, $topK));
    $bestScore = $topChunks[0]['score'] ?? 0.0;

    if ($topChunks === [] || $bestScore < $minScore) {
        return [
            'question' => $cleanQuestion,
            'rejected' => true,
            'bestScore' => $bestScore,
            'minScore' => $minScore,
            'sources' => $topChunks,
            'answer' => 'Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db.',
        ];
    }

    $contextParts = [];
    foreach ($topChunks as $i => $chunk) {
        $num = $i + 1;
        $scoreFormatted = number_format((float) $chunk['score'], 4, '.', '');
        $contextParts[] = "[Extrait #{$num} | source: {$chunk['source']} | score: {$scoreFormatted}]\n{$chunk['content']}";
    }

    $prompt = implode("\n", [
        'Tu es un assistant strictement basé sur le corpus documentaire extrait de entreprise.db.',
        'Règles impératives :',
        '1. Utilise UNIQUEMENT les extraits fournis ci-dessous. N’invente aucune information externe.',
        '2. Cite explicitement le nom du document source sous la forme [source: nom-fichier.md].',
        '3. Si la réponse ne figure pas dans les extraits, réponds exactement : "Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db."',
        '',
        'EXTRAITS DU CORPUS (entreprise.db) :',
        implode("\n\n", $contextParts),
        '',
        'QUESTION : ' . $cleanQuestion,
    ]);

    $answer = askGroq($groqApiKey, $prompt);

    return [
        'question' => $cleanQuestion,
        'rejected' => false,
        'bestScore' => $bestScore,
        'minScore' => $minScore,
        'sources' => $topChunks,
        'answer' => $answer,
    ];
}

/**
 * Charge les skills .mdr depuis le dossier .prompts/.
 */
function loadMdrSkills(string $rootDir): array
{
    $promptsDir = $rootDir . DIRECTORY_SEPARATOR . '.prompts';
    if (!is_dir($promptsDir)) {
        return [];
    }

    $skills = [];
    $files = glob($promptsDir . DIRECTORY_SEPARATOR . '*.mdr') ?: [];
    sort($files);

    foreach ($files as $file) {
        $raw = trim((string) file_get_contents($file));
        if ($raw === '') {
            continue;
        }
        $baseName = pathinfo($file, PATHINFO_FILENAME);
        $name = preg_replace('/[^a-zA-Z0-9_-]/', '_', $baseName) ?: 'skill';
        $description = "Skill {$name} (.mdr)";
        $template = $raw;

        if (preg_match('/^---\s*\R([\s\S]*?)\R---\s*\R([\s\S]*)$/', $raw, $matches)) {
            $frontMatter = $matches[1];
            $template = trim($matches[2]);
            if (preg_match('/^description:\s*(.+)$/m', $frontMatter, $descMatch)) {
                $description = trim($descMatch[1]);
            }
        }

        $skills[$name] = [
            'name' => $name,
            'description' => $description,
            'template' => $template,
        ];
    }

    return $skills;
}

/**
 * Compile un template de skill avec l'entrée utilisateur.
 */
function compileSkill(array $skill, string $input): string
{
    $template = (string) $skill['template'];
    if (str_contains($template, '{{input}}')) {
        return str_replace('{{input}}', $input, $template);
    }
    return trim($template . "\n\n" . $input);
}

$skills = loadMdrSkills($projectRoot);

// ============================================================================
// MODE CLI DIRECT
// ============================================================================
$argvSlice = array_slice($argv, 1);
if ($argvSlice !== []) {
    $isPrompt = in_array('--prompt', $argvSlice, true);
    $cleanArgs = array_values(array_filter($argvSlice, static fn(string $a): bool => $a !== '--prompt'));
    $cmd = $cleanArgs[0] ?? '';
    $target = $cleanArgs[1] ?? '';

    if ($cmd === 'sql') {
        try {
            $rows = executeSafeSelect($db, $target);
            fwrite(STDOUT, json_encode($rows, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . PHP_EOL);
            exit(0);
        } catch (Throwable $e) {
            fwrite(STDERR, '[Erreur SQL] ' . $e->getMessage() . PHP_EOL);
            exit(1);
        }
    }

    if ($cmd === 'rag-index') {
        try {
            $force = in_array('--force', $cleanArgs, true);
            $stats = indexDbCorpusRag($db, $jinaApiKey, $force);
            fwrite(STDOUT, json_encode($stats, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . PHP_EOL);
            exit(0);
        } catch (Throwable $e) {
            fwrite(STDERR, '[Erreur RAG Index] ' . $e->getMessage() . PHP_EOL);
            exit(1);
        }
    }

    if ($cmd === 'rag-ask') {
        try {
            $result = queryDbCorpusRag($db, $jinaApiKey, $groqApiKey, $target, RAG_TOP_K, RAG_MIN_SCORE);
            fwrite(STDOUT, json_encode($result, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . PHP_EOL);
            exit(0);
        } catch (Throwable $e) {
            fwrite(STDERR, '[Erreur RAG Ask] ' . $e->getMessage() . PHP_EOL);
            exit(1);
        }
    }

    if (!isset($skills[$cmd])) {
        $avail = $skills !== [] ? implode(', ', array_keys($skills)) : '(aucun .mdr dans .prompts/)';
        $extra = $db !== null ? ', sql, rag-index, rag-ask' : '';
        fwrite(STDERR, "Skill introuvable : '{$cmd}'\nDisponibles : {$avail}{$extra}" . PHP_EOL);
        exit(1);
    }

    $input = ($target !== '' && is_file($target)) ? (string) file_get_contents($target) : $target;
    $compiled = compileSkill($skills[$cmd], $input);
    if ($isPrompt) {
        fwrite(STDOUT, $compiled . PHP_EOL);
        exit(0);
    }

    try {
        $answer = askGroq($groqApiKey, $compiled);
        fwrite(STDOUT, $answer . PHP_EOL);
        exit(0);
    } catch (Throwable $e) {
        fwrite(STDERR, '[Erreur Groq] ' . $e->getMessage() . PHP_EOL);
        exit(1);
    }
}

// ============================================================================
// MOTEUR JSON-RPC 2.0 / MCP (STDIO)
// ============================================================================
function sendRpc(mixed $id, mixed $result = null, ?array $error = null): void
{
    $frame = ['jsonrpc' => '2.0', 'id' => $id];
    if ($error !== null) {
        $frame['error'] = $error;
    } else {
        $frame['result'] = $result;
    }
    fwrite(STDOUT, json_encode($frame, JSON_UNESCAPED_UNICODE) . "\n");
    fflush(STDOUT);
}

$stdin = fopen('php://stdin', 'r');
if ($stdin === false) {
    exit(1);
}

while (($line = fgets($stdin)) !== false) {
    $trimmed = trim($line);
    if ($trimmed === '') {
        continue;
    }
    $req = json_decode($trimmed, true);
    if (!is_array($req)) {
        continue;
    }

    $id = $req['id'] ?? null;
    $method = (string) ($req['method'] ?? '');
    $params = is_array($req['params'] ?? null) ? $req['params'] : [];

    try {
        switch ($method) {
            case 'initialize':
                sendRpc($id, [
                    'protocolVersion' => '2024-11-05',
                    'capabilities' => ['prompts' => new stdClass(), 'tools' => new stdClass(), 'resources' => new stdClass()],
                    'serverInfo' => ['name' => 'mcp-unified-php85', 'version' => '1.1.0'],
                ]);
                break;

            case 'notifications/initialized':
                break;

            case 'prompts/list':
                $prompts = [];
                foreach ($skills as $s) {
                    $prompts[] = [
                        'name' => $s['name'],
                        'description' => $s['description'],
                        'arguments' => [['name' => 'input', 'description' => 'Contexte {{input}}', 'required' => false]],
                    ];
                }
                sendRpc($id, ['prompts' => $prompts]);
                break;

            case 'prompts/get':
                $pName = (string) ($params['name'] ?? '');
                if (!isset($skills[$pName])) {
                    sendRpc($id, null, ['code' => -32601, 'message' => "Prompt introuvable : {$pName}"]);
                    break;
                }
                $argInput = (string) ($params['arguments']['input'] ?? '');
                sendRpc($id, [
                    'description' => $skills[$pName]['description'],
                    'messages' => [
                        ['role' => 'user', 'content' => ['type' => 'text', 'text' => compileSkill($skills[$pName], $argInput)]],
                    ],
                ]);
                break;

            case 'tools/list':
                $tools = [];
                foreach ($skills as $s) {
                    $tools[] = [
                        'name' => $s['name'],
                        'description' => '[Groq] ' . $s['description'],
                        'inputSchema' => [
                            'type' => 'object',
                            'properties' => ['input' => ['type' => 'string', 'description' => 'Données à analyser']],
                            'required' => ['input'],
                        ],
                    ];
                }
                if ($db !== null) {
                    $tools[] = [
                        'name' => 'query_database',
                        'description' => 'Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).',
                        'inputSchema' => [
                            'type' => 'object',
                            'properties' => ['sql' => ['type' => 'string', 'minLength' => 10, 'description' => 'Requête SQL SELECT valide']],
                            'required' => ['sql'],
                        ],
                    ];
                    $tools[] = [
                        'name' => 'rag_search_db_corpus',
                        'description' => 'Recherche RAG (Jina jina-embeddings-v3 + Groq) uniquement dans le corpus documentaire de entreprise.db.',
                        'inputSchema' => [
                            'type' => 'object',
                            'properties' => [
                                'question' => ['type' => 'string', 'minLength' => 3, 'description' => 'Question métier à vérifier dans le corpus de entreprise.db'],
                                'topK' => ['type' => 'number', 'description' => 'Nombre maximum d’extraits retournés (défaut: 3)'],
                            ],
                            'required' => ['question'],
                        ],
                    ];
                    $tools[] = [
                        'name' => 'rag_index_db_corpus',
                        'description' => 'Indexe ou met à jour les embeddings Jina (jina-embeddings-v3) du corpus documentaire de entreprise.db.',
                        'inputSchema' => [
                            'type' => 'object',
                            'properties' => ['force' => ['type' => 'boolean', 'description' => 'Force le recalcul complet (défaut: false)']],
                            'required' => [],
                        ],
                    ];
                }
                sendRpc($id, ['tools' => $tools]);
                break;

            case 'tools/call':
                $tName = (string) ($params['name'] ?? '');
                $tArgs = is_array($params['arguments'] ?? null) ? $params['arguments'] : [];

                if ($tName === 'query_database') {
                    try {
                        $rows = executeSafeSelect($db, $tArgs['sql'] ?? '');
                        sendRpc($id, ['content' => [['type' => 'text', 'text' => json_encode($rows, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)]]]);
                    } catch (Throwable $e) {
                        sendRpc($id, ['isError' => true, 'content' => [['type' => 'text', 'text' => $e->getMessage()]]]);
                    }
                    break;
                }

                if ($tName === 'rag_search_db_corpus') {
                    try {
                        $topK = isset($tArgs['topK']) && is_numeric((string) $tArgs['topK']) ? max(1, (int) $tArgs['topK']) : RAG_TOP_K;
                        $res = queryDbCorpusRag($db, $jinaApiKey, $groqApiKey, (string) ($tArgs['question'] ?? ''), $topK, RAG_MIN_SCORE);
                        sendRpc($id, ['content' => [['type' => 'text', 'text' => json_encode($res, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)]]]);
                    } catch (Throwable $e) {
                        sendRpc($id, ['isError' => true, 'content' => [['type' => 'text', 'text' => $e->getMessage()]]]);
                    }
                    break;
                }

                if ($tName === 'rag_index_db_corpus') {
                    try {
                        $stats = indexDbCorpusRag($db, $jinaApiKey, !empty($tArgs['force']));
                        sendRpc($id, ['content' => [['type' => 'text', 'text' => json_encode($stats, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)]]]);
                    } catch (Throwable $e) {
                        sendRpc($id, ['isError' => true, 'content' => [['type' => 'text', 'text' => $e->getMessage()]]]);
                    }
                    break;
                }

                if (!isset($skills[$tName])) {
                    sendRpc($id, null, ['code' => -32601, 'message' => "Outil introuvable : {$tName}"]);
                    break;
                }
                try {
                    $compiled = compileSkill($skills[$tName], (string) ($tArgs['input'] ?? ''));
                    $reply = askGroq($groqApiKey, $compiled);
                    sendRpc($id, ['content' => [['type' => 'text', 'text' => $reply]]]);
                } catch (Throwable $e) {
                    sendRpc($id, ['isError' => true, 'content' => [['type' => 'text', 'text' => $e->getMessage()]]]);
                }
                break;

            case 'resources/list':
                $resources = [];
                foreach ($skills as $s) {
                    $resources[] = [
                        'uri' => 'skill://' . $s['name'],
                        'name' => $s['name'] . '.mdr',
                        'description' => $s['description'],
                        'mimeType' => 'text/markdown',
                    ];
                }
                if ($db !== null) {
                    $resources[] = [
                        'uri' => 'db://schema',
                        'name' => 'Schéma SQLite (entreprise.db)',
                        'description' => 'Structure DDL (CREATE TABLE) de entreprise.db',
                        'mimeType' => 'text/plain',
                    ];
                    $resources[] = [
                        'uri' => 'rag://db-corpus',
                        'name' => 'Corpus Documentaire SQLite (entreprise.db)',
                        'description' => 'Documents métier stockés dans entreprise.db pour le moteur RAG',
                        'mimeType' => 'application/json',
                    ];
                }
                sendRpc($id, ['resources' => $resources]);
                break;

            case 'resources/templates/list':
                sendRpc($id, ['resourceTemplates' => []]);
                break;

            case 'resources/read':
                $uri = (string) ($params['uri'] ?? '');
                if ($uri === 'db://schema' && $db !== null) {
                    sendRpc($id, ['contents' => [['uri' => 'db://schema', 'mimeType' => 'text/plain', 'text' => getDatabaseSchema($db)]]]);
                    break;
                }
                if ($uri === 'rag://db-corpus' && $db !== null) {
                    $corpus = ensureRagCorpusInDb($db);
                    sendRpc($id, ['contents' => [['uri' => 'rag://db-corpus', 'mimeType' => 'application/json', 'text' => json_encode($corpus['documents'], JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)]]]);
                    break;
                }
                if (str_starts_with($uri, 'skill://')) {
                    $sName = substr($uri, 8);
                    if (isset($skills[$sName])) {
                        sendRpc($id, ['contents' => [['uri' => $uri, 'mimeType' => 'text/markdown', 'text' => $skills[$sName]['template']]]]);
                        break;
                    }
                }
                sendRpc($id, null, ['code' => -32602, 'message' => "Ressource inconnue : {$uri}"]);
                break;

            case 'ping':
                sendRpc($id, new stdClass());
                break;

            default:
                if ($id !== null) {
                    sendRpc($id, null, ['code' => -32601, 'message' => "Méthode inconnue : {$method}"]);
                }
                break;
        }
    } catch (Throwable $e) {
        if ($id !== null) {
            sendRpc($id, null, ['code' => -32603, 'message' => $e->getMessage()]);
        }
    }
}

fclose($stdin);