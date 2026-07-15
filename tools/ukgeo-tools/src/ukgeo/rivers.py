from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from collections import defaultdict, deque
import math

import geopandas as gpd
import numpy as np
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rich.console import Console
from shapely.geometry import LineString, MultiLineString
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import read_manifest, write_manifest
from .tiles import HEIGHT_NODATA, read_r16_tile, write_u8_tile, u8_extension, r16_extension

console = Console()

MAX_RIVER_HALFWIDTH = 40
MIN_RIVER_HALFWIDTH_BY_ORDER = {1: 2, 2: 3, 3: 5}
SOURCE_ORDER_FIELDS = ("ORDER_", "ORDER", "US_ORDER", "STRAHLER", "STRAHLER_ORDER", "STREAM_ORDER")
SOURCE_DATASET_FIELDS = ("source_dataset", "SOURCE_DATASET")
THINNER_RIVER_DATASETS = {"epa_river_network_routes_ie", "ni_river_segment"}
THINNER_RIVER_WIDTH_FACTOR = 0.8
TOP_ORDER_THINNING_FACTORS = (0.5, 1.0 / 1.5, 1.0 / 1.25, 1.0 / 1.125)


def make_river_tiles(
    *,
    rivers: Path,
    manifest_path: Path,
    out: Path,
    layer: str | None = None,
    width_metres: float = 30.0,
    debug_geotiff: Path | None = None,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    gpkg, tmp = resolve_gpkg(rivers)
    try:
        layer_name = layer or _default_layer(gpkg)
        frame = gpd.read_file(
            gpkg,
            layer=layer_name,
            bbox=(geo["bng_min_easting"], geo["bng_min_northing"], geo["bng_max_easting"], geo["bng_max_northing"]),
        )
        if frame.empty:
            console.print("[yellow]No river features intersect the manifest extent.[/yellow]")
            arr = np.zeros((depth, width), dtype=np.uint8)
        order_arr = np.zeros((depth, width), dtype=np.uint8)
        half_width_arr = np.zeros((depth, width), dtype=np.uint8)
        if frame.empty:
            order_shapes = []
            variable_shapes = []
        else:
            if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                frame = frame.to_crs("EPSG:27700")
            lines = _extract_lines(frame)
            strahler = _strahler_widths(lines, manifest, manifest_path.parent)
            order_shapes = []
            variable_shapes = []
            shapes = []
            for edge in strahler.edges:
                if edge.line.is_empty:
                    continue
                half_width = max(1, edge.half_width)
                buffer_metres = half_width * _cell_metres(geo, width, depth)
                buffered = edge.line.buffer(buffer_metres, cap_style="round", join_style="round")
                shapes.append((buffered, 255))
                variable_shapes.append((buffered, half_width))
                order_shapes.append((buffered, min(255, max(1, edge.order))))
            if not shapes:
                shapes = []
                variable_shapes = []
                order_shapes = []
            # Fall back to the old fixed-width raster if graph extraction produced no usable line edges.
            fallback_fixed = not shapes
            for geom in tqdm(frame.geometry, desc="buffering rivers"):
                if geom is None or geom.is_empty:
                    continue
                if not isinstance(geom, (LineString, MultiLineString)):
                    continue
                if width_metres > 0 and fallback_fixed:
                    buffered = geom.buffer(width_metres / 2.0, cap_style="round", join_style="round")
                    shapes.append((buffered, 255))
                    variable_shapes.append((buffered, max(1, int(round(width_metres / (2.0 * _cell_metres(geo, width, depth)))))))
                    order_shapes.append((buffered, 1))
                elif fallback_fixed:
                    shapes.append((geom, 255))
                    variable_shapes.append((geom, 1))
                    order_shapes.append((geom, 1))
            arr = rasterize(shapes, out_shape=(depth, width), transform=transform, fill=0, dtype=np.uint8, merge_alg=MergeAlg.replace, all_touched=True) if shapes else np.zeros((depth, width), dtype=np.uint8)
            variable_shapes.sort(key=lambda item: item[1])
            order_shapes.sort(key=lambda item: item[1])
            half_width_arr = rasterize(variable_shapes, out_shape=(depth, width), transform=transform, fill=0, dtype=np.uint8, merge_alg=MergeAlg.replace, all_touched=True) if variable_shapes else half_width_arr
            order_arr = rasterize(order_shapes, out_shape=(depth, width), transform=transform, fill=0, dtype=np.uint8, merge_alg=MergeAlg.replace, all_touched=True) if order_shapes else order_arr
        root = out / "water" / "rivers"
        _write_tiles(arr, root, tile_size)
        _write_tiles(order_arr, out / "water" / "river_order", tile_size)
        _write_tiles(half_width_arr, out / "water" / "river_half_width", tile_size)
        manifest["rivers"] = {
            "path": "water/rivers",
            "extension": u8_extension(),
            "dtype": "uint8",
            "min": 0,
            "max": 255,
            "order_path": "water/river_order",
            "half_width_path": "water/river_half_width",
            "max_half_width": int(half_width_arr.max()) if half_width_arr.size else 0,
            "note": "255 marks cells inside variable-width river/watercourse vectors. Widths are derived from source river order where present, otherwise approximate Strahler stream order.",
        }
        write_manifest(manifest_path, manifest)
        if debug_geotiff:
            _write_debug(debug_geotiff, arr, transform)
    finally:
        if tmp is not None:
            tmp.cleanup()


def _default_layer(gpkg: Path) -> str:
    import fiona

    layers = fiona.listlayers(gpkg)
    if "watercourse_link" in layers:
        return "watercourse_link"
    for layer in layers:
        if "watercourse" in layer.lower() or "river" in layer.lower():
            return layer
    return layers[0]


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int) -> None:
    for tile_z in tqdm(range(math.ceil(arr.shape[0] / tile_size)), desc="river tile rows"):
        for tile_x in range(math.ceil(arr.shape[1] / tile_size)):
            tile = arr[tile_z * tile_size : (tile_z + 1) * tile_size, tile_x * tile_size : (tile_x + 1) * tile_size]
            if tile.shape != (tile_size, tile_size):
                padded = np.zeros((tile_size, tile_size), dtype=np.uint8)
                padded[: tile.shape[0], : tile.shape[1]] = tile
                tile = padded
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}{u8_extension()}", tile)


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)


