# Correction Complète — 10 Exercices TP 3 MCP & SQLite

Cette correction fournit la solution détaillée de chacun des **10 exercices** ainsi qu'un script `init-db.js` **100 % cohérent** (21 enregistrements exacts : 6 clients, 6 produits dont 2 à stock négatif, 9 commandes dont 2 au statut `'BLOQUEE'`, avec des clés étrangères valides).

---

## Jeu de données de référence (`init-db.js` — 21 enregistrements)

Enregistrez ce fichier sous `init-db.js` dans le dossier du serveur MCP (`tp3-mcp-server/` ou `ressources/mcp/training-scripts/nodejs/`) et exécutez `node init-db.js` :

```javascript
import { DatabaseSync } from 'node:sqlite';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const dbPath = path.join(__dirname, 'entreprise.db');
const db = new DatabaseSync(dbPath);

db.exec(`
  PRAGMA foreign_keys = ON;

  DROP TABLE IF EXISTS commandes;
  DROP TABLE IF EXISTS produits;
  DROP TABLE IF EXISTS clients;

  CREATE TABLE clients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nom TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    segment TEXT NOT NULL
  );

  CREATE TABLE produits (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nom TEXT NOT NULL,
    prix REAL NOT NULL,
    stock INTEGER NOT NULL
  );

  CREATE TABLE commandes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id INTEGER NOT NULL REFERENCES clients(id),
    produit_id INTEGER NOT NULL REFERENCES produits(id),
    quantite INTEGER NOT NULL,
    statut TEXT NOT NULL,
    date_commande TEXT NOT NULL
  );

  -- 6 clients
  INSERT INTO clients (nom, email, segment) VALUES
    ('Acme Corp', 'contact@acme.fr', 'B2B'),
    ('TechNova', 'achats@technova.io', 'B2B'),
    ('Claire Martin', 'claire.m@gmail.com', 'B2C'),
    ('Lucas Bernard', 'l.bernard@outlook.fr', 'B2C'),
    ('Studio Pixel', 'admin@studiopixel.fr', 'B2B'),
    ('Emma Leroy', 'emma.leroy@orange.fr', 'B2C');

  -- 6 produits (dont 2 à stock négatif : id 3 et id 5)
  INSERT INTO produits (nom, prix, stock) VALUES
    ('Clavier Mécanique Pro', 129.90, 42),
    ('Écran 27" 4K', 399.00, 15),
    ('Dock USB-C', 89.50, -3),
    ('Souris Ergonomique', 69.00, 28),
    ('Webcam HD', 54.00, -1),
    ('Casque Audio Studio', 149.00, 19);

  -- 9 commandes (dont 2 au statut BLOQUEE : id 4 et id 6)
  INSERT INTO commandes (client_id, produit_id, quantite, statut, date_commande) VALUES
    (1, 1, 5, 'LIVREE', '2026-10-01'),
    (2, 2, 2, 'LIVREE', '2026-10-05'),
    (3, 4, 1, 'EXPEDIEE', '2026-10-12'),
    (1, 3, 3, 'BLOQUEE', '2026-10-15'),
    (4, 1, 1, 'LIVREE', '2026-10-18'),
    (5, 5, 1, 'BLOQUEE', '2026-10-20'),
    (2, 4, 3, 'EN_COURS', '2026-11-02'),
    (3, 2, 1, 'LIVREE', '2026-11-10'),
    (6, 6, 2, 'EXPEDIEE', '2026-11-18');
`);

const counts = db.prepare(`
  SELECT
    (SELECT COUNT(*) FROM clients) AS nb_clients,
    (SELECT COUNT(*) FROM produits) AS nb_produits,
    (SELECT COUNT(*) FROM commandes) AS nb_commandes
`).get();

console.log('Base entreprise.db initialisée :', counts);
```

---

## Correction Exercice 1 : Confinement strict du périmètre `filesystem`

### Configuration Continue (`.continue/mcpServers/tp3-mcp.yaml`)
```yaml
name: TP3 MCP Servers
version: 0.0.1
schema: v1
mcpServers:
  - name: filesystem
    command: npx
    args:
      - -y
      - "@modelcontextprotocol/server-filesystem"
      - "."
```

### Configuration VS Code (`.vscode/mcp.json`)
```json
{
  "servers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "${workspaceFolder}"
      ]
    }
  }
}
```

### Vérification du confinement
* Tenter de lire `C:/Windows/win.ini` (Windows) ou `/etc/passwd` (Linux/macOS) via `read_text_file` échoue avec une erreur d'accès hors des répertoires autorisés.

---

## Correction Exercice 2 : Exploration d'arborescence et lecture multi-fichiers

### Prompt en mode Agent (avec vérification du nom exact des dossiers)
```text
Liste d'abord la racine "." avec l'outil filesystem pour vérifier le nom exact des dossiers, puis explore le dossier cible et lis simultanément init-db.js et server.js (ou server-mcp.mjs) en un seul appel multi-fichiers pour résumer leur rôle.
```

---

## Correction Exercice 3 : Génération de `entreprise.db` (21 lignes)

* Exécution : `node init-db.js`
* Résultat vérifié :
  * `clients` : **6 lignes** (`id` 1 à 6).
  * `produits` : **6 lignes** (`id` 1 à 6), dont **2 anomalies de stock négatif** :
    * `id = 3` (`Dock USB-C`) : `stock = -3`
    * `id = 5` (`Webcam HD`) : `stock = -1`
  * `commandes` : **9 lignes** (`id` 1 à 9), dont **2 anomalies `'BLOQUEE'`** :
    * `id = 4` (`client_id = 1` Acme Corp, `produit_id = 3` Dock USB-C, `quantite = 3`, `statut = 'BLOQUEE'`)
    * `id = 6` (`client_id = 5` Studio Pixel, `produit_id = 5` Webcam HD, `quantite = 1`, `statut = 'BLOQUEE'`)

