# Exercice 2 & TP 2 — Corrigé Complet (Environnement Java)

---

## Partie 1 : Corrigé de l'Exercice 2

### 1. Les 5 faiblesses critiques du prompt initial

1. **Absence de dialecte et de version SQL (Syntaxe) :** « Un script SQL » ne précise pas le SGBD. Le LLM peut générer des types propriétaires incompatibles avec PostgreSQL 16 (ex. `AUTO_INCREMENT` de MySQL au lieu de `GENERATED ALWAYS AS IDENTITY`, ou `NVARCHAR` de SQL Server), provoquant un échec immédiat lors de l'exécution par Flyway ou Liquibase.
2. **Non-idempotence lors du rejeu en CI/CD :** Sans clauses `IF NOT EXISTS` ni vérification préalable des contraintes, toute relance du pipeline de déploiement ou reprise sur erreur échoue avec une erreur `relation "utilisateurs" already exists`.
3. **Intégrité référentielle non spécifiée :** « Lier les commandes » laisse le choix de la politique de suppression au modèle. Il peut générer `ON DELETE CASCADE` (suppression silencieuse de l'historique comptable des commandes lors de la suppression d'un client) ou omettre la clé étrangère.
4. **Sécurité et contraintes métier floues :** « Fais attention à la sécurité » est trop vague. Le modèle peut nommer la colonne `password` (risque de stockage en clair), autoriser des chaînes arbitraires pour le statut au lieu d'une contrainte `CHECK`, ou oublier l'indexation de la clé étrangère (causant des verrous et lenteurs lors des `DELETE`/`JOIN` sur PostgreSQL).
5. **Format de sortie incompatible avec l'automatisation :** Sans consigne stricte sur le format, le LLM encadre le SQL dans des balises Markdown (` ```sql ... ``` `) et ajoute des formules de politesse, faisant planter les parseurs SQL des outils de migration (Flyway, Liquibase, `psql`).

---

### 2. Réécriture du prompt selon le cadre ROC-DCI (PostgreSQL 16 / Java)

```text
[RÔLE]
Tu es un Architecte Bases de Données Senior spécialisé sur PostgreSQL 16 et les migrations critiques en production pour des applications Java (Flyway / Liquibase / JDBC).

[OBJECTIF]
Générer un script de migration SQL DDL complet permettant de créer la table `users` et de la relier à la table existante `orders`.

[CONTEXTE]
Ce script sera exécuté automatiquement par Flyway lors d'un déploiement CI/CD sur une base de données PostgreSQL 16 en production contenant déjà des données dans la table `orders`.

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
- Ne pas inclure de `BEGIN;` / `COMMIT;` explicites si Flyway gère déjà la transaction, ou s'assurer que le script reste valide en exécution transactionnelle PostgreSQL 16.

[FORMAT DE SORTIE]
Retourne UNIQUEMENT le code SQL brut valide. N'inclus aucune balise Markdown (pas de ```sql), aucun commentaire introductif ni explication finale. Le texte produit doit être directement enregistré dans un fichier `V2__create_users_and_link_orders.sql` sans nettoyage manuel.
```

---

## Partie 2 : Corrigé du TP 2 (Socle Java)

### 1. Fichier d'instructions IDE : `.continuerules`

```markdown
# Standards de développement Java 21+

- Exploiter les fonctionnalités modernes de Java 21 : `record` pour les DTO immutables, `sealed interface`, Pattern Matching et `var` uniquement lorsque le type est évident à droite.
- Privilégier l'immutabilité (`final` sur les classes, champs et paramètres pertinents) et éviter de retourner `null` (utiliser `Optional<T>` pour les retours potentiellement absents).
- Utiliser systématiquement `PreparedStatement` pour toute requête JDBC ou des paramètres nommés en JPQL ; interdiction absolue de concaténer des chaînes dans une requête SQL.
- Ne jamais coder de secrets en dur ; utiliser des variables d'environnement ou la configuration externalisée.
- Rédiger une Javadoc claire (`@param`, `@return`, `@throws`) décrivant les contrats métier et les invariants sans paraphraser le code.
```

---

### 2. Les 4 templates dans `.prompts/`

#### `.prompts/01-refactoring.md`
```markdown
[RÔLE]
Tu es un Staff Engineer Java 21+ expert en Clean Code et architecture logicielle.

