# Exercice 2 & TP 2 — Corrigé Complet (Environnement PHP)

---

## Partie 1 : Corrigé de l'Exercice 2

### 1. Les 5 faiblesses critiques du prompt initial

1. **Absence de dialecte et de version SQL (Syntaxe) :** « Un script SQL » ne précise pas le SGBD. Le LLM peut générer des types propriétaires incompatibles avec PostgreSQL 16 (ex. `AUTO_INCREMENT` de MySQL au lieu de `GENERATED ALWAYS AS IDENTITY`, ou `NVARCHAR` de SQL Server), provoquant un échec immédiat de la migration.
2. **Non-idempotence lors du rejeu en CI/CD :** Sans clauses `IF NOT EXISTS` ni vérification préalable des contraintes, toute relance du pipeline de déploiement ou reprise sur erreur échoue avec une erreur `relation "utilisateurs" already exists`.
3. **Intégrité référentielle non spécifiée :** « Lier les commandes » laisse le choix de la politique de suppression au modèle. Il peut générer `ON DELETE CASCADE` (suppression silencieuse de l'historique comptable des commandes lors de la suppression d'un client) ou omettre la clé étrangère.
4. **Sécurité et contraintes métier floues :** « Fais attention à la sécurité » est trop vague. Le modèle peut nommer la colonne `password` (risque de stockage en clair), autoriser des chaînes arbitraires pour le statut au lieu d'une contrainte `CHECK`, ou oublier l'indexation de la clé étrangère (causant des verrous et lenteurs lors des `DELETE`/`JOIN` sur PostgreSQL).
5. **Format de sortie incompatible avec l'automatisation :** Sans consigne stricte sur le format, le LLM encadre le SQL dans des balises Markdown (` ```sql ... ``` `) et ajoute des formules de politesse, faisant planter les exécuteurs automatisés (Doctrine Migrations, Phinx, `psql`).

---

### 2. Réécriture du prompt selon le cadre ROC-DCI (PostgreSQL 16 / PHP)

```text
[RÔLE]
Tu es un Architecte Bases de Données Senior spécialisé sur PostgreSQL 16 et les migrations critiques en production pour des applications PHP (PDO / Doctrine / Phinx).

[OBJECTIF]
Générer un script de migration SQL DDL complet permettant de créer la table `users` et de la relier à la table existante `orders`.

[CONTEXTE]
Ce script sera exécuté automatiquement par un outil de migration CI/CD sur une base de données PostgreSQL 16 en production contenant déjà des données dans la table `orders`.

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
Retourne UNIQUEMENT le code SQL brut valide. N'inclus aucune balise Markdown (pas de ```sql), aucun commentaire introductif ni explication finale. Le texte produit doit être directement exécutable par `psql` ou un runner de migration sans modification.
```

---

## Partie 2 : Corrigé du TP 2 (Socle PHP)

### 1. Fichier d'instructions IDE : `.continuerules`

```markdown
# Standards de développement PHP 8.3+

- Toujours commencer chaque fichier PHP par `<?php` suivi de `declare(strict_types=1);`.
- Respecter strictement les conventions PSR-12 et PER Coding Style.
- Typer systématiquement tous les paramètres, propriétés et valeurs de retour (éviter `mixed` sauf nécessité absolue).
- Privilégier l'immutabilité (`readonly` classes/properties), la promotion de propriétés dans le constructeur et les `enum` natifs plutôt que des constantes magiques.
- Utiliser des requêtes préparées PDO (`prepare` / `execute`) pour tout accès SQL ; ne jamais concaténer de variables dans une requête.
- Ne jamais inclure de secrets ou clés API en dur ; utiliser `$_ENV` ou des variables injectées.
- Fournir des blocs PHPDoc complets uniquement lorsque les types natifs ne suffisent pas (ex. `@return array<int, User>`, `@throws InvalidArgumentException`).
```

---

### 2. Les 4 templates dans `.prompts/`

#### `.prompts/01-refactoring.md`
```markdown
[RÔLE]
Tu es un Staff Engineer PHP 8.3+ expert en Clean Code et architecture logicielle.

[OBJECTIF]
Refactoriser le code PHP sélectionné pour réduire sa complexité cyclomatique et améliorer sa maintenabilité, tout en préservant strictement son comportement fonctionnel existant.

[CONTRAINTES]
- Conserver exactement la même signature publique et les mêmes effets de bord (rétrocompatibilité 100 % garantie).
- Imposer `declare(strict_types=1);` et un typage strict sur toutes les méthodes privées extraites.
- Appliquer les principes Early Return (guard clauses) pour éliminer les niveaux d'imbrication `if/else` excessifs.
- Extraire les sous-responsabilités dans des méthodes privées courtes et nommées selon le domaine métier.
- Remplacer les tableaux associatifs non typés par des Value Objects / DTO `readonly` si pertinent en interne.

[FORMAT DE SORTIE]
Fournis uniquement le code PHP refactorisé prêt à remplacer la sélection, suivi d'une liste à puces concise (3 lignes maximum) résumant les améliorations structurelles apportées.
```

#### `.prompts/02-unit-tests.md`
```markdown
[RÔLE]
Tu es un Ingénieur QA / Développeur PHP spécialisé dans les tests automatisés avec PHPUnit 11+.

[OBJECTIF]
Générer une suite de tests unitaires exhaustive et déterministe pour le code PHP fourni.

