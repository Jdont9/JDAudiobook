# Changelog

🇬🇧 [In English](CHANGELOG.md)

## 1.3.1
**Corrections**
- Les listes de chapitres bidon sont ignorées : il faut au moins 2 chapitres à des instants différents. Certains MP3 découpés portent des chapitres tous à 0:00:00, ce qui affichait « Chapitre 2/2 » sur un fichier qui n'a pas vraiment de chapitres. La même règle s'applique aux `m4b`/`m4a` (un chapitre unique est ignoré).

**Modifications**
- La liste des chapitres n'est plus sous la couverture (qui garde toujours sa pleine taille) : elle est maintenant dans le menu sous le lecteur (la ligne du fichier, avec la flèche), sous la liste des fichiers, pour les `m4b` comme pour les `mp3`. Le chapitre en cours est mis en évidence et un toucher y saute.

## 1.3.0
**Nouveauté**
- **Chapitres dans les fichiers MP3.** Les MP3 qui portent des chapitres ID3v2.3/2.4 (frames `CHAP`, comme en écrivent Mp3tag ou Chapter and Verse) affichent maintenant leur liste de chapitres dans le lecteur, avec précédent/suivant par chapitre et une ligne « Chapitre n/N ». Un chapitre sans titre s'affiche « Chapitre N ». Les mêmes garde-fous que pour les `m4b` s'appliquent (tailles bornées ; une balise suspecte n'affiche simplement aucun chapitre).
- Non géré : la table des matières `CTOC` est ignorée (les chapitres sont classés par heure de début, donc des chapitres imbriqués apparaissent à plat), les balises ID3v2.2 n'ont pas de chapitres, et les frames compressées ou chiffrées sont ignorées.

## 1.2.2
Version de robustesse et de sécurité, issue d'un audit du code. Aucune nouvelle fonction.

**Corrections**
- Une mauvaise valeur dans `position.jd.json` (vitesse nulle, négative ou qui n'est pas un nombre ; position négative ; date très lointaine) pouvait faire planter l'appli à chaque ouverture du livre concerné. Les valeurs lues dans ce fichier, et dans la progression enregistrée, sont maintenant vérifiées et ramenées à quelque chose d'utilisable.
- L'ouverture d'un livre ne peut plus faire planter l'appli : toute erreur est journalisée et signalée par un message.
- La lecture ne saccade plus et ne s'arrête plus écran éteint sur certains téléphones : le lecteur garde maintenant le processeur éveillé pendant la lecture (le verrou que le README promettait déjà).
- Chapitres (`m4b`/`m4a`) : un fichier corrompu ou piégé ne peut plus épuiser la mémoire ni figer l'appli. Les tailles et compteurs lus dans le fichier sont bornés, les lectures sont menées à terme, et un fichier suspect n'affiche simplement aucun chapitre.
- Remplacer une pochette écrase maintenant `cover.jpg` au lieu de créer `cover (1).jpg` à côté.
- Une pochette trouvée pendant le scan de la bibliothèque n'est plus perdue à la fin du scan.
- Le cache de la bibliothèque ne peut plus être corrompu par deux scans (appli et Android Auto) qui écrivent en même temps : les scans se font désormais l'un après l'autre.
- Des textes qui ignoraient la langue de l'appli (nom du dossier racine de la bibliothèque, nom de l'appli dans le widget et dans Android Auto) sont maintenant traduits.

**Sécurité et vie privée**
- Le service de lecture (exporté pour Android Auto et la notification) n'accepte plus n'importe quelle appli : seuls l'appli elle-même, les composants système de confiance, Android Auto, l'Assistant Google, Android Automotive, le Bluetooth et Wear OS peuvent parcourir la bibliothèque ou piloter la lecture. Les clients refusés sont journalisés.
- Les commandes lecture/suivant/précédent du widget portent un jeton secret ; les mêmes commandes envoyées par une autre appli sont ignorées. (Les widgets déjà posés sur l'écran d'accueil récupèrent le jeton dès que le lecteur change d'état ; si un bouton du widget ne répond pas juste après la mise à jour, ouvre l'appli une fois.)
- Le README précise maintenant que la sauvegarde Android inclut la progression, les signets et les statistiques, et comment la désactiver.
- La vérification de mise à jour ignore les réponses de plus de 1 Mo.
- Quand on change de dossier de bibliothèque, l'autorisation d'accès à l'ancien dossier est rendue.

**Performances**
- Les positions et les statistiques ont leurs propres fichiers : enregistrer la position toutes les 5 s réécrivait un gros fichier contenant tous les réglages, signets, durées et statistiques. Les données existantes sont déplacées automatiquement au premier lancement (et depuis une sauvegarde restaurée).
- Les statistiques par jour de plus de 400 jours sont regroupées par mois : les statistiques ne grossissent plus indéfiniment (les totaux restent exacts ; seul le détail jour par jour des vieilles données disparaît).
- La liste de la bibliothèque (filtre, recherche, tri) n'est plus recalculée à chaque rafraîchissement de l'écran.
- L'écran ne se rafraîchit plus deux fois par seconde quand l'appli est en arrière-plan.

**Autres**
- Les erreurs qui étaient avalées en silence sont maintenant écrites dans le journal Android (étiquette `JDAudiobook`).
- 10 nouveaux tests unitaires (vitesse sûre, nettoyage du fichier de progression, sauvegardes corrompues, lecture des chapitres y compris fichiers surdimensionnés ou tronqués) ; le workflow de compilation lance aussi le lint et publie son rapport.
- Workflow de compilation durci : le wrapper Gradle est vérifié, le job de compilation est en lecture seule, et seul un job séparé peut publier une version.
- Non modifié dans cette version : la source de pochettes Audiolib, les versions des dépendances et du SDK cible (Dependabot propose les mises à jour), et la taille de `MainActivity.kt`.

## 1.2.1
**Corrections**
- La rotation de l'écran ne ferme plus le lecteur (livre ouvert, écran lecteur/statistiques, recherche et tri sont conservés ; le livre est rechargé si le processus a été tué).
- Android Auto ne se fige plus quand le chargement de la bibliothèque échoue : la requête se termine par une erreur au lieu d'attendre indéfiniment.
- Les pochettes ne sont plus recopiées dans chaque fichier de la playlist d'un livre (300 fichiers = 300 copies) ; elles sont servies par URI, ce qui allège aussi la notification, l'écran de verrouillage et Android Auto.
- Le retour en arrière automatique à la reprise ne s'applique plus si tu as déplacé la position toi-même pendant la pause (choix d'un chapitre, par exemple).
- La barre de progression ne déplace la lecture qu'au relâchement, et non à chaque mouvement du doigt.
- Le bouton « saut des silences » reflète l'état réel quand on rouvre le lecteur.
- L'ouverture d'un livre n'attend plus indéfiniment si le service de lecture ne démarre pas (message d'erreur au bout de 10 s).
- L'autorisation des notifications n'est demandée que si elle n'a pas déjà été accordée.

