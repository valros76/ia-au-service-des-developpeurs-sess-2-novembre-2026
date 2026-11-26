# Guide complet des commandes MCP & Base SQLite (`entreprise.db`)

Ce document centralise toutes les commandes nécessaires pour initialiser la base de données du TP 3, tester les serveurs MCP unifiés (`.mdr` + SQLite) en ligne de commande, les valider avec **MCP Inspector** et les brancher dans un IDE (**VS Code** ou **Continue**) pour les 4 langages supportés (**Node.js**, **Python**, **PHP** et **Java**).

---

## 1. Initialisation de la base de données SQLite (`entreprise.db`)

Les serveurs MCP détectent automatiquement la présence du fichier `entreprise.db` à la racine du projet (ou dans un dossier parent) et activent dynamiquement la ressource `db://schema` ainsi que l'outil `query_database`.

### Méthode A : Via Node.js 22/24+ (Module natif `node:sqlite` — Recommandé)
Crée un fichier `init-db.js` à la racine du projet :

```javascript
import { DatabaseSync } from 'node:sqlite';

const db = new DatabaseSync('entreprise.db');

db.exec(`
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

  INSERT INTO clients (nom, email, segment) VALUES
    ('Acme Corp', 'contact@acme.fr', 'B2B'),
    ('TechNova', 'achats@technova.io', 'B2B'),
    ('Claire Martin', 'claire.m@gmail.com', 'B2C'),
    ('Lucas Bernard', 'l.bernard@outlook.fr', 'B2C'),
    ('Studio Pixel', 'admin@studiopixel.fr', 'B2B');

  INSERT INTO produits (nom, prix, stock) VALUES
    ('Clavier Mécanique Pro', 129.90, 42),
    ('Écran 27" 4K', 399.00, 15),
    ('Dock USB-C', 89.50, -3),       -- Anomalie volontaire : stock négatif
    ('Souris Ergonomique', 69.00, 28),
    ('Webcam HD', 54.00, -1);        -- Anomalie volontaire : stock négatif

  INSERT INTO commandes (client_id, produit_id, quantite, statut, date_commande) VALUES
    (1, 1, 5, 'LIVREE', '2026-10-01'),
    (2, 2, 2, 'LIVREE', '2026-10-05'),
    (3, 4, 1, 'EXPEDIEE', '2026-10-12'),
    (1, 3, 10, 'BLOQUEE', '2026-10-15'), -- Anomalie volontaire : commande bloquée
    (4, 1, 1, 'LIVREE', '2026-10-18'),
    (5, 5, 4, 'BLOQUEE', '2026-10-20'),  -- Anomalie volontaire : commande bloquée
    (2, 4, 3, 'EN_COURS', '2026-11-02'),
    (3, 2, 1, 'LIVREE', '2026-11-10');
`);

console.log('Base entreprise.db générée avec succès.');
```

**Exécution :**
```bash
node init-db.js
```

### Méthode B : Via Python 3 (Module standard `sqlite3`)
Si Node.js n'est pas utilisé sur le poste, exécute cette commande directe :

```bash
python3 -c "import sqlite3; c = sqlite3.connect('entreprise.db'); c.executescript('''
DROP TABLE IF EXISTS commandes; DROP TABLE IF EXISTS produits; DROP TABLE IF EXISTS clients;
CREATE TABLE clients (id INTEGER PRIMARY KEY AUTOINCREMENT, nom TEXT NOT NULL, email TEXT UNIQUE NOT NULL, segment TEXT NOT NULL);
CREATE TABLE produits (id INTEGER PRIMARY KEY AUTOINCREMENT, nom TEXT NOT NULL, prix REAL NOT NULL, stock INTEGER NOT NULL);
CREATE TABLE commandes (id INTEGER PRIMARY KEY AUTOINCREMENT, client_id INTEGER NOT NULL, produit_id INTEGER NOT NULL, quantite INTEGER NOT NULL, statut TEXT NOT NULL, date_commande TEXT NOT NULL);
INSERT INTO clients (nom, email, segment) VALUES ('Acme Corp','contact@acme.fr','B2B'),('TechNova','achats@technova.io','B2B'),('Claire Martin','claire.m@gmail.com','B2C');
INSERT INTO produits (nom, prix, stock) VALUES ('Clavier Pro',129.9,42),('Dock USB-C',89.5,-3),('Webcam HD',54.0,-1);
INSERT INTO commandes (client_id, produit_id, quantite, statut, date_commande) VALUES (1,1,5,'LIVREE','2026-10-01'),(1,2,10,'BLOQUEE','2026-10-15'),(3,3,4,'BLOQUEE','2026-10-20');
'''); c.commit(); print('Base entreprise.db générée avec succès.')"
```

