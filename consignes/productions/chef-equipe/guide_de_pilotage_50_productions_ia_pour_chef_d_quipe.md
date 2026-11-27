# Guide de Pilotage : 50 Productions IA pour Chef d'Équipe Non-Développeur

**Objectif de pilotage :** Traduire les 50 chantiers techniques du programme (*IA au service des développeurs — Novembre 2026*) en **spécifications fonctionnelles, exigences de sécurité, objectifs FinOps et critères de recette (Definition of Done)**. En tant que responsable d'équipe, votre rôle n'est pas d'écrire le code, mais de cadrer le besoin métier, de challenger les risques et de valider les preuves de fonctionnement dans le journal de suivi (`STATS.md`).

## Partie I : Fondations RAG, Métriques et Sécurisation (Productions 1 à 10)

### Production 1 : Découpage documentaire respectueux de la structure (`smartChunkText`)
* **Enjeu métier :** Un découpage aveugle tous les $$N$$ caractères coupe les phrases en plein milieu et sépare un titre de son paragraphe explicatif, ce qui dégrade fortement la pertinence des réponses de l'assistant.
* **Commande à l'équipe :** Exiger que l'outil d'indexation découpe d'abord les documents selon leurs chapitres et sous-titres logiques avant d'appliquer une limite de taille maximale de $$500$$ caractères.
* **Critère de recette :** L'équipe présente un tableau comparatif sur les 3 documents de référence prouvant que le découpage structurel génère des blocs plus cohérents que l'ancien découpage fixe.

### Production 2 : Indexation incrémentale par empreinte numérique (`indexer`)
* **Enjeu métier :** Réanalyser toute la base documentaire à chaque petite correction coûte cher en appels API et ralentit les mises à jour.
* **Commande à l'équipe :** Mettre en place une signature cryptographique (**SHA-256**) sur chaque fichier afin que le système ignore automatiquement les documents qui n'ont pas été modifiés.
* **Critère de recette :** Lors d'une double exécution sans modification de fichiers, le second passage doit afficher `0` appel API et un gain de temps mesuré en millisecondes dans `STATS.md`.

### Production 3 : Enrichissement contextuel des extraits
* **Enjeu métier :** Un extrait isolé (ex. *"Le délai est de 14 jours"*) perd son sens s'il est détaché du nom de la procédure ou du contrat dont il provient.
* **Commande à l'équipe :** Imposer l'ajout automatique du nom du fichier source et du titre du chapitre en tête de chaque morceau avant son indexation.
* **Critère de recette :** Vérification d'un échantillon de la base montrant la présence systématique du bandeau `[Document: ... | Section: ...]`.

### Production 4 : Validation de cohérence des 3 métriques de pertinence
* **Enjeu métier :** Garantir que le moteur de recherche classe les documents de manière mathématiquement fiable et reproductible.
* **Commande à l'équipe :** Faire implémenter et comparer trois formules standard de calcul de proximité vectorielle (similarité cosinus $$\cos(\theta)$$, produit scalaire et distance euclidienne $$L_2$$) sur des vecteurs normalisés.
* **Critère de recette :** Présentation d'un tableau prouvant que le Top-3 des documents remontés est strictement identique quelle que soit la métrique utilisée (avec un écart inférieur à $$10^{-6}$$).

### Production 5 : Recherche hybride (Sens global + Mots-clés exacts)
* **Enjeu métier :** L'IA comprend très bien le sens général d'une phrase, mais échoue souvent sur les codes erreurs précis, références produits ou statuts métiers (ex. `429`, `BLOQUEE`).
* **Commande à l'équipe :** Ajouter un bonus de pertinence de $$+0,08$$ lorsqu'un extrait contient exactement le code numérique ou l'acronyme en majuscules recherché par l'utilisateur.
* **Critère de recette :** Démonstration sur une question contenant un code technique précis montrant le score avant et après application du bonus.

