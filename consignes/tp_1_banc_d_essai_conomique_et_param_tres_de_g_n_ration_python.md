# TP 1 : Banc d'essai économique et paramètres de génération

**Objectif :** Configurer votre environnement de travail Python local, tester l'impact des paramètres de génération (`temperature`, `top_p`) via Continue, et mesurer objectivement la consommation de tokens et la latence via un script Python.

---

## Étape 1 : Initialisation sécurisée du projet

1. Créez un dossier `formation-ia-dev` et initialisez un dépôt Git (`git init`).
2. Créez et activez un environnement virtuel Python 3.11+ en local (`python3 -m venv .venv`), puis installez un client HTTP (`httpx` ou `requests`) et `python-dotenv` (ou utilisez `urllib.request` natif).
3. Créez un fichier `.gitignore` excluant les répertoires et fichiers sensibles (`.venv/`, `__pycache__/`, `.env`).
4. Créez votre fichier `.env` contenant votre clé API (par exemple `MISTRAL_API_KEY` ou `GROQ_API_KEY`) ainsi qu'un fichier `.env.example` vierge pour documenter les variables requises.

---

## Étape 2 : Exploration des paramètres dans Continue (IDE)

Ouvrez le panneau **Continue** dans votre IDE (VS Code ou PyCharm), modifiez le paramètre `temperature` dans la configuration de votre modèle (`config.json` ou `config.yaml` de Continue), et soumettez exactement la même demande :

> *"Écris une fonction Python 3.11+ typée qui valide un numéro de SIRET français avec l'algorithme de Luhn."*

Lancez le test **3 fois avec `temperature = 0.0`**, puis **3 fois avec `temperature = 1.2`**.

### Points d'observation :
* **Stabilité de la signature :** Présence des annotations de type (`siret: str -> bool`), nommage `snake_case`.
* **Gestion des cas limites :** Nettoyage des espaces, vérification de la longueur exacte (14 chiffres) et prise en compte de l'exception historique de La Poste.
* **Documentation et style :** Cohérence des docstrings (Google/NumPy style) et lisibilité pythonique.
* **Hallucinations :** Apparition éventuelle de méthodes inexistantes sur `str` ou d'imports de bibliothèques fictives lorsque la température augmente.

---

## Étape 3 : Script de mesure `tp1/benchmark_tokens.py`

Écrivez un script Python (`tp1/benchmark_tokens.py`) qui :
1. Envoie une requête de génération de code à l'API choisie.
2. Mesure le temps exact d'exécution avec `time.perf_counter()`.
3. Extrait les compteurs `usage.prompt_tokens` et `usage.completion_tokens` de la réponse JSON.
4. Calcule et affiche le coût exact de l'appel en dollars (`$`) et en euros (`€`).
5. Compare deux versions du même prompt : une version bavarde rédigée en langage courant, et une version condensée et structurée.

---

## Livrables attendus

* Dépôt Git initialisé avec `.gitignore` et `.env.example` propres.
* Script `tp1/benchmark_tokens.py` fonctionnel.
* Premier tableau de relevés consigné dans `STATS.md`.