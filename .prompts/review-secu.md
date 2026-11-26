# Audit de sécurité des scripts JavaScript

## 1️⃣ Objectif
Fournir une méthode structurée pour analyser la sécurité d’un code JavaScript (frontend ou Node.js) afin d’identifier les vulnérabilités courantes et de recommander des correctifs.

## 2️⃣ Prérequis
- Accès au dépôt ou aux fichiers source.
- Outils installés :  
  - **ESLint** avec le plugin `eslint-plugin-security`.  
  - **npm audit** (ou `yarn audit`).  
  - **Node.js ≥ 20**.  
  - **Bandit/sonarjs** ou tout autre scanner SAST compatible JavaScript.  
- Connaissance des bonnes pratiques OWASP Top 10 (JavaScript/Node.js).

## Contraintes d'exécution
- N'invoque aucun outil externe, aucune fonction et aucune commande système.

## 3️⃣ Checklist de sécurité (par catégorie)

| ✅ Catégorie | Points de contrôle | Action recommandée |
|------------|--------------------|--------------------|
| **Injection** | - Recherche de `eval`, `Function`, `setTimeout(string)`, `setInterval(string)`<br>- Paramètres passés directement à des requêtes SQL/NoSQL | Remplacer par des API sécurisées, utiliser des requêtes préparées ou ORM. |
| **XSS** | - Insertion de données non‑échappées dans le DOM (`innerHTML`, `outerHTML`, `document.write`)<br>- Utilisation de bibliothèques de templating sans échappement | Utiliser des fonctions d’échappement (`DOMPurify`, `escapeHTML`) ou le binding Vue (`v-html` uniquement avec contenu sanitisé). |
| **CSRF** | - Absence de tokens CSRF sur les requêtes POST/PUT/DELETE (pour les apps serveur) | Implémenter `SameSite=Lax`/`Strict` et vérifier les tokens côté serveur. |
| **Gestion des dépendances** | - Packages avec vulnérabilités connues (`npm audit` > 0) | Mettre à jour ou remplacer les packages. |
| **Exposition d’informations** | - Clés API, secrets ou configurations en clair dans le code | Utiliser des variables d’environnement (`process.env`) et des fichiers `.env` ignorés par Git. |
| **Contrôle d’accès** | - Fonctions sensibles accessibles sans vérification d’autorisation | Ajouter des middlewares/guards côté serveur et côté client (Vue Router). |
| **Sécurité des modules natifs** | - Utilisation de `child_process.exec` ou `spawn` avec entrées non filtrées | Valider/échapper les arguments, privilégier les API de haut niveau. |
| **Sécurité du runtime** | - `node --inspect` en production, `debug` activé | Désactiver le débogage en prod, configurer `NODE_ENV=production`. |
| **Cryptographie** | - Algorithmes faibles (MD5, SHA1) ou usage direct de `crypto` sans sel | Utiliser `bcrypt`, `argon2`, ou `crypto.pbkdf2` avec paramètres sécurisés. |
| **Performance / DoS** | - Boucles infinies ou appels récursifs non protégés | Limiter la taille des entrées, appliquer des timeouts. |

## 4️⃣ Procédure d’audit

1. **Cloner le dépôt**  
   ```bash
   git clone <repo-url>
   cd <repo>
   ```

2. **Installer les dépendances en mode audit**  
   ```bash
   npm ci   # ou yarn install --frozen-lockfile
   ```

3. **Lancer les scanners**  
   - ESLint + security plugin  
     ```bash
     npx eslint . --ext .js,.ts --rule '{ "security/detect-eval-with-expression": "error" }'
     ```  
   - npm audit (ou `yarn audit`)  
     ```bash
     npm audit --audit-level=high
     ```  
   - SonarJS (ou autre SAST) – exécuter via Docker ou CI.

4. **Analyser les résultats**  
   - Prioriser les vulnérabilités **high** / **critical**.  
   - Vérifier les faux‑positifs (ex. `eval` utilisé uniquement pour du code de test).  

5. **Revue manuelle**  
   - Parcourir les fichiers contenant `eval`, `Function`, `innerHTML`.  
   - Vérifier les points d’entrée (API, formulaires) pour la validation/sanitation.  

6. **Documenter chaque problème**  
   - Description, fichier/ligne, niveau de risque, recommandation de correction.  

7. **Proposer les correctifs**  
   - Pull request ou patch avec les changements (ex. remplacement de `eval` par `JSON.parse`).  

8. **Retester**  
   - Réexécuter les scanners pour s’assurer que les issues sont résolues.  

## 5️⃣ Reporting

- **Executive summary** : nombre total de vulnérabilités, répartition par catégorie, impact business.  
- **Tableau détaillé** : chaque vulnérabilité avec ID, localisation, sévérité, statut (ouvert/fermé).  
- **Plan d’action** : priorités, estimation de l’effort, responsable.  
- **Annexes** : logs d’audit, configuration ESLint, résultats `npm audit`.

## 6️⃣ Bonnes pratiques à intégrer dans le CI/CD
```yaml
# Exemple GitHub Actions
name: Security Scan

on: [push, pull_request]

jobs:
  security:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
      - run: npm ci
      - run: npx eslint . --ext .js,.ts
      - run: npm audit --audit-level=high