### Production 6 : Réorganisation anti-« oubli du milieu » (`reorderContext`)
* **Enjeu métier :** Les modèles d'IA exploitent très bien les informations placées tout au début et tout à la fin de leurs consignes, mais négligent souvent celles noyées au milieu.
* **Commande à l'équipe :** Passer à $$5$$ extraits récupérés et ordonner leur injection de sorte que le meilleur résultat soit placé en **1ère position** et le deuxième meilleur en **dernière position**.
* **Critère de recette :** Preuve dans le terminal que l'ordre d'envoi des extraits suit bien la séquence `#1, #3, #4, #5, #2`.

### Production 7 : Mode audit et traçabilité des sources (`--debug`)
* **Enjeu métier :** Permettre aux équipes métier et support de distinguer immédiatement un problème de documentation manquante d'une mauvaise interprétation par l'IA.
* **Commande à l'équipe :** Ajouter une option de diagnostic (`--debug`) affichant sous chaque réponse les extraits exacts utilisés, leur fichier d'origine et leur score de pertinence.
* **Critère de recette :** Capture d'une exécution métier avec `--debug` consignée dans `STATS.md`.

### Production 8 : Bouclier contre les documents piégés (Injection indirecte)
* **Enjeu métier :** Empêcher qu'un document externe malveillant (ex. un ticket client ou un fichier importé contenant une commande cachée) ne détourne l'IA pour lui faire divulguer des clés d'accès.
* **Commande à l'équipe :** Isoler strictement chaque extrait documentaire dans des balises de données passives (`<document_chunk>`) et bloquer en sortie toute réponse contenant un format de clé API (`gsk_...`, `jina_...`).
* **Critère de recette :** Test d'attaque simulée avec une instruction pirate cachée dans un document : l'attaque doit échouer totalement avant nettoyage du fichier de test.

### Production 9 : Cache sémantique des questions récurrentes (`semantic-cache.json`)
* **Enjeu métier :** Éviter de payer plusieurs fois le calcul d'une réponse lorsque des utilisateurs posent la même question avec des formulations légèrement différentes.
* **Commande à l'équipe :** Mettre en place un cache qui reconnaît toute question similaire à au moins $$92\,\%$$ ($$\ge 0,92$$) à une question déjà traitée et renvoie instantanément la réponse mémorisée.
* **Critère de recette :** Test sur deux formulations proches d'une même question avec une latence inférieure à $$250\text{ ms}$$ et `0` token consommé au second appel.

### Production 10 : Banc d'évaluation automatisé de non-régression (`eval-rag`)
* **Enjeu métier :** Piloter la qualité de l'assistant avec un indicateur objectif avant chaque mise en production, plutôt que par des tests manuels subjectifs.
* **Commande à l'équipe :** Constituer un jeu de test de $$5$$ questions étalons (incluant $$1$$ question hors sujet que l'IA doit refuser) et automatiser le calcul du score de réussite.
* **Critère de recette :** Exécution du script `eval-rag` affichant une note globale claire sur $$5$$ (ex. `5/5 - 100 %`) dans `STATS.md`.

## Partie II : Architecture RAG Avancée et Modules Annexes (Productions 11 à 20)

### Production 11 : Double moteur de recherche avec fusion de rangs RRF (`bm25`)
* **Enjeu métier :** Combiner la recherche par sens (vectorielle) et la recherche par fréquence de mots exacts (lexicale) pour maximiser la précision sur le vocabulaire métier spécialisé.
* **Commande à l'équipe :** Développer un module lexical autonome et fusionner son classement avec le moteur vectoriel grâce à la formule de rangs réciproques (**RRF** avec $$k = 60$$).
* **Critère de recette :** Comparatif dans `STATS.md` montrant l'amélioration du Top-3 grâce à la fusion des deux moteurs.

