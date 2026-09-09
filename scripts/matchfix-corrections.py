#!/usr/bin/env python3
"""Applies a fixed list of metadata corrections through the fix-wrong-metadata API
(/api/media/admin/matches), so a freshly-scanned library doesn't need each title
retyped by hand.

Items are matched by fileName rather than mediaItemId, since the id is a fresh UUID
on every empty-database scan (a new server, a wiped demo DB, ...) while the filename
on disk is stable. Only items still sitting in the mismatch queue (metadataSource ==
FILENAME) are touched, so a correction applied once and then locked by the app is
never re-applied or overwritten.

Usage:
    python3 scripts/matchfix-corrections.py [--dry-run] [--base-url URL]

Auth, one of:
    KIDO_TOKEN                            a bearer token for a PARENT account
    KIDO_USERNAME + KIDO_PASSWORD         logged in automatically

Config (env, with defaults for local/dev use):
    KIDO_BASE_URL   default http://localhost:8080
    KIDO_ADMIN_KEY  default kido-admin-dev-key (override for anything but local testing)
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_ADMIN_KEY = "kido-admin-dev-key"

# fileName -> (title, year). fileName must match exactly what LibraryController
# indexed (MediaItem.fileName), not the full path.
CORRECTIONS = {
    "Aquaman HD.mp4": ("Aquaman", 2018),
    "DEATHLY HALLOW PART 2.mp4": ("Harry Potter and the Deathly Hallows: Part 2", 2011),
    "Gypsy.mkv": ("Gypsy", 2020),
    "HARRY 1.mp4": ("Harry Potter and the Philosopher's Stone", 2001),
    "Harry Potter and The Goblet Of Fire HD.mp4": ("Harry Potter and the Goblet of Fire", 2005),
    "Harry Potter and The Half Blood Prince HD.mp4": ("Harry Potter and the Half-Blood Prince", 2009),
    "Harry Potter and The Order Of The Phoenix HD.mp4": ("Harry Potter and the Order of the Phoenix", 2007),
    "KGF 2 720p.mp4": ("KGF: Chapter 2", 2022),
    "Master.mkv": ("Master", 2021),
    "Mission_Impossible_1996720p_BDRip.mkv": ("Mission: Impossible", 1996),
    "Mission.Impossible.Fallout 2018 720p HDRip @HindiHDCinema.mkv": ("Mission: Impossible - Fallout", 2018),
    "Mission_Impossible_Ghost_Protocol.mkv": ("Mission: Impossible - Ghost Protocol", 2011),
    "Mission_Impossible_II_2000720p_BDRip.mkv": ("Mission: Impossible II", 2000),
    "Mission_Impossible_III_2006720p.mkv": ("Mission: Impossible III", 2006),
    "Mission_Impossible_Rogue_Nation.mkv": ("Mission: Impossible - Rogue Nation", 2015),
    "Moviesda.Mobi - Gulu Gulu 2022 Original 720p HD.mp4": ("Gulu Gulu", 2022),
    "Moviesda.Mobi - Naai Sekar Returns 2022 HDRip 720p HD.mp4": ("Naai Sekar Returns", 2022),
    "Mukudhi_amman.mkv": ("Mookuthi Amman", 2020),
    "Pirates Of The Caribbean 1 (2003) HD (640x360).mp4": ("Pirates of the Caribbean: The Curse of the Black Pearl", 2003),
    "Pirates Of The Caribbean 2 (2006) HD (640x360).mp4": ("Pirates of the Caribbean: Dead Man's Chest", 2006),
    "Ragnarok HD.mp4": ("Thor: Ragnarok", 2017),
    "Soorarai_pottru.mkv": ("Soorarai Pottru", 2020),
    "www.TamilRockerss.ch - K.G.F Chapter 1 (2018)[Tamil Proper HQ HDRip - x264 - 700MB - ESubs].mkv": ("KGF: Chapter 1", 2018),
    "www.TamilRockers.ws - Kanchana 3 (2019) Tamil Proper HDRip x264 700MB ESubs.mkv": ("Kanchana 3", 2019),
    # 1_5037806186473193577.mkv deliberately excluded: no identifying information in
    # the filename, folder, or any candidate the app itself can offer. Someone has to
    # actually watch it to know what it is.
}


def request(method, url, headers, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as ex:
        try:
            payload = json.loads(ex.read().decode())
        except Exception:
            payload = {"message": ex.reason}
        return ex.code, payload


def login(base_url, username, password):
    status, payload = request(
        "POST", f"{base_url}/api/auth/login",
        {"Content-Type": "application/json"},
        {"usernameOrEmail": username, "password": password},
    )
    if status != 200:
        sys.exit(f"Login failed ({status}): {payload}")
    return payload["token"]


def fetch_queue(base_url, headers):
    items = []
    page = 0
    while True:
        status, payload = request(
            "GET", f"{base_url}/api/media/admin/matches?page={page}&size=100", headers)
        if status != 200:
            sys.exit(f"Failed to read mismatch queue ({status}): {payload}")
        items.extend(payload["items"])
        page += 1
        if page >= payload["totalPages"]:
            break
    return items


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("KIDO_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--dry-run", action="store_true", help="Print what would change without calling PUT")
    args = parser.parse_args()

    admin_key = os.environ.get("KIDO_ADMIN_KEY", DEFAULT_ADMIN_KEY)
    token = os.environ.get("KIDO_TOKEN")
    if not token:
        username = os.environ.get("KIDO_USERNAME")
        password = os.environ.get("KIDO_PASSWORD")
        if not username or not password:
            sys.exit("Set KIDO_TOKEN, or KIDO_USERNAME + KIDO_PASSWORD, for a PARENT account.")
        token = login(args.base_url, username, password)

    headers = {
        "Authorization": f"Bearer {token}",
        "X-Admin-Key": admin_key,
        "Content-Type": "application/json",
    }

    queue = fetch_queue(args.base_url, headers)
    by_filename = {item["fileName"]: item for item in queue}

    applied, skipped, missing = 0, 0, []
    for file_name, (title, year) in CORRECTIONS.items():
        item = by_filename.get(file_name)
        if item is None:
            missing.append(file_name)
            continue

        if args.dry_run:
            print(f"[dry-run] would set '{item['currentTitle']}' -> '{title}' ({year})  [{file_name}]")
            applied += 1
            continue

        status, result = request(
            "PUT", f"{args.base_url}/api/media/admin/matches/{item['mediaItemId']}",
            headers, {"title": title, "year": year},
        )
        if status == 200:
            print(f"OK   '{title}' ({year})  [{file_name}]")
            applied += 1
        else:
            print(f"FAIL '{title}' ({year})  [{file_name}] -> {status} {result}")
            skipped += 1

    print(f"\n{applied} applied, {skipped} failed, {len(missing)} not in this library's queue")
    if missing:
        print("Not found (already fixed, or this file isn't on this server's disk):")
        for name in missing:
            print(f"  - {name}")


if __name__ == "__main__":
    main()
