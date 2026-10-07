# JD Audiobook Reader

Lecteur de livres audio pour Android, simple et rapide, qui lit directement tes fichiers (MP3, M4B…) depuis un dossier de ton téléphone. Pas de compte, pas de publicité, pas de suivi.

Écrit en Kotlin avec Jetpack Compose et Media3. Version **1.0.0**.

## Fonctionnalités

**Bibliothèque**
- Tu choisis un dossier racine ; l'appli le parcourt et considère chaque dossier contenant des fichiers audio comme un livre.
- Formats : `mp3`, `m4b`, `m4a`, `ogg`, `opus`, `flac`, `wav`. Les fichiers sont triés en ordre naturel (`2` avant `10`).
- Pochette : image du dossier (de préférence `cover`, `folder` ou `front`), sinon image intégrée au fichier audio.
- Recherche de pochettes manquantes sur Internet (voir plus bas).

**Lecteur**
- Recul/avance de 30 s, fichier précédent/suivant, choix d'un fichier du livre.
- Vitesse de ×0,75 à ×3, minuterie de sommeil (10 à 90 min), saut des silences, gain de volume de +3 à +12 dB (mémorisé par livre).
- Chapitres pour les fichiers `m4b`/`m4a`.
- **Mini-lecteur** en bas de la bibliothèque dès qu'un livre est chargé : pochette, titre, recul 30 s, lecture/pause, fermer. Un appui rouvre le lecteur sans rien recharger.
- **Retour** (bouton ou geste du système, bouton de l'appli) : revient à la bibliothèque, la lecture continue et l'appli reste ouverte. Le ✕ du mini-lecteur arrête la lecture.
- Lecture en arrière-plan avec notification et commandes de l'écran verrouillé ; utilisable depuis **Android Auto** ; widget d'écran d'accueil.

**Progression et statistiques**
- Position, vitesse et état « lu » enregistrés par livre, y compris sans l'écran du lecteur (service) ou depuis Android Auto.
- Statistiques d'écoute : aujourd'hui, 7 derniers jours, par livre, et temps gagné grâce à la vitesse.
- Reprise depuis **Smart AudioBook Player** : les fichiers `position.sabp.dat` sont lus au scan, et `statistics.xml` peut être importé (icône de téléchargement).

## Installation

1. Ouvre la page **Releases** du dépôt GitHub et télécharge `JDAudiobook-1.0.0.apk` sur ton téléphone.
2. Ouvre le fichier. Android te demandera d'autoriser l'installation depuis cette source (navigateur ou gestionnaire de fichiers) : accepte.
3. Lance l'appli, appuie sur l'icône **dossier** et choisis le dossier qui contient tes livres. Autorise l'accès, y compris en écriture (nécessaire pour la progression et les pochettes).

Android 8.0 (API 26) ou plus récent. Les mises à jour s'installent par-dessus la version précédente, car tous les APK sont signés avec la même clé.

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

L'icône image de la bibliothèque analyse les livres sans pochette, puis cherche sur Internet d'après le nom du dossier, dans cet ordre : **iTunes** (livres audio), **Audible** (France puis États-Unis), **Audiolib**, **Google Books**, **Open Library**. Un résultat n'est gardé que si son titre ressemble assez au nom du livre ; un numéro de tome absent du résultat le fait écarter.

- L'image est enregistrée en `cover.jpg` dans le dossier du livre (donc retrouvée aux scans suivants), avec une copie dans l'appli en secours si l'écriture dans le dossier est refusée.
- Pour un livre « introuvable », le bouton **Lien** permet de coller l'adresse de sa page (Audiolib, Audible, Babelio…) ou d'une image.
- Audiolib est interrogé via l'API de recherche de leur site (`api.hachette.fr`), qui n'est pas documentée : elle peut changer sans prévenir. Le bouton **Lien** reste alors disponible.

## Fichiers écrits dans tes dossiers

| Fichier | Rôle |
|---|---|
| `position.jd.json` | Progression du livre (fichier, position, vitesse, lu/non lu, date), lisible et modifiable à la main. Écrit à la pause, au changement de fichier, toutes les 20 s en lecture et en quittant le lecteur. S'il est plus récent que la sauvegarde locale, il est appliqué à l'ouverture et au rescan. |
| `cover.jpg` | Pochette téléchargée. |

Les fichiers de Smart AudioBook Player (`position.sabp.dat`) ne sont jamais modifiés.

## Vie privée

- Aucune donnée n'est envoyée à l'auteur ; aucun compte, aucune statistique d'usage, aucune publicité.
- Le seul accès réseau est la recherche de pochettes, que tu déclenches toi-même : le **titre du livre** (nom du dossier) est alors envoyé à iTunes (Apple), Audible (Amazon), Audiolib (Hachette), Google Books et Open Library.
- Permissions : lecture en arrière-plan (service de premier plan), notifications, maintien de l'appareil éveillé pendant la lecture, Internet (pochettes uniquement). L'accès aux fichiers passe par le sélecteur de dossier d'Android, limité au dossier que tu choisis.

## Compiler soi-même

Prérequis : JDK 17 et Android SDK (API 34).

```bash
./gradlew assembleDebug      # APK de test : app/build/outputs/apk/debug/
./gradlew assembleRelease    # APK release, signé si une clé est fournie (voir RELEASING.md)
```

Le workflow GitHub Actions (`.github/workflows/build.yml`) compile un APK debug à chaque push. Pour publier une version signée, voir **[RELEASING.md](RELEASING.md)**.

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
| `SabpImport.kt` | Import depuis Smart AudioBook Player |
| `Boost.kt`, `Widget.kt`, `FilePicker.kt`, `Icons.kt`, `Theme.kt` | Gain de volume, widget, sélecteurs, icônes, thème |

## Limites connues

- Pas de signets ni d'égaliseur (retirés volontairement).
- La recherche de pochettes dépend de services tiers et de leurs réponses ; une mauvaise correspondance reste possible sur des titres très courts ou génériques.
- Le nom du dossier doit ressembler au titre du livre pour que la recherche aboutisse.

## Licence

Aucune licence n'est définie pour l'instant : tous droits réservés par défaut. Ajoute un fichier `LICENSE` si tu veux autoriser la réutilisation.

Historique des versions : [CHANGELOG.md](CHANGELOG.md).
