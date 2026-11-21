# Screenshots

Pictures for the App Store listing. `scripts/metadata_to_fastlane.py` copies
them into `fastlane/screenshots/<locale>/`, from where the release workflow
hands them to `fastlane deliver`. With none committed, the workflow leaves the
screenshots in App Store Connect alone.

The **Screenshots** workflow takes them: it builds the app for the iOS
simulator, launches it once per screen with a `-screenshotRoute` argument that
turns on the demo and opens that screen (`scripts/screenshots.sh`), and commits
the pictures to
`AppStore/screenshots/en-US/`. They sort in the order they appear: instances,
an instance, serverless, logs, costs, the instance setup guide and a metric
chart. The website
deploy shows them on the marketing page too.

Taking them by hand works as well: use the demo, so no real server names or
costs end up in the listing.

## Size

The app is iPhone only, so one set is enough:

| Display | Portrait | Taken on |
| --- | --- | --- |
| iPhone 6.9" | 1320 × 2868 | iPhone 16 Pro Max, 17 Pro Max |

PNG, no alpha channel, no rounded corners or device frame. Up to ten. App Store
Connect rejects any other size, and Photos may hand out a downscaled copy:
AirDrop the screenshot straight from the phone instead.
