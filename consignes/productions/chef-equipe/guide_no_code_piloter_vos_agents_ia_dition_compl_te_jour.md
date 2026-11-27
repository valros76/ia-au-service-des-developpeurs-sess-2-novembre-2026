# Guide Pratique No-Code : Créer et Piloter vos Agents IA

**À qui s'adresse ce guide ?** Aux chefs d'équipe, Product Owners et managers sans compétences en développement qui souhaitent configurer, sécuriser et piloter les agents IA nécessaires aux **50 productions techniques** (RAG, FinOps, sécurité, audit).

**Votre approche de pilotage :** Grâce au protocole **MCP (*Model Context Protocol*)** et à des outils **open-source gratuits**, vous assemblez des briques prêtes à l'emploi (moteur LLM, embeddings, lecture de fichiers et base de données) dans une interface visuelle, tout en cadrant chaque agent selon la méthode **ROC-DCI** (*Rôle, Objectif, Contraintes, Definition of Done, Contexte, Input/Output*).

## 0. Cartographie de la Base de Données Métier (`entreprise.db`)

Vos agents d'enquête, de conformité et d'audit (Productions 31, 36, 39, 43, 45, 49) interrogent la base SQLite `entreprise.db`. Voici sa structure exacte et son contenu de référence :

### 0.1. Table `clients` (`id`, `nom`, `email`, `segment`) — 5 clients
* `1` | **Acme Corp** (`contact@acme.fr`) — Segment `B2B`
* `2` | **TechNova** (`achats@technova.io`) — Segment `B2B`
* `3` | **Claire Martin** (`claire.m@gmail.com`) — Segment `B2C`
* `4` | **Lucas Bernard** (`l.bernard@outlook.fr`) — Segment `B2C`
* `5` | **Studio Pixel** (`admin@studiopixel.fr`) — Segment `B2B`

### 0.2. Table `produits` (`id`, `nom`, `prix`, `stock`) — 5 produits
* `1` | **Clavier Mécanique Pro** — Stock positif
* `2` | **Écran 27" 4K** — **Alerte stock critique : stock négatif (`-3`)**
* `3` | **Dock USB-C** — Stock positif
* `4` | **Souris Ergonomique** — Stock positif
* `5` | **Webcam HD** — **Alerte stock critique : stock nul ou négatif**

### 0.3. Table `commandes` (`id`, `client_id`, `produit_id`, `quantite`, `statut`, `date_commande`) — 8 commandes
* Statuts présents en base (toujours en majuscules) : `LIVREE`, `EXPEDIEE`, `EN_COURS`, et **2 commandes en anomalie avec le statut `BLOQUEE`** (dates : `2026-10-15` et `2026-10-20`).

**Repère de validation pour vos tests :** Lorsqu'un agent audite les anomalies opérationnelles ou les ruptures de stock, il doit impérativement identifier les **2 commandes `BLOQUEE`** ainsi que l'**Écran 27" 4K** (stock `-3`) et la **Webcam HD**.

## 1. Outils Open-Source Gratuits pour Déployer un Serveur MCP sans Coder

Pour connecter vos agents aux données et aux fichiers sans écrire de serveur sur mesure, vous pouvez vous appuyer sur des briques open-source gratuites :

* **Serveurs MCP officiels prêts à l'emploi :**
  * `@modelcontextprotocol/server-sqlite` / `mcp-server-sqlite` : expose le schéma et l'exécution de requêtes d'analyse (`SELECT`) sur une base SQLite.
  * `@modelcontextprotocol/server-filesystem` : permet de lire les documents (`docs/`), consulter `STATS.md` et produire des rapports dans un périmètre maîtrisé.
  * `@modelcontextprotocol/server-fetch` : récupère et convertit des pages web ou documentations distantes pour alimenter l'analyse.
