# Scaleway ControlRoom

iPhone app (SwiftUI, iOS 17+) that lists Scaleway instances across all zones,
starts/stops/reboots them, shows serverless apps with their Cockpit logs and
metrics, and shows monthly costs from the Billing API. No backend: the app calls
`api.scaleway.com` with the user's API keys, which live in the keychain.

## Rules

- KISS, DRY, YAGNI. Build what is asked, nothing speculative.
- English only: code, UI text, docs, commits. No localization.
- Work on `master` for now.
- iPhone only, portrait. No widgets, no Siri/Shortcuts, no alerts, no
  backend, no "you saved" figure, no Android.
- Never use Scaleway's logo or colours, and never "Control Center" (Apple's).
  The description must keep saying the app is not made by Scaleway; the
  metadata validator enforces it.

## Layout

Same structure as the owner's FreeCubeSolver: an Xcode project plus a local
Swift package for everything that is not UI.

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
    screens. `website/support.html` repeats the key setup commands; keep them
    in step.
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
- `website/`: marketing page, support (also the key setup guide) and privacy.
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
- Serverless: containers (v1) and functions (v1beta1) carry no project; it
  comes from their namespace. Functions can only be listed per namespace.
  Jobs (serverless-jobs v1alpha1) are job definitions; their status is the
  last run's state. Cockpit labels job logs `job_definition_id` (their
  `resource_id` is the run), so `CockpitTarget.label` says which to query. A
  key that may not see serverless (401/403) is left out quietly.
- Other: every product in `Products.all`, in every region or zone (~170
  requests per key), only when the tab is shown or pulled. Scaleway answers
  bursts with 429: `send` retries, `listResources` keeps 6 in flight. Object
  Storage is missing: buckets need the S3 API and an access key.
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

## Workflows

`ci.yml` (every push: validators, package tests, simulator build),
`release.yml` (TestFlight, metadata and subscriptions, manual),
`screenshots.yml` (simulator screenshots, manual, commits them),
`website.yml` (on website changes and after Screenshots).

## Xcode

The macOS jobs run on GitHub's `xcode-27` runner image (one major Xcode
version per image, arm64), so builds use the iOS 27 SDK that App Store Connect
requires from April 2027. Move to the next `xcode-NN` label when Apple raises
the minimum again.

## Checks

`swift test` in `Packages/ControlRoomKit`, `python3 scripts/validate_metadata.py`,
`python3 scripts/validate_subscriptions.py`,
`python3 -m unittest discover -s scripts/tests -t .`. The app target only
builds with Xcode (CI or a Mac).
