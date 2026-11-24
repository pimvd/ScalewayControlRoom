# Scaleway ControlRoom

iPhone app (SwiftUI, iOS 17+) and Android app (Kotlin, Jetpack Compose,
Android 9+) that list Scaleway instances across all zones, start/stop/reboot
them, show serverless apps with their Cockpit logs and metrics, and show monthly
costs from the Billing API. No backend: the apps call `api.scaleway.com` with
the user's API keys, which live in the keychain (iOS) or encrypted with an
Android Keystore key (Android).

The two apps are twins: same screens, same behaviour, same demo. A change to
one goes into the other too, unless it is about the platform.

## Rules

- KISS, DRY, YAGNI. Build what is asked, nothing speculative.
- English only: code, UI text, docs, commits. No localization.
- Work on `master` for now.
- Phones only, portrait. No widgets, no Siri/Shortcuts, no alerts, no
  backend, no "you saved" figure.
- Never use Scaleway's logo or colours, and never "Control Center" (Apple's).
  The description must keep saying the app is not made by Scaleway; the
  metadata validator enforces it.

## Layout

`ios/` holds the iPhone app and its App Store pipeline, `android/` the Android
app and its Play Store files. `website/` and `docs/` are shared.

### ios/

Same structure as the owner's FreeCubeSolver: an Xcode project plus a local
Swift package for everything that is not UI. Paths below are inside `ios/`.

