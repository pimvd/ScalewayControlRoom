# Screenshots

Pictures for the App Store listing. `scripts/metadata_to_fastlane.py` copies
them into `fastlane/screenshots/<locale>/`, from where the release workflow
hands them to `fastlane deliver`. With none committed, the workflow leaves the
screenshots in App Store Connect alone.

Put them in `AppStore/screenshots/en-US/`, named so they sort in the order
they should appear: `01-instances.png`, `02-instance.png`, `03-costs.png`.

Take them in the demo (Settings → Try the demo), so no real server names or
costs end up in the listing.

## Size

The app is iPhone only, so one set is enough:

| Display | Portrait | Taken on |
| --- | --- | --- |
| iPhone 6.9" | 1320 × 2868 | iPhone 16 Pro Max, 17 Pro Max |

PNG, no alpha channel, no rounded corners or device frame. Up to ten. App Store
Connect rejects any other size, and Photos may hand out a downscaled copy:
AirDrop the screenshot straight from the phone instead.
