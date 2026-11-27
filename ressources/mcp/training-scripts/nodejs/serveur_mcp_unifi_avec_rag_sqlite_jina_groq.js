#!/usr/bin/env node

/**
 * Serveur MCP Unifié (Skills .mdr + SQLite entreprise.db + RAG SQLite Jina/Groq)
 * - Nécessite une clé env : GROQ_API_KEY="votreCleGroq" (pour les skills .mdr et la génération RAG)
 * - Requis pour la vectorisation RAG : JINA_API_KEY="votreCleJina" (utilise https://api.jina.ai/v1/embeddings avec jina-embeddings-v3)
 * - Si entreprise.db est présente, active les outils SQLite du TP 3 (db://schema + query_database)
 *   et le moteur RAG alimenté uniquement par le corpus documentaire stocké dans entreprise.db.
 *
 * Exemples d'utilisation en ligne de commande (CLI) :
 * - node server-mcp.mjs review-node example-sale.js
 * - node server-mcp.mjs review-node example-sale.js --prompt
 * - node server-mcp.mjs sql "SELECT * FROM produits"
 * - node server-mcp.mjs rag-index
 * - node server-mcp.mjs rag-ask "Quelles sont les règles sur les commandes BLOQUEE ?"
 */

import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import readline from 'node:readline';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ============================================================================
// 1. RECHERCHE RACINE PROJET & CHARGEMENT DU .ENV
// ============================================================================

/**
 * Recherche le répertoire racine contenant les ressources du projet (.prompts, .env, entreprise.db)
 * en donnant priorité au dossier du script afin d'éviter toute ambiguïté d'exécution.
 */
