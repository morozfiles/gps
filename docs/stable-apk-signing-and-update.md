## Permanent certificate generated on 2026-11-18

The permanent signing identity has already been generated outside the
GitHub repository and packaged privately for the user. Do **not**
generate another key when setting up the provided bundle.
The public SHA-256 signer certificate fingerprint is:

`0F:97:87:85:27:A8:C2:68:70:5C:3B:EB:BF:45:70:D7:80:34:DC:26:15:9E:DB:56:D7:87:43:CF:D2:02:78:4E`

The build workflow pins this **public certificate digest** to prevent
accidentally publishing updates signed with a DIFFERENT key.

The private key and password are **not** stored in the repository;
the repo admin must paste the two secret values into
https://github.com/morozfiles/gps/settings/secrets/actions using the
private backup archive that was delivered directly to them.

**Do not uninstall the existing app until BOTH secrets are configured,
a new APK is built with permanent signing, and offline data have been
backed up or the user agrees to redownload it.**

---

# Android APK upgrades: stable signing and offline-map backup

## Why the package conflict appears

OBD GPS Navigator uses the Android package ID `ua.obdgps.nav`.
Early CI releases were built as `assembleDebug` on fresh GitHub-hosted
runners. Android Gradle creates a fresh signing identity when the user
debug keystore is absent. Therefore consecutive downloaded APKs may
have DIFFERENT signer certificates although their package ID is the
same. Android refuses an in-place update with a different signer.

**There is no standard non-root installer flag that safely bypasses
this check.** Merely increasing `versionCode`, renaming the APK or
running `adb install -r` cannot fix the mismatch. Re-signing an old
APK requires the ORIGINAL private key, which is not present in the APK.

## Fix for all FUTURE builds: permanent signer

The build now supports a permanent Android PKCS12 signing key for the
`debug` variant. This is a one-time setup in GitHub Actions Secrets:

1. On your own Windows computer, install JDK 17+ (for `keytool.exe`).
2. Run `tools/create-stable-signing-key.ps1`, e.g. open PowerShell:
   `powershell -ExecutionPolicy Bypass -File tools/create-stable-signing-key.ps1`
   (or pass the full file path).
3. Choose a STRONG password and save it securely. A new private
   keystore is created at `%USERPROFILE%\OBD-GPS-Signing\OBD-GPS-Navigator-Signing.p12`.
   The script writes a single-line Base64 representation beside it,
   `OBD-GPS-Navigator-Signing.base64.txt`.
4. Go to the GitHub repository:
   **Settings > Secrets and variables > Actions > New repository secret**.
   Add:
   - `OBDGPS_KEYSTORE_B64`: **entire** text contents of
     `OBD-GPS-Navigator-Signing.base64.txt`.
   - `OBDGPS_SIGNING_PASSWORD`: the original keystore password.
5. Do NOT commit/share either of the generated files or the password.
   Back up the permanent `.p12` file and password in a secure place.
6. Trigger a new GitHub Actions build. The workflow's
   **Prepare permanent APK signing key** step must say
   `Permanent APK signing key loaded from GitHub Actions secrets.`
   It also prints the signer certificate SHA-256 for verification.
   Every later build configured with the SAME keystore will have
   the SAME APK signature and can update an earlier permanent-signed
   APK as long as the package ID stays `ua.obdgps.nav` and
   the `versionCode` increases.

If no secrets are set, the build remains installable, but logs a
warning: **temporary signature, no guaranteed updates**.
If only one secret is set, the workflow fails instead of silently
producing a differently signed APK.

**Important:** the very FIRST permanently signed APK still cannot
update an existing APK signed by the earlier temporary key. Plan one
last migration after safely backing up downloaded offline data.

## Safely migrate without losing Ukraine offline maps

**Before any uninstall**, back up the installed app's private files.
Existing downloaded map/POI paths are:
- `files/offline-vectors/selected.map`
- `files/offline-vectors/selected.poi`

On Windows use `tools/backup-offline-ukraine.cmd`. It requires
official Android platform-tools (`adb`), enabled USB/Wi-Fi debugging,
the Android device authorized for ADB, and the OLD debug APK
still installed. It uses `adb exec-out run-as ua.obdgps.nav` to copy
the map and address file to the Windows computer without changing
the installed app.

Check the files in `UkraineOfflineBackup` have reasonable nonzero
sizes. In particular nationwide maps may be many hundreds of MB.

Only after confirming backups should you uninstall the incompatible
old APK, install the APK signed with the NEW PERMANENT key, copy the
backed-up `.map` and `.poi` files into Android Downloads, and use
**Offline maps > Import Ukraine .map/.poi** to restore them.
Check map and address search before discarding the computer backup.

If `run-as` fails, **do not uninstall** the app until another
reliable backup has been found. ADB backup is not guaranteed on
every Android build; `allowBackup=false` in the app means Android's
standard automatic backup cannot be relied on for these files.

## Security

Never use a public/repository-committed signing key as the permanent
identity. The key is effectively the identity of the app and must
remain private; losing it causes the same update conflict in the
future. Prefer GitHub Actions **Secrets** to plain source/config
files or repository variables.
