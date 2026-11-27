# Correction : Guide de Pilotage, Arbitrage et Recette des 50 Productions IA (Vue Chef d'Équipe)

**Mode d'emploi de ce corrigé :** Pour chaque production, ce document vous fournit le **livrable de référence attendu de votre équipe**, le **point de vigilance managérial** (le piège technique ou métier à anticiper) et la **question clé à poser en revue de sprint** pour valider le travail sans lire une seule ligne de code.

## Partie I : Fondations RAG, Métriques et Sécurisation (Productions 1 à 10)

### Production 1 : Découpage documentaire respectueux de la structure (`smartChunkText`)
* **Livrable attendu :** Un rapport comparatif sur les 3 fichiers métiers montrant la réduction du nombre d'extraits fragmentés et la préservation des blocs sous chaque titre `##`.
* **Point de vigilance :** Vérifier que les sections très longues (plus de $$500$$ caractères) restent bien découpées avec un chevauchement pour ne pas dépasser la capacité de lecture du modèle d'embedding.
* **Question de recette :** *"Pouvez-vous me montrer un extrait généré pour vérifier qu'un paragraphe de règle métier n'est plus coupé en deux au milieu d'une phrase ?"*

### Production 2 : Indexation incrémentale par empreinte numérique (`indexer`)
* **Livrable attendu :** Deux relevés d'exécution consécutifs dans `STATS.md` prouvant que le second lancement consomme `0` appel API et s'exécute en quelques millisecondes.
* **Point de vigilance :** S'assurer que la modification d'une seule lettre dans un fichier déclenche bien uniquement la réindexation de ce fichier précis et non de tout le dossier.
* **Question de recette :** *"Si je corrige une faute de frappe dans la politique de remboursement, combien d'appels API facturés le système déclenche-t-il ?"*

### Production 3 : Enrichissement contextuel des extraits
* **Livrable attendu :** Une entrée complète extraite de `vector-store.json` affichant le préfixe `[Document: ... | Section: ...]` tout en conservant le texte d'origine intact pour l'affichage utilisateur.
* **Point de vigilance :** Veiller à ce que l'ajout du préfixe ne dénature pas la citation brute présentée à l'utilisateur final.
* **Question de recette :** *"Si un extrait indique uniquement 'Délai de 14 jours', l'IA sait-elle désormais à quel document et à quel chapitre ce délai s'applique ?"*

### Production 4 : Validation de cohérence des 3 métriques de pertinence
* **Livrable attendu :** Un tableau de comparaison montrant le même classement Top-3 pour la similarité cosinus $$\cos(\theta)$$, le produit scalaire et la distance euclidienne $$L_2$$, avec un écart numérique inférieur à $$10^{-6}$$.
* **Point de vigilance :** Si les classements divergent, cela signifie que les vecteurs fournis par le modèle d'embedding ne sont pas normalisés (norme différente de $$1$$).
* **Question de recette :** *"Vos trois formules mathématiques classent-elles bien les mêmes documents en tête sur une requête standard ?"*

### Production 5 : Recherche hybride (Sens global + Mots-clés exacts)
* **Livrable attendu :** Une trace de recherche sur un code précis (ex. `429` ou `BLOQUEE`) montrant la remontée du score grâce au bonus de $$+0,08$$.
* **Point de vigilance :** Vérifier que le bonus ne s'applique pas sur des mots courants en majuscules (comme `LE` ou `DE`) qui fausseraient le classement.
* **Question de recette :** *"Sur une recherche contenant un code d'erreur exact, le document officiel passe-t-il bien devant un document plus généraliste ?"*

### Production 6 : Réorganisation anti-« oubli du milieu » (`reorderContext`)
* **Livrable attendu :** Une trace console affichant l'ordre d'injection des $$5$$ meilleurs extraits selon la séquence `#1, #3, #4, #5, #2`.
* **Point de vigilance :** Confirmer que le paramètre de recherche a bien été élargi à `TOP_K = 5` sans faire exploser le temps de réponse.
* **Question de recette :** *"Les deux extraits les plus pertinents sont-ils bien placés aux deux extrémités des consignes envoyées au LLM ?"*

