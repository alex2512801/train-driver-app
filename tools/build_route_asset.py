#!/usr/bin/env python3
"""
Строит app/src/main/assets/route_hilok_chernyshevsk.json из сырой выгрузки OSM (export.geojson).

export.geojson не хранится в репозитории (это большой файл, полученный через Overpass Turbo,
см. ТЗ раздел 1) — положи его рядом со скриптом или передай путь первым аргументом.

Что делает скрипт:
  1. Оставляет только сегменты railway=rail внутри примерного bbox перегона
     Хилок — Карымская — Чернышевск-Забайкальский и убирает съезды/тупики/парковые пути
     (service = spur/siding/yard), но оставляет стрелочные соединения (crossover) — иначе
     граф рвётся на стыках путей на крупных станциях.
  2. Склеивает конечные точки сегментов, если они ближе ~15 м друг от друга (в OSM соседние
     way могут не иметь буквально совпадающих координат на стыке).
  3. Строит граф связности и находит кратчайший путь (по фактической длине) между узлом,
     ближайшим к Хилоку, и самым восточным узлом связной компоненты (Чернышевск-Забайкальский).
  4. Сохраняет упорядоченные точки пути в JSON.

Известное ограничение: даже после склейки конечных точек (шаг 2) граф распадается на несколько
десятков компонент — крупные станции (Карымская, Чернышевск-Забайкальский) иногда соединены с
остальной линией разрывом в несколько десятков метров, чуть больше порога склейки (видимо,
неточность исходной OSM-выгрузки на стыке путей). Поэтому после первой сборки графа скрипт
дополнительно ищет пары компонент, ближайшие точки которых не дальше GAP_BRIDGE_THRESHOLD_M
друг от друга, и явно соединяет их одним отрезком — без этого шага короткий путь Хилок →
Чернышевск-Забайкальский не находится.
"""
import sys
import json
import math
import heapq
from collections import defaultdict

LON_MIN, LON_MAX = 109.5, 118.5
LAT_MIN, LAT_MAX = 50.8, 52.8

# Примерные координаты станции Хилок — используются только чтобы найти ближайший узел линии,
# от которого начинается маршрут. Реального значения км+пк по ним НЕ вычисляется.
KHILOK_APPROX = (110.4692, 51.3549)  # (lon, lat)

MERGE_TOLERANCE_M = 15.0

# Компоненты графа, чьи ближайшие точки не дальше этого порога, считаются одной линией,
# разорванной неточностью исходных данных, и явно соединяются отдельным отрезком.
GAP_BRIDGE_THRESHOLD_M = 50.0


def in_bbox(coords):
    return any(LON_MIN <= c[0] <= LON_MAX and LAT_MIN <= c[1] <= LAT_MAX for c in coords)


def dist_m(a, b):
    lon1, lat1 = a
    lon2, lat2 = b
    dx = (lon2 - lon1) * 111_320 * math.cos(math.radians((lat1 + lat2) / 2))
    dy = (lat2 - lat1) * 111_320
    return math.hypot(dx, dy)


def seg_len_m(coords):
    return sum(dist_m(coords[i], coords[i + 1]) for i in range(len(coords) - 1))


def load_candidates(geojson_path):
    with open(geojson_path, encoding="utf-8") as f:
        data = json.load(f)
    candidates = []
    for feat in data["features"]:
        coords = feat["geometry"]["coordinates"]
        if not in_bbox(coords):
            continue
        if feat["properties"].get("service") in ("spur", "siding", "yard"):
            continue
        candidates.append(coords)
    return candidates


def merge_endpoints(candidates):
    parent = {}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x, y):
        rx, ry = find(x), find(y)
        if rx != ry:
            parent[rx] = ry

    endpoints = set()
    for coords in candidates:
        endpoints.add(tuple(coords[0]))
        endpoints.add(tuple(coords[-1]))
    for e in endpoints:
        parent[e] = e

    cell = 0.0015  # ~roughly 100-150m bucket, generous vs. the 15m merge tolerance
    buckets = defaultdict(list)
    for p in endpoints:
        buckets[(round(p[0] / cell), round(p[1] / cell))].append(p)

    for p in endpoints:
        cx, cy = round(p[0] / cell), round(p[1] / cell)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for q in buckets[(cx + dx, cy + dy)]:
                    if q != p and dist_m(p, q) <= MERGE_TOLERANCE_M:
                        union(p, q)

    return lambda pt: find(tuple(pt))


