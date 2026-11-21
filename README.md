# Scaleway ControlRoom

An iPhone app to start and stop Scaleway instances, read serverless logs and
see what an organization costs this month. It talks straight to Scaleway's API
with the user's own API keys; there is no backend.

Independent app, not made or endorsed by Scaleway.

## Develop

Open `ScalewayControlRoom.xcodeproj` in Xcode 26 or later and run. Signing is
automatic with the team in the project. Running from Xcode uses
`StoreKit/ControlRoom.storekit`, so the Pro subscription can be bought from a
local test store. Settings → Try the demo gives the whole app made-up data.

The logic lives in `Packages/ControlRoomKit`:

```sh
cd Packages/ControlRoomKit && swift test
```

## Release

- **CI** (`.github/workflows/ci.yml`) runs on every push to master: metadata
  and subscription checks, the package tests, and a simulator build.
- **Release to TestFlight** (`.github/workflows/release.yml`) is started by
  hand. It archives, uploads to TestFlight, pushes the store text and
  screenshots, and optionally syncs the subscriptions. It needs the
  `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_P8` secrets; see the workflow.
- **Screenshots** (`.github/workflows/screenshots.yml`) takes the App Store
  screenshots of the demo in the simulator and commits them.
- Store text lives in `AppStore/`. What has to be done by hand in App Store
  Connect: `MANUAL_METADATA.md`. The website is in `website/`.
