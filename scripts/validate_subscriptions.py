#!/usr/bin/env python3
"""Checks AppStore/subscriptions.json, the Pro subscriptions the release
pipeline creates in App Store Connect (scripts/sync_subscriptions.py).

- The product IDs match ProProducts in the app (ControlRoomKit) and the
  StoreKit test configuration (StoreKit/ControlRoom.storekit), both ways.
- Names, descriptions, periods and prices fit App Store Connect's rules.

Run: python3 scripts/validate_subscriptions.py   (exit code 1 on any problem)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAME_MAX = 30
DESCRIPTION_MAX = 45
REFERENCE_MAX = 64
PERIODS = {"ONE_WEEK", "ONE_MONTH", "TWO_MONTHS", "THREE_MONTHS", "SIX_MONTHS", "ONE_YEAR"}
PRICE = re.compile(r"^\d+\.\d{2}$")


APP_SOURCE = "Packages/ControlRoomKit/Sources/ControlRoomKit/Navigation.swift"
STOREKIT = "StoreKit/ControlRoom.storekit"


def app_product_ids(root: Path) -> set[str]:
    """The IDs in `enum ProProducts`: its `static let x = "..."` strings."""
    source = (root / APP_SOURCE).read_text(encoding="utf-8")
    enum = re.search(r"enum ProProducts \{(.*?)\n\}", source, re.DOTALL).group(1)
    return set(re.findall(r'static let \w+ = "([\w.]+)"', enum))


def storekit_product_ids(root: Path) -> set[str]:
    config = json.loads((root / STOREKIT).read_text(encoding="utf-8"))
    return {s["productID"] for group in config["subscriptionGroups"] for s in group["subscriptions"]}


def validate(folder: Path, known_ids: set[str]) -> list[str]:
    problems: list[str] = []
    config = json.loads((folder / "subscriptions.json").read_text(encoding="utf-8"))
    products = config.get("products", [])
    ids = {p.get("product_id") for p in products}
    for missing in sorted(known_ids - ids):
        problems.append(f"subscriptions.json: the app sells {missing}, but it is not configured")
    for unknown in sorted(ids - known_ids):
        problems.append(f"subscriptions.json: {unknown} is configured, but the app does not know it")
    if not re.fullmatch(r"[A-Z]{3}", config.get("base_territory", "")):
        problems.append("subscriptions.json: base_territory must be a three-letter territory code, e.g. NLD")
    group = config.get("group", {})
    if not group.get("reference_name", "").strip():
        problems.append("subscriptions.json: group.reference_name is empty")
    if not 1 <= len(group.get("name", "")) <= NAME_MAX:
        problems.append(f"subscriptions.json: group.name must be 1-{NAME_MAX} characters")
    for product in products:
        label = product.get("product_id", "?")
        if product.get("period") not in PERIODS:
            problems.append(f"{label}: period must be one of {sorted(PERIODS)}")
        if not PRICE.match(product.get("base_price", "")):
            problems.append(f"{label}: base_price must look like 1.99")
        if not 1 <= len(product.get("reference_name", "")) <= REFERENCE_MAX:
            problems.append(f"{label}: reference_name must be 1-{REFERENCE_MAX} characters")
        if not 1 <= len(product.get("name", "")) <= NAME_MAX:
            problems.append(f"{label}: name must be 1-{NAME_MAX} characters")
        if not 1 <= len(product.get("description", "")) <= DESCRIPTION_MAX:
            problems.append(f"{label}: description must be 1-{DESCRIPTION_MAX} characters")
        if not product.get("review_note", "").strip():
            problems.append(f"{label}: review_note is empty")
    return problems


def main() -> int:
    known = app_product_ids(ROOT)
    problems = validate(ROOT / "AppStore", known)
    storekit = storekit_product_ids(ROOT)
    if storekit != known:
        problems.append(f"{STOREKIT} sells {sorted(storekit)}, the app {sorted(known)}")
    for problem in problems:
        print(f"error: {problem}")
    print(f"Checked {len(known)} subscription(s): {'OK' if not problems else f'{len(problems)} problem(s)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