---

## Correction Exercice 4 : Ressource `db://schema`

```javascript
function getDatabaseSchema(db) {
  if (!db) return '';
  return db
    .prepare("SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid")
    .all()
    .map((row) => `${row.sql};`)
    .join('\n\n');
}
```

* Lors de `resources/read` avec `uri: "db://schema"`, la fonction retourne les 3 instructions `CREATE TABLE` (`clients`, `produits`, `commandes`) sans exposer `sqlite_sequence`.

---

## Correction Exercice 5 : Validation des entrées (`z.string().min(10)`)

Avec `@modelcontextprotocol/sdk` et `zod` :

```javascript
import { z } from 'zod';

const queryDatabaseSchema = z.object({
  sql: z.string().min(10, 'La requête SQL doit contenir au moins 10 caractères.'),
});
```

Et côté garde-fou applicatif (dans un serveur JSON-RPC autonome) :

```javascript
if (typeof rawSql !== 'string' || rawSql.trim().length < 10) {
  throw new Error('Paramètre sql invalide (minimum 10 caractères requis).');
}
```

* `""` (0 car.) ou `"SELECT 1"` (8 car.) sont rejetés immédiatement.

---

## Correction Exercice 6 : Blindage anti-injection et interdiction des écritures

```javascript
const FORBIDDEN_KEYWORDS = /\b(DROP|DELETE|UPDATE|INSERT|ALTER|PRAGMA|ATTACH|DETACH|REPLACE|VACUUM)\b/i;

function validateAndCleanSelect(rawSql) {
  if (typeof rawSql !== 'string' || rawSql.trim().length < 10) {
    throw new Error('Paramètre sql invalide (minimum 10 caractères requis).');
  }

  const cleaned = rawSql.trim().replace(/;+$/, '');
  if (cleaned.includes(';')) {
    throw new Error('Sécurité : requêtes multiples interdites.');
  }
  if (!/^SELECT\b/i.test(cleaned)) {
    throw new Error('Sécurité : seul SELECT est autorisé.');
  }
  if (FORBIDDEN_KEYWORDS.test(cleaned)) {
    throw new Error('Sécurité : mot-clé SQL interdit.');
  }

  return cleaned;
}
```

---

## Correction Exercice 7 : `LIMIT 50` automatique

```javascript
function buildLimitedSelect(cleanedSql) {
  return /\bLIMIT\s+\d+/i.test(cleanedSql)
    ? cleanedSql
    : `${cleanedSql} LIMIT 50`;
}
```

* `SELECT * FROM commandes` -> `SELECT * FROM commandes LIMIT 50` (renvoie les 9 commandes).
* `SELECT * FROM produits LIMIT 3` -> `SELECT * FROM produits LIMIT 3` (renvoie 3 produits).

---

## Correction Exercice 8 : Lecture seule et résilience des chemins

```javascript
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function resolveDbPath(startDir) {
  const candidates = [
    path.join(__dirname, 'entreprise.db'),
    path.join(startDir, 'entreprise.db'),
    path.join(startDir, '..', 'entreprise.db'),
  ];
  return candidates.find((p) => fs.existsSync(p)) ?? null;
}

const dbPath = resolveDbPath(process.cwd());
const db = dbPath ? new DatabaseSync(dbPath, { readOnly: true }) : null;
```

* L'option `{ readOnly: true }` empêche toute écriture physique sur `entreprise.db` même si une requête contournait un filtre applicatif.

---

## Correction Exercice 9 : Validation avec MCP Inspector

```bash
npx @modelcontextprotocol/inspector node server.js
```

1. **Resources -> List -> Read `db://schema`** : affiche les DDL de `clients`, `produits` et `commandes`.
2. **Tools -> Call `query_database`** :
   * `{"sql": "SELECT id, nom, stock FROM produits WHERE stock < 0"}` -> renvoie `Dock USB-C` (`-3`) et `Webcam HD` (`-1`).
   * `{"sql": "DROP TABLE clients;"}` -> renvoie une erreur explicite (`Sécurité : seul SELECT est autorisé.` / `DROP`).

---

## Correction Exercice 10 : Audit métier en langage naturel sans mutation

### Requête envoyée dans l'IDE
```text
Analyse le schéma de la base de données, trouve les anomalies de stock ou de commandes bloquées, et propose-moi les requêtes SQL de correction sans les exécuter.
```

### Requêtes `SELECT` exécutées par l'assistant via `query_database`
```sql
SELECT id, nom, prix, stock FROM produits WHERE stock < 0;

SELECT cmd.id, cmd.statut, cmd.quantite, cmd.date_commande,
       c.nom AS client_nom, c.email,
       p.nom AS produit_nom, p.stock AS produit_stock
FROM commandes cmd
JOIN clients c ON c.id = cmd.client_id
JOIN produits p ON p.id = cmd.produit_id
WHERE cmd.statut = 'BLOQUEE';
```

### Requêtes `UPDATE` proposées (non exécutées)
```sql
-- 1. Recaler les stocks négatifs à 0 (à ajuster selon l'inventaire réel)
UPDATE produits SET stock = 0 WHERE id IN (3, 5);

-- 2. Requalifier ou régulariser les commandes BLOQUEE une fois le stock validé
UPDATE commandes SET statut = 'EN_COURS' WHERE id IN (4, 6);
```

* Après la réponse, la base `entreprise.db` conserve intactes les **2 lignes de stock négatif** et les **2 commandes `'BLOQUEE'`**.