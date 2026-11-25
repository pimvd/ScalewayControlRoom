#!/usr/bin/env python3
"""Prints the newest version code on a Google Play track (default: internal).

fastlane supply attaches a store listing to one release of a track, and asks
which one when the track holds several. The workflow passes it this.

Signs in like scripts/sync_subscriptions.py. Needs google-auth and requests.

Run: python3 scripts/latest_version_code.py [track]
"""
import json
import sys
from pathlib import Path

from sync_subscriptions import API, PlayApi, SyncError

PACKAGE = json.loads((Path(__file__).resolve().parent.parent / "PlayStore/listing.json").read_text())["package_name"]


def latest_version_code(api, track="internal"):
    edits = f"{API}/{PACKAGE}/edits"
    edit = api.request("POST", edits, body={})["id"]
    try:
        releases = api.request("GET", f"{edits}/{edit}/tracks/{track}").get("releases", [])
    finally:
        api.request("DELETE", f"{edits}/{edit}")
    codes = [int(code) for release in releases for code in release.get("versionCodes", [])]
    if not codes:
        raise SyncError(f"no release on the {track} track yet")
    return max(codes)


def main():
    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    try:
        print(latest_version_code(PlayApi(AuthorizedSession(credentials)), *sys.argv[1:2]))
    except SyncError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