@dataclass(frozen=True)
class _InputLine:
    line: LineString
    source_order: int | None = None
    source_dataset: str | None = None


def _source_column(columns: list[str], candidates: tuple[str, ...]) -> str | None:
    lookup = {column.upper(): column for column in columns}
    for candidate in candidates:
        found = lookup.get(candidate.upper())
        if found is not None:
            return found
    return None


def _source_order_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_ORDER_FIELDS)


def _source_dataset_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_DATASET_FIELDS)


def _coerce_source_order(value) -> int | None:
    if value is None:
        return None
    try:
        if np.isnan(value):
            return None
    except TypeError:
        pass
    try:
        order = int(value)
    except (TypeError, ValueError):
        return None
    return order if order > 0 else None


def _extract_lines(frame: gpd.GeoDataFrame) -> list[_InputLine]:
    columns = list(frame.columns)
    order_column = _source_order_column(columns)
    source_dataset_column = _source_dataset_column(columns)
    order_values = frame[order_column] if order_column is not None else None
    source_dataset_values = frame[source_dataset_column] if source_dataset_column is not None else None
    lines: list[_InputLine] = []
    for geom, raw_order, raw_source_dataset in zip(
        frame.geometry,
        order_values if order_values is not None else [None] * len(frame),
        source_dataset_values if source_dataset_values is not None else [None] * len(frame),
        strict=False,
    ):
        source_order = _coerce_source_order(raw_order)
        source_dataset = str(raw_source_dataset) if raw_source_dataset not in {None, ""} else None
        if geom is None or geom.is_empty:
            continue
        if isinstance(geom, LineString):
            if len(geom.coords) >= 2:
                lines.append(_InputLine(geom, source_order, source_dataset))
        elif isinstance(geom, MultiLineString):
            for part in geom.geoms:
                if len(part.coords) >= 2:
                    lines.append(_InputLine(part, source_order, source_dataset))
    return lines


def _cell_metres(geo: dict, width: int, depth: int) -> float:
    x = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / max(1, width)
    z = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / max(1, depth)
    return (abs(x) + abs(z)) * 0.5


