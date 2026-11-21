#!/usr/bin/env python3
"""Converts AppStore/*.json into fastlane's layout for `fastlane deliver`.

Run: python3 scripts/metadata_to_fastlane.py [output_dir]   (default: fastlane)
Writes <output_dir>/metadata and, if AppStore/screenshots holds any, copies
them to <output_dir>/screenshots. The output is generated; do not commit it.

Screenshots live in AppStore/screenshots/<locale>/*.png. A locale with no
folder of its own falls back to the primary locale's.
"""
import json
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "AppStore"

# JSON key -> fastlane file name (per locale)
LOCALIZED_FILES = {
    "name": "name.txt",
    "subtitle": "subtitle.txt",
    "description": "description.txt",
    "keywords": "keywords.txt",
    "promotional_text": "promotional_text.txt",
    "release_notes": "release_notes.txt",
}
# app.json key -> fastlane file name (per locale, same value everywhere)
SHARED_PER_LOCALE = {
    "privacy_url": "privacy_url.txt",
    "support_url": "support_url.txt",
    "marketing_url": "marketing_url.txt",
}
# The App Review notes live in MANUAL_METADATA.md between these markers. The
# contact details are typed into App Store Connect: this repository is public.
NOTES_FILE = REPO / "MANUAL_METADATA.md"
NOTES_MARKERS = ("<!-- BEGIN REVIEW NOTES -->", "<!-- END REVIEW NOTES -->")


def review_notes() -> str:
    text = NOTES_FILE.read_text(encoding="utf-8")
    start, end = (text.index(marker) for marker in NOTES_MARKERS)
    return text[start + len(NOTES_MARKERS[0]):end].strip()
# app.json key -> fastlane file name (top level)
TOP_LEVEL = {
    "copyright": "copyright.txt",
    "primary_category": "primary_category.txt",
    "secondary_category": "secondary_category.txt",
}


def copy_screenshots(app: dict, output: Path) -> int:
    """Copies the committed screenshots into fastlane's layout. Returns the count."""
    source = SOURCE / "screenshots"
    if not source.is_dir():
        return 0
    primary = app["primary_locale"]
    copied = 0
    for locale in app["locales"]:
        folder = source / locale
        if not folder.is_dir():
            folder = source / primary
        if not folder.is_dir():
            continue
        target = output / locale
        target.mkdir(parents=True, exist_ok=True)
        for picture in sorted(folder.glob("*.png")):
            shutil.copy2(picture, target / picture.name)
            copied += 1
    return copied


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "fastlane"
    output = root / "metadata"
    app = json.loads((SOURCE / "app.json").read_text(encoding="utf-8"))
    output.mkdir(parents=True, exist_ok=True)
    for key, filename in TOP_LEVEL.items():
        if app.get(key):
            (output / filename).write_text(str(app[key]) + "\n", encoding="utf-8")
    folder = output / "review_information"
    folder.mkdir(exist_ok=True)
    (folder / "notes.txt").write_text(review_notes() + "\n", encoding="utf-8")
    for locale in app["locales"]:
        data = json.loads((SOURCE / "metadata" / f"{locale}.json").read_text(encoding="utf-8"))
        folder = output / locale
        folder.mkdir(exist_ok=True)
        for key, filename in LOCALIZED_FILES.items():
            (folder / filename).write_text(data[key] + "\n", encoding="utf-8")
        for key, filename in SHARED_PER_LOCALE.items():
            # Present but empty is meaningful: deliver only touches a field it is
            # given a file for, so an empty file is how a URL gets cleared again.
            if key in app:
                value = str(app[key])
                (folder / filename).write_text(value + "\n" if value else "", encoding="utf-8")
    print(f"Wrote fastlane metadata for {len(app['locales'])} locale(s) to {output}")
    pictures = copy_screenshots(app, root / "screenshots")
    print(f"Copied {pictures} screenshot(s) to {root / 'screenshots'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
