#!/usr/bin/env php
<?php

declare(strict_types=1);

/**
 * - Nécessite une clé env : GROQ_API_KEY="votreCleGroq" (pour les skills .mdr)
 * - Si entreprise.db est présente, active aussi les outils SQLite du TP 3 (db://schema + query_database)
 *
 * - Pour tester le serveur en CLI :
 * - exécution skill : php server-mcp.php revue_php85 exemple_sale.php
 * - prévisualisation : php server-mcp.php revue_php85 exemple_sale.php --prompt
 * - requête SQLite  : php server-mcp.php sql "SELECT * FROM produits"
 */

ini_set('display_errors', $argc > 1 ? '1' : '0');
error_reporting(E_ALL);

// ============================================================================
// 1. RECHERCHE RACINE PROJET & CHARGEMENT DU .ENV
// ============================================================================
function findProjectRoot(string $startDir): string
{
    $dir = $startDir;
    while (true) {
        if (
            is_dir($dir . DIRECTORY_SEPARATOR . '.prompts') ||
            is_file($dir . DIRECTORY_SEPARATOR . '.env') ||
            is_file($dir . DIRECTORY_SEPARATOR . 'entreprise.db')
        ) {
            return $dir;
        }
        $parent = dirname($dir);
        if ($parent === $dir) {
            return $startDir;
        }
        $dir = $parent;
    }
}

$projectRoot = findProjectRoot(getcwd() ?: __DIR__);
$envFile     = $projectRoot . DIRECTORY_SEPARATOR . '.env';

if (is_file($envFile)) {
    foreach (file($envFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [] as $rawLine) {
        $line = trim($rawLine);
        if ($line === '' || str_starts_with($line, '#') || !str_contains($line, '=')) {
            continue;
        }
        [$k, $v] = explode('=', $line, 2);
        $cleanVal = trim($v, " \t\n\r\0\x0B\"'");
        putenv(trim($k) . "={$cleanVal}");
        $_ENV[trim($k)] = $cleanVal;
    }
}

$groqApiKey = getenv('GROQ_API_KEY') ?: ($_ENV['GROQ_API_KEY'] ?? '');

// ============================================================================
// 2. MODULE SQLITE (TP 3 - actif automatiquement si entreprise.db existe)
// ============================================================================
$dbPath = $projectRoot . DIRECTORY_SEPARATOR . 'entreprise.db';
$pdo    = is_file($dbPath)
    ? new PDO('sqlite:' . $dbPath, null, null, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::SQLITE_ATTR_OPEN_FLAGS  => PDO::SQLITE_OPEN_READONLY,
    ])
    : null;

function executeSafeSelect(?PDO $pdo, string $rawSql): array
{
    if ($pdo === null) {
        throw new RuntimeException('Base entreprise.db introuvable dans le projet.');
    }
    if (mb_strlen(trim($rawSql)) < 10) {
        throw new InvalidArgumentException('Paramètre sql invalide (minimum 10 caractères requis).');
    }

    $cleaned = rtrim(trim($rawSql), ';');
    if (str_contains($cleaned, ';')) {
        throw new InvalidArgumentException('Sécurité : requêtes multiples interdites.');
    }
    if (!preg_match('/^SELECT\b/i', $cleaned)) {
        throw new InvalidArgumentException('Sécurité : seul SELECT est autorisé.');
    }
    if (preg_match('/\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b/i', $cleaned)) {
        throw new InvalidArgumentException('Sécurité : mot-clé SQL interdit.');
    }

    $finalSql = preg_match('/\bLIMIT\s+\d+/i', $cleaned) ? $cleaned : "{$cleaned} LIMIT 50";
    return $pdo->query($finalSql)->fetchAll();
}

function getDatabaseSchema(?PDO $pdo): string
{
    if ($pdo === null) {
        return '';
    }
    $rows = $pdo->query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")->fetchAll();
    return implode("\n\n", array_map(static fn(array $r): string => $r['sql'] . ';', $rows));
}

// ============================================================================
// 3. MODULE SKILLS .MDR + GROQ (Base initiale conservée)
// ============================================================================
final readonly class Skill
{
    public function __construct(
        public string $name,
        public string $description,
        public string $template,
    ) {}

    public function compile(string $input): string
    {
        return str_contains($this->template, '{{input}}')
            ? str_replace('{{input}}', $input, $this->template)
            : trim($this->template . "\n\n" . $input);
    }
}

final class GroqClient
{
    private CurlHandle $ch;

