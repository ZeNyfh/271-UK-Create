"""Canonical river stations baked from directed vector edges.

Vector edges are merged into confluence-to-confluence reaches before ids are
assigned. A node with one inflow and one outflow is removed when the ends
meet within half a cell, flow continues downstream, and order and half-width
match. Junctions stay split.

Ownership is an associative minimum of
(|perp| millicells, -strahler_order, reach_id, station_id). Distance is the
absolute cross-track offset from the station tangent, not Euclidean range to
the station point. Station tangents are blended with neighbours so bends do
not flip abruptly; stamp eligibility uses a rounded rectangle so hard corners
do not leave triangular bank spikes. Rasterisation order does not change the
winner. Water Y is computed once per station from the lower bank, gently
stepped in integer Minecraft Y along the reach (cascades kept), and stamped
unchanged across that station's corridor.

Reach ids are uint32. The minimum compares the full id, so ids above 65535
do not alias and are never masked with 0xFFFF.
"""

from __future__ import annotations

from collections import OrderedDict, defaultdict
from dataclasses import dataclass
from pathlib import Path
import gc
import math
import multiprocessing
import os
from typing import Any, Callable, Iterable, Iterator, Sequence

import numpy as np
from rich.console import Console
from tqdm import tqdm

from .manifest import read_manifest, write_manifest
from .tiles import (
    HEIGHT_NODATA,
    default_tile_extension,
    read_layer_tile,
    resolve_existing_tile,
    river_u8_layer,
    tile_filename,
    u8_extension,
    u16_extension,
    u32_extension,
    write_r16_tile,
    write_u16_tile,
    write_u32_tile,
    write_u8_tile,
)

console = Console()

BANK_CARVE_REACH = 8
STATION_SPACING_CELLS = 1.0
CASCADE_DROP_METRES = 2.5
BANK_OFFSET_MIN = 2
BANK_OFFSET_MAX = 6
LONGITUDINAL_DELTAS = (-1, 0, 1)
SMOOTH_RADIUS = 3
# Half a spacing cell, plus a cell so neighboring stamps overlap on bends.
ALONG_LIMIT = 1.5
CROSS_PAD = 0.5
MAX_UINT16 = 65535
# Snap reach ends that land within half a cell. Shared hydro nodes survive
# BNG-to-cell rounding; this does not bridge a separate channel.
MERGE_ENDPOINT_CELLS = 0.5
# End tangent · start tangent. Positive keeps a downstream bend under 90°
# and rejects a there-and-back reversal.
FLOW_DOT_MIN = 0.0
# Reach id 0 is empty and uint32 max stays unused, so the largest 1-based id is 2^32-2.
MAX_REACH_COUNT = (1 << 32) - 2
STATION_SCHEMA_VERSION = 1
# Fork workers inherit the ordered reach list copy-on-write.
# make-river-tiles and the stations-only resume CLI both default to 12.
# STATION_BAKE_WORKERS overrides either stamp default. STATION_BUILD_WORKERS
# overrides reach sampling only; otherwise sampling uses the stamp count.
STATION_BAKE_WORKERS = 12
RESUME_STATION_BAKE_WORKERS = 12
STATION_BUILD_WORKERS = 12
_BAKE_IMAP_CHUNKSIZE = 16
# About 64 reaches per fork task. Extra chunks let uneven channels overlap.
_BUILD_REACHES_PER_CHUNK = 64
# Sequential bank sampling. 96 x 512^2 int16 is about 48 MiB.
HEIGHT_TILE_CACHE = 96
# Each build worker owns this cache. 32 x 512^2 int16 is about 16 MiB.
WORKER_HEIGHT_TILE_CACHE = 32
# River-mask tiles. 32 x 512^2 uint8 is about 8 MiB, per lookup instance.
RIVER_MASK_TILE_CACHE = 32
REACH_ID_PATH = "water/river_reach_id"
STATION_ID_PATH = "water/river_station_id"
WATER_Y_PATH = "water/river_water_y"
FLOW_AZIMUTH_PATH = "water/river_flow_azimuth"
_EMPTY_KEY = np.uint64(np.iinfo(np.uint64).max)

HeightFn = Callable[[int, int], float | None]
MaskFn = Callable[[int, int], bool]
LookupFactory = Callable[[], tuple[HeightFn, MaskFn]]


def smoothstep(value: float) -> float:
    t = min(1.0, max(0.0, value))
    return t * t * (3.0 - 2.0 * t)


def _java_round_float(value: float) -> int:
    """Match Math.round((float) value): nearest int, ties toward +inf."""
    f = float(np.float32(value))
    if not math.isfinite(f):
        return 0
    return int(math.floor(f + 0.5))


def metres_to_minecraft_y(
    metres: float,
    *,
    sea_level_y: int = 64,
    height_scale: float = 0.27,
    lowland_extra_scale: float = 0.045,
    lowland_ceiling_metres: float = 120.0,
    highland_scale: float = 0.3,
    highland_start_metres: float = 120.0,
    highland_full_metres: float = 750.0,
) -> int:
    """DEM metres to Minecraft Y, matching UkGeoChunkGenerator.rawSurfaceY.

    Highland neighbourhood smoothing is omitted. The station Y is stamped once
    and must not drift between columns.
    """
    if not math.isfinite(metres):
        return int(sea_level_y)
    span = highland_full_metres - highland_start_metres
    if span == 0.0:
        span = 1.0
    highland_weight = smoothstep((metres - highland_start_metres) / span)
    if metres <= 0.0:
        lowland_weight = 1.0
    else:
        ceiling = lowland_ceiling_metres if lowland_ceiling_metres > 0.0 else 1.0
        lowland_weight = 1.0 - min(1.0, max(0.0, metres / ceiling))
    scale = height_scale + lowland_extra_scale * lowland_weight
    scale = scale + (highland_scale - scale) * highland_weight
    return int(sea_level_y) + _java_round_float(float(metres) * scale)


def minecraft_y_params(manifest: dict[str, Any] | None) -> dict[str, float | int]:
    """Scales from the manifest when present, otherwise the UK preset defaults."""
    params: dict[str, float | int] = {
        "sea_level_y": 64,
        "height_scale": 0.27,
        "lowland_extra_scale": 0.045,
        "lowland_ceiling_metres": 120.0,
        "highland_scale": 0.3,
        "highland_start_metres": 120.0,
        "highland_full_metres": 750.0,
    }
    if not manifest:
        return params
    height = manifest.get("height") or {}
    if "sea_level_y" in height:
        params["sea_level_y"] = int(height["sea_level_y"])
    src: dict[str, Any] = {}
    for key in ("minecraft_height", "height_shaping"):
        block = manifest.get(key)
        if isinstance(block, dict):
            src.update(block)
    for name in (
        "height_scale",
        "lowland_extra_scale",
        "lowland_ceiling_metres",
        "highland_scale",
        "highland_start_metres",
        "highland_full_metres",
    ):
        if name in src:
            params[name] = float(src[name])
        elif name in height:
            params[name] = float(height[name])
    return params


def flow_azimuth_u8(tx: float, tz: float) -> int:
    """atan2(tz, tx) mapped onto 0..255. +X is 0, +Z is 64."""
    angle = math.atan2(tz, tx)
    if angle < 0.0:
        angle += 2.0 * math.pi
    return int(math.floor(angle * 256.0 / (2.0 * math.pi))) & 255