### Production 12 : Recherche par réponse hypothétique (*HyDE*)
* **Enjeu métier :** Les utilisateurs posent des questions courtes et familières qui ressemblent peu au style juridique ou technique des documents internes.
* **Commande à l'équipe :** Ajouter une option `--hyde` qui demande d'abord à l'IA de rédiger un exemple de réponse officielle (limitée à $$120$$ tokens) afin de rechercher dans la base les textes ayant le même style.
* **Critère de recette :** Comparaison des scores de pertinence avec et sans `--hyde` sur une question formulée de manière très familière.

### Production 13 : Reclassement fin par IA juge (`reranker`)
* **Enjeu métier :** Éviter les faux positifs en faisant relire les résultats bruts par un modèle plus fin avant de rédiger la réponse finale.
* **Commande à l'équipe :** Récupérer un filet large de $$6$$ extraits candidats, demander à l'IA de noter chacun de $$0$$ à $$10$$ selon sa pertinence réelle, et ne conserver que les $$2$$ meilleurs.
* **Critère de recette :** Journal de test montrant comment le reclassement élimine les extraits trompeurs au profit des $$2$$ meilleures sources.

### Production 14 : Compression contextuelle anti-bruit (`context-compressor`)
* **Enjeu métier :** Réduire la facture API et accélérer le temps de réponse en supprimant les phrases inutiles autour des informations clés.
* **Commande à l'équipe :** Implémenter un filtre qui ne conserve que les phrases directement utiles à la question dans chaque extrait retenu, visant une réduction d'au moins $$40\,\%$$ du volume.
* **Critère de recette :** Mesure comparative prouvant la baisse d'au moins $$40\,\%$$ des tokens envoyés au modèle dans `STATS.md`.

### Production 15 : Gestion de conversation multi-tours (`chat-rag`)
* **Enjeu métier :** Permettre aux utilisateurs de poser des questions de suivi naturelles (ex. *"Et si le montant dépasse 500 € ?"*) sans que l'assistant ne perde le fil du sujet initial.
* **Commande à l'équipe :** Créer un mode conversationnel conservant les $$4$$ derniers échanges et reformulant automatiquement chaque question de suivi en une requête autonome avant recherche.
* **Critère de recette :** Transcription d'un dialogue fluide en $$3$$ tours successifs démontrant la compréhension du contexte implicite.

### Production 16 : Intégration de tableaux et tarifs CSV (`csv-ingestor`)
* **Enjeu métier :** Rendre les catalogues produits, grilles tarifaires et tableaux Excel/CSV interrogeables par l'assistant sans casser le lien entre les colonnes et les lignes.
* **Commande à l'équipe :** Développer un importateur qui traduit chaque ligne d'un fichier CSV en une phrase descriptive complète avant de l'indexer.
* **Critère de recette :** Preuve que l'assistant répond exactement à une question tarifaire issue du fichier `catalogue-tarifs.csv`.

### Production 17 : Mise à disposition sous forme de service web interne (`rag-server`)
* **Enjeu métier :** Sortir l'outil du terminal des développeurs pour permettre son branchement sur un intranet, un chatbot ou une application métier.
* **Commande à l'équipe :** Exposer le moteur RAG via une interface web standardisée (`POST /api/ask`) retournant la réponse, les sources et le temps de traitement.
* **Critère de recette :** Test d'appel externe réussi consigné dans `STATS.md` avec un code statut `200`.

### Production 18 : Journalisation d'activité et suivi des indicateurs (`rag-telemetry`)
* **Enjeu métier :** Disposer d'un tableau de bord d'exploitation pour suivre l'usage réel, les temps de réponse, la consommation de tokens et les questions restées sans réponse.
* **Commande à l'équipe :** Enregistrer automatiquement chaque requête dans un journal structuré (`logs/rag-traces.jsonl`) et fournir un script de synthèse statistique.
* **Critère de recette :** Calcul automatique du score moyen et du taux de rejet sur un échantillon de $$5$$ requêtes réelles.

