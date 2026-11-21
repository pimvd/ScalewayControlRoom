# Scaleway ControlRoom

An iPhone app to start and stop Scaleway instances and see what an organization
costs this month. It talks straight to Scaleway's API with the user's own API
keys; there is no backend.

Independent app, not made or endorsed by Scaleway.

## Develop

```sh
npm install
npx expo start        # then open in Expo Go or a development build
npm test              # unit tests
npx tsc --noEmit      # type check
```

The demo key (Settings → Try the demo) gives the whole app made-up data, so no
Scaleway account is needed to work on it.

## Release

- **CI** (`.github/workflows/ci.yml`) runs on every push to master: type check,
  tests, Expo dependency check, metadata validation, and an unsigned iOS
  simulator build on macOS. No secrets needed.
- **Release to TestFlight** (`.github/workflows/release.yml`) is started by
  hand. It archives, uploads to TestFlight and pushes the store text and
  screenshots. It needs the `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_P8`
  secrets; see the workflow's header.
- Store text lives in `AppStore/`, checked by `scripts/validate_metadata.py`
  and converted by `scripts/metadata_to_fastlane.py`.
- What has to be done by hand in App Store Connect: `MANUAL_METADATA.md`.
- The privacy and support pages are in `website/`.