[OBJECTIF]
Refactoriser la classe ou méthode Java sélectionnée pour réduire sa complexité cyclomatique et améliorer sa lisibilité, tout en préservant strictement son comportement fonctionnel existant.

[CONTRAINTES]
- Conserver exactement la même signature publique et les mêmes effets de bord (rétrocompatibilité 100 % garantie).
- Remplacer les retours `null` internes par `Optional` et introduire des `record` privés pour structurer les données intermédiaires.
- Appliquer les guard clauses (retours anticipés) pour éliminer les niveaux d'imbrication `if/else` excessifs.
- Extraire les sous-responsabilités dans des méthodes privées courtes et nommées selon le domaine métier.
- Utiliser l'API Streams Java 21 uniquement lorsqu'elle améliore réellement la lisibilité par rapport à une boucle `for`.

[FORMAT DE SORTIE]
Fournis uniquement le code Java refactorisé prêt à remplacer la sélection, suivi d'une liste à puces concise (3 lignes maximum) résumant les améliorations structurelles apportées.
```

#### `.prompts/02-unit-tests.md`
```markdown
[RÔLE]
Tu es un Ingénieur QA / Développeur Java spécialisé dans les tests automatisés avec JUnit 5 et AssertJ.

[OBJECTIF]
Générer une suite de tests unitaires exhaustive et déterministe pour la classe Java fournie.

[CONTRAINTES]
- Utiliser **JUnit 5** (`org.junit.jupiter.api.*`), `@ParameterizedTest` (`@ValueSource`, `@NullAndEmptySource`, `@CsvSource`) et **AssertJ** (`assertThat(...)`, `assertThatThrownBy(...)`).
- Respecter le motif AAA (Arrange, Act, Assert) et nommer les méthodes de test de manière explicite (`shouldThrowExceptionWhenSiretIsNull`).
- Couvrir obligatoirement 3 catégories de scénarios :
  1. Cas nominaux (entrées valides standards).
  2. Cas limites et entrées dégénérées (`null`, chaînes vides `""`, espaces, collections vides, valeurs zéro/négatives).
  3. Cas d'erreur (vérification précise du type d'exception et de son message).
- Isoler les dépendances externes (JDBC, HTTP, système de fichiers) à l'aide de **Mockito** (`@Mock`, `@InjectMocks`).

[FORMAT DE SORTIE]
Retourne uniquement la classe de test Java complète (`*Test.java`), prête à être exécutée via `mvn test` ou `./gradlew test`.
```

#### `.prompts/03-code-review.md`
```markdown
[RÔLE]
Tu es un Auditeur Sécurité Applicative (AppSec) et Expert Performance Java.

[OBJECTIF]
Réaliser une revue de code critique du snippet Java fourni en ciblant exclusivement les failles de sécurité, les goulots d'étranglement et la robustesse.

