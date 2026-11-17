#!/usr/bin/env python3
"""Builds a .spendidly import file for the Android app.

Usage: python3 scripts/build-android-import.py EXPENSES.csv OUTPUT.spendidly

OUTPUT is always written with the .spendidly extension.

The file is a zip with expenses.csv (Date,Amount,Category,Note, copied as-is) and
categories.json, with names and descriptions from GUIDANCE below plus any other
category the CSV uses (with an empty description).
"""
import csv, io, json, sys, zipfile
from pathlib import Path

GUIDANCE = {
    "Accessories": "Device add-ons and small carry items, like phone case, screen protector, USB-C cable.",
    "Bills": "Recurring or periodic utility and telecom bills, like Jio recharge, Airtel postpaid, broadband.",
    "Clothes": "Clothing and footwear, like T-shirts, socks, track pants.",
    "Digital": "Apps, online subscriptions, and store credit, like ChatGPT Plus, Amazon Prime, Apple Gift Card.",
    "Electronics": "Devices and computer hardware, like Pixel, MacBook, monitor.",
    "Family": "Purchases specifically for relatives, like medicine for a grandparent, slippers for a parent.",
    "Food & Snacks": "Prepared meals, snacks, and drinks, like Swiggy lunch, pizza, chai.",
    "Groceries": "Ingredients and pantry staples, like curd, ghee, fruit.",
    "Home": "Housing, furnishings, and household services, like rent, furniture rental, cook.",
    "Learning": "Courses and study materials, like Udemy course.",
    "Other": "Miscellaneous spending outside named categories, like tips, donations, gifts for friends.",
    "Personal": "Grooming, fitness, and personal errands, like haircut, gym membership, face wash.",
    "Transport": "Rides, public transit, and bus fares, like Uber, Ola, Rapido.",
}

source, output = Path(sys.argv[1]), Path(sys.argv[2]).with_suffix(".spendidly")

guidance = GUIDANCE

raw = source.read_bytes().decode("utf-8-sig")
rows = list(csv.DictReader(io.StringIO(raw)))
used = []
for row in rows:
    name = (row.get("Category") or "").strip()
    if name and name not in used:
        used.append(name)

names = sorted(set(guidance) | set(used), key=str.lower)
categories = [{"name": n, "description": guidance.get(n, "")} for n in names]

with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("expenses.csv", raw)
    z.writestr("categories.json", json.dumps(categories, indent=2, ensure_ascii=False))

print(f"{output}: {len(rows)} expenses, {len(categories)} categories")
missing = [n for n in used if n not in guidance]
if missing:
    print("Categories without a description:", ", ".join(missing))
