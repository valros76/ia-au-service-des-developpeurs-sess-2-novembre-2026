# TP 1 : Banc d'essai économique et paramètres de génération

**Objectif :** Configurer votre environnement de travail Python local, tester l'impact des paramètres de génération (`temperature`, `top_p`) via Continue, et mesurer objectivement la consommation de tokens et la latence via un script Python.

---

## Étape 1 : Initialisation sécurisée du projet

1. Créez un dossier `formation-ia-dev` et initialisez un dépôt Git (`git init`).
2. Créez et activez un environnement virtuel Python 3.12+ en local (`python -m venv .venv` puis activation via `source .venv/bin/activate` ou `.venv\Scripts\activate`).
3. Installez un client HTTP/SDK (ex. `httpx`, `openai` ou `mistralai`) ainsi que `python-dotenv` via `pip install`, et figez vos dépendances dans un fichier `requirements.txt` (`pip freeze > requirements.txt`).
4. Créez un fichier `.gitignore` excluant les répertoires et fichiers sensibles (`__pycache__/`, `.venv/`, `.env`).
5. Créez votre fichier `.env` contenant votre clé API (par exemple `MISTRAL_API_KEY` ou `GROQ_API_KEY`) ainsi qu'un fichier `.env.example` vierge pour documenter les variables requises.

---

## Étape 2 : Exploration des paramètres dans Continue (IDE)

Ouvrez le panneau **Continue** dans votre IDE (VS Code ou PyCharm), modifiez le paramètre `temperature` dans la configuration de votre modèle (`config.json` ou `config.yaml` de Continue), et soumettez exactement la même demande :

> *"Écris une fonction Python 3.12 avec annotations de types (type hints) qui valide un numéro de SIRET français avec l'algorithme de Luhn."*

Lancez le test **3 fois avec `temperature = 0.0`**, puis **3 fois avec `temperature = 1.2`**.

### Points d'observation :
* **Stabilité de la signature :** Présence et cohérence des *type hints* (`siret: str -> bool`), respect des conventions PEP 8.
* **Gestion des cas limites :** Nettoyage des espaces, vérification de la longueur exacte (14 chiffres) et prise en compte de l'exception historique de La Poste.
* **Documentation et style :** Format des *docstrings* (Google, NumPy ou reST) et approche algorithmique choisie (compréhension de liste vs boucle classique).
* **Hallucinations :** Apparition éventuelle de méthodes de chaînes de caractères ou de modules de la bibliothèque standard inexistants lorsque la température augmente.