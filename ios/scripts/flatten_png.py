#!/usr/bin/env python3
"""Removes the alpha channel from PNGs, which App Store Connect refuses.

Run: python3 scripts/flatten_png.py <file.png>...   (needs Pillow)
"""
import sys

from PIL import Image

for path in sys.argv[1:]:
    image = Image.open(path)
    if image.mode != "RGB":
        image.convert("RGB").save(path)
    print(f"{path}: {image.size[0]} x {image.size[1]}")
