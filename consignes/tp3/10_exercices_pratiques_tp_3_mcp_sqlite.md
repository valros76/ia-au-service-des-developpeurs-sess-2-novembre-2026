# 10 Exercices Pratiques — Intégration MCP Marketplace & `mcp-db-explorer` (SQLite)

Ce recueil propose **10 exercices progressifs** construits sur la base finale du **TP 3** :
* **Partie A :** configuration bornée de `@modelcontextprotocol/server-filesystem` et exploration/lecture multi-fichiers dans l'IDE (VS Code / Continue).
* **Partie B :** initialisation de `entreprise.db` (`clients`, `produits`, `commandes` avec **21 enregistrements au total** et anomalies volontaires : stocks négatifs et commandes `BLOQUEE`), exposition de la ressource `db://schema` et du tool `query_database` sécurisé, validation avec **MCP Inspector** et exploitation en langage naturel dans l'IDE.

> **Convention BDD partagée pour tous les exercices :**
> * **Base :** `entreprise.db` (ouverte en lecture seule par le serveur MCP).
> * **Tables :**
>   * `clients` (`id`, `nom`, `email`, `segment`) — **6 clients**
>   * `produits` (`id`, `nom`, `prix`, `stock`) — **6 produits**, dont **2 avec stock négatif** (`stock < 0`)
>   * `commandes` (`id`, `client_id`, `produit_id`, `quantite`, `statut`, `date_commande`) — **9 commandes**, dont **2 au statut `'BLOQUEE'`**
> * **Total :** `6 + 6 + 9 = 21` enregistrements réalistes.

---

## Exercice 1 : Confinement strict du périmètre `filesystem` dans l'IDE

### Énoncé
Déclarez `@modelcontextprotocol/server-filesystem` dans la configuration de votre IDE (Continue ou VS Code) en limitant strictement le périmètre au dossier de votre projet courant. Vérifiez ensuite que l'outil refuse l'accès à un fichier situé hors du répertoire autorisé.

### Critères de validation
* Le périmètre passé en argument est borné au projet courant (sans accès au système de fichiers parent ou système).
* Une tentative de lecture d'un chemin externe (ex. hors du dossier du projet) est rejetée avec une erreur d'accès refusé.

### Piste de correction
* Dans `.continue/mcpServers/tp3-mcp.yaml`, passez `"."` (ou le chemin absolu du dossier projet) comme dernier argument de `@modelcontextprotocol/server-filesystem`.
* Dans `.vscode/mcp.json`, utilisez `"${workspaceFolder}"` comme répertoire autorisé.

---

## Exercice 2 : Exploration d'arborescence et lecture multi-fichiers sans copier-coller

### Énoncé
En mode **Agent** dans votre IDE, rédigez une demande obligeant l'assistant à explorer l'arborescence du projet et à lire plusieurs fichiers via les outils `filesystem` en un minimum d'allers-retours, sans aucun copier-coller manuel dans le chat.

### Critères de validation
* L'assistant utilise un outil de listage/exploration de dossier puis un outil de lecture groupée/multi-fichiers.
* Attention à l'orthographe exacte des dossiers sur le disque (par exemple `ressources` vs `resources`) pour éviter les erreurs `ENOENT`.

### Piste de correction
* Commencez par demander de lister la racine `.` si le chemin d'un sous-dossier est incertain, puis enchaînez sur la lecture simultanée des fichiers cibles pour en produire une synthèse structurée.

---

## Exercice 3 : Génération d'une base `entreprise.db` réaliste (21 lignes) avec anomalies métier

### Énoncé
Écrivez et exécutez un script `init-db.js` (avec `node:sqlite` ou `better-sqlite3`) générant `entreprise.db` avec les tables `clients` (6 lignes), `produits` (6 lignes) et `commandes` (9 lignes) — soit **21 enregistrements au total** — incluant des anomalies volontaires : au moins 2 produits à stock négatif (`stock < 0`) et au moins 2 commandes au statut `'BLOQUEE'`.

### Critères de validation
* Les 3 tables (`clients`, `produits`, `commandes`) existent avec leurs clés étrangères (`client_id -> clients(id)`, `produit_id -> produits(id)`).
* Le jeu de données contient **21 enregistrements** au total, dont **2 produits avec `stock < 0`** et **2 commandes avec `statut = 'BLOQUEE'`** référençant des clients et produits existants.
* Relancer `init-db.js` réinitialise proprement les tables sans doublons ni violation de contrainte.

### Piste de correction
* Utilisez `DROP TABLE IF EXISTS` dans l'ordre inverse des clés étrangères (`commandes`, puis `produits`, puis `clients`), puis `CREATE TABLE` et `INSERT INTO` multi-lignes.

---

## Exercice 4 : Exposition dynamique du schéma via la ressource `db://schema`

### Énoncé
Implémentez la ressource MCP `db://schema` interrogeant `sqlite_master` pour retourner dynamiquement le DDL (`CREATE TABLE`) de toutes les tables métier (`clients`, `produits`, `commandes`), en excluant les tables internes `sqlite_*`.

### Critères de validation
* `resources/list` expose `db://schema` (lorsque `entreprise.db` est détectée).
* `resources/read` avec `uri: "db://schema"` renvoie les instructions `CREATE TABLE` de `clients`, `produits` et `commandes`.

### Piste de correction
* Requêtez `SELECT sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'` et concaténez chaque instruction avec `;`.

---

## Exercice 5 : Validation des entrées du Tool `query_database`

