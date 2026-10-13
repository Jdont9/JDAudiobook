# JD Audiobook Reader

🇫🇷 [Lire en français](README.fr.md)

A simple, fast audiobook player for Android that plays your files (MP3, M4B…) straight from a folder on your phone. No account, no ads, no tracking.

Written in Kotlin with Jetpack Compose and Media3. Version **1.5.2**. The app is available in **English and French** (it follows your phone's language).

> 🤖 **Built by an AI.** The code, documentation and scripts in this repository were written by an artificial intelligence (Claude, by Anthropic) from the requests and feedback of the project's owner, who directed the development and uses the app. Like any software it may contain bugs; it is provided as is, without warranty.

[![Donate](https://img.shields.io/badge/Donate-PayPal-0070BA?logo=paypal&logoColor=white)](https://www.paypal.me/jd02310)

## Features

**Library**
- Actions (statistics, missing covers, import, folder choice, refresh, **Settings**, About) are in a menu hidden behind the **gear icon** at the top right, shown only on demand.
- You pick a root folder; the app scans it and treats every folder that contains audio files as a book.
- Formats: `mp3`, `m4b`, `m4a`, `ogg`, `opus`, `flac`, `wav`. Files are sorted in natural order (`2` before `10`).
- Cover art: an image in the folder (preferably named `cover`, `folder` or `front`), otherwise the artwork embedded in the audio file.
- Missing covers can be searched for on the internet (see below).
- **Search** field (as soon as the library has more than a few books) and a menu choice to sort by **recently played** instead of library order.

**Player**
- Seek back/forward by **10, 15, 30, 45 or 60 s** (menu → Skip duration; also used by the notification and Android Auto), previous/next file, jump to any file of the book.
- **Bookmarks** per book (bookmark icon in the player): add the current position with an optional note, jump back to one, edit its note, delete it.
- Speed from ×0.75 to ×3, sleep timer (10 to 90 min, with a 15 s volume fade-out, or **end of the current chapter / file**; shake the phone to restart a running timer; when it stopped playback, the next resume goes back a few extra minutes — Settings → Rewind after the sleep timer), silence skipping, volume boost from +3 to +12 dB (remembered per book), and a **parametric equalizer** with 11 presets (speech, dialogue, old recordings, night listening, headphone corrections…) or your own 10-band curve, remembered per book.
- Chapters for `m4b`/`m4a` files and for `mp3` files that carry ID3 chapters (`CHAP` frames): previous/next jump chapter by chapter, and the chapter list is in the menu under the player (the file row with the arrow), next to the file list. Bogus chapter lists (a single chapter, or chapters less than a second apart) are ignored.
- The app **opens on the player of the last book you listened to** (paused at your saved position), or on the library if that book is finished; the back button goes to the library.
- **Mini player** at the bottom of the library whenever a book is loaded: cover, title, back (skip duration), play/pause, close. Tapping it reopens the player without reloading anything.
- **Back** (system button, gesture, or the in-app button) returns to the library: playback keeps going and the app stays open. The ✕ on the mini player stops playback.
- **Bookmarks export**: a Share button in the bookmarks list sends them as Markdown text (notes, messages…).
- Background playback with a notification and lock-screen controls; usable from **Android Auto** (a **Resume** entry at the top, and voice or text **search** of your books, e.g. “play Germinal”); the **play button of a Bluetooth headset or a car** resumes your last book even when the app is closed.
- **Home-screen widget** in two sizes that adapt when you resize it: large (rounded cover, book title, current file, time, progress bar, previous / −10 s / play-pause / +10 s / next) and compact (cover, title, previous / play-pause / next, thin progress bar). It follows your system colors (Material You, light and dark) on Android 12+ and the app's red before. Tap the cover or the title to open the app; with nothing loaded, ▶ resumes the last book.

**Settings** (menu → Settings)
- **Chain the next volume**: at the end of a book kept in a series folder, the next unfinished book of that folder starts.
- **Voice compression** (Android 9+): evens out quiet and loud passages (car, noise, night), with a limiter against distortion.
- **System colors (Material You)** (Android 12+): use your wallpaper's colors instead of the app's red.
- **Encrypted backup**: export / import your positions, bookmarks, settings and statistics to one file protected by a password (AES-256-GCM, key from PBKDF2, 600 000 rounds). Nothing goes online; keep the password, it cannot be recovered. See *Privacy and security*.

**Progress and statistics**
- Position, speed and "finished" state are saved per book (a book is marked finished automatically when its last file ends, and its position goes back to the start), even when the player screen is closed (service) or when driven from Android Auto.
- Listening statistics: today, last 7 days, per book, time saved thanks to speed, your **current and longest streak** of days, a 10-week **heat map** and a **CSV export**.
- **Two devices, one folder** (synced by Syncthing, Nextcloud, a cloud drive…): if the folder holds a more recent position that points somewhere clearly different (another file, or more than a minute away), the app asks which one to keep instead of overwriting yours silently.
- Migration from **Smart AudioBook Player**: `position.sabp.dat` files are read during the scan, and `statistics.xml` can be imported (menu → Import statistics).

## Install

1. Open the repository's [**Releases** page](https://github.com/Jdont9/JDAudiobook/releases) and download `JDAudiobook-1.5.2.apk` on your phone.
2. Open the file. Android will ask you to allow installs from that source (your browser or file manager): accept.
3. Launch the app, open the menu (**gear icon**, top right) → **Choose the books folder**. Grant access, including write access (needed for progress and covers).

Requires Android 8.0 (API 26) or newer. Updates install over the previous version because every APK is signed with the same key.

## Updates

**Menu (gear icon) → About → Check for updates.** The app compares its version with the latest GitHub release and, if a newer one exists, offers to open the [download page](https://github.com/Jdont9/JDAudiobook/releases). **It never checks automatically**: nothing is sent until you tap the button, and nothing is downloaded or installed for you — you install the new APK yourself, over the old one.

## How to organise your books

```
Books/
├── Author/
│   ├── Book title/
│   │   ├── 01.mp3
│   │   ├── 02.mp3
│   │   ├── cover.jpg            ← cover (optional)
│   │   └── position.jd.json     ← created by the app
│   └── Another book/
│       └── book.m4b             ← a single m4b file works too
└── ...
```

One folder = one book. The folder name is used as the title and as the basis for the cover search.

## Missing covers

**Menu (gear icon) → Find missing covers** scans for books without a cover, then searches the internet from the folder name, in this order: **iTunes** (audiobooks), **Audible** (France, then US), **Google Books**, **Open Library**, and **Audiolib** only if you tick the box in the dialog (it is off by default, see below). A result is kept only if its title is close enough to the book name; a volume number that is missing from the result makes it rejected.

- The image is saved as `cover.jpg` in the book's folder (so it is found again on later scans), with a backup copy inside the app in case writing to the folder is refused.
- For a book that is "not found", the **Link** button lets you paste the address of its page (Audiolib, Audible, Babelio…) or of an image: the app picks up the cover (`og:image` tag).
- A book that was not found is not searched again for 7 days (the **Link** button always works).
- Audiolib is queried through the site's own search API (`api.hachette.fr`), which is undocumented, may change without notice and is not meant for third-party apps: that is why it is **opt-in** (checkbox in the dialog). The **Link** button remains available as a fallback.

## Files written in your folders

| File | Purpose |
|---|---|
| `position.jd.json` | The book's progress (file, position, speed, finished or not, bookmarks with their notes, date); human-readable and hand-editable. Written on pause, on file change, every 60 s while playing and when leaving the player. If it is newer than the local save, it is applied when the book is opened and on rescan. |
| `cover.jpg` | Downloaded cover. |

Smart AudioBook Player's `position.sabp.dat` files are never modified.

## Privacy and security

- Nothing is sent to the author; no account, no usage analytics, no ads.
- The only network accesses are ones you trigger yourself: the cover search (the **book title**, i.e. the folder name, is sent to iTunes (Apple), Audible (Amazon), Google Books and Open Library, and to Audiolib (Hachette) only if you ticked it) and the update check (a request to `api.github.com`, like any web request it exposes your IP address to GitHub). Nothing runs in the background.
- Permissions: background playback (foreground service), notifications, keeping the device awake while playing (the CPU only, and only while audio is playing), internet (cover search and update check only), vibration (short confirmation when you shake the phone to restart the sleep timer). File access goes through Android's folder picker and is limited to the folder you choose.
- Other apps cannot browse your library or control playback: the media service only accepts the app itself, the system (lock screen, Bluetooth), Android Auto, Google Assistant, Android Automotive and Wear OS. The widget's commands carry a secret token that never leaves the app.
- Android backup: if Android's backup is on for your phone, your **progress, speeds, bookmarks and listening statistics** are included in your Google backup (never the audio, the covers or the library), and only if the backup is end-to-end encrypted. To opt out, turn off backup in Android's settings, and use the app's own **encrypted backup** (Settings) instead.
- Network hardening: **HTTPS only** (cleartext is forbidden by a network security configuration), requests to local or private addresses (router, home network, `localhost`) are refused, including after a redirect, redirects are limited to 5 and every download has a size limit. A pasted link therefore cannot make the app probe your network.
- Files from a shared or synced folder are never trusted: the size of `position.jd.json`, `position.sabp.dat` and cover images is capped and absurd images are ignored, so a huge or crafted file cannot crash the app. A backup file is only accepted if it decrypts with your password, and only known settings of the right type and range are restored.
- Covers served to the notification, Android Auto and the widget have **unguessable file names** (128 bits), so another app cannot find out which books you have by guessing names.
- Verify your download: *Menu → About* shows the **SHA-256 fingerprint of the certificate that signed your APK**; compare it with the one on the release page. Releases also carry a **build provenance attestation**: `gh attestation verify JDAudiobook-X.Y.Z.apk --repo Jdont9/JDAudiobook`.

## Build it yourself

Requirements: JDK 17 and the Android SDK (API 34).

```bash
./gradlew assembleDebug      # test APK: app/build/outputs/apk/debug/
./gradlew assembleRelease    # release APK, signed if a key is provided (see RELEASING.md)
```

The GitHub Actions workflow (`.github/workflows/build.yml`) builds a debug APK on every push. To publish a signed release, see **[RELEASING.md](RELEASING.md)**.

## Translations

User-facing text lives in Android string resources: `app/src/main/res/values/strings.xml` (English, default) and `values-fr/strings.xml` (French). To add a language, copy `strings.xml` into a new `values-xx/` folder, translate it, and add the language to `res/xml/locales_config.xml`.

## Code layout

`app/src/main/kotlin/fr/jd/audiobooks/`:

| File | Contents |
|---|---|
| `MainActivity.kt` | Activity, shared state and navigation |
| `LibraryScreen.kt`, `PlayerScreen.kt`, `StatsScreen.kt` | Screens: library (+ mini player), player, statistics |
| `PlaybackService.kt` | Background playback, media session, Android Auto (browse, search, resume), progress saving, sleep timer |
| `Store.kt` | Root folder, library scan, cache, statistics |
| `Progress.kt` | The `position.jd.json` file |
| `Media.kt` | Local covers, chapters, formatting |
| `CoverFetch.kt` | Cover search and download, dialog |
| `About.kt` | About dialog and on-demand update check |
| `SabpImport.kt` | Import from Smart AudioBook Player |
| `Skip.kt`, `Dialogs.kt` | Skip duration, bookmark and skip dialogs |
| `Util.kt` | Shared helpers: logging, safe playback speed, bounded reads, a screen-refresh tick that pauses in the background |
| `CoverProvider.kt` | Serves covers by URI to the notification, Android Auto and the widget |
| `Boost.kt`, `Dynamics.kt`, `FilePicker.kt`, `Icons.kt`, `Theme.kt` | Volume boost, voice compression, pickers, icons, theme (+ Material You option) |
| `Widget.kt` (+ `res/layout/widget_jd*.xml`, `res/values*/widget_colors.xml`) | Home-screen widget, large and compact layouts |
| `SafeHttp.kt` | Outgoing requests: HTTPS only, no private addresses, bounded redirects and sizes |
| `Settings.kt`, `Backup.kt` | Settings dialog; encrypted backup (AES-256-GCM, PBKDF2) and validated restore |
| `Search.kt`, `StatsMath.kt`, `Bookmarks.kt` | Book search (Android Auto, voice), streaks / heat map / CSV, bookmarks export |
| `Shake.kt` | Shake detection for the sleep timer (accelerometer, only while a timer runs) |
| `Eq.kt`, `EqDialog.kt` | Equalizer: biquad filters in the audio output chain, Equalizer APO preset parser, preset list and picker |

Note: source-code comments are mostly in French.

## Known limitations

- The custom equalizer curve is one shared curve (10 fixed bands), not one per book; there is no import of Equalizer APO files yet.
- The app does not group the library by series (the series folder name is shown under each title); “Chain the next volume” follows the folder order.
- Progress, statistics and bookmarks are still stored in Android preferences, not a database.
- Cover search depends on third-party services and what they return; a wrong match is still possible for very short or generic titles.
- The folder name has to look like the book's title for the cover search to succeed.

## License

[GNU General Public License v3.0 or later](LICENSE) (GPL-3.0-or-later). This is free software: you may use it, study it, modify it and share it, including commercially, as long as any version you distribute stays under the same license with its source code available.

## Support the project

The app is free and will stay free. If you like it and want to thank its author, you can donate through [PayPal](https://www.paypal.me/jd02310). It is entirely optional: a donation gives no particular support entitlement.

Version history: [CHANGELOG.md](CHANGELOG.md).
