# Exercice 2 & TP 2 — Corrigé Complet (Environnement Python)

---

## Partie 1 : Corrigé de l'Exercice 2

### 1. Les 5 faiblesses critiques du prompt initial

1. **Absence de dialecte et de version SQL (Syntaxe) :** « Un script SQL » ne précise pas le SGBD. Le LLM peut générer des types propriétaires incompatibles avec PostgreSQL 16 (ex. `AUTO_INCREMENT` de MySQL au lieu de `GENERATED ALWAYS AS IDENTITY`, ou `NVARCHAR` de SQL Server), provoquant un échec immédiat lors de l'exécution via `psycopg` ou Alembic.
2. **Non-idempotence lors du rejeu en CI/CD :** Sans clauses `IF NOT EXISTS` ni vérification préalable des contraintes, toute relance du pipeline de déploiement ou reprise sur erreur échoue avec une erreur `relation "utilisateurs" already exists`.
3. **Intégrité référentielle non spécifiée :** « Lier les commandes » laisse le choix de la politique de suppression au modèle. Il peut générer `ON DELETE CASCADE` (suppression silencieuse de l'historique comptable des commandes lors de la suppression d'un client) ou omettre la clé étrangère.
4. **Sécurité et contraintes métier floues :** « Fais attention à la sécurité » est trop vague. Le modèle peut nommer la colonne `password` (risque de stockage en clair), autoriser des chaînes arbitraires pour le statut au lieu d'une contrainte `CHECK`, ou oublier l'indexation de la clé étrangère (causant des verrous et lenteurs lors des `DELETE`/`JOIN` sur PostgreSQL).
5. **Format de sortie incompatible avec l'automatisation :** Sans consigne stricte sur le format, le LLM encadre le SQL dans des balises Markdown (` ```sql ... ``` `) et ajoute des formules de politesse, faisant planter les exécuteurs automatisés (`alembic`, `psql`, scripts Python).

---

### 2. Réécriture du prompt selon le cadre ROC-DCI (PostgreSQL 16 / Python)

```text
[RÔLE]
Tu es un Architecte Bases de Données Senior spécialisé sur PostgreSQL 16 et les migrations critiques en production pour des applications Python (psycopg3 / SQLAlchemy / Alembic).

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
Retourne UNIQUEMENT le code SQL brut valide. N'inclus aucune balise Markdown (pas de ```sql), aucun commentaire introductif ni explication finale. Le texte produit doit être directement exécutable par `psql` ou `connection.execute()` sans nettoyage manuel.
```

---

## Partie 2 : Corrigé du TP 2 (Socle Python)

### 1. Fichier d'instructions IDE : `.continuerules`

```markdown
# Standards de développement Python 3.11+

- Respecter strictement la PEP 8 et les règles du linter Ruff (longueur de ligne max : 88 caractères).
- Ajouter des annotations de type (type hints) complètes sur tous les paramètres et valeurs de retour, compatibles avec Mypy en mode strict (syntaxe native Python 3.11+ : `list[str]`, `str | None`).
- Privilégier l'immutabilité avec `@dataclass(frozen=True)` et les `Enum` (`StrEnum`) plutôt que des dictionnaires non typés ou des chaînes magiques.
- Utiliser exclusivement des requêtes paramétrées (`%s` ou `:param`) pour tout accès SQL ; ne jamais utiliser de f-strings dans une requête SQL.
- Ne jamais coder de secrets en dur ; utiliser `os.environ` ou `pydantic-settings`.
- Rédiger des docstrings au format Google Style uniquement pour expliquer l'intention métier, les invariants et les exceptions levées (`Raises:`).
```

---

### 2. Les 4 templates dans `.prompts/`

#### `.prompts/01-refactoring.md`
```markdown
[RÔLE]
Tu es un Staff Engineer Python 3.11+ expert en Clean Code et architecture logicielle.

[OBJECTIF]
Refactoriser le code Python sélectionné pour réduire sa complexité cyclomatique et améliorer sa lisibilité, tout en préservant strictement son comportement fonctionnel existant.

[CONTRAINTES]
- Conserver exactement la même signature publique et les mêmes effets de bord (rétrocompatibilité 100 % garantie).
- Imposer un typage statique strict (compatible `mypy --strict`, syntaxe Python 3.11+ `X | None`).
- Appliquer les guard clauses (retours anticipés) pour éliminer les niveaux d'imbrication `if/else` excessifs.
- Découper les responsabilités dans des fonctions privées (`_nom_fonction`) courtes, testables et nommées selon le domaine métier.
- Remplacer les dictionnaires non structurés par des `@dataclass(frozen=True)` si pertinent en interne.

[FORMAT DE SORTIE]
Fournis uniquement le code Python refactorisé prêt à remplacer la sélection, suivi d'une liste à puces concise (3 lignes maximum) résumant les améliorations structurelles apportées.
```

#### `.prompts/02-unit-tests.md`
```markdown
[RÔLE]
Tu es un Ingénieur QA / Développeur Python spécialisé dans les tests automatisés avec Pytest.

[OBJECTIF]
Générer une suite de tests unitaires exhaustive et déterministe pour le code Python fourni.

