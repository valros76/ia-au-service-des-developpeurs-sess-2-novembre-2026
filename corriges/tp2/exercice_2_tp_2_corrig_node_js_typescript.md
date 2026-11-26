# Exercice 2 & TP 2 — Corrigé Complet (Environnement Node.js / TypeScript)

---

## Partie 1 : Corrigé de l'Exercice 2

### 1. Les 5 faiblesses critiques du prompt initial

1. **Absence de dialecte et de version SQL (Syntaxe) :** « Un script SQL » ne précise pas le SGBD. Le LLM peut générer des types propriétaires incompatibles avec PostgreSQL 16 (ex. `AUTO_INCREMENT` de MySQL au lieu de `GENERATED ALWAYS AS IDENTITY`, ou `NVARCHAR` de SQL Server), provoquant un échec immédiat lors de l'exécution par `node-pg-migrate`, Prisma Migrate ou Knex.
2. **Non-idempotence lors du rejeu en CI/CD :** Sans clauses `IF NOT EXISTS` ni vérification préalable des contraintes, toute relance du pipeline de déploiement ou reprise sur erreur échoue avec une erreur `relation "utilisateurs" already exists`.
3. **Intégrité référentielle non spécifiée :** « Lier les commandes » laisse le choix de la politique de suppression au modèle. Il peut générer `ON DELETE CASCADE` (suppression silencieuse de l'historique comptable des commandes lors de la suppression d'un client) ou omettre la clé étrangère.
4. **Sécurité et contraintes métier floues :** « Fais attention à la sécurité » est trop vague. Le modèle peut nommer la colonne `password` (risque de stockage en clair), autoriser des chaînes arbitraires pour le statut au lieu d'une contrainte `CHECK`, ou oublier l'indexation de la clé étrangère (causant des verrous et lenteurs lors des `DELETE`/`JOIN` sur PostgreSQL).
5. **Format de sortie incompatible avec l'automatisation :** Sans consigne stricte sur le format, le LLM encadre le SQL dans des balises Markdown (` ```sql ... ``` `) et ajoute des formules de politesse, faisant planter les runners de migration SQL automatisés (`psql`, `node-pg-migrate`).

---

### 2. Réécriture du prompt selon le cadre ROC-DCI (PostgreSQL 16 / Node.js)

```text
[RÔLE]
Tu es un Architecte Bases de Données Senior spécialisé sur PostgreSQL 16 et les migrations critiques en production pour des applications Node.js / TypeScript (node-pg / Drizzle / Prisma raw SQL).

[OBJECTIF]
Générer un script de migration SQL DDL complet permettant de créer la table `users` et de la relier à la table existante `orders`.

[CONTEXTE]
Ce script sera exécuté automatiquement par un pipeline CI/CD sur une base de données PostgreSQL 16 en production contenant déjà des données dans la table `orders`.

[DONNÉES ET CONTRAINTES]
1. Table `users` :
   - `id` : BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY.
   - `email` : VARCHAR(255) NOT NULL UNIQUE, avec contrainte CHECK vérifiant la présence d'un `@`.
   - `password_hash` : VARCHAR(255) NOT NULL (destiné exclusivement aux empreintes Argon2id/Bcrypt, jamais de mot de passe en clair).
   - `status` : VARCHAR(20) NOT NULL DEFAULT 'PENDING', verrouillé via une contrainte CHECK stricte n'autorisant que ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETED').
   - `created_at` et `updated_at` : TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP.
2. Liaison avec `orders` :
   - Ajouter la colonne `user_id` (BIGINT) sur `orders` si elle n'existe pas.
   - Créer une clé étrangère `fk_orders_user_id` référençant `users(id)` avec `ON DELETE RESTRICT` et `ON UPDATE CASCADE` afin d'interdire la suppression d'un utilisateur ayant passé commande.
3. Performance :
   - Créer un index B-Tree explicite sur `orders(user_id)` et sur `users(status)`.

[INSTRUCTIONS]
- Garantir une idempotence totale : utiliser `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, et encapsuler l'ajout des contraintes dans des blocs `DO $$ BEGIN ... EXCEPTION WHEN duplicate_object THEN NULL; END $$;`.
- Encapsuler l'ensemble de la migration dans une transaction explicite (`BEGIN;` ... `COMMIT;`).