def ownership_hi_lo(
    dist_millicells: int,
    order: int,
    reach_id: int,
    station_id: int,
    along_millicells: int = 0,
) -> tuple[np.uint64, np.uint64]:
    """Lexicographic key (cross-track, -order, reach, along-track, station).

    Cross-track ownership remains the primary geometric criterion. Within the
    same reach, the closest longitudinal station wins instead of the earliest
    station id owning every overlapping stamp. The low key packs a full uint32
    reach id, uint16 along distance, and uint16 station id.
    """
    dist = min(0xFFFFFF, max(0, int(dist_millicells)))
    order_key = 255 - (int(order) & 255)
    along = min(0xFFFF, max(0, int(along_millicells)))
    hi = (np.uint64(dist) << np.uint64(8)) | np.uint64(order_key)
    lo = (
        (np.uint64(int(reach_id) & 0xFFFFFFFF) << np.uint64(32))
        | (np.uint64(along) << np.uint64(16))
        | np.uint64(int(station_id) & 0xFFFF)
    )
    return hi, lo


def smooth_longitudinal_profile(raw_metres: np.ndarray) -> np.ndarray:
    """Smooth a downstream bank profile.

    The result never rises above the bank sample, never rises downstream, and
    keeps a drop of at least CASCADE_DROP_METRES where the monotone profile
    already falls that far. The lip is not pulled down into the pool.
    """
    raw = np.asarray(raw_metres, dtype=np.float64).reshape(-1)
    if raw.size == 0:
        return raw.copy()
    filled = _fill_bank_gaps(raw)
    finite = np.isfinite(filled)
    if not bool(finite.any()):
        return filled
    mono = np.minimum.accumulate(np.where(finite, filled, np.inf))
    out = np.where(np.isfinite(mono), mono, np.nan)
    n = int(out.size)
    starts = [0]
    for i in range(n - 1):
        left = out[i]
        right = out[i + 1]
        if np.isfinite(left) and np.isfinite(right) and (left - right) >= CASCADE_DROP_METRES:
            starts.append(i + 1)
    starts.append(n)
    for begin, end in zip(starts, starts[1:]):
        seg = out[begin:end]
        if seg.size == 0 or not bool(np.isfinite(seg).all()):
            continue
        smoothed = np.minimum(_moving_mean(seg, SMOOTH_RADIUS), seg)
        out[begin:end] = np.minimum.accumulate(smoothed)
    return out



def smooth_water_y_profile(
    raw_ys: np.ndarray,
    *,
    max_step: int = 1,
    cascade_at: np.ndarray | None = None,
) -> np.ndarray:
    """Integer Minecraft Y along a reach: gradual slopes, metre-true cascades.

    Large integer drops are only preserved where ``cascade_at[i]`` is true
    (from CASCADE_DROP_METRES in the metre profile). Accidental Y jumps from
    bank sampling or rounding are stepped at most ``max_step``.
    """
    raw = np.asarray(raw_ys, dtype=np.float64).reshape(-1)
    if raw.size == 0:
        return raw.copy()
    mono = raw.copy()
    finite = np.isfinite(mono)
    if not bool(finite.any()):
        return mono
    first = int(np.flatnonzero(finite)[0])
    last_v = float(mono[first])
    for i in range(mono.size):
        if np.isfinite(mono[i]):
            last_v = float(mono[i])
        else:
            mono[i] = last_v
    for i in range(1, mono.size):
        mono[i] = min(mono[i], mono[i - 1])
    flags = np.zeros(mono.shape, dtype=bool) if cascade_at is None else np.asarray(cascade_at, dtype=bool).reshape(-1)
    if flags.size != mono.size:
        raise ValueError("cascade_at must match raw_ys length")
    result = mono.copy()
    for i in range(1, result.size):
        target = float(mono[i])
        prev = float(result[i - 1])
        if bool(flags[i]):
            result[i] = min(prev, target)
        else:
            result[i] = max(target, prev - float(max_step))
    return result


def _fill_bank_gaps(raw: np.ndarray) -> np.ndarray:

    out = raw.astype(np.float64, copy=True)
    finite_idx = np.flatnonzero(np.isfinite(out))
    if finite_idx.size == 0:
        return out
    out[: int(finite_idx[0])] = out[int(finite_idx[0])]
    out[int(finite_idx[-1]) + 1 :] = out[int(finite_idx[-1])]
    for left_i, right_i in zip(finite_idx, finite_idx[1:]):
        left = int(left_i)
        right = int(right_i)
        if right <= left + 1:
            continue
        left_v = float(out[left])
        right_v = float(out[right])
        gap = np.arange(left + 1, right)
        if left_v - right_v >= CASCADE_DROP_METRES:
            mid = (left + right) / 2.0
            out[gap] = np.where(gap < mid, left_v, right_v)
        else:
            t = (gap - left) / (right - left)
            out[gap] = left_v + (right_v - left_v) * t
    return out


def _moving_mean(values: np.ndarray, radius: int) -> np.ndarray:
    n = int(values.shape[0])
    prefix = np.zeros(n + 1, dtype=np.float64)
    np.cumsum(values, out=prefix[1:])
    out = np.empty(n, dtype=np.float64)
    for i in range(n):
        lo = max(0, i - radius)
        hi = min(n, i + radius + 1)
        out[i] = (prefix[hi] - prefix[lo]) / (hi - lo)
    return out


def robust_lowest(samples: Sequence[float]) -> float:
    """True minimum below 4 samples, otherwise the ~10th percentile."""
    if not samples:
        return math.nan
    ordered = sorted(float(sample) for sample in samples)
    count = len(ordered)
    if count < 4:
        return ordered[0]
    idx = int(math.floor((count - 1) * 0.10))
    return ordered[min(count - 1, max(0, idx))]


@dataclass(frozen=True)
class CellReach:
    """Directed centreline in continuous cell coordinates (x east, z south)."""

    points: tuple[tuple[float, float], ...]
    order: int
    half_width: int

    def __init__(self, points: Sequence[tuple[float, float]], order: int, half_width: int):
        object.__setattr__(self, "points", tuple((float(x), float(z)) for x, z in points))
        object.__setattr__(self, "order", int(order))
        object.__setattr__(self, "half_width", int(half_width))


@dataclass(frozen=True)
class Station:
    reach_id: int
    station_id: int
    order: int
    x: float
    z: float
    tx: float
    tz: float
    half_width: int
    water_metres: float
    water_y: int
    azimuth: int


@dataclass(frozen=True)
class StationGrid:
    reach_id: np.ndarray
    station_id: np.ndarray
    water_y: np.ndarray
    azimuth: np.ndarray


@dataclass(frozen=True)
class BakedCellGrid:
    stations: list[Station]
    reach_id: np.ndarray
    station_id: np.ndarray
    water_y: np.ndarray
    azimuth: np.ndarray


@dataclass
class _Sample:
    x: float
    z: float
    tx: float
    tz: float


def _reach_sort_key(reach: CellReach) -> tuple:
    pts = tuple((round(x, 4), round(z, 4)) for x, z in reach.points)
    return (pts, int(reach.order), int(reach.half_width))


def _polyline_samples(points: Sequence[tuple[float, float]], spacing: float = STATION_SPACING_CELLS) -> list[_Sample]:
    segments: list[tuple[float, float, float, float, float, float]] = []
    cursor = 0.0
    for (x0, z0), (x1, z1) in zip(points, points[1:]):
        dx = x1 - x0
        dz = z1 - z0
        length = math.hypot(dx, dz)
        if length < 1e-8:
            continue
        segments.append((x0, z0, dx / length, dz / length, cursor, length))
        cursor += length
    if cursor < 1e-8 or not segments:
        return []
    if cursor < spacing:
        targets = [cursor * 0.5]
    else:
        n_full = int(math.floor(cursor / spacing))
        targets = [(i + 0.5) * spacing for i in range(n_full)]
        covered = n_full * spacing
        if cursor - covered > 1e-6:
            targets.append(covered + 0.5 * (cursor - covered))
    samples: list[_Sample] = []
    seg_i = 0
    for target in targets:
        while seg_i + 1 < len(segments) and target > segments[seg_i][4] + segments[seg_i][5] + 1e-9:
            seg_i += 1
        x0, z0, tx, tz, start, length = segments[seg_i]
        dist = min(length, max(0.0, target - start))
        samples.append(_Sample(x0 + tx * dist, z0 + tz * dist, tx, tz))
    return _smooth_sample_tangents(samples)



