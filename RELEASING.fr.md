# Publier une version signée

🇬🇧 [In English](RELEASING.md)

Un APK Android doit être signé pour s'installer. **Toutes les mises à jour d'une appli doivent être signées avec la même clé** : si tu la perds, les utilisateurs devront désinstaller puis réinstaller (et perdront leurs données locales). Garde donc le fichier `.jks` et ses mots de passe dans un endroit sûr, **en dehors du dépôt** (gestionnaire de mots de passe + copie de secours).

## 1. Créer la clé (une seule fois)

Il faut `keytool`, fourni avec le JDK 17.

```bash
keytool -genkeypair -v -storetype PKCS12 \
  -keystore jdaudiobook.jks \
  -alias jdaudiobook \
  -keyalg RSA -keysize 4096 -validity 10000
```

Réponds aux questions (nom, etc. : tu peux laisser vide) et choisis un mot de passe. Avec le format PKCS12, le mot de passe de la clé est le même que celui du keystore. `*.jks` est dans le `.gitignore`.

**Depuis un téléphone avec Termux :**

```bash
pkg update && pkg install openjdk-17
termux-setup-storage
cd ~ && keytool -genkeypair -v -storetype PKCS12 -keystore jdaudiobook.jks -alias jdaudiobook -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 jdaudiobook.jks > keystore_base64.txt
cp jdaudiobook.jks keystore_base64.txt ~/storage/downloads/
```

Supprime `keystore_base64.txt` une fois les secrets enregistrés : il contient ta clé, protégée seulement par son mot de passe.

## 2. Ajouter les secrets sur GitHub

Dépôt → **Settings → Secrets and variables → Actions → New repository secret** :

| Secret | Valeur |
|---|---|
| `KEYSTORE_BASE64` | le fichier `.jks` encodé en base64 (voir ci-dessous) |
| `KEYSTORE_PASSWORD` | mot de passe du keystore |
| `KEY_ALIAS` | `jdaudiobook` (l'alias choisi à l'étape 1) |
| `KEY_PASSWORD` | mot de passe de la clé (identique à celui du keystore avec PKCS12) |

Encodage en base64 :

```bash
base64 -w0 jdaudiobook.jks      # Linux / Termux
base64 -i jdaudiobook.jks       # macOS
```
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("jdaudiobook.jks"))   # Windows PowerShell
```

## 3. Publier

1. Mettre à jour `versionName` (ex. `1.2.1`) **et augmenter `versionCode`** dans `app/build.gradle.kts` (Android refuse d'installer une version dont le `versionCode` n'est pas supérieur).
2. Mettre à jour `CHANGELOG.md` et `CHANGELOG.fr.md`.
3. Committer, puis créer le tag correspondant et le pousser :

```bash
git tag v1.2.0
git push origin v1.2.0
```

Le workflow vérifie que le tag (`v1.2.0`) correspond à `versionName`, compile l'APK release, vérifie sa signature, puis le publie dans **Releases** sous le nom `JDAudiobook-1.2.0.apk`. Sans les secrets, un tag `v*` fait échouer le workflow avec un message explicite.

## Build signé en local (optionnel)

Dans `~/.gradle/gradle.properties` (jamais dans le dépôt) :

```properties
jd.keystore.file=/chemin/absolu/vers/jdaudiobook.jks
jd.keystore.password=...
jd.key.alias=jdaudiobook
jd.key.password=...
```

puis `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Sans ces valeurs, l'APK release est non signé.
