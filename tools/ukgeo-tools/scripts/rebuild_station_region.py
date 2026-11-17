"""Bake a station-layer review patch, retaining national reach IDs and all upstream constraints.

Reads the source dataset; writes only to a separate, initially empty output folder.
The output is diagnostic only, not a complete runtime dataset. Corrections can
continue beyond the selected tiles; report.json records changed outer edges.
"""

from __future__ import annotations

import argparse
from collections import defaultdict, deque
import csv
import json
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from ukgeo import river_stations as rs
from ukgeo.rivers import load_river_edges
from ukgeo.tiles import read_layer_tile


def changed_outer_edges(old: np.ndarray, new: np.ndarray, tile: tuple[int, int],
                        tiles: set[tuple[int, int]]) -> dict[str, int]:
    """Count changed border cells only where the neighbouring tile is outside the patch."""
    tx, tz = tile
    changed = old != new
    sides = {"left": ((tx - 1, tz), changed[:, 0]),
             "right": ((tx + 1, tz), changed[:, -1]),
             "top": ((tx, tz - 1), changed[0, :]),
             "bottom": ((tx, tz + 1), changed[-1, :])}
    return {name: int(np.count_nonzero(edge)) for name, (neighbour, edge) in sides.items()
            if neighbour not in tiles}


def rebuild(root: Path, rivers: Path, out: Path, bounds: list[int]) -> None:
    root, out = root.resolve(), out.resolve()
    if out == root or out.is_relative_to(root) or root.is_relative_to(out):
        raise ValueError("output must be separate from the source dataset")
    if out.exists() and any(out.iterdir()):
        raise ValueError("output must be initially empty")
    manifest = json.loads((root / "manifest.json").read_text())
    min_x, min_z, max_x, max_z = bounds
    if min_x > max_x or min_z > max_z:
        raise ValueError("bounds must be MIN_X MIN_Z MAX_X MAX_Z")
    world, size = manifest["world"], int(manifest["tile_size"])
    x0 = (min_x - world["minecraft_min_x"]) // size
    x1 = (max_x - world["minecraft_min_x"]) // size
    z0 = (min_z - world["minecraft_min_z"]) // size
    z1 = (max_z - world["minecraft_min_z"]) // size
    tiles = {(x, z) for z in range(z0, z1 + 1) for x in range(x0, x1 + 1)}
    print("Loading national topology to preserve reach IDs", flush=True)
    edges = load_river_edges(rivers, root / "manifest.json")
    reaches = sorted(rs.reaches_from_edges(edges, manifest), key=rs._reach_sort_key)
    del edges
    roots = rs._endpoint_roots(reaches, rs.MERGE_ENDPOINT_CELLS)
    incoming = defaultdict(list)
    wanted = set()
    for i, reach in enumerate(reaches):
        incoming[roots[i * 2 + 1]].append(i)
        pad = reach.half_width + rs.BANK_CARVE_REACH + 2
        xs, zs = zip(*reach.points)
        if (min(xs) - pad < (x1 + 1) * size and max(xs) + pad >= x0 * size
                and min(zs) - pad < (z1 + 1) * size and max(zs) + pad >= z0 * size):
            wanted.add(i)
    queue = deque(wanted)
    while queue:
        for parent in incoming[roots[queue.popleft() * 2]]:
            if parent not in wanted:
                wanted.add(parent)
                queue.append(parent)
    print(f"Sampling {len(wanted)} of {len(reaches)} reaches (region plus upstream closure)", flush=True)

    def lookups():
        return rs._HeightLookup(root, manifest).metres, rs.TileMaskLookup(root, manifest)

    numbered = [(i + 1, reaches[i]) for i in sorted(wanted)]
    stations = rs._build_stations_parallel(numbered, rs._unused_station_height, rs._unused_station_mask,
        rs.minecraft_y_params(manifest), min(rs._env_workers("STATION_BUILD_WORKERS", rs.STATION_BUILD_WORKERS), len(numbered)), lookups)
    corrected = rs._align_junction_water(reaches, stations)
    changed = sum(a.water_y != b.water_y for a, b in zip(stations, corrected))
    print(f"Corrected {changed} station levels; writing {len(tiles)} review tiles", flush=True)
    rs._write_station_tiles(out, manifest, corrected, tile_coords=tiles)
    report = {"source": str(root), "bounds": bounds, "diagnostic_only": True,
              "corrected_stations_in_upstream_closure": changed, "tiles": []}
    comparison = [["x", "z", "old_baked_water_y", "new_baked_water_y"]]
    for tx, tz in sorted(tiles):
        layers = {}
        for name, dtype in [("reach_id", "uint32_le"), ("water_y", "int16_le")]:
            layer = {"path": manifest["rivers"][name + "_path"], "dtype": dtype,
                     "extension": manifest["rivers"][name + "_extension"]}
            layers[name] = (read_layer_tile(root, layer, tx, tz, size), read_layer_tile(out, layer, tx, tz, size))
        old_reach, new_reach = layers["reach_id"]
        old_y, new_y = layers["water_y"]
        mismatches = int(np.count_nonzero(old_reach != new_reach))
        report["tiles"].append({"x": tx, "z": tz, "reach_id_mismatches": mismatches,
                                "water_y_changes": int(np.count_nonzero(old_y != new_y)),
                                "changed_outer_edges": changed_outer_edges(old_y, new_y, (tx, tz), tiles)})
        for lz, lx in zip(*np.nonzero((old_reach != 0) | (new_reach != 0))):
            x = world["minecraft_min_x"] + tx * size + int(lx)
            z = world["minecraft_min_z"] + tz * size + int(lz)
            if min_x <= x <= max_x and min_z <= z <= max_z:
                comparison.append([x, z, int(old_y[lz, lx]), int(new_y[lz, lx])])
    with (out / "junction.csv").open("w") as stream:
        csv.writer(stream).writerows(comparison)
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if any(any(tile["changed_outer_edges"].values()) for tile in report["tiles"]):
        print("Corrections reach the outer boundary. Do not overlay these diagnostic tiles on old data; "
              "rebake the full station layers in an isolated output for deployment.", flush=True)
    if any(tile["reach_id_mismatches"] for tile in report["tiles"]):
        raise RuntimeError("source geometry/reach IDs differ from installed tiles; review patch is not compatible")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--rivers", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--bounds", type=int, nargs=4, required=True, metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"))
    args = parser.parse_args()
    rebuild(args.root, args.rivers, args.out, args.bounds)
