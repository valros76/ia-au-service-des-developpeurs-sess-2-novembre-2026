# TP 4 : Corrigé Complet — Chaîne RAG sans framework et workflow agentique combiné (Node.js)

Ce document fournit l'implémentation de référence complète du **TP 4** en **Node.js 20+ pur** (ES Modules, `fetch` natif, zéro dépendance externe), utilisant **Jina AI** (`jina-embeddings-v3`) pour la vectorisation et **Groq** (`llama-3.3-70b-versatile`) pour la génération.

---

## Partie A : Moteur RAG minimaliste en Node.js (`tp4-rag/`)

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

### 2. Module de découpage `tp4-rag/chunker.js`

```javascript
/**
 * Découpe un texte en segments de taille maximale `chunkSize`
 * en conservant un chevauchement de `overlap` caractères entre deux segments.
 *
 * @param {string} text - Texte source à découper
 * @param {number} chunkSize - Taille maximale d'un morceau en caractères (défaut : 500)
 * @param {number} overlap - Nombre de caractères de chevauchement (défaut : 75)
 * @returns {string[]} Liste des morceaux générés
 */
export function chunkText(text, chunkSize = 500, overlap = 75) {
  if (typeof text !== "string" || text.trim().length === 0) {
    return [];
  }
  if (overlap >= chunkSize) {
    throw new Error("Le chevauchement (overlap) doit être strictement inférieur à chunkSize.");
  }

  const cleanText = text.replace(/\r\n/g, "\n").trim();
  const chunks = [];
  let start = 0;

  while (start < cleanText.length) {
    const end = Math.min(start + chunkSize, cleanText.length);
    const slice = cleanText.slice(start, end);
    chunks.push(slice);

    if (end === cleanText.length) {
      break;
    }

    start += chunkSize - overlap;
  }

  return chunks;
}

// Exécution directe pour validation locale (sans appel API) : node chunker.js
if (import.meta.url === `file://${process.argv[1]}`) {
  const { readdir, readFile } = await import("node:fs/promises");
  const { join } = await import("node:path");
  const { fileURLToPath } = await import("node:url");

  const docsDir = fileURLToPath(new URL("./docs", import.meta.url));
  const files = (await readdir(docsDir)).filter((f) => f.endsWith(".md"));

  for (const file of files) {
    const content = await readFile(join(docsDir, file), "utf8");
    const chunks = chunkText(content, 500, 75);
    console.log(`Fichier "${file}" (${content.length} car.) -> ${chunks.length} morceaux`);

    for (let i = 0; i < chunks.length - 1; i++) {
      const tail = chunks[i].slice(-75);
      const head = chunks[i + 1].slice(0, 75);
      if (tail !== head) {
        console.error(`Erreur de chevauchement entre ${i} et ${i + 1} dans ${file}`);
      }
    }
  }
  console.log("Validation du chevauchement (75 caractères) réussie !");
}
```

---

### 3. Script d'indexation `tp4-rag/indexer.js`

Exécution : `node --env-file=../.env indexer.js` (adaptez le chemin vers votre `.env`).

```javascript
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { chunkText } from "./chunker.js";

const JINA_API_KEY = process.env.JINA_API_KEY;
if (!JINA_API_KEY) {
  console.error("Erreur : JINA_API_KEY manquante dans le fichier .env");
  process.exit(1);
}

const docsDir = fileURLToPath(new URL("./docs", import.meta.url));
const storePath = fileURLToPath(new URL("./vector-store.json", import.meta.url));

/**
 * Appelle l'API Jina AI (jina-embeddings-v3) pour vectoriser un passage documentaire.
 *
 * @param {string} text
 * @returns {Promise<number[]>}
 */
async function embedPassage(text) {
  const response = await fetch("https://api.jina.ai/v1/embeddings", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${JINA_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "jina-embeddings-v3",
      task: "retrieval.passage",
      input: [text],
    }),
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Erreur API Jina (${response.status}) : ${errText}`);
  }

  const data = await response.json();
  return data.data[0].embedding;
}

const startTime = performance.now();
const files = (await readdir(docsDir)).filter((f) => f.endsWith(".md")).sort();

if (files.length === 0) {
  console.error("Aucun fichier .md trouvé dans le dossier docs/.");
  process.exit(1);
}

const vectorStore = [];

for (const file of files) {
  const content = await readFile(join(docsDir, file), "utf8");
  const chunks = chunkText(content, 500, 75);

  console.log(`Indexation de ${file} (${chunks.length} morceaux)...`);

  for (let i = 0; i < chunks.length; i++) {
    const embedding = await embedPassage(chunks[i]);
    vectorStore.push({
      id: `${file}#${i}`,
      source: file,
      content: chunks[i],
      embedding,
    });

    // Pause de 200 ms pour respecter les limites de débit de l'API gratuite
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}

await writeFile(storePath, JSON.stringify(vectorStore, null, 2), "utf8");
const durationMs = performance.now() - startTime;
const dimension = vectorStore[0]?.embedding?.length ?? 0;

