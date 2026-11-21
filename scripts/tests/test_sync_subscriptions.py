"""Tests for scripts/sync_subscriptions.py against an in-memory App Store Connect.

The fake keeps state like the real API, so running the sync twice shows that a
second run writes nothing. No network and no API key are involved.
"""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import sync_subscriptions  # noqa: E402
from asc import NotFound, SyncError  # noqa: E402

TERRITORIES = ["NLD", "USA", "DEU"]


class FakeAppStoreConnect:
    def __init__(self):
        self.groups, self.group_names, self.subs, self.sub_texts = {}, {}, {}, {}
        self.prices = {}  # (subscription, territory) -> price point id
        self.available = set()
        self.writes = []
        self.next = 0

    def new_id(self, prefix):
        self.next += 1
        return f"{prefix}{self.next}"

    @staticmethod
    def point(territory, price):
        return f"pp-{territory}-{price}"

    def request(self, method, path, params=None, body=None):
        params = params or {}
        if method != "GET":
            self.writes.append((method, path))
        data = (body or {}).get("data", {})
        attributes = data.get("attributes", {})
        rel = data.get("relationships", {})
        parts = path.strip("/").split("/")

        if method == "GET" and path == "/v1/apps":
            return {"data": [{"id": "app1"}]}
        if method == "GET" and path == "/v1/apps/app1/subscriptionGroups":
            return {"data": [{"id": g, "attributes": a} for g, a in self.groups.items()
                             if a["referenceName"] == params["filter[referenceName]"]]}
        if method == "POST" and path == "/v1/subscriptionGroups":
            gid = self.new_id("g")
            self.groups[gid] = attributes
            return {"data": {"id": gid}}
        if method == "GET" and path.endswith("/subscriptionGroupLocalizations"):
            return {"data": [{"id": i, "attributes": a} for i, (g, a) in self.group_names.items() if g == parts[2]]}
        if method == "POST" and path == "/v1/subscriptionGroupLocalizations":
            self.group_names[self.new_id("gl")] = (rel["subscriptionGroup"]["data"]["id"], attributes)
            return {}
        if method == "GET" and path.endswith("/subscriptions"):
            return {"data": [{"id": s, "attributes": a} for s, (g, a) in self.subs.items()
                             if g == parts[2] and a["productId"] == params["filter[productId]"]]}
        if method == "POST" and path == "/v1/subscriptions":
            sid = self.new_id("s")
            self.subs[sid] = (rel["group"]["data"]["id"], dict(attributes))
            return {"data": {"id": sid}}
        if method == "PATCH" and parts[1] == "subscriptions":
            self.subs[parts[2]][1].update(attributes)
            return {}
        if method == "GET" and path.endswith("/subscriptionLocalizations"):
            return {"data": [{"id": i, "attributes": a} for i, (s, a) in self.sub_texts.items() if s == parts[2]]}
        if method == "POST" and path == "/v1/subscriptionLocalizations":
            self.sub_texts[self.new_id("sl")] = (rel["subscription"]["data"]["id"], attributes)
            return {}
        if method == "PATCH" and parts[1] == "subscriptionLocalizations":
            self.sub_texts[parts[2]][1].update(attributes)
            return {}
        if method == "GET" and path.endswith("/prices"):
            point = self.prices.get((parts[2], params["filter[territory]"]))
            return {"data": [], "included": [] if point is None else [
                {"type": "subscriptionPricePoints", "attributes": {"customerPrice": point.rsplit("-", 1)[1]}}]}
        if method == "GET" and path.endswith("/pricePoints"):
            t = params["filter[territory]"]
            return {"data": [{"id": self.point(t, p), "attributes": {"customerPrice": p}}
                             for p in ("0.99", "1.99", "2.49", "19.99")]}
        if method == "GET" and path.endswith("/equalizations"):
            price = parts[2].rsplit("-", 1)[1]
            return {"data": [{"id": self.point(t, price), "relationships": {"territory": {"data": {"id": t}}}}
                             for t in TERRITORIES]}
        if method == "POST" and path == "/v1/subscriptionPrices":
            key = (rel["subscription"]["data"]["id"], rel["territory"]["data"]["id"])
            self.prices[key] = rel["subscriptionPricePoint"]["data"]["id"]
            return {}
        if method == "GET" and path.endswith("/subscriptionAvailability"):
            if parts[2] not in self.available:
                raise NotFound(path)
            return {"data": {}}
        if method == "GET" and path == "/v1/territories":
            return {"data": [{"id": t} for t in TERRITORIES]}
        if method == "POST" and path == "/v1/subscriptionAvailabilities":
            self.available.add(rel["subscription"]["data"]["id"])
            return {}
        raise AssertionError(f"unexpected call {method} {path}")


class SyncTest(unittest.TestCase):
    def setUp(self):
        self.folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.folder)
        for name in ("app.json", "subscriptions.json"):
            shutil.copy(SCRIPTS.parent / "AppStore" / name, self.folder / name)
        self.api = FakeAppStoreConnect()

    def sync(self):
        sync_subscriptions.Sync(self.api, self.folder, log=lambda _: None).run()

    def test_creates_everything_then_changes_nothing(self):
        self.sync()
        self.assertEqual(len(self.api.groups), 1)
        self.assertEqual(len(self.api.subs), 2)
        periods = sorted(a["subscriptionPeriod"] for _, a in self.api.subs.values())
        self.assertEqual(periods, ["ONE_MONTH", "ONE_YEAR"])
        # Every territory gets a price, and the base one is the configured price.
        monthly = next(s for s, (_, a) in self.api.subs.items() if a["productId"].endswith("monthly"))
        self.assertEqual({t for s, t in self.api.prices if s == monthly}, set(TERRITORIES))
        self.assertEqual(self.api.prices[(monthly, "NLD")], "pp-NLD-1.99")
        self.assertEqual(len(self.api.available), 2)

        self.api.writes.clear()
        self.sync()
        self.assertEqual(self.api.writes, [])

    def test_updates_a_changed_name(self):
        self.sync()
        texts = (self.folder / "subscriptions.json").read_text().replace('"Pro, monthly"', '"Pro, per month"')
        (self.folder / "subscriptions.json").write_text(texts)
        self.api.writes.clear()
        self.sync()
        self.assertEqual([w[0] for w in self.api.writes], ["PATCH"])

    def test_refuses_a_price_apple_does_not_offer(self):
        texts = (self.folder / "subscriptions.json").read_text().replace('"1.99"', '"2.00"')
        (self.folder / "subscriptions.json").write_text(texts)
        with self.assertRaisesRegex(SyncError, "not a price point.*nearest: 1.99"):
            self.sync()


if __name__ == "__main__":
    unittest.main()