### Production 7 : Mode audit et traçabilité des sources (`--debug`)
* **Livrable attendu :** Une capture d'écran ou sortie texte dans `STATS.md` montrant la réponse suivie de la liste numérotée des extraits, leur score et leur position dans le fichier source.
* **Point de vigilance :** Vérifier que ce mode reste désactivé par défaut pour ne pas polluer l'interface des utilisateurs finaux.
* **Question de recette :** *"En cas de réclamation métier sur une réponse de l'assistant, le support peut-il retrouver en 10 secondes le paragraphe exact qui a servi à la rédiger ?"*

### Production 8 : Bouclier contre les documents piégés (Injection indirecte)
* **Livrable attendu :** La preuve dans `STATS.md` que l'instruction pirate insérée dans la documentation a été ignorée et qu'aucun format de clé secrète (`gsk_...`, `jina_...`) n'a pu sortir.
* **Point de vigilance :** Exiger la confirmation que le fichier de test piégé a bien été nettoyé après la validation.
* **Question de recette :** *"Si un document importé ordonne à l'IA d'afficher ses clés secrètes, quelle double barrière empêche la fuite ?"*

### Production 9 : Cache sémantique des questions récurrentes (`semantic-cache.json`)
* **Livrable attendu :** Deux tests avec des formulations proches montrant le message `[CACHE HIT - 0 token LLM consommé]` et une latence inférieure à $$250\text{ ms}$$ au second appel.
* **Point de vigilance :** Attention au seuil de similarité ($$0,92$$) : trop bas, le système risquerait de servir la même réponse à deux questions pourtant opposées (ex. *"Comment activer"* vs *"Comment désactiver"*).
* **Question de recette :** *"Avez-vous vérifié que deux questions proches mais de sens opposé ne déclenchent pas le cache à tort ?"*

### Production 10 : Banc d'évaluation automatisé de non-régression (`eval-rag`)
* **Livrable attendu :** Le fichier `eval-dataset.json` ($$4$$ questions métiers + $$1$$ question hors sujet `"NONE"`) et le rapport d'exécution affichant un score sur $$5$$ (ex. `5/5 - 100 %`).
* **Point de vigilance :** La question hors sujet est la plus importante : elle garantit que l'IA sait dire *"Je ne sais pas"* au lieu d'inventer.
* **Question de recette :** *"Que se passe-t-il sur notre score global si l'IA répond à la question hors sujet au lieu de la rejeter ?"*

## Partie II : Architecture RAG Avancée et Modules Annexes (Productions 11 à 20)

### Production 11 : Double moteur de recherche avec fusion de rangs RRF (`bm25`)
* **Livrable attendu :** Un comparatif dans `STATS.md` entre le Top-3 de la recherche vectorielle seule et celui de la fusion RRF (avec constante $$k = 60$$).
* **Point de vigilance :** S'assurer que le module lexical ignore les mots vides (*stop words* comme *le, la, les, pour*) afin de valoriser uniquement les termes porteurs de sens.
* **Question de recette :** *"La fusion RRF permet-elle de remonter des documents contenant un terme métier rare que le moteur sémantique seul avait sous-estimé ?"*

### Production 12 : Recherche par réponse hypothétique (*HyDE*)
* **Livrable attendu :** Une comparaison des scores de similarité avec et sans l'option `--hyde` sur une question formulée en langage courant très familier.
* **Point de vigilance :** Contrôler le surcoût et la latence : *HyDE* ajoute un appel LLM préalable (plafonné à $$120$$ tokens) avant chaque recherche. Il doit rester optionnel ou réservé aux requêtes complexes.
* **Question de recette :** *"Quel est le surcoût en millisecondes de l'option HyDE par rapport au gain de pertinence observé ?"*

