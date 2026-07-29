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
from shapely.ops import linemerge
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import read_manifest, write_manifest
from .raster_memory import U8Raster, find_memmap_path, maximum_in_place, row_windows
from .tiles import HEIGHT_NODATA, read_r16_tile, write_u8_tile, u8_extension, r16_extension

console = Console()

MAX_RIVER_HALFWIDTH = 40
MIN_RIVER_HALFWIDTH_BY_ORDER = {1: 2, 2: 3, 3: 5}
SOURCE_ORDER_FIELDS = ("ORDER_", "ORDER", "US_ORDER", "STRAHLER", "STRAHLER_ORDER", "STREAM_ORDER")
SOURCE_DATASET_FIELDS = ("source_dataset", "SOURCE_DATASET")
SOURCE_START_NODE_FIELDS = ("start_node", "START_NODE", "source_start_node", "FROM_NODE", "from_node")
SOURCE_END_NODE_FIELDS = ("end_node", "END_NODE", "source_end_node", "TO_NODE", "to_node")
SOURCE_FLOW_DIRECTION_FIELDS = ("flow_direction", "FLOW_DIRECTION", "direction")
THINNER_RIVER_DATASETS = {"epa_river_network_routes_ie", "ni_river_segment"}
THINNER_RIVER_WIDTH_FACTOR = 0.7
GB_RIVER_DATASETS = {"os_open_rivers_gb"}
GB_SMALL_STREAM_HALF_WIDTH_BONUS = 2
GB_MAJOR_RIVER_WIDTH_FACTOR = 1.5
TOP_ORDER_THINNING_FACTORS = (0.5, 1.0 / 1.5, 1.0 / 1.25, 1.0 / 1.125)
RIVER_OUTPUT_WIDTH_FACTOR = 1.0 / 6.0
RIVER_RASTER_BATCH_SIZE = 25000
PREVIEW_RIVER_WIDTH_SCALE = 0.18
PREVIEW_RIVER_MAX_RADIUS = 6
RIVER_MEMMAP_LABELS = (
    "rivers",
    "river-order",
    "river-half-width",
    "river-preview-radius",
    "river-burn",
)