* **Outils d'exposition et d'inspection gratuits :**
  * **FastMCP / MCP Gateway (Open-Source) :** permet d'exposer un serveur MCP local vers une équipe via un accès réseau sécurisé par jeton (utile pour la Production 32).
  * **MCP Inspector (`npx @modelcontextprotocol/inspector`) :** interface visuelle gratuite pour vérifier les outils, ressources et prompts exposés par un serveur MCP (indispensable pour la Production 31).

## 2. Interfaces Open-Source Gratuites pour Piloter vos Agents

* **Continue.dev (Extension VS Code / JetBrains) :** intègre nativement les fichiers `.continue/config.yaml` et `.continue/mcpServers/*.yaml`.
* **Cline (Extension VS Code) :** interface visuelle offrant une validation explicite (*Approve / Reject*) avant chaque action d'écriture (idéal pour la Production 38).
* **AnythingLLM ou Open WebUI :** solutions open-source gratuites avec gestion documentaire RAG et prise en charge MCP.

## 3. Configuration Pas à Pas (Sans Code)

### Étape 1 : Obtenir les clés d'accès gratuites
* **Groq (`gsk_...`) :** créer un compte sur `console.groq.com` puis générer une clé API pour le modèle `openai/gpt-oss-120b`.
* **Jina AI (`jina_...`) :** récupérer une clé gratuite sur `jina.ai` pour les embeddings `jina-embeddings-v3`.

### Étape 2 : Configurer les serveurs MCP dans `.continue/mcpServers/mon-serveur.yaml`

Placez la déclaration des serveurs MCP dans `.continue/mcpServers/mon-serveur.yaml` :

```yaml
name: MCP Node Skills
version: 0.0.1
schema: v1
mcpServers:
  - name: mcp-node
    command: node
    args:
      - ressources/mcp/training-scripts/nodejs/server-mcp.mjs
  - name: filesystem
    command: npx
    args:
      - -y
      - "@modelcontextprotocol/server-filesystem"
      - "."
```

### Étape 3 : Configurer le modèle LLM et les embeddings dans `.continue/config.yaml`

Sous la section `name: Config Locale Vue / Go` et `models:`, renseignez votre configuration principale :

```yaml
name: Config Locale Vue / Go
version: 1.0.0
schema: v1
models:
  - name: GPT OSS 120B
    provider: groq
    model: openai/gpt-oss-120b
    apiKey: VOTRE_CLE_GROQ_ICI
    contextLength: 8192
    roles:
      - chat
      - edit
      - apply
embeddingsProvider:
  provider: openai
  model: jina-embeddings-v3
  apiBase: https://api.jina.ai/v1
  apiKey: VOTRE_CLE_JINA_ICI
```

**Règle de sécurité (Production 40) :** Ne commitez jamais vos clés réelles (`gsk_...`, `jina_...`) dans le dépôt partagé.

## 4. Vos 5 Fiches d'Agents au Format ROC-DCI

Chaque agent ci-dessous est cadré selon le pattern **ROC-DCI** (*Rôle, Objectif, Contraintes, Definition of Done, Contexte, Input/Output*) afin de garantir un pilotage clair et reproductible.

### Agent 1 : Architecte & Cadrage Fonctionnel (Productions 1, 3, 27, 28)

* **Rôle :** Architecte Fonctionnel Senior intervenant en lecture seule sur le projet.
* **Objectif :** Transformer chaque demande métier ou production technique en un plan d'action structuré et exploitable par l'équipe.
* **Contraintes :** Aucune écriture ni modification de fichiers sans validation humaine ; pas de code monolithique non structuré ; identification systématique des cas limites.
* **Definition of Done :** Un plan d'action clair en Markdown est produit, couvrant les critères d'acceptation, les risques et les cas limites à tester.
* **Contexte :** Projet d'ingénierie IA couvrant le RAG, les prompts normés et les pipelines de test.
* **Input/Output :** Entrée : besoin métier brut ou numéro de production. Sortie : spécification structurée en Markdown.

### Agent 2 : Auditeur Qualité & Recette de Sprint (Productions 7, 10, 19, 47)