### Production 13 : Reclassement fin par IA juge (`reranker`)
* **Livrable attendu :** Une trace montrant les $$6$$ candidats initiaux notés de $$0$$ à $$10$$ par l'IA juge et la sélection finale des $$2$$ meilleurs extraits.
* **Point de vigilance :** Exiger un format de notation structuré (`JSON`) pour éviter que le modèle juge ne plante le programme avec du texte libre.
* **Question de recette :** *"Le reranker a-t-il bien éliminé un extrait qui contenait les mêmes mots-clés que la question mais ne répondait pas au problème ?"*

### Production 14 : Compression contextuelle anti-bruit (`context-compressor`)
* **Livrable attendu :** Une mesure comparative dans `STATS.md` démontrant une baisse d'au moins $$40\,\%$$ des `prompt_tokens` envoyés au générateur final.
* **Point de vigilance :** Vérifier que la compression ne supprime pas les conditions d'exception (ex. *"sauf en cas de force majeure"*).
* **Question de recette :** *"Après compression de 40 %, toutes les conditions et exceptions juridiques de l'extrait sont-elles toujours présentes dans la réponse ?"*

### Production 15 : Gestion de conversation multi-tours (`chat-rag`)
* **Livrable attendu :** La transcription d'un échange en $$3$$ tours montrant qu'une question elliptique (ex. *"Et pour l'étranger ?"*) est bien reformulée en question complète avant recherche.
* **Point de vigilance :** Vérifier que la fenêtre de mémoire est bien bornée aux $$4$$ derniers échanges pour maîtriser les coûts.
* **Question de recette :** *"Si je pose une question de suivi sans répéter le sujet initial, quelle requête exacte le système envoie-t-il au moteur de recherche ?"*

### Production 16 : Intégration de tableaux et tarifs CSV (`csv-ingestor`)
* **Livrable attendu :** Un fichier `catalogue-tarifs.csv` indexé et une question RAG prouvant la restitution exacte d'un tarif ou d'une caractéristique ligne par ligne.
* **Point de vigilance :** S'assurer que chaque ligne convertie en phrase répète explicitement le nom de chaque colonne devant sa valeur.
* **Question de recette :** *"Si deux produits ont le même prix, l'assistant attribue-t-il bien chaque ligne au bon produit sans mélanger les colonnes ?"*

### Production 17 : Mise à disposition sous forme de service web interne (`rag-server`)
* **Livrable attendu :** Un serveur HTTP local opérationnel sur le port `8080` et le résultat d'un appel `POST /api/ask` retournant un statut `200` avec la réponse, les sources et la latence.
* **Point de vigilance :** Vérifier la gestion propre des erreurs (statut `400` si la question est vide) pour éviter tout crash du serveur.
* **Question de recette :** *"Que renvoie l'API si une application cliente lui envoie une requête vide ou mal formée ?"*

### Production 18 : Journalisation d'activité et suivi des indicateurs (`rag-telemetry`)
* **Livrable attendu :** Un fichier `logs/rag-traces.jsonl` contenant $$5$$ traces complètes et un script de synthèse calculant le score moyen et le taux de rejet.
* **Point de vigilance :** Vérifier que les journaux s'ajoutent en fin de fichier sans écraser l'historique existant.
* **Question de recette :** *"Quel est notre taux actuel de questions rejetées faute de documentation pertinente ?"*

### Production 19 : Contrôle anti-hallucinations avant affichage (*Faithfulness Guardrail*)
* **Livrable attendu :** Une démonstration prouvant qu'une réponse contenant une affirmation non présente dans les sources est interceptée (`VERDICT: HALLUCINATION`) et remplacée par un message de prudence.
* **Point de vigilance :** Accepter le compromis latence/sécurité : ce double contrôle ajoute un appel de vérification avant l'affichage utilisateur.
* **Question de recette :** *"Si le modèle invente un délai de livraison non mentionné dans les extraits, le garde-fou bloque-t-il bien l'affichage ?"*