def make_river_tiles(
    *,
    rivers: Path,
    manifest_path: Path,
    out: Path,
    layer: str | None = None,
    width_metres: float = 30.0,
    debug_geotiff: Path | None = None,
    resume_memmaps: bool = False,
    skip_edges: int = 0,
) -> None:
    if skip_edges < 0:
        raise ValueError("--skip-edges must be >= 0")
    if skip_edges and not resume_memmaps:
        raise ValueError("--skip-edges requires --resume-memmaps")
    if resume_memmaps and skip_edges % RIVER_RASTER_BATCH_SIZE != 0:
        raise ValueError(
            f"--skip-edges must be a multiple of {RIVER_RASTER_BATCH_SIZE} "
            f"(last flushed batch boundary); got {skip_edges}"
        )
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
    resume_paths = _resolve_resume_paths(out) if resume_memmaps else {}
    if resume_memmaps:
        console.print(
            f"[cyan]Resuming river memmaps under {out}; skipping first {skip_edges} edges[/cyan]"
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
        with (
            U8Raster((depth, width), tmp_parent=out, label="rivers", resume_path=resume_paths.get("rivers")) as arr,
            U8Raster(
                (depth, width), tmp_parent=out, label="river-order", resume_path=resume_paths.get("river-order")
            ) as order_arr,
            U8Raster(
                (depth, width),
                tmp_parent=out,
                label="river-half-width",
                resume_path=resume_paths.get("river-half-width"),
            ) as half_width_arr,
            U8Raster(
                (depth, width),
                tmp_parent=out,
                label="river-preview-radius",
                resume_path=resume_paths.get("river-preview-radius"),
            ) as preview_radius_arr,
        ):
            if not frame.empty:
                if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                    frame = frame.to_crs("EPSG:27700")
                lines = _extract_lines(frame)
                strahler = _strahler_widths(lines, manifest, manifest_path.parent)
                # Fall back to the old fixed-width raster if graph extraction produced no usable line edges.
                fallback_fixed = not strahler.edges
                cell_metres = _cell_metres(geo, width, depth)
                axis_scale = _horizontal_axis_scale(manifest)
                if fallback_fixed:
                    if skip_edges:
                        raise ValueError("--skip-edges is not supported for the fixed-width river fallback path")
                    cover_shapes: list[tuple[object, int]] = []
                    width_shapes: list[tuple[object, int]] = []
                    order_shapes: list[tuple[object, int]] = []
                    preview_shapes: list[tuple[object, int]] = []
                    scaled_width_metres = width_metres * axis_scale * RIVER_OUTPUT_WIDTH_FACTOR
                    fallback_half_width = (
                        max(1, int(round(scaled_width_metres / (2.0 * cell_metres)))) if scaled_width_metres > 0 else 1
                    )
                    for geom in tqdm(frame.geometry, desc="rasterizing fallback rivers"):
                        if geom is None or geom.is_empty:
                            continue
                        if not isinstance(geom, (LineString, MultiLineString)):
                            continue
                        if scaled_width_metres > 0:
                            shape = geom.buffer(scaled_width_metres / 2.0, cap_style="round", join_style="round")
                        else:
                            shape = geom
                        cover_shapes.append((shape, 255))
                        width_shapes.append((shape, fallback_half_width))
                        order_shapes.append((shape, 1))
                        preview_shapes.append((shape, _preview_radius_for_order(1)))
                        if len(cover_shapes) >= RIVER_RASTER_BATCH_SIZE:
                            _rasterize_shape_batches_direct(cover_shapes, arr, transform)
                            _rasterize_shape_batches_direct(width_shapes, half_width_arr, transform)
                            _rasterize_shape_batches_direct(order_shapes, order_arr, transform)
                            _rasterize_shape_batches_direct(preview_shapes, preview_radius_arr, transform)
                            cover_shapes.clear()
                            width_shapes.clear()
                            order_shapes.clear()
                            preview_shapes.clear()
                    _rasterize_shape_batches_direct(cover_shapes, arr, transform)
                    _rasterize_shape_batches_direct(width_shapes, half_width_arr, transform)
                    _rasterize_shape_batches_direct(order_shapes, order_arr, transform)
                    _rasterize_shape_batches_direct(preview_shapes, preview_radius_arr, transform)
                else:
                    edges = strahler.edges
                    if skip_edges > len(edges):
                        raise ValueError(f"--skip-edges {skip_edges} exceeds edge count {len(edges)}")
                    remaining_edges = edges[skip_edges:]
                    _rasterize_edge_pass(
                        remaining_edges,
                        arr,
                        transform,
                        cell_metres,
                        value_fn=lambda edge: 255,
                        desc="rasterizing river coverage",
                        initial=skip_edges,
                        total=len(edges),
                    )
                    _rasterize_edge_pass(
                        sorted(remaining_edges, key=lambda edge: min(255, max(1, edge.order))),
                        order_arr,
                        transform,
                        cell_metres,
                        value_fn=lambda edge: min(255, max(1, edge.order)),
                        desc="rasterizing river order",
                    )
                    _rasterize_edge_pass(
                        sorted(remaining_edges, key=lambda edge: min(255, max(1, edge.half_width))),
                        half_width_arr,
                        transform,
                        cell_metres,
                        value_fn=lambda edge: min(255, max(1, edge.half_width)),
                        desc="rasterizing river half width",
                    )
                    _rasterize_edge_pass(
                        sorted(remaining_edges, key=_preview_radius_for_edge),
                        preview_radius_arr,
                        transform,
                        cell_metres,
                        value_fn=_preview_radius_for_edge,
                        desc="rasterizing river preview radius",
                    )
            root = out / "water" / "rivers"
            _write_tiles(arr, root, tile_size)
            _write_tiles(order_arr, out / "water" / "river_order", tile_size)
            _write_tiles(half_width_arr, out / "water" / "river_half_width", tile_size)
            _write_tiles(preview_radius_arr, out / "water" / "river_preview_radius", tile_size)
            max_half_width = _u8_max(half_width_arr) if half_width_arr.size else 0
            manifest["rivers"] = {
                "path": "water/rivers",
                "extension": u8_extension(),
                "dtype": "uint8",
                "min": 0,
                "max": 255,
                "order_path": "water/river_order",
                "half_width_path": "water/river_half_width",
                "preview_radius_path": "water/river_preview_radius",
                "max_half_width": max_half_width,
                "note": "255 marks cells inside variable-width river/watercourse vectors. Widths are derived from source river order where present, otherwise approximate Strahler stream order, then scaled to one-third output width for in-world readability.",
            }
            write_manifest(manifest_path, manifest)
            if debug_geotiff:
                _write_debug(debug_geotiff, arr, transform)
    finally:
        if tmp is not None:
            tmp.cleanup()


def _resolve_resume_paths(out: Path) -> dict[str, Path]:
    return {label: find_memmap_path(out, label) for label in RIVER_MEMMAP_LABELS}


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


def _u8_max(arr: np.ndarray) -> int:
    maximum = 0
    for window in row_windows(arr.shape[0]):
        maximum = max(maximum, int(arr[window].max()))
    return maximum


def _zero_u8(arr: np.ndarray) -> None:
    for window in row_windows(arr.shape[0]):
        arr[window] = 0
    if isinstance(arr, np.memmap):
        arr.flush()


def _rasterize_shape_batches(
    shapes: list[tuple[object, int]],
    out: np.ndarray,
    transform,
    burned: np.ndarray,
) -> None:
    if not shapes:
        return
    _zero_u8(burned)
    rasterize(
        shapes,
        out=burned,
        transform=transform,
        fill=0,
        dtype=np.uint8,
        merge_alg=MergeAlg.replace,
        all_touched=True,
    )
    maximum_in_place(out, burned)


def _rasterize_edge_pass(
    edges: list[_Edge],
    out: np.ndarray,
    transform,
    cell_metres: float,
    *,
    value_fn,
    desc: str,
    initial: int = 0,
    total: int | None = None,
) -> None:
    shapes: list[tuple[object, int]] = []
    progress_total = total if total is not None else len(edges)
    for edge in tqdm(edges, desc=desc, initial=initial, total=progress_total):
        if edge.line.is_empty:
            continue
        half_width = max(1, edge.half_width)
        buffer_metres = half_width * cell_metres
        buffered = edge.line.buffer(buffer_metres, cap_style="round", join_style="round")
        shapes.append((buffered, int(value_fn(edge))))
        if len(shapes) >= RIVER_RASTER_BATCH_SIZE:
            _rasterize_shape_batches_direct(shapes, out, transform)
            shapes.clear()
    _rasterize_shape_batches_direct(shapes, out, transform)


def _rasterize_shape_batches_direct(
    shapes: list[tuple[object, int]],
    out: np.ndarray,
    transform,
) -> None:
    if not shapes:
        return
    rasterize(
        shapes,
        out=out,
        transform=transform,
        dtype=np.uint8,
        merge_alg=MergeAlg.replace,
        all_touched=True,
    )


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
    source_start_node: str | None = None
    source_end_node: str | None = None
    source_flow_direction: str | None = None


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


def _source_start_node_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_START_NODE_FIELDS)


