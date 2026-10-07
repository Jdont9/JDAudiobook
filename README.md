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
- Les fichiers d'un livre sont triés en ordre « naturel » (2 avant 10). Au rescan, la position est recalée par nom de fichier si l'ordre a changé.

## Volume
Le bouton « Volume » du lecteur ajoute un gain de 3 à 12 dB (LoudnessEnhancer), mémorisé par livre. Les signets et l'égaliseur ont été retirés.

## Mini-lecteur et navigation
- Quand un livre est chargé, un mini-lecteur (pochette, titre, recul 30 s, lecture/pause, fermer ✕) s'affiche en bas de la bibliothèque ; un appui dessus rouvre le lecteur sans rien recharger.
- Le bouton retour de l'appli, le geste ou la touche retour du système renvoient à la bibliothèque : la lecture continue et l'appli n'est pas fermée. Le ✕ du mini-lecteur met en pause et ferme le lecteur. Même comportement depuis l'écran Statistiques.

## Pochettes manquantes
- Icône image dans la bibliothèque : analyse les livres sans pochette (ni image dans le dossier, ni pochette intégrée), puis cherche sur Internet (iTunes livres audio → Audible FR → Audiolib → Audible US → Google Books → Open Library) d'après le nom du dossier (avec le dossier parent en 2e essai). Un résultat n'est gardé que si son titre ressemble assez au nom du livre.
- Pour un livre « introuvable », le bouton **Lien** du journal permet de coller l'adresse d'une page du livre (Audiolib, Audible, Babelio…) ou d'une image : l'appli en récupère la pochette (balise `og:image`). Audiolib est interrogé via l'API de recherche du site (api.hachette.fr, non officielle : elle peut changer).
- L'image est enregistrée en `cover.jpg` dans le dossier du livre (nécessite l'écriture sur le dossier racine) et aussi dans le stockage de l'appli ; si l'écriture dans le dossier échoue, la copie de l'appli sert de secours.
- Nécessite la permission Internet (ajoutée au manifeste).