class _HeightSampler:
    def __init__(self, manifest: dict, root: Path):
        self.manifest = manifest
        self.root = root
        self.tile_size = int(manifest["tile_size"])
        self.geo = manifest["georeferencing"]
        self.world = manifest["world"]
        self.cache: dict[tuple[int, int], np.ndarray] = {}

    def sample(self, easting: float, northing: float) -> float | None:
        width = int(self.world["width"])
        depth = int(self.world["depth"])
        data_x = int(math.floor((easting - float(self.geo["bng_min_easting"])) * width / (float(self.geo["bng_max_easting"]) - float(self.geo["bng_min_easting"]))))
        data_z = int(math.floor((float(self.geo["bng_max_northing"]) - northing) * depth / (float(self.geo["bng_max_northing"]) - float(self.geo["bng_min_northing"]))))
        if data_x < 0 or data_z < 0 or data_x >= int(self.world["padded_width"]) or data_z >= int(self.world["padded_depth"]):
            return None
        tile_x = data_x // self.tile_size
        tile_z = data_z // self.tile_size
        tile = self.cache.get((tile_x, tile_z))
        if tile is None:
            path = self.root / self.manifest["height"]["path"] / f"{tile_x:03d}_{tile_z:03d}{self.manifest['height'].get('extension', r16_extension())}"
            if not path.exists():
                return None
            tile = read_r16_tile(path, self.tile_size)
            self.cache[(tile_x, tile_z)] = tile
        value = int(tile[data_z % self.tile_size, data_x % self.tile_size])
        return None if value == HEIGHT_NODATA else value / 10.0


class _Edge:
    def __init__(self, line: LineString, order: int, half_width: int, source_dataset: str | None = None):
        self.line = line
        self.order = order
        self.half_width = half_width
        self.source_dataset = source_dataset


class _StrahlerResult:
    def __init__(self, edges: list[_Edge]):
        self.edges = edges


def _strahler_widths(lines: list[_InputLine], manifest: dict, root: Path) -> _StrahlerResult:
    if not lines:
        return _StrahlerResult([])
    sampler = _HeightSampler(manifest, root)
    source_max_order = max((item.source_order or 0) for item in lines)
    node_ids: dict[tuple[int, int], int] = {}
    node_points: list[tuple[float, float]] = []

    def node_id(point: tuple[float, float]) -> int:
        key = (round(point[0]), round(point[1]))
        found = node_ids.get(key)
        if found is not None:
            return found
        found = len(node_points)
        node_ids[key] = found
        node_points.append((float(point[0]), float(point[1])))
        return found

    raw_edges: list[tuple[int, int, LineString, int | None, str | None]] = []
    for item in lines:
        coords = list(item.line.coords)
        a = node_id((coords[0][0], coords[0][1]))
        b = node_id((coords[-1][0], coords[-1][1]))
        if a != b:
            raw_edges.append((a, b, item.line, item.source_order, item.source_dataset))
    if not raw_edges:
        return _StrahlerResult([])

    heights = [sampler.sample(e, n) for e, n in node_points]
    downstream: dict[int, list[tuple[int, int]]] = defaultdict(list)
    upstream_count: dict[int, int] = defaultdict(int)
    oriented: list[tuple[int, int, LineString, int | None, str | None]] = []
    for a, b, line, source_order, source_dataset in raw_edges:
        ha = heights[a]
        hb = heights[b]
        if ha is not None and hb is not None and ha != hb:
            src, dst = (a, b) if ha > hb else (b, a)
        else:
            na = node_points[a][1]
            nb = node_points[b][1]
            src, dst = (a, b) if (na > nb or (na == nb and a < b)) else (b, a)
        downstream[src].append((dst, len(oriented)))
        upstream_count[dst] += 1
        upstream_count.setdefault(src, upstream_count.get(src, 0))
        oriented.append((src, dst, line, source_order, source_dataset))

    node_order = [1] * len(node_points)
    incoming_orders: list[list[int]] = [[] for _ in node_points]
    ready = deque(sorted(node for node in range(len(node_points)) if upstream_count.get(node, 0) == 0))
    remaining = dict(upstream_count)
    processed_edges: set[int] = set()

    def combine(orders: list[int]) -> int:
        if not orders:
            return 1
        highest = max(orders)
        return highest + 1 if sum(1 for order in orders if order == highest) >= 2 else highest

    while ready:
        node = ready.popleft()
        node_order[node] = max(node_order[node], combine(incoming_orders[node]))
        for dst, edge_index in downstream.get(node, []):
            processed_edges.add(edge_index)
            incoming_orders[dst].append(node_order[node])
            remaining[dst] = remaining.get(dst, 0) - 1
            if remaining[dst] == 0:
                ready.append(dst)

    # Cycles/braids are collapsed deterministically by processing high-to-low elevation and carrying max incoming order.
    unresolved = [i for i in range(len(oriented)) if i not in processed_edges]
    for edge_index in sorted(unresolved, key=lambda i: (-(heights[oriented[i][0]] or -9999.0), oriented[i][0], oriented[i][1])):
        src, dst, _, _, _ = oriented[edge_index]
        node_order[src] = max(node_order[src], combine(incoming_orders[src]))
        incoming_orders[dst].append(node_order[src])
        node_order[dst] = max(node_order[dst], combine(incoming_orders[dst]))

    computed_max_order = max((node_order[src] for src, _, _, source_order, _ in oriented if source_order is None), default=0)
    edges = []
    for src, dst, line, source_order, source_dataset in oriented:
        computed_order = max(1, node_order[src])
        computed_order = _normalize_computed_order_to_source_scale(computed_order, computed_max_order, source_max_order)
        order = source_order if source_order is not None else computed_order
        base_half_width = _half_width_for_order(order)
        half_width = _scaled_half_width_for_order(order, base_half_width)
        e0 = heights[src]
        e1 = heights[dst]
        if e0 is not None and e1 is not None:
            slope = abs(e0 - e1) / max(1.0, line.length)
            if slope > 0.018:
                half_width = max(1, round(half_width * 0.75))
            elif slope < 0.003 and min(e0, e1) < 90.0:
                half_width = round(half_width * 1.2)
        minimum = _minimum_half_width_for_order(order)
        clamped_half_width = min(MAX_RIVER_HALFWIDTH, max(minimum, half_width))
        edges.append(_Edge(line, min(order, 255), _dataset_half_width(clamped_half_width, source_dataset), source_dataset))
    _thin_top_order_half_widths(edges)
    return _StrahlerResult(edges)


