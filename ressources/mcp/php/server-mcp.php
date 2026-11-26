<?php

/**
 * Nécessite une clé env : GROQ_API_KEY="votreCleGroq"
 */

/**
 * Pour tester le serveur en CLI : 
 * exécution (exemple de commande) : php server-mcp.php revue_php85 exemple_sale.php
 * 
 * Prévisualisation (sans consommation API) : php server-mcp.php revue_php85 exemple_sale.php --prompt
 */

declare(strict_types=1);

// Sécurité absolue : aucune erreur PHP ne doit polluer STDOUT (réservé au JSON-RPC)
ini_set('display_errors', '1');
error_reporting(E_ALL);

// ============================================================================
// 1. CHARGEMENT DU .ENV & CONFIGURATION GROQ
// ============================================================================
$projectRoot = getcwd() ?: __DIR__;

$envFile = rtrim($projectRoot, '/\\') . DIRECTORY_SEPARATOR . '.env';
if (is_file($envFile) && ($env = parse_ini_file($envFile, false, INI_SCANNER_RAW)) !== false) {
    foreach ($env as $k => $v) {
        $cleanVal = trim((string) $v, " \t\n\r\0\x0B\"'");
        putenv("{$k}={$cleanVal}");
        $_ENV[$k] = $cleanVal;
    }
}

$groqApiKey = getenv('GROQ_API_KEY') ?: ($_ENV['GROQ_API_KEY'] ?? '');
if ($groqApiKey === '') {
    fwrite(STDERR, "[Erreur fatale] Variable d'environnement GROQ_API_KEY absente (vérifie ton fichier .env).\n");
    exit(1);
}

// ============================================================================
// 2. DÉFINITIONS DES CLASSES & FONCTIONS
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
        // Connexion persistante : évite de renégocier TLS à chaque appel
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
        $payload = json_encode([
            'model'    => $model,
            'messages' => [['role' => 'user', 'content' => $prompt]],
        ], JSON_THROW_ON_ERROR);

        curl_setopt($this->ch, CURLOPT_POSTFIELDS, $payload);
        $raw = curl_exec($this->ch);

        if ($raw === false) {
            throw new RuntimeException('Erreur réseau Groq : ' . curl_error($this->ch));
        }

        $data = json_decode((string) $raw, true, 512, JSON_THROW_ON_ERROR);
        return $data['choices'][0]['message']['content'] ?? 'Réponse vide de Groq.';
    }
}

/** @return array<string, Skill> */
function loadMdrSkills(string $rootDir): array
{
    $promptsDir = rtrim($rootDir, '/\\') . DIRECTORY_SEPARATOR . '.prompts';
    if (!is_dir($promptsDir)) {
        return [];
    }

    $skills = [];
    foreach (glob($promptsDir . DIRECTORY_SEPARATOR . '*.mdr') ?: [] as $file) {
        $raw = file_get_contents($file);
        if ($raw === false || trim($raw) === '') {
            continue;
        }

        $name = pathinfo($file, PATHINFO_FILENAME)
            |> (static fn(string $s): string => preg_replace('/[^a-zA-Z0-9_-]/', '_', $s));

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

// ============================================================================
// 3. INSTANCIATION DU CLIENT GROQ & CHARGEMENT DES SKILLS
// ============================================================================
$groq   = new GroqClient($groqApiKey);
$skills = loadMdrSkills($projectRoot);

// ============================================================================
// 4. MODE CLI DIRECT (Tests utilisateurs dans le terminal)
// ============================================================================
if ($argc > 1) {
    $isPrompt  = in_array('--prompt', $argv, true);
    $cleanArgs = array_values(array_filter(
        array_slice($argv, 1),
        static fn(string $arg): bool => $arg !== '--prompt'
    ));

    $skillName = $cleanArgs[0] ?? '';
    $target    = $cleanArgs[1] ?? null;

    if (!isset($skills[$skillName])) {
        $available = $skills === [] ? '(aucun fichier .mdr trouvé dans .prompts/)' : implode(', ', array_keys($skills));
        fwrite(STDERR, "Skill introuvable : '{$skillName}'\nSkills disponibles : {$available}\n");
        exit(1);
    }

    // Lecture depuis un fichier, un texte direct, ou un pipe STDIN
    $input = match (true) {
        $target !== null && is_file($target) => (string) file_get_contents($target),
        $target !== null                     => $target,
        !stream_isatty(STDIN)                => (string) stream_get_contents(STDIN),
        default                              => '',
    };

    $compiled = $skills[$skillName]->compile($input);

    if ($isPrompt) {
        echo $compiled . "\n";
        exit(0);
    }

    try {
        echo $groq->ask($compiled) . "\n";
        exit(0);
    } catch (Throwable $e) {
        fwrite(STDERR, "[Erreur Groq] {$e->getMessage()}\n");
        exit(1);
    }
}

// ============================================================================
// 5. MOTEUR JSON-RPC 2.0 / MCP (STDIO)
// ============================================================================
$send = static function (int|string|null $id, mixed $result = null, ?array $error = null): void {
    $frame = ['jsonrpc' => '2.0', 'id' => $id];
    $error !== null ? $frame['error'] = $error : $frame['result'] = $result;

    fwrite(STDOUT, json_encode($frame, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . "\n");
    fflush(STDOUT);
};

while (($line = fgets(STDIN)) !== false) {
    $req = $line
        |> trim(...)
        |> (static fn(string $s): ?array => $s === '' ? null : json_decode($s, true));

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
            'serverInfo'      => ['name' => 'mcp-initial-php85', 'version' => '1.0.0'],
        ]),

        'notifications/initialized' => null,

        // --- CAPACITÉ 1 : PROMPTS (/nom_du_skill dans le chat) ---
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

        // --- CAPACITÉ 2 : TOOLS (Exécution déléguée à Groq) ---
        'tools/list' => $send($id, [
            'tools' => array_values(array_map(
                static fn(Skill $s): array => [
                    'name'        => $s->name,
                    'description' => '[Groq] ' . $s->description,
                    'inputSchema' => [
                        'type'       => 'object',
                        'properties' => [
                            'input' => ['type' => 'string', 'description' => 'Données à analyser'],
                        ],
                        'required'   => ['input'],
                    ],
                ],
                $skills
            )),
        ]),

        'tools/call' => (static function () use ($id, $params, $skills, $groq, $send): void {
            $name = $params['name'] ?? '';
            if (!isset($skills[$name])) {
                $send($id, error: ['code' => -32601, 'message' => "Outil introuvable : {$name}"]);
                return;
            }

            try {
                $prompt = $skills[$name]->compile((string) ($params['arguments']['input'] ?? ''));
                $answer = $groq->ask($prompt);
                $send($id, ['content' => [['type' => 'text', 'text' => $answer]]]);
            } catch (Throwable $e) {
                $send($id, error: ['code' => -32000, 'message' => $e->getMessage()]);
            }
        })(),

        // --- CAPACITÉ 3 : RESOURCES (Lecture des .mdr bruts par le client) ---
        'resources/list' => $send($id, [
            'resources' => array_values(array_map(
                static fn(Skill $s): array => [
                    'uri'         => "skill://{$s->name}",
                    'name'        => "{$s->name}.mdr",
                    'description' => $s->description,
                    'mimeType'    => 'text/markdown',
                ],
                $skills
            )),
        ]),

        'resources/read' => (static function () use ($id, $params, $skills, $send): void {
            $uri  = (string) ($params['uri'] ?? '');
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