def _source_end_node_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_END_NODE_FIELDS)


def _source_flow_direction_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_FLOW_DIRECTION_FIELDS)


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


def _coerce_optional_text(value) -> str | None:
    if value is None:
        return None
    try:
        if np.isnan(value):
            return None
    except TypeError:
        pass
    text = str(value).strip()
    if not text or text.lower() == "nan":
        return None
    return text


def _extract_lines(frame: gpd.GeoDataFrame) -> list[_InputLine]:
    columns = list(frame.columns)
    order_column = _source_order_column(columns)
    source_dataset_column = _source_dataset_column(columns)
    source_start_node_column = _source_start_node_column(columns)
    source_end_node_column = _source_end_node_column(columns)
    source_flow_direction_column = _source_flow_direction_column(columns)
    order_values = frame[order_column] if order_column is not None else None
    source_dataset_values = frame[source_dataset_column] if source_dataset_column is not None else None
    source_start_node_values = frame[source_start_node_column] if source_start_node_column is not None else None
    source_end_node_values = frame[source_end_node_column] if source_end_node_column is not None else None
    source_flow_direction_values = frame[source_flow_direction_column] if source_flow_direction_column is not None else None
    lines: list[_InputLine] = []
    for geom, raw_order, raw_source_dataset, raw_source_start_node, raw_source_end_node, raw_source_flow_direction in zip(
        frame.geometry,
        order_values if order_values is not None else [None] * len(frame),
        source_dataset_values if source_dataset_values is not None else [None] * len(frame),
        source_start_node_values if source_start_node_values is not None else [None] * len(frame),
        source_end_node_values if source_end_node_values is not None else [None] * len(frame),
        source_flow_direction_values if source_flow_direction_values is not None else [None] * len(frame),
        strict=False,
    ):
        source_order = _coerce_source_order(raw_order)
        source_dataset = _coerce_optional_text(raw_source_dataset)
        source_start_node = _coerce_optional_text(raw_source_start_node)
        source_end_node = _coerce_optional_text(raw_source_end_node)
        source_flow_direction = _coerce_optional_text(raw_source_flow_direction)
        if geom is None or geom.is_empty:
            continue
        if isinstance(geom, LineString):
            if len(geom.coords) >= 2:
                lines.append(_InputLine(geom, source_order, source_dataset, source_start_node, source_end_node, source_flow_direction))
        elif isinstance(geom, MultiLineString):
            merged = linemerge(geom)
            if isinstance(merged, LineString) and len(merged.coords) >= 2 and source_start_node and source_end_node:
                lines.append(_InputLine(merged, source_order, source_dataset, source_start_node, source_end_node, source_flow_direction))
                continue
            for part in geom.geoms:
                if len(part.coords) >= 2:
                    lines.append(_InputLine(part, source_order, source_dataset, None, None, source_flow_direction))
    return lines


