# TP 1 : Banc d'essai économique et paramètres de génération

**Objectif :** Configurer votre environnement de travail PHP local, tester l'impact des paramètres de génération (`temperature`, `top_p`) via Continue, et mesurer objectivement la consommation de tokens et la latence via un script PHP.

---

## Étape 1 : Initialisation sécurisée du projet

1. Créez un dossier `formation-ia-dev` et initialisez un dépôt Git (`git init`).
2. Initialisez un projet Composer (`composer init`) avec PHP 8.3+ en local, configurez le chargement automatique PSR-4 (`"App\\": "src/"` dans `composer.json`), puis installez un client HTTP (ex. `guzzlehttp/guzzle` ou `symfony/http-client`) ainsi que `vlucas/phpdotenv` (`composer require ...`).
3. Créez un fichier `.gitignore` excluant les répertoires et fichiers sensibles (`vendor/`, `.env`).
4. Créez votre fichier `.env` contenant votre clé API (par exemple `MISTRAL_API_KEY` ou `GROQ_API_KEY`) ainsi qu'un fichier `.env.example` vierge pour documenter les variables requises.

---

## Étape 2 : Exploration des paramètres dans Continue (IDE)

Ouvrez le panneau **Continue** dans votre IDE (VS Code ou PhpStorm), modifiez le paramètre `temperature` dans la configuration de votre modèle (`config.json` ou `config.yaml` de Continue), et soumettez exactement la même demande :

> *"Écris une fonction PHP 8.3 strictement typée qui valide un numéro de SIRET français avec l'algorithme de Luhn."*

Lancez le test **3 fois avec `temperature = 0.0`**, puis **3 fois avec `temperature = 1.2`**.

### Points d'observation :
* **Stabilité de la signature :** Présence de `declare(strict_types=1);`, typage des paramètres (`string`), type de retour (`bool`).
* **Gestion des cas limites :** Nettoyage des espaces, vérification de la longueur exacte (14 chiffres) et prise en compte de l'exception historique de La Poste.
* **Documentation et style :** Cohérence des blocs PHPDoc et structure du code généré.
* **Hallucinations :** Apparition éventuelle de fonctions natives PHP inexistantes ou de paramètres invalides lorsque la température augmente.