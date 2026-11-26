# Ce que ce serveur initial permet de faire concrètement
Ce socle couvre les 3 piliers fondamentaux du protocole MCP avec un minimum de code, permettant aux utilisateurs de manipuler chaque concept :

- Déclencher des commandes slash humaines (Prompts) :

- Chaque fichier créé dans .prompts/ (ex: .prompts/refactor.mdr) apparaît immédiatement sous forme de commande /refactor dans l'interface du client (Claude Code, Cursor, Cline).

- L'utilisateur saisit /refactor suivi de son code : le serveur remplace {{input}} par le code et injecte le prompt prêt à l'emploi dans la conversation principale.

- Déléguer des sous-tâches à un modèle ultra-rapide (Tools + Groq) :

- L'assistant IA principal peut décider lui-même d'appeler un skill en arrière-plan (tools/call).

- Le serveur envoie alors le prompt compilé à Groq (llama-3.3-70b-versatile) via une connexion HTTP persistante, économisant les tokens et le temps de réflexion du modèle principal pour des tâches ciblées (résumé, traduction, vérification de syntaxe, génération de tests).

## Exposer le contexte brut du projet (Resources) :
Grâce au schéma d'URI personnalisé skill://nom_du_skill, le client MCP peut consulter le contenu brut d'un fichier .mdr comme source documentaire (mention @skill://... dans certains clients) sans l'exécuter.

## Expérimenter l'architecture événementielle JSON-RPC sans framework :

Les utilisateurs voient concrètement comment fonctionne un serveur stateful en ligne de commande : boucle infinie sur STDIN, réponses strictes sur STDOUT, logs isolés sur STDERR, et utilisation des nouveautés PHP 8.5 (|>, classes readonly, CurlHandle typé).