### Production 19 : Contrôle anti-hallucinations avant affichage (*Faithfulness Guardrail*)
* **Enjeu métier :** Protéger l'entreprise contre le risque juridique ou commercial d'une IA qui inventerait un chiffre ou une condition absente des documents officiels.
* **Commande à l'équipe :** Ajouter une étape de relecture automatique vérifiant que chaque affirmation de la réponse est bien prouvée par les extraits sources, et bloquer la réponse en cas de doute.
* **Critère de recette :** Test simulé montrant le blocage immédiat d'une réponse contenant une information inventée.

### Production 20 : Maintenance chirurgicale de la base documentaire (`manage-store`)
* **Enjeu métier :** Pouvoir retirer immédiatement un document obsolète ou mettre à jour une seule procédure sans perturber ni recalculer tout le système.
* **Commande à l'équipe :** Livrer un outil d'administration permettant de supprimer (`--delete`) ou mettre à jour (`--update`) un fichier unique dans l'index.
* **Critère de recette :** Démonstration d'une mise à jour unitaire d'un document sans altérer le reste de la base.

## Partie III : Économie des Tokens, Résilience API et Prompting Industriel (Productions 21 à 30)

### Production 21 : Audit de sobriété des prompts et chasse aux majuscules (`token-auditor`)
* **Enjeu métier :** Écrire des consignes en MAJUSCULES ou mal optimisées en français multiplie par $$2$$ à $$3$$ le coût de facturation tout en dégradant l'attention de l'IA.
* **Commande à l'équipe :** Créer un auditeur automatique qui analyse les fichiers de consignes, repère les majuscules abusives et chiffre l'économie financière après nettoyage.
* **Critère de recette :** Rapport d'audit avant/après exécuté sur un prompt verbeux dans `STATS.md`.

### Production 22 : Aiguillage intelligent Petit Modèle vs Grand Modèle (`model-router`)
* **Enjeu métier :** Utiliser un modèle massif et coûteux pour une simple extraction de texte revient à prendre un avion de ligne pour traverser la rue.
* **Commande à l'équipe :** Développer un routeur qui oriente automatiquement les tâches simples vers un modèle léger rapide (**SLM**) et réserve les grands modèles (**LLM**) aux raisonnements complexes.
* **Critère de recette :** Test sur $$4$$ tâches variées chiffrant l'économie réalisée par rapport à un usage exclusif du grand modèle.

### Production 23 : Protection contre les saturations et pannes API (`api-resilience`)
* **Enjeu métier :** Éviter que l'outil ne plante brutalement lors des pics d'activité (erreurs de quota `429` ou surcharge serveur `503`).
* **Commande à l'équipe :** Encapsuler tous les appels externes avec un mécanisme de nouvelle tentative progressive avec délai aléatoire (*Exponential Backoff + Jitter*) et un plafond de sécurité par requête.
* **Critère de recette :** Test simulé prouvant que le système encaisse deux erreurs `429` consécutives avant de réussir sans intervention humaine.