[GRILLE D'AUDIT OBLIGATOIRE]
1. **Sécurité (OWASP Top 10) :** Injections SQL (concaténation dans `Statement`), XXE, désérialisation non sécurisée (`ObjectInputStream`), exposition de secrets/tokens dans les logs ou exceptions.
2. **Performance :** Requêtes SQL dans des boucles (problème $N+1$), fuites de ressources (`Connection`, `PreparedStatement`, `ResultSet` non fermés via `try-with-resources`), allocations inutiles en boucle.
3. **Gestion des erreurs :** Blocs `catch (Exception e)` vides ou `e.printStackTrace()`, déréférencement de `null` non protégé (`NullPointerException`).

[FORMAT DE SORTIE]
Présente les résultats sous forme de tableau Markdown avec les colonnes : `Sévérité (CRITIQUE / MAJEURE / MINEURE)` | `Ligne / Bloc` | `Vulnérabilité ou Défaut` | `Correction Java recommandée`. Si aucun défaut critique n'est détecté, indique-le explicitement.
```

#### `.prompts/04-tech-doc.md`
```markdown
[RÔLE]
Tu es un Lead Developer Java chargé de la documentation technique d'équipe.

[OBJECTIF]
Produire la documentation technique complète (Javadoc + extrait README) de la classe ou méthode Java sélectionnée.

[CONTRAINTES]
- Rédiger une Javadoc conforme aux standards JDK (`@param`, `@return`, `@throws` pour chaque exception vérifiée ou non vérifiée relevante).
- Interdiction formelle de paraphraser trivialement le code (ne pas écrire « Retourne l'identifiant » sur `getId()`). Expliquer le **pourquoi** métier, les préconditions et la thread-safety éventuelle.
- Rédiger un court extrait prêt à être intégré dans `README.md` présentant le rôle du composant et un exemple d'utilisation concret en Java 21.

[FORMAT DE SORTIE]
1. Le code Java enrichi uniquement de sa Javadoc (sans modifier la logique).
2. Un bloc Markdown séparé contenant la section prête pour le `README.md`.
```

---

### 3. Exemple de code imparfait de test et relevé `STATS.md`

**Classe imparfaite utilisée pour le banc d'essai (`src/main/java/fr/formation/ia/LegacyOrderProcessor.java`) :**
```java
package fr.formation.ia;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

public class LegacyOrderProcessor {
    public boolean process(Connection conn, long userId, List<Map<String, Integer>> items) throws Exception {
        double total = 0;
        Statement stmt = conn.createStatement();
        for (Map<String, Integer> item : items) {
            ResultSet rs = stmt.executeQuery("SELECT price FROM products WHERE id = " + item.get("id"));
            if (rs.next()) {
                total += rs.getDouble("price") * item.get("qty");
            }
        }
        if (total > 0) {
            stmt.executeUpdate("INSERT INTO orders (user_id, total) VALUES (" + userId + ", " + total + ")");
            return true;
        }
        return false;
    }
}
```
*(Défauts détectés par `03-code-review.md` : injections SQL critiques via `Statement` ; fuite de ressources `Statement` et `ResultSet` sans `try-with-resources` ; requêtes SQL $N+1$ dans la boucle `for` ; utilisation de `double` au lieu de `BigDecimal` pour des montants financiers).*

**Mise à jour de `STATS.md` :**
```markdown
## TP 2 : Comparatif Prompting Naïf vs Socle `.prompts/` (Java 21)

| Tâche effectuée | Prompting Naïf (Allers-retours) | Prompting Naïf (Temps) | Socle `.prompts/` (Allers-retours) | Socle `.prompts/` (Temps) | Gain observé |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Refactoring (`01-refactoring.md`)** | 4 itérations | 15 min | 1 itération | 2 min 30 | **-83 % de temps**, `BigDecimal` et `try-with-resources` immédiats |
| **Tests JUnit 5 (`02-unit-tests.md`)** | 4 itérations | 14 min | 1 itération | 2 min 30 | **-82 % de temps**, `@ParameterizedTest` + Mockito générés du 1er coup |
| **Audit Sécu/Perf (`03-code-review.md`)** | 3 itérations | 11 min | 1 itération | 1 min 30 | **-86 % de temps**, détection immédiate SQLi + fuites JDBC + $N+1$ |
| **Documentation (`04-tech-doc.md`)** | 2 itérations | 8 min | 1 itération | 1 min 30 | **-81 % de temps**, zéro paraphrase, Javadoc complète |
```