"""Tests for scripts/latest_version_code.py against a canned track."""
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import latest_version_code  # noqa: E402
from sync_subscriptions import SyncError  # noqa: E402


class FakePlay:
    def __init__(self, releases):
        self.releases = releases
        self.calls = []

    def request(self, method, url, params=None, body=None):
        self.calls.append((method, url.rsplit("/", 2)[-2:]))
        if method == "POST":
            return {"id": "e1"}
        if method == "GET":
            return {"releases": self.releases}
        return {}


class LatestVersionCodeTest(unittest.TestCase):
    def test_the_newest_code_of_every_release(self):
        play = FakePlay([{"versionCodes": ["80"]}, {"versionCodes": ["83", "81"]}])
        self.assertEqual(latest_version_code.latest_version_code(play), 83)
        # The edit it opened to look is thrown away again.
        self.assertEqual(play.calls[-1][0], "DELETE")

    def test_an_empty_track_is_an_error(self):
        with self.assertRaises(SyncError):
            latest_version_code.latest_version_code(FakePlay([]))


if __name__ == "__main__":
    unittest.main()
