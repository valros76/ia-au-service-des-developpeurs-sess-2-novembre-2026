# TP 1 : Banc d'essai économique et paramètres de génération

**Objectif :** Configurer votre environnement de travail Java local, tester l'impact des paramètres de génération (`temperature`, `top_p`) via Continue, et mesurer objectivement la consommation de tokens et la latence via une application Java.

---

## Étape 1 : Initialisation sécurisée du projet

1. Créez un dossier `formation-ia-dev` et initialisez un dépôt Git (`git init`).
2. Initialisez un projet **Maven** (ou **Gradle**) avec un **JDK 21+** en local, et configurez la structure standard (`src/main/java/...`).
3. Ajoutez les dépendances nécessaires dans votre `pom.xml` (ou `build.gradle`) : un client JSON comme `com.fasterxml.jackson.core:jackson-databind` (pour exploiter le `java.net.http.HttpClient` natif du JDK) ainsi que `io.github.cdimascio:dotenv-java` pour la gestion des variables d'environnement.
4. Créez un fichier `.gitignore` excluant les répertoires de build et fichiers sensibles (`target/`, `.gradle/`, `build/`, `.idea/`, `.env`).
5. Créez votre fichier `.env` contenant votre clé API (par exemple `MISTRAL_API_KEY` ou `GROQ_API_KEY`) ainsi qu'un fichier `.env.example` vierge pour documenter les variables requises.

---

## Étape 2 : Exploration des paramètres dans Continue (IDE)

Ouvrez le panneau **Continue** dans votre IDE (IntelliJ IDEA ou VS Code), modifiez le paramètre `temperature` dans la configuration de votre modèle (`config.json` ou `config.yaml` de Continue), et soumettez exactement la même demande :

> *"Écris une méthode Java 21 statique et documentée qui valide un numéro de SIRET français avec l'algorithme de Luhn."*

Lancez le test **3 fois avec `temperature = 0.0`**, puis **3 fois avec `temperature = 1.2`**.

### Points d'observation :
* **Stabilité de la signature :** Modificateurs d'accès (`public static boolean`), nommage en `camelCase`, gestion des valeurs `null` (`Objects.requireNonNull` vs retour `false`).
* **Gestion des cas limites :** Nettoyage des espaces, vérification de la longueur exacte (14 chiffres) et prise en compte de l'exception historique de La Poste.
* **Documentation et style :** Cohérence des blocs Javadoc (`@param`, `@return`) et approche algorithmique choisie (`IntStream` vs boucle `for` classique).
* **Hallucinations :** Apparition éventuelle de méthodes inexistantes sur `String`, `Character` ou `IntStream` lorsque la température augmente.