def build_graph(candidates, snap):
    graph = defaultdict(list)
    for coords in candidates:
        a, b = snap(coords[0]), snap(coords[-1])
        length = seg_len_m(coords)
        if length < 0.5:
            continue
        graph[a].append((b, length, coords))
        graph[b].append((a, length, list(reversed(coords))))
    return graph


def connected_components(graph):
    visited = set()
    components = []
    for start in graph:
        if start in visited:
            continue
        comp = []
        stack = [start]
        visited.add(start)
        while stack:
            u = stack.pop()
            comp.append(u)
            for v, _, _ in graph[u]:
                if v not in visited:
                    visited.add(v)
                    stack.append(v)
        components.append(comp)
    components.sort(key=len, reverse=True)
    return components


def bridge_close_components(graph):
    """Repeatedly finds the two closest components and joins them with a direct
    segment if they're within GAP_BRIDGE_THRESHOLD_M — see module docstring."""
    while True:
        components = connected_components(graph)
        if len(components) <= 1:
            return components

        best = None  # (distance, node_in_a, node_in_b)
        for i, comp_a in enumerate(components):
            for comp_b in components[i + 1:]:
                for na in comp_a:
                    for nb in comp_b:
                        d = dist_m(na, nb)
                        if best is None or d < best[0]:
                            best = (d, na, nb)

        if best is None or best[0] > GAP_BRIDGE_THRESHOLD_M:
            return components

        _, na, nb = best
        length = dist_m(na, nb)
        graph[na].append((nb, length, [na, nb]))
        graph[nb].append((na, length, [nb, na]))


def dijkstra(graph, nodeset, src, dst):
    dist = {src: 0.0}
    prev = {}
    pq = [(0.0, src)]
    visited = set()
    while pq:
        d, u = heapq.heappop(pq)
        if u in visited:
            continue
        visited.add(u)
        if u == dst:
            break
        for v, w, shape in graph.get(u, []):
            if v not in nodeset:
                continue
            nd = d + w
            if nd < dist.get(v, math.inf):
                dist[v] = nd
                prev[v] = (u, shape)
                heapq.heappush(pq, (nd, v))
    if dst not in dist:
        return None, None
    chain = []
    cur = dst
    while cur != src:
        u, shape = prev[cur]
        chain.append(shape)
        cur = u
    chain.reverse()
    coords = []
    for shape in chain:
        coords.extend(shape[1:] if coords and coords[-1] == shape[0] else shape)
    return coords, dist[dst]


def main():
    geojson_path = sys.argv[1] if len(sys.argv) > 1 else "export.geojson"
    out_path = sys.argv[2] if len(sys.argv) > 2 else "route_hilok_chernyshevsk.json"

    candidates = load_candidates(geojson_path)
    snap = merge_endpoints(candidates)
    graph = build_graph(candidates, snap)

    components = bridge_close_components(graph)
    # The through route is by far the largest connected piece — everything else left
    # over (spurs and sidings that share no endpoint with it, disconnected fragments
    # elsewhere in the bbox) is much smaller.
    main_component = set(max(components, key=len))
    start = min(main_component, key=lambda n: dist_m(n, KHILOK_APPROX))
    end = max(main_component, key=lambda n: n[0])  # easternmost node = Chernyshevsk end

    path, total_m = dijkstra(graph, main_component, start, end)
    if path is None:
        raise SystemExit("No path found between Khilok and Chernyshevsk ends — check the graph.")

    print(f"path points: {len(path)}, length: {total_m / 1000:.1f} km")

    out = [{"lat": round(lat, 6), "lon": round(lon, 6)} for lon, lat in path]
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {len(out)} points to {out_path}")


if __name__ == "__main__":
    main()