function findProjectRoot(startDir) {
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
const JINA_API_KEY = process.env.JINA_API_KEY ?? '';
const JINA_EMBEDDINGS_URL = 'https://api.jina.ai/v1/embeddings';
const JINA_EMBEDDINGS_MODEL = 'jina-embeddings-v3';
const RAG_TOP_K = 3;
const RAG_MIN_SCORE = 0.35;

// ============================================================================
// 2. MODULE SQLITE (TP 3 - actif automatiquement si entreprise.db existe)
// ============================================================================
const candidateDbs = [
  path.join(__dirname, 'entreprise.db'),
  path.join(__dirname, 'enterprise.db'),
  path.join(projectRoot, 'entreprise.db'),
];
const dbPath = candidateDbs.find((p) => fs.existsSync(p)) ?? null;
const db = dbPath ? new DatabaseSync(dbPath) : null;
const FORBIDDEN_KEYWORDS = /\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b/i;

/**
 * Exécute uniquement une requête SQL SELECT en lecture seule avec garde-fous et LIMIT automatique.
 */
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

/**
 * Retourne le schéma SQL (DDL) des tables applicatives de entreprise.db.
 */
function getDatabaseSchema() {
  if (!db) return '';
  return db
    .prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
    .all()
    .map((r) => `${r.sql};`)
    .join('\n\n');
}

// ============================================================================
// 2B. MODULE RAG SUR CORPUS DOCUMENTAIRE DANS ENTREPRISE.DB (JINA + GROQ)
// ============================================================================
const DEFAULT_DB_DOCS = [
  {
    titre: 'politique-remboursement.md',
    categorie: 'politique-remboursement',
    contenu: `## Délais de rétractation et remboursement\nLe client dispose d'un délai légal de 14 jours calendaires à compter de la réception de la commande pour demander un remboursement ou un retour produit, sous réserve que l'article soit retourné dans son état d'origine.\n\n## Règles selon le statut des commandes\nLes commandes au statut LIVREE peuvent être remboursées après contrôle de réception. Les commandes EXPEDIEE font l'objet d'un suivi transporteur avant remboursement. Les commandes au statut EN_COURS peuvent être annulées sans frais avant préparation logistique. Toute commande au statut BLOQUEE ne peut pas être remboursée automatiquement tant que l'anomalie métier ou le litige n'a pas été levé.\n\n## Validation financière et seuils\nTout remboursement ou avoir supérieur à 500 EUR nécessite une double validation manager et une vérification préalable des stocks et factures associées.`,
  },
  {
    titre: 'architecture-api.md',
    categorie: 'architecture-api',
    contenu: `## Endpoints et formats JSON\nLes services internes exposent des endpoints REST au format JSON UTF-8 avec pagination explicite (limit et offset) et horodatage ISO-8601.\n\n## Gestion des codes d'erreur HTTP\nLes erreurs de validation retournent un statut 400. Les authentifications invalides retournent 401. Les dépassements de quota ou de débit (rate limit) retournent HTTP 429 (Too Many Requests) et doivent être traités avec un délai d'attente progressif (backoff). Les erreurs internes inattendues retournent HTTP 500.\n\n## Résilience et intégration MCP\nLes connecteurs MCP doivent respecter la lecture seule pour les requêtes d'exploration et ne jamais exposer de secrets ou clés API dans les réponses JSON.`,
  },
  {
    titre: 'guide-securite.md',
    categorie: 'guide-securite',
    contenu: `## Intégrité des stocks en base\nAucune ligne de la table produits ne doit présenter un stock négatif ou nul non maîtrisé. Un stock négatif (par exemple -3 sur un écran ou un stock nul sur une webcam) constitue une anomalie critique d'inventaire à auditer sans modifier la base à l'aveugle.\n\n## Traitement des commandes BLOQUEE\nToute commande enregistrée avec le statut BLOQUEE (en majuscules) doit être isolée dans le rapport d'audit de conformité avec son identifiant, son client, son produit, sa quantité et sa date_commande.\n\n## Conformité clients et requêtes SQL\nLes adresses email des clients doivent être valides et non dupliquées, et les segments clients respectent B2B ou B2C. Toute inspection de entreprise.db doit s'appuyer exclusivement sur des requêtes SELECT en lecture seule.`,
  },
];

/**
 * Initialise les tables du corpus documentaire RAG dans entreprise.db si elles n'existent pas encore,
 * et alimente le corpus de référence uniquement lorsque la table rag_documents est vide.
 */
function ensureRagCorpusInDb() {
  if (!db) return { initialized: false, documents: [] };

  db.exec(`
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
  `);

  const existingCount = db.prepare('SELECT COUNT(*) AS total FROM rag_documents').get()?.total ?? 0;
  if (existingCount === 0) {
    const insertDoc = db.prepare(`
      INSERT OR IGNORE INTO rag_documents (titre, categorie, contenu, sha256, updated_at)
      VALUES (?, ?, ?, ?, ?)
    `);
    const nowIso = new Date().toISOString();
    for (const doc of DEFAULT_DB_DOCS) {
      const hash = crypto.createHash('sha256').update(doc.contenu, 'utf8').digest('hex');
      insertDoc.run(doc.titre, doc.categorie, doc.contenu, hash, nowIso);
    }
  }

  const docs = db.prepare('SELECT id, titre, categorie, contenu, sha256, updated_at FROM rag_documents ORDER BY id ASC').all();
  return { initialized: true, documents: docs };
}

/**
 * Découpe un texte documentaire en segments avec chevauchement pour préserver le contexte.
 */
function chunkCorpusText(text, chunkSize = 500, overlap = 75) {
  const normalized = String(text ?? '').replace(/\r\n/g, '\n').trim();
  if (!normalized) return [];
  if (normalized.length <= chunkSize) return [normalized];

  const safeOverlap = Math.max(0, Math.min(overlap, chunkSize - 1));
  const step = Math.max(1, chunkSize - safeOverlap);
  const chunks = [];
  let start = 0;

  while (start < normalized.length) {
    const end = Math.min(normalized.length, start + chunkSize);
    const slice = normalized.slice(start, end).trim();
    if (slice) chunks.push(slice);
    if (end >= normalized.length) break;
    start += step;
  }

  return chunks;
}

/**
 * Calcule la similarité cosinus entre deux vecteurs numériques de même dimension.
 */
function cosineSimilarity(vecA, vecB) {
  if (!Array.isArray(vecA) || !Array.isArray(vecB) || vecA.length === 0 || vecA.length !== vecB.length) {
    return 0;
  }
  let dot = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < vecA.length; i += 1) {
    const a = Number(vecA[i]) || 0;
    const b = Number(vecB[i]) || 0;
    dot += a * b;
    normA += a * a;
    normB += b * b;
  }
  if (normA === 0 || normB === 0) return 0;
  return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}

/**
 * Appelle l'API d'embeddings Jina AI (https://api.jina.ai/v1/embeddings) avec jina-embeddings-v3.
 */
async function embedWithJina(inputs, task = 'retrieval.passage') {
  if (!JINA_API_KEY) {
    throw new Error('Variable JINA_API_KEY absente dans .env (requise pour https://api.jina.ai/v1/embeddings).');
  }
  const list = Array.isArray(inputs) ? inputs : [inputs];
  const res = await fetch(JINA_EMBEDDINGS_URL, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${JINA_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: JINA_EMBEDDINGS_MODEL,
      task,
      input: list,
    }),
  });

  if (!res.ok) {
    throw new Error(`Erreur HTTP Jina Embeddings ${res.status} : ${await res.text()}`);
  }

  const data = await res.json();
  const vectors = (data.data ?? [])
    .slice()
    .sort((a, b) => (a.index ?? 0) - (b.index ?? 0))
    .map((item) => item.embedding);

  if (vectors.length !== list.length || vectors.some((v) => !Array.isArray(v))) {
    throw new Error('Réponse Jina invalide : nombre ou format des vecteurs inattendu.');
  }
  return vectors;
}

