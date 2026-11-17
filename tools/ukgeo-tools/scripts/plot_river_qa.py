"""Plot the CSV diagnostics emitted by the UKGeo RiverPlanningTest (no data baking)."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


def read_report(path: Path) -> dict[str, np.ndarray]:
    with path.open() as stream:
        rows = list(csv.DictReader(stream))
    return {key: np.array([float(row[key]) if row[key] else np.nan for row in rows]) for key in rows[0]}


def grid(data: dict[str, np.ndarray], values: np.ndarray) -> tuple[np.ndarray, tuple]:
    x, z = data["x"].astype(int), data["z"].astype(int)
    array = np.full((z.max() - z.min() + 1, x.max() - x.min() + 1), np.nan)
    array[z - z.min(), x - x.min()] = values
    return array, (x.min() - 0.5, x.max() + 0.5, z.max() + 0.5, z.min() - 0.5)


def panel(ax, data, values, title, *, vmin=None, vmax=None, cmap="viridis"):
    array, extent = grid(data, values)
    plot = ax.imshow(array, extent=extent, origin="upper", interpolation="nearest",
                     cmap=cmap, vmin=vmin, vmax=vmax)
    ax.set(title=title, xlabel="X (blocks)", ylabel="Z (blocks)", facecolor="#dedede")
    ax.figure.colorbar(plot, ax=ax, shrink=0.8)


def spawn_plot(data, out):
    fig = plt.figure(figsize=(14, 14), layout="constrained")
    axes = fig.add_gridspec(3, 3, height_ratios=[1, 1, 0.8])
    old, baked, final = (data[key] for key in ("old_clamped_water_y", "baked_water_y", "final_water_y"))
    lo, hi = min(old.min(), baked.min()) - 0.5, max(old.max(), baked.max()) + 0.5
    for pos, values, title in [((0, 0), baked, "Baked station water Y"),
                               ((0, 1), old, "Former DEM clamp: water Y"),
                               ((0, 2), final, "Final planned water Y")]:
        panel(fig.add_subplot(axes[pos]), data, values, title, vmin=lo, vmax=hi)
    panel(fig.add_subplot(axes[1, 0]), data, data["station_id"], "Station owner ID", cmap="turbo")
    panel(fig.add_subplot(axes[1, 1]), data, data["final_bed_y"], "Final bed Y after finishing passes")
    panel(fig.add_subplot(axes[1, 2]), data, data["depth"], "Final water depth (blocks)", vmin=0)
    ax = fig.add_subplot(axes[2, :])
    section = data["x"] == -57
    z = data["z"][section]
    for key, label, style in [("dem_y", "Raw DEM surface", "k:"),
                               ("old_clamped_water_y", "Former DEM-clamped water", "r--"),
                               ("baked_water_y", "Baked station water", "c-"),
                               ("final_water_y", "Final planned water", "b--"),
                               ("final_bed_y", "Final bed", "saddlebrown")]:
        if style == "saddlebrown":
            ax.step(z, data[key][section], where="mid", label=label, color=style)
        else:
            ax.step(z, data[key][section], style, where="mid", label=label)
    for boundary in range(int(z.min()), int(z.max()) + 1, 16):
        ax.axvline(boundary - 0.5, color="gray", alpha=0.25)
    ax.set(title="Longitudinal section at X = -57 (gray lines: chunk boundaries)",
           xlabel="Z (blocks)", ylabel="Minecraft Y")
    ax.legend(loc="lower left", ncols=3)
    ax.grid(alpha=0.2)
    dips = int(np.count_nonzero(old < baked))
    fig.suptitle(f"Spawn river fixture: {dips} former dips; {np.count_nonzero(final != baked)} final departures from baked Y\n"
                 "Sampled tile inputs, requested depth 3; production Java finishing passes. Gray = outside wet channel.", fontsize=13)
    fig.savefig(out, dpi=130)
    plt.close(fig)


def mountain_plot(data, out):
    fig = plt.figure(figsize=(12, 10), layout="constrained")
    axes = fig.add_gridspec(2, 2)
    dem, baked = data["dem_y"], data["baked_water_y"]
    # The removed cappedPlane rule: displayed only as a historical comparison.
    old = np.where((dem >= 128) & (dem - baked > 3), np.maximum(baked, dem + 5 - 3), baked)
    panel(fig.add_subplot(axes[0, 0]), data, old, "Removed mountain rule: water Y", vmin=145, vmax=161)
    panel(fig.add_subplot(axes[0, 1]), data, data["final_water_y"], "Final water Y (gray = dry terrain)", vmin=145, vmax=161)
    ax = fig.add_subplot(axes[1, :])
    section = data["z"] == 8
    x = data["x"][section]
    ax.step(x, dem[section], "k:", where="mid", label="DEM surface")
    ax.step(x, old[section], "r--", where="mid", label="Removed water-raising rule")
    ax.step(x, baked[section], "c-", where="mid", label="Baked water Y")
    ax.step(x, data["final_water_y"][section], "b--", where="mid", label="Final planned water")
    ax.step(x, data["final_bed_y"][section], color="saddlebrown", where="mid", label="Final terrain / bed")
    ax.set(title="Transverse section at Z = 8: shallow channel ends where the incision budget cannot reach water",
           xlabel="X (blocks)", ylabel="Minecraft Y")
    ax.legend(loc="upper left")
    ax.grid(alpha=0.2)
    fig.suptitle("Synthetic mountain corridor with a four-block cascade at Z = 8\n"
                 "Production Java resolver and finishing passes; requested depth 5. No terrain-based water lift.", fontsize=13)
    fig.savefig(out, dpi=130)
    plt.close(fig)


def lake_plot(data, out):
    fig, axes = plt.subplots(2, 2, figsize=(12, 12), layout="constrained")
    panel(axes[0, 0], data, data["raw_lake"], "Source freshwater mask (4-block cells)", vmin=0, vmax=1)
    panel(axes[0, 1], data, data["rounded_lake"], "Rounded lake boundary", vmin=0, vmax=1)
    panel(axes[1, 0], data, data["former_local_max_y"], "Former moving-maximum water Y", vmin=71, vmax=73)
    panel(axes[1, 1], data, data["final_water_y"], "Shared lake water Y", vmin=71, vmax=73)
    for ax in axes.flat:
        ax.plot(-396, 433, "r+", markersize=12)
    fig.suptitle("Lake reported at X=-396, Z=433\n"
                 "Actual coarse freshwater/DEM fixture; old comparison excludes chunk leveling. Gray = dry.")
    fig.savefig(out, dpi=130)
    plt.close(fig)


def junction_plot(data, out):
    fig, axes = plt.subplots(1, 2, figsize=(15, 7), layout="constrained")
    lo, hi = data["new_baked_water_y"].min(), data["old_baked_water_y"].max()
    for ax, key, label in [(axes[0], "old_baked_water_y", "Installed station water Y"),
                            (axes[1], "new_baked_water_y", "Rebaked station water Y")]:
        panel(ax, data, data[key], label, vmin=lo, vmax=hi)
        for x, z in [(-252, 385), (-85, 256)]:
            ax.plot(x, z, "r+", markersize=10)
    fig.suptitle("Reported raised-water sites (red crosses)\n"
                 "Actual regional station bake with national reach IDs; includes dry bank corridor stamps.")
    fig.savefig(out, dpi=130)
    plt.close(fig)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[3]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, default=root / "mods/ukgeo/build/reports/river-qa")
    parser.add_argument("--out", type=Path, default=root / "tools/ukgeo-tools/tests/output")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name, plot in [("spawn", spawn_plot), ("mountain", mountain_plot), ("lake", lake_plot), ("junction", junction_plot)]:
        if not (args.reports / f"{name}.csv").exists():
            continue
        output = args.out / f"river_qa_{name}.png"
        plot(read_report(args.reports / f"{name}.csv"), output)
        print(output)