* **Rôle :** Auditeur Qualité IA rigoureux chargé de la réception des livrables.
* **Objectif :** Vérifier que chaque production annoncée dispose de preuves chiffrées et objectives dans `STATS.md` et les rapports associés.
* **Contraintes :** Ne valider aucune affirmation non étayée par une mesure ou une trace ; distinguer clairement faits constatés et points restant à corriger.
* **Definition of Done :** Un bulletin de recette synthétique (`Validé` / `À corriger`) est remis avec l'état de chaque critère de validation.
* **Contexte :** Revue de sprint et contrôle qualité des 50 productions techniques.
* **Input/Output :** Entrée : `STATS.md`, journaux et livrables du dépôt. Sortie : rapport de recette structuré par production.

### Agent 3 : Enquêteur Données & Métier (Productions 31, 36, 43, 45)

* **Rôle :** Analyste Métier autonome d'investigation sur les données et la documentation.
* **Objectif :** Répondre factuellement aux questions croisant les règles de `docs/` et les tables de `entreprise.db` (`clients`, `produits`, `commandes`).
* **Contraintes :** Utiliser uniquement les outils autorisés (`mcp-node` / `filesystem`) et exécuter exclusivement des requêtes de lecture (`SELECT`) ; respecter la casse exacte des statuts (`LIVREE`, `EXPEDIEE`, `EN_COURS`, `BLOQUEE`) ; s'arrêter dès que la réponse est établie.
* **Definition of Done :** Une réponse factuelle et sourcée est fournie, mentionnant explicitement les anomalies détectées (dont les commandes `BLOQUEE` et les stocks négatifs/nuls le cas échéant).
* **Contexte :** Base SQLite `entreprise.db` (5 clients, 5 produits, 8 commandes) et documentation métier `docs/`.
* **Input/Output :** Entrée : question d'enquête métier. Sortie : synthèse factuelle avec sources et résultats chiffrés.

### Agent 4 : Contrôleur Sécurité & Conformité (Productions 8, 33, 35, 38, 40)

* **Rôle :** Responsable Sécurité des Systèmes d'Information (RSSI) de pilotage.
* **Objectif :** Détecter les risques d'injection indirecte, de *tool poisoning*, de fuite de secrets ou d'actions d'écriture non encadrées.
* **Contraintes :** Appliquer strictement le moindre privilège ; ne jamais exposer ni afficher de clés API (`gsk_...`, `jina_...`) ; bloquer ou signaler toute action sensible non validée humainement.
* **Definition of Done :** Un diagnostic de sécurité complet liste les vulnérabilités éventuelles, leur gravité et les actions correctives requises avant mise en production.
* **Contexte :** Sécurisation des flux RAG, des serveurs MCP, des agents autonomes et de l'historique Git.
* **Input/Output :** Entrée : fichiers du dépôt, configurations MCP, logs ou extraits suspects. Sortie : rapport de conformité et alertes de sécurité.

### Agent 5 : Contrôleur de Gestion FinOps (Productions 9, 21, 22, 24, 50)

* **Rôle :** Contrôleur de Gestion FinOps spécialisé dans la maîtrise des coûts IA.
* **Objectif :** Suivre la consommation de tokens, détecter les gaspillages (majuscules abusives, historiques longs, sur-dimensionnement de modèle) et piloter le budget.
* **Contraintes :** Toujours chiffrer les impacts (tokens, latence, coût estimé) avant de recommander un arbitrage ; respecter les seuils et plafonds budgétaires définis.
* **Definition of Done :** Un bilan financier et d'optimisation (`FINOPS_REPORT.md` / synthèse) présente les coûts observés, les économies potentielles et l'état du plafond.
* **Contexte :** Suivi des appels Groq (`openai/gpt-oss-120b`), Jina (`jina-embeddings-v3`), caches et journaux (`STATS.md`, `finops-ledger.json`).
* **Input/Output :** Entrée : prompts, traces d'exécution et grand livre FinOps. Sortie : tableau de bord chiffré et recommandations d'optimisation.