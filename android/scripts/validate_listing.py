#!/usr/bin/env python3
"""Checks the Play Store listing in PlayStore/ against Google Play's limits and the app.

- PlayStore/listing.json: text lengths, the "not made by Scaleway" line, no
  "Control Center", and the links the app shows (model/Links.kt).
- PlayStore/subscriptions.json: the product and base plans match ProProducts
  (kit Navigation.kt) and the test store's plans and prices (MockProStore).
- PlayStore/screenshots/phone: 2 to 8 pictures, none more than twice as long
  as wide; icon-512.png and feature-graphic.png at Play's sizes.

Run: python3 scripts/validate_listing.py   (exit code 1 on any problem)
"""
import json
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STORE = ROOT / "PlayStore"
LINKS = ROOT / "app/src/main/kotlin/com/pvd/scwcontrolroom/model/Links.kt"
PRO_STORE = ROOT / "app/src/main/kotlin/com/pvd/scwcontrolroom/model/ProStore.kt"
PRODUCTS = ROOT / "kit/src/main/kotlin/com/pvd/scwcontrolroom/kit/Navigation.kt"
BUILD = ROOT / "app/build.gradle.kts"

# Google Play Console limits (characters).
LIMITS = {"title": 30, "short_description": 80, "full_description": 4000, "release_notes": 500}
# "Control Center" is Apple's; the iPhone listing avoids it, and so does this one.
FORBIDDEN = re.compile(r"control\s+center", re.IGNORECASE)
# The app uses Scaleway's name, so the listing has to say it is not Scaleway's.
DISCLAIMER = "not made, endorsed or supported by Scaleway"


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as f:
        header = f.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", header[16:24])


def constant(source: str, name: str) -> str | None:
    match = re.search(rf'const val {name} = "([^"]+)"', source)
    return match.group(1) if match else None


def main() -> int:
    problems: list[str] = []
    listing = json.loads((STORE / "listing.json").read_text(encoding="utf-8"))

    package = re.search(r'applicationId = "([^"]+)"', BUILD.read_text(encoding="utf-8"))
    if not package or package.group(1) != listing.get("package_name"):
        problems.append("listing.json: package_name does not match applicationId in app/build.gradle.kts")

    links = LINKS.read_text(encoding="utf-8")
    for key, name in (("privacy_url", "PRIVACY"), ("support_url", "SUPPORT")):
        if constant(links, name) != listing.get(key):
            problems.append(f"Links.kt: {name} does not match listing.json {key} ({listing.get(key)})")

    for language, text in listing.get("languages", {}).items():
        for key, limit in LIMITS.items():
            value = text.get(key)
            if not isinstance(value, str) or not value.strip():
                problems.append(f"{language}: {key} is missing or empty")
            elif len(value) > limit:
                problems.append(f"{language}: {key} has {len(value)} characters (max {limit})")
        for key in ("title", "short_description"):
            if FORBIDDEN.search(text.get(key, "")):
                problems.append(f"{language}: {key} must not contain 'Control Center' (Apple's)")
        if DISCLAIMER not in text.get("full_description", ""):
            problems.append(f"{language}: full_description must say the app is {DISCLAIMER}")
        if listing.get("privacy_url") not in text.get("full_description", ""):
            problems.append(f"{language}: full_description must link the privacy policy")
    if listing.get("default_language") not in listing.get("languages", {}):
        problems.append("listing.json: default_language has no text")

    subscriptions = json.loads((STORE / "subscriptions.json").read_text(encoding="utf-8"))
    products = PRODUCTS.read_text(encoding="utf-8")
    if constant(products, "PRODUCT") != subscriptions.get("product_id"):
        problems.append("subscriptions.json: product_id does not match ProProducts.PRODUCT")
    # Google Play's limits for a subscription's listing.
    for key, limit in (("name", 55), ("description", 80)):
        if not 0 < len(subscriptions.get(key, "")) <= limit:
            problems.append(f"subscriptions.json: {key} must have 1 to {limit} characters")
    benefits = subscriptions.get("benefits", [])
    if len(benefits) > 4 or any(not 0 < len(b) <= 40 for b in benefits):
        problems.append("subscriptions.json: at most 4 benefits, each 1 to 40 characters")
    plans = {plan["base_plan_id"]: plan for plan in subscriptions.get("base_plans", [])}
    in_app = {constant(products, "MONTHLY"), constant(products, "YEARLY")}
    if set(plans) != in_app:
        problems.append(f"subscriptions.json: base plans {sorted(plans)} do not match ProProducts {sorted(in_app)}")
    test_store = PRO_STORE.read_text(encoding="utf-8")
    for plan in plans.values():
        per = {"P1M": "month", "P1Y": "year"}.get(plan.get("period"))
        if per is None:
            problems.append(f"subscriptions.json: {plan['base_plan_id']} has an unknown period {plan.get('period')}")
        elif f'"€{plan["price_eur"]} per {per}"' not in test_store:
            problems.append(f"MockProStore: no plan at €{plan['price_eur']} per {per} ({plan['base_plan_id']})")

    shots = sorted((STORE / "screenshots/phone").glob("*.png"))
    if not 2 <= len(shots) <= 8:
        problems.append(f"screenshots/phone: {len(shots)} pictures (Google Play takes 2 to 8)")
    for shot in shots:
        width, height = png_size(shot)
        if min(width, height) < 320 or max(width, height) > 3840 or max(width, height) > 2 * min(width, height):
            problems.append(f"{shot.name}: {width} x {height} is not a size Google Play takes")
    for name, size in (("icon-512.png", (512, 512)), ("feature-graphic.png", (1024, 500))):
        if not (STORE / name).exists():
            problems.append(f"{name} is missing")
        elif png_size(STORE / name) != size:
            problems.append(f"{name} must be {size[0]} x {size[1]}")

    for problem in problems:
        print(problem)
    if problems:
        return 1
    print(f"Checked {len(listing.get('languages', {}))} language(s), {len(plans)} base plan(s), {len(shots)} screenshot(s): OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