    public function __construct(private readonly string $apiKey)
    {
        $this->ch = curl_init('https://api.groq.com/openai/v1/chat/completions');
        curl_setopt_array($this->ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST           => true,
            CURLOPT_HTTPHEADER     => [
                'Authorization: Bearer ' . $this->apiKey,
                'Content-Type: application/json',
            ],
            CURLOPT_TIMEOUT        => 30,
        ]);
    }

    public function ask(string $prompt, string $model = 'llama-3.3-70b-versatile'): string
    {
        if ($this->apiKey === '') {
            throw new RuntimeException('Variable GROQ_API_KEY absente dans .env.');
        }

        $payload = json_encode([
            'model'    => $model,
            'messages' => [['role' => 'user', 'content' => $prompt]],
        ], JSON_THROW_ON_ERROR);

        curl_setopt($this->ch, CURLOPT_POSTFIELDS, $payload);
        $raw  = curl_exec($this->ch);
        $code = curl_getinfo($this->ch, CURLINFO_HTTP_CODE);

        if ($raw === false) {
            throw new RuntimeException('Erreur réseau Groq : ' . curl_error($this->ch));
        }

        $data = json_decode((string) $raw, true, 512, JSON_THROW_ON_ERROR);
        if ($code >= 400 || isset($data['error'])) {
            $msg = $data['error']['message'] ?? (string) $raw;
            throw new RuntimeException("Erreur API Groq (HTTP {$code}) : {$msg}");
        }

        return $data['choices'][0]['message']['content'] ?? 'Réponse vide de Groq.';
    }
}

/** @return array<string, Skill> */
function loadMdrSkills(string $rootDir): array
{
    $promptsDir = $rootDir . DIRECTORY_SEPARATOR . '.prompts';
    if (!is_dir($promptsDir)) {
        return [];
    }

    $skills = [];
    foreach (glob($promptsDir . DIRECTORY_SEPARATOR . '*.mdr') ?: [] as $file) {
        $raw = file_get_contents($file);
        if ($raw === false || trim($raw) === '') {
            continue;
        }

        $name = preg_replace('/[^a-zA-Z0-9_-]/', '_', pathinfo($file, PATHINFO_FILENAME));
        $description = "Skill {$name} (.mdr)";
        $body        = $raw;

        if (preg_match('/^---\s*\R(.*?)\R---\s*\R(.*)$/s', $raw, $m)) {
            $body = $m[2];
            if (preg_match('/^description:\s*(.+)$/m', $m[1], $d)) {
                $description = trim($d[1]);
            }
        }

        $skills[$name] = new Skill($name, $description, trim($body));
    }

    return $skills;
}

$groq   = new GroqClient($groqApiKey);
$skills = loadMdrSkills($projectRoot);

// ============================================================================
// 4. MODE CLI DIRECT
// ============================================================================
if ($argc > 1) {
    $isPrompt  = in_array('--prompt', $argv, true);
    $cleanArgs = array_values(array_filter(
        array_slice($argv, 1),
        static fn(string $arg): bool => $arg !== '--prompt'
    ));

    $cmd    = $cleanArgs[0] ?? '';
    $target = $cleanArgs[1] ?? '';

    if ($cmd === 'sql') {
        try {
            echo json_encode(executeSafeSelect($pdo, $target), JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) . "\n";
            exit(0);
        } catch (Throwable $e) {
            echo "[Erreur SQL] {$e->getMessage()}\n";
            exit(1);
        }
    }

    if (!isset($skills[$cmd])) {
        $available = $skills === [] ? '(aucun .mdr dans .prompts/)' : implode(', ', array_keys($skills));
        $suffix    = $pdo !== null ? ', sql' : '';
        echo "Skill introuvable : '{$cmd}'\nDisponibles : {$available}{$suffix}\n";
        exit(1);
    }

    $input    = ($target !== '' && is_file($target)) ? (string) file_get_contents($target) : $target;
    $compiled = $skills[$cmd]->compile($input);

    if ($isPrompt) {
        echo $compiled . "\n";
        exit(0);
    }

    try {
        echo $groq->ask($compiled) . "\n";
        exit(0);
    } catch (Throwable $e) {
        echo "[Erreur Groq] {$e->getMessage()}\n";
        exit(1);
    }
}

