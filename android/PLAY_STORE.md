# Google Play: what is mocked, and what to do to publish there

For now the app is distributed on GitHub (`DISTRIBUTION.md`), not on Google
Play. Publishing on Play later needs the steps below; everything that needs
the Play Console is mocked or left as a placeholder, the rest is ready.

## Mocked for now

- **Billing.** The app sells Pro from a local test store (`MockProStore`): a
  purchase only sets a flag on the device, and Settings can cancel it again.
  The real Google Play Billing client (`PlayProStore`) is written and builds,
  but is only used when the app is built with `-Pstore=play`.
- **Upload.** The Android release workflow (channels `play-test` and `play`)
  builds the bundle and keeps it as an artifact; its "Upload to Google Play"
  step only prints a notice.

## Once the account exists

1. Create the developer account (one-time fee) and the app
   `com.pvd.scwcontrolroom`, free, category Tools.
2. Use the GitHub releases' signing key as the upload key (`DISTRIBUTION.md`);
   Play App Signing holds the key Play signs with.
3. Run the Android release workflow with channel `play-test` and upload its bundle to a closed
   testing track by hand: Play requires the first one to be uploaded in the
   Play Console. A new personal account needs 12 testers opted in for 14 days
   before production; see `TESTERS.md`. The test builds keep the test store,
   so testers get Pro for free (the tester page says so).
4. Create the subscription from `PlayStore/subscriptions.json`: product `pro`
   with base plans `yearly` (P1Y, €19.99) and `monthly` (P1M, €1.99),
   auto-renewing. The IDs must match `ProProducts` (checked by
   `scripts/validate_listing.py`).
5. Fill in the store listing from `PlayStore/listing.json`, with
   `icon-512.png`, `feature-graphic.png` and `screenshots/phone/`.
6. App content: privacy policy URL, ads: none, data safety: no data collected
   or shared (keys never leave the phone except to Scaleway's API), content
   rating questionnaire (a developer tool: Everyone), target audience 18+.
7. For production, run the workflow with channel `play` (`-Pstore=play`), so
   Pro is sold through Google Play. Add yourself as a license tester to buy it without being charged.
8. Add a Google Cloud service account with release rights in the Play
   Console as the `PLAY_SERVICE_ACCOUNT_JSON` secret, and replace the
   placeholder step in `.github/workflows/android-release.yml` with
   `r0adkll/upload-google-play` to the internal track.
9. In-app reviews only show for apps installed from Google Play; nothing to
   set up.
