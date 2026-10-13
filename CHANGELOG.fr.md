# Changelog

🇬🇧 [In English](CHANGELOG.md)

## 1.5.0
**Sécurité**
- Réseau : HTTPS uniquement (le trafic en clair est désormais interdit par une configuration de sécurité réseau). Les requêtes vers des adresses locales ou privées sont refusées, y compris après une redirection, les redirections sont limitées à 5 et chaque téléchargement a une taille maximale. Un lien de pochette collé ne peut plus servir à sonder votre réseau domestique.
- Les fichiers de vos dossiers de livres ne sont plus crus sur parole : `position.jd.json`, `position.sabp.dat` et les images de pochette sont lus avec une taille maximale, et les images aux dimensions absurdes sont ignorées. Un fichier énorme ou forgé pouvait auparavant faire planter l'appli à chaque ouverture de la bibliothèque.
- Les pochettes servies aux autres applis (notification, Android Auto, widget) ont maintenant des noms impossibles à deviner (128 bits) au lieu d'un hachage de 32 bits énumérable, ce qui supprime aussi les collisions accidentelles entre deux livres.
- Commandes du widget et du service : le jeton secret est comparé à temps constant.
- Audiolib (API non officielle) devient **optionnel** : case à cocher dans la fenêtre « Rechercher les pochettes manquantes », décochée par défaut.
- La sauvegarde cloud d'Android n'est utilisée que si elle est chiffrée de bout en bout.
- À propos affiche l'empreinte SHA-256 du certificat qui a signé l'APK installé, à comparer avec la page de la release. Les releases portent une attestation de provenance de compilation.

