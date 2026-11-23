# Scaleway ControlRoom

An iPhone and Android app to start and stop Scaleway instances, read
serverless logs and see what an organization costs this month. It talks
straight to Scaleway's API with the user's own API keys; there is no backend.

Independent app, not made or endorsed by Scaleway.

- `ios/`: the iPhone app (SwiftUI) and its App Store pipeline.
- `android/`: the Android app (Kotlin, Jetpack Compose) and its Play Store files.
- `website/`: the marketing, support and privacy pages, shared by both.

## iPhone

Open `ios/ScalewayControlRoom.xcodeproj` in Xcode 26 or later and run.
Signing is automatic with the team in the project. Running from Xcode uses
`ios/StoreKit/ControlRoom.storekit`, so the Pro subscription can be bought
from a local test store. Settings → Try the demo gives the whole app made-up
data.

The logic lives in `ios/Packages/ControlRoomKit`:

```sh
cd ios/Packages/ControlRoomKit && swift test
```

## Android

Open `android/` in Android Studio, or build from the command line with JDK 21
and the Android SDK:

```sh
cd android
./gradlew :kit:test :app:testDebugUnitTest   # logic tests, and every screen of the demo
./gradlew :app:installDebug                   # on a connected phone or emulator
./gradlew :app:recordRoborazziDebug           # Play Store screenshots
```

The app is published as a signed APK on this repository's GitHub Releases,
with every feature free; see `android/DISTRIBUTION.md`. Debug builds sell Pro
from a local test store. Publishing on Google Play later:
`android/PLAY_STORE.md`.

## Release

- **CI** (`.github/workflows/ci.yml`) runs on every push: store metadata
  checks, the iOS package tests and simulator build, and the Android tests
  and debug build.
- **Release to TestFlight** (`release.yml`) is started by hand. It archives,
  uploads to TestFlight, pushes the store text and screenshots, and
  optionally syncs the subscriptions. It needs the `ASC_KEY_ID`,
  `ASC_ISSUER_ID` and `ASC_KEY_P8` secrets; see the workflow.
- **Screenshots** (`screenshots.yml`) takes the App Store screenshots of the
  demo in the simulator and commits them.
- **Android release** (`android-release.yml`) publishes the signed APK as a
  GitHub Release, or builds a Play bundle (its upload to Google Play is not
  set up yet).
- **Android screenshots** (`android-screenshots.yml`) renders the Play Store
  screenshots and commits them.
- Store text lives in `ios/AppStore/` and `android/PlayStore/`. What has to
  be done by hand: `ios/MANUAL_METADATA.md` and `android/PLAY_STORE.md`.