/**
 * Indexe ou met à jour dans entreprise.db les embeddings des extraits issus uniquement de rag_documents.
 */
async function indexDbCorpusRag(forceReembed = false) {
  if (!db) throw new Error('Base entreprise.db introuvable : RAG SQLite indisponible.');
  const { documents } = ensureRagCorpusInDb();

  const upsertChunk = db.prepare(`
    INSERT INTO rag_chunks (id, document_id, source, chunk_index, content, sha256, embedding_json)
    VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      document_id = excluded.document_id,
      source = excluded.source,
      chunk_index = excluded.chunk_index,
      content = excluded.content,
      sha256 = excluded.sha256,
      embedding_json = excluded.embedding_json
  `);

  const getExistingChunk = db.prepare('SELECT sha256, embedding_json FROM rag_chunks WHERE id = ?');

  let totalChunks = 0;
  let embeddedNow = 0;
  let reusedFromDb = 0;
  let dimension = 0;

  for (const doc of documents) {
    const chunks = chunkCorpusText(doc.contenu, 500, 75);
    const pendingItems = [];

    for (let i = 0; i < chunks.length; i += 1) {
      totalChunks += 1;
      const chunkId = `${doc.titre}#${i}`;
      const chunkHash = crypto.createHash('sha256').update(chunks[i], 'utf8').digest('hex');
      const existing = getExistingChunk.get(chunkId);

      if (!forceReembed && existing && existing.sha256 === chunkHash && existing.embedding_json) {
        reusedFromDb += 1;
        if (!dimension) {
          try {
            const parsed = JSON.parse(existing.embedding_json);
            if (Array.isArray(parsed)) dimension = parsed.length;
          } catch {
            pendingItems.push({ chunkId, index: i, content: chunks[i], chunkHash });
            reusedFromDb -= 1;
          }
        }
      } else {
        pendingItems.push({ chunkId, index: i, content: chunks[i], chunkHash });
      }
    }

    if (pendingItems.length > 0) {
      const vectors = await embedWithJina(
        pendingItems.map((p) => p.content),
        'retrieval.passage'
      );
      for (let k = 0; k < pendingItems.length; k += 1) {
        const item = pendingItems[k];
        const vec = vectors[k];
        if (!dimension && Array.isArray(vec)) dimension = vec.length;
        upsertChunk.run(item.chunkId, doc.id, doc.titre, item.index, item.content, item.chunkHash, JSON.stringify(vec));
        embeddedNow += 1;
      }
      await new Promise((r) => setTimeout(r, 150));
    }
  }

  return {
    sourceStore: 'entreprise.db (tables rag_documents & rag_chunks)',
    model: JINA_EMBEDDINGS_MODEL,
    documentsCount: documents.length,
    totalChunks,
    embeddedNow,
    reusedFromDb,
    dimension,
  };
}

/**
 * Interroge le moteur RAG en piochant exclusivement dans le corpus documentaire stocké dans entreprise.db.
 */
