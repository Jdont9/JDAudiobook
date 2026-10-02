# JD Audiobook Reader
Lecteur de livres audio Android (Kotlin, Compose, Media3).
Push sur GitHub : le workflow Actions (`.github/workflows/build.yml`) compile l'APK et le publie comme artefact du run, onglet **Actions** du dépôt (`audiobooks-debug`).

## Progression de lecture
- L'appli écrit `position.jd.json` (JSON lisible : fichier, position, vitesse, lu/non lu, date) dans le dossier de chaque livre, à côté des fichiers audio : à la pause, au changement de fichier, toutes les 20 s en lecture, et en quittant le lecteur.
- À l'ouverture d'un livre et au rescan, ce fichier est appliqué s'il est plus récent que la sauvegarde locale. S'il existe, `position.sabp.dat` (Smart AudioBook Player) est ignoré ; sinon l'import Smart fonctionne comme avant.
- Il faut l'autorisation d'écriture sur le dossier racine : si l'appli l'affiche manquante, rechoisir le dossier avec « Dossier ».
