#!/usr/bin/env python3
"""Checks AppStore/app.json and AppStore/metadata/<locale>.json against App Store limits.

Run: python3 scripts/validate_metadata.py   (exit code 1 on any problem)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "AppStore"

# App Store Connect limits (characters; keywords are counted in bytes).
LIMITS = {
    "name": 30,
    "subtitle": 30,
    "promotional_text": 170,
    "description": 4000,
    "release_notes": 4000,
}
KEYWORDS_MAX_BYTES = 100
REQUIRED = ["name", "subtitle", "description", "keywords", "promotional_text", "release_notes"]
APP_REQUIRED = ["bundle_id", "primary_locale", "primary_category", "privacy_url", "support_url", "terms_url", "locales"]
REVIEW_NOTES_MAX = 4000  # App Review Information → Notes
# "Control Center" is Apple's; App Review rejects names that echo its features.
FORBIDDEN = re.compile(r"control\s+center", re.IGNORECASE)
# The app uses Scaleway's name, so the listing has to say it is not Scaleway's.
DISCLAIMER = "not made, endorsed or supported by Scaleway"


def main() -> int:
    problems: list[str] = []
    app = json.loads((ROOT / "app.json").read_text(encoding="utf-8"))
    for key in APP_REQUIRED:
        if not app.get(key):
            problems.append(f"app.json: missing {key}")

    # The review notes, pushed with the metadata.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from metadata_to_fastlane import review_notes
    try:
        notes = review_notes()
        if not notes or len(notes) > REVIEW_NOTES_MAX:
            problems.append(f"MANUAL_METADATA.md: review notes have {len(notes)} characters (1-{REVIEW_NOTES_MAX})")
    except ValueError:
        problems.append("MANUAL_METADATA.md: the BEGIN/END REVIEW NOTES markers are missing")

    files = {p.stem: p for p in sorted((ROOT / "metadata").glob("*.json"))}
    for locale in app.get("locales", []):
        if locale not in files:
            problems.append(f"{locale}: listed in app.json but metadata/{locale}.json is missing")
    for locale in files:
        if locale not in app.get("locales", []):
            problems.append(f"{locale}: metadata file exists but the locale is not listed in app.json")

    for locale, path in files.items():
        data = json.loads(path.read_text(encoding="utf-8"))
        for key in REQUIRED:
            value = data.get(key)
            if not isinstance(value, str) or not value.strip():
                problems.append(f"{locale}: {key} is missing or empty")
        for key, limit in LIMITS.items():
            value = data.get(key, "")
            if len(value) > limit:
                problems.append(f"{locale}: {key} has {len(value)} characters (max {limit})")
        keywords = data.get("keywords", "")
        size = len(keywords.encode("utf-8"))
        if size > KEYWORDS_MAX_BYTES:
            problems.append(f"{locale}: keywords are {size} bytes (max {KEYWORDS_MAX_BYTES})")
        if ", " in keywords:
            problems.append(f"{locale}: keywords should be separated by commas without spaces")
        for key in ("name", "subtitle", "keywords"):
            if FORBIDDEN.search(data.get(key, "")):
                problems.append(f"{locale}: {key} must not contain 'Control Center' (Apple's)")
        # A subscription app must link its terms of use and privacy policy.
        for key in ("terms_url", "privacy_url"):
            if app.get(key) and app[key] not in data.get("description", ""):
                problems.append(f"{locale}: description must link {key} ({app[key]})")
        if DISCLAIMER not in data.get("description", ""):
            problems.append(f"{locale}: description must say the app is {DISCLAIMER}")
        unknown = set(data) - set(REQUIRED)
        if unknown:
            problems.append(f"{locale}: unknown keys {sorted(unknown)}")

    # The app links the same pages as the listing.
    links = (ROOT.parent / "ControlRoom/Model/Links.swift").read_text(encoding="utf-8")
    for key in ("privacy_url", "support_url", "terms_url"):
        if app.get(key) and f'"{app[key]}"' not in links:
            problems.append(f"ControlRoom/Model/Links.swift does not link {key} ({app[key]})")

    for problem in problems:
        print(f"error: {problem}")
    print(f"Checked {len(files)} locale(s): {'OK' if not problems else f'{len(problems)} problem(s)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
