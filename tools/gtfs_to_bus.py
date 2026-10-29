#!/usr/bin/env python3
"""
Adds Rapid KL bus routes from Prasarana's official GTFS feed to app/src/main/assets/bus.json.

    curl -L -o rapid.zip "https://api.data.gov.my/gtfs-static/prasarana?category=rapid-bus-kl"
    python3 tools/gtfs_to_bus.py rapid.zip T789 [more route numbers ...]

For each route: the stop order and minutes between stops (from the weekday trip with the most stops), the
service bands (start, end, minutes between buses, from frequencies.txt) for weekdays and weekends, and every stop
as a place with its coordinates. Place ids are "R" + the stop code ("KL1109 PERPUSTAKAAN UM" -> R1109) unless a
stop is mapped onto an existing UM shuttle place in SAME_PLACE. Existing routes and places with the same ids are
replaced; everything else in bus.json is kept. Bump generatedAt so phones download the new file.
"""
import csv
import io
import json
import os
import re
import sys
import zipfile
from collections import defaultdict
from datetime import date

BUS_JSON = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "bus.json")

# GTFS stop_id -> existing bus.json place id, where a Rapid stop is the same place as a UM shuttle stop
SAME_PLACE = {
    "1006747": "CFS",  # KL1108 PUSAT ASASI SAINS UM = Centre for Foundation in Science (PASUM)
}

DAYS = {"monday": 1, "tuesday": 2, "wednesday": 3, "thursday": 4, "friday": 5, "saturday": 6, "sunday": 7}


def read(z, name):
    with z.open(name) as f:
        return list(csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig")))


def minutes(t):
    h, m, s = (int(x) for x in t.split(":"))
    return h * 60 + m + s / 60


def hm(m):
    m = int(round(m))
    return "%02d:%02d" % (m // 60, m % 60)


def nice(name):
    """'KL1109 PERPUSTAKAAN UM' -> 'Perpustakaan UM (KL1109)'; '(M) ' marks are dropped."""
    name = re.sub(r"^\(M\)\s*", "", name.strip())
    m = re.match(r"^([A-Z]{2}\d{2,4})\s+(.*)$", name)
    code, rest = (m.group(1), m.group(2)) if m else ("", name)
    keep = {"UM", "LRT", "MRT", "PKNS", "KL", "SK", "SMK", "UIA", "PPUM", "KWSP"}

    def word(w):
        # capitalise each part of "AR-RAHMAN", "KERINCHI/KL", "(TIMUR)"
        return re.sub(r"[A-Za-z]+", lambda m: m.group(0) if m.group(0) in keep else m.group(0).capitalize(), w)
    text = " ".join(word(w) for w in rest.split())
    return f"{text} ({code})" if code else text


def place_id(stop_id, stop_name):
    if stop_id in SAME_PLACE:
        return SAME_PLACE[stop_id]
    m = re.search(r"([A-Z]{2})(\d{2,4})", stop_name)
    return "R" + (m.group(2) if m else stop_id)


def main(zip_path, wanted):
    z = zipfile.ZipFile(zip_path)
    routes = {r["route_short_name"]: r for r in read(z, "routes.txt")}
    stops = {s["stop_id"]: s for s in read(z, "stops.txt")}
    trips = read(z, "trips.txt")
    service_days = {}
    for c in read(z, "calendar.txt"):
        service_days[c["service_id"]] = sorted(d for k, d in DAYS.items() if c.get(k) == "1")
    times = defaultdict(list)
    want_ids = {routes[w]["route_id"] for w in wanted if w in routes}
    trip_ids = {t["trip_id"] for t in trips if t["route_id"] in want_ids}
    for r in read(z, "stop_times.txt"):
        if r["trip_id"] in trip_ids:
            times[r["trip_id"]].append(r)
    freqs = defaultdict(list)
    for f in read(z, "frequencies.txt"):
        if f["trip_id"] in trip_ids:
            freqs[f["trip_id"]].append(f)

    data = json.load(open(BUS_JSON, encoding="utf-8"))
    places = {p["id"]: p for p in data["places"]}
    out_routes = [r for r in data["routes"] if r["id"] not in wanted]

    for short in wanted:
        if short not in routes:
            print(f"{short}: not in this GTFS feed (discontinued or renamed?) - skipped", file=sys.stderr)
            continue
        r = routes[short]
        rtrips = [t for t in trips if t["route_id"] == r["route_id"]]
        # stop pattern: the weekday trip with the most stops (fallback: any trip)
        def nstops(t):
            return len(times[t["trip_id"]])
        weekday = [t for t in rtrips if 1 in service_days.get(t["service_id"], [])]
        pattern = max(weekday or rtrips, key=nstops)
        rows = sorted(times[pattern["trip_id"]], key=lambda x: int(x["stop_sequence"]))
        t0 = minutes(rows[0]["departure_time"])
        ids, offs = [], []
        for row in rows:
            s = stops[row["stop_id"]]
            pid = place_id(row["stop_id"], s["stop_name"])
            ids.append(pid)
            offs.append(round(minutes(row["arrival_time"]) - t0))
            if pid not in places or pid.startswith("R"):
                old = places.get(pid, {})
                places[pid] = {**old, "id": pid, "name": old.get("name") if pid in SAME_PLACE.values() else nice(s["stop_name"]),
                               "stop": True, "lat": round(float(s["stop_lat"]), 6), "lng": round(float(s["stop_lon"]), 6)}
            else:
                # an existing UM shuttle place: keep its name, add the coordinates
                places[pid].setdefault("lat", round(float(s["stop_lat"]), 6))
                places[pid].setdefault("lng", round(float(s["stop_lon"]), 6))
        bands = []
        for t in rtrips:
            for f in freqs[t["trip_id"]]:
                bands.append({"days": service_days.get(t["service_id"], []), "from": hm(minutes(f["start_time"])),
                              "to": hm(minutes(f["end_time"])), "every": int(f["headway_secs"]) // 60})
        bands.sort(key=lambda b: (b["days"][0] if b["days"] else 9, b["from"]))
        route = {
            "id": short, "operator": "rapid", "name": r["route_long_name"], "color": "#" + (r.get("route_color") or "008716"),
            "loop": ids[0] == ids[-1], "stops": ids, "offsets": offs, "bands": bands,
        }
        out_routes.append(route)
        print(f"{short}: {len(ids)} stops, {offs[-1]} min end to end, {len(bands)} service bands", file=sys.stderr)

    data["routes"] = out_routes
    data["places"] = list(places.values())
    data["generatedAt"] = date.today().isoformat()
    with open(BUS_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
