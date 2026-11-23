# Google Play: what is mocked, and what to do once there is an account

There is no Google Play developer account yet. Everything that needs one is
mocked or left as a placeholder; the rest is ready.

## Mocked for now

- **Billing.** The app sells Pro from a local test store (`MockProStore`): a
  purchase only sets a flag on the device, and Settings can cancel it again.
  The real Google Play Billing client (`PlayProStore`) is written and builds,
  but is only used when the app is built with `-PplayBilling=true`.
- **Upload.** The Android release workflow builds the bundle and keeps it as
  an artifact; its "Upload to Google Play" step only prints a notice.

## Once the account exists

1. Create the developer account (one-time fee) and the app
   `com.pvd.scwcontrolroom`, free, category Tools.
2. Make an upload key and add it as the `UPLOAD_KEYSTORE_BASE64`,
   `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS` and `UPLOAD_KEY_PASSWORD`
   repository secrets:
   ```sh
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 upload.jks
   ```
   Keep `upload.jks` safe; Play App Signing holds the real signing key.
3. Run the Android release workflow and upload its bundle to a closed
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
7. For production, build with `playBilling=true` (in `android/gradle.properties`,
   or `-PplayBilling=true` in the release workflow), so Pro is sold through
   Google Play. Add yourself as a license tester to buy it without being charged.
8. Add a Google Cloud service account with release rights in the Play
   Console as the `PLAY_SERVICE_ACCOUNT_JSON` secret, and replace the
   placeholder step in `.github/workflows/android-release.yml` with
   `r0adkll/upload-google-play` to the internal track.
9. In-app reviews only show for apps installed from Google Play; nothing to
   set up.