**Nouveautés**
- **Nouveau widget d'écran d'accueil**, bien plus soigné : pochette arrondie, titre du livre, fichier en cours, temps, barre de progression et cinq boutons (précédent, −10 s, lecture/pause, +10 s, suivant) en grand format ; un format compact pour une seule ligne. Il bascule de l'un à l'autre quand on le redimensionne, suit les couleurs du système et le mode sombre (Material You dès Android 12) et peut reprendre le dernier livre quand rien n'est chargé.
- La touche **lecture** d'un casque Bluetooth ou d'une voiture reprend maintenant votre dernier livre même appli fermée.
- Android Auto : une entrée **Reprendre** en tête, la **recherche** de vos livres (aussi à la voix avec l'Assistant), et la vitesse propre au livre est maintenant appliquée quand on le lance depuis Android Auto.
- Minuterie de sommeil : **fin du chapitre en cours**, et **recul supplémentaire** automatique (0 à 15 min, 5 par défaut) à la reprise après que la minuterie a arrêté la lecture.
- Option **Enchaîner le tome suivant** : à la fin d'un livre rangé dans un dossier de série, le livre suivant non terminé du même dossier démarre.
- Quand le dossier contient une position plus récente venant d'un autre appareil et qui pointe clairement ailleurs, l'appli **demande laquelle garder** au lieu d'écraser la vôtre en silence.
- **Sauvegarde chiffrée** (Réglages) : export / import des positions, signets, réglages et statistiques dans un seul fichier protégé par mot de passe (AES-256-GCM, PBKDF2). Seuls des réglages connus, du bon type et dans les bonnes bornes sont restaurés.
- Signets : un bouton **Partager** les exporte en texte Markdown.
- Statistiques : **série** de jours en cours et record, **calendrier de chaleur** sur 10 semaines et **export CSV** (cellules protégées contre l'injection de formules des tableurs).
- **Compression de la voix** (Android 9+) pour les lieux bruyants et l'écoute de nuit.
- **Couleurs du système** (Material You) en option pour l'appli elle-même (Android 12+).
- Tablettes et pliables ouverts : le contenu reste dans une colonne centrée de 840 dp au plus.
- Nouvelle entrée **Réglages** dans le menu.

**Améliorations**
- Moins de travail en arrière-plan : la boucle d'une seconde ne tourne que pendant la lecture, et le widget n'est redessiné que lorsque quelque chose de visible change (mises à jour regroupées).
- Correction : la vitesse enregistrée d'un livre était ignorée quand on le lançait depuis Android Auto.

## 1.4.2
**Nouveauté**
- Minuterie de sommeil : secouer le téléphone pendant qu'elle tourne (fondu compris) la relance pour la même durée. Une courte vibration le confirme. Le capteur de mouvement n'est écouté que pendant qu'une minuterie à durée fixe tourne.
- Signets : une note facultative à l'ajout, et un bouton ✎ pour la modifier ensuite. La note remplace le nom du fichier comme ligne principale du signet dans la liste.
- Nouvelle permission : vibration (permission normale, rien à accepter).
- Les signets (avec leurs notes) sont maintenant enregistrés dans le `position.jd.json` du livre, à côté de la position, de la vitesse et de l'état « terminé ». Ils suivent le dossier quand tu le copies ou le synchronises vers un autre téléphone. Le fichier le plus récent l'emporte en bloc, comme pour la position. Les signets que tu as déjà seront écrits dans le fichier à la prochaine sauvegarde du livre.

## 1.4.1
**Nouveauté**
- Android Auto et notification : seuls les boutons −10 s et +10 s sont conservés (les boutons −30 s et +30 s sont retirés).
- L'appli s'ouvre maintenant directement sur le lecteur du dernier livre écouté (ou de celui déjà en lecture, par exemple lancé depuis Android Auto), en pause à ta position enregistrée. Le bouton retour mène à la bibliothèque.

## 1.4.0
**Nouveauté**
- Égaliseur : un bouton dans le lecteur (à côté du gain de volume) ouvre une liste de 11 presets tirés du guide des réglages d'Equalizer APO (voix, dialogues en avant, vieux enregistrements, écoute de nuit, aigus fatigants, équilibré, et des corrections pour écouteurs, intra-auriculaires, casques ouvert/fermé et petits haut-parleurs). Une courbe montre la forme du preset, et le son change dès le toucher. Le choix est mémorisé par livre. C'est un vrai égaliseur paramétrique (filtres en cloche, plateaux et passe-haut), identique sur tous les téléphones.
- Égaliseur : en plus des presets, une courbe **Personnalisé** à 10 bandes (de 31 Hz à 16 kHz, de −12 à +12 dB). On fait glisser les barres, un double toucher remet une barre à zéro. La courbe suit en direct et le pré-ampli baisse tout seul pour que le son ne puisse pas saturer. La courbe personnalisée est partagée par tous les livres qui la choisissent.

## 1.3.4
**Nouveautés**
- Lecteur : un bouton pour changer la pochette. On colle un lien (image ou page du livre), puis on cadre l'image dans un carré (glisser, pincer) avant d'enregistrer.
- Android Auto et notification : boutons −30 s, −10 s, +10 s et +30 s.

## 1.3.3
**Corrections**
- Le recul automatique à la reprise fonctionne maintenant après un redémarrage de l'appli ou du téléphone, et quand on ouvre un livre depuis la bibliothèque. L'heure de la dernière écoute est relue dans la position enregistrée (elle n'était gardée qu'en mémoire, donc perdue). Une pause de plus d'un jour recule de 30 s. La reprise depuis Android Auto, le widget et le Bluetooth suit la même règle.

**Changements**
- L'APK release est allégé par R8 (téléchargement plus petit, sans obfuscation du code).
- Interne : `MainActivity.kt` découpé en `LibraryScreen.kt`, `PlayerScreen.kt` et `StatsScreen.kt` (aucun changement visible).

## 1.3.2
**Corrections**
- Les chapitres séparés de moins d'une seconde sont ignorés. Certains MP3 portent des chapitres dont les débuts tombent tous dans la première seconde (affichés 0:00:00) ; la 1.3.1 ne repérait que les instants identiques, si bien qu'un tel fichier affichait encore « Chapitre 2/2 » et une liste de chapitres. Il se comporte maintenant comme le fichier sans chapitres qu'il est.

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
