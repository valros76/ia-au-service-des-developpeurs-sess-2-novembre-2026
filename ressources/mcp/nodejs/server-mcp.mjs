#!/usr/bin/env node

/**
 * - Nécessite une clé env : GROQ_API_KEY="votreCleGroq"
 */

/**
 * - Pour tester le serveur en CLI : 
 * - exécution (exemple de commande) : node server-mcp.mjs revue_php85 exemple_sale.php
 * - 
 * - Prévisualisation (sans consommation API) : node server-mcp.mjs revue_php85 exemple_sale.php --prompt
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

// ============================================================================
// 1. CHARGEMENT DU .ENV & CONFIGURATION GROQ
// ============================================================================
const projectRoot = process.cwd();
const envPath = path.join(projectRoot, '.env');

if (fs.existsSync(envPath)) {
  for (const rawLine of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || !line.includes('=')) continue;
    const idx = line.indexOf('=');
    const key = line.slice(0, idx).trim();
    const val = line.slice(idx + 1).trim().replace(/^["']|["']$/g, '');
    process.env[key] ??= val;
  }
}

const GROQ_API_KEY = process.env.GROQ_API_KEY ?? '';
if (!GROQ_API_KEY) {
  console.error('[Erreur fatale] Variable GROQ_API_KEY absente (vérifie ton .env).');
  process.exit(1);
}

// ============================================================================
// 2. CLIENT GROQ & CHARGEMENT DES SKILLS (.prompts/*.mdr)
// ============================================================================
async function askGroq(prompt, model = 'llama-3.3-70b-versatile') {
  const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${GROQ_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model,
      messages: [{ role: 'user', content: prompt }],
    }),
  });

  if (!res.ok) {
    throw new Error(`Erreur HTTP Groq ${res.status} : ${await res.text()}`);
  }
  const data = await res.json();
  return data.choices?.[0]?.message?.content ?? 'Réponse vide de Groq.';
}

function loadMdrSkills(rootDir) {
  const promptsDir = path.join(rootDir, '.prompts');
  if (!fs.existsSync(promptsDir)) return new Map();

  const skills = new Map();
  for (const file of fs.readdirSync(promptsDir).filter((f) => f.endsWith('.mdr'))) {
    const raw = fs.readFileSync(path.join(promptsDir, file), 'utf8').trim();
    if (!raw) continue;

    const name = path.parse(file).name.replace(/[^a-zA-Z0-9_-]/g, '_');
    let description = `Skill ${name} (.mdr)`;
    let template = raw;

    const fm = raw.match(/^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n([\s\S]*)$/);
    if (fm) {
      template = fm[2].trim();
      const desc = fm[1].match(/^description:\s*(.+)$/m);
      if (desc) description = desc[1].trim();
    }

    skills.set(name, {
      name,
      description,
      template,
      compile: (input) =>
        template.includes('{{input}}')
          ? template.replaceAll('{{input}}', input)
          : `${template}\n\n${input}`.trim(),
    });
  }
  return skills;
}

const skills = loadMdrSkills(projectRoot);

// ============================================================================
// 3. MODE CLI DIRECT (Tests utilisateurs dans le terminal)
// ============================================================================
const cliArgs = process.argv.slice(2);
if (cliArgs.length > 0) {
  const isPrompt = cliArgs.includes('--prompt');
  const cleanArgs = cliArgs.filter((a) => a !== '--prompt');
  const skillName = cleanArgs[0] ?? '';
  const target = cleanArgs[1];

  if (!skills.has(skillName)) {
    const avail = [...skills.keys()].join(', ') || '(aucun .mdr dans .prompts/)';
    console.error(`Skill introuvable : '${skillName}'\nDisponibles : ${avail}`);
    process.exit(1);
  }

  let input = '';
  if (target && fs.existsSync(target) && fs.statSync(target).isFile()) {
    input = fs.readFileSync(target, 'utf8');
  } else if (target !== undefined) {
    input = target;
  } else if (!process.stdin.isTTY) {
    input = fs.readFileSync(0, 'utf8');
  }

  const compiled = skills.get(skillName).compile(input);
  if (isPrompt) {
    console.log(compiled);
    process.exit(0);
  }

  try {
    console.log(await askGroq(compiled));
    process.exit(0);
  } catch (err) {
    console.error(`[Erreur Groq] ${err.message}`);
    process.exit(1);
  }
}

// ============================================================================
// 4. MOTEUR JSON-RPC 2.0 / MCP (STDIO)
// ============================================================================
const send = (id, result = null, error = null) => {
  const frame = { jsonrpc: '2.0', id };
  if (error) frame.error = error;
  else frame.result = result;
  process.stdout.write(`${JSON.stringify(frame)}\n`);
};

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

for await (const line of rl) {
  if (!line.trim()) continue;
  let req;
  try {
    req = JSON.parse(line);
  } catch {
    continue;
  }

  const { id = null, method, params = {} } = req;

  switch (method) {
    case 'initialize':
      send(id, {
        protocolVersion: '2024-11-05',
        capabilities: { prompts: {}, tools: {}, resources: {} },
        serverInfo: { name: 'mcp-initial-node', version: '1.0.0' },
      });
      break;

    case 'notifications/initialized':
      break;

    case 'prompts/list':
      send(id, {
        prompts: [...skills.values()].map((s) => ({
          name: s.name,
          description: s.description,
          arguments: [{ name: 'input', description: 'Contexte {{input}}', required: false }],
        })),
      });
      break;

    case 'prompts/get': {
      const skill = skills.get(params.name ?? '');
      if (!skill) {
        send(id, null, { code: -32601, message: `Prompt introuvable : ${params.name}` });
        break;
      }
      const text = skill.compile(String(params.arguments?.input ?? ''));
      send(id, {
        description: skill.description,
        messages: [{ role: 'user', content: { type: 'text', text } }],
      });
      break;
    }

    case 'tools/list':
      send(id, {
        tools: [...skills.values()].map((s) => ({
          name: s.name,
          description: `[Groq] ${s.description}`,
          inputSchema: {
            type: 'object',
            properties: { input: { type: 'string', description: 'Données à analyser' } },
            required: ['input'],
          },
        })),
      });
      break;

    case 'tools/call': {
      const skill = skills.get(params.name ?? '');
      if (!skill) {
        send(id, null, { code: -32601, message: `Outil introuvable : ${params.name}` });
        break;
      }
      try {
        const answer = await askGroq(skill.compile(String(params.arguments?.input ?? '')));
        send(id, { content: [{ type: 'text', text: answer }] });
      } catch (err) {
        send(id, null, { code: -32000, message: err.message });
      }
      break;
    }

    case 'resources/list':
      send(id, {
        resources: [...skills.values()].map((s) => ({
          uri: `skill://${s.name}`,
          name: `${s.name}.mdr`,
          description: s.description,
          mimeType: 'text/markdown',
        })),
      });
      break;

    case 'resources/read': {
      const uri = String(params.uri ?? '');
      const name = uri.startsWith('skill://') ? uri.slice(8) : '';
      const skill = skills.get(name);
      if (!skill) {
        send(id, null, { code: -32602, message: `Ressource inconnue : ${uri}` });
        break;
      }
      send(id, { contents: [{ uri, mimeType: 'text/markdown', text: skill.template }] });
      break;
    }

    case 'ping':
      send(id, {});
      break;

    default:
      if (id !== null) send(id, null, { code: -32601, message: `Méthode inconnue : ${method}` });
  }
}