### Production 20 : Maintenance chirurgicale de la base documentaire (`manage-store`)
* **Livrable attendu :** La démonstration dans `STATS.md` de la suppression (`--delete`) et de la mise à jour (`--update`) d'un seul document sans recalculer les autres entrées de `vector-store.json`.
* **Point de vigilance :** Vérifier que le nombre total de vecteurs des autres documents reste strictement inchangé après l'opération.
* **Question de recette :** *"Combien de temps faut-il pour retirer d'urgence une procédure obsolète de la base de connaissances ?"*

## Partie III : Économie des Tokens, Résilience API et Prompting Industriel (Productions 21 à 30)

### Production 21 : Audit de sobriété des prompts et chasse aux majuscules (`token-auditor`)
* **Livrable attendu :** Un rapport d'audit exécuté sur un prompt bruyant chiffrant la surconsommation de tokens liée au français et aux majuscules abusives, ainsi que l'économie réalisée après normalisation.
* **Point de vigilance :** Préserver les quelques mots-clés structurants autorisés (`UNIQUEMENT`, `JAMAIS`, `OBLIGATOIRE`, `SELECT`, `JSON`).
* **Question de recette :** *"Quel pourcentage de réduction de coût obtenons-nous simplement en nettoyant la mise en forme de nos prompts système ?"*

### Production 22 : Aiguillage intelligent Petit Modèle vs Grand Modèle (`model-router`)
* **Livrable attendu :** Un test sur $$4$$ tâches variées montrant l'aiguillage automatique vers un SLM rapide pour l'extraction/formatage et vers un grand LLM pour le raisonnement complexe, avec calcul d'économie.
* **Point de vigilance :** Surveiller que les tâches complexes ne soient pas envoyées par erreur au petit modèle, ce qui dégraderait la qualité perçue.
* **Question de recette :** *"Sur quels critères objectifs votre routeur décide-t-il qu'une demande nécessite le modèle le plus coûteux ?"*

### Production 23 : Protection contre les saturations et pannes API (`api-resilience`)
* **Livrable attendu :** Un test simulé prouvant que le client HTTP encaisse deux erreurs `429` consécutives grâce au délai progressif aléatoire (*Exponential Backoff + Jitter*) avant d'aboutir en `200 OK`.
* **Point de vigilance :** Vérifier la présence du plafond local `MAX_INPUT_TOKENS` qui bloque une requête trop grosse avant même d'interroger l'API.
* **Question de recette :** *"Si 10 développeurs lancent une analyse simultanément et atteignent le plafond de requêtes par minute, l'outil réessaie-t-il proprement ou plante-t-il ?"*

