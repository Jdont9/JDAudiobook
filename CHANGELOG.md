# Changelog

🇫🇷 [En français](CHANGELOG.fr.md)

## 1.3.0
**New**
- **Chapters in MP3 files.** MP3s that carry ID3v2.3/2.4 chapters (`CHAP` frames, the kind written by Mp3tag or Chapter and Verse) now show their chapter list in the player, with chapter-aware previous/next and a "Chapter n/N" line. A chapter without a title is shown as "Chapter N". The same safety limits as for `m4b` apply (bounded sizes, a suspicious tag simply shows no chapters).
- Not covered: the `CTOC` table of contents is ignored (chapters are listed by start time, so nested chapters appear as a flat list), ID3v2.2 tags have no chapters, and compressed or encrypted frames are skipped.

## 1.2.2
Robustness and security release, following a code audit. No new feature.

**Fixes**
- A bad value in `position.jd.json` (speed of 0, negative or not a number; negative position; a date far in the future) could make the app crash every time that book was opened. Values read from that file, and from the saved progress, are now checked and brought back to something usable.
- Opening a book can no longer crash the app: any error is logged and reported with a message.
- Playback no longer stutters or stops with the screen off on some phones: the player now keeps the CPU awake while playing (the wake lock the README already promised).
- Chapters (`m4b`/`m4a`): a corrupted or booby-trapped file can no longer exhaust memory or freeze the app. Sizes and counters read from the file are bounded, reads are completed, and a suspicious file simply shows no chapters.
- Replacing a cover now overwrites `cover.jpg` instead of creating `cover (1).jpg` next to it.
- A cover found while the library was being scanned is no longer lost when the scan ends.
- The library cache can no longer be corrupted by two scans (app and Android Auto) writing at once: scans now run one at a time.
- Texts that ignored the app language (the library's root folder name, the app name in the widget and in Android Auto) are now translated.

**Security and privacy**
- The playback service (exported for Android Auto and the notification) no longer accepts just any app: only the app itself, trusted system components, Android Auto, Google Assistant, Android Automotive, Bluetooth and Wear OS can browse the library or control playback. Refused clients are logged.
- The widget's play/next/previous commands carry a secret token; the same commands sent by another app are ignored. (Widgets already on your home screen pick up the token the next time the player changes state; if a widget button does nothing right after updating, open the app once.)
- The README now says that Android backup includes progress, bookmarks and statistics, and how to turn it off.
- The update check ignores answers larger than 1 MB.
- Leaving a library folder for another one now releases the old folder's access permission.

**Performance**
- Positions and statistics moved to their own files: saving the position every 5 s used to rewrite one large file holding all settings, bookmarks, durations and statistics. Existing data is moved automatically on first launch (and from a restored backup).
- Daily statistics older than 400 days are grouped by month, so the statistics no longer grow forever (totals stay exact; only the day-by-day detail of old data goes).
- The library list (filter, search, sort) is no longer recomputed at each redraw.
- The screen no longer refreshes twice a second while the app is in the background.

**Other**
- Errors that were silently swallowed are now written to the Android log (tag `JDAudiobook`).
- 10 new unit tests (safe speed, progress-file sanitising, corrupted saves, chapter parsing including oversized and truncated files); the build workflow now also runs lint and publishes its report.
- Build workflow hardened: the Gradle wrapper is verified, the build job is read-only, and only a separate job can publish a release.
- Not changed in this release: the Audiolib cover source, dependency and target SDK versions (Dependabot proposes updates), and the size of `MainActivity.kt`.

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