console.log("\n--- Indexation terminée ---");
console.log(`Vecteurs générés : ${vectorStore.length}`);
console.log(`Dimension        : ${dimension}`);
console.log(`Temps total      : ${durationMs.toFixed(2)} ms`);
console.log(`Fichier généré   : ${storePath}`);
```

---

### 4. Script d'interrogation `tp4-rag/ask-rag.js`

Exécution : `node --env-file=../.env ask-rag.js "Quel est le seuil de validation d'un remboursement de 250 euros ?"`

```javascript
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const JINA_API_KEY = process.env.JINA_API_KEY;
const GROQ_API_KEY = process.env.GROQ_API_KEY;

if (!JINA_API_KEY || !GROQ_API_KEY) {
  console.error("Erreur : JINA_API_KEY et GROQ_API_KEY doivent être définies dans .env");
  process.exit(1);
}

const question = process.argv[2];
if (!question || question.trim().length === 0) {
  console.error('Usage : node --env-file=../.env ask-rag.js "Votre question"');
  process.exit(1);
}

const SIMILARITY_THRESHOLD = 0.35;
const TOP_K = 3;
const storePath = fileURLToPath(new URL("./vector-store.json", import.meta.url));

/**
 * Calcule la similarité cosinus entre deux vecteurs numériques de même dimension.
 *
 * @param {number[]} vecA
 * @param {number[]} vecB
 * @returns {number}
 */
function cosineSimilarity(vecA, vecB) {
  let dotProduct = 0;
  let normA = 0;
  let normB = 0;

  for (let i = 0; i < vecA.length; i++) {
    dotProduct += vecA[i] * vecB[i];
    normA += vecA[i] * vecA[i];
    normB += vecB[i] * vecB[i];
  }

  const denominator = Math.sqrt(normA) * Math.sqrt(normB);
  return denominator === 0 ? 0 : dotProduct / denominator;
}

/**
 * Vectorise la question utilisateur via Jina AI (task: retrieval.query).
 *
 * @param {string} queryText
 * @returns {Promise<number[]>}
 */
async function embedQuery(queryText) {
  const response = await fetch("https://api.jina.ai/v1/embeddings", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${JINA_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "jina-embeddings-v3",
      task: "retrieval.query",
      input: [queryText],
    }),
  });

  if (!response.ok) {
    throw new Error(`Erreur Jina Query (${response.status}) : ${await response.text()}`);
  }

  const data = await response.json();
  return data.data[0].embedding;
}

/**
 * Interroge Groq (llama-3.3-70b-versatile) uniquement à partir du contexte extrait.
 *
 * @param {string} userQuestion
 * @param {Array<{ id: string, source: string, content: string, score: number }>} topChunks
 * @returns {Promise<string>}
 */
async function generateAnswer(userQuestion, topChunks) {
  const formattedContext = topChunks
    .map((chunk) => `--- EXTRAIT [source: ${chunk.source}] (score: ${chunk.score.toFixed(4)}) ---\n${chunk.content}`)
    .join("\n\n");

  const systemPrompt = [
    "Tu es un assistant documentaire interne strict.",
    "RÈGLES IMPÉRATIVES :",
    "1. Réponds UNIQUEMENT à partir des extraits de contexte fournis ci-dessous.",
    "2. Interdiction absolue d'utiliser tes connaissances générales ou d'inventer des informations.",
    "3. Cite systématiquement le fichier source sous la forme [source: nom-du-fichier.md] après chaque affirmation.",
    '4. Si le contexte ne permet pas de répondre à la question, réponds exactement : "Je ne dispose pas de cette information dans la documentation fournie."',
  ].join("\n");

  const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${GROQ_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "llama-3.3-70b-versatile",
      temperature: 0.0,
      messages: [
        { role: "system", content: systemPrompt },
        {
          role: "user",
          content: `CONTEXTE DOCUMENTAIRE :\n${formattedContext}\n\nQUESTION :\n${userQuestion}`,
        },
      ],
    }),
  });

  if (!response.ok) {
    throw new Error(`Erreur Groq Chat (${response.status}) : ${await response.text()}`);
  }

  const data = await response.json();
  return data.choices[0].message.content;
}

// --- Exécution principale ---
const vectorStore = JSON.parse(await readFile(storePath, "utf8"));
const queryVector = await embedQuery(question);

const scoredChunks = vectorStore
  .map((entry) => ({
    ...entry,
    score: cosineSimilarity(queryVector, entry.embedding),
  }))
  .sort((a, b) => b.score - a.score)
  .slice(0, TOP_K);

console.log(`\nQuestion : "${question}"`);
console.log("--- Top-3 des morceaux les plus proches ---");
for (const chunk of scoredChunks) {
  console.log(`• [${chunk.id}] Score cosinus : ${chunk.score.toFixed(4)}`);
}

if (scoredChunks.length === 0 || scoredChunks[0].score < SIMILARITY_THRESHOLD) {
  console.log(
    `\n[Rejet automatique] Score maximal (${scoredChunks[0]?.score.toFixed(4) ?? 0}) inférieur au seuil (${SIMILARITY_THRESHOLD}).`
  );
  console.log("Réponse : Je ne dispose pas de cette information dans la documentation fournie.");
  process.exit(0);
}

console.log("\n--- Réponse générée par Groq (llama-3.3-70b-versatile) ---");
const answer = await generateAnswer(question, scoredChunks);
console.log(answer);
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
## TP 4 : Chaîne RAG (Jina v3 + Groq) & Orchestration Agentique MCP

### 1. Banc d'essai de robustesse du moteur RAG (`ask-rag.js`)

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