- `Packages/ControlRoomKit` (Foundation only, builds and tests on macOS and
  Linux with `swift test`):
  - `Models.swift` domain types, `ScalewayClient` protocol, `client(for:)`.
  - `ScalewayAPI.swift` the real client over an injectable `HTTPTransport`;
    tests feed it canned Scaleway JSON (`ScalewayAPITests`).
  - `Demo.swift` the demo client and data. The demo key is an ordinary key
    whose client is fake, so there is no separate demo code path. Demo data
    doubles as screenshot content.
  - `Logic.swift` merge/dedupe, sorting, filters, cost sums, periods,
    formatting, key validation, the rating rule.
  - `Commands.swift` the copyable scw CLI and shell commands of the setup
    screens. `website/support.html` repeats the key setup commands and
    `docs/instance-logs-and-metrics.md` the Alloy ones; keep them in step
    (and Android's `Commands.kt`).
  - `Resources.swift` the Other tab: `Products.all` (one line per product
    list endpoint, read as plain JSON), `listResources` (a few requests at a
    time), rows and grouping.
  - `Navigation.swift` tabs, destinations, the screenshot route parser and
    `ProProducts` (the subscription IDs).
- `ControlRoom/` (app target, a synchronized folder: new files are picked up
  without editing the project):
  - `Model/AppModel.swift` keys, merged lists, refresh, polling while a server
    is starting/stopping, actions with `withKeys`.
  - `Model/KeyStore.swift` keychain. `Model/ProStore.swift` StoreKit 2.
    `Model/Links.swift` the store URLs (checked against `AppStore/app.json`).
  - `Views/` one file per screen; `Components.swift` has `CommandBlock`,
    `ProGate`, `StateDot`.
- `StoreKit/ControlRoom.storekit` the scheme's StoreKit configuration, so
  running from Xcode buys from a local test store.
- `AppStore/`, `scripts/`, `MANUAL_METADATA.md`: the store pipeline.

### android/

A Gradle project (`./gradlew`, JDK 21, Android SDK) with two modules, the
same split as iOS:

- `kit` (plain Kotlin/JVM, `./gradlew :kit:test`): the port of
  ControlRoomKit, file for file (`Models.kt`, `ScalewayApi.kt`, `Demo.kt`,
  `Logic.kt`, `Commands.kt`, `Resources.kt`, `Navigation.kt`), with the
  Swift tests ported. Scaleway's JSON is read as kotlinx `JsonElement`.
  Numbers are rounded as Swift's `String(format:)` does (`fixed`), names
  sorted as `localizedStandardCompare` does (`naturalOrder`).
- `app` (Compose, Material 3): `model/` (`AppModel` with Compose state,
  `KeyStore`, `ProStore`, `Links`), `ui/` one file per tab plus
  `Components.kt` and `Chart.kt` (a Canvas chart in place of Swift Charts).
  Navigation is a stack of `Destination`s per tab in `Root.kt`, as on iOS.
- `app/src/test/.../ScreenshotTest.kt`: Robolectric opens each screenshot
  route of the demo and checks it; `./gradlew :app:recordRoborazziDebug`
  also saves the Play Store pictures to `PlayStore/screenshots/phone`. No
  emulator needed.
- `DISTRIBUTION.md`: how the app is published, as a signed APK on GitHub
  Releases, and the signing key.
- `PlayStore/` the listing, subscriptions, graphics and the closed test's
  tester page; `scripts/validate_listing.py` checks them against the app.
  `PLAY_STORE.md`: the Google Play setup; `TESTERS.md`: finding testers.

### Shared

- `website/`: marketing page, support (also the key setup guide and the
  Android install guide, `support.html#android`) and privacy.
  The Website workflow uploads it with the app icon and the App Store
  screenshots to `vandijkcloud.nl/Scaleway-ControlRoom/` over FTP.

## Behaviour worth knowing

- Several keys may see the same resource; it is listed once, and actions try
  each key that sees it until one is allowed (401/403 moves on to the next).
- Costs are fetched once per organization ID, so two keys for one
  organization do not double count.
- Logs: the app lists the project's Cockpit log data sources in the region and
  queries each one's Loki with `{resource_id="<id>"}`, authenticated with the
  IAM secret key (`X-Auth-Token`). Scaleway labels serverless logs that way;
  the instance guide's Alloy config adds the same label.
- Metrics: the same data sources of type metrics, through their Prometheus API
  (`/prometheus/api/v1/...`). Names are not assumed: the app asks Cockpit for
  the metric names carrying `{resource_id="<id>"}` and shows them as a
  dashboard, a chart per metric with a line per `resource_instance`
  (`metricQuery`: rate for counters, average otherwise; Swift Charts); tapping
  one opens it large. Cockpit gives no metric types, so names decide, with
  Scaleway's exceptions in `Logic.swift`: `instances_total` is no counter, the
  serverless CPU "ratio" is a percentage, `_info`/`_status` are not charted.
  The instance guide's Alloy config pushes basic node metrics with the same
  label.
- An instance with no logs or metrics in the period gets an explanation from
  its state (`instanceQuiet`: stopped, starting, new = created within the
  hour, running) above the Alloy setup, which is collapsed when the agent may
  already be there (stopped, starting).
- Serverless: containers (v1) and functions (v1beta1) carry no project; it
  comes from their namespace. Functions can only be listed per namespace.
  Jobs (serverless-jobs v1alpha1) are job definitions; their status is the
  last run's state. Cockpit labels job logs `job_definition_id` (their
  `resource_id` is the run), so `CockpitTarget.label` says which to query. A
  key that may not see serverless (401/403) is left out quietly.
- Other: every product in `Products.all`, in every region or zone (~170
  requests per key, ~9 s), the first time the tab is shown, when the keys
  change, or when pulled. The listing runs in its own task: opening a resource
  cancels the view's task, and a cancelled listing would replace the list
  with the part that had loaded. Scaleway answers
  bursts with 429: `send` retries, `listResources` keeps 6 in flight. Object
  Storage is missing: buckets need the S3 API and an access key.
  A resource's detail screen shows every field of its list item (`Detail`,
  flattened, readable labels and values, IDs left out): the product's
  `keyFields` in a Details section, all of them in a collapsed "All fields"
  list. The demo resources are read from Scaleway-like JSON through the same
  parser.
- Stop uses `poweroff`, which releases the hypervisor and stops compute
  billing. `stop_in_place` is deliberately not offered.
