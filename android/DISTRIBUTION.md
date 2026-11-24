# Distributing the Android app on GitHub

The Android app is published as a signed APK on this repository's GitHub
Releases. That build has every feature free: Google Play Billing only works
for apps installed from Google Play. Users install it by hand, or with
[Obtainium](https://obtainium.imranr.dev/), which also keeps it up to date.
The install guide for users is the Android section of `website/support.html`.

## Once: the signing key

Every release must be signed with the same key, or phones refuse the update.
Lose the key and every user has to uninstall and install again.

1. Make the key (any machine with Java; on a Mac, `brew install openjdk` gives
   `keytool`):
   ```sh
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   base64 -i upload.jks | tr -d '\n'   # this is UPLOAD_KEYSTORE_BASE64
   ```
2. Add four repository secrets (Settings → Secrets and variables → Actions):
   `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`
   (`upload`) and `UPLOAD_KEY_PASSWORD`.
3. Keep `upload.jks` and its passwords somewhere safe besides GitHub, for
   example in a password manager. GitHub secrets cannot be read back.
4. Register the app with Google's Android developer verification: in the
   Play Console (or Android Developer Console), register the package name
   `com.pvd.scwcontrolroom` with this key's certificate. Android checks this
   before installing sideloaded apps: in a few countries since September
   2026, worldwide from 2027. The certificate's SHA-256 is in every release's
   notes, or:
   ```sh
   keytool -list -v -keystore upload.jks -alias upload | grep SHA256
   ```

The same key is the upload key for Google Play (`PLAY_STORE.md`). Play
re-signs apps with its own key, so moving from the GitHub version to the
Play version means installing once more.

## Each release

Run the **Android release** workflow with channel `github`. It runs the tests,
builds the APK with `-Pstore=github`, checks it is signed, and creates the
release `android-v<version>` with the APK, its SHA-256 and install notes.
The version is the iPhone app's version plus the build number (the commit
count), for example `1.0.57`, so each release has a version of its own.
