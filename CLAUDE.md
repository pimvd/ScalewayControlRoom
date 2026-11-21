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

- `src/app/` screens (expo-router): tabs for Instances, Costs, Settings;
  `instance/[id]` detail; `add-key` modal.
- `src/lib/types.ts` domain types and the `ScalewayClient` interface.
- `src/lib/scaleway.ts` the real client; `src/lib/demo.ts` the demo client and
  data. The demo key is an ordinary key whose client is fake, so there is no
  separate demo code path. Demo data doubles as screenshot content.
- `src/lib/logic.ts` pure functions (merge/dedupe, filters, cost sums), tested
  in `logic.test.ts`.
- `src/lib/store.tsx` React context: keys, merged server list, refresh,
  polling while a server is starting/stopping, actions.
- `src/lib/pro.ts` the single Pro gate (`usePro`). Everything is unlocked until
  the subscription ships.
- `AppStore/`, `scripts/`, `MANUAL_METADATA.md`, `website/`: the store pipeline,
  modelled on FreeCubeSolver's.

## Behaviour worth knowing

- Several keys may see the same server; it is listed once, and actions try each
  key that sees it until one is allowed (401/403 moves on to the next).
- Costs are fetched once per organization ID, so two keys for one organization
  do not double count.
- Stop uses `poweroff`, which releases the hypervisor and stops compute billing.
  `stop_in_place` is deliberately not offered.
- Store URLs (privacy, support, terms) come from `AppStore/app.json` through
  `app.config.ts`, so the app and the listing never disagree.
- The build number is the git commit count, set by the release workflow through
  the `BUILD_NUMBER` environment variable read in `app.config.ts`.

## Checks

`npx tsc --noEmit`, `npm test`, `python3 scripts/validate_metadata.py`. CI runs
these plus an unsigned simulator build.
