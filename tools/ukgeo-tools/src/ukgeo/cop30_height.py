from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import shutil
import tarfile
import tempfile

import numpy as np
from affine import Affine
from pyproj import Transformer
import rasterio
from rasterio.enums import Resampling
from rasterio.transform import from_bounds
from rasterio.warp import reproject, transform_bounds
from rasterio.windows import Window
from rich.console import Console
from shapely import contains_xy
from shapely.geometry import MultiPolygon, Polygon
from tqdm import tqdm

from .height import _normalise_choice, process_height_mosaic
from .manifest import read_manifest, write_manifest
from .tiles import HEIGHT_NODATA, read_r16_tile, r16_extension, write_r16_tile

console = Console()

TARGET_MODES = {"ireland-iom", "ireland-only", "iom-only", "all-cop30"}
DEST_CRS = "EPSG:27700"
DEFAULT_MINECRAFT_HEIGHT_SCALE = 0.18
COP30_MIN_LAND_METRES = 0.0
WGS84_TO_BNG = Transformer.from_crs("EPSG:4326", DEST_CRS, always_xy=True)
IRELAND_MASK_EXTENSION_CIRCLES_BNG = (
    (162_623.7, 513_045.0, 31_200.0),  # Belfast coast cutoff, northern patch
    (147_725.9, 467_831.0, 31_200.0),  # Belfast coast cutoff, southern patch
)


@dataclass(frozen=True)
class Cop30Raster:
    name: str
    path: Path
    crs: str
    bounds: tuple[float, float, float, float]


@dataclass
class OverlayStats:
    source_rasters: int = 0
    target_cells_considered: int = 0
    land_mask_cells: int = 0
    ocean_cells_skipped: int = 0
    cells_written: int = 0
    cells_skipped_nodata: int = 0
    cells_skipped_outside_target: int = 0
    cells_skipped_mainland_gb: int = 0
    tiles_touched: int = 0
    target_min_x: float | None = None
    target_min_y: float | None = None
    target_max_x: float | None = None
    target_max_y: float | None = None
    land_min_x: float | None = None
    land_min_y: float | None = None
    land_max_x: float | None = None
    land_max_y: float | None = None
    written_min_x: float | None = None
    written_min_y: float | None = None
    written_max_x: float | None = None
    written_max_y: float | None = None