### Production 24 : Optimisation de la mémoire cache fournisseur (`cache-bench`)
* **Enjeu métier :** Réduire de $$50\,\%$$ à $$90\,\%$$ le coût de relecture des longues consignes fixes (guides de style, règles d'entreprise de plus de $$1\,024$$ tokens).
* **Commande à l'équipe :** Structurer les appels pour placer les consignes statiques tout en haut des instructions et mesurer la réduction effective de facturation sur $$5$$ appels successifs.
* **Critère de recette :** Tableau comparatif dans `STATS.md` démontrant la baisse du coût réel grâce aux tokens mis en cache.

### Production 25 : Filtre anti-explosion de facture avant analyse de code (`diff-sanitizer`)
* **Enjeu métier :** Éviter qu'un développeur n'envoie par erreur des fichiers techniques générés automatiquement (ex. `package-lock.json` dépassant $$140\,000$$ tokens), ce qui saturerait instantanément le budget et les quotas.
* **Commande à l'équipe :** Créer un filtre automatique qui exclut les fichiers générés ou minifiés et tronque tout fichier dépassant $$400$$ lignes avant envoi à l'IA.
* **Critère de recette :** Relevé avant/après sur un commit volumineux prouvant l'économie massive de tokens.

### Production 26 : Compression glissante de l'historique de discussion
* **Enjeu métier :** Empêcher que le coût d'une longue session de travail n'explose exponentiellement à mesure que la conversation s'allonge.
* **Commande à l'équipe :** Imposer un plafond budgétaire de $$4\,000$$ tokens par conversation en résumant automatiquement les échanges anciens tout en gardant intacts les $$2$$ derniers messages.
* **Critère de recette :** Simulation d'une conversation de $$8$$ tours montrant la stabilisation de la consommation de tokens.

### Production 27 : Générateur standardisé de prompts métiers (`roc-builder`)
* **Enjeu métier :** Permettre à n'importe quel membre de l'équipe de transformer une idée de $$2$$ lignes en un cahier des charges IA rigoureux et reproductible (**méthode ROC-DCI**).
* **Commande à l'équipe :** Créer un outil qui génère automatiquement un modèle structuré avec température à $$0,0$$ (zéro créativité aléatoire) et sans bavardage inutile.
* **Critère de recette :** Génération et sauvegarde d'un template prêt à l'emploi dans le dossier `.prompts/`.

### Production 28 : Chaîne de production logicielle en 3 étapes validées (`tdd-chain`)
* **Enjeu métier :** Réduire drastiquement les bugs et oublis lorsque l'IA génère du code en lui imposant de rédiger d'abord les spécifications, puis les tests, et enfin seulement le code.
* **Commande à l'équipe :** Automatiser ce pipeline en $$3$$ étapes séquentielles sur un cas métier concret (ex. calcul de remise commerciale).
* **Critère de recette :** Exécution réussie des tests unitaires générés automatiquement sur le code produit.

### Production 29 : Garant de format informatique strict avec auto-correction (`json-enforcer`)
* **Enjeu métier :** Éviter les plantages applicatifs lorsque l'IA répond par une phrase polie au lieu du format de données strict (`JSON`) attendu par le logiciel.
* **Commande à l'équipe :** Créer un validateur qui nettoie la sortie, vérifie la présence de tous les champs obligatoires et renvoie automatiquement l'erreur à l'IA pour correction en cas d'anomalie.
* **Critère de recette :** Preuve d'un rattrapage automatique réussi après une réponse volontairement mal formatée.

### Production 30 : Séparation des rôles en sous-agents spécialisés (`subagent-workflow`)
* **Enjeu métier :** Éviter les conflits d'intérêts lorsqu'une même IA doit concevoir, coder et contrôler son propre travail.
* **Commande à l'équipe :** Découper le flux en $$3$$ intervenants isolés : un **Architecte** (qui établit le plan), un **Développeur** (qui exécute) et un **Testeur** (qui audite la conformité).
* **Critère de recette :** Trace complète des $$3$$ étapes exécutées sur un fichier imparfait consignée dans `STATS.md`.

## Partie IV : Protocole MCP Avancé, Sécurité et Agents ReAct (Productions 31 à 40)

### Production 31 : Catalogue de procédures d'audit intégrées à l'éditeur (`mcp-prompts`)
* **Enjeu métier :** Offrir aux équipes des boutons d'audit standardisés directement dans leur outil de travail sans qu'ils aient à rédiger de longues consignes.
* **Commande à l'équipe :** Ajouter un modèle d'audit paramétrable (`audit_anomalies_table`) au serveur MCP de base de données.
* **Critère de recette :** Validation visuelle du chargement du modèle dans l'outil de contrôle `MCP Inspector`.

### Production 32 : Serveur d'exploitation partagé et sécurisé par jeton (`mcp-http-ops`)
* **Enjeu métier :** Permettre à toute l'équipe d'interroger l'état des services techniques à distance tout en verrouillant strictement les accès.
* **Commande à l'équipe :** Déployer le serveur en mode réseau sécurisé avec authentification obligatoire par jeton et liste blanche stricte des services consultables.
* **Critère de recette :** Preuve qu'un appel sans jeton valide est rejeté (`401 Unauthorized`) tandis qu'un appel autorisé aboutit (`200 OK`).

### Production 33 : Scanner de sécurité des connecteurs externes (`mcp-sec-scanner`)
* **Enjeu métier :** Empêcher l'installation d'extensions tierces piégées (*Tool Poisoning*) qui cacheraient dans leur notice l'ordre de voler des mots de passe ou fichiers sensibles.
* **Commande à l'équipe :** Créer un scanner statique qui inspecte la description de chaque outil externe et bloque tout terme suspect (`.env`, `password`, `secret`, `ssh`, `curl`) ou description anormalement longue ($$> 300$$ caractères).
* **Critère de recette :** Démonstration du blocage immédiat d'un connecteur empoisonné lors d'un test d'audit.

### Production 34 : Verrou anti-redémarrage en cascade des serveurs critiques (`service-restarter`)
* **Enjeu métier :** Éviter qu'un agent autonome manipulé par un message d'erreur ne redémarre en boucle les serveurs vitaux de l'entreprise (paiements, authentification).
* **Commande à l'équipe :** Imposer $$3$$ barrières de sécurité : interdiction absolue sur les services critiques, liste blanche limitée aux services non vitaux, et délai de refroidissement obligatoire de $$15\text{ minutes}$$ entre deux redémarrages.
* **Critère de recette :** Tests prouvant le rejet d'un redémarrage sur `payment-core` et le blocage d'un double redémarrage rapproché.

### Production 35 : Sas de décontamination des données externes (`untrusted-wrapper`)
* **Enjeu métier :** Neutraliser toute tentative de piratage cachée dans les journaux applicatifs, emails ou tickets clients avant qu'un agent ne les analyse.
* **Commande à l'équipe :** Filtrer et isoler automatiquement tout flux externe dans une balise dédiée (`<external_data>`) en signalant les motifs d'attaque connus.
* **Critère de recette :** Test sur un journal applicatif piégé prouvant que l'alerte d'injection est déclenchée et neutralisée.

### Production 36 : Agent enquêteur autonome avec garde-fous budgétaires (`react-agent`)
* **Enjeu métier :** Automatiser des enquêtes croisant documentation et base de données sans jamais risquer une boucle infinie qui viderait le compte API en quelques minutes.
* **Commande à l'équipe :** Déployer un agent autonome équipé d'un double plafond absolu : maximum $$5$$ étapes de réflexion (`MAX_ITERATIONS = 5`) et $$8\,000$$ tokens par session.
* **Critère de recette :** Trace d'une enquête réussie croisant deux sources de données dans le respect strict des plafonds.

### Production 37 : Coupe-circuit automatique en cas de panne d'outil (`tool-circuit-breaker`)
* **Enjeu métier :** Éviter qu'un agent ne s'acharne des dizaines de fois sur une base de données verrouillée ou un fichier corrompu.
* **Commande à l'équipe :** Installer un disjoncteur qui coupe l'accès à un outil dès qu'une même action échoue $$2$$ fois de suite et ordonne à l'agent d'en informer l'utilisateur.
* **Critère de recette :** Test sur un outil simulé en panne montrant l'arrêt propre dès le 2e échec dans `STATS.md`.

### Production 38 : Validation humaine obligatoire avant toute action sensible (`hitl-gate`)
* **Enjeu métier :** Garder le contrôle humain absolu (*Human-in-the-Loop*) avant qu'une IA n'envoie un email externe, ne modifie un fichier ou n'écrive en base de données.
* **Commande à l'équipe :** Classer les outils en **Lecture** (automatique) et **Écriture** (blocage systématique avec affichage des paramètres et demande de confirmation explicite `oui/non`).
* **Critère de recette :** Démonstration d'un agent tentant d'envoyer un email suite à un ticket client piégé et stoppé net par le portail de validation humaine.

### Production 39 : Équipe d'agents spécialisés à privilèges restreints (`multi-agent-audit`)
* **Enjeu métier :** Appliquer le principe de séparation des pouvoirs : l'agent qui rédige le rapport final ne doit pas avoir accès directement à la base de données ni aux fichiers bruts.
* **Commande à l'équipe :** Orchestrer $$3$$ agents cloisonnés (**Documentaire**, **Analyste SQL**, **Rédacteur**) ne disposant chacun que des droits strictement nécessaires à sa mission.
* **Critère de recette :** Génération du rapport `AUDIT_CONFORMITE.md` et comparaison de la consommation de tokens face à un agent unique.

### Production 40 : Contrôle de conformité sécurité avant livraison (`repo-auditor`)
* **Enjeu métier :** S'assurer avant toute mise en ligne qu'aucun mot de passe ou clé secrète n'a été oublié dans l'historique du projet et que toute la documentation est à jour.
* **Commande à l'équipe :** Créer un auditeur automatique vérifiant l'exclusion des fichiers secrets (`.env`), l'absence de fuite de clés sur les $$10$$ derniers enregistrements et la complétude de `STATS.md`.
* **Critère de recette :** Exécution de `repo-auditor` affichant un score de conformité maximal dans `STATS.md`.

## Partie V : Architectures IA Avancées, Quantification, Graphes & Ingénierie Déterministe (Productions 41 à 50)

### Production 41 : Compression haute performance de la mémoire vectorielle (`vector-quantizer`)
* **Enjeu métier :** Diviser par $$4$$ à $$32$$ l'espace mémoire et les coûts d'infrastructure nécessaires pour héberger des millions de documents sans perdre en qualité de recherche.
* **Commande à l'équipe :** Implémenter une compression des vecteurs en entiers $$8\text{ bits}$$ (`Int8`) et en format binaire $$1\text{-bit}$$ avec recalibrage fin sur les $$20$$ meilleurs candidats.
* **Critère de recette :** Tableau comparatif prouvant la réduction massive de taille sur disque tout en maintenant la fidélité du Top-3.

### Production 42 : Détecteur mathématique d'hésitation de l'IA (`entropy-probe`)
* **Enjeu métier :** Repérer le moment précis où l'IA « bafouille intérieurement » et risque d'inventer un chiffre ou un nom propre, avant même de diffuser la réponse.
* **Commande à l'équipe :** Exploiter les indices de probabilité internes (`logprobs`) pour calculer l'entropie de chaque mot généré et signaler en rouge tout passage dont la certitude chute sous $$55\,\%$$ ($$p < 0,55$$).
* **Critère de recette :** Comparaison entre une question factuelle maîtrisée et une question ambiguë montrant l'allumage des alertes d'incertitude.

### Production 43 : Moteur de recherche relationnelle par graphe (`graph-rag`)
* **Enjeu métier :** Résoudre des questions complexes nécessitant de relier plusieurs informations dispersées (ex. *"Quels clients de Lyon ont commandé un article en rupture de stock ?"*), là où la recherche classique échoue.
* **Commande à l'équipe :** Extraire automatiquement les liens entre entités sous forme de graphe de connaissances et permettre une exploration sur $$2$$ niveaux de relations.
* **Critère de recette :** Démonstration d'une réponse multi-critères impossible à obtenir à partir d'un seul paragraphe isolé.

### Production 44 : Découpage automatique par détection de rupture de sujet (`semantic-chunker`)
* **Enjeu métier :** Structurer proprement des documents bruts mal mis en page (sans titres ni chapitres) en repérant mathématiquement les changements de thème d'une phrase à l'autre.
* **Commande à l'équipe :** Développer un analyseur qui mesure la distance sémantique entre phrases consécutives et découpe le texte dès qu'un saut thématique majeur est détecté.
* **Critère de recette :** Test sur un document fusionné sans aucun titre montrant que l'outil retrouve seul les frontières exactes entre les différents sujets.

### Production 45 : Encadrement des agents critiques par processus verrouillé (`fsm-agent`)
* **Enjeu métier :** Interdire à un agent autonome de sauter une étape obligatoire (ex. modifier des données avant d'avoir vérifié les règles de sécurité).
* **Commande à l'équipe :** Piloter l'agent via une **Machine à États Finis** qui masque physiquement les outils des étapes suivantes tant que l'étape en cours n'est pas validée.
* **Critère de recette :** Trace d'exécution d'un audit en $$4$$ étapes strictes prouvant l'impossibilité de contourner l'ordre établi.

### Production 46 : Exécution sécurisée des calculs financiers par code isolé (`code-sandbox`)
* **Enjeu métier :** Les IA sont mauvaises en calcul mental mais excellentes pour écrire des formules ; il faut donc exécuter leurs calculs dans une « boîte à gants » étanche sans risque de piratage du serveur.
* **Commande à l'équipe :** Créer un environnement isolé sans accès réseau ni fichiers sensibles, doté d'un coupe-circuit automatique au bout de $$2\text{ secondes}$$ ($$2\,000\text{ ms}$$).
* **Critère de recette :** Validation de $$3$$ scénarios : calcul financier exact réussi, arrêt net d'une boucle infinie à $$2\text{ secondes}$$, et blocage d'une tentative de lecture de mots de passe.

### Production 47 : Audit qualité multi-critères de niveau industriel (`ragas-eval`)
* **Enjeu métier :** Dépasser les simples tests par mots-clés en évaluant scientifiquement la précision des sources, l'absence d'invention et la pertinence réelle de chaque réponse.
* **Commande à l'équipe :** Déployer un évaluateur automatisé mesurant les $$3$$ indicateurs standards **RAGAS** (*Context Precision*, *Faithfulness*, *Answer Relevance*) sur une batterie de $$4$$ questions.
* **Critère de recette :** Matrice complète des $$3$$ notes normalisées sur $$[0, 1]$$ consignée dans `STATS.md`.

### Production 48 : Décomposition des questions complexes multi-sujets (`query-planner`)
* **Enjeu métier :** Lorsqu'un utilisateur pose une question comparant $$3$$ sujets différents à la fois, une recherche unique rate souvent la moitié des documents pertinents.
* **Commande à l'équipe :** Mettre en place une architecture **Fan-Out / Fan-In** qui découpe toute question complexe en sous-questions indépendantes (maximum $$4$$), lance les recherches en parallèle puis fusionne les résultats.
* **Critère de recette :** Trace prouvant qu'une seule question transversale mobilise correctement $$3$$ documents distincts de la base.

### Production 49 : Carnet d'apprentissage persistant pour agents (`agent-memory`)
* **Enjeu métier :** Éviter que l'agent ne reproduise chaque jour les mêmes erreurs techniques sur les particularités de nos bases de données internes.
* **Commande à l'équipe :** Doter l'agent d'une mémoire épisodique persistante (`memory-store.json`) dans laquelle il consigne chaque erreur corrigée pour la relire avant toute future mission similaire.
* **Critère de recette :** Preuve d'apprentissage inter-sessions : une erreur commise lors d'une première session est évitée dès le premier essai lors de la session suivante.

### Production 50 : Tour de contrôle budgétaire et disjoncteur FinOps (`finops-gateway`)
* **Enjeu métier :** Garantir une maîtrise financière totale en production, imputer les coûts par service métier et empêcher tout dépassement de budget accidentel ou malveillant.
* **Commande à l'équipe :** Centraliser tous les appels IA derrière une passerelle comptable équipée d'un plafond journalier strict (avec bascule automatique en mode économique en cas d'atteinte du seuil) et d'une projection de dépense mensuelle.
* **Critère de recette :** Simulation d'un blocage budgétaire à $$0,05\text{ USD}$$ et génération automatique du rapport financier `FINOPS_REPORT.md`.