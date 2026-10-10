# JD Audiobook Reader

🇫🇷 [Lire en français](README.fr.md)

A simple, fast audiobook player for Android that plays your files (MP3, M4B…) straight from a folder on your phone. No account, no ads, no tracking.

Written in Kotlin with Jetpack Compose and Media3. Version **1.3.1**. The app is available in **English and French** (it follows your phone's language).

> 🤖 **Built by an AI.** The code, documentation and scripts in this repository were written by an artificial intelligence (Claude, by Anthropic) from the requests and feedback of the project's owner, who directed the development and uses the app. Like any software it may contain bugs; it is provided as is, without warranty.

[![Donate](https://img.shields.io/badge/Donate-PayPal-0070BA?logo=paypal&logoColor=white)](https://www.paypal.me/jd02310)

## Features

**Library**
- Actions (statistics, missing covers, import, folder choice, refresh, About) are in a menu hidden behind the **gear icon** at the top right, shown only on demand.
- You pick a root folder; the app scans it and treats every folder that contains audio files as a book.
- Formats: `mp3`, `m4b`, `m4a`, `ogg`, `opus`, `flac`, `wav`. Files are sorted in natural order (`2` before `10`).
- Cover art: an image in the folder (preferably named `cover`, `folder` or `front`), otherwise the artwork embedded in the audio file.
- Missing covers can be searched for on the internet (see below).
- **Search** field (as soon as the library has more than a few books) and a menu choice to sort by **recently played** instead of library order.

**Player**
- Seek back/forward by **10, 15, 30, 45 or 60 s** (menu → Skip duration; also used by the notification and Android Auto), previous/next file, jump to any file of the book.
- **Bookmarks** per book (bookmark icon in the player): add the current position, jump back to one, delete it.
- Speed from ×0.75 to ×3, sleep timer (10 to 90 min, with a 15 s volume fade-out, or **end of the current file**), silence skipping, volume boost from +3 to +12 dB (remembered per book).
- Chapters for `m4b`/`m4a` files and for `mp3` files that carry ID3 chapters (`CHAP` frames): previous/next jump chapter by chapter, and the chapter list is in the menu under the player (the file row with the arrow), next to the file list. Bogus chapter lists (a single chapter, or several at the same instant) are ignored.
- **Mini player** at the bottom of the library whenever a book is loaded: cover, title, back (skip duration), play/pause, close. Tapping it reopens the player without reloading anything.
- **Back** (system button, gesture, or the in-app button) returns to the library: playback keeps going and the app stays open. The ✕ on the mini player stops playback.
- Background playback with a notification and lock-screen controls; usable from **Android Auto**; home-screen widget.

**Progress and statistics**
- Position, speed and "finished" state are saved per book (a book is marked finished automatically when its last file ends, and its position goes back to the start), even when the player screen is closed (service) or when driven from Android Auto.
- Listening statistics: today, last 7 days, per book, and time saved thanks to speed.
- Migration from **Smart AudioBook Player**: `position.sabp.dat` files are read during the scan, and `statistics.xml` can be imported (menu → Import statistics).

## Install

1. Open the repository's [**Releases** page](https://github.com/Jdont9/JDAudiobook/releases) and download `JDAudiobook-1.3.1.apk` on your phone.
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

**Menu (gear icon) → Find missing covers** scans for books without a cover, then searches the internet from the folder name, in this order: **iTunes** (audiobooks), **Audible** (France, then US), **Audiolib**, **Google Books**, **Open Library**. A result is kept only if its title is close enough to the book name; a volume number that is missing from the result makes it rejected.

- The image is saved as `cover.jpg` in the book's folder (so it is found again on later scans), with a backup copy inside the app in case writing to the folder is refused.
- For a book that is "not found", the **Link** button lets you paste the address of its page (Audiolib, Audible, Babelio…) or of an image: the app picks up the cover (`og:image` tag).
- A book that was not found is not searched again for 7 days (the **Link** button always works).
- Audiolib is queried through the site's own search API (`api.hachette.fr`), which is undocumented and may change without notice. The **Link** button remains available as a fallback.

## Files written in your folders

| File | Purpose |
|---|---|
| `position.jd.json` | The book's progress (file, position, speed, finished or not, date); human-readable and hand-editable. Written on pause, on file change, every 60 s while playing and when leaving the player. If it is newer than the local save, it is applied when the book is opened and on rescan. |
| `cover.jpg` | Downloaded cover. |

Smart AudioBook Player's `position.sabp.dat` files are never modified.

## Privacy

- Nothing is sent to the author; no account, no usage analytics, no ads.
- The only network accesses are ones you trigger yourself: the cover search (the **book title**, i.e. the folder name, is sent to iTunes (Apple), Audible (Amazon), Audiolib (Hachette), Google Books and Open Library) and the update check (a request to `api.github.com`, like any web request it exposes your IP address to GitHub). Nothing runs in the background.
- Permissions: background playback (foreground service), notifications, keeping the device awake while playing (the CPU only, and only while audio is playing), internet (cover search and update check only). File access goes through Android's folder picker and is limited to the folder you choose.
- Other apps cannot browse your library or control playback: the media service only accepts the app itself, the system (lock screen, Bluetooth), Android Auto, Google Assistant, Android Automotive and Wear OS. The widget's commands carry a secret token that never leaves the app.
- Android backup: if Android's backup is on for your phone, your **progress, speeds, bookmarks and listening statistics** are included in your Google backup (never the audio, the covers or the library). To opt out, turn off backup in Android's settings.

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
| `MainActivity.kt` | Screens (library, player, mini player, statistics) and navigation |
| `PlaybackService.kt` | Background playback, media session, Android Auto, progress saving, sleep timer |
| `Store.kt` | Root folder, library scan, cache, statistics |
| `Progress.kt` | The `position.jd.json` file |
| `Media.kt` | Local covers, chapters, formatting |
| `CoverFetch.kt` | Cover search and download, dialog |
| `About.kt` | About dialog and on-demand update check |
| `SabpImport.kt` | Import from Smart AudioBook Player |
| `Skip.kt`, `Dialogs.kt` | Skip duration, bookmark and skip dialogs |
| `Util.kt` | Shared helpers: logging, safe playback speed, bounded reads, a screen-refresh tick that pauses in the background |
| `CoverProvider.kt` | Serves covers by URI to the notification, Android Auto and the widget |
| `Boost.kt`, `Widget.kt`, `FilePicker.kt`, `Icons.kt`, `Theme.kt` | Volume boost, widget, pickers, icons, theme |

Note: source-code comments are mostly in French.

## Known limitations

- No equalizer (removed on purpose).
- Cover search depends on third-party services and what they return; a wrong match is still possible for very short or generic titles.
- The folder name has to look like the book's title for the cover search to succeed.

## License

[GNU General Public License v3.0 or later](LICENSE) (GPL-3.0-or-later). This is free software: you may use it, study it, modify it and share it, including commercially, as long as any version you distribute stays under the same license with its source code available.

## Support the project

The app is free and will stay free. If you like it and want to thank its author, you can donate through [PayPal](https://www.paypal.me/jd02310). It is entirely optional: a donation gives no particular support entitlement.

Version history: [CHANGELOG.md](CHANGELOG.md).