def _thin_top_order_half_widths(edges: list[_Edge]) -> None:
    if not edges:
        return
    top_orders = sorted({edge.order for edge in edges if edge.order > 0}, reverse=True)[: len(TOP_ORDER_THINNING_FACTORS)]
    factors = {order: factor for order, factor in zip(top_orders, TOP_ORDER_THINNING_FACTORS, strict=False)}
    for edge in edges:
        factor = factors.get(edge.order)
        if factor is None:
            continue
        edge.half_width = max(1, int(round(edge.half_width * factor)))


def _normalize_computed_order_to_source_scale(order: int, computed_max_order: int, source_max_order: int) -> int:
    if order <= 0:
        return 1
    if source_max_order <= 0 or computed_max_order <= 0 or computed_max_order <= source_max_order:
        return order
    scaled = int(round(order * source_max_order / computed_max_order))
    return max(1, min(source_max_order, scaled))


def _dataset_half_width(half_width: int, source_dataset: str | None) -> int:
    if source_dataset not in THINNER_RIVER_DATASETS:
        return half_width
    return max(1, int(math.floor(half_width * THINNER_RIVER_WIDTH_FACTOR)))


def _scaled_half_width_for_order(order: int, base_half_width: int) -> int:
    scaled = math.ceil(base_half_width * _width_multiplier_for_order(order))
    return max(_minimum_half_width_for_order(order), scaled)


def _width_multiplier_for_order(order: int) -> float:
    if order <= 1:
        return 2.0
    if order == 2:
        return 1.8
    return 1.5


def _minimum_half_width_for_order(order: int) -> int:
    if order <= 1:
        return MIN_RIVER_HALFWIDTH_BY_ORDER[1]
    if order == 2:
        return MIN_RIVER_HALFWIDTH_BY_ORDER[2]
    if order == 3:
        return MIN_RIVER_HALFWIDTH_BY_ORDER[3]
    return 1


def _half_width_for_order(order: int) -> int:
    if order <= 1:
        return 1
    if order == 2:
        return 2
    if order == 3:
        return 4
    if order == 4:
        return 6
    if order == 5:
        return 9
    return 12 + min(8, (order - 6) * 3)
