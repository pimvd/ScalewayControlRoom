# Scaleway ControlRoom

iPhone app (Expo / React Native, TypeScript, expo-router) that lists Scaleway
instances across all zones, starts/stops/reboots them, and shows monthly costs
from the Billing API. No backend: the app calls `api.scaleway.com` with the
user's API keys, which live in the keychain (`expo-secure-store`).

## Rules

- KISS, DRY, YAGNI. Build what is asked, nothing speculative.
- English only: code, UI text, docs, commits. No localization.
- Work on `master` for now.
- iPhone only (`supportsTablet: false`). No widgets, no Siri/Shortcuts, no
  alerts, no backend, no "you saved" figure.
- Never use Scaleway's logo or colours, and never "Control Center" (Apple's).
  The description must keep saying the app is not made by Scaleway; the
  metadata validator enforces it.

## Layout

- `src/app/` screens (expo-router): tabs for Instances, Serverless, Costs,
  Settings; `instance/[id]` and `serverless/[id]` details; `logs` (both kinds);
  `add-key` and `pro` (the purchase screen) modals.
- `src/components/instance-logs-setup.tsx` the guide shown when an instance has
  no logs. Setup screens show copyable scw CLI and shell commands
  (`src/lib/commands.ts`, `CommandBlock` in `src/components/command.tsx`);
  `website/support.html` repeats the key setup commands, keep them in step.
- `src/lib/review.ts` asks for an App Store rating after the 3rd, 20th and
  60th successful start/stop with a real key (never in the demo).
- `src/lib/types.ts` domain types and the `ScalewayClient` interface.
- `src/lib/scaleway.ts` the real client; `src/lib/demo.ts` the demo client and
  data. The demo key is an ordinary key whose client is fake, so there is no
  separate demo code path. Demo data doubles as screenshot content.
- `src/lib/logic.ts` pure functions (merge/dedupe, filters, cost sums), tested
  in `logic.test.ts`.
- `src/lib/store.tsx` React context: keys, merged server list, refresh,
  polling while a server is starting/stopping, actions.
- `src/lib/pro.tsx` Pro: `ProProvider` reads the active subscription with
  StoreKit on the device (expo-iap, no backend), buys and restores. `usePro()`
  is the one gate every Pro feature asks; it is also true while the demo is the
  only key, so App Review and screenshots see everything. Gates show
  `ProGate` (`src/components/pro-gate.tsx`), which opens `/pro`.
  Free: one key, instances with start/stop/reboot, this month's total. Pro:
  more keys, serverless, logs, cost breakdown and earlier months.
- `AppStore/`, `scripts/`, `MANUAL_METADATA.md`: the store pipeline, modelled on
  FreeCubeSolver's.
- `website/`: marketing page, support (also the key setup guide) and privacy.
  The Website workflow uploads it with the icon and the App Store screenshots
  to `vandijkcloud.nl/Scaleway-ControlRoom/` over FTP.
- Subscriptions: `AppStore/subscriptions.json` → `scripts/sync_subscriptions.py`
  (App Store Connect client in `scripts/asc.py`, tests in `scripts/tests/`).
  Product IDs live in `PRO_PRODUCTS` (`src/lib/pro.tsx`) and
  `scripts/validate_subscriptions.py` keeps both in step.
- Screenshots: `scripts/screenshots.sh` writes the screen's path to the app's
  `screenshotRoute` setting before each launch; `ScreenshotRoute` in `src/app/_layout.tsx` turns
  the demo on and opens that screen. Deep links are not used because iOS asks
  "Open in …?" first. The script fails when two pictures are identical.
- Workflows: `ci.yml` (every push), `release.yml` (TestFlight, metadata and
  subscriptions, manual), `screenshots.yml` (simulator screenshots, manual, commits them),
  `website.yml` (on website changes and after Screenshots).

## Behaviour worth knowing

- Several keys may see the same server; it is listed once, and actions try each
  key that sees it until one is allowed (401/403 moves on to the next).
- Costs are fetched once per organization ID, so two keys for one organization
  do not double count.
- Logs: the app lists the project's Cockpit log data sources in the region and
  queries each one's Loki with `{resource_id="<id>"}`, authenticated with the
  IAM secret key (`X-Auth-Token`). Scaleway labels serverless logs that way; the
  instance guide's Alloy config adds the same label.
- Serverless: containers (v1) and functions (v1beta1) carry no project; it comes
  from their namespace. Functions can only be listed per namespace. A key that
  may not see serverless (401/403) is left out quietly.
- Stop uses `poweroff`, which releases the hypervisor and stops compute billing.
  `stop_in_place` is deliberately not offered.
- Store URLs (privacy, support, terms) come from `AppStore/app.json` through
  `app.config.ts`, so the app and the listing never disagree.
- The build number is the git commit count, set by the release workflow through
  the `BUILD_NUMBER` environment variable read in `app.config.ts`.

## Checks

`npx tsc --noEmit`, `npm test`, `python3 scripts/validate_metadata.py`. CI runs
these plus an unsigned simulator build.