**Nouveautés**
- **Signets** par livre.
- Réglage de la **durée du saut** (10, 15, 30, 45 ou 60 s), aussi utilisée par la notification, l'écran de verrouillage et Android Auto.
- Minuterie de sommeil : option **fin du fichier en cours** et **fondu sonore** de 15 s avant l'arrêt.
- Champ de **recherche** et tri par **écoute récente** dans la bibliothèque.
- Un livre est marqué **lu** automatiquement quand son dernier fichier se termine (position remise au début).
- Le widget affiche la pochette et reprend le dernier livre quand le lecteur est vide.
- Un livre dont la pochette est introuvable n'est pas recherché à nouveau pendant 7 jours.

**Performances**
- Le cache de la bibliothèque passe des préférences à un fichier, est gardé en mémoire et n'est plus lu sur le thread principal au démarrage.
- Le scan n'essaie plus d'ouvrir un fichier Smart AudioBook Player « deviné » pour chaque livre (seulement pour les premiers, tant qu'aucun n'est trouvé) ; le code de diagnostic laissé en place a été retiré.
- `position.jd.json` est écrit toutes les 60 s au lieu de 20 s en lecture (toujours à la pause et au changement de fichier), pour ménager les dossiers synchronisés.

**Divers**
- Téléchargement des pochettes plafonné à 10 Mo.
- La sauvegarde Android ne couvre plus que la progression, les signets et les statistiques.
- Tests unitaires des fonctions utilitaires (ordre naturel, noms de fichiers, correspondance des pochettes), exécutés par le workflow ; Dependabot activé pour les dépendances et les actions GitHub.

## 1.2.0
- **Nouveau** : les actions de la bibliothèque sont dans un menu derrière une icône engrenage (affiché seulement à la demande).
- **Nouveau** : fenêtre « À propos » avec le numéro de version et une vérification de mise à jour à la demande (jamais automatique).

## 1.1.0
- **Nouveau** : l'appli est disponible en français et en anglais (selon la langue du téléphone ; choix de la langue par appli sur Android 13+).
- **Licence** : le projet est désormais publié sous GPL-3.0-or-later.
- Documentation (README, guide de publication, changelog) en français et en anglais ; mention de la réalisation par IA et lien de don ajoutés.

## 1.0.0
Première version signée.

- Bibliothèque par dossier, lecteur (vitesse, minuterie de sommeil, saut des silences, gain de volume, chapitres m4b/m4a), widget, accès depuis Android Auto.
- Progression sauvegardée dans `position.jd.json` à côté des fichiers audio ; reprise de Smart AudioBook Player.
- Statistiques d'écoute.
- Mini-lecteur en bas de la bibliothèque quand un livre est chargé.
- Le geste ou la touche retour du système revient à la bibliothèque (la lecture continue) au lieu de fermer l'appli.
- Recherche et téléchargement des pochettes manquantes (iTunes, Audible, Audiolib, Google Books, Open Library) et ajout d'une pochette par lien collé.
