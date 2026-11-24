#!/usr/bin/env python3
"""Pushes PlayStore/ to Google Play, the way the iPhone app's release pushes
AppStore/ to App Store Connect.

--listing        the store listing of every language in listing.json (title,
                 short and full description), and the graphics: icon-512.png,
                 feature-graphic.png and screenshots/phone/*.png, which
                 replace what Play has.
--subscriptions  the Pro subscription from subscriptions.json: created with
                 its base plans when missing, its listing kept in step, and
                 every base plan activated. The price is set in euros and
                 converted to every other region by Google, the way the Play
                 Console does it. An existing base plan's price is not
                 changed (that moves existing subscribers): the script says
                 when it differs, and you change it in the Play Console.

Not done here, because Google only allows it in the Play Console: the content
rating questionnaire, the target audience, the data safety form and sending
the app for review.

Signs in with Google's application default credentials: in the workflow, the
file google-github-actions/auth writes (no key, see android/PLAY_STORE.md).
The service account needs "Manage store presence" for the app. Needs the
google-auth and requests packages.

Run: python3 scripts/sync_play.py --listing --subscriptions
"""
import argparse
import json
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "PlayStore"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
# The region list subscriptions are priced against; Google's current one.
REGIONS_VERSION = {"regionsVersion.version": "2022/02"}
CURRENCY = "EUR"


class SyncError(Exception):
    pass


class NotFound(SyncError):
    pass


class PlayApi:
    """The Google Play Developer API over an authorized requests session."""

    def __init__(self, session):
        self.session = session

    def request(self, method, url, params=None, body=None, data=None, content_type=None):
        headers = {"Content-Type": content_type} if content_type else {}
        response = self.session.request(method, url, params=params, json=body, data=data, headers=headers)
        if response.status_code == 404:
            raise NotFound(f"{method} {url}: not found")
        if response.status_code >= 400:
            raise SyncError(f"{method} {url}: HTTP {response.status_code}: {response.text}")
        return response.json() if response.content else {}


def money(amount, currency=CURRENCY):
    """google.type.Money for "19.99"."""
    value = Decimal(amount)
    units = int(value)
    return {"currencyCode": currency, "units": str(units), "nanos": int((value - units) * 1_000_000_000)}


def same_money(a, b):
    def amount(m):
        return Decimal(str(m.get("units", "0"))) + Decimal(m.get("nanos", 0)) / 1_000_000_000
    return a.get("currencyCode") == b.get("currencyCode") and amount(a) == amount(b)