def _smooth_sample_tangents(samples: list[_Sample], window: int = 1) -> list[_Sample]:
    """Blend each station tangent with neighbours so bends do not flip abruptly.

    Abrupt per-segment tangents make oriented stamps fan into triangular bank
    spikes at polyline vertices. A small circular-mean blend keeps ownership
    cross-track while stabilising stamp orientation through curves.
    """
    n = len(samples)
    if n < 3 or window <= 0:
        return samples
    out: list[_Sample] = []
    for i, sample in enumerate(samples):
        sx = 0.0
        sz = 0.0
        weight = 0.0
        for j in range(max(0, i - window), min(n, i + window + 1)):
            # Distance weight: centre sample counts most.
            w = 1.0 / (1.0 + abs(j - i))
            sx += samples[j].tx * w
            sz += samples[j].tz * w
            weight += w
        if weight <= 1e-12:
            out.append(sample)
            continue
        tx, tz = _unit_tangent(sx / weight, sz / weight)
        # Reject reversals that would invert flow; keep original if blend flips.
        if (tx * sample.tx + tz * sample.tz) < 0.0:
            out.append(sample)
            continue
        out.append(_Sample(sample.x, sample.z, tx, tz))
    return out


def _bank_metres(sample: _Sample, half_width: int, height_at: HeightFn, mask_at: MaskFn) -> float:

    nx = -sample.tz
    nz = sample.tx
    radius = max(0, int(half_width))
    sides: list[list[float]] = [[], []]
    seen: list[set[tuple[int, int]]] = [set(), set()]
    for sign, bucket, used in ((1.0, sides[0], seen[0]), (-1.0, sides[1], seen[1])):
        for perp in range(radius + BANK_OFFSET_MIN, radius + BANK_OFFSET_MAX + 1):
            for delta in LONGITUDINAL_DELTAS:
                x = sample.x + sign * nx * perp + sample.tx * delta
                z = sample.z + sign * nz * perp + sample.tz * delta
                cell = (int(math.floor(x)), int(math.floor(z)))
                if cell in used:
                    continue
                used.add(cell)
                if mask_at(cell[0], cell[1]):
                    continue
                height = height_at(cell[0], cell[1])
                if height is None or not math.isfinite(height):
                    continue
                bucket.append(float(height))
    left = robust_lowest(sides[0])
    right = robust_lowest(sides[1])
    left_ok = math.isfinite(left)
    right_ok = math.isfinite(right)
    if left_ok and right_ok:
        return min(left, right)
    if left_ok:
        return left
    if right_ok:
        return right
    return math.nan


def _stations_from_reach(
    reach_id: int,
    reach: CellReach,
    height_at: HeightFn,
    mask_at: MaskFn,
    params: dict[str, Any],
) -> list[Station]:
    samples = _polyline_samples(reach.points)
    if len(samples) > MAX_UINT16:
        raise ValueError(f"reach {reach_id} has {len(samples)} stations; uint16 station_id exhausted")
    if not samples:
        return []
    raw = np.fromiter(
        (_bank_metres(sample, reach.half_width, height_at, mask_at) for sample in samples),
        dtype=np.float64,
        count=len(samples),
    )
    smoothed = smooth_longitudinal_profile(raw)
    raw_ys = np.array(
        [
            metres_to_minecraft_y(float(m), **params) if math.isfinite(float(m)) else math.nan
            for m in smoothed
        ],
        dtype=np.float64,
    )
    cascade = np.zeros(smoothed.shape, dtype=bool)
    for i in range(int(smoothed.size) - 1):
        left = float(smoothed[i])
        right = float(smoothed[i + 1])
        if math.isfinite(left) and math.isfinite(right) and (left - right) >= CASCADE_DROP_METRES:
            cascade[i + 1] = True
    water_ys = smooth_water_y_profile(raw_ys, cascade_at=cascade)
    stations: list[Station] = []
    for station_id, (sample, metres, water_y) in enumerate(zip(samples, smoothed, water_ys)):
        metres_f = float(metres)
        if not math.isfinite(metres_f) or not math.isfinite(float(water_y)):
            continue
        stations.append(
            Station(
                reach_id=reach_id,
                station_id=station_id,
                order=int(reach.order),
                x=sample.x,
                z=sample.z,
                tx=sample.tx,
                tz=sample.tz,
                half_width=int(reach.half_width),
                water_metres=metres_f,
                water_y=int(water_y),
                azimuth=flow_azimuth_u8(sample.tx, sample.tz),
            )
        )
    return stations


def _collect_stations(
    numbered: Sequence[tuple[int, CellReach]],
    height_at: HeightFn,
    mask_at: MaskFn,
    params: dict[str, Any],
) -> list[Station]:
    stations: list[Station] = []
    for reach_id, reach in numbered:
        stations.extend(_stations_from_reach(reach_id, reach, height_at, mask_at, params))
    return stations


def _reach_index_chunks(count: int, workers: int) -> list[tuple[int, int]]:
    """Half-open ranges over the parent reach list, at least one per worker."""
    if count <= 0:
        return []
    workers = max(1, min(int(workers), count))
    span = max(1, min(_BUILD_REACHES_PER_CHUNK, math.ceil(count / workers)))
    return [(start, min(count, start + span)) for start in range(0, count, span)]


def _shares_tile_cache(height_at: HeightFn, mask_at: MaskFn) -> bool:
    if isinstance(mask_at, TileMaskLookup):
        return True
    return isinstance(getattr(height_at, "__self__", None), _HeightLookup)


def build_stations(
    reaches: Sequence[CellReach],
    height_at: HeightFn,
    mask_at: MaskFn,
    *,
    y_params: dict[str, Any] | None = None,
    workers: int | None = None,
    lookup_factory: LookupFactory | None = None,
) -> list[Station]:
    """Assign stable 1-based reach ids and one water Y per station.

    Reach ids follow geometry, order, and half-width, so reversing the input
    does not renumber reaches. Vector edges should already be confluence-merged
    via merge_reaches so a degree-2 chain is one id.

    Ids are assigned in the parent. With more than one worker, each fork
    process samples an index range of that ordered list. ``lookup_factory``
    runs inside the worker so tile caches are not shared after fork.
    ``workers`` defaults to ``STATION_BUILD_WORKERS`` (12) and is capped by
    the reach count. One worker, or a single reach, stays sequential.
    """
    if not reaches:
        raise ValueError("river station bake requires vector edges")
    ordered = sorted(reaches, key=_reach_sort_key)
    # Full uint32 ids. A count above 65534 is valid; masking with 0xFFFF would
    # alias distinct reaches. Only uint32 exhaustion is a hard error.
    if len(ordered) > MAX_REACH_COUNT:
        raise ValueError(
            f"reach count {len(ordered)} exceeds uint32; refusing to truncate reach ids with & 0xFFFF"
        )
    numbered = [(reach_id, reach) for reach_id, reach in enumerate(ordered, start=1)]
    params = dict(y_params or minecraft_y_params(None))
    if workers is None:
        configured = _env_workers("STATION_BUILD_WORKERS", STATION_BUILD_WORKERS)
    else:
        configured = max(1, int(workers))
    processes = min(configured, len(numbered))
    if processes <= 1:
        if lookup_factory is not None:
            height_at, mask_at = lookup_factory()
        return _collect_stations(numbered, height_at, mask_at, params)
    if lookup_factory is None and _shares_tile_cache(height_at, mask_at):
        raise RuntimeError("refusing to share mutable tile caches across station build workers")
    return _build_stations_parallel(numbered, height_at, mask_at, params, processes, lookup_factory)


