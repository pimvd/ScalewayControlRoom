# Google Play

Every push to master that changes the Android app builds the bundle with
Pro sold through Google Play Billing (`-Pstore=play`) and uploads it to the
internal testing track (`.github/workflows/android-release.yml`). Promote a
release to production in the Play Console. The signed APK on GitHub Releases
(`DISTRIBUTION.md`) stays available, with every feature free.

## Setting it up, once

1. **The app.** In the Play Console, create the app `com.pvd.scwcontrolroom`,
   free, category Tools.
2. **The upload key.** The four `UPLOAD_*` secrets from `DISTRIBUTION.md`;
   Play App Signing holds the key Play signs with.
3. **The service account**, as the `PLAY_SERVICE_ACCOUNT_JSON` secret:
   1. In the Google Cloud console, pick or create a project and enable the
      *Google Play Android Developer API*.
   2. IAM → Service accounts: create one, then Keys → Add key → JSON. The
      file's contents are the secret.
   3. In the Play Console, Users and permissions → Invite new users: the
      service account's email address, with the app under App permissions
      and at least *Release apps to testing tracks*.
4. **Draft releases first.** Set the repository variable
   `PLAY_RELEASE_STATUS` to `draft`: a Play app that has never been
   published only accepts drafts. Remove it once the first release is out;
   from then on each upload goes straight to the internal testers.
5. **The first bundle by hand.** Play wants the very first upload done in
   the Play Console: run the Android release workflow (channel `play`), take
   the bundle from the run's artifacts, and upload it to the internal track.
6. **The subscription**, from `PlayStore/subscriptions.json`: product `pro`
   with base plans `yearly` (P1Y, €19.99) and `monthly` (P1M, €1.99),
   auto-renewing. The IDs must match `ProProducts` (checked by
   `scripts/validate_listing.py`). Add yourself as a license tester to buy it
   without being charged.
7. **The store listing** from `PlayStore/listing.json`, with `icon-512.png`,
   `feature-graphic.png` and `screenshots/phone/`.
8. **App content:** privacy policy URL, ads: none, data safety: no data
   collected or shared (keys never leave the phone except to Scaleway's API),
   content rating questionnaire (a developer tool: Everyone), target
   audience 18+.
9. **Testers before production.** A personal developer account needs a
   closed test with 12 testers for 14 days first (`TESTERS.md`); an
   organization account does not.

In-app reviews only show for apps installed from Google Play; nothing to set
up. Debug builds keep the local test store (`MockProStore`).