---

## 2. Prérequis d'environnement (`.env`)

À la racine du projet, créer un fichier `.env` contenant la clé API Groq (requise uniquement pour l'exécution des skills `.mdr`, facultative pour les tests `--prompt` et `sql`) :

```env
GROQ_API_KEY="gsk_votre_cle_api_groq"
```

---

## 3. Tableau récapitulatif rapide par langage

| Action / Scénario | Node.js (`server-mcp.mjs`) | Python 3 (`server-mcp.py`) | PHP 8.5 (`server-mcp.php`) | Java 25 (`ServerMcp.java`) |
| :--- | :--- | :--- | :--- | :--- |
| **Prévisualiser un prompt `.mdr`** *(sans appel API)* | `node server-mcp.mjs revue_node exemple_sale.js --prompt` | `python3 server-mcp.py revue_python exemple_sale.py --prompt` | `php server-mcp.php revue_php85 exemple_sale.php --prompt` | `java ServerMcp.java revue_java ExempleSale.java --prompt` |
| **Exécuter un skill `.mdr`** *(appel API Groq)* | `node server-mcp.mjs revue_node exemple_sale.js` | `python3 server-mcp.py revue_python exemple_sale.py` | `php server-mcp.php revue_php85 exemple_sale.php` | `java ServerMcp.java revue_java ExempleSale.java` |
| **Tester un `SELECT` SQLite** *(ajout auto `LIMIT 50`)* | `node server-mcp.mjs sql "SELECT * FROM produits"` | `python3 server-mcp.py sql "SELECT * FROM produits"` | `php server-mcp.php sql "SELECT * FROM produits"` | `java ServerMcp.java sql "SELECT * FROM produits"` |
| **Tester la sécurité SQL** *(doit être rejeté)* | `node server-mcp.mjs sql "DROP TABLE clients"` | `python3 server-mcp.py sql "DROP TABLE clients"` | `php server-mcp.php sql "DROP TABLE clients"` | `java ServerMcp.java sql "DROP TABLE clients"` |
| **Lister les commandes disponibles** | `node server-mcp.mjs list` | `python3 server-mcp.py list` | `php server-mcp.php list` | `java ServerMcp.java list` |
| **Lancer MCP Inspector** *(interface web de test)* | `npx @modelcontextprotocol/inspector node server-mcp.mjs` | `npx @modelcontextprotocol/inspector python3 server-mcp.py` | `npx @modelcontextprotocol/inspector php server-mcp.php` | `npx @modelcontextprotocol/inspector java ServerMcp.java` |

---

## 4. Détail des commandes par langage

### Node.js (v22.5+ / v24 LTS)
```bash
# 1. Prévisualisation d'un skill .mdr (zéro token consommé)
node server-mcp.mjs revue_node exemple_sale.js --prompt

# 2. Exécution d'un skill .mdr via Groq
node server-mcp.mjs revue_node exemple_sale.js

# 3. Exécution d'un skill transversal
node server-mcp.mjs expliquer_ligne_par_ligne exemple_sale.js
node server-mcp.mjs generer_readme exemple_sale.js

# 4. Interrogation SQLite en lecture seule (TP 3)
node server-mcp.mjs sql "SELECT * FROM produits WHERE stock < 0"
node server-mcp.mjs sql "SELECT c.nom, cmd.statut FROM commandes cmd JOIN clients c ON c.id = cmd.client_id WHERE cmd.statut = 'BLOQUEE'"

# 5. Test de rejet d'injection SQL
node server-mcp.mjs sql "DROP TABLE clients"

# 6. Validation visuelle via MCP Inspector
npx @modelcontextprotocol/inspector node server-mcp.mjs
```

### Python (3.13 / 3.14+)
*(Note : sous Windows, remplacer `python3` par `python` si nécessaire).*
```bash
# 1. Prévisualisation d'un skill .mdr (zéro token consommé)
python3 server-mcp.py revue_python exemple_sale.py --prompt

# 2. Exécution d'un skill .mdr via Groq
python3 server-mcp.py revue_python exemple_sale.py

# 3. Exécution d'un skill transversal
python3 server-mcp.py expliquer_ligne_par_ligne exemple_sale.py
python3 server-mcp.py generer_readme exemple_sale.py

# 4. Interrogation SQLite en lecture seule (TP 3)
python3 server-mcp.py sql "SELECT * FROM produits WHERE stock < 0"
python3 server-mcp.py sql "SELECT c.nom, cmd.statut FROM commandes cmd JOIN clients c ON c.id = cmd.client_id WHERE cmd.statut = 'BLOQUEE'"

# 5. Test de rejet d'injection SQL
python3 server-mcp.py sql "DROP TABLE clients"

# 6. Validation visuelle via MCP Inspector
npx @modelcontextprotocol/inspector python3 server-mcp.py
```

### PHP (8.5)
```bash
# 1. Vérification syntaxique
php -l server-mcp.php

# 2. Prévisualisation d'un skill .mdr (zéro token consommé)
php server-mcp.php revue_php85 exemple_sale.php --prompt

# 3. Exécution d'un skill .mdr via Groq
php server-mcp.php revue_php85 exemple_sale.php

# 4. Exécution d'un skill transversal
php server-mcp.php expliquer_ligne_par_ligne exemple_sale.php
php server-mcp.php generer_readme exemple_sale.php

# 5. Interrogation SQLite en lecture seule (TP 3)
php server-mcp.php sql "SELECT * FROM produits WHERE stock < 0"
php server-mcp.php sql "SELECT c.nom, cmd.statut FROM commandes cmd JOIN clients c ON c.id = cmd.client_id WHERE cmd.statut = 'BLOQUEE'"

# 6. Test de rejet d'injection SQL
php server-mcp.php sql "DROP TABLE clients"

# 7. Validation visuelle via MCP Inspector
npx @modelcontextprotocol/inspector php server-mcp.php
```

### Java (25 LTS — exécution directe sans compilation)
*(Prérequis SQLite : binaire `sqlite3` disponible dans le `PATH` système).*
```bash
# 1. Prévisualisation d'un skill .mdr (zéro token consommé)
java ServerMcp.java revue_java ExempleSale.java --prompt

# 2. Exécution d'un skill .mdr via Groq
java ServerMcp.java revue_java ExempleSale.java

# 3. Exécution d'un skill transversal
java ServerMcp.java expliquer_ligne_par_ligne ExempleSale.java
java ServerMcp.java generer_readme ExempleSale.java

# 4. Interrogation SQLite en lecture seule (TP 3)
java ServerMcp.java sql "SELECT * FROM produits WHERE stock < 0"
java ServerMcp.java sql "SELECT c.nom, cmd.statut FROM commandes cmd JOIN clients c ON c.id = cmd.client_id WHERE cmd.statut = 'BLOQUEE'"

# 5. Test de rejet d'injection SQL
java ServerMcp.java sql "DROP TABLE clients"

# 6. Validation visuelle via MCP Inspector
npx @modelcontextprotocol/inspector java ServerMcp.java
```

---

## 5. Configurations IDE (TP 3 : Marketplace + Serveur Custom)

### VS Code (`.vscode/mcp.json`)
```json
{
  "servers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${workspaceFolder}"]
    },
    "mcp-node": {
      "type": "stdio",
      "command": "node",
      "args": ["${workspaceFolder}/server-mcp.mjs"],
      "cwd": "${workspaceFolder}"
    },
    "mcp-python": {
      "type": "stdio",
      "command": "python3",
      "args": ["${workspaceFolder}/server-mcp.py"],
      "cwd": "${workspaceFolder}"
    },
    "mcp-php": {
      "type": "stdio",
      "command": "php",
      "args": ["${workspaceFolder}/server-mcp.php"],
      "cwd": "${workspaceFolder}"
    },
    "mcp-java": {
      "type": "stdio",
      "command": "java",
      "args": ["${workspaceFolder}/ServerMcp.java"],
      "cwd": "${workspaceFolder}"
    }
  }
}
```

### Extension Continue (`.continue/mcpServers/tp3-mcp.yaml`)
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

  # Conserver uniquement le serveur du langage utilisé :
  - name: mcp-node
    command: node
    args:
      - server-mcp.mjs

  # - name: mcp-python
  #   command: python3
  #   args:
  #     - server-mcp.py

  # - name: mcp-php
  #   command: php
  #   args:
  #     - server-mcp.php

  # - name: mcp-java
  #   command: java
  #   args:
  #     - ServerMcp.java
```

### Claude Code / Cursor (`.mcp.json` ou `.cursor/mcp.json`)
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."]
    },
    "mcp-node": {
      "command": "node",
      "args": ["server-mcp.mjs"]
    }
  }
}
```