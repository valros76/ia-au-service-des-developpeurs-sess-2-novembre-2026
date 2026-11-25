# Exercice 2 & TP 2 — Énoncés (Environnement Java)

---

## Exercice 2 : Audit, sécurisation et réécriture d'un prompt critique

Un développeur de votre équipe Java/PostgreSQL utilise le prompt suivant pour générer des migrations SQL sur une base de production critique :

> *"Fais-moi un script SQL pour ajouter une table utilisateurs et lier les commandes, fais attention à la sécurité et mets des index, merci."*

### Questions :
1. **Identifiez 5 faiblesses critiques** de ce prompt face aux risques réels d'une mise en production (syntaxe/dialecte SQL, rejeu en CI/CD, intégrité référentielle, sécurité/contraintes et automatisation/format de sortie).
2. **Réécrivez ce prompt** en appliquant strictement le cadre **ROC-DCI** (*Rôle, Objectif, Contexte, Données/Contraintes, Instructions, Format de sortie*) pour un moteur **PostgreSQL 16** (compatible avec une exécution via **Flyway** ou **Liquibase**). Votre prompt doit imposer :
   * L'idempotence totale du script (`IF NOT EXISTS`, blocs conditionnels pour les contraintes).
   * L'interdiction de supprimer un utilisateur ayant déjà passé commande (`ON DELETE RESTRICT`).
   * Le stockage sécurisé des mots de passe (empreinte de hachage uniquement, ex. Argon2id/Bcrypt) et le verrouillage des statuts autorisés (`CHECK`).
   * Une sortie brute directement exécutable par un outil de migration sans nettoyage manuel (aucun bloc Markdown, aucun texte conversationnel).

---

## TP 2 : Bibliothèque de prompts versionnée et Skills IDE (Java)

**Objectif :** Construire un socle de prompts industriels partageables en équipe via Git et configurés dans votre assistant IDE (**Continue**).

### Consignes de réalisation

1. **Configuration globale :** Créez un dossier `.prompts/` à la racine de votre dépôt Git ainsi qu'un fichier d'instructions globales pour votre IDE (`.continuerules` à la racine du projet) imposant les standards Java 21+ (immutabilité via `record`/`final`, gestion explicite de `Optional`, Clean Code).
2. **Création des templates :** Utilisez la méthode de décharge mentale pour concevoir et affiner **4 templates de prompts autonomes et idempotents** dans `.prompts/` :
   * `01-refactoring.md` : Refactoring d'une méthode Java complexe avec préservation stricte du comportement, typage fort (`Optional`, `record`, API Streams pertinente) et réduction de la complexité cyclomatique.
   * `02-unit-tests.md` : Génération de tests unitaires **JUnit 5** (avec `@ParameterizedTest` et AssertJ) couvrant le cas nominal, les entrées `null`/vides et les exceptions limites.
   * `03-code-review.md` : Grille d'audit de sécurité et de performance Java (recherche d'injections SQL JDBC/JPQL, désérialisation non sécurisée, fuites de secrets, boucles coûteuses/requêtes $N+1$, gestion des ressources `AutoCloseable`).
   * `04-tech-doc.md` : Génération de documentation technique (**Javadoc** complète avec `@param`, `@return`, `@throws` + extrait `README.md`) sans paraphraser trivialement le code.
3. **Banc d'essai sur code réel :** Prenez une classe Java volontairement imparfaite (fournie ou issue de vos projets, ex. service de traitement de commandes ou d'authentification) et appliquez successivement vos 4 prompts via Continue.
4. **Mesure d'impact :** Consignez dans `STATS.md` le gain de temps et la réduction du nombre d'allers-retours nécessaires par rapport à un prompting naïf.

### Livrables attendus
* Dossier `.prompts/` contenant les 4 fichiers structurés (`01-refactoring.md` à `04-tech-doc.md`).
* Fichier de règles IDE `.continuerules` versionné.
* Mise à jour du fichier `STATS.md` avec les observations comparatives.