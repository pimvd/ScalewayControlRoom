#!/usr/bin/env python3
"""Creates or updates the Pro subscriptions in App Store Connect from
AppStore/subscriptions.json.

Safe to run on every release: the group and each subscription are looked up by
name and product ID, and only what differs from the repo is written: the group
and its display name, each subscription's name, period and review note, its
English name and description, its price, its availability and its review
screenshot.

A price is set in the base territory and, through Apple's equalized price
points, in every other territory, the way App Store Connect itself does it.
Existing subscribers keep their current price when it changes.

Not done here, because Apple only allows it in App Store Connect itself or it
is a one-off: the Paid Apps Agreement, an introductory offer (free trial), and
submitting the first subscriptions together with an app version.

Needs the release's App Store Connect API key (Admin role) in ASC_KEY_ID,
ASC_ISSUER_ID and ASC_KEY_PATH, and the `cryptography` package.

Run: python3 scripts/sync_subscriptions.py
"""
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from asc import NotFound, SyncError, client_from_environment  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent / "AppStore"
LOCALE = "en-US"


class Sync:
    def __init__(self, api, folder=ROOT, log=print):
        self.api = api
        self.folder = Path(folder)
        self.log = log
        self.config = json.loads((self.folder / "subscriptions.json").read_text(encoding="utf-8"))
        self.app = json.loads((self.folder / "app.json").read_text(encoding="utf-8"))
        self._territories = None

    def run(self):
        apps = self.api.request("GET", "/v1/apps", {"filter[bundleId]": self.app["bundle_id"]})["data"]
        if not apps:
            raise SyncError(f"no app with bundle ID {self.app['bundle_id']} in App Store Connect")
        group = self.group(apps[0]["id"])
        for product in self.config["products"]:
            subscription = self.subscription(group, product)
            self.localization(subscription, product)
            # Availability first: App Store Connect refuses a price in a
            # territory where the subscription is not available.
            self.availability(subscription)
            self.price(subscription, product)
            screenshot = self.folder / product.get("review_screenshot", "")
            if product.get("review_screenshot") and screenshot.is_file():
                self.screenshot(subscription, screenshot)
            elif product.get("review_screenshot"):
                self.log(f"{product['product_id']}: no {product['review_screenshot']} yet; review screenshot skipped")
        self.log("Subscriptions are in sync.")

    def group(self, app_id):
        wanted = self.config["group"]
        found = self.api.request("GET", f"/v1/apps/{app_id}/subscriptionGroups",
                                 {"filter[referenceName]": wanted["reference_name"], "limit": "200"})["data"]
        if found:
            group = found[0]["id"]
        else:
            self.log(f"Creating subscription group {wanted['reference_name']}")
            group = self.api.request("POST", "/v1/subscriptionGroups", body={"data": {
                "type": "subscriptionGroups",
                "attributes": {"referenceName": wanted["reference_name"]},
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}}})["data"]["id"]
        existing = {item["attributes"]["locale"]: item for item in self.api.request(
            "GET", f"/v1/subscriptionGroups/{group}/subscriptionGroupLocalizations", {"limit": "200"})["data"]}
        item = existing.get(LOCALE)
        if item is None:
            self.api.request("POST", "/v1/subscriptionGroupLocalizations", body={"data": {
                "type": "subscriptionGroupLocalizations",
                "attributes": {"name": wanted["name"], "locale": LOCALE},
                "relationships": {"subscriptionGroup": {"data": {"type": "subscriptionGroups", "id": group}}}}})
        elif item["attributes"].get("name") != wanted["name"]:
            self.api.request("PATCH", f"/v1/subscriptionGroupLocalizations/{item['id']}", body={"data": {
                "type": "subscriptionGroupLocalizations", "id": item["id"], "attributes": {"name": wanted["name"]}}})
        return group

    def subscription(self, group, product):
        found = self.api.request("GET", f"/v1/subscriptionGroups/{group}/subscriptions",
                                 {"filter[productId]": product["product_id"], "limit": "200"})["data"]
        wanted = {
            "name": product["reference_name"],
            "subscriptionPeriod": product["period"],
            "reviewNote": product["review_note"],
        }
        if not found:
            self.log(f"Creating {product['product_id']}")
            return self.api.request("POST", "/v1/subscriptions", body={"data": {
                "type": "subscriptions",
                # One level for all: monthly and yearly are the same Pro.
                "attributes": {**wanted, "productId": product["product_id"], "groupLevel": 1},
                "relationships": {"group": {"data": {"type": "subscriptionGroups", "id": group}}}}})["data"]["id"]
        subscription = found[0]
        if any(subscription["attributes"].get(key) != value for key, value in wanted.items()):
            self.log(f"Updating {product['product_id']}")
            self.api.request("PATCH", f"/v1/subscriptions/{subscription['id']}", body={"data": {
                "type": "subscriptions", "id": subscription["id"], "attributes": wanted}})
        return subscription["id"]

    def localization(self, subscription, product):
        existing = {item["attributes"]["locale"]: item for item in self.api.request(
            "GET", f"/v1/subscriptions/{subscription}/subscriptionLocalizations", {"limit": "200"})["data"]}
        wanted = {"name": product["name"], "description": product["description"]}
        item = existing.get(LOCALE)
        if item is None:
            self.api.request("POST", "/v1/subscriptionLocalizations", body={"data": {
                "type": "subscriptionLocalizations",
                "attributes": {**wanted, "locale": LOCALE},
                "relationships": {"subscription": {"data": {"type": "subscriptions", "id": subscription}}}}})
        elif any(item["attributes"].get(field) != value for field, value in wanted.items()):
            self.api.request("PATCH", f"/v1/subscriptionLocalizations/{item['id']}", body={"data": {
                "type": "subscriptionLocalizations", "id": item["id"], "attributes": wanted}})

    def price(self, subscription, product):
        territory, wanted = self.config["base_territory"], product["base_price"]
        current = self.api.request("GET", f"/v1/subscriptions/{subscription}/prices", {
            "include": "subscriptionPricePoint", "filter[territory]": territory, "limit": "200"})
        if any(item.get("type") == "subscriptionPricePoints" and item["attributes"].get("customerPrice") == wanted
               for item in current.get("included", [])):
            return
        points = self.api.request("GET", f"/v1/subscriptions/{subscription}/pricePoints",
                                  {"filter[territory]": territory, "limit": "8000"})["data"]
        base = next((p["id"] for p in points if p["attributes"]["customerPrice"] == wanted), None)
        if base is None:
            near = sorted(points, key=lambda p: abs(float(p["attributes"]["customerPrice"]) - float(wanted)))[:5]
            raise SyncError(f"{product['product_id']}: {wanted} is not a price point in {territory}; "
                            f"nearest: {', '.join(p['attributes']['customerPrice'] for p in near)}")
        equalized = self.api.request("GET", f"/v1/subscriptionPricePoints/{base}/equalizations",
                                     {"limit": "8000", "include": "territory"})["data"]
        self.log(f"Setting {product['product_id']} to {wanted} in {territory} and {len(equalized)} other territories")
        targets = [(territory, base)] + [
            (p["relationships"]["territory"]["data"]["id"], p["id"]) for p in equalized
            if p["relationships"]["territory"]["data"]["id"] != territory]
        for target, point in targets:
            self.api.request("POST", "/v1/subscriptionPrices", body={"data": {
                "type": "subscriptionPrices",
                "attributes": {"startDate": None, "preserveCurrentPrice": True},
                "relationships": {
                    "subscription": {"data": {"type": "subscriptions", "id": subscription}},
                    "subscriptionPricePoint": {"data": {"type": "subscriptionPricePoints", "id": point}},
                    "territory": {"data": {"type": "territories", "id": target}}}}})

    def availability(self, subscription):
        try:
            self.api.request("GET", f"/v1/subscriptions/{subscription}/subscriptionAvailability")
            return
        except NotFound:
            pass
        if self._territories is None:
            self._territories = [t["id"] for t in self.api.request("GET", "/v1/territories", {"limit": "200"})["data"]]
        self.api.request("POST", "/v1/subscriptionAvailabilities", body={"data": {
            "type": "subscriptionAvailabilities", "attributes": {"availableInNewTerritories": True},
            "relationships": {
                "subscription": {"data": {"type": "subscriptions", "id": subscription}},
                "availableTerritories": {"data": [{"type": "territories", "id": t} for t in self._territories]}}}})

    def screenshot(self, subscription, path):
        data = path.read_bytes()
        checksum = hashlib.md5(data).hexdigest()  # noqa: S324 - Apple asks for MD5
        try:
            current = self.api.request("GET", f"/v1/subscriptions/{subscription}/appStoreReviewScreenshot")["data"]
            if current and current.get("attributes", {}).get("sourceFileChecksum") == checksum:
                return
        except NotFound:
            pass
        self.log(f"Uploading review screenshot {path.name}")
        reserved = self.api.request("POST", "/v1/subscriptionAppStoreReviewScreenshots", body={"data": {
            "type": "subscriptionAppStoreReviewScreenshots",
            "attributes": {"fileName": path.name, "fileSize": len(data)},
            "relationships": {"subscription": {"data": {"type": "subscriptions", "id": subscription}}}}})["data"]
        for operation in reserved["attributes"]["uploadOperations"]:
            start = operation["offset"]
            self.api.upload(operation, data[start:start + operation["length"]])
        self.api.request("PATCH", f"/v1/subscriptionAppStoreReviewScreenshots/{reserved['id']}", body={"data": {
            "type": "subscriptionAppStoreReviewScreenshots", "id": reserved["id"],
            "attributes": {"uploaded": True, "sourceFileChecksum": checksum}}})


def main():
    try:
        Sync(client_from_environment()).run()
    except SyncError as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
