# JD Audiobook Reader

🇬🇧 [Read in English](README.md)

Lecteur de livres audio pour Android, simple et rapide, qui lit directement tes fichiers (MP3, M4B…) depuis un dossier de ton téléphone. Pas de compte, pas de publicité, pas de suivi.

Écrit en Kotlin avec Jetpack Compose et Media3. Version **1.2.1**. L'appli est disponible en **français et en anglais** (selon la langue du téléphone).

> 🤖 **Projet réalisé par une IA.** Le code, la documentation et les scripts de ce dépôt ont été écrits par une intelligence artificielle (Claude, d'Anthropic) à partir des demandes et des retours du propriétaire du projet, qui a dirigé le développement et utilise l'appli. Comme tout logiciel, elle peut contenir des erreurs : elle est fournie telle quelle, sans garantie.

[![Faire un don](https://img.shields.io/badge/Faire%20un%20don-PayPal-0070BA?logo=paypal&logoColor=white)](https://www.paypal.me/jd02310)

## Fonctionnalités

**Bibliothèque**
- Les actions (statistiques, pochettes manquantes, import, choix du dossier, actualisation, À propos) sont dans un menu caché derrière l'**icône engrenage** en haut à droite, affiché seulement à la demande.
- Tu choisis un dossier racine ; l'appli le parcourt et considère chaque dossier contenant des fichiers audio comme un livre.
- Formats : `mp3`, `m4b`, `m4a`, `ogg`, `opus`, `flac`, `wav`. Les fichiers sont triés en ordre naturel (`2` avant `10`).
- Pochette : image du dossier (de préférence `cover`, `folder` ou `front`), sinon image intégrée au fichier audio.
- Recherche de pochettes manquantes sur Internet (voir plus bas).
- Champ de **recherche** (dès que la bibliothèque compte quelques livres) et choix dans le menu de trier par **écoute récente** plutôt que dans l'ordre de la bibliothèque.

**Lecteur**
- Saut avant/arrière de **10, 15, 30, 45 ou 60 s** (menu → Durée du saut ; aussi utilisé par la notification et Android Auto), fichier précédent/suivant, accès direct à n'importe quel fichier du livre.
- **Signets** par livre (icône signet dans le lecteur) : ajouter la position actuelle, y revenir, les supprimer.
- Vitesse de ×0,75 à ×3, minuterie de sommeil (10 à 90 min, avec un fondu sonore de 15 s, ou **fin du fichier en cours**), saut des silences, gain de volume de +3 à +12 dB (mémorisé par livre).
- Chapitres pour les fichiers `m4b`/`m4a`.
- **Mini-lecteur** en bas de la bibliothèque dès qu'un livre est chargé : pochette, titre, recul (durée du saut), lecture/pause, fermer. Un appui rouvre le lecteur sans rien recharger.
- **Retour** (bouton ou geste du système, bouton de l'appli) : revient à la bibliothèque, la lecture continue et l'appli reste ouverte. Le ✕ du mini-lecteur arrête la lecture.
- Lecture en arrière-plan avec notification et commandes de l'écran verrouillé ; utilisable depuis **Android Auto** ; widget d'écran d'accueil.

**Progression et statistiques**
- Position, vitesse et état « lu » enregistrés par livre, y compris sans l'écran du lecteur (service) ou depuis Android Auto. Un livre est marqué « lu » automatiquement quand son dernier fichier se termine, et sa position revient au début.
- Statistiques d'écoute : aujourd'hui, 7 derniers jours, par livre, et temps gagné grâce à la vitesse.
- Reprise depuis **Smart AudioBook Player** : les fichiers `position.sabp.dat` sont lus au scan, et `statistics.xml` peut être importé (menu → Importer des statistiques).

## Installation

1. Ouvre la [page **Releases**](https://github.com/Jdont9/JDAudiobook/releases) du dépôt GitHub et télécharge `JDAudiobook-1.2.1.apk` sur ton téléphone.
2. Ouvre le fichier. Android te demandera d'autoriser l'installation depuis cette source (navigateur ou gestionnaire de fichiers) : accepte.
3. Lance l'appli, ouvre le menu (**icône engrenage**, en haut à droite) → **Choisir le dossier des livres**. Autorise l'accès, y compris en écriture (nécessaire pour la progression et les pochettes).

Android 8.0 (API 26) ou plus récent. Les mises à jour s'installent par-dessus la version précédente, car tous les APK sont signés avec la même clé.

## Mises à jour

**Menu (engrenage) → À propos → Vérifier les mises à jour.** L'appli compare sa version à la dernière release GitHub et, s'il en existe une plus récente, propose d'ouvrir la [page de téléchargement](https://github.com/Jdont9/JDAudiobook/releases). **Elle ne vérifie jamais automatiquement** : rien n'est envoyé tant que tu n'appuies pas sur le bouton, et rien n'est téléchargé ni installé à ta place — tu installes toi-même le nouvel APK par-dessus l'ancien.

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

**Menu (engrenage) → Rechercher les pochettes manquantes** analyse les livres sans pochette, puis cherche sur Internet d'après le nom du dossier, dans cet ordre : **iTunes** (livres audio), **Audible** (France puis États-Unis), **Audiolib**, **Google Books**, **Open Library**. Un résultat n'est gardé que si son titre ressemble assez au nom du livre ; un numéro de tome absent du résultat le fait écarter.

- L'image est enregistrée en `cover.jpg` dans le dossier du livre (donc retrouvée aux scans suivants), avec une copie dans l'appli en secours si l'écriture dans le dossier est refusée.
- Pour un livre « introuvable », le bouton **Lien** permet de coller l'adresse de sa page (Audiolib, Audible, Babelio…) ou d'une image : l'appli en récupère la pochette (balise `og:image`).
- Un livre introuvable n'est pas recherché à nouveau pendant 7 jours (le bouton **Lien** fonctionne toujours).
- Audiolib est interrogé via l'API de recherche de leur site (`api.hachette.fr`), qui n'est pas documentée : elle peut changer sans prévenir. Le bouton **Lien** reste alors disponible.

## Fichiers écrits dans tes dossiers

| Fichier | Rôle |
|---|---|
| `position.jd.json` | Progression du livre (fichier, position, vitesse, lu/non lu, date), lisible et modifiable à la main. Écrit à la pause, au changement de fichier, toutes les 60 s en lecture et en quittant le lecteur. S'il est plus récent que la sauvegarde locale, il est appliqué à l'ouverture et au rescan. |
| `cover.jpg` | Pochette téléchargée. |

Les fichiers de Smart AudioBook Player (`position.sabp.dat`) ne sont jamais modifiés.

## Vie privée

- Aucune donnée n'est envoyée à l'auteur ; aucun compte, aucune statistique d'usage, aucune publicité.
- Les seuls accès réseau sont ceux que tu déclenches toi-même : la recherche de pochettes (le **titre du livre**, c'est-à-dire le nom du dossier, est envoyé à iTunes (Apple), Audible (Amazon), Audiolib (Hachette), Google Books et Open Library) et la vérification de mise à jour (une requête vers `api.github.com`, qui, comme toute requête web, expose ton adresse IP à GitHub). Rien ne tourne en arrière-plan.
- Permissions : lecture en arrière-plan (service de premier plan), notifications, maintien de l'appareil éveillé pendant la lecture, Internet (recherche de pochettes et vérification de mise à jour uniquement). L'accès aux fichiers passe par le sélecteur de dossier d'Android, limité au dossier que tu choisis.

## Compiler soi-même

Prérequis : JDK 17 et Android SDK (API 34).

```bash
./gradlew assembleDebug      # APK de test : app/build/outputs/apk/debug/
./gradlew assembleRelease    # APK release, signé si une clé est fournie (voir RELEASING.fr.md)
```

Le workflow GitHub Actions (`.github/workflows/build.yml`) compile un APK debug à chaque push. Pour publier une version signée, voir **[RELEASING.fr.md](RELEASING.fr.md)**.

## Traductions

Les textes de l'interface sont dans les ressources Android : `app/src/main/res/values/strings.xml` (anglais, par défaut) et `values-fr/strings.xml` (français). Pour ajouter une langue, copie `strings.xml` dans un nouveau dossier `values-xx/`, traduis-le, et ajoute la langue dans `res/xml/locales_config.xml`.

## Structure du code

`app/src/main/kotlin/fr/jd/audiobooks/` :

| Fichier | Contenu |
|---|---|
| `MainActivity.kt` | Écrans (bibliothèque, lecteur, mini-lecteur, statistiques) et navigation |
| `PlaybackService.kt` | Lecture en arrière-plan, session média, Android Auto, sauvegarde de la progression, minuterie de sommeil |
| `Store.kt` | Dossier racine, scan de la bibliothèque, cache, statistiques |
| `Progress.kt` | Fichier `position.jd.json` |
| `Media.kt` | Pochettes locales, chapitres, formatage |
| `CoverFetch.kt` | Recherche et téléchargement des pochettes, boîte de dialogue |
| `About.kt` | Fenêtre « À propos » et vérification de mise à jour à la demande |
| `SabpImport.kt` | Import depuis Smart AudioBook Player |
| `Skip.kt`, `Dialogs.kt` | Durée du saut avant/arrière, fenêtres des signets et du saut |
| `CoverProvider.kt` | Sert les pochettes (URI) à la notification, à Android Auto et au widget |
| `Boost.kt`, `Widget.kt`, `FilePicker.kt`, `Icons.kt`, `Theme.kt` | Gain de volume, widget, sélecteurs, icônes, thème |

Remarque : les commentaires du code source sont en grande partie en français.

## Limites connues

- Pas d'égaliseur (retiré volontairement).
- La recherche de pochettes dépend de services tiers et de leurs réponses ; une mauvaise correspondance reste possible sur des titres très courts ou génériques.
- Le nom du dossier doit ressembler au titre du livre pour que la recherche aboutisse.

## Licence

[GNU General Public License v3.0 ou ultérieure](LICENSE) (GPL-3.0-or-later). C'est un logiciel libre : tu peux l'utiliser, l'étudier, le modifier et le partager, y compris commercialement, à condition que toute version que tu distribues reste sous la même licence, avec son code source disponible.

## Soutenir le projet

L'appli est gratuite et le restera. Si elle te plaît et que tu veux remercier son auteur, tu peux faire un don par [PayPal](https://www.paypal.me/jd02310). C'est entièrement facultatif : un don ne donne droit à aucun support particulier.

Historique des versions : [CHANGELOG.fr.md](CHANGELOG.fr.md).
