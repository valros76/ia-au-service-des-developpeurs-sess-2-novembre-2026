#!/usr/bin/env node

/**
 * - Nécessite une clé env : GROQ_API_KEY="votreCleGroq" (pour les skills .mdr)
 * - Si entreprise.db est présente, active aussi les outils SQLite du TP 3 (db://schema + query_database)
 *
 * - Pour tester le serveur en CLI :
 * - exécution skill : node server-mcp.mjs review-node example-sale.js
 * - prévisualisation : node server-mcp.mjs review-node example-sale.js --prompt
 * - requête SQLite  : node server-mcp.mjs sql "SELECT * FROM produits"
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ============================================================================
// 1. RECHERCHE RACINE PROJET & CHARGEMENT DU .ENV
// ============================================================================
function findProjectRoot(startDir) {
  // Priorité absolue au dossier contenant server-mcp.mjs (__dirname)
  if (
    fs.existsSync(path.join(__dirname, '.prompts')) ||
    fs.existsSync(path.join(__dirname, '.env')) ||
    fs.existsSync(path.join(__dirname, 'entreprise.db'))
  ) {
    return __dirname;
  }

  let dir = startDir;
  while (true) {
    if (
      fs.existsSync(path.join(dir, '.prompts')) ||
      fs.existsSync(path.join(dir, '.env')) ||
      fs.existsSync(path.join(dir, 'entreprise.db'))
    ) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) return startDir;
    dir = parent;
  }
}

const projectRoot = findProjectRoot(process.cwd());
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

// ============================================================================
// 2. MODULE SQLITE (TP 3 - actif automatiquement si entreprise.db existe)
// ============================================================================
const candidateDbs = [
  path.join(__dirname, 'entreprise.db'),
  path.join(__dirname, 'enterprise.db'),
  path.join(projectRoot, 'entreprise.db'),
];
const dbPath = candidateDbs.find((p) => fs.existsSync(p)) ?? null;
const db = dbPath ? new DatabaseSync(dbPath, { readOnly: true }) : null;
const FORBIDDEN_KEYWORDS = /\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b/i;

function executeSafeSelect(rawSql) {
  if (!db) throw new Error('Base entreprise.db introuvable dans le projet.');
  if (typeof rawSql !== 'string' || rawSql.trim().length < 10) {
    throw new Error('Paramètre sql invalide (minimum 10 caractères requis).');
  }
  const cleaned = rawSql.trim().replace(/;+$/, '');
  if (cleaned.includes(';')) throw new Error('Sécurité : requêtes multiples interdites.');
  if (!/^SELECT\b/i.test(cleaned)) throw new Error('Sécurité : seul SELECT est autorisé.');
  if (FORBIDDEN_KEYWORDS.test(cleaned)) throw new Error('Sécurité : mot-clé SQL interdit.');

  const finalSql = /\bLIMIT\s+\d+/i.test(cleaned) ? cleaned : `${cleaned} LIMIT 50`;
  return db.prepare(finalSql).all();
}

function getDatabaseSchema() {
  if (!db) return '';
  return db
    .prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
    .all()
    .map((r) => `${r.sql};`)
    .join('\n\n');
}

// ============================================================================
// 3. MODULE SKILLS .MDR + GROQ (Base initiale conservée)
// ============================================================================
async function askGroq(prompt, model = 'openai/gpt-oss-120b') {
  if (!GROQ_API_KEY) {
    throw new Error('Variable GROQ_API_KEY absente dans .env.');
  }
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

  if (!res.ok) throw new Error(`Erreur HTTP Groq ${res.status} : ${await res.text()}`);
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
// 4. MODE CLI DIRECT
// ============================================================================
const cliArgs = process.argv.slice(2);
if (cliArgs.length > 0) {
  const isPrompt = cliArgs.includes('--prompt');
  const cleanArgs = cliArgs.filter((a) => a !== '--prompt');
  const cmd = cleanArgs[0] ?? '';
  const target = cleanArgs[1] ?? '';

  if (cmd === 'sql') {
    try {
      console.log(JSON.stringify(executeSafeSelect(target), null, 2));
      process.exit(0);
    } catch (err) {
      console.error(`[Erreur SQL] ${err.message}`);
      process.exit(1);
    }
  }

  if (!skills.has(cmd)) {
    const avail = [...skills.keys()].join(', ') || '(aucun .mdr dans .prompts/)';
    console.error(`Skill introuvable : '${cmd}'\nDisponibles : ${avail}${db ? ', sql' : ''}`);
    process.exit(1);
  }

  const input =
    target && fs.existsSync(target) && fs.statSync(target).isFile()
      ? fs.readFileSync(target, 'utf8')
      : target;

  const compiled = skills.get(cmd).compile(input);
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
// 5. MOTEUR JSON-RPC 2.0 / MCP UNIFIÉ (MDR + SQLITE)
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
        serverInfo: { name: 'mcp-unified-node', version: '1.0.0' },
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
      send(id, {
        description: skill.description,
        messages: [{ role: 'user', content: { type: 'text', text: skill.compile(String(params.arguments?.input ?? '')) } }],
      });
      break;
    }

    case 'tools/list': {
      const toolsList = [...skills.values()].map((s) => ({
        name: s.name,
        description: `[Groq] ${s.description}`,
        inputSchema: {
          type: 'object',
          properties: { input: { type: 'string', description: 'Données à analyser' } },
          required: ['input'],
        },
      }));

      if (db) {
        toolsList.push({
          name: 'query_database',
          description: 'Exécute une requête SQL SELECT en lecture seule sur entreprise.db (LIMIT 50 automatique).',
          inputSchema: {
            type: 'object',
            properties: { sql: { type: 'string', minLength: 10, description: 'Requête SQL SELECT valide' } },
            required: ['sql'],
          },
        });
      }

      send(id, { tools: toolsList });
      break;
    }

    case 'tools/call': {
      if (params.name === 'query_database') {
        try {
          const rows = executeSafeSelect(params.arguments?.sql);
          send(id, { content: [{ type: 'text', text: JSON.stringify(rows, null, 2) }] });
        } catch (err) {
          send(id, { isError: true, content: [{ type: 'text', text: err.message }] });
        }
        break;
      }

      const skill = skills.get(params.name ?? '');
      if (!skill) {
        send(id, null, { code: -32601, message: `Outil introuvable : ${params.name}` });
        break;
      }
      try {
        const answer = await askGroq(skill.compile(String(params.arguments?.input ?? '')));
        send(id, { content: [{ type: 'text', text: answer }] });
      } catch (err) {
        send(id, { isError: true, content: [{ type: 'text', text: err.message }] });
      }
      break;
    }

    case 'resources/list': {
      const resList = [...skills.values()].map((s) => ({
        uri: `skill://${s.name}`,
        name: `${s.name}.mdr`,
        description: s.description,
        mimeType: 'text/markdown',
      }));

      if (db) {
        resList.push({
          uri: 'db://schema',
          name: 'Schéma SQLite (entreprise.db)',
          description: 'Structure DDL (CREATE TABLE) de la base entreprise.db',
          mimeType: 'text/plain',
        });
      }

      send(id, { resources: resList });
      break;
    }

    case 'resources/templates/list':
      send(id, { resourceTemplates: [] });
      break;

    case 'resources/read': {
      const uri = String(params.uri ?? '');
      if (uri === 'db://schema' && db) {
        send(id, { contents: [{ uri: 'db://schema', mimeType: 'text/plain', text: getDatabaseSchema() }] });
        break;
      }

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