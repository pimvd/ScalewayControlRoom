#!/usr/bin/env python3
"""Revokes the development certificates this CI run made, and only those.

Archiving with -allowProvisioningUpdates on a fresh runner makes Apple create
an "Apple Development: Created via API" certificate whose private key lives in
that runner's keychain alone. The runner is thrown away; the certificate is
not, and the team's certificate limit (shared by every app on the team) fills
up until archiving fails with "Your account has reached the maximum number of
certificates".

Run by the release workflow after the upload. It takes the SHA-1 fingerprints
of the certificates in the runner's keychain as arguments and revokes the App
Store Connect development certificates with the same fingerprint: never one
made elsewhere, such as on a Mac or by another app's run going at the same
time. Needs ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (see scripts/asc.py).

Never fails the job: the build is already uploaded by now, so a problem here
is reported as a warning.

With --all-created-via-api instead of fingerprints (the "Revoke stale
certificates" workflow) it revokes every development certificate named
"Created via API": the ones older runs left behind, in this app or another on
the team. A certificate made in Xcode carries its owner's name, not that one,
and is left alone; so is every distribution certificate.
"""
import base64
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from asc import SyncError, client_from_environment  # noqa: E402

DEVELOPMENT_TYPES = {"DEVELOPMENT", "IOS_DEVELOPMENT"}
API_CREATED = "Created via API"
ALL_FLAG = "--all-created-via-api"


def fingerprint(certificate_content):
    """The SHA-1 of a certificate's DER bytes, as `security -Z` prints it."""
    return hashlib.sha1(base64.b64decode(certificate_content)).hexdigest().upper()


def certificates_to_revoke(certificates, runner_fingerprints):
    """The development certificates whose fingerprint is one of the runner's."""
    wanted = {f.strip().upper() for f in runner_fingerprints if f.strip()}
    chosen = []
    for certificate in certificates:
        attributes = certificate.get("attributes", {})
        if attributes.get("certificateType") not in DEVELOPMENT_TYPES:
            continue
        content = attributes.get("certificateContent")
        if content and fingerprint(content) in wanted:
            chosen.append(certificate)
    return chosen


def stale_certificates(certificates):
    """Every development certificate that cloud signing made on some runner."""
    return [
        certificate for certificate in certificates
        if certificate.get("attributes", {}).get("certificateType") in DEVELOPMENT_TYPES
        and API_CREATED in (certificate.get("attributes", {}).get("name") or "")
    ]


def list_certificates(api):
    certificates, path, params = [], "/v1/certificates", {"limit": 200}
    while path:
        page = api.request("GET", path, params)
        certificates += page.get("data", [])
        following = page.get("links", {}).get("next")
        path = following.removeprefix("https://api.appstoreconnect.apple.com") if following else None
        params = None
    return certificates


def main(arguments):
    revoke_all = arguments == [ALL_FLAG]
    if not arguments:
        print("No development certificate in the runner's keychain; nothing to revoke.")
        return
    try:
        api = client_from_environment()
        certificates = list_certificates(api)
        if revoke_all:
            chosen = stale_certificates(certificates)
            print(f"{len(chosen)} development certificate(s) named '{API_CREATED}' to revoke.")
            if not chosen:
                for certificate in certificates:
                    attributes = certificate.get("attributes", {})
                    print(f"  left alone: {attributes.get('certificateType')} {attributes.get('name')}")
        else:
            chosen = certificates_to_revoke(certificates, arguments)
            if not chosen:
                print("None of the runner's certificates is on App Store Connect; nothing to revoke.")
        for certificate in chosen:
            attributes = certificate["attributes"]
            api.request("DELETE", f"/v1/certificates/{certificate['id']}")
            print(f"Revoked {attributes.get('name')} ({attributes.get('serialNumber')})")
    except (SyncError, OSError) as error:
        if revoke_all:
            sys.exit(f"::error::Revoking failed: {error}")
        print(f"::warning::Could not revoke this run's development certificate: {error}. "
              "Run the 'Revoke stale certificates' workflow if archiving reports the limit.")


if __name__ == "__main__":
    main(sys.argv[1:])