- Pro: `ProStore.isPro(keys:)` is the one gate. It is true with an active
  subscription (StoreKit `Transaction.currentEntitlements`, no server) and
  while the demo is the only key, so App Review and screenshots see
  everything. Free: one key, instances with start/stop/reboot, this month's
  total. Pro: more keys, serverless, other resources, logs, metrics, cost
  breakdown and earlier months.
  The purchase screen is StoreKit's `SubscriptionStoreView`.
- Rating: after the 3rd, 20th and 60th successful start/stop with a real key
  (never in the demo), via SwiftUI's `requestReview`.
- Subscriptions: `AppStore/subscriptions.json` → `scripts/sync_subscriptions.py`
  (App Store Connect client in `scripts/asc.py`, tests in `scripts/tests/`).
  `scripts/validate_subscriptions.py` checks the IDs against `ProProducts` and
  the StoreKit configuration.
- Screenshots: `scripts/screenshots.sh` launches the simulator build with
  `-screenshotRoute <path>`; `RootView.start` turns the demo on and opens that
  screen. Deep links are not used because iOS asks "Open in …?" first. The
  simulator build is signed ad hoc: an unsigned app cannot use the keychain.
  The script fails when two pictures are identical.
- The build number is the git commit count, passed to `xcodebuild archive` as
  `CURRENT_PROJECT_VERSION`; the version is `MARKETING_VERSION` in the project.

## Android specifics

- Distributed on Google Play: every push to master that changes `android/`
  uploads the bundle (Play Billing) to the internal track; production is
  promoted by hand in the Play Console. The upload signs in to Google Cloud
  without a key (Workload Identity Federation; the organization forbids
  service account keys), with the `PLAY_WORKLOAD_IDENTITY_PROVIDER` and
  `PLAY_SERVICE_ACCOUNT` repository variables. Run by hand, it can also push
  the store listing, graphics and the Pro subscription from `PlayStore/`
  (`scripts/sync_play.py`, tests in `scripts/tests/`), as `release.yml` does
  for the App Store. Also as a signed APK on GitHub
  Releases (`android-v<version>`, manual), where Play Billing cannot work, so
  that build has every feature free; users install it by hand or with
  Obtainium.
- `-Pstore` picks where Pro comes from: `test` (default; `MockProStore`, a
  local test store like Xcode's StoreKit configuration), `github`
  (`FreeProStore`, everything free) or `play` (`PlayProStore`, Google Play
  Billing, product `pro` with base plans `yearly` and `monthly`).
- Rating: Play's in-app review, at the same milestones.
- The screenshot route is the `screenshotRoute` intent extra.
- Version: `versionCode` is the commit count, passed as `-PversionCode`;
  `versionName` is iOS's `MARKETING_VERSION` plus that number (`1.0.57`), so
  every GitHub release has a version Obtainium can compare.

## Workflows

`ci.yml` (every push, any branch: validators, package tests, simulator build,
Android tests and debug build), `android-release.yml` (Android changes on
master: the bundle to Play's internal track; manual: also the signed APK as a
GitHub Release), `android-screenshots.yml` (manual, commits them),
`release.yml` (TestFlight, metadata and subscriptions, manual),
`screenshots.yml` (simulator screenshots, manual, commits them),
`website.yml` (on website changes and after Screenshots).

## Xcode

The macOS jobs run on GitHub's `xcode-27` runner image (one major Xcode
version per image, arm64), so builds use the iOS 27 SDK that App Store Connect
requires from April 2027. Move to the next `xcode-NN` label when Apple raises
the minimum again.

## Checks

In `ios/`: `swift test` in `Packages/ControlRoomKit`,
`python3 scripts/validate_metadata.py`, `python3 scripts/validate_subscriptions.py`,
`python3 -m unittest discover -s scripts/tests -t .`. The app target only
builds with Xcode (CI or a Mac).

In `android/`: `./gradlew :kit:test :app:testDebugUnitTest :app:assembleDebug`,
`python3 scripts/validate_listing.py`,
`python3 -m unittest discover -s scripts/tests -t .`.