def add_cop30_height_tiles(
    *,
    cop30_archive: Path,
    manifest_path: Path,
    out: Path,
    resampling: str = "bilinear",
    smoothing: str = "light",
    deterrace: bool = True,
    target: str = "ireland-iom",
    protect_mainland_gb: bool = True,
    minecraft_y_offset: float = 0.0,
    debug_geotiff: Path | None = None,
    debug_mask_geotiff: Path | None = None,
    debug_target_mask_geotiff: Path | None = None,
    debug_land_mask_geotiff: Path | None = None,
    debug_written_geotiff: Path | None = None,
    allow_empty: bool = False,
) -> None:
    resampling = _normalise_choice(resampling, {"nearest", "bilinear"}, "resampling")
    smoothing = _normalise_choice(smoothing, {"none", "light", "medium"}, "smoothing")
    target = _normalise_choice(target, TARGET_MODES, "target")

    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    manifest_crs = geo.get("crs", DEST_CRS)
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    padded_width = int(world["padded_width"])
    padded_depth = int(world["padded_depth"])
    tiles_x = padded_width // tile_size
    tiles_z = padded_depth // tile_size
    x_scale = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / width
    z_scale = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / depth
    method = _resampling(resampling)
    height_root = out / manifest["height"]["path"]
    height_scale = _minecraft_height_scale(manifest)
    height_offset_decimetres = int(round((minecraft_y_offset / height_scale) * 10.0)) if minecraft_y_offset else 0
    written_debug_path = debug_written_geotiff or debug_geotiff

    with tempfile.TemporaryDirectory(prefix="ukgeo-cop30-") as tmp_name:
        rasters = _extract_geotiffs(cop30_archive, Path(tmp_name))
        if not rasters:
            raise ValueError(f"No GeoTIFF files found in {cop30_archive}")

        source_crs_summary = _source_crs_summary(rasters)
        _warn_if_source_extends_outside_manifest(rasters, geo, manifest_crs)
        stats = OverlayStats(source_rasters=len(rasters))
        land_debug_path = debug_land_mask_geotiff or debug_mask_geotiff
        debug_written = _DebugWriter(written_debug_path, manifest, dtype="int16", nodata=HEIGHT_NODATA) if written_debug_path else None
        debug_target_mask = _DebugWriter(debug_target_mask_geotiff, manifest, dtype="uint8", nodata=0) if debug_target_mask_geotiff else None
        debug_land_mask = _DebugWriter(land_debug_path, manifest, dtype="uint8", nodata=0) if land_debug_path else None
        try:
            for tile_z in tqdm(range(tiles_z), desc="COP30 height tile rows"):
                for tile_x in range(tiles_x):
                    padded = _sample_tile_with_border(
                        rasters=rasters,
                        tile_x=tile_x,
                        tile_z=tile_z,
                        tile_size=tile_size,
                        x_scale=x_scale,
                        z_scale=z_scale,
                        geo=geo,
                        crs=manifest_crs,
                        resampling=method,
                    )
                    source_valid = np.isfinite(padded)
                    if not source_valid.any():
                        if debug_written is not None:
                            debug_written.write_tile(tile_x, tile_z, np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2"))
                        if debug_target_mask is not None:
                            debug_target_mask.write_tile(tile_x, tile_z, np.zeros((tile_size, tile_size), dtype=np.uint8))
                        if debug_land_mask is not None:
                            debug_land_mask.write_tile(tile_x, tile_z, np.zeros((tile_size, tile_size), dtype=np.uint8))
                        continue

                    xs, ys = _tile_cell_centres(tile_x, tile_z, tile_size, x_scale, z_scale, geo, border=1)
                    lon, lat = Transformer.from_crs(manifest_crs, "EPSG:4326", always_xy=True).transform(xs[None, :], ys[:, None])
                    target_mask = is_in_cop30_target(lon, lat, target)
                    land_mask = is_in_cop30_land_mask(lon, lat, target)
                    protected = mainland_gb_protection_mask_lonlat(lon, lat) if protect_mainland_gb else np.zeros_like(target_mask, dtype=bool)
                    # The target mask is intentionally a broad envelope where
                    # COP30 may be considered. The land mask is the tighter
                    # coastline approximation that decides which cells may be
                    # written. This prevents 0m COP30 sea pixels from becoming
                    # valid flat land around Ireland/IOM.
                    # The hand-drawn land masks are only spatial filters. Do
                    # not let sea-level or below-sea-level COP30 samples become
                    # valid terrain, otherwise they show up as broad Y=64
                    # shelves around Ireland/IoM where the target mask is
                    # intentionally conservative.
                    source_land = padded > COP30_MIN_LAND_METRES
                    usable = source_valid & source_land & target_mask & land_mask & ~protected
                    center_usable = usable[1:-1, 1:-1]
                    center_source_valid = source_valid[1:-1, 1:-1]
                    center_source_land = source_land[1:-1, 1:-1]
                    center_target = target_mask[1:-1, 1:-1]
                    center_land = land_mask[1:-1, 1:-1]
                    center_protected = protected[1:-1, 1:-1]
                    center_xs = xs[1:-1]
                    center_ys = ys[1:-1]
                    stats.target_cells_considered += int((center_source_valid & center_target & ~center_protected).sum())
                    stats.land_mask_cells += int((center_source_valid & center_target & center_land & center_source_land & ~center_protected).sum())
                    stats.ocean_cells_skipped += int((center_source_valid & center_target & (~center_land | ~center_source_land) & ~center_protected).sum())
                    _update_bounds(stats, "target", center_source_valid & center_target & ~center_protected, center_xs, center_ys)
                    _update_bounds(stats, "land", center_source_valid & center_target & center_land & center_source_land & ~center_protected, center_xs, center_ys)
                    stats.cells_skipped_nodata += int((~center_source_valid & center_target & center_land & ~center_protected).sum())
                    stats.cells_skipped_outside_target += int((center_source_valid & ~center_target).sum())
                    stats.cells_skipped_mainland_gb += int((center_source_valid & center_target & center_protected).sum())
                    if not center_usable.any():
                        if debug_written is not None:
                            debug_written.write_tile(tile_x, tile_z, np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2"))
                        if debug_target_mask is not None:
                            debug_target_mask.write_tile(tile_x, tile_z, center_target.astype(np.uint8))
                        if debug_land_mask is not None:
                            debug_land_mask.write_tile(tile_x, tile_z, (center_target & center_land & ~center_protected).astype(np.uint8))
                        continue

                    overlay_values = np.where(np.isfinite(padded), padded, 0.0)
                    overlay_dm = np.rint(overlay_values * 10.0).clip(-32767, 32767).astype("<i2")
                    if height_offset_decimetres:
                        can_offset = usable & (overlay_dm != HEIGHT_NODATA)
                        adjusted = overlay_dm.astype(np.int32) + height_offset_decimetres
                        overlay_dm = np.where(can_offset, np.clip(adjusted, -32767, 32767), overlay_dm).astype("<i2")
                    overlay_dm[~usable] = HEIGHT_NODATA
                    if smoothing != "none" or deterrace:
                        overlay_dm = process_height_mosaic(overlay_dm, smoothing=smoothing, deterrace=deterrace, strip_rows=tile_size)
                        overlay_dm[~usable] = HEIGHT_NODATA

                    overlay = overlay_dm[1:-1, 1:-1]
                    valid = center_usable & (overlay != HEIGHT_NODATA)
                    if not valid.any():
                        if debug_written is not None:
                            debug_written.write_tile(tile_x, tile_z, np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2"))
                        if debug_target_mask is not None:
                            debug_target_mask.write_tile(tile_x, tile_z, center_target.astype(np.uint8))
                        if debug_land_mask is not None:
                            debug_land_mask.write_tile(tile_x, tile_z, (center_target & center_land & ~center_protected).astype(np.uint8))
                        continue

                    path = height_root / f"{tile_x:03d}_{tile_z:03d}{manifest['height'].get('extension', r16_extension())}"
                    existing = read_r16_tile(path, tile_size).copy() if path.exists() else np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2")
                    overlay = _blend_to_existing_edges(overlay, valid, existing)
                    write_mask = valid & (overlay != HEIGHT_NODATA)
                    existing[write_mask] = overlay[write_mask]
                    write_r16_tile(path, existing)
                    stats.cells_written += int(write_mask.sum())
                    stats.tiles_touched += 1
                    _update_bounds(stats, "written", write_mask, center_xs, center_ys)
                    if debug_written is not None:
                        debug_tile = np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2")
                        debug_tile[write_mask] = overlay[write_mask]
                        debug_written.write_tile(tile_x, tile_z, debug_tile)
                    if debug_target_mask is not None:
                        debug_target_mask.write_tile(tile_x, tile_z, center_target.astype(np.uint8))
                    if debug_land_mask is not None:
                        debug_land_mask.write_tile(tile_x, tile_z, (center_target & center_land & ~center_protected).astype(np.uint8))
        finally:
            if debug_written is not None:
                debug_written.close()
            if debug_target_mask is not None:
                debug_target_mask.close()
            if debug_land_mask is not None:
                debug_land_mask.close()

    if stats.cells_written == 0 and not allow_empty:
        raise ValueError(
            "COP30 overlay wrote zero cells. Check the manifest extent, --target, and --protect-mainland-gb; "
            "use --allow-empty only for deliberate dry/debug runs."
        )

    overlays = manifest.setdefault("height_overlays", [])
    overlays.append(
        {
            "source": "Copernicus DEM COP30 GeoTIFF",
            "archive": cop30_archive.name,
            "target": target,
            "resampling": resampling,
            "smoothing": smoothing,
            "deterrace": deterrace,
            "protect_mainland_gb": protect_mainland_gb,
            "minecraft_y_offset": minecraft_y_offset,
            "minecraft_height_scale": height_scale,
            "height_offset_decimetres": height_offset_decimetres,
        }
    )
    write_manifest(manifest_path, manifest)

    console.print(f"COP30 source rasters: {stats.source_rasters}")
    console.print(f"COP30 target cells considered: {stats.target_cells_considered:,}")
    console.print(f"COP30 land-mask cells: {stats.land_mask_cells:,}")
    console.print(f"COP30 ocean/sea cells skipped: {stats.ocean_cells_skipped:,}")
    console.print(f"Cells written: {stats.cells_written:,}")
    console.print(f"COP30 cells skipped due nodata: {stats.cells_skipped_nodata:,}")
    console.print(f"Cells skipped outside target mask: {stats.cells_skipped_outside_target:,}")
    console.print(f"COP30 cells skipped due mainland GB protection: {stats.cells_skipped_mainland_gb:,}")
    console.print(f"Tiles touched: {stats.tiles_touched}")
    console.print(f"Height offset: {minecraft_y_offset:g} Minecraft blocks / {height_offset_decimetres:+d} decimetres (height scale {height_scale:g})")
    console.print(f"Target bounds in BNG: {_format_bounds(stats, 'target')}")
    console.print(f"Land-mask bounds in BNG: {_format_bounds(stats, 'land')}")
    console.print(f"Actual written bounds in BNG: {_format_bounds(stats, 'written')}")
    console.print(f"Source CRS summary: {source_crs_summary}")


def is_in_cop30_target(lon: np.ndarray, lat: np.ndarray, target: str = "ireland-iom") -> np.ndarray:
    """Broad envelope where COP30 may be considered for a target mode.

    This is deliberately more generous than the land-write mask. It should not
    be used as a coastline or as proof that a cell is land.
    """
    target = _normalise_choice(target, TARGET_MODES, "target")
    if target == "all-cop30":
        return np.ones(np.broadcast_shapes(np.shape(lon), np.shape(lat)), dtype=bool)
    polygons = []
    if target in {"ireland-iom", "ireland-only"}:
        polygons.append(_ireland_polygon())
    if target in {"ireland-iom", "iom-only"}:
        polygons.append(_iom_polygon())
    mask = contains_xy(MultiPolygon(polygons), lon, lat)
    if target in {"ireland-iom", "ireland-only"}:
        mask |= _ireland_extension_mask_lonlat(lon, lat)
    return mask


def is_in_cop30_land_mask(lon: np.ndarray, lat: np.ndarray, target: str = "ireland-iom") -> np.ndarray:
    """Approximate Ireland/IOM land where COP30 heights are allowed to write.

    The target envelope catches the right source raster area; this tighter mask
    prevents sea pixels around Ireland and the Isle of Man from becoming valid
    flat terrain in height tiles.
    """
    target = _normalise_choice(target, TARGET_MODES, "target")
    if target == "all-cop30":
        return np.ones(np.broadcast_shapes(np.shape(lon), np.shape(lat)), dtype=bool)
    polygons = []
    if target in {"ireland-iom", "ireland-only"}:
        polygons.append(_ireland_land_polygon())
    if target in {"ireland-iom", "iom-only"}:
        polygons.append(_iom_land_polygon())
    mask = contains_xy(MultiPolygon(polygons), lon, lat)
    if target in {"ireland-iom", "ireland-only"}:
        mask |= _ireland_extension_mask_lonlat(lon, lat)
    return mask


def target_mask_lonlat(lon: np.ndarray, lat: np.ndarray, target: str = "ireland-iom") -> np.ndarray:
    return is_in_cop30_target(lon, lat, target)


def cop30_land_mask_lonlat(lon: np.ndarray, lat: np.ndarray, target: str = "ireland-iom") -> np.ndarray:
    return is_in_cop30_land_mask(lon, lat, target)


def mainland_gb_protection_mask_lonlat(lon: np.ndarray, lat: np.ndarray) -> np.ndarray:
    # The broad GB protection outline necessarily crosses parts of the Irish
    # Sea. Subtract the Isle of Man target explicitly so COP30 can write there.
    return contains_xy(_mainland_gb_protection_polygon(), lon, lat) & ~contains_xy(_iom_polygon(), lon, lat)


def _ireland_polygon() -> Polygon:
    # Conservative WGS84 outline of the island of Ireland. It intentionally hugs
    # the Irish landmass and nearby Irish islands instead of using a broad box,
    # so western Scotland, Wales, and Anglesey remain outside the COP30 target.
    return Polygon(
        [
            (-10.75, 51.35),
            (-10.25, 51.34),
            (-9.65, 51.47),
            (-8.80, 51.42),
            (-8.05, 51.48),
            (-7.25, 51.68),
            (-6.55, 52.02),
            (-5.86, 52.55),
            (-5.58, 53.25),
            (-5.43, 54.05),
            (-5.45, 54.55),
            (-5.78, 54.95),
            (-6.25, 55.26),
            (-7.05, 55.50),
            (-7.75, 55.50),
            (-8.20, 55.35),
            (-9.20, 54.95),
            (-10.08, 54.35),
            (-10.35, 53.45),
            (-10.18, 52.72),
            (-10.75, 51.35),
        ]
    )


def _ireland_land_polygon() -> Polygon:
    # Rough clockwise coastline approximation for the island of Ireland. It is
    # intentionally tighter than the target envelope so COP30 0m sea pixels in
    # the Irish Sea and Atlantic are skipped instead of written as flat land.
    return Polygon(
        [
            (-10.55, 51.42),
            (-10.05, 51.54),
            (-9.55, 51.42),
            (-8.80, 51.50),
            (-8.20, 51.44),
            (-7.55, 51.72),
            (-7.05, 52.05),
            (-6.35, 52.16),
            (-6.08, 52.62),
            (-6.00, 53.10),
            (-6.02, 53.55),
            (-6.22, 53.92),
            (-6.05, 54.18),
            (-5.78, 54.52),
            (-5.62, 54.60),
            (-5.58, 54.72),
            (-5.72, 54.96),
            (-6.15, 55.20),
            (-6.85, 55.35),
            (-7.65, 55.38),
            (-8.35, 55.15),
            (-9.15, 54.86),
            (-9.85, 54.25),
            (-10.22, 53.55),
            (-10.05, 52.92),
            (-10.42, 52.18),
            (-10.55, 51.42),
        ]
    )


def _iom_polygon() -> Polygon:
    return Polygon(
        [
            (-4.92, 54.02),
            (-4.72, 53.95),
            (-4.38, 53.98),
            (-4.18, 54.15),
            (-4.26, 54.38),
            (-4.55, 54.48),
            (-4.82, 54.34),
            (-4.92, 54.02),
        ]
    )


def _iom_land_polygon() -> Polygon:
    return Polygon(
        [
            (-4.86, 54.03),
            (-4.70, 53.98),
            (-4.45, 54.02),
            (-4.31, 54.14),
            (-4.31, 54.31),
            (-4.50, 54.42),
            (-4.72, 54.33),
            (-4.84, 54.14),
            (-4.86, 54.03),
        ]
    )


def _mainland_gb_protection_polygon() -> MultiPolygon:
    mainland = Polygon(
        [
            (-6.45, 50.00),
            (-5.50, 49.86),
            (-4.05, 50.05),
            (-2.35, 50.45),
            (0.95, 50.72),
            (1.82, 51.35),
            (1.70, 52.55),
            (1.05, 53.35),
            (0.10, 54.05),
            (-0.95, 54.95),
            (-1.62, 55.62),
            (-2.05, 56.55),
            (-2.15, 57.65),
            (-3.05, 58.65),
            (-4.48, 58.75),
            (-5.72, 58.15),
            (-6.32, 57.30),
            (-6.15, 56.25),
            (-5.45, 55.40),
            (-4.72, 54.90),
            (-4.75, 54.32),
            (-4.98, 53.62),
            (-5.38, 52.82),
            (-5.72, 51.78),
            (-6.45, 50.00),
        ]
    )
    anglesey = Polygon([(-4.78, 53.05), (-4.00, 53.05), (-4.00, 53.48), (-4.78, 53.48), (-4.78, 53.05)])
    return MultiPolygon([mainland, anglesey])


def _ireland_extension_mask_lonlat(lon: np.ndarray, lat: np.ndarray) -> np.ndarray:
    easting, northing = WGS84_TO_BNG.transform(lon, lat)
    mask = np.zeros(np.broadcast_shapes(np.shape(lon), np.shape(lat)), dtype=bool)
    for center_easting, center_northing, radius_metres in IRELAND_MASK_EXTENSION_CIRCLES_BNG:
        mask |= (easting - center_easting) ** 2 + (northing - center_northing) ** 2 <= radius_metres**2
    return mask


def _extract_geotiffs(archive_path: Path, tmp: Path) -> list[Cop30Raster]:
    rasters: list[Cop30Raster] = []
    with tarfile.open(archive_path, "r:gz") as archive:
        members = [m for m in archive.getmembers() if m.isfile() and m.name.lower().endswith((".tif", ".tiff"))]
        for index, member in enumerate(members):
            src = archive.extractfile(member)
            if src is None:
                continue
            suffix = ".tiff" if member.name.lower().endswith(".tiff") else ".tif"
            path = tmp / f"cop30_{index:04d}{suffix}"
            with path.open("wb") as dst:
                shutil.copyfileobj(src, dst, length=1024 * 1024)
            with rasterio.open(path) as ds:
                if ds.crs is None:
                    raise ValueError(f"{member.name} has no CRS")
                rasters.append(Cop30Raster(name=member.name, path=path, crs=str(ds.crs), bounds=tuple(ds.bounds)))
    return rasters


def _sample_tile_with_border(
    *,
    rasters: list[Cop30Raster],
    tile_x: int,
    tile_z: int,
    tile_size: int,
    x_scale: float,
    z_scale: float,
    geo: dict,
    crs: str,
    resampling: Resampling,
) -> np.ndarray:
    dst = np.full((tile_size + 2, tile_size + 2), np.nan, dtype=np.float32)
    dst_transform = Affine(
        x_scale,
        0.0,
        float(geo["bng_min_easting"]) + (tile_x * tile_size - 1) * x_scale,
        0.0,
        -z_scale,
        float(geo["bng_max_northing"]) - (tile_z * tile_size - 1) * z_scale,
    )
    dst_bounds = _bounds_from_transform(dst_transform, dst.shape[1], dst.shape[0])
    for item in rasters:
        with rasterio.open(item.path) as src:
            src_bounds = transform_bounds(src.crs, crs, *src.bounds, densify_pts=21)
            if not _bounds_overlap(dst_bounds, src_bounds):
                continue
            temp = np.full_like(dst, np.nan)
            reproject(
                source=rasterio.band(src, 1),
                destination=temp,
                src_crs=src.crs,
                src_transform=src.transform,
                src_nodata=src.nodata,
                dst_crs=crs,
                dst_transform=dst_transform,
                dst_nodata=np.nan,
                resampling=resampling,
                num_threads=2,
            )
            scale = src.scales[0] if src.scales else 1.0
            offset = src.offsets[0] if src.offsets else 0.0
            if scale != 1.0 or offset != 0.0:
                temp = temp * float(scale) + float(offset)
            valid = np.isfinite(temp)
            dst[valid] = temp[valid]
    return dst


def _tile_cell_centres(
    tile_x: int,
    tile_z: int,
    tile_size: int,
    x_scale: float,
    z_scale: float,
    geo: dict,
    *,
    border: int = 0,
) -> tuple[np.ndarray, np.ndarray]:
    cols = np.arange(tile_x * tile_size - border, tile_x * tile_size + tile_size + border, dtype=np.float64)
    rows = np.arange(tile_z * tile_size - border, tile_z * tile_size + tile_size + border, dtype=np.float64)
    xs = float(geo["bng_min_easting"]) + (cols + 0.5) * x_scale
    ys = float(geo["bng_max_northing"]) - (rows + 0.5) * z_scale
    return xs, ys


def _blend_to_existing_edges(overlay: np.ndarray, valid: np.ndarray, existing: np.ndarray, radius: int = 4) -> np.ndarray:
    result = overlay.copy()
    remaining = valid.copy()
    frontier = _edge_cells(valid)
    for distance in range(radius):
        layer = remaining & frontier
        if not layer.any():
            break
        neighbour_average, neighbour_valid = _existing_neighbour_average(existing)
        blendable = layer & neighbour_valid
        if blendable.any():
            weight = (radius - distance) / (radius + 1.0)
            blended = result.astype(np.float32) * (1.0 - weight) + neighbour_average * weight
            result[blendable] = np.rint(blended[blendable]).clip(-32767, 32767).astype("<i2")
        remaining[layer] = False
        frontier = _adjacent_to(layer) & remaining
    return result


def _edge_cells(mask: np.ndarray) -> np.ndarray:
    padded = np.pad(mask, 1, constant_values=False)
    neighbours = (
        padded[:-2, 1:-1]
        & padded[2:, 1:-1]
        & padded[1:-1, :-2]
        & padded[1:-1, 2:]
    )
    return mask & ~neighbours


def _adjacent_to(mask: np.ndarray) -> np.ndarray:
    padded = np.pad(mask, 1, constant_values=False)
    return padded[:-2, 1:-1] | padded[2:, 1:-1] | padded[1:-1, :-2] | padded[1:-1, 2:]


def _existing_neighbour_average(existing: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    total = np.zeros(existing.shape, dtype=np.float32)
    count = np.zeros(existing.shape, dtype=np.float32)
    valid_existing = existing != HEIGHT_NODATA
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            shifted = np.full(existing.shape, HEIGHT_NODATA, dtype=existing.dtype)
            src_z0 = max(0, -dz)
            src_z1 = existing.shape[0] - max(0, dz)
            dst_z0 = max(0, dz)
            dst_z1 = existing.shape[0] - max(0, -dz)
            src_x0 = max(0, -dx)
            src_x1 = existing.shape[1] - max(0, dx)
            dst_x0 = max(0, dx)
            dst_x1 = existing.shape[1] - max(0, -dx)
            shifted[dst_z0:dst_z1, dst_x0:dst_x1] = existing[src_z0:src_z1, src_x0:src_x1]
            valid = shifted != HEIGHT_NODATA
            total += np.where(valid, shifted.astype(np.float32), 0.0)
            count += valid.astype(np.float32)
    return total / np.maximum(count, 1.0), count > 0


def _resampling(value: str) -> Resampling:
    if value == "nearest":
        return Resampling.nearest
    if value == "bilinear":
        return Resampling.bilinear
    raise ValueError("--resampling must be nearest or bilinear")


def _minecraft_height_scale(manifest: dict) -> float:
    world = manifest.get("world", {})
    height = manifest.get("height", {})
    generator = manifest.get("generator") or manifest.get("worldgen") or manifest.get("ukgeo_generator") or {}
    model = manifest.get("minecraft_height") or manifest.get("height_model") or world.get("height_model") or height.get("minecraft") or generator.get("height") or {}
    for source in (model, world, height, generator, manifest):
        for key in ("height_scale", "heightScale"):
            value = source.get(key) if isinstance(source, dict) else None
            if value is not None:
                scale = float(value)
                if scale <= 0.0:
                    raise ValueError(f"height scale must be positive, got {scale:g}")
                return scale
    return DEFAULT_MINECRAFT_HEIGHT_SCALE


def _update_bounds(stats: OverlayStats, prefix: str, mask: np.ndarray, xs: np.ndarray, ys: np.ndarray) -> None:
    if not mask.any():
        return
    rows, cols = np.nonzero(mask)
    min_x = float(xs[cols].min())
    max_x = float(xs[cols].max())
    min_y = float(ys[rows].min())
    max_y = float(ys[rows].max())
    current_min_x = getattr(stats, f"{prefix}_min_x")
    setattr(stats, f"{prefix}_min_x", min_x if current_min_x is None else min(current_min_x, min_x))
    current_min_y = getattr(stats, f"{prefix}_min_y")
    setattr(stats, f"{prefix}_min_y", min_y if current_min_y is None else min(current_min_y, min_y))
    current_max_x = getattr(stats, f"{prefix}_max_x")
    setattr(stats, f"{prefix}_max_x", max_x if current_max_x is None else max(current_max_x, max_x))
    current_max_y = getattr(stats, f"{prefix}_max_y")
    setattr(stats, f"{prefix}_max_y", max_y if current_max_y is None else max(current_max_y, max_y))


def _format_bounds(stats: OverlayStats, prefix: str) -> str:
    min_x = getattr(stats, f"{prefix}_min_x")
    min_y = getattr(stats, f"{prefix}_min_y")
    max_x = getattr(stats, f"{prefix}_max_x")
    max_y = getattr(stats, f"{prefix}_max_y")
    if min_x is None or min_y is None or max_x is None or max_y is None:
        return "none"
    return f"E {min_x:.0f}..{max_x:.0f}, N {min_y:.0f}..{max_y:.0f}"


def _bounds_from_transform(transform: Affine, width: int, height: int) -> tuple[float, float, float, float]:
    left = transform.c
    top = transform.f
    right = left + width * transform.a
    bottom = top + height * transform.e
    return min(left, right), min(bottom, top), max(left, right), max(bottom, top)


def _bounds_overlap(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> bool:
    return a[0] < b[2] and a[2] > b[0] and a[1] < b[3] and a[3] > b[1]


def _warn_if_source_extends_outside_manifest(rasters: list[Cop30Raster], geo: dict, crs: str) -> None:
    transformed = []
    for item in rasters:
        with rasterio.open(item.path) as src:
            transformed.append(transform_bounds(src.crs, crs, *src.bounds, densify_pts=21))
    min_x = min(b[0] for b in transformed)
    min_y = min(b[1] for b in transformed)
    max_x = max(b[2] for b in transformed)
    max_y = max(b[3] for b in transformed)
    outside = (
        min_x < float(geo["bng_min_easting"])
        or min_y < float(geo["bng_min_northing"])
        or max_x > float(geo["bng_max_easting"])
        or max_y > float(geo["bng_max_northing"])
    )
    if outside:
        console.print(
            "[yellow]Warning: COP30 source bounds extend outside the manifest extent; "
            "cells outside the manifest cannot be written.[/yellow]"
        )
        console.print(
            f"[yellow]COP30 BNG bounds E {min_x:.0f}..{max_x:.0f}, N {min_y:.0f}..{max_y:.0f}; "
            f"manifest E {geo['bng_min_easting']}..{geo['bng_max_easting']}, "
            f"N {geo['bng_min_northing']}..{geo['bng_max_northing']}[/yellow]"
        )


def _source_crs_summary(rasters: list[Cop30Raster]) -> str:
    counts: dict[str, int] = {}
    for item in rasters:
        counts[item.crs] = counts.get(item.crs, 0) + 1
    return ", ".join(f"{crs}: {count}" for crs, count in sorted(counts.items()))


class _DebugWriter:
    def __init__(self, path: Path, manifest: dict, *, dtype: str, nodata: int) -> None:
        geo = manifest["georeferencing"]
        world = manifest["world"]
        self.width = int(world["width"])
        self.depth = int(world["depth"])
        self.tile_size = int(manifest["tile_size"])
        path.parent.mkdir(parents=True, exist_ok=True)
        self.dataset = rasterio.open(
            path,
            "w",
            driver="GTiff",
            height=self.depth,
            width=self.width,
            count=1,
            dtype=dtype,
            crs=geo.get("crs", DEST_CRS),
            transform=from_bounds(
                geo["bng_min_easting"],
                geo["bng_min_northing"],
                geo["bng_max_easting"],
                geo["bng_max_northing"],
                self.width,
                self.depth,
            ),
            nodata=nodata,
            tiled=True,
            compress="deflate",
        )

    def write_tile(self, tile_x: int, tile_z: int, tile: np.ndarray) -> None:
        x0 = tile_x * self.tile_size
        y0 = tile_z * self.tile_size
        width = min(self.tile_size, self.width - x0)
        height = min(self.tile_size, self.depth - y0)
        if width <= 0 or height <= 0:
            return
        self.dataset.write(tile[:height, :width], 1, window=Window(x0, y0, width, height))

    def close(self) -> None:
        self.dataset.close()