### Énoncé
Configurez le Tool `query_database` afin qu'il exige un paramètre `sql` sous forme de chaîne d'au moins 10 caractères (`z.string().min(10)` ou équivalent). Testez les cas invalides : chaîne vide, requête trop courte (ex. `SELECT 1`), type non chaîne ou paramètre manquant.

### Critères de validation
* Toute requête de moins de 10 caractères (après `trim()`) est rejetée explicitement.
* Le schéma d'entrée du Tool documente clairement le paramètre `sql` et sa contrainte de longueur minimale (`minLength: 10` / `z.string().min(10)`).

### Piste de correction
* Vérifiez `typeof rawSql === 'string' && rawSql.trim().length >= 10` (ou la validation Zod) avant toute analyse SQL ou exécution.

---

## Exercice 6 : Blindage anti-injection et interdiction des écritures SQL

### Énoncé
Renforcez `query_database` pour rejeter immédiatement :
1. Toute requête ne commençant pas par `SELECT` (insensible à la casse, y compris avec espaces ou `WITH` non autorisé).
2. Les mots-clés interdits (`DROP`, `DELETE`, `UPDATE`, `INSERT`, `ALTER`, `PRAGMA`, `ATTACH`, `DETACH`, `REPLACE`, `VACUUM`).
3. Les requêtes multiples séparées par `;` (y compris un `;` suivi d'une seconde instruction).

### Critères de validation
* `DROP TABLE clients;` est rejeté avant exécution.
* `SELECT * FROM produits; DELETE FROM commandes;` est rejeté.
* `SELECT * FROM produits` (ou `SELECT * FROM produits;`) est accepté.

### Piste de correction
* Retirez les points-virgules finaux (`replace(/;+$/, '')`), vérifiez qu'il ne reste aucun `;`, testez `/^SELECT\b/i`, puis appliquez une expression régulière avec bornes de mots `\b...\b` et drapeau `i`.

---

## Exercice 7 : Protection de la fenêtre de contexte avec `LIMIT 50` automatique

### Énoncé
Modifiez l'exécution de `query_database` afin qu'elle ajoute automatiquement `LIMIT 50` si la requête `SELECT` ne contient pas déjà de clause `LIMIT`, tout en respectant une limite explicite fournie par l'appelant.

### Critères de validation
* `SELECT * FROM commandes` devient `SELECT * FROM commandes LIMIT 50` et retourne les 9 commandes de la base.
* `SELECT * FROM produits LIMIT 3` conserve `LIMIT 3` et retourne 3 lignes.
* Les lignes retournées sont sérialisées en JSON propre.

### Piste de correction
* Testez `/\bLIMIT\s+\d+/i.test(cleaned)` : si faux, ajoutez ` LIMIT 50` avant de préparer et exécuter la requête en lecture seule.

---

## Exercice 8 : Ouverture en lecture seule et résilience des chemins (`cwd` vs dossier du script)

### Énoncé
Assurez-vous que la connexion SQLite s'ouvre en mode lecture seule (`readOnly: true` / `mode=ro` / `SQLITE_OPEN_READONLY`) et que le serveur retrouve `entreprise.db` même lorsque l'IDE ou le terminal est lancé depuis un autre répertoire que celui du script.

### Critères de validation
* Toute tentative d'écriture au niveau du moteur SQLite échoue également en lecture seule.
* Le serveur trouve `entreprise.db` qu'il soit lancé depuis le dossier du script ou depuis la racine du dépôt.

### Piste de correction
* Cherchez `entreprise.db` dans le répertoire du script en priorité, puis dans le répertoire courant et ses parents, et ouvrez la base en lecture seule.

---

## Exercice 9 : Isolation et validation complète avec MCP Inspector

### Énoncé
Lancez votre serveur en isolation avec MCP Inspector (`npx @modelcontextprotocol/inspector ...`) avant tout branchement dans l'IDE et exécutez le scénario de recette complet :
1. Lister les ressources et lire `db://schema`.
2. Appeler `query_database` avec une requête `SELECT` valide sur `produits` (6 lignes attendues) et `commandes` (9 lignes attendues).
3. Appeler `query_database` avec `DROP TABLE clients;` et vérifier le rejet immédiat.

### Critères de validation
* Aucune erreur de protocole JSON-RPC sur `stdout`.
* Les résultats `SELECT` reviennent en JSON avec les bonnes données et l'injection `DROP` est bloquée proprement.

### Piste de correction
* Vérifiez que rien d'autre que les trames JSON-RPC n'est écrit sur `stdout` (les logs de diagnostic doivent aller vers `stderr`).

---

## Exercice 10 : Audit métier en langage naturel dans l'IDE sans mutation de données

### Énoncé
Une fois le serveur branché dans votre IDE, demandez en langage naturel :
> *"Analyse le schéma de la base de données, trouve les anomalies de stock ou de commandes bloquées, et propose-moi les requêtes SQL de correction sans les exécuter."*

### Critères de validation
* L'assistant lit ou utilise `db://schema` et interroge `entreprise.db` via `query_database`.
* Il identifie précisément les **2 produits à stock négatif** et les **2 commandes `'BLOQUEE'`** (avec leurs clients et produits).
* Il fournit des requêtes `UPDATE` prêtes à l'emploi **sans** exécuter d'écriture sur la base.

### Piste de correction
* Vérifiez après la réponse que les 2 stocks négatifs et les 2 statuts `'BLOQUEE'` sont toujours présents dans `entreprise.db` (preuve que seules des requêtes `SELECT` ont été exécutées et que les `UPDATE` ont uniquement été proposés).