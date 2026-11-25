"""Tests for scripts/listing_to_fastlane.py: PlayStore/ in fastlane supply's layout."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import listing_to_fastlane  # noqa: E402


class ListingToFastlaneTest(unittest.TestCase):
    def test_every_text_and_picture_lands_where_supply_looks(self):
        listing = json.loads((listing_to_fastlane.STORE / "listing.json").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as out:
            metadata = listing_to_fastlane.write(Path(out))
            folder = metadata / "en-US"
            text = listing["languages"]["en-US"]
            self.assertEqual((folder / "title.txt").read_text().strip(), text["title"])
            self.assertEqual((folder / "short_description.txt").read_text().strip(), text["short_description"])
            self.assertEqual((folder / "full_description.txt").read_text().strip(), text["full_description"])
            self.assertEqual((folder / "changelogs/default.txt").read_text().strip(), text["release_notes"])
            self.assertTrue((folder / "images/icon.png").is_file())
            self.assertTrue((folder / "images/featureGraphic.png").is_file())
            shots = sorted(p.name for p in (folder / "images/phoneScreenshots").glob("*.png"))
            self.assertEqual(shots, sorted(p.name for p in (listing_to_fastlane.STORE / "screenshots/phone").glob("*.png")))
            # A second run starts clean.
            listing_to_fastlane.write(Path(out))
            self.assertEqual(len(list((folder / "images/phoneScreenshots").glob("*.png"))), len(shots))


if __name__ == "__main__":
    unittest.main()