def _worker_station_lookups(ctx: dict[str, Any]) -> tuple[HeightFn, MaskFn]:
    """One height and mask lookup per process, created on the first chunk."""
    global _WORKER_STATION_LOOKUPS
    if _WORKER_STATION_LOOKUPS is None:
        factory: LookupFactory | None = ctx.get("lookup_factory")
        if factory is not None:
            _WORKER_STATION_LOOKUPS = factory()
        else:
            _WORKER_STATION_LOOKUPS = (ctx["height_at"], ctx["mask_at"])
    return _WORKER_STATION_LOOKUPS


def _build_station_chunk(task: tuple[int, int]) -> tuple[int, list[Station]]:
    start, end = task
    ctx = _STATION_BUILD
    if ctx is None:
        raise RuntimeError("station build worker missing fork context")
    height_at, mask_at = _worker_station_lookups(ctx)
    numbered: Sequence[tuple[int, CellReach]] = ctx["numbered"]
    params: dict[str, Any] = ctx["params"]
    return start, _collect_stations(numbered[start:end], height_at, mask_at, params)


def _build_stations_parallel(
    numbered: list[tuple[int, CellReach]],
    height_at: HeightFn,
    mask_at: MaskFn,
    params: dict[str, Any],
    processes: int,
    lookup_factory: LookupFactory | None,
) -> list[Station]:
    global _STATION_BUILD, _WORKER_STATION_LOOKUPS
    chunks = _reach_index_chunks(len(numbered), processes)
    console.print(f"[cyan]River station reaches[/cyan] reaches={len(numbered)} workers={processes}")
    # Children must inherit a clear cache slot and build their own lookups.
    _WORKER_STATION_LOOKUPS = None
    _STATION_BUILD = {
        "numbered": numbered,
        "height_at": height_at,
        "mask_at": mask_at,
        "params": params,
        "lookup_factory": lookup_factory,
    }
    parts: list[tuple[int, list[Station]]] = []
    try:
        with _ForkWorkers(processes, enabled=True) as pool:
            for part in pool.imap(
                _build_station_chunk,
                chunks,
                task_count=len(chunks),
                desc="river station reaches",
            ):
                parts.append(part)
    finally:
        _STATION_BUILD = None
        _WORKER_STATION_LOOKUPS = None
    parts.sort(key=lambda item: item[0])
    stations: list[Station] = []
    for _start, group in parts:
        stations.extend(group)
    return stations


def rasterize_stations(stations: Sequence[Station], depth: int, width: int) -> StationGrid:
    """Stamp stations into a dense grid. Input order does not affect ownership."""
    reach = np.zeros((depth, width), dtype="<u4")
    station_id = np.zeros((depth, width), dtype="<u2")
    water = np.full((depth, width), HEIGHT_NODATA, dtype=np.int16)
    azimuth = np.zeros((depth, width), dtype=np.uint8)
    best_hi = np.full((depth, width), _EMPTY_KEY, dtype=np.uint64)
    best_lo = np.full((depth, width), _EMPTY_KEY, dtype=np.uint64)
    _stamp_stations(stations, reach, station_id, water, azimuth, best_hi, best_lo, origin_x=0, origin_z=0)
    return StationGrid(reach, station_id, water, azimuth)


def bake_cell_reaches(
    reaches: Sequence[CellReach],
    height_dm: np.ndarray,
    river_mask: np.ndarray,
    *,
    y_params: dict[str, Any] | None = None,
) -> BakedCellGrid:
    """Bake stations for an in-memory height grid (decimetres, NODATA=-32768)."""
    height = np.asarray(height_dm)
    mask = np.asarray(river_mask)
    if height.shape != mask.shape:
        raise ValueError(f"height shape {height.shape} does not match river mask {mask.shape}")
    depth, width = int(height.shape[0]), int(height.shape[1])

    def height_at(x: int, z: int) -> float | None:
        if x < 0 or z < 0 or x >= width or z >= depth:
            return None
        value = int(height[z, x])
        if value == HEIGHT_NODATA:
            return None
        return value / 10.0

    def mask_at(x: int, z: int) -> bool:
        if x < 0 or z < 0 or x >= width or z >= depth:
            return False
        return int(mask[z, x]) != 0

    stations = build_stations(reaches, height_at, mask_at, y_params=y_params)
    grid = rasterize_stations(stations, depth, width)
    return BakedCellGrid(stations, grid.reach_id, grid.station_id, grid.water_y, grid.azimuth)


def _stamp_stations(
    stations: Sequence[Station],
    reach: np.ndarray,
    station_id: np.ndarray,
    water: np.ndarray,
    azimuth: np.ndarray,
    best_hi: np.ndarray,
    best_lo: np.ndarray,
    *,
    origin_x: int,
    origin_z: int,
) -> None:
    depth, width = reach.shape
    by_reach: dict[int, list[Station]] = defaultdict(list)
    for station in stations:
        if math.isfinite(station.water_metres):
            by_reach[int(station.reach_id)].append(station)
    neighbours: dict[tuple[int, int], tuple[Station | None, Station | None]] = {}
    for reach_id, group in by_reach.items():
        group.sort(key=lambda item: int(item.station_id))
        for index, station in enumerate(group):
            previous = group[index - 1] if index > 0 else None
            following = group[index + 1] if index + 1 < len(group) else None
            neighbours[(reach_id, int(station.station_id))] = (previous, following)

    for station in stations:
        if not math.isfinite(station.water_metres):
            continue
        previous, following = neighbours.get((int(station.reach_id), int(station.station_id)), (None, None))
        tx, tz = _unit_tangent(station.tx, station.tz)
        previous_spacing = 0.0 if previous is None else -((previous.x - station.x) * tx + (previous.z - station.z) * tz)
        next_spacing = 0.0 if following is None else ((following.x - station.x) * tx + (following.z - station.z) * tz)
        _stamp_one(
            station.x,
            station.z,
            station.tx,
            station.tz,
            int(station.half_width),
            int(station.order),
            int(station.reach_id),
            int(station.station_id),
            int(station.water_y),
            int(station.azimuth),
            reach,
            station_id,
            water,
            azimuth,
            best_hi,
            best_lo,
            origin_x=origin_x,
            origin_z=origin_z,
            width=width,
            depth=depth,
            previous_water_y=None if previous is None else int(previous.water_y),
            previous_spacing=previous_spacing,
            next_water_y=None if following is None else int(following.water_y),
            next_spacing=next_spacing,
        )


def _unit_tangent(tx: float, tz: float) -> tuple[float, float]:
    norm = math.hypot(tx, tz)
    if norm < 1e-12:
        return 1.0, 0.0
    return tx / norm, tz / norm