class Sync:
    def __init__(self, api, folder=ROOT, log=print):
        self.api = api
        self.folder = Path(folder)
        self.log = log
        self.listing = json.loads((self.folder / "listing.json").read_text(encoding="utf-8"))
        self.config = json.loads((self.folder / "subscriptions.json").read_text(encoding="utf-8"))
        self.package = self.listing["package_name"]
        self.app = f"{API}/{self.package}"

    # Store listing

    def push_listing(self):
        edit = self.api.request("POST", f"{self.app}/edits", body={})["id"]
        base = f"{self.app}/edits/{edit}"
        for language, text in self.listing["languages"].items():
            self.api.request("PUT", f"{base}/listings/{language}", body={
                "language": language,
                "title": text["title"],
                "shortDescription": text["short_description"],
                "fullDescription": text["full_description"],
            })
            self.log(f"Listing {language}: text")
            for kind, files in self.images().items():
                self.api.request("DELETE", f"{base}/listings/{language}/{kind}")
                for path in files:
                    self.api.request("POST", f"{UPLOAD}/{self.package}/edits/{edit}/listings/{language}/{kind}",
                                     params={"uploadType": "media"}, data=path.read_bytes(), content_type="image/png")
                self.log(f"Listing {language}: {kind} ({len(files)})")
        self.api.request("POST", f"{base}:commit")
        self.log("Listing committed")

    def images(self):
        """Play's image type → the files for it, in order."""
        return {
            "icon": [self.folder / "icon-512.png"],
            "featureGraphic": [self.folder / "feature-graphic.png"],
            "phoneScreenshots": sorted((self.folder / "screenshots/phone").glob("*.png")),
        }

    # Subscription

    def subscription_listings(self):
        return [{
            "languageCode": self.listing["default_language"],
            "title": self.config["name"],
            "description": self.config["description"],
            "benefits": self.config["benefits"],
        }]

    def base_plan(self, plan):
        converted = self.api.request("POST", f"{self.app}/pricing:convertRegionPrices",
                                     body={"price": money(plan["price_eur"])})
        regions = [{"regionCode": region, "newSubscriberAvailability": True, "price": value["price"]}
                   for region, value in sorted(converted.get("convertedRegionPrices", {}).items())]
        base_plan = {
            "basePlanId": plan["base_plan_id"],
            "autoRenewingBasePlanType": {"billingPeriodDuration": plan["period"]},
            "regionalConfigs": regions,
        }
        other = converted.get("convertedOtherRegionsPrice")
        if other:
            base_plan["otherRegionsConfig"] = {
                "usdPrice": other["usdPrice"], "eurPrice": other["eurPrice"], "newSubscriberAvailability": True,
            }
        return base_plan

    def push_subscriptions(self):
        product = self.config["product_id"]
        url = f"{self.app}/subscriptions/{product}"
        try:
            existing = self.api.request("GET", url)
        except NotFound:
            existing = None
        if existing is None:
            existing = self.api.request("POST", f"{self.app}/subscriptions", params={"productId": product, **REGIONS_VERSION}, body={
                "packageName": self.package,
                "productId": product,
                "listings": self.subscription_listings(),
                "basePlans": [self.base_plan(plan) for plan in self.config["base_plans"]],
            })
            self.log(f"Subscription {product}: created")
        else:
            if existing.get("listings") != self.subscription_listings():
                existing = self.api.request("PATCH", url, params={"updateMask": "listings", **REGIONS_VERSION}, body={
                    "packageName": self.package, "productId": product, "listings": self.subscription_listings(),
                })
                self.log(f"Subscription {product}: listing updated")
            self.check_base_plans(existing)
        for base_plan in existing.get("basePlans", []):
            if base_plan.get("state") != "ACTIVE":
                plan_id = base_plan["basePlanId"]
                self.api.request("POST", f"{url}/basePlans/{plan_id}:activate", body={
                    "packageName": self.package, "productId": product, "basePlanId": plan_id,
                })
                self.log(f"Subscription {product}: base plan {plan_id} activated")

    def check_base_plans(self, existing):
        """Says what differs from the repo; changing it is left to the Play Console."""
        found = {plan["basePlanId"]: plan for plan in existing.get("basePlans", [])}
        for plan in self.config["base_plans"]:
            have = found.get(plan["base_plan_id"])
            if have is None:
                self.log(f"Warning: base plan {plan['base_plan_id']} is missing in Play; add it in the Play Console.")
                continue
            period = have.get("autoRenewingBasePlanType", {}).get("billingPeriodDuration")
            if period != plan["period"]:
                self.log(f"Warning: base plan {plan['base_plan_id']} renews every {period} in Play, not {plan['period']}.")
            prices = [c["price"] for c in have.get("regionalConfigs", []) if c["price"].get("currencyCode") == CURRENCY]
            if prices and not any(same_money(p, money(plan["price_eur"])) for p in prices):
                self.log(f"Warning: base plan {plan['base_plan_id']} has another euro price in Play than "
                         f"€{plan['price_eur']}; change it in the Play Console.")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--listing", action="store_true", help="push the store listing and graphics")
    parser.add_argument("--subscriptions", action="store_true", help="create or update the Pro subscription")
    args = parser.parse_args()
    if not (args.listing or args.subscriptions):
        parser.error("nothing to do: pass --listing and/or --subscriptions")

    import google.auth
    from google.auth.transport.requests import AuthorizedSession
    credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
    sync = Sync(PlayApi(AuthorizedSession(credentials)))
    try:
        if args.subscriptions:
            sync.push_subscriptions()
        if args.listing:
            sync.push_listing()
    except SyncError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
