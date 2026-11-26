# Exercice 1

## Réponses

### 1
11 000 tokens en entrée donnent 0,0275$, plus 0,0095$ en sortie, soit 0,037$ par appel.
Le coût mensuel pour 1 800 revues est de 66,60$.

### 2
Avec le cache sur les 4 800 tokens du préfixe fixe, l'appel coûte 0,02788$, soit ≈ 50,18$ par mois (−25 %).

### 3
8 PR représentent 88 000 tokens en une minute pour un plafond de 60 000. Environ 5 requêtes passent et 3 reçoivent une erreur 429. Les solutions sont une file d'attente, un retry avec backoff exponentiel et jitter, et l'API batch. Selon le fournisseur, les tokens en cache comptent ou non dans le TPM.

- Filtrage strict du diff avant appel API : Exclure les fichiers générés ou verrouillés dès la commande Git : git diff -- . ':(exclude)package-lock.json' ':(exclude)yarn.lock' ':(exclude)*.min.js'. Ajouter un garde-fou en code qui tronque ou rejette tout diff dépassant un budget maximal (ex: 30 000 caractères).

- File d'attente avec backoff exponentiel : Implémenter un mécanisme de réessai automatique (retry) qui intercepte le code HTTP 429, lit l'en-tête Retry-After (ou attend 2s, puis 4s, puis 8s) avant de relancer la requête.


# Irritants et résultats attendus 

## Création de tickets jira : Mettre en place un proccess automatisé pour créer des nouveaux tickets
Quelles sont les parties automatisables du ticket et quelles sont les parties nécessitant une intervention humaine ?

Automatisable : 
- Type de ticket
- Date limite par défaut
- Assignation équipe
- Contenu du ticket

Nécessitant une intervention humaine : 
- Fournir un contenu de base à faire découper pour la création de plusieurs tickets, liés à cette tâche