def _stamp_one(
    sx: float,
    sz: float,
    tx: float,
    tz: float,
    half_width: int,
    order: int,
    reach_id: int,
    station_id: int,
    water_y: int,
    azimuth_value: int,
    reach: np.ndarray,
    station_out: np.ndarray,
    water: np.ndarray,
    azimuth: np.ndarray,
    best_hi: np.ndarray,
    best_lo: np.ndarray,
    *,
    origin_x: int,
    origin_z: int,
    width: int,
    depth: int,
    previous_water_y: int | None = None,
    previous_spacing: float = 0.0,
    next_water_y: int | None = None,
    next_spacing: float = 0.0,
) -> None:
    tx, tz = _unit_tangent(tx, tz)
    cross_lim = float(max(0, half_width) + BANK_CARVE_REACH) + CROSS_PAD
    x0 = max(origin_x, int(math.floor(sx - cross_lim - 1.0)))
    x1 = min(origin_x + width, int(math.ceil(sx + cross_lim + 1.0)))
    z0 = max(origin_z, int(math.floor(sz - cross_lim - 1.0)))
    z1 = min(origin_z + depth, int(math.ceil(sz + cross_lim + 1.0)))
    if x0 >= x1 or z0 >= z1:
        return
    xs = np.arange(x0, x1, dtype=np.float64) + 0.5
    zs = np.arange(z0, z1, dtype=np.float64) + 0.5
    dx = xs[None, :] - sx
    dz = zs[:, None] - sz
    along = dx * tx + dz * tz
    perp = dx * (-tz) + dz * tx
    # Rounded-rectangle eligibility: hard rect corners become quarter-circles so
    # neighbouring stamps with different tangents do not leave triangular spikes.
    abs_along = np.abs(along)
    abs_perp = np.abs(perp)
    corner = (np.maximum(0.0, abs_along - ALONG_LIMIT) ** 2) + (
        np.maximum(0.0, abs_perp - (cross_lim - CROSS_PAD)) ** 2
    )
    inside = (abs_along <= ALONG_LIMIT + CROSS_PAD + 1e-6) & (abs_perp <= cross_lim + 1e-6) & (
        corner <= (CROSS_PAD + 1e-6) ** 2
    )
    if not bool(inside.any()):
        return
    dist_milli = np.floor(np.abs(perp) * 1000.0 + 0.5).astype(np.int64)
    np.clip(dist_milli, 0, 0xFFFFFF, out=dist_milli)
    # hi holds distance above inverted order. lo holds the full uint32 reach
    # id above the uint16 station id. Compare hi, then lo. Both sentinels are
    # the uint64 maximum, so any real pair is smaller and the min is associative.
    along_milli = np.floor(abs_along * 1000.0 + 0.5).astype(np.int64)
    np.clip(along_milli, 0, 0xFFFF, out=along_milli)
    order_hi, _ = ownership_hi_lo(0, order, reach_id, station_id)
    keys_hi = (dist_milli.astype(np.uint64) << np.uint64(8)) | order_hi
    keys_lo = (
        (np.uint64(int(reach_id) & 0xFFFFFFFF) << np.uint64(32))
        | (along_milli.astype(np.uint64) << np.uint64(16))
        | np.uint64(int(station_id) & 0xFFFF)
    )
    lz0 = z0 - origin_z
    lx0 = x0 - origin_x
    hh = z1 - z0
    ww = x1 - x0
    best_hi_view = best_hi[lz0 : lz0 + hh, lx0 : lx0 + ww]
    best_lo_view = best_lo[lz0 : lz0 + hh, lx0 : lx0 + ww]
    better = inside & ((keys_hi < best_hi_view) | ((keys_hi == best_hi_view) & (keys_lo < best_lo_view)))
    if not bool(better.any()):
        return
    best_hi_view[better] = keys_hi[better]
    best_lo_view[better] = keys_lo
    reach_view = reach[lz0 : lz0 + hh, lx0 : lx0 + ww]
    station_view = station_out[lz0 : lz0 + hh, lx0 : lx0 + ww]
    water_view = water[lz0 : lz0 + hh, lx0 : lx0 + ww]
    azimuth_view = azimuth[lz0 : lz0 + hh, lx0 : lx0 + ww]
    reach_view[better] = np.uint32(int(reach_id) & 0xFFFFFFFF)
    station_view[better] = np.uint16(int(station_id) & 0xFFFF)
    # Interpolate station water levels along the reach. Without this,
    # each overlapping stamp paints a constant-height slab and ownership seams
    # create a repeating sequence of shallow pools/steps downstream.
    interpolated_water = np.full(along.shape, float(water_y), dtype=np.float64)
    if next_water_y is not None and next_spacing > 1e-6:
        mask = along > 0.0
        t = np.clip(along[mask] / next_spacing, 0.0, 1.0)
        interpolated_water[mask] = float(water_y) + (float(next_water_y) - float(water_y)) * t
    if previous_water_y is not None and previous_spacing > 1e-6:
        mask = along < 0.0
        t = np.clip(-along[mask] / previous_spacing, 0.0, 1.0)
        interpolated_water[mask] = float(water_y) + (float(previous_water_y) - float(water_y)) * t
    water_view[better] = np.rint(interpolated_water[better]).astype(np.int16)
    azimuth_view[better] = np.uint8(azimuth_value)


def _bng_to_cells(line: Any, geo: dict[str, Any], width: int, depth: int) -> list[tuple[float, float]]:
    span_x = float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])
    span_z = float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])
    if span_x == 0.0 or span_z == 0.0:
        raise ValueError("georeferencing span must be non-zero")
    points: list[tuple[float, float]] = []
    for east, north, *_rest in line.coords:
        x = (float(east) - float(geo["bng_min_easting"])) * width / span_x
        z = (float(geo["bng_max_northing"]) - float(north)) * depth / span_z
        if points and abs(points[-1][0] - x) < 1e-9 and abs(points[-1][1] - z) < 1e-9:
            continue
        points.append((x, z))
    return points


def _within_cells(x0: float, z0: float, x1: float, z1: float, tolerance: float) -> bool:
    dx = x0 - x1
    dz = z0 - z1
    return dx * dx + dz * dz <= tolerance * tolerance + 1e-12


def _unit_segment(x0: float, z0: float, x1: float, z1: float) -> tuple[float, float] | None:
    dx = x1 - x0
    dz = z1 - z0
    length = math.hypot(dx, dz)
    if length < 1e-8:
        return None
    return dx / length, dz / length


def _start_tangent(points: Sequence[tuple[float, float]]) -> tuple[float, float]:
    for (x0, z0), (x1, z1) in zip(points, points[1:]):
        tangent = _unit_segment(x0, z0, x1, z1)
        if tangent is not None:
            return tangent
    return 0.0, 0.0


def _end_tangent(points: Sequence[tuple[float, float]]) -> tuple[float, float]:
    for index in range(len(points) - 1, 0, -1):
        x0, z0 = points[index - 1]
        x1, z1 = points[index]
        tangent = _unit_segment(x0, z0, x1, z1)
        if tangent is not None:
            return tangent
    return 0.0, 0.0


