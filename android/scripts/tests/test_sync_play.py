"""Tests for scripts/sync_play.py against an in-memory Google Play.

The fake keeps state like the real API, so running the sync twice shows that
a second run only does what must be done every time. No network and no
credentials are involved.
"""
import io
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import sync_play  # noqa: E402
from sync_play import API, NotFound, UPLOAD  # noqa: E402

APP = f"{API}/com.pvd.scwcontrolroom"


class FakePlay:
    def __init__(self):
        self.subscription = None
        self.calls = []

    def request(self, method, url, params=None, body=None, data=None, content_type=None):
        self.calls.append((method, url.replace(APP, "").replace(f"{UPLOAD}/com.pvd.scwcontrolroom", "UPLOAD")))
        if url.endswith("/pricing:convertRegionPrices"):
            price = body["price"]
            return {
                "convertedRegionPrices": {
                    "NL": {"regionCode": "NL", "price": price},
                    "US": {"regionCode": "US", "price": {"currencyCode": "USD", "units": "2", "nanos": 490000000}},
                },
                "convertedOtherRegionsPrice": {"usdPrice": {"currencyCode": "USD"}, "eurPrice": price},
            }
        if url.endswith("/subscriptions") and method == "POST":
            self.subscription = {**body, "basePlans": [{**plan, "state": "DRAFT"} for plan in body["basePlans"]]}
            return self.subscription
        if "/subscriptions/pro" in url and method == "GET":
            if self.subscription is None:
                raise NotFound("not found")
            return self.subscription
        if "/subscriptions/pro" in url and method == "PATCH":
            self.subscription["listings"] = body["listings"]
            return self.subscription
        if url.endswith(":activate"):
            plan_id = url.split("/basePlans/")[1].split(":")[0]
            for plan in self.subscription["basePlans"]:
                if plan["basePlanId"] == plan_id:
                    plan["state"] = "ACTIVE"
            return {}
        if url.endswith("/edits") and method == "POST":
            return {"id": "e1"}
        return {}


class SyncPlayTest(unittest.TestCase):
    def sync(self, play):
        log = io.StringIO()
        return sync_play.Sync(play, log=lambda line: log.write(line + "\n")), log

    def test_money(self):
        self.assertEqual(sync_play.money("19.99"), {"currencyCode": "EUR", "units": "19", "nanos": 990000000})
        self.assertEqual(sync_play.money("1.99"), {"currencyCode": "EUR", "units": "1", "nanos": 990000000})
        self.assertTrue(sync_play.same_money({"currencyCode": "EUR", "units": "1", "nanos": 990000000},
                                             sync_play.money("1.99")))

    def test_the_subscription_is_created_priced_in_every_region_and_activated(self):
        play = FakePlay()
        sync, log = self.sync(play)
        sync.push_subscriptions()
        plans = {plan["basePlanId"]: plan for plan in play.subscription["basePlans"]}
        self.assertEqual(set(plans), {"yearly", "monthly"})
        self.assertEqual(plans["yearly"]["autoRenewingBasePlanType"]["billingPeriodDuration"], "P1Y")
        self.assertEqual([c["regionCode"] for c in plans["yearly"]["regionalConfigs"]], ["NL", "US"])
        nl = next(c for c in plans["monthly"]["regionalConfigs"] if c["regionCode"] == "NL")
        self.assertEqual(nl["price"], sync_play.money("1.99"))
        self.assertTrue(all(plan["state"] == "ACTIVE" for plan in plans.values()))
        self.assertEqual(play.subscription["listings"][0]["title"], "ControlRoom Pro")
        self.assertIn("created", log.getvalue())

    def test_a_second_run_changes_nothing(self):
        play = FakePlay()
        self.sync(play)[0].push_subscriptions()
        play.calls.clear()
        sync, log = self.sync(play)
        sync.push_subscriptions()
        self.assertEqual(play.calls, [("GET", "/subscriptions/pro")])
        self.assertNotIn("Warning", log.getvalue())

    def test_another_price_in_play_is_reported_not_changed(self):
        play = FakePlay()
        self.sync(play)[0].push_subscriptions()
        for config in play.subscription["basePlans"][0]["regionalConfigs"]:
            if config["regionCode"] == "NL":
                config["price"] = sync_play.money("24.99")
        sync, log = self.sync(play)
        sync.push_subscriptions()
        self.assertIn("another euro price", log.getvalue())
        self.assertNotIn("PATCH", [method for method, _ in play.calls[-1:]])

    def test_the_listing_replaces_text_and_graphics_in_one_edit(self):
        play = FakePlay()
        self.sync(play)[0].push_listing()
        calls = play.calls
        self.assertEqual(calls[0], ("POST", "/edits"))
        self.assertEqual(calls[-1], ("POST", "/edits/e1:commit"))
        self.assertIn(("PUT", "/edits/e1/listings/en-US"), calls)
        uploads = [url for method, url in calls if method == "POST" and url.startswith("UPLOAD")]
        self.assertEqual(sum("phoneScreenshots" in url for url in uploads), 8)
        self.assertEqual(sum(url.endswith("/icon") for url in uploads), 1)
        self.assertEqual(sum(url.endswith("/featureGraphic") for url in uploads), 1)
        # Old pictures go before the new ones come.
        self.assertLess(calls.index(("DELETE", "/edits/e1/listings/en-US/phoneScreenshots")),
                        calls.index(("POST", "UPLOAD/edits/e1/listings/en-US/phoneScreenshots")))


if __name__ == "__main__":
    unittest.main()
