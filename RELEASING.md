# Publishing a signed release

🇫🇷 [En français](RELEASING.fr.md)

An Android APK must be signed to install. **Every update of an app must be signed with the same key**: if you lose it, users will have to uninstall and reinstall (and lose local data). Keep the `.jks` file and its passwords somewhere safe, **outside the repository** (password manager + backup copy).

## 1. Create the key (once)

You need `keytool`, shipped with JDK 17.

```bash
keytool -genkeypair -v -storetype PKCS12 \
  -keystore jdaudiobook.jks \
  -alias jdaudiobook \
  -keyalg RSA -keysize 4096 -validity 10000
```

Answer the questions (name, etc. — you may leave them blank) and choose a password. With the PKCS12 format the key password is the same as the keystore password. `*.jks` is in `.gitignore`.

**On a phone with Termux:**

```bash
pkg update && pkg install openjdk-17
termux-setup-storage
cd ~ && keytool -genkeypair -v -storetype PKCS12 -keystore jdaudiobook.jks -alias jdaudiobook -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 jdaudiobook.jks > keystore_base64.txt
cp jdaudiobook.jks keystore_base64.txt ~/storage/downloads/
```

Delete `keystore_base64.txt` once the secrets are saved: it contains your key, only protected by its password.

## 2. Add the secrets on GitHub

Repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the `.jks` file encoded as base64 (see below) |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `jdaudiobook` (the alias chosen in step 1) |
| `KEY_PASSWORD` | the key password (same as the keystore password with PKCS12) |

Base64 encoding:

```bash
base64 -w0 jdaudiobook.jks      # Linux / Termux
base64 -i jdaudiobook.jks       # macOS
```
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("jdaudiobook.jks"))   # Windows PowerShell
```

## 3. Publish

1. Update `versionName` (e.g. `1.1.1`) **and increase `versionCode`** in `app/build.gradle.kts` (Android refuses to install a version whose `versionCode` is not higher).
2. Update `CHANGELOG.md` and `CHANGELOG.fr.md`.
3. Commit, then create the matching tag and push it:

```bash
git tag v1.1.0
git push origin v1.1.0
```

The workflow checks that the tag (`v1.1.0`) matches `versionName`, builds the release APK, verifies its signature, then publishes it under **Releases** as `JDAudiobook-1.1.0.apk`. Without the secrets, a `v*` tag makes the workflow fail with an explicit message.

## Signed build locally (optional)

In `~/.gradle/gradle.properties` (never in the repository):

```properties
jd.keystore.file=/absolute/path/to/jdaudiobook.jks
jd.keystore.password=...
jd.key.alias=jdaudiobook
jd.key.password=...
```

then `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Without these values, the release APK is unsigned.