[FORMAT DE SORTIE]
Retourne UNIQUEMENT le code SQL brut valide. N'inclus aucune balise Markdown (pas de ```sql), aucun commentaire introductif ni explication finale. Le texte produit doit être directement exécutable par `psql` ou `pool.query()` sans nettoyage manuel.
```

---

## Partie 2 : Corrigé du TP 2 (Socle Node.js / TypeScript)

### 1. Fichier d'instructions IDE : `.continuerules`

```markdown
# Standards de développement Node.js 22+ & TypeScript

- Utiliser exclusivement la syntaxe ES Modules (`import` / `export` avec extensions explicites `.js` en TypeScript NodeNext).
- Imposer le typage strict TypeScript (`strict: true`, interdiction d'utiliser `any` ; préférer `unknown` avec des type guards).
- Privilégier l'immutabilité (`const`, `readonly`, `ReadonlyArray<T>`, `as const`) plutôt que la mutation d'objets en place.
- Utiliser exclusivement des requêtes SQL paramétrées (`$1`, `$2` avec `pg` ou requêtes préparées) ; ne jamais interpoler de variables `${...}` dans une chaîne SQL.
- Ne jamais coder de secrets en dur ; lire la configuration via `process.env` validé au démarrage.
- Rédiger des commentaires JSDoc/TSDoc uniquement pour documenter l'intention métier, les préconditions et les exceptions levées (`@throws`).
```

---

### 2. Les 4 templates dans `.prompts/`

#### `.prompts/01-refactoring.md`
```markdown
[RÔLE]
Tu es un Staff Engineer Node.js / TypeScript expert en Clean Code et architecture logicielle.

[OBJECTIF]
Refactoriser le code Node.js / TypeScript sélectionné pour réduire sa complexité cyclomatique et améliorer sa lisibilité, tout en préservant strictement son comportement fonctionnel existant.

[CONTRAINTES]
- Conserver exactement la même signature publique et les mêmes effets de bord (rétrocompatibilité 100 % garantie).
- Imposer un typage strict TypeScript sans aucun `any` implicite ou explicite.
- Appliquer les guard clauses (retours anticipés) pour éliminer les niveaux d'imbrication `if/else` excessifs.
- Extraire les sous-responsabilités dans des fonctions pures privées courtes et nommées selon le domaine métier.
- Remplacer les objets dynamiques non typés par des interfaces/types `readonly` explicites en interne.

[FORMAT DE SORTIE]
Fournis uniquement le code TypeScript/JavaScript refactorisé prêt à remplacer la sélection, suivi d'une liste à puces concise (3 lignes maximum) résumant les améliorations structurelles apportées.
```

#### `.prompts/02-unit-tests.md`
```markdown
[RÔLE]
Tu es un Ingénieur QA / Développeur Node.js spécialisé dans les tests automatisés avec le runner natif `node:test` (ou Vitest).

[OBJECTIF]
Générer une suite de tests unitaires exhaustive et déterministe pour le module Node.js / TypeScript fourni.

[CONTRAINTES]
- Utiliser le module natif **`node:test`** (`describe`, `it`, `mock`) et **`node:assert/strict`** (zéro dépendance externe requise).
- Respecter le motif AAA (Arrange, Act, Assert) dans chaque cas de test.
- Couvrir obligatoirement 3 catégories de scénarios :
  1. Cas nominaux (entrées valides standards).
  2. Cas limites et entrées dégénérées (`null`, `undefined`, chaînes vides `""`, espaces, tableaux vides, valeurs zéro/négatives).
  3. Cas d'erreur (vérification précise des exceptions synchrones avec `assert.throws()` et asynchrones avec `assert.rejects()`).
- Isoler les dépendances externes (I/O, base de données, `fetch`) à l'aide de `mock.fn()` ou de l'injection de dépendances.

[FORMAT DE SORTIE]
Retourne uniquement le fichier de test complet (`*.test.js` ou `*.test.ts`), prêt à être exécuté via `node --test`.
```

#### `.prompts/03-code-review.md`
```markdown
[RÔLE]
Tu es un Auditeur Sécurité Applicative (AppSec) et Expert Performance Node.js.

[OBJECTIF]
Réaliser une revue de code critique du snippet Node.js / TypeScript fourni en ciblant exclusivement les failles de sécurité, les goulots d'étranglement de l'Event Loop et la robustesse.

[GRILLE D'AUDIT OBLIGATOIRE]
1. **Sécurité (OWASP Top 10) :** Injections SQL (template literals `${...}` dans `query()`), injections de commandes (`child_process.exec`), Prototype Pollution, exposition de secrets/tokens dans les logs ou le code.
2. **Performance & Event Loop :** Opérations synchrones bloquantes (`readFileSync`, boucles CPU intensives), requêtes SQL séquentielles dans une boucle `for...of` (problème $N+1$ au lieu d'un `Promise.all` ou d'une requête groupée `WHERE id = ANY($1)`), fuites mémoire.
3. **Gestion des erreurs :** Promesses non attendues (*floating promises*), blocs `catch {}` vides ou silencieux, absence de validation des entrées externes.

[FORMAT DE SORTIE]
Présente les résultats sous forme de tableau Markdown avec les colonnes : `Sévérité (CRITIQUE / MAJEURE / MINEURE)` | `Ligne / Bloc` | `Vulnérabilité ou Défaut` | `Correction Node.js/TS recommandée`. Si aucun défaut critique n'est détecté, indique-le explicitement.
```

#### `.prompts/04-tech-doc.md`
```markdown
[RÔLE]
Tu es un Lead Developer Node.js / TypeScript chargé de la documentation technique d'équipe.

[OBJECTIF]
Produire la documentation technique complète (TSDoc/JSDoc + extrait README) du module ou de la fonction sélectionnée.

[CONTRAINTES]
- Rédiger les commentaires au format **TSDoc / JSDoc** (`@param`, `@returns`, `@throws`, `@example`).
- Interdiction formelle de paraphraser trivialement le code (ne pas écrire « Retourne l'ID » sur `getId()`). Expliquer le **pourquoi** métier, les préconditions, les effets de bord asynchrones et les invariants.
- Rédiger un court extrait prêt à être intégré dans `README.md` présentant le rôle du module et un exemple d'utilisation concret en ESM.

[FORMAT DE SORTIE]
1. Le code TypeScript/JavaScript enrichi uniquement de sa TSDoc/JSDoc (sans modifier la logique).
2. Un bloc Markdown séparé contenant la section prête pour le `README.md`.
```

---

### 3. Exemple de code imparfait de test et relevé `STATS.md`

**Script imparfait utilisé pour le banc d'essai (`src/legacyOrderProcessor.js`) :**
```javascript
export async function processOrder(db, userId, items) {
  let total = 0;
  for (const item of items) {
    const res = await db.query(`SELECT price FROM products WHERE id = ${item.id}`);
    total += res.rows[0].price * item.qty;
  }
  if (total > 0) {
    await db.query(`INSERT INTO orders (user_id, total) VALUES (${userId}, ${total})`);
    return true;
  }
  return false;
}
```
*(Défauts détectés par `03-code-review.md` : injections SQL critiques par interpolation `${...}` ; requêtes SQL $N+1$ séquentielles dans la boucle `for...of` ; risque de `TypeError: Cannot read properties of undefined (reading 'price')` si `res.rows[0]` est absent ; absence de transaction SQL).*

**Mise à jour de `STATS.md` :**
```markdown
## TP 2 : Comparatif Prompting Naïf vs Socle `.prompts/` (Node.js / TypeScript)

| Tâche effectuée | Prompting Naïf (Allers-retours) | Prompting Naïf (Temps) | Socle `.prompts/` (Allers-retours) | Socle `.prompts/` (Temps) | Gain observé |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Refactoring (`01-refactoring.md`)** | 4 itérations | 13 min | 1 itération | 2 min | **-85 % de temps**, typage strict et requête groupée immédiats |
| **Tests `node:test` (`02-unit-tests.md`)** | 3 itérations | 11 min | 1 itération | 2 min | **-82 % de temps**, zéro dépendance externe, mocks natifs |
| **Audit Sécu/Perf (`03-code-review.md`)** | 3 itérations | 10 min | 1 itération | 1 min 30 | **-85 % de temps**, détection immédiate SQLi + $N+1$ asynchrone |
| **Documentation (`04-tech-doc.md`)** | 2 itérations | 7 min | 1 itération | 1 min 30 | **-79 % de temps**, zéro paraphrase, TSDoc valide |
```