def _cell_metres(geo: dict, width: int, depth: int) -> float:
    x = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / max(1, width)
    z = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / max(1, depth)
    return (abs(x) + abs(z)) * 0.5


def _horizontal_axis_scale(manifest: dict) -> float:
    """Return the mean |axis_scale| for x/z, floored at 1.0.

    At axis_scale 2 the raster cells are half as wide in metres, so order-based
    half-widths (in cells) must be doubled to keep rivers the same physical and
    relative size as a 1x world.
    """
    axis = manifest.get("axis_scale") or {}
    try:
        scale_x = abs(float(axis.get("x", 1.0) or 1.0))
        scale_z = abs(float(axis.get("z", 1.0) or 1.0))
    except (TypeError, ValueError):
        return 1.0
    return max(1.0, (scale_x + scale_z) * 0.5)


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
    def __init__(self, line: LineString, order: int, half_width: int, source_dataset: str | None = None, source_order_provided: bool = False):
        self.line = line
        self.order = order
        self.half_width = half_width
        self.source_dataset = source_dataset
        self.source_order_provided = source_order_provided


class _StrahlerResult:
    def __init__(self, edges: list[_Edge]):
        self.edges = edges


def _strahler_widths(lines: list[_InputLine], manifest: dict, root: Path) -> _StrahlerResult:
    if not lines:
        return _StrahlerResult([])
    # Keep each source dataset as its own network. Irish rivers ship with ORDER_
    # while GB must compute Strahler; mixing them made GB orders compress to 1
    # against the Irish source_max / inflated computed_max.
    by_dataset: dict[str | None, list[_InputLine]] = defaultdict(list)
    for item in lines:
        by_dataset[item.source_dataset].append(item)
    edges: list[_Edge] = []
    axis_scale = _horizontal_axis_scale(manifest)
    for dataset_lines in by_dataset.values():
        edges.extend(_strahler_widths_for_network(dataset_lines, manifest, root).edges)
    _apply_axis_scale_to_half_widths(edges, axis_scale)
    _apply_gb_width_adjustments(edges, axis_scale)
    _apply_output_width_scale(edges)
    return _StrahlerResult(edges)


def _strahler_widths_for_network(lines: list[_InputLine], manifest: dict, root: Path) -> _StrahlerResult:
    if not lines:
        return _StrahlerResult([])
    sampler = _HeightSampler(manifest, root)
    source_max_order = max((item.source_order or 0) for item in lines)
    node_ids: dict[tuple[str, str] | tuple[int, int], int] = {}
    node_points: list[tuple[float, float]] = []

    def node_id(point: tuple[float, float], source_node: str | None = None) -> int:
        key: tuple[str, str] | tuple[int, int]
        if source_node is not None:
            key = ("source-node", source_node)
        else:
            key = (round(point[0]), round(point[1]))
        found = node_ids.get(key)
        if found is not None:
            return found
        found = len(node_points)
        node_ids[key] = found
        node_points.append((float(point[0]), float(point[1])))
        return found

    raw_edges: list[tuple[int, int, LineString, int | None, str | None, str | None]] = []
    for item in lines:
        coords = list(item.line.coords)
        a = node_id((coords[0][0], coords[0][1]), item.source_start_node)
        b = node_id((coords[-1][0], coords[-1][1]), item.source_end_node)
        if a != b:
            raw_edges.append(
                (a, b, item.line, item.source_order, item.source_dataset, item.source_flow_direction)
            )
    if not raw_edges:
        return _StrahlerResult([])

    heights = [sampler.sample(e, n) for e, n in node_points]
    downstream: dict[int, list[tuple[int, int]]] = defaultdict(list)
    upstream_count: dict[int, int] = defaultdict(int)
    oriented: list[tuple[int, int, LineString, int | None, str | None]] = []
    # Iterate raw_edges only — never zip with `lines`. Degenerate a==b skips would
    # shift that pairing and apply the wrong flow_direction to later edges.
    for a, b, line, source_order, source_dataset, flow_direction in raw_edges:
        src, dst = _orient_edge(a, b, flow_direction, heights, node_points)
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
        edges.append(
            _Edge(
                line,
                min(order, 255),
                _dataset_half_width(clamped_half_width, source_dataset),
                source_dataset,
                source_order is not None,
            )
        )
    _thin_top_order_half_widths(edges)
    return _StrahlerResult(edges)