async function queryDbCorpusRag(question, topK = RAG_TOP_K, minScore = RAG_MIN_SCORE) {
  if (!db) throw new Error('Base entreprise.db introuvable : RAG SQLite indisponible.');
  const cleanQuestion = String(question ?? '').trim();
  if (!cleanQuestion) throw new Error('La question RAG ne peut pas être vide.');

  ensureRagCorpusInDb();

  let rows = db.prepare('SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks').all();
  if (!rows.length || rows.some((r) => !r.embedding_json)) {
    await indexDbCorpusRag(false);
    rows = db.prepare('SELECT id, source, chunk_index, content, embedding_json FROM rag_chunks').all();
  }

  const [queryVector] = await embedWithJina([cleanQuestion], 'retrieval.query');

  const scored = rows
    .map((row) => {
      let vec = [];
      try {
        vec = JSON.parse(row.embedding_json);
      } catch {
        vec = [];
      }
      const score = cosineSimilarity(queryVector, vec);
      return {
        id: row.id,
        source: row.source,
        chunkIndex: row.chunk_index,
        content: row.content,
        score,
      };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, topK);

  const bestScore = scored[0]?.score ?? 0;
  if (!scored.length || bestScore < minScore) {
    return {
      question: cleanQuestion,
      rejected: true,
      bestScore,
      minScore,
      sources: scored,
      answer: 'Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db.',
    };
  }

  const contextBlock = scored
    .map((item, idx) => `[Extrait #${idx + 1} | source: ${item.source} | score: ${item.score.toFixed(4)}]\n${item.content}`)
    .join('\n\n');

  const ragPrompt = [
    'Tu es un assistant strictement basé sur le corpus documentaire extrait de entreprise.db.',
    'Règles impératives :',
    '1. Utilise UNIQUEMENT les extraits fournis ci-dessous. N’invente aucune information externe.',
    '2. Cite explicitement le nom du document source sous la forme [source: nom-fichier.md].',
    '3. Si la réponse ne figure pas dans les extraits, réponds exactement : "Je ne dispose pas de cette information dans le corpus documentaire de entreprise.db."',
    '',
    'EXTRAITS DU CORPUS (entreprise.db) :',
    contextBlock,
    '',
    `QUESTION : ${cleanQuestion}`,
  ].join('\n');

  const answer = await askGroq(ragPrompt);
  return {
    question: cleanQuestion,
    rejected: false,
    bestScore,
    minScore,
    sources: scored,
    answer,
  };
}

// ============================================================================
// 3. MODULE SKILLS .MDR + GROQ
// ============================================================================

/**
 * Envoie un prompt à l'API Groq Chat Completions.
 */
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

/**
 * Charge les fichiers de skills (.mdr) présents dans le dossier .prompts/ du projet.
 */
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

  if (cmd === 'rag-index') {
    try {
      const force = cleanArgs.includes('--force');
      const stats = await indexDbCorpusRag(force);
      console.log(JSON.stringify(stats, null, 2));
      process.exit(0);
    } catch (err) {
      console.error(`[Erreur RAG Index] ${err.message}`);
      process.exit(1);
    }
  }

  if (cmd === 'rag-ask') {
    try {
      const result = await queryDbCorpusRag(target);
      console.log(JSON.stringify(result, null, 2));
      process.exit(0);
    } catch (err) {
      console.error(`[Erreur RAG Ask] ${err.message}`);
      process.exit(1);
    }
  }

  if (!skills.has(cmd)) {
    const avail = [...skills.keys()].join(', ') || '(aucun .mdr dans .prompts/)';
    console.error(`Skill introuvable : '${cmd}'\nDisponibles : ${avail}${db ? ', sql, rag-index, rag-ask' : ''}`);
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
// 5. MOTEUR JSON-RPC 2.0 / MCP UNIFIÉ (MDR + SQLITE + RAG CORPUS DB)
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
        serverInfo: { name: 'mcp-unified-node', version: '1.1.0' },
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

        toolsList.push({
          name: 'rag_search_db_corpus',
          description: 'Recherche RAG (Jina jina-embeddings-v3 + Groq) uniquement dans le corpus documentaire de entreprise.db.',
          inputSchema: {
            type: 'object',
            properties: {
              question: { type: 'string', minLength: 3, description: 'Question métier à vérifier dans le corpus documentaire de entreprise.db' },
              topK: { type: 'number', description: 'Nombre maximum d’extraits retournés (défaut: 3)' },
            },
            required: ['question'],
          },
        });

        toolsList.push({
          name: 'rag_index_db_corpus',
          description: 'Indexe ou met à jour les embeddings Jina (jina-embeddings-v3) du corpus documentaire stocké dans entreprise.db.',
          inputSchema: {
            type: 'object',
            properties: {
              force: { type: 'boolean', description: 'Force le recalcul complet des vecteurs (défaut: false)' },
            },
            required: [],
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

      if (params.name === 'rag_search_db_corpus') {
        try {
          const topK = Number.isFinite(Number(params.arguments?.topK)) ? Math.max(1, Number(params.arguments.topK)) : RAG_TOP_K;
          const result = await queryDbCorpusRag(params.arguments?.question, topK, RAG_MIN_SCORE);
          send(id, { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] });
        } catch (err) {
          send(id, { isError: true, content: [{ type: 'text', text: err.message }] });
        }
        break;
      }

      if (params.name === 'rag_index_db_corpus') {
        try {
          const stats = await indexDbCorpusRag(Boolean(params.arguments?.force));
          send(id, { content: [{ type: 'text', text: JSON.stringify(stats, null, 2) }] });
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

        resList.push({
          uri: 'rag://db-corpus',
          name: 'Corpus Documentaire SQLite (entreprise.db)',
          description: 'Documents métier stockés dans entreprise.db pour le moteur RAG',
          mimeType: 'application/json',
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

      if (uri === 'rag://db-corpus' && db) {
        try {
          const { documents } = ensureRagCorpusInDb();
          send(id, { contents: [{ uri: 'rag://db-corpus', mimeType: 'application/json', text: JSON.stringify(documents, null, 2) }] });
        } catch (err) {
          send(id, { isError: true, content: [{ type: 'text', text: err.message }] });
        }
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