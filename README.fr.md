# JD Audiobook Reader

🇬🇧 [Read in English](README.md)

Lecteur de livres audio pour Android, simple et rapide, qui lit directement vos fichiers (MP3, M4B…) depuis un dossier de votre téléphone. Pas de compte, pas de publicité, pas de suivi.

Écrit en Kotlin avec Jetpack Compose et Media3. Version **1.5.2**. L'appli est disponible en **français et en anglais** (selon la langue du téléphone).

> 🤖 **Projet réalisé par une IA.** Le code, la documentation et les scripts de ce dépôt ont été écrits par une intelligence artificielle (Claude, d'Anthropic) à partir des demandes et des retours du propriétaire du projet, qui a dirigé le développement et utilise l'appli. Comme tout logiciel, elle peut contenir des erreurs : elle est fournie telle quelle, sans garantie.

[![Faire un don](https://img.shields.io/badge/Faire%20un%20don-PayPal-0070BA?logo=paypal&logoColor=white)](https://www.paypal.me/jd02310)

## Fonctionnalités

**Bibliothèque**
- Les actions (statistiques, pochettes manquantes, import, choix du dossier, actualisation, **Réglages**, À propos) sont dans un menu caché derrière l'**icône engrenage** en haut à droite, affiché seulement à la demande.
- Vous choisissez un dossier racine ; l'appli le parcourt et considère chaque dossier contenant des fichiers audio comme un livre.
- Formats : `mp3`, `m4b`, `m4a`, `ogg`, `opus`, `flac`, `wav`. Les fichiers sont triés en ordre naturel (`2` avant `10`).
- Pochette : image du dossier (de préférence `cover`, `folder` ou `front`), sinon image intégrée au fichier audio.
- Recherche de pochettes manquantes sur Internet (voir plus bas).
- Champ de **recherche** (dès que la bibliothèque compte quelques livres) et choix dans le menu de trier par **écoute récente** plutôt que dans l'ordre de la bibliothèque.

**Lecteur**
- Saut avant/arrière de **10, 15, 30, 45 ou 60 s** (menu → Durée du saut ; aussi utilisé par la notification et Android Auto), fichier précédent/suivant, accès direct à n'importe quel fichier du livre.
- **Signets** par livre (icône signet dans le lecteur) : ajouter la position actuelle avec une note facultative, y revenir, modifier la note, les supprimer.
- Vitesse de ×0,75 à ×3, minuterie de sommeil (10 à 90 min, avec un fondu sonore de 15 s, ou **fin du chapitre / du fichier en cours** ; après l'arrêt par la minuterie, la reprise recule de quelques minutes en plus (Réglages → Recul après la minuterie) ; secouer le téléphone relance une minuterie en cours), saut des silences, gain de volume de +3 à +12 dB (mémorisé par livre), et un **égaliseur paramétrique** avec 11 presets (voix, dialogues, vieux enregistrements, écoute de nuit, corrections de casque…) ou ta propre courbe à 10 bandes, au choix par livre.
- Chapitres pour les fichiers `m4b`/`m4a` et pour les `mp3` qui portent des chapitres ID3 (frames `CHAP`) : précédent/suivant sautent de chapitre en chapitre, et la liste des chapitres est dans le menu sous le lecteur (la ligne du fichier, avec la flèche), à côté de la liste des fichiers. Les listes de chapitres bidon (un seul chapitre, ou des chapitres séparés de moins d'une seconde) sont ignorées.
- L'appli **s'ouvre sur le lecteur du dernier livre écouté** (en pause à la position enregistrée), ou sur la bibliothèque si ce livre est terminé ; le bouton retour mène à la bibliothèque.
- **Mini-lecteur** en bas de la bibliothèque dès qu'un livre est chargé : pochette, titre, recul (durée du saut), lecture/pause, fermer. Un appui rouvre le lecteur sans rien recharger.
- **Retour** (bouton ou geste du système, bouton de l'appli) : revient à la bibliothèque, la lecture continue et l'appli reste ouverte. Le ✕ du mini-lecteur arrête la lecture.
- **Export des signets** : un bouton Partager dans la liste des signets les envoie en texte Markdown (notes, messagerie…).
- Lecture en arrière-plan avec notification et commandes de l'écran verrouillé ; utilisable depuis **Android Auto** (une entrée **Reprendre** en tête, et **recherche** vocale ou écrite de vos livres, par ex. « lis Germinal ») ; la touche **lecture d'un casque Bluetooth ou d'une voiture** reprend votre dernier livre même appli fermée.
- **Widget d'écran d'accueil** en deux tailles qui s'adaptent quand vous le redimensionnez : grand (pochette arrondie, titre du livre, fichier en cours, temps, barre de progression, précédent / −10 s / lecture-pause / +10 s / suivant) et compact (pochette, titre, précédent / lecture-pause / suivant, fine barre de progression). Il suit les couleurs du système (Material You, clair et sombre) dès Android 12, et le rouge de l'appli avant. Un appui sur la pochette ou le titre ouvre l'appli ; sans livre chargé, ▶ reprend le dernier livre.

**Réglages** (menu → Réglages)
- **Enchaîner le tome suivant** : à la fin d'un livre rangé dans un dossier de série, le livre suivant non terminé du même dossier démarre.
- **Compression de la voix** (Android 9+) : rapproche les passages faibles et forts (voiture, bruit, nuit), avec un limiteur contre la saturation.
- **Couleurs du système (Material You)** (Android 12+) : utilise les couleurs de votre fond d'écran au lieu du rouge de l'appli.
- **Sauvegarde chiffrée** : exporte / importe vos positions, signets, réglages et statistiques dans un seul fichier protégé par mot de passe (AES-256-GCM, clé dérivée par PBKDF2, 600 000 tours). Rien ne passe par Internet ; gardez le mot de passe, il est irrécupérable. Voir *Vie privée et sécurité*.

**Progression et statistiques**
- Position, vitesse et état « lu » enregistrés par livre, y compris sans l'écran du lecteur (service) ou depuis Android Auto. Un livre est marqué « lu » automatiquement quand son dernier fichier se termine, et sa position revient au début.
- Statistiques d'écoute : aujourd'hui, 7 derniers jours, par livre, temps gagné grâce à la vitesse, **série de jours en cours et record**, **calendrier de chaleur** sur 10 semaines et **export CSV**.
- **Deux appareils, un dossier** (synchronisé par Syncthing, Nextcloud, un cloud…) : si le dossier contient une position plus récente qui pointe clairement ailleurs (autre fichier, ou plus d'une minute d'écart), l'appli demande laquelle garder au lieu d'écraser la vôtre en silence.
- Reprise depuis **Smart AudioBook Player** : les fichiers `position.sabp.dat` sont lus au scan, et `statistics.xml` peut être importé (menu → Importer des statistiques).

## Installation

1. Ouvrez la [page **Releases**](https://github.com/Jdont9/JDAudiobook/releases) du dépôt GitHub et téléchargez `JDAudiobook-1.5.2.apk` sur votre téléphone.
2. Ouvrez le fichier. Android vous demandera d'autoriser l'installation depuis cette source (navigateur ou gestionnaire de fichiers) : acceptez.
3. Lancez l'appli, ouvrez le menu (**icône engrenage**, en haut à droite) → **Choisir le dossier des livres**. Autorisez l'accès, y compris en écriture (nécessaire pour la progression et les pochettes).

Android 8.0 (API 26) ou plus récent. Les mises à jour s'installent par-dessus la version précédente, car tous les APK sont signés avec la même clé.

## Mises à jour

**Menu (engrenage) → À propos → Vérifier les mises à jour.** L'appli compare sa version à la dernière release GitHub et, s'il en existe une plus récente, propose d'ouvrir la [page de téléchargement](https://github.com/Jdont9/JDAudiobook/releases). **Elle ne vérifie jamais automatiquement** : rien n'est envoyé tant que vous n'appuyez pas sur le bouton, et rien n'est téléchargé ni installé à votre place — vous installez vous-même le nouvel APK par-dessus l'ancien.

## Organisation des livres

```
Livres/
├── Auteur/
│   ├── Titre du livre/
│   │   ├── 01.mp3
│   │   ├── 02.mp3
│   │   ├── cover.jpg            ← pochette (facultatif)
│   │   └── position.jd.json     ← créé par l'appli
│   └── Autre livre/
│       └── livre.m4b            ← un seul fichier m4b convient aussi
└── ...
```

Un dossier = un livre. Le nom du dossier sert de titre, et de base à la recherche de pochettes.

## Pochettes manquantes

**Menu (engrenage) → Rechercher les pochettes manquantes** analyse les livres sans pochette, puis cherche sur Internet d'après le nom du dossier, dans cet ordre : **iTunes** (livres audio), **Audible** (France puis États-Unis), **Google Books**, **Open Library**, et **Audiolib** seulement si vous cochez la case de la fenêtre (désactivé par défaut, voir plus bas). Un résultat n'est gardé que si son titre ressemble assez au nom du livre ; un numéro de tome absent du résultat le fait écarter.

- L'image est enregistrée en `cover.jpg` dans le dossier du livre (donc retrouvée aux scans suivants), avec une copie dans l'appli en secours si l'écriture dans le dossier est refusée.
- Pour un livre « introuvable », le bouton **Lien** permet de coller l'adresse de sa page (Audiolib, Audible, Babelio…) ou d'une image : l'appli en récupère la pochette (balise `og:image`).
- Un livre introuvable n'est pas recherché à nouveau pendant 7 jours (le bouton **Lien** fonctionne toujours).
- Audiolib est interrogé via l'API de recherche de leur site (`api.hachette.fr`), qui n'est pas documentée, peut changer sans prévenir et n'est pas prévue pour des applis tierces : c'est pourquoi elle est **optionnelle** (case à cocher dans la fenêtre). Le bouton **Lien** reste alors disponible.

## Fichiers écrits dans vos dossiers

| Fichier | Rôle |
|---|---|
| `position.jd.json` | Progression du livre (fichier, position, vitesse, lu/non lu, signets avec leurs notes, date), lisible et modifiable à la main. Écrit à la pause, au changement de fichier, toutes les 60 s en lecture et en quittant le lecteur. S'il est plus récent que la sauvegarde locale, il est appliqué à l'ouverture et au rescan. |
| `cover.jpg` | Pochette téléchargée. |

Les fichiers de Smart AudioBook Player (`position.sabp.dat`) ne sont jamais modifiés.

## Vie privée et sécurité

- Aucune donnée n'est envoyée à l'auteur ; aucun compte, aucune statistique d'usage, aucune publicité.
- Les seuls accès réseau sont ceux que vous déclenchez vous-même : la recherche de pochettes (le **titre du livre**, c'est-à-dire le nom du dossier, est envoyé à iTunes (Apple), Audible (Amazon), Google Books et Open Library, et à Audiolib (Hachette) seulement si vous l'avez cochée) et la vérification de mise à jour (une requête vers `api.github.com`, qui, comme toute requête web, expose votre adresse IP à GitHub). Rien ne tourne en arrière-plan.
- Permissions : lecture en arrière-plan (service de premier plan), notifications, maintien de l'appareil éveillé pendant la lecture (le processeur seulement, et seulement tant que le son joue), Internet (recherche de pochettes et vérification de mise à jour uniquement), vibration (courte confirmation quand on secoue le téléphone pour relancer la minuterie de sommeil). L'accès aux fichiers passe par le sélecteur de dossier d'Android, limité au dossier que vous choisissez.
- Les autres applications ne peuvent ni parcourir votre bibliothèque ni piloter la lecture : le service média n'accepte que l'appli elle-même, le système (écran de verrouillage, Bluetooth), Android Auto, l'Assistant Google, Android Automotive et Wear OS. Les commandes du widget portent un jeton secret qui ne quitte jamais l'appli.
- Sauvegarde Android : si la sauvegarde d'Android est activée sur votre téléphone, votre **progression, vos vitesses, vos signets et vos statistiques d'écoute** sont inclus dans votre sauvegarde Google (jamais l'audio, les pochettes ni la bibliothèque). Pour vous y opposer, désactivez la sauvegarde dans les réglages d'Android. Elle n'a lieu que si la sauvegarde est chiffrée de bout en bout ; pour l'éviter, désactivez-la dans les réglages d'Android et utilisez plutôt la **sauvegarde chiffrée** de l'appli (Réglages).
- Réseau durci : **HTTPS uniquement** (le trafic en clair est interdit par une configuration de sécurité réseau), les requêtes vers des adresses locales ou privées (box, réseau domestique, `localhost`) sont refusées, y compris après une redirection, les redirections sont limitées à 5 et chaque téléchargement a une taille maximale. Un lien collé ne peut donc pas faire sonder votre réseau.
- Les fichiers d'un dossier partagé ou synchronisé ne sont jamais crus sur parole : la taille de `position.jd.json`, de `position.sabp.dat` et des images de pochette est plafonnée et les images absurdes sont ignorées, donc un fichier énorme ou forgé ne peut pas faire planter l'appli. Une sauvegarde n'est acceptée que si elle se déchiffre avec votre mot de passe, et seuls des réglages connus, du bon type et dans les bonnes bornes sont restaurés.
- Les pochettes servies à la notification, à Android Auto et au widget ont des **noms de fichier impossibles à deviner** (128 bits) : une autre appli ne peut pas savoir quels livres vous avez en devinant des noms.
- Vérifier votre téléchargement : *Menu → À propos* affiche l'**empreinte SHA-256 du certificat qui a signé votre APK** ; comparez-la à celle de la page de la release. Les releases portent aussi une **attestation de provenance** : `gh attestation verify JDAudiobook-X.Y.Z.apk --repo Jdont9/JDAudiobook`.

## Compiler soi-même

Prérequis : JDK 17 et Android SDK (API 34).

```bash
./gradlew assembleDebug      # APK de test : app/build/outputs/apk/debug/
./gradlew assembleRelease    # APK release, signé si une clé est fournie (voir RELEASING.fr.md)
```

Le workflow GitHub Actions (`.github/workflows/build.yml`) compile un APK debug à chaque push. Pour publier une version signée, voir **[RELEASING.fr.md](RELEASING.fr.md)**.

## Traductions

Les textes de l'interface sont dans les ressources Android : `app/src/main/res/values/strings.xml` (anglais, par défaut) et `values-fr/strings.xml` (français). Pour ajouter une langue, copiez `strings.xml` dans un nouveau dossier `values-xx/`, traduisez-le, et ajoutez la langue dans `res/xml/locales_config.xml`.

## Structure du code

`app/src/main/kotlin/fr/jd/audiobooks/` :

| Fichier | Contenu |
|---|---|
| `MainActivity.kt` | Activité, état partagé et navigation |
| `LibraryScreen.kt`, `PlayerScreen.kt`, `StatsScreen.kt` | Écrans : bibliothèque (+ mini-lecteur), lecteur, statistiques |
| `PlaybackService.kt` | Lecture en arrière-plan, session média, Android Auto (navigation, recherche, reprise), sauvegarde de la progression, minuterie de sommeil |
| `Store.kt` | Dossier racine, scan de la bibliothèque, cache, statistiques |
| `Progress.kt` | Fichier `position.jd.json` |
| `Media.kt` | Pochettes locales, chapitres, formatage |
| `CoverFetch.kt` | Recherche et téléchargement des pochettes, boîte de dialogue |
| `About.kt` | Fenêtre « À propos » et vérification de mise à jour à la demande |
| `SabpImport.kt` | Import depuis Smart AudioBook Player |
| `Skip.kt`, `Dialogs.kt` | Durée du saut avant/arrière, fenêtres des signets et du saut |
| `Util.kt` | Utilitaires partagés : journal, vitesse de lecture sûre, lectures bornées, rafraîchissement de l'écran qui se met en pause en arrière-plan |
| `CoverProvider.kt` | Sert les pochettes (URI) à la notification, à Android Auto et au widget |
| `Boost.kt`, `Dynamics.kt`, `FilePicker.kt`, `Icons.kt`, `Theme.kt` | Gain de volume, compression de la voix, sélecteurs, icônes, thème (+ option Material You) |
| `Widget.kt` (+ `res/layout/widget_jd*.xml`, `res/values*/widget_colors.xml`) | Widget d'écran d'accueil, mises en page grande et compacte |
| `SafeHttp.kt` | Requêtes sortantes : HTTPS seul, pas d'adresses privées, redirections et tailles bornées |
| `Settings.kt`, `Backup.kt` | Fenêtre des réglages ; sauvegarde chiffrée (AES-256-GCM, PBKDF2) et restauration validée |
| `Search.kt`, `StatsMath.kt`, `Bookmarks.kt` | Recherche de livres (Android Auto, voix), séries / calendrier / CSV, export des signets |
| `Shake.kt` | Détection de secousse pour la minuterie de sommeil (accéléromètre, seulement pendant qu'une minuterie tourne) |
| `Eq.kt`, `EqDialog.kt` | Égaliseur : filtres biquad dans la chaîne de sortie audio, lecture du format Equalizer APO, liste des presets et sélecteur |

Remarque : les commentaires du code source sont en grande partie en français.

## Limites connues

- La courbe d'égaliseur personnalisée est unique (10 bandes fixes), pas une par livre ; pas encore d'import de fichiers Equalizer APO.
- L'appli ne regroupe pas la bibliothèque par série (le nom du dossier de série est affiché sous chaque titre) ; « Enchaîner le tome suivant » suit l'ordre des dossiers.
- La progression, les statistiques et les signets sont toujours dans les préférences Android, pas dans une base de données.
- La recherche de pochettes dépend de services tiers et de leurs réponses ; une mauvaise correspondance reste possible sur des titres très courts ou génériques.
- Le nom du dossier doit ressembler au titre du livre pour que la recherche aboutisse.

## Licence

[GNU General Public License v3.0 ou ultérieure](LICENSE) (GPL-3.0-or-later). C'est un logiciel libre : vous pouvez l'utiliser, l'étudier, le modifier et le partager, y compris commercialement, à condition que toute version que vous distribuez reste sous la même licence, avec son code source disponible.

## Soutenir le projet

L'appli est gratuite et le restera. Si elle vous plaît et que vous souhaitez remercier son auteur, vous pouvez faire un don par [PayPal](https://www.paypal.me/jd02310). C'est entièrement facultatif : un don ne donne droit à aucun support particulier.

Historique des versions : [CHANGELOG.fr.md](CHANGELOG.fr.md).
