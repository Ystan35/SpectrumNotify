"""
Adds road-following lines to app/src/main/assets/bus.json: for every route, the road path between each pair of
consecutive stops, from the OSRM demo server (OpenStreetMap roads, driving, one-way streets respected).

    python tools/route_shapes.py app/src/main/assets/bus.json

Each route gets "shape": one list per stop-to-stop segment of [lat, lng] points (empty when a stop has no
coordinates; the app then draws nothing for that segment). Run it again after adding stops or coordinates.
"""
import json
import sys
import time
import urllib.request

OSRM = "https://router.project-osrm.org/route/v1/driving/{a};{b}?overview=full&geometries=geojson"
# footpaths ignore one-way streets: used when the driving path is a long detour (a stop snapped to the far side
# of a divided road, or a bus-only road the car profile does not know)
FOOT = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/{a};{b}?overview=full&geometries=geojson"
DETOUR = 2.5


def metres(p, q):
    import math
    return 6371000 * math.hypot(math.radians(q[0] - p[0]), math.radians(q[1] - p[1]) * math.cos(math.radians(p[0])))


def length(pts):
    return sum(metres(p, q) for p, q in zip(pts, pts[1:]))


def best_path(a, b):
    car = path(a, b)
    straight = metres(a, b)
    if car and length(car) <= DETOUR * straight:
        return car
    time.sleep(1.1)
    foot = path(a, b, FOOT)
    if foot and (not car or length(foot) < length(car)):
        return foot
    return car


def path(a, b, server=OSRM):
    url = server.format(a=f"{a[1]},{a[0]}", b=f"{b[1]},{b[0]}")
    req = urllib.request.Request(url, headers={"User-Agent": "SpectrumNotify-tools"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                d = json.load(r)
            if d.get("code") == "Ok":
                return [[round(lat, 5), round(lng, 5)] for lng, lat in d["routes"][0]["geometry"]["coordinates"]]
            return []
        except Exception as e:
            print("  retry", attempt + 1, e, file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    return []


def main(p):
    d = json.load(open(p, encoding="utf-8"))
    pos = {pl["id"]: (pl["lat"], pl["lng"]) for pl in d["places"] if "lat" in pl and "lng" in pl}
    cache = {}
    for r in d["routes"]:
        segs = []
        for a, b in zip(r["stops"], r["stops"][1:]):
            if a not in pos or b not in pos or a == b:
                segs.append([])
                continue
            if (a, b) not in cache:
                cache[(a, b)] = best_path(pos[a], pos[b])
                time.sleep(1.1)  # the demo server allows about one request a second
            segs.append(cache[(a, b)])
        r["shape"] = segs
        print(r["id"], sum(1 for s in segs if s), "of", len(segs), "segments")
    json.dump(d, open(p, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(sys.argv[1])
