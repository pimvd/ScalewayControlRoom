#!/usr/bin/env python3
"""Converts PlayStore/ into fastlane supply's layout, as the iPhone app's
scripts/metadata_to_fastlane.py does for deliver.

Writes <output_dir>/metadata/android/<language>/ with title, short and full
description, the release notes as changelogs/default.txt (shown with every
upload), and images/: icon, featureGraphic and phoneScreenshots. The output
is generated; do not commit it.

Run: python3 scripts/listing_to_fastlane.py [output_dir]   (default: fastlane)
"""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STORE = ROOT / "PlayStore"

# listing.json key -> fastlane file name (per language)
TEXTS = {
    "title": "title.txt",
    "short_description": "short_description.txt",
    "full_description": "full_description.txt",
}


def write(output: Path, store: Path = STORE) -> Path:
    listing = json.loads((store / "listing.json").read_text(encoding="utf-8"))
    metadata = output / "metadata" / "android"
    if metadata.exists():
        shutil.rmtree(metadata)
    screenshots = sorted((store / "screenshots" / "phone").glob("*.png"))
    for language, text in listing["languages"].items():
        folder = metadata / language
        (folder / "changelogs").mkdir(parents=True)
        for key, name in TEXTS.items():
            (folder / name).write_text(text[key] + "\n", encoding="utf-8")
        (folder / "changelogs" / "default.txt").write_text(text["release_notes"] + "\n", encoding="utf-8")
        images = folder / "images"
        (images / "phoneScreenshots").mkdir(parents=True)
        shutil.copyfile(store / "icon-512.png", images / "icon.png")
        shutil.copyfile(store / "feature-graphic.png", images / "featureGraphic.png")
        for shot in screenshots:
            shutil.copyfile(shot, images / "phoneScreenshots" / shot.name)
    return metadata


def main() -> int:
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "fastlane"
    metadata = write(output)
    print(f"Wrote fastlane metadata to {metadata}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
