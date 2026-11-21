# Manual App Store Connect setup

Everything the release pipeline **cannot** push, and has to be filled in by hand
in App Store Connect. The pipeline covers the rest: `AppStore/app.json` and
`AppStore/metadata/en-US.json` hold the store text, `AppStore/screenshots/`
holds the pictures, and the "Release to TestFlight" workflow sends both with
`fastlane deliver`.

## 0. Create the app record

App Store Connect → Apps → **+** → New App: iOS, name **Scaleway ControlRoom**,
primary language English (U.S.), bundle ID `com.pvd.scwcontrolroom`, SKU
`scwcontrolroom`. If the name is refused, try "ControlRoom for Scaleway" and
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
Pro is an auto-renewing subscription (monthly or yearly). While the demo is the
only key, every Pro feature is open so the whole app can be reviewed. To test
the purchase itself, open Settings → Get Pro: it shows both subscriptions with
their price, Restore purchases, and links to the Terms of Use and Privacy
Policy. Subscription status is checked with StoreKit on the device.
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

The app is free to download, available worldwide. The Paid Apps agreement is in
place.

## 5a. Pro subscriptions

The release workflow's `push_subscriptions` switch creates and updates them
from `AppStore/subscriptions.json` (`scripts/sync_subscriptions.py`): the
"ControlRoom Pro" group, the monthly and yearly subscription, their names and
descriptions, the price in every territory, availability and the review
screenshot. The product IDs must match `ProProducts` in
`Packages/ControlRoomKit` and `StoreKit/ControlRoom.storekit`; CI checks that.

By hand, once:

- **Free trial:** each subscription → Subscription Prices → Introductory
  Offers → Free, one week, all territories.
- **Review screenshot:** a screenshot of the Pro screen (Settings → Get Pro).
  Running from Xcode in the simulator shows real-looking prices from
  `StoreKit/ControlRoom.storekit`; a TestFlight build shows the sandbox ones. Save it as
  `AppStore/subscriptions/review.png`; until it exists the sync skips it, and
  App Store Connect will not let the subscriptions be submitted.
- **First submission:** new subscriptions go to review together with an app
  version. On the version page, under In-App Purchases and Subscriptions, add
  both before submitting.
- **Description:** the store description says which features need Pro, how the
  subscription renews, and links the Terms of Use and Privacy Policy;
  `scripts/validate_metadata.py` checks the links are there.

## 6. Screenshots

Pushed by the pipeline from `AppStore/screenshots/en-US/`. See
`AppStore/screenshots/README.md`.

## 7. Website

`website/` holds the marketing page and the privacy and support pages behind the
two store URLs. The Website workflow publishes them to
`https://vandijkcloud.nl/Scaleway-ControlRoom/` on every change, using the `FTP_HOST`,
`FTP_USERNAME` and `FTP_PASSWORD` secrets.