[CONTRAINTES]
- Utiliser exclusivement **Pytest** avec `@pytest.mark.parametrize` pour factoriser les jeux de données et `pytest.raises(..., match=...)` pour les exceptions.
- Respecter le motif AAA (Arrange, Act, Assert) dans chaque fonction de test.
- Couvrir obligatoirement 3 catégories de scénarios :
  1. Cas nominaux (entrées valides standards).
  2. Cas limites et entrées dégénérées (`None`, chaînes vides `""`, espaces, listes/dicts vides, valeurs zéro/négatives).
  3. Cas d'erreur (vérification précise des exceptions attendues et de leur message).
- Isoler les dépendances externes (I/O, base de données, réseau) à l'aide de `unittest.mock.MagicMock` ou `pytest-mock` (`mocker`).

[FORMAT DE SORTIE]
Retourne uniquement le fichier de test Python complet (`test_*.py`), prêt à être exécuté via `pytest`.
```

#### `.prompts/03-code-review.md`
```markdown
[RÔLE]
Tu es un Auditeur Sécurité Applicative (AppSec) et Expert Performance Python.

[OBJECTIF]
Réaliser une revue de code critique du snippet Python fourni en ciblant exclusivement les failles de sécurité, les goulots d'étranglement et la robustesse.

[GRILLE D'AUDIT OBLIGATOIRE]
1. **Sécurité (OWASP / Bandit) :** Injections SQL (f-strings dans `execute()`), exécution arbitraire (`eval()`, `exec()`, `pickle.loads()`, `subprocess` avec `shell=True`), exposition de secrets/tokens.
2. **Performance :** Requêtes SQL dans des boucles (problème $N+1$), chargement complet de gros volumes en mémoire au lieu de générateurs (`yield`), complexité algorithmique $O(n^2)$ (ex. recherche `in list` répétée au lieu d'un `set`).
3. **Gestion des erreurs :** Blocs `except Exception:` ou `except:` nus silencieux, fuites de descripteurs de fichiers/connexions (absence de context manager `with`).

[FORMAT DE SORTIE]
Présente les résultats sous forme de tableau Markdown avec les colonnes : `Sévérité (CRITIQUE / MAJEURE / MINEURE)` | `Ligne / Bloc` | `Vulnérabilité ou Défaut` | `Correction Python recommandée`. Si aucun défaut critique n'est détecté, indique-le explicitement.
```

#### `.prompts/04-tech-doc.md`
```markdown
[RÔLE]
Tu es un Lead Developer Python chargé de la documentation technique d'équipe.

[OBJECTIF]
Produire la documentation technique complète (Docstrings Google Style + extrait README) du module ou de la fonction Python sélectionnée.

[CONTRAINTES]
- Rédiger les docstrings au format **Google Style** (`Args:`, `Returns:`, `Raises:`).
- Interdiction formelle de paraphraser trivialement le code (ne pas écrire « Retourne l'identifiant » sur `get_id()`). Expliquer le **pourquoi** métier, les préconditions et les invariants.
- Rédiger un court extrait prêt à être intégré dans `README.md` présentant le rôle du composant et un exemple d'utilisation concret en Python 3.11+.

[FORMAT DE SORTIE]
1. Le code Python enrichi uniquement de ses docstrings (sans modifier la logique).
2. Un bloc Markdown séparé contenant la section prête pour le `README.md`.
```

---

### 3. Exemple de code imparfait de test et relevé `STATS.md`

**Script imparfait utilisé pour le banc d'essai (`src/legacy_order_processor.py`) :**
```python
def process_order(cursor, user_id, items):
    total = 0
    for item in items:
        cursor.execute(f"SELECT price FROM products WHERE id = {item['id']}")
        row = cursor.fetchone()
        total += row[0] * item["qty"]
    if total > 0:
        cursor.execute(f"INSERT INTO orders (user_id, total) VALUES ({user_id}, {total})")
        return True
    return False
```
*(Défauts détectés par `03-code-review.md` : injections SQL critiques par f-strings ; requêtes SQL $N+1$ dans la boucle `for` ; risque de `TypeError` si `row` est `None` ; absence d'annotations de types).*

**Mise à jour de `STATS.md` :**
```markdown
## TP 2 : Comparatif Prompting Naïf vs Socle `.prompts/` (Python 3.11)

| Tâche effectuée | Prompting Naïf (Allers-retours) | Prompting Naïf (Temps) | Socle `.prompts/` (Allers-retours) | Socle `.prompts/` (Temps) | Gain observé |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Refactoring (`01-refactoring.md`)** | 4 itérations | 13 min | 1 itération | 2 min | **-85 % de temps**, typage strict Mypy immédiat |
| **Tests Pytest (`02-unit-tests.md`)** | 3 itérations | 11 min | 1 itération | 2 min | **-82 % de temps**, `@pytest.mark.parametrize` généré du 1er coup |
| **Audit Sécu/Perf (`03-code-review.md`)** | 3 itérations | 10 min | 1 itération | 1 min 30 | **-85 % de temps**, détection immédiate des f-strings SQL + $N+1$ |
| **Documentation (`04-tech-doc.md`)** | 2 itérations | 7 min | 1 itération | 1 min 30 | **-79 % de temps**, zéro paraphrase, Google Style valide |
```