### Production 24 : Optimisation de la mémoire cache fournisseur (`cache-bench`)
* **Livrable attendu :** Un tableau comparatif sur $$5$$ appels successifs partageant un guide de style de plus de $$1\,500$$ tokens, chiffrant la réduction obtenue grâce aux `cached_tokens`.
* **Point de vigilance :** Tout élément variable (date, nom d'utilisateur) placé en haut du prompt système casse le cache et annule l'économie.
* **Question de recette :** *"Avez-vous vérifié qu'aucune variable dynamique n'est insérée avant notre guide de style statique dans le prompt système ?"*

### Production 25 : Filtre anti-explosion de facture avant analyse de code (`diff-sanitizer`)
* **Livrable attendu :** Un relevé avant/après sur un commit contenant un fichier verrouillé (`package-lock.json`) montrant l'exclusion automatique des fichiers générés et la troncature à $$400$$ lignes.
* **Point de vigilance :** Maintenir une liste d'exclusion configurable (`.lock`, `.min.js`, dossiers `dist/`).
* **Question de recette :** *"Combien de milliers de tokens inutiles ce filtre évite-t-il d'envoyer lors d'une mise à jour de dépendances ?"*

### Production 26 : Compression glissante de l'historique de discussion
* **Livrable attendu :** Une simulation de conversation sur $$8$$ tours avec graphique ou relevé montrant le plafonnement de la consommation sous le budget de $$4\,000$$ tokens.
* **Point de vigilance :** S'assurer que le prompt système initial et les $$2$$ derniers échanges restent toujours intacts et non résumés.
* **Question de recette :** *"Au 8e échange, l'IA se souvient-elle encore des décisions prises au 1er échange grâce au résumé glissant ?"*

### Production 27 : Générateur standardisé de prompts métiers (`roc-builder`)
* **Livrable attendu :** Un outil CLI générant un fichier structuré selon les $$6$$ rubriques **ROC-DCI** dans `.prompts/` avec `temperature: 0.0` et interdiction de bavardage.
* **Point de vigilance :** Vérifier que la rubrique *Definition of Done* est bien remplie pour permettre une validation objective du résultat.
* **Question de recette :** *"Un chef de projet peut-il générer un prompt industriel prêt à l'emploi en moins de 30 secondes à partir d'une idée brute ?"*

### Production 28 : Chaîne de production logicielle en 3 étapes validées (`tdd-chain`)
* **Livrable attendu :** Les $$3$$ fichiers générés séparément (`spec.md`, `feature.test.*`, `feature.*`) sur un cas métier concret et le rapport de passage réussi des tests unitaires.
* **Point de vigilance :** Exiger que les tests couvrent explicitement les cas limites (valeurs nulles, négatives, seuils).
* **Question de recette :** *"Les tests unitaires ont-ils bien été générés à partir de la spécification avant l'écriture du code métier ?"*

### Production 29 : Garant de format informatique strict avec auto-correction (`json-enforcer`)
* **Livrable attendu :** Une trace dans `STATS.md` prouvant la détection d'un JSON malformé ou incomplet et sa réparation automatique en $$1$$ tentative de relance.
* **Point de vigilance :** Limiter strictement à $$1$$ seule tentative de correction automatique pour éviter une boucle de consommation en cas d'échec persistant.
* **Question de recette :** *"Si l'IA ajoute des balises Markdown autour du JSON ou oublie un champ obligatoire, le système corrige-t-il l'erreur sans intervention humaine ?"*

### Production 30 : Séparation des rôles en sous-agents spécialisés (`subagent-workflow`)
* **Livrable attendu :** La trace des $$3$$ exécutions isolées (**Architecte** $$\rightarrow$$ **Développeur** $$\rightarrow$$ **Testeur**) sur un fichier imparfait du dépôt.
* **Point de vigilance :** Vérifier que l'Architecte opère bien en lecture seule et ne modifie jamais directement le code.
* **Question de recette :** *"Le Testeur reçoit-il uniquement le code produit et les critères attendus, sans être influencé par l'historique de réflexion du Développeur ?"*

## Partie IV : Protocole MCP Avancé, Sécurité et Agents ReAct (Productions 31 à 40)

### Production 31 : Catalogue de procédures d'audit intégrées à l'éditeur (`mcp-prompts`)
* **Livrable attendu :** La mise à jour du serveur MCP exposant le prompt paramétré `audit_anomalies_table` et sa validation dans `MCP Inspector`.
* **Point de vigilance :** Vérifier que l'argument `table_name` est bien marqué comme obligatoire pour éviter des requêtes globales non maîtrisées.
* **Question de recette :** *"Un utilisateur peut-il déclencher un audit complet d'une table en sélectionnant simplement ce prompt dans son interface ?"*

### Production 32 : Serveur d'exploitation partagé et sécurisé par jeton (`mcp-http-ops`)
* **Livrable attendu :** Deux requêtes `curl` consignées dans `STATS.md` prouvant le rejet (`401 Unauthorized`) sans jeton valide et le succès (`200 OK`) avec jeton valide sur un service autorisé.
* **Point de vigilance :** S'assurer qu'un jeton valide ne permet malgré tout d'interroger que les services inscrits sur la liste blanche.
* **Question de recette :** *"Que se passe-t-il si un utilisateur authentifié tente d'interroger un service non inscrit sur la liste blanche ?"*

### Production 33 : Scanner de sécurité des connecteurs externes (`mcp-sec-scanner`)
* **Livrable attendu :** Le rapport d'exécution de `mcp-sec-scanner` validant un outil légitime et bloquant immédiatement un outil dont la description contient une directive cachée ou dépasse $$300$$ caractères.
* **Point de vigilance :** Imposer le passage de ce scanner avant toute installation d'un nouveau connecteur MCP tiers dans l'équipe.
* **Question de recette :** *"Si un outil externe cache une instruction demandant de lire le fichier `.env`, votre scanner le bloque-t-il avant toute exécution ?"*

### Production 34 : Verrou anti-redémarrage en cascade des serveurs critiques (`service-restarter`)
* **Livrable attendu :** Les tests unitaires prouvant le rejet des services vitaux (`payment-core`, `auth-gateway`), le filtrage par liste blanche et le blocage d'un second redémarrage avant $$15\text{ minutes}$$.
* **Point de vigilance :** Vérifier que l'état du délai de refroidissement est persisté sur disque (`restart-state.json`) pour survivre à un redémarrage du script.
* **Question de recette :** *"Si un message d'erreur piégé ordonne à l'agent de redémarrer `payment-core` deux fois de suite, quelles barrières s'activent ?"*

### Production 35 : Sas de décontamination des données externes (`untrusted-wrapper`)
* **Livrable attendu :** Le test sur le journal applicatif piégé montrant l'encapsulation dans `<external_data>` et l'insertion du marqueur `[ALERTE_INJECTION_DETECTEE]`.
* **Point de vigilance :** Vérifier que toute balise XML présente à l'intérieur du contenu brut est neutralisée pour empêcher une sortie anticipée du conteneur.
* **Question de recette :** *"Un ticket client contenant des balises fermantes XML peut-il s'échapper de la zone de quarantaine ?"*

### Production 36 : Agent enquêteur autonome avec garde-fous budgétaires (`react-agent`)
* **Livrable attendu :** La trace complète dans `STATS.md` d'une enquête croisant documentation et base SQL, s'arrêtant proprement sous les plafonds `MAX_ITERATIONS = 5` et `MAX_SESSION_TOKENS = 8000`.
* **Point de vigilance :** Vérifier que l'agent s'arrête proprement avec un message explicite s'il atteint le plafond de $$5$$ tours sans avoir conclu.
* **Question de recette :** *"Quelle est la dépense maximale absolue qu'une session de cet agent autonome peut engendrer en cas de problème ?"*

### Production 37 : Coupe-circuit automatique en cas de panne d'outil (`tool-circuit-breaker`)
* **Livrable attendu :** Une trace dans `STATS.md` montrant qu'après $$2$$ échecs consécutifs sur un outil en panne, le coupe-circuit s'ouvre et force l'agent à rendre la main proprement.
* **Point de vigilance :** S'assurer que le compteur d'échecs est indexé sur la paire `(nom de l'outil, paramètres exacts)` pour ne pas bloquer l'outil sur d'autres paramètres valides.
* **Question de recette :** *"Si une base de données est verrouillée, combien d'appels en erreur l'agent effectue-t-il au maximum avant d'abandonner ?"*

### Production 38 : Validation humaine obligatoire avant toute action sensible (`hitl-gate`)
* **Livrable attendu :** Une démonstration dans `STATS.md` d'un agent bloqué au moment d'envoyer un email externe suite à une manipulation, affichant les paramètres en jaune et exigeant une validation humaine.
* **Point de vigilance :** Vérifier qu'en mode automatisé non-interactif (ex. serveur en arrière-plan), toute action d'écriture est rejetée par défaut.
* **Question de recette :** *"Un agent peut-il envoyer un email ou modifier une ligne en base de données sans une validation humaine explicite ?"*

### Production 39 : Équipe d'agents spécialisés à privilèges restreints (`multi-agent-audit`)
* **Livrable attendu :** Le rapport final `AUDIT_CONFORMITE.md` généré par l'équipe de $$3$$ agents cloisonnés et le bilan comparatif de consommation face à un agent unique.
* **Point de vigilance :** Contrôler que l'Agent Rédacteur ne dispose techniquement d'aucun outil d'accès à la base de données ou au système de fichiers.
* **Question de recette :** *"Pourquoi cette architecture à 3 agents est-elle plus sûre et plus économique qu'un agent unique disposant de tous les outils ?"*

### Production 40 : Contrôle de conformité sécurité avant livraison (`repo-auditor`)
* **Livrable attendu :** Le rapport de conformité dans `STATS.md` attestant l'absence de fichier `.env` suivi par Git, l'absence de fuite de clés sur les $$10$$ derniers commits et la présence d'un `.env.example`.
* **Point de vigilance :** Intégrer cet auditeur dans la check-list obligatoire avant toute livraison en recette ou production.
* **Question de recette :** *"Votre script garantit-il qu'aucune clé API n'a été enregistrée par erreur dans l'historique récent du dépôt ?"*

## Partie V : Architectures IA Avancées, Quantification, Graphes & Ingénierie Déterministe (Productions 41 à 50)

### Production 41 : Compression haute performance de la mémoire vectorielle (`vector-quantizer`)
* **Livrable attendu :** Un tableau comparatif dans `STATS.md` mesurant l'empreinte mémoire, le temps de recherche et la fidélité du Top-3 entre `Float32`, `Int8` et `Binary + Rescore`.
* **Point de vigilance :** Vérifier que la recherche binaire ultra-rapide est toujours suivie d'un recalcul précis en `Float32` sur le Top-20 candidat pour préserver la qualité.
* **Question de recette :** *"De combien divisons-nous la taille de notre index sur disque tout en conservant le même Top-3 de résultats ?"*

### Production 42 : Détecteur mathématique d'hésitation de l'IA (`entropy-probe`)
* **Livrable attendu :** Deux exécutions comparatives dans `STATS.md` (question factuelle maîtrisée vs question ambiguë) avec surlignage en rouge des mots dont la probabilité linéaire chute sous $$55\,\%$$ ($$p < 0,55$$).
* **Point de vigilance :** S'assurer que l'équipe convertit bien les logarithmes (`logprobs`) en probabilités linéaires intelligibles par un humain.
* **Question de recette :** *"Ce détecteur permet-il d'identifier visuellement les chiffres ou noms propres sur lesquels l'IA hésite ?"*

### Production 43 : Moteur de recherche relationnelle par graphe (`graph-rag`)
* **Livrable attendu :** Le fichier `knowledge-graph.json` et la démonstration d'une réponse croisant deux informations distantes de $$2$$ sauts relationnels (ex. client $$\rightarrow$$ commande $$\rightarrow$$ produit en rupture).
* **Point de vigilance :** Imposer `temperature: 0.0` lors de l'extraction des relations pour éviter la création de liens imaginaires dans le graphe.
* **Question de recette :** *"Pouvez-vous me montrer une question multi-critères que le RAG classique ratait et que le GraphRAG résout désormais ?"*

### Production 44 : Découpage automatique par détection de rupture de sujet (`semantic-chunker`)
* **Livrable attendu :** La trace d'exécution sur un texte fusionné sans aucun titre prouvant que l'algorithme détecte automatiquement les frontières exactes entre les différents thèmes.
* **Point de vigilance :** Vérifier que le seuil de rupture (90e percentile ou $$\mu_d + 1,5\,\sigma_d$$) s'adapte dynamiquement à la longueur et au style de chaque document.
* **Question de recette :** *"Si un service métier nous fournit un export PDF brut sans aucun titre structuré, cet outil sait-il le découper proprement par thème ?"*

### Production 45 : Encadrement des agents critiques par processus verrouillé (`fsm-agent`)
* **Livrable attendu :** Le journal des transitions d'états d'un audit en $$4$$ étapes strictes (`READ_POLICY -> INSPECT_SCHEMA -> QUERY_DATA -> WRITE_REPORT`) prouvant le rejet de toute action hors séquence.
* **Point de vigilance :** S'assurer que les outils des étapes futures sont physiquement absents de la liste envoyée au LLM tant que l'état requis n'est pas atteint.
* **Question de recette :** *"Est-il techniquement possible pour l'agent d'interroger la base de données avant d'avoir chargé les règles de sécurité ?"*

### Production 46 : Exécution sécurisée des calculs financiers par code isolé (`code-sandbox`)
* **Livrable attendu :** Les preuves des $$3$$ tests dans `STATS.md` : calcul exact réussi, arrêt net d'une boucle infinie au bout de $$2\text{ secondes}$$ et blocage d'une tentative d'accès au fichier `.env`.
* **Point de vigilance :** Vérifier le nettoyage systématique du dossier temporaire éphémère même en cas d'erreur ou d'interruption brutale.
* **Question de recette :** *"Si le code généré par l'IA contient une boucle infinie ou tente d'accéder au réseau, comment le système réagit-il ?"*

### Production 47 : Audit qualité multi-critères de niveau industriel (`ragas-eval`)
* **Livrable attendu :** La matrice d'évaluation sur $$4$$ questions détaillant les $$3$$ notes normalisées sur $$[0, 1]$$ (*Context Precision*, *Faithfulness*, *Answer Relevance*) et leur moyenne harmonique.
* **Point de vigilance :** Utiliser cette matrice comme tableau de bord qualité officiel lors des comités de pilotage projet.
* **Question de recette :** *"Parmi la précision des sources, la fidélité sans invention et la pertinence de la réponse, quel est notre indicateur le plus faible aujourd'hui ?"*

### Production 48 : Décomposition des questions complexes multi-sujets (`query-planner`)
* **Livrable attendu :** La trace d'une requête complexe décomposée en sous-questions parallèles (maximum $$4$$) mobilisant simultanément $$3$$ documents sources distincts.
* **Point de vigilance :** Vérifier la déduplication des extraits récupérés en phase de fusion (*Fan-In*) pour ne pas payer deux fois le même paragraphe.
* **Question de recette :** *"Sur une question comparant trois règles issues de trois documents différents, l'assistant parvient-il désormais à synthétiser les trois en une seule passe ?"*

### Production 49 : Carnet d'apprentissage persistant pour agents (`agent-memory`)
* **Livrable attendu :** La preuve dans `STATS.md` d'une erreur SQL commise et corrigée lors d'une première session, mémorisée dans `memory-store.json`, puis évitée dès le premier essai lors de la session suivante.
* **Point de vigilance :** Prévoir une revue périodique de `memory-store.json` pour éviter que l'agent ne mémorise une mauvaise habitude ou une règle obsolète.
* **Question de recette :** *"Comment l'agent capitalise-t-il sur ses erreurs passées d'une exécution à l'autre sans réentraîner le modèle ?"*

### Production 50 : Tour de contrôle budgétaire et disjoncteur FinOps (`finops-gateway`)
* **Livrable attendu :** Le grand livre `finops-ledger.json`, la preuve du blocage budgétaire lors du dépassement du seuil de test (`$$0,05\text{ USD}$$`) avec bascule en mode dégradé, et le rapport `FINOPS_REPORT.md`.
* **Point de vigilance :** Vérifier que le contrôle du plafond est atomique (effectué *avant* chaque appel externe) et que chaque service métier dispose de son propre centre de coût (`tag`).
* **Question de recette :** *"Si une boucle anormale survient pendant le week-end, quel mécanisme garantit que notre facture ne dépassera jamais le plafond journalier fixé ?"*