def _apply_axis_scale_to_half_widths(edges: list[_Edge], axis_scale: float) -> None:
    if axis_scale <= 1.0 or not edges:
        return
    max_half_width = max(1, int(round(MAX_RIVER_HALFWIDTH * axis_scale)))
    for edge in edges:
        scaled = max(1, int(round(edge.half_width * axis_scale)))
        edge.half_width = min(max_half_width, scaled)


def _apply_output_width_scale(edges: list[_Edge]) -> None:
    if not edges:
        return
    for edge in edges:
        edge.half_width = max(1, int(round(edge.half_width * RIVER_OUTPUT_WIDTH_FACTOR)))


def _thin_top_order_half_widths(edges: list[_Edge]) -> None:
    thin_edges = [edge for edge in edges if edge.source_dataset in THINNER_RIVER_DATASETS]
    if not thin_edges:
        return
    top_orders = sorted({edge.order for edge in thin_edges if edge.order > 0}, reverse=True)[
        : len(TOP_ORDER_THINNING_FACTORS)
    ]
    factors = {order: factor for order, factor in zip(top_orders, TOP_ORDER_THINNING_FACTORS, strict=False)}
    for edge in thin_edges:
        factor = factors.get(edge.order)
        if factor is None:
            continue
        edge.half_width = max(1, int(round(edge.half_width * factor)))


def _apply_gb_width_adjustments(edges: list[_Edge], axis_scale: float) -> None:
    if not edges:
        return
    max_half_width = max(1, int(round(MAX_RIVER_HALFWIDTH * max(1.0, axis_scale))))
    for edge in edges:
        if edge.source_dataset not in GB_RIVER_DATASETS:
            continue
        if edge.order <= 1:
            continue
        if edge.order <= 3:
            edge.half_width = min(max_half_width, edge.half_width + GB_SMALL_STREAM_HALF_WIDTH_BONUS)
            continue
        edge.half_width = min(
            max_half_width,
            max(1, int(round(edge.half_width * GB_MAJOR_RIVER_WIDTH_FACTOR))),
        )


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


def _preview_radius_for_half_width(half_width: int) -> int:
    if half_width <= 0:
        return 0
    return max(1, min(PREVIEW_RIVER_MAX_RADIUS, int(round(half_width * PREVIEW_RIVER_WIDTH_SCALE))))


def _preview_radius_for_order(order: int) -> int:
    if order <= 0:
        return 0
    if order in {1, 2}:
        return 1
    if order == 3:
        return 2
    if order == 4:
        return 3
    if order == 5:
        return 5
    return 6


def _irish_preview_radius_for_order(order: int) -> int:
    if order <= 2:
        return 0
    if order == 3:
        return 1
    if order == 4:
        return 2
    if order == 5:
        return 3
    if order == 6:
        return 4
    return 5


def _preview_radius_for_edge(edge: _Edge) -> int:
    if edge.source_dataset == "epa_river_network_routes_ie":
        return _irish_preview_radius_for_order(edge.order)
    if edge.source_dataset == "ni_river_segment":
        return _preview_radius_for_half_width(edge.half_width)
    if not edge.source_order_provided:
        return _preview_radius_for_order(edge.order)
    return _preview_radius_for_half_width(edge.half_width)


def _orient_edge(
    a: int,
    b: int,
    source_flow_direction: str | None,
    heights: list[float | None],
    node_points: list[tuple[float, float]],
) -> tuple[int, int]:
    flow = (source_flow_direction or "").strip().lower()
    if flow:
        if "against" in flow:
            return b, a
        if "in direction" in flow or "with" in flow:
            return a, b
    ha = heights[a]
    hb = heights[b]
    if ha is not None and hb is not None and ha != hb:
        return (a, b) if ha > hb else (b, a)
    na = node_points[a][1]
    nb = node_points[b][1]
    return (a, b) if (na > nb or (na == nb and a < b)) else (b, a)


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
