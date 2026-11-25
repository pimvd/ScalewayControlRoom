# Google Play

Every push to master that changes the Android app builds the bundle with
Pro sold through Google Play Billing (`-Pstore=play`) and uploads it with
fastlane supply to the internal testing track, with the release notes from
`PlayStore/listing.json` (`.github/workflows/android-release.yml`). Promote a
release to production in the Play Console. The signed APK on GitHub Releases
(`DISTRIBUTION.md`) stays available, with every feature free.

## Setting it up, once

1. **The app.** In the Play Console, create the app `com.pvd.scwcontrolroom`,
   free, category Tools.
2. **The upload key.** The four `UPLOAD_*` secrets from `DISTRIBUTION.md`;
   Play App Signing holds the key Play signs with.
3. **The upload rights, without a key.** Google Cloud organizations block
   service account keys by default, so GitHub signs in with Workload
   Identity Federation instead: Google trusts this repository's workflow runs
   and lets them act as a service account.
   1. In the Google Cloud console, select the project, open Cloud Shell (the
      `>_` button at the top) and paste:
      ```sh
      PROJECT=$(gcloud config get-value project)
      NUMBER=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')
      REPO=pimvd/ScalewayControlRoom
      gcloud services enable androidpublisher.googleapis.com iamcredentials.googleapis.com
      gcloud iam service-accounts describe "github-upload@$PROJECT.iam.gserviceaccount.com" >/dev/null 2>&1 ||
        gcloud iam service-accounts create github-upload --display-name="GitHub upload to Play"
      gcloud iam workload-identity-pools create github --location=global --display-name=GitHub
      gcloud iam workload-identity-pools providers create-oidc github --location=global \
        --workload-identity-pool=github --issuer-uri=https://token.actions.githubusercontent.com \
        --attribute-mapping=google.subject=assertion.sub,attribute.repository=assertion.repository \
        --attribute-condition="assertion.repository=='$REPO'"
      gcloud iam service-accounts add-iam-policy-binding "github-upload@$PROJECT.iam.gserviceaccount.com" \
        --role=roles/iam.workloadIdentityUser \
        --member="principalSet://iam.googleapis.com/projects/$NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/$REPO"
      echo
      echo "PLAY_WORKLOAD_IDENTITY_PROVIDER = projects/$NUMBER/locations/global/workloadIdentityPools/github/providers/github"
      echo "PLAY_SERVICE_ACCOUNT            = github-upload@$PROJECT.iam.gserviceaccount.com"
      ```
   2. Add the two printed values as repository **variables** (not secrets;
      they are not secret) on GitHub: Settings → Secrets and variables →
      Actions → Variables.
   3. In the Play Console, Users and permissions → Invite new users: the
      service account's email address (`PLAY_SERVICE_ACCOUNT`), with the app
      under App permissions and at least *Release apps to testing tracks*.
4. **Draft releases first.** Set the repository variable
   `PLAY_RELEASE_STATUS` to `draft`: a Play app that has never been
   published only accepts drafts. Remove it once the first release is out;
   from then on each upload goes straight to the internal testers.
5. **The first bundle by hand.** Play wants the very first upload done in
   the Play Console: run the Android release workflow (channel `play`), take
   the bundle from the run's artifacts, and upload it to the internal track.
6. **The subscription and the store listing.** Pushed from `PlayStore/` as
   the iPhone app's are from `AppStore/`: fastlane supply uploads the
   listing's text and graphics (`scripts/listing_to_fastlane.py` converts them
   to its layout), and `scripts/sync_subscriptions.py` creates the
   subscription, which fastlane cannot. Give the service account *Manage
   store presence* for the app in the Play Console, then run the Android
   release workflow with `push_subscriptions` and `push_listing` on
   (`upload_build` off to push only those). The subscription is `pro` with
   base plans `yearly` (P1Y, €19.99) and `monthly` (P1M, €1.99), priced in
   euros and converted by Google for every other region; the IDs must match
   `ProProducts` (checked by `scripts/validate_listing.py`). Add yourself as a
   license tester to buy it without being charged.
7. **The store listing's other settings** in the Play Console, once: the app's
   category, contact email and website (Store settings).
8. **App content:** privacy policy URL, ads: none, data safety: no data
   collected or shared (keys never leave the phone except to Scaleway's API),
   content rating questionnaire (a developer tool: Everyone), target
   audience 18+.
9. **Testers before production.** A personal developer account needs a
   closed test with 12 testers for 14 days first (`TESTERS.md`); an
   organization account does not.

In-app reviews only show for apps installed from Google Play; nothing to set
up. Debug builds keep the local test store (`MockProStore`).
