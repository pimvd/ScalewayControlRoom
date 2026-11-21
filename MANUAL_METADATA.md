# Manual App Store Connect setup

Everything the release pipeline **cannot** push, and has to be filled in by hand
in App Store Connect. The pipeline covers the rest: `AppStore/app.json` and
`AppStore/metadata/en-US.json` hold the store text, `AppStore/screenshots/`
holds the pictures, and the "Release to TestFlight" workflow sends both with
`fastlane deliver`.

## 0. Create the app record

App Store Connect → Apps → **+** → New App: iOS, name **Scaleway ControlRoom**,
primary language English (U.S.), bundle ID `com.pvd.controlroom`, SKU
`controlroom`. If the name is refused, try "ControlRoom for Scaleway" and
change `name` in `AppStore/metadata/en-US.json` and `app.json` to match.

## 1. App Review Information — do this before the first metadata push

`deliver` reads the App Review details back whether or not it has anything to
write there, and crashes with `No data` when App Store Connect has none. Fill in
contact name, phone and email, and paste the block below into **Notes**.

<!-- BEGIN REVIEW NOTES -->
The app controls servers at the cloud provider Scaleway through the user's own
Scaleway API key. You do not need a Scaleway account to review it: on the first
screen tap "Try the demo" (also in Settings). The demo shows made-up instances,
serverless apps, logs and costs. Starting, stopping and rebooting work on the
instances as they would on real servers. Open an instance and tap Logs to see
the guide for instances that send no logs; open a serverless app and tap Logs
to see its log lines.

The app has no server of its own and no account system. API keys are stored in
the iOS keychain and sent only to api.scaleway.com.

ControlRoom is an independent app, not made or endorsed by Scaleway. It uses
the name only to say which service it works with.
<!-- END REVIEW NOTES -->

These details can live in the repository instead: add a `review` block to
`AppStore/app.json` with `first_name`, `last_name`, `phone_number`,
`email_address` and `notes`. `scripts/validate_metadata.py` insists it is
complete.

## 2. App Privacy

App Privacy → **Data Not Collected**. The app has no analytics, ads, tracking or
server; it talks only to Scaleway with the user's own key. Purchases go through
Apple.

## 3. Age rating

Answer the questionnaire; nothing applies, so it comes out **4+**.

## 4. Content rights

Declare no third-party content. The app uses Scaleway's name to say what it
works with, never its logo or colours, and says in the description and in
Settings that it is not made by Scaleway. `scripts/validate_metadata.py` fails
if the description loses that sentence.

## 5. Agreements, pricing and availability

The app is free to download. The Pro subscription (not built yet) will need the
**Paid Apps agreement** under Business → Agreements, with banking and tax
filled in. Available worldwide.

## 6. Screenshots

Pushed by the pipeline from `AppStore/screenshots/en-US/`. See
`AppStore/screenshots/README.md`.

## 7. Website

`website/` holds the marketing page and the privacy and support pages behind the
two store URLs. The Website workflow publishes them to
`https://vandijkcloud.nl/controlroom/` on every change, using the `FTP_HOST`,
`FTP_USERNAME` and `FTP_PASSWORD` secrets.