def _endpoint_roots(reaches: Sequence[CellReach], tolerance: float) -> list[int]:
    """Cluster endpoints within ``tolerance`` without collapsing one reach.

    Closest pairs join first, so an exact shared node beats a nearby vertex.
    A union that would put both ends of the same reach in one cluster is
    rejected. Otherwise a run of sub-cell edges would glue the whole chain
    into a single point and could not be walked.
    """
    count = len(reaches)
    parent = list(range(count * 2))
    members: list[list[tuple[int, int]]] = [[(edge, flag)] for edge in range(count) for flag in (0, 1)]

    def find(node: int) -> int:
        root = node
        while parent[root] != root:
            root = parent[root]
        while parent[node] != root:
            nxt = parent[node]
            parent[node] = root
            node = nxt
        return root

    def try_union(left: int, right: int) -> None:
        ra = find(left)
        rb = find(right)
        if ra == rb:
            return
        if len(members[ra]) > len(members[rb]):
            ra, rb = rb, ra
        for edge_i, end_flag in members[ra]:
            other = find(edge_i * 2 + (1 - end_flag))
            if other == rb:
                return
        parent[ra] = rb
        members[rb].extend(members[ra])
        members[ra] = []

    grid: dict[tuple[int, int], list[tuple[int, int, float, float]]] = defaultdict(list)
    for edge, reach in enumerate(reaches):
        for flag, (x, z) in ((0, reach.points[0]), (1, reach.points[-1])):
            grid[(math.floor(x / tolerance), math.floor(z / tolerance))].append((edge, flag, x, z))

    pairs: list[tuple[float, int, int]] = []
    for bucket in grid.values():
        for edge_a, flag_a, x_a, z_a in bucket:
            id_a = edge_a * 2 + flag_a
            bx = math.floor(x_a / tolerance)
            bz = math.floor(z_a / tolerance)
            for nx in (bx - 1, bx, bx + 1):
                for nz in (bz - 1, bz, bz + 1):
                    for edge_b, flag_b, x_b, z_b in grid.get((nx, nz), ()):
                        id_b = edge_b * 2 + flag_b
                        if id_b <= id_a or edge_a == edge_b:
                            continue
                        if not _within_cells(x_a, z_a, x_b, z_b, tolerance):
                            continue
                        dx = x_a - x_b
                        dz = z_a - z_b
                        pairs.append((dx * dx + dz * dz, id_a, id_b))
    pairs.sort()
    for _dist2, id_a, id_b in pairs:
        try_union(id_a, id_b)
    return [find(node) for node in range(count * 2)]


def _concat_reaches(chain: Sequence[CellReach], tolerance: float) -> CellReach:
    if len(chain) == 1:
        return chain[0]
    points: list[tuple[float, float]] = list(chain[0].points)
    for reach in chain[1:]:
        extra: Sequence[tuple[float, float]] = reach.points
        if points and extra and _within_cells(points[-1][0], points[-1][1], extra[0][0], extra[0][1], tolerance):
            extra = extra[1:]
        points.extend(extra)
    if len(points) < 2:
        points = [point for reach in chain for point in reach.points]
    return CellReach(points, chain[0].order, chain[0].half_width)


def merge_reaches(
    reaches: Sequence[CellReach],
    *,
    tolerance: float = MERGE_ENDPOINT_CELLS,
) -> list[CellReach]:
    """Chain degree-2 reaches into confluence-to-confluence polylines.

    Each removed node has exactly one inflow and one outflow. Any other
    degree is a junction and breaks the chain. Flow has to keep the same
    downstream direction, and order plus half-width have to match so the
    merged reach keeps one corridor. Each successful link removes one reach,
    so the result counts paths between junctions rather than vector edges.

    Ids are assigned later as uint32. A merged count above 65534 stays
    addressable and is not masked with 0xFFFF.
    """
    active = [reach for reach in reaches if len(reach.points) >= 2]
    short = [reach for reach in reaches if len(reach.points) < 2]
    if not active:
        return sorted(short, key=_reach_sort_key)
    roots = _endpoint_roots(active, tolerance)
    incoming: dict[int, list[int]] = defaultdict(list)
    outgoing: dict[int, list[int]] = defaultdict(list)
    for index in range(len(active)):
        outgoing[roots[index * 2]].append(index)
        incoming[roots[index * 2 + 1]].append(index)
    nxt: dict[int, int] = {}
    prev: set[int] = set()
    for root, ins in incoming.items():
        outs = outgoing.get(root, [])
        if len(ins) != 1 or len(outs) != 1:
            continue
        src = ins[0]
        dst = outs[0]
        if src == dst or src in nxt or dst in prev:
            continue
        left = active[src]
        right = active[dst]
        if left.order != right.order or left.half_width != right.half_width:
            continue
        ax, az = _end_tangent(left.points)
        bx, bz = _start_tangent(right.points)
        if ax * bx + az * bz <= FLOW_DOT_MIN:
            continue
        nxt[src] = dst
        prev.add(dst)

    used = [False] * len(active)
    chains: list[list[int]] = []

    def walk(start: int) -> list[int]:
        chain: list[int] = []
        seen: set[int] = set()
        node: int | None = start
        while node is not None and node not in seen and not used[node]:
            seen.add(node)
            used[node] = True
            chain.append(node)
            node = nxt.get(node)
        return chain

    for index in range(len(active)):
        if used[index] or index in prev:
            continue
        chains.append(walk(index))
    for index in range(len(active)):
        if used[index]:
            continue
        comp: list[int] = []
        seen: set[int] = set()
        node: int | None = index
        while node is not None and node not in seen:
            seen.add(node)
            comp.append(node)
            node = nxt.get(node)
        if not comp:
            continue
        # Pure cycles have no source. Rotate to a stable start and keep
        # downstream order. A line is already emitted from its source above.
        if nxt.get(comp[-1]) in seen:
            start = min(comp, key=lambda item: (_reach_sort_key(active[item]), item))
            pos = comp.index(start)
            comp = comp[pos:] + comp[:pos]
        for item in comp:
            used[item] = True
        chains.append(comp)

    merged = [_concat_reaches([active[index] for index in chain], tolerance) for chain in chains if chain]
    merged.extend(short)
    merged.sort(key=_reach_sort_key)
    return merged


def reaches_from_edges(edges: Sequence[Any], manifest: dict[str, Any]) -> list[CellReach]:
    geo = manifest["georeferencing"]
    world = manifest["world"]
    width = int(world["width"])
    depth = int(world["depth"])
    reaches: list[CellReach] = []
    for edge in edges:
        points = _bng_to_cells(edge.line, geo, width, depth)
        if len(points) < 2:
            continue
        reaches.append(CellReach(points, int(edge.order), int(edge.half_width)))
    return merge_reaches(reaches)


