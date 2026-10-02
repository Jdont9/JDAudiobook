# JD Audiobook Reader
Lecteur de livres audio Android (Kotlin, Compose, Media3).
Push sur GitHub : le workflow Actions (`.github/workflows/build.yml`) compile l'APK et le publie comme artefact du run, onglet **Actions** du dépôt (`audiobooks-debug`).

## Progression de lecture
- L'appli écrit `position.jd.json` (JSON lisible : fichier, position, vitesse, lu/non lu, date) dans le dossier de chaque livre, à côté des fichiers audio : à la pause, au changement de fichier, toutes les 20 s en lecture, et en quittant le lecteur.
- À l'ouverture d'un livre et au rescan, ce fichier est appliqué s'il est plus récent que la sauvegarde locale. S'il existe, `position.sabp.dat` (Smart AudioBook Player) est ignoré ; sinon l'import Smart fonctionne comme avant.
- Il faut l'autorisation d'écriture sur le dossier racine : si l'appli l'affiche manquante, rechoisir le dossier avec « Dossier ».

## Sauvegarde de la progression (service)
- La progression est sauvegardée par `PlaybackService` (donc même sans écran lecteur ouvert, ou depuis Android Auto) : position toutes les 5 s en lecture, `position.jd.json` + statistiques toutes les 20 s, et à chaque pause, changement de fichier, déplacement, fermeture de la tâche ou arrêt du service.
- Le cache de la bibliothèque est dans son propre fichier de préférences (`lib`), les statistiques sont cumulées en mémoire puis écrites par paquets.
- Les fichiers d'un livre sont triés en ordre « naturel » (2 avant 10). Au rescan, position et signets sont recalés par nom de fichier si l'ordre a changé.