// ============================================================================
// 5. MOTEUR JSON-RPC 2.0 / MCP UNIFIÉ (MDR + SQLITE)
// ============================================================================
$send = static function (int|string|null $id, mixed $result = null, ?array $error = null): void {
    $frame = ['jsonrpc' => '2.0', 'id' => $id];
    $error !== null ? $frame['error'] = $error : $frame['result'] = $result;

    fwrite(STDOUT, json_encode($frame, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . "\n");
    fflush(STDOUT);
};

while (($line = fgets(STDIN)) !== false) {
    $trimmed = trim($line);
    if ($trimmed === '') {
        continue;
    }
    $req = json_decode($trimmed, true);
    if (!is_array($req) || !isset($req['method'])) {
        continue;
    }

    $id     = $req['id'] ?? null;
    $method = $req['method'];
    $params = $req['params'] ?? [];

    match ($method) {
        'initialize' => $send($id, [
            'protocolVersion' => '2024-11-05',
            'capabilities'    => [
                'prompts'   => new stdClass(),
                'tools'     => new stdClass(),
                'resources' => new stdClass(),
            ],
            'serverInfo'      => ['name' => 'mcp-unified-php85', 'version' => '1.0.0'],
        ]),

        'notifications/initialized' => null,

        'prompts/list' => $send($id, [
            'prompts' => array_values(array_map(
                static fn(Skill $s): array => [
                    'name'        => $s->name,
                    'description' => $s->description,
                    'arguments'   => [[
                        'name'        => 'input',
                        'description' => 'Contexte ou code à injecter dans {{input}}',
                        'required'    => false,
                    ]],
                ],
                $skills
            )),
        ]),

        'prompts/get' => (static function () use ($id, $params, $skills, $send): void {
            $name = $params['name'] ?? '';
            if (!isset($skills[$name])) {
                $send($id, error: ['code' => -32601, 'message' => "Prompt introuvable : {$name}"]);
                return;
            }
            $compiled = $skills[$name]->compile((string) ($params['arguments']['input'] ?? ''));
            $send($id, [
                'description' => $skills[$name]->description,
                'messages'    => [[
                    'role'    => 'user',
                    'content' => ['type' => 'text', 'text' => $compiled],
                ]],
            ]);
        })(),

        'tools/list' => (static function () use ($id, $skills, $pdo, $send): void {
            $tools = array_values(array_map(
                static fn(Skill $s): array => [
                    'name'        => $s->name,
                    'description' => '[Groq] ' . $s->description,
                    'inputSchema' => [
                        'type'       => 'object',
                        'properties' => ['input' => ['type' => 'string', 'description' => 'Données à analyser']],
                        'required'   => ['input'],
                    ],
                ],
                $skills
            ));

            if ($pdo !== null) {
                $tools[] = [
                    'name'        => 'query_database',
                    'description' => 'Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).',
                    'inputSchema' => [
                        'type'       => 'object',
                        'properties' => ['sql' => ['type' => 'string', 'minLength' => 10, 'description' => 'Requête SQL SELECT valide']],
                        'required'   => ['sql'],
                    ],
                ];
            }

            $send($id, ['tools' => $tools]);
        })(),

        'tools/call' => (static function () use ($id, $params, $skills, $groq, $pdo, $send): void {
            $name = $params['name'] ?? '';
            if ($name === 'query_database') {
                try {
                    $rows = executeSafeSelect($pdo, (string) ($params['arguments']['sql'] ?? ''));
                    $send($id, ['content' => [['type' => 'text', 'text' => json_encode($rows, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)]]]);
                } catch (Throwable $e) {
                    $send($id, ['isError' => true, 'content' => [['type' => 'text', 'text' => $e->getMessage()]]]);
                }
                return;
            }

            if (!isset($skills[$name])) {
                $send($id, error: ['code' => -32601, 'message' => "Outil introuvable : {$name}"]);
                return;
            }
            try {
                $prompt = $skills[$name]->compile((string) ($params['arguments']['input'] ?? ''));
                $answer = $groq->ask($prompt);
                $send($id, ['content' => [['type' => 'text', 'text' => $answer]]]);
            } catch (Throwable $e) {
                $send($id, ['isError' => true, 'content' => [['type' => 'text', 'text' => $e->getMessage()]]]);
            }
        })(),

        'resources/list' => (static function () use ($id, $skills, $pdo, $send): void {
            $resources = array_values(array_map(
                static fn(Skill $s): array => [
                    'uri'         => "skill://{$s->name}",
                    'name'        => "{$s->name}.mdr",
                    'description' => $s->description,
                    'mimeType'    => 'text/markdown',
                ],
                $skills
            ));

            if ($pdo !== null) {
                $resources[] = [
                    'uri'         => 'db://schema',
                    'name'        => 'Schéma SQLite (entreprise.db)',
                    'description' => 'Structure DDL (CREATE TABLE) de la base entreprise.db',
                    'mimeType'    => 'text/plain',
                ];
            }

            $send($id, ['resources' => $resources]);
        })(),

        'resources/read' => (static function () use ($id, $params, $skills, $pdo, $send): void {
            $uri = (string) ($params['uri'] ?? '');
            if ($uri === 'db://schema' && $pdo !== null) {
                $send($id, ['contents' => [['uri' => 'db://schema', 'mimeType' => 'text/plain', 'text' => getDatabaseSchema($pdo)]]]);
                return;
            }

            $name = str_starts_with($uri, 'skill://') ? substr($uri, 8) : '';
            if (!isset($skills[$name])) {
                $send($id, error: ['code' => -32602, 'message' => "Ressource inconnue : {$uri}"]);
                return;
            }

            $send($id, [
                'contents' => [[
                    'uri'      => $uri,
                    'mimeType' => 'text/markdown',
                    'text'     => $skills[$name]->template,
                ]],
            ]);
        })(),

        'ping' => $send($id, new stdClass()),

        default => $id !== null
            ? $send($id, error: ['code' => -32601, 'message' => "Méthode non supportée : {$method}"])
            : null,
    };
}