class _HeightLookup:
    def __init__(self, root: Path, manifest: dict[str, Any], *, cache_tiles: int = HEIGHT_TILE_CACHE):
        self.root = root
        self.layer = manifest["height"]
        self.tile_size = int(manifest["tile_size"])
        world = manifest["world"]
        self.padded_width = int(world["padded_width"])
        self.padded_depth = int(world["padded_depth"])
        self._cache_limit = max(1, int(cache_tiles))
        self._cache: dict[tuple[int, int], np.ndarray] = {}

    def metres(self, x: int, z: int) -> float | None:
        if x < 0 or z < 0 or x >= self.padded_width or z >= self.padded_depth:
            return None
        key = (x // self.tile_size, z // self.tile_size)
        tile = self._cache.get(key)
        if tile is None:
            loaded = read_layer_tile(self.root, self.layer, key[0], key[1], self.tile_size)
            tile = np.array(loaded, dtype=np.int16, copy=True)
            self._cache[key] = tile
            if len(self._cache) > self._cache_limit:
                self._cache.pop(next(iter(self._cache)))
        value = int(tile[z % self.tile_size, x % self.tile_size])
        if value == HEIGHT_NODATA:
            return None
        return value / 10.0


class TileMaskLookup:
    """Sample the baked river mask from u8 tiles. Keeps at most 32 tiles."""

    def __init__(self, root: Path, manifest: dict[str, Any]):
        rivers = manifest.get("rivers")
        if not isinstance(rivers, dict) or "path" not in rivers:
            raise ValueError("manifest is missing rivers.path; river mask tiles are required")
        self.root = root
        self.layer = river_u8_layer(rivers)
        self.tile_size = int(manifest["tile_size"])
        world = manifest["world"]
        # U8Raster is (world depth, world width), not the padded extent.
        self.width = int(world["width"])
        self.depth = int(world["depth"])
        self._cache: OrderedDict[tuple[int, int], np.ndarray] = OrderedDict()

    def __call__(self, x: int, z: int) -> bool:
        if x < 0 or z < 0 or x >= self.width or z >= self.depth:
            return False
        key = (x // self.tile_size, z // self.tile_size)
        tile = self._cache.get(key)
        if tile is None:
            loaded = read_layer_tile(self.root, self.layer, key[0], key[1], self.tile_size)
            tile = np.array(loaded, dtype=np.uint8, copy=True)
            self._cache[key] = tile
            while len(self._cache) > RIVER_MASK_TILE_CACHE:
                self._cache.popitem(last=False)
        else:
            self._cache.move_to_end(key)
        return int(tile[z % self.tile_size, x % self.tile_size]) != 0


def _index_stations(stations: Sequence[Station], tile_size: int) -> dict[tuple[int, int], list[int]]:
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)
    for index, station in enumerate(stations):
        pad = int(station.half_width) + BANK_CARVE_REACH + 2
        min_x = int(math.floor(station.x - pad))
        max_x = int(math.floor(station.x + pad))
        min_z = int(math.floor(station.z - pad))
        max_z = int(math.floor(station.z + pad))
        for tz in range(min_z // tile_size, max_z // tile_size + 1):
            for tx in range(min_x // tile_size, max_x // tile_size + 1):
                buckets[(tx, tz)].append(index)
    return buckets


def _env_workers(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        return max(1, int(raw))
    except ValueError:
        return default


class _ForkWorkers:
    """Fork pool so large parent objects stay shared instead of being pickled.

    Callers put big read-only state in module globals before entering, and pass
    only small task arguments through the pool. gc.freeze() keeps the child's
    collector from dirtying those pages. When fork is unavailable, imap runs
    in-process and never pickles the task function's parent state.
    """

    def __init__(self, workers: int, *, enabled: bool):
        self.workers = max(1, int(workers))
        self.enabled = bool(enabled) and self.workers > 1
        self._pool: Any = None
        self._froze = False

    def __enter__(self) -> _ForkWorkers:
        if not self.enabled:
            return self
        gc.collect()
        gc.freeze()
        self._froze = True
        try:
            ctx = multiprocessing.get_context("fork")
        except ValueError:
            gc.unfreeze()
            self._froze = False
            return self
        try:
            self._pool = ctx.Pool(processes=self.workers)
        except Exception:
            gc.unfreeze()
            self._froze = False
            raise
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        pool = self._pool
        self._pool = None
        if pool is not None:
            if exc_type is not None:
                pool.terminate()
            else:
                pool.close()
            pool.join()
        if self._froze:
            gc.unfreeze()
            self._froze = False

    def imap(
        self,
        fn: Callable[..., Any],
        tasks: Iterable[Any],
        *,
        task_count: int,
        desc: str | None = None,
        chunksize: int = _BAKE_IMAP_CHUNKSIZE,
    ) -> Iterator[Any]:
        if task_count <= 0:
            return
        if self._pool is None:
            iterable: Iterable[Any] = tqdm(tasks, total=task_count, desc=desc) if desc else tasks
            for task in iterable:
                yield fn(task)
            return
        chunk = max(1, min(int(chunksize), 32, task_count))
        mapped = self._pool.imap_unordered(fn, tasks, chunksize=chunk)
        if desc:
            mapped = tqdm(mapped, total=task_count, desc=desc)
        yield from mapped


# Set in the parent before fork. Workers read these; they are not pickled per task.
_STATION_BAKE: dict[str, Any] | None = None
_STATION_BUILD: dict[str, Any] | None = None
# None at fork so each child builds a fresh height and mask cache on its first chunk.
_WORKER_STATION_LOOKUPS: tuple[HeightFn, MaskFn] | None = None


def _bake_one_station_tile(task: tuple[int, int, list[int]]) -> None:
    """Stamp one tile and write its four layers. Allocates only that tile's arrays."""
    tile_x, tile_z, indexes = task
    ctx = _STATION_BAKE
    if ctx is None:
        raise RuntimeError("station bake worker missing fork context")
    stations: Sequence[Station] = ctx["stations"]
    tile_size = int(ctx["tile_size"])
    reach = np.zeros((tile_size, tile_size), dtype="<u4")
    station_id = np.zeros((tile_size, tile_size), dtype="<u2")
    water = np.full((tile_size, tile_size), HEIGHT_NODATA, dtype=np.int16)
    azimuth = np.zeros((tile_size, tile_size), dtype=np.uint8)
    best_hi = np.full((tile_size, tile_size), _EMPTY_KEY, dtype=np.uint64)
    best_lo = np.full((tile_size, tile_size), _EMPTY_KEY, dtype=np.uint64)
    _stamp_stations(
        [stations[index] for index in indexes],
        reach,
        station_id,
        water,
        azimuth,
        best_hi,
        best_lo,
        origin_x=tile_x * tile_size,
        origin_z=tile_z * tile_size,
    )
    reach_ext = ctx["reach_ext"]
    station_ext = ctx["station_ext"]
    water_ext = ctx["water_ext"]
    azimuth_ext = ctx["azimuth_ext"]
    write_u32_tile(ctx["reach_root"] / tile_filename(tile_x, tile_z, reach_ext), reach, tile_size)
    write_u16_tile(ctx["station_root"] / tile_filename(tile_x, tile_z, station_ext), station_id, tile_size)
    write_r16_tile(ctx["water_root"] / tile_filename(tile_x, tile_z, water_ext), water, tile_size)
    write_u8_tile(ctx["azimuth_root"] / tile_filename(tile_x, tile_z, azimuth_ext), azimuth, tile_size)


def _clear_tile(path: Path) -> None:
    path.unlink(missing_ok=True)
    if path.name.endswith(".gz"):
        path.with_name(path.name[:-3]).unlink(missing_ok=True)
    else:
        path.with_name(path.name + ".gz").unlink(missing_ok=True)


def _mask_at_array(river_mask: np.ndarray) -> MaskFn:
    mask = np.asarray(river_mask)

    def mask_at(x: int, z: int) -> bool:
        if x < 0 or z < 0 or z >= mask.shape[0] or x >= mask.shape[1]:
            return False
        return int(mask[z, x]) != 0

    return mask_at


def _unused_station_height(_x: int, _z: int) -> float | None:
    raise RuntimeError("station build must create its own height lookup")


def _unused_station_mask(_x: int, _z: int) -> bool:
    raise RuntimeError("station build must create its own mask lookup")


def _bake_stations(
    root: Path,
    manifest_path: Path,
    manifest: dict[str, Any],
    edges: Sequence[Any],
    mask_at: MaskFn | None,
    *,
    skip_existing: bool,
    default_workers: int,
    tile_mask: bool = False,
) -> None:
    reaches = reaches_from_edges(edges, manifest)
    if not reaches:
        raise ValueError("river station bake requires vector edges")
    console.print(f"[cyan]Confluence reaches[/cyan] vector_edges={len(edges)} reaches={len(reaches)}")
    stamp_default = int(default_workers)
    build_configured = _env_workers(
        "STATION_BUILD_WORKERS",
        _env_workers("STATION_BAKE_WORKERS", stamp_default),
    )
    build_workers = max(1, min(int(build_configured), len(reaches)))
    # Parallel workers each keep a 32-tile height cache. The one-process path keeps 96.
    cache_tiles = WORKER_HEIGHT_TILE_CACHE if build_workers > 1 else HEIGHT_TILE_CACHE
    shared_mask = mask_at

    def lookup_factory() -> tuple[HeightFn, MaskFn]:
        height = _HeightLookup(root, manifest, cache_tiles=cache_tiles)
        if tile_mask:
            return height.metres, TileMaskLookup(root, manifest)
        if shared_mask is None:
            raise RuntimeError("station build missing mask lookup")
        return height.metres, shared_mask

    stations = build_stations(
        reaches,
        _unused_station_height,
        shared_mask if shared_mask is not None else _unused_station_mask,
        y_params=minecraft_y_params(manifest),
        workers=build_workers,
        lookup_factory=lookup_factory,
    )
    _write_station_tiles(
        root,
        manifest,
        stations,
        skip_existing=skip_existing,
        default_workers=default_workers,
    )
    _write_station_manifest(manifest_path, manifest, stations)
    console.print(
        f"[green]Baked river stations[/green] reaches={len(reaches)} stations={len(stations)} schema={STATION_SCHEMA_VERSION}"
    )


def bake_river_stations(
    root: Path,
    *,
    manifest_path: Path,
    edges: Sequence[Any],
    river_mask: np.ndarray,
) -> None:
    """Tile-by-tile station bake. Requires directed edges; there is no centroid fallback."""
    if not edges:
        raise ValueError("river station bake requires vector edges")
    manifest = read_manifest(manifest_path)
    _bake_stations(
        root,
        manifest_path,
        manifest,
        edges,
        _mask_at_array(river_mask),
        skip_existing=False,
        default_workers=STATION_BAKE_WORKERS,
    )


def bake_river_stations_standalone(
    root: Path,
    *,
    manifest_path: Path,
    edges: Sequence[Any],
    skip_existing: bool = False,
    default_workers: int | None = None,
) -> None:
    """Bake stations from on-disk river tiles. Does not open a world-sized mask.

    ``default_workers`` sizes reach sampling and tile stamping when
    STATION_BAKE_WORKERS is unset. The resume CLI leaves it None, which
    selects 12 workers. STATION_BUILD_WORKERS overrides sampling only.
    Each worker builds its own height and river-mask tile caches.
    """
    if not edges:
        raise ValueError("river station bake requires vector edges")
    manifest = read_manifest(manifest_path)
    workers = RESUME_STATION_BAKE_WORKERS if default_workers is None else int(default_workers)
    _bake_stations(
        root,
        manifest_path,
        manifest,
        edges,
        None,
        skip_existing=skip_existing,
        default_workers=workers,
        tile_mask=True,
    )


def _station_outputs_present(
    reach_root: Path,
    station_root: Path,
    water_root: Path,
    azimuth_root: Path,
    tile_x: int,
    tile_z: int,
    reach_ext: str,
    station_ext: str,
    water_ext: str,
    azimuth_ext: str,
) -> bool:
    paths = (
        reach_root / tile_filename(tile_x, tile_z, reach_ext),
        station_root / tile_filename(tile_x, tile_z, station_ext),
        water_root / tile_filename(tile_x, tile_z, water_ext),
        azimuth_root / tile_filename(tile_x, tile_z, azimuth_ext),
    )
    return all(resolve_existing_tile(path).exists() for path in paths)


def _write_station_tiles(
    root: Path,
    manifest: dict[str, Any],
    stations: Sequence[Station],
    *,
    skip_existing: bool = False,
    default_workers: int = STATION_BAKE_WORKERS,
) -> None:
    global _STATION_BAKE
    tile_size = int(manifest["tile_size"])
    world = manifest["world"]
    tiles_x = math.ceil(int(world["padded_width"]) / tile_size)
    tiles_z = math.ceil(int(world["padded_depth"]) / tile_size)
    buckets = _index_stations(stations, tile_size)
    reach_ext = u32_extension()
    station_ext = u16_extension()
    water_ext = default_tile_extension("int16_le")
    azimuth_ext = u8_extension()
    reach_root = root / REACH_ID_PATH
    station_root = root / STATION_ID_PATH
    water_root = root / WATER_Y_PATH
    azimuth_root = root / FLOW_AZIMUTH_PATH
    for folder in (reach_root, station_root, water_root, azimuth_root):
        folder.mkdir(parents=True, exist_ok=True)
    for tile_z in tqdm(range(tiles_z), desc="river station empty tiles"):
        for tile_x in range(tiles_x):
            if (tile_x, tile_z) in buckets:
                continue
            _clear_tile(reach_root / tile_filename(tile_x, tile_z, reach_ext))
            _clear_tile(station_root / tile_filename(tile_x, tile_z, station_ext))
            _clear_tile(water_root / tile_filename(tile_x, tile_z, water_ext))
            _clear_tile(azimuth_root / tile_filename(tile_x, tile_z, azimuth_ext))
    tasks = [
        (tx, tz, indexes)
        for (tx, tz), indexes in buckets.items()
        if 0 <= tx < tiles_x and 0 <= tz < tiles_z
    ]
    if skip_existing and tasks:
        pending: list[tuple[int, int, list[int]]] = []
        skipped = 0
        for tile_x, tile_z, indexes in tasks:
            if _station_outputs_present(
                reach_root,
                station_root,
                water_root,
                azimuth_root,
                tile_x,
                tile_z,
                reach_ext,
                station_ext,
                water_ext,
                azimuth_ext,
            ):
                skipped += 1
                continue
            pending.append((tile_x, tile_z, indexes))
        tasks = pending
        console.print(f"[cyan]River station resume[/cyan] skipped={skipped} remaining={len(tasks)}")
    if not tasks:
        return
    configured = _env_workers("STATION_BAKE_WORKERS", default_workers)
    processes = min(configured, len(tasks))
    if processes > 1:
        console.print(f"[cyan]River station tiles[/cyan] occupied={len(tasks)} workers={processes}")
    _STATION_BAKE = {
        "stations": stations,
        "tile_size": tile_size,
        "reach_root": reach_root,
        "station_root": station_root,
        "water_root": water_root,
        "azimuth_root": azimuth_root,
        "reach_ext": reach_ext,
        "station_ext": station_ext,
        "water_ext": water_ext,
        "azimuth_ext": azimuth_ext,
    }
    try:
        with _ForkWorkers(processes, enabled=processes > 1) as pool:
            for _ in pool.imap(
                _bake_one_station_tile,
                tasks,
                task_count=len(tasks),
                desc="river station tiles",
            ):
                pass
    finally:
        _STATION_BAKE = None


def _write_station_manifest(manifest_path: Path, manifest: dict[str, Any], stations: Sequence[Station]) -> None:
    rivers = manifest.setdefault("rivers", {})
    rivers["station_schema_version"] = STATION_SCHEMA_VERSION
    rivers["reach_id_path"] = REACH_ID_PATH
    rivers["reach_id_extension"] = u32_extension()
    rivers["reach_id_dtype"] = "uint32_le"
    rivers["station_id_path"] = STATION_ID_PATH
    rivers["station_id_extension"] = u16_extension()
    rivers["station_id_dtype"] = "uint16_le"
    rivers["water_y_path"] = WATER_Y_PATH
    rivers["water_y_extension"] = default_tile_extension("int16_le")
    rivers["water_y_dtype"] = "int16_le"
    rivers["water_y_nodata"] = HEIGHT_NODATA
    rivers["flow_azimuth_path"] = FLOW_AZIMUTH_PATH
    rivers["flow_azimuth_extension"] = u8_extension()
    rivers["flow_azimuth_dtype"] = "uint8"
    rivers["station_count"] = int(len(stations))
    note = str(rivers.get("note") or "")
    extra = (
        " Station schema 1 bakes canonical reach_id, station_id, water_y, and flow_azimuth "
        "from directed edges with associative min ownership."
    )
    if "Station schema 1" not in note:
        rivers["note"] = (note + extra).strip()
    write_manifest(manifest_path, manifest)
