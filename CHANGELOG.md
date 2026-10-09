# Changelog

🇫🇷 [En français](CHANGELOG.fr.md)

## 1.2.1
**Fixes**
- Rotating the screen no longer closes the player (open book, player/stats screen, search and sort are kept; the book is reloaded if the process was killed).
- Android Auto no longer freezes when loading a library fails: the request now ends with an error instead of waiting forever.
- Covers are no longer copied into every file of a book's playlist (300 files = 300 copies); they are served by URI, which also lightens the notification, the lock screen and Android Auto.
- The automatic rewind on resume no longer applies when you moved the position yourself while paused (e.g. picking a chapter).
- The progress bar only seeks when you release it, instead of at every finger movement.
- The "skip silences" button reflects the real state when the player is reopened.
- Opening a book no longer waits forever if the playback service fails to start (an error message is shown after 10 s).
- Notification permission is only requested when it has not been granted yet.

**New**
- **Bookmarks** per book.
- **Skip duration** setting (10, 15, 30, 45 or 60 s), also used by the notification, the lock screen and Android Auto.
- Sleep timer: **end of the current file** option and a 15 s **volume fade-out** before the timer stops playback.
- **Search** field and **recently played** sort in the library.
- A book is marked **finished** automatically when its last file ends (position back to the start).
- The home-screen widget shows the cover and resumes the last book when the player is empty.
- A book whose cover was not found is not searched again for 7 days.

**Performance**
- The library cache moves from preferences to a file, is kept in memory and is no longer read on the main thread at startup.
- The scan no longer tries to open a guessed Smart AudioBook Player file for every book (only for the first books, until one is found), and the leftover diagnostic code was removed.
- `position.jd.json` is written every 60 s instead of 20 s while playing (still on pause and file change), to spare synced folders.

**Other**
- Cover downloads are capped at 10 MB.
- Android backup now only covers progress, bookmarks and statistics.
- Unit tests for the pure helpers (natural order, file names, cover matching), run by the build workflow; Dependabot enabled for dependencies and GitHub Actions.

## 1.2.0
- **New**: library actions now live in a menu behind a gear icon (shown only on demand).
- **New**: About dialog showing the version, with an on-demand update check (never automatic).

## 1.1.0
- **New**: the app is available in English and French (follows the phone's language; per-app language choice on Android 13+).
- **License**: the project is now released under GPL-3.0-or-later.
- Documentation (README, release guide, changelog) in English and French; AI-authorship notice and donation link added.

## 1.0.0
First signed release.

- Folder-based library, player (speed, sleep timer, silence skipping, volume boost, m4b/m4a chapters), widget, Android Auto access.
- Progress saved in `position.jd.json` next to the audio files; migration from Smart AudioBook Player.
- Listening statistics.
- Mini player at the bottom of the library whenever a book is loaded.
- The system back gesture/button returns to the library (playback continues) instead of closing the app.
- Search and download of missing covers (iTunes, Audible, Audiolib, Google Books, Open Library) and adding a cover from a pasted link.