[CONTRAINTES]
- Utiliser la syntaxe moderne PHPUnit 11+ (attributs PHP 8 `#[Test]`, `#[DataProvider('...')]`, `#[CoversClass(...)]`).
- Respecter le motif AAA (Arrange, Act, Assert) dans chaque méthode de test.
- Couvrir obligatoirement 3 catégories de scénarios :
  1. Cas nominaux (entrées valides standards).
  2. Cas limites et entrées dégénérées (`null`, chaînes vides `""`, espaces, tableaux vides, valeurs zéro/négatives).
  3. Cas d'erreur (vérification précise des exceptions attendues avec `expectException()` et `expectExceptionMessage()`).
- Mocker uniquement les dépendances externes (I/O, base de données, HTTP) via `$this->createMock()`.

[FORMAT DE SORTIE]
Retourne uniquement la classe de test PHP complète, prête à être enregistrée dans `tests/` et exécutée via `./vendor/bin/phpunit`.
```

#### `.prompts/03-code-review.md`
```markdown
[RÔLE]
Tu es un Auditeur Sécurité Applicative (AppSec) et Expert Performance PHP.

[OBJECTIF]
Réaliser une revue de code critique du snippet PHP fourni en ciblant exclusivement les failles de sécurité, les goulots d'étranglement et la robustesse.

[GRILLE D'AUDIT OBLIGATOIRE]
1. **Sécurité (OWASP Top 10) :** Injections SQL (concaténation PDO), XSS (sorties non échappées), désérialisation (`unserialize()`), exposition de secrets/tokens dans les logs ou le code.
2. **Performance :** Requêtes SQL dans des boucles (problème $N+1$), chargement complet de gros fichiers/tables en mémoire sans générateur (`yield`), complexité algorithmique $O(n^2)$ évitable.
3. **Gestion des erreurs :** Blocs `catch (\Throwable $e)` vides ou silencieux, fuites de ressources, absence de validation des entrées.

[FORMAT DE SORTIE]
Présente les résultats sous forme de tableau Markdown avec les colonnes : `Sévérité (CRITIQUE / MAJEURE / MINEURE)` | `Ligne / Bloc` | `Vulnérabilité ou Défaut` | `Correction PHP recommandée`. Si aucun défaut critique n'est détecté, indique-le explicitement.
```

#### `.prompts/04-tech-doc.md`
```markdown
[RÔLE]
Tu es un Lead Developer PHP chargé de la documentation technique d'équipe.

[OBJECTIF]
Produire la documentation technique complète (PHPDoc + extrait README) du composant PHP sélectionné.

[CONTRAINTES]
- Rédiger les blocs PHPDoc conformes PSR-5 / PHPStan (types génériques précis comme `array<string, mixed>`, `@throws` documentant chaque exception levée).
- Interdiction formelle de paraphraser trivialement le code (ne pas écrire « Retourne l'ID » sur `getId()`). Expliquer le **pourquoi** métier, les préconditions et les invariants.
- Rédiger un court extrait prêt à être intégré dans `README.md` présentant le rôle du composant et un exemple d'utilisation concret en PHP 8.3.

[FORMAT DE SORTIE]
1. Le code PHP enrichi uniquement de sa PHPDoc (sans modifier la logique).
2. Un bloc Markdown séparé contenant la section prête pour le `README.md`.
```

---

### 3. Exemple de code imparfait de test et relevé `STATS.md`

**Script imparfait utilisé pour le banc d'essai (`src/LegacyOrderProcessor.php`) :**
```php
<?php
class OrderProcessor {
    function process($db, $userId, $items) {
        $total = 0;
        foreach ($items as $item) {
            $res = $db->query("SELECT price FROM products WHERE id = " . $item['id']);
            $row = $res->fetch();
            $total += $row['price'] * $item['qty'];
        }
        if ($total > 0) {
            $db->query("INSERT INTO orders (user_id, total) VALUES ($userId, $total)");
            return true;
        }
        return false;
    }
}
```
*(Défauts détectés par `03-code-review.md` : injections SQL critiques sur `$item['id']`, `$userId`, `$total` ; requêtes SQL $N+1$ dans la boucle `foreach` ; absence de `strict_types=1` et de typage ; absence de transaction).*

**Mise à jour de `STATS.md` :**
```markdown
## TP 2 : Comparatif Prompting Naïf vs Socle `.prompts/` (PHP 8.3)

| Tâche effectuée | Prompting Naïf (Allers-retours) | Prompting Naïf (Temps) | Socle `.prompts/` (Allers-retours) | Socle `.prompts/` (Temps) | Gain observé |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Refactoring (`01-refactoring.md`)** | 4 itérations | 14 min | 1 itération | 2 min | **-85 % de temps**, typage `strict_types` immédiat |
| **Tests PHPUnit (`02-unit-tests.md`)** | 3 itérations | 12 min | 1 itération | 2 min 30 | **-79 % de temps**, `DataProvider` et mocks générés du 1er coup |
| **Audit Sécu/Perf (`03-code-review.md`)** | 3 itérations | 10 min | 1 itération | 1 min 30 | **-85 % de temps**, détection immédiate des injections SQL + $N+1$ |
| **Documentation (`04-tech-doc.md`)** | 2 itérations | 8 min | 1 itération | 1 min 30 | **-81 % de temps**, zéro paraphrase, PHPDoc PHPStan valide |
```