from __future__ import annotations

from pathlib import Path
import math

import fiona
import typer
from rich.console import Console
from rich.table import Table

from .asc import iter_nested_asc_headers
from .animal_habitats import make_animal_habitat_tiles as make_animal_habitat_tiles_impl
from .bgs import likely_geology_fields, resolve_gpkg
from .coal import make_coal_resource_tiles as make_coal_resource_tiles_impl
from .clc import make_clc_wms_vegetation_tiles as make_clc_wms_vegetation_tiles_impl
from .coords import (
    DEFAULT_MINECRAFT_MIN_X,
    DEFAULT_MINECRAFT_MIN_Z,
    WorldBounds,
    minecraft_to_layer_cell,
    minecraft_to_tile_cell,
)
from .cop30_height import add_cop30_height_tiles as add_cop30_height_tiles_impl
from .height import make_height_tiles as make_height_tiles_impl
from .gold import harvest_gold_occurrences as harvest_gold_occurrences_impl
from .gold import make_gold_occurrence_tiles as make_gold_occurrence_tiles_impl
from .egdi import add_egdi_surface_geology_tiles as add_egdi_surface_geology_tiles_impl
from .landmask import mask_height_to_bgs_land as mask_height_to_bgs_land_impl
from .manifest import read_manifest, write_manifest
from .ore_image_overlay import apply_named_svg_ore_overlays as apply_named_svg_ore_overlays_impl
from .ore_image_overlay import apply_ore_image_overlay as apply_ore_image_overlay_impl
from .ores import make_ore_tiles as make_ore_tiles_impl
from .preview import make_preview
from .rivers import make_river_tiles as make_river_tiles_impl
from .surface import make_surface_geology_tiles as make_surface_geology_tiles_impl
from .ni_height import add_osni_height_tiles as add_osni_height_tiles_impl
from .vegetation import make_vegetation_tiles as make_vegetation_tiles_impl
from .tiles import HEIGHT_NODATA, pack_manifest_regions, read_layer_tile, river_u8_layer
from .validate import tile_summary, validate_tiles

app = typer.Typer(no_args_is_help=True)
console = Console()


@app.command("inspect-os")
def inspect_os(path: Path) -> None:
    headers = list(iter_nested_asc_headers(str(path)))
    if not headers:
        raise typer.BadParameter("No ASC files found")
    xs = [h.xllcorner for _, h in headers]
    ys = [h.yllcorner for _, h in headers]
    table = Table("metric", "value")
    table.add_row("ASC tile count", str(len(headers)))
    table.add_row("xllcorner min/max", f"{min(xs)} / {max(xs)}")
    table.add_row("yllcorner min/max", f"{min(ys)} / {max(ys)}")
    table.add_row("ncols values", str(sorted({h.ncols for _, h in headers})))
    table.add_row("nrows values", str(sorted({h.nrows for _, h in headers})))
    table.add_row("cellsize values", str(sorted({h.cellsize for _, h in headers})))
    table.add_row("sample files", "\n".join(name for name, _ in headers[:10]))
    console.print(table)


@app.command("inspect-bgs")
def inspect_bgs(path: Path) -> None:
    gpkg, tmp = resolve_gpkg(path)
    try:
        layers = fiona.listlayers(gpkg)
        table = Table("layer", "crs", "features", "geometry", "likely fields")
        import geopandas as gpd

        for layer in layers:
            frame = gpd.read_file(gpkg, layer=layer, rows=1)
            with fiona.open(gpkg, layer=layer) as src:
                count = len(src)
                geom = src.schema.get("geometry", "?")
                crs = src.crs_wkt or str(src.crs)
                fields = likely_geology_fields(list(src.schema.get("properties", {}).keys()))
            table.add_row(layer, crs[:40], str(count), geom, ", ".join(fields[:12]))
        console.print(table)
    finally:
        if tmp is not None:
            tmp.cleanup()


@app.command("make-height-tiles")
def make_height_tiles(
    os_zip: Path = typer.Option(..., "--os-zip"),
    out: Path = typer.Option(..., "--out"),
    bng_min_easting: float = typer.Option(..., "--bng-min-easting"),
    bng_min_northing: float = typer.Option(..., "--bng-min-northing"),
    bng_max_easting: float = typer.Option(..., "--bng-max-easting"),
    bng_max_northing: float = typer.Option(..., "--bng-max-northing"),
    world_width: int = typer.Option(25000, "--world-width"),
    world_depth: int = typer.Option(50000, "--world-depth"),
    tile_size: int = typer.Option(512, "--tile-size"),
    minecraft_min_x: int = typer.Option(DEFAULT_MINECRAFT_MIN_X, "--minecraft-min-x"),
    minecraft_min_z: int = typer.Option(DEFAULT_MINECRAFT_MIN_Z, "--minecraft-min-z"),
    sea_level_y: int = typer.Option(64, "--sea-level-y"),
    axis_scale_x: float = typer.Option(1.0, "--axis-scale-x"),
    axis_scale_z: float = typer.Option(1.0, "--axis-scale-z"),
    height_resampling: str = typer.Option("nearest", "--height-resampling", help="nearest or bilinear"),
    height_smoothing: str = typer.Option("none", "--height-smoothing", help="none, light, or medium"),
    height_deterrace: bool = typer.Option(False, "--height-deterrace/--no-height-deterrace"),
    height_jobs: int = typer.Option(1, "--height-jobs", min=1),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff", "--height-debug-geotiff"),
) -> None:
    make_height_tiles_impl(
        os_zip=os_zip,
        out=out,
        bng_min_easting=bng_min_easting,
        bng_min_northing=bng_min_northing,
        bng_max_easting=bng_max_easting,
        bng_max_northing=bng_max_northing,
        world_width=world_width,
        world_depth=world_depth,
        tile_size=tile_size,
        minecraft_min_x=minecraft_min_x,
        minecraft_min_z=minecraft_min_z,
        sea_level_y=sea_level_y,
        axis_scale_x=axis_scale_x,
        axis_scale_z=axis_scale_z,
        height_resampling=height_resampling,
        height_smoothing=height_smoothing,
        height_deterrace=height_deterrace,
        height_jobs=height_jobs,
        debug_geotiff=debug_geotiff,
    )


@app.command("make-ore-tiles")
def make_ore_tiles(
    bgs: Path = typer.Option(..., "--bgs"),
    rules: Path = typer.Option(..., "--rules"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    debug_geotiff_dir: Path | None = typer.Option(None, "--debug-geotiff-dir"),
    jobs: int = typer.Option(1, "--jobs", help="Ore/mineral layers to process in parallel."),
    only_ore: list[str] | None = typer.Option(None, "--only-ore", help="Generate only the named ore layer. Repeat for multiple ores."),
) -> None:
    make_ore_tiles_impl(bgs=bgs, rules=rules, manifest_path=manifest, out=out, debug_geotiff_dir=debug_geotiff_dir, jobs=jobs, only_ores=only_ore)


@app.command("apply-ore-image-overlay")
def apply_ore_image_overlay(
    image: Path = typer.Option(..., "--image"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    ore: str = typer.Option("iron", "--ore"),
    score: int = typer.Option(180, "--score"),
    red_min: int = typer.Option(180, "--red-min"),
    green_max: int = typer.Option(120, "--green-max"),
    blue_max: int = typer.Option(120, "--blue-max"),
    fit: str = typer.Option("outline", "--fit", help="Placement: outline, cover, or contain."),
) -> None:
    apply_ore_image_overlay_impl(
        image=image,
        manifest_path=manifest,
        out=out,
        ore=ore,
        score=score,
        red_min=red_min,
        green_max=green_max,
        blue_max=blue_max,
        fit=fit,
    )


@app.command("apply-named-svg-ore-overlays")
def apply_named_svg_ore_overlays(
    image: Path = typer.Option(..., "--image"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    overlay: list[str] = typer.Option(..., "--overlay", help="Repeat as ore=SvgPathId:score, for example --overlay copper=Copper:180"),
    fit: str = typer.Option("full-frame", "--fit", help="Placement: full-frame, ireland-reference, outline, cover, or contain."),
    svg_raster_scale: int = typer.Option(1, "--svg-raster-scale"),
) -> None:
    overlays: dict[str, tuple[str, int]] = {}
    for item in overlay:
        ore, sep, rest = item.partition("=")
        if not ore or not sep:
            raise typer.BadParameter(f"Invalid overlay mapping {item!r}; expected ore=SvgPathId:score")
        path_id, score_sep, raw_score = rest.rpartition(":")
        if not path_id or not score_sep:
            raise typer.BadParameter(f"Invalid overlay mapping {item!r}; expected ore=SvgPathId:score")
        overlays[ore.strip()] = (path_id.strip(), int(raw_score))
    apply_named_svg_ore_overlays_impl(
        image=image,
        manifest_path=manifest,
        out=out,
        overlays=overlays,
        fit=fit,
        svg_raster_scale=svg_raster_scale,
    )


@app.command("make-animal-habitat-tiles")
def make_animal_habitat_tiles(
    image: Path = typer.Option(..., "--image"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    fit: str = typer.Option("full-frame", "--fit", help="Placement: full-frame, outline, cover, or contain."),
    svg_raster_scale: int = typer.Option(1, "--svg-raster-scale"),
) -> None:
    make_animal_habitat_tiles_impl(
        image=image,
        manifest_path=manifest,
        out=out,
        fit=fit,
        svg_raster_scale=svg_raster_scale,
    )


@app.command("make-coal-resource-tiles")
def make_coal_resource_tiles(
    coal_resources: Path = typer.Option(..., "--coal-resources"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
) -> None:
    make_coal_resource_tiles_impl(coal_resources=coal_resources, manifest_path=manifest, out=out, debug_geotiff=debug_geotiff)


@app.command("harvest-gold-occurrences")
def harvest_gold_occurrences(
    out: Path = typer.Option(..., "--out"),
    tile_metres: float = typer.Option(50_000.0, "--tile-metres"),
    pixel_metres: float = typer.Option(100.0, "--pixel-metres"),
    request_pause: float = typer.Option(0.02, "--request-pause"),
    limit_tiles: int | None = typer.Option(None, "--limit-tiles"),
) -> None:
    harvest_gold_occurrences_impl(
        out=out,
        tile_metres=tile_metres,
        pixel_metres=pixel_metres,
        request_pause=request_pause,
        limit_tiles=limit_tiles,
    )


@app.command("make-gold-occurrence-tiles")
def make_gold_occurrence_tiles(
    gold_occurrences: Path = typer.Option(..., "--gold-occurrences"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    radius_metres: float = typer.Option(4500.0, "--radius-metres"),
    core_metres: float = typer.Option(900.0, "--core-metres"),
    merge_existing: bool = typer.Option(True, "--merge-existing/--replace", help="Max-merge occurrence scores with existing gold tiles."),
) -> None:
    make_gold_occurrence_tiles_impl(
        gold_occurrences=gold_occurrences,
        manifest_path=manifest,
        out=out,
        radius_metres=radius_metres,
        core_metres=core_metres,
        merge_existing=merge_existing,
    )


@app.command("make-surface-geology-tiles")
def make_surface_geology_tiles(
    bgs: Path = typer.Option(..., "--bgs"),
    rules: Path = typer.Option(..., "--rules"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
) -> None:
    make_surface_geology_tiles_impl(bgs=bgs, rules=rules, manifest_path=manifest, out=out, debug_geotiff=debug_geotiff)


@app.command("add-egdi-surface-geology-tiles")
def add_egdi_surface_geology_tiles(
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    cache_dir: Path = typer.Option(..., "--cache-dir"),
    feature_type: list[str] = typer.Option(["ms:geologicunitview"], "--feature-type"),
    fill_only: bool = typer.Option(True, "--fill-only/--replace-nonzero", help="Only fill cells where the existing surface geology class is 0."),
    page_size: int = typer.Option(5000, "--page-size", help="WFS features per paged request."),
    wfs_url: str = typer.Option("https://maps.europe-geology.eu/wfs/", "--wfs-url"),
) -> None:
    add_egdi_surface_geology_tiles_impl(
        manifest_path=manifest,
        out=out,
        cache_dir=cache_dir,
        feature_types=feature_type,
        fill_only=fill_only,
        page_size=page_size,
        wfs_url=wfs_url,
    )


@app.command("add-osni-height-tiles")
def add_osni_height_tiles(
    osni_dtm: Path = typer.Option(..., "--osni-dtm"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    source_crs: str = typer.Option("EPSG:29902", "--source-crs"),
    source_cell_size: float = typer.Option(50.0, "--source-cell-size"),
    resampling: str = typer.Option("bilinear", "--resampling", help="nearest or bilinear"),
) -> None:
    add_osni_height_tiles_impl(
        osni_dtm=osni_dtm,
        manifest_path=manifest,
        out=out,
        source_crs=source_crs,
        source_cell_size=source_cell_size,
        resampling=resampling,
    )


@app.command("add-cop30-height-tiles")
def add_cop30_height_tiles(
    cop30: Path = typer.Option(..., "--cop30"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    resampling: str = typer.Option("bilinear", "--resampling", help="nearest or bilinear"),
    smoothing: str = typer.Option("light", "--smoothing", help="none, light, or medium"),
    height_deterrace: bool = typer.Option(True, "--height-deterrace/--no-height-deterrace"),
    target: str = typer.Option("ireland-iom", "--target", help="ireland-iom, ireland-only, iom-only, or all-cop30"),
    protect_mainland_gb: bool = typer.Option(True, "--protect-mainland-gb/--no-protect-mainland-gb"),
    minecraft_y_offset: float = typer.Option(0.0, "--minecraft-y-offset", help="Optional Minecraft Y blocks to add to COP30 terrain before writing height tiles."),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
    debug_mask_geotiff: Path | None = typer.Option(None, "--debug-mask-geotiff"),
    debug_target_mask_geotiff: Path | None = typer.Option(None, "--debug-target-mask-geotiff"),
    debug_land_mask_geotiff: Path | None = typer.Option(None, "--debug-land-mask-geotiff"),
    debug_written_geotiff: Path | None = typer.Option(None, "--debug-written-geotiff"),
    allow_empty: bool = typer.Option(False, "--allow-empty"),
) -> None:
    add_cop30_height_tiles_impl(
        cop30_archive=cop30,
        manifest_path=manifest,
        out=out,
        resampling=resampling,
        smoothing=smoothing,
        deterrace=height_deterrace,
        target=target,
        protect_mainland_gb=protect_mainland_gb,
        minecraft_y_offset=minecraft_y_offset,
        debug_geotiff=debug_geotiff,
        debug_mask_geotiff=debug_mask_geotiff,
        debug_target_mask_geotiff=debug_target_mask_geotiff,
        debug_land_mask_geotiff=debug_land_mask_geotiff,
        debug_written_geotiff=debug_written_geotiff,
        allow_empty=allow_empty,
    )


@app.command("make-river-tiles")
def make_river_tiles(
    rivers: Path = typer.Option(..., "--rivers"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    layer: str | None = typer.Option(None, "--layer"),
    width_metres: float = typer.Option(30.0, "--width-metres"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
    resume_memmaps: bool = typer.Option(
        False,
        "--resume-memmaps",
        help="Reopen orphaned .rivers-* / .river-* memmaps under --out instead of allocating fresh zeroed rasters.",
    ),
    skip_edges: int = typer.Option(
        0,
        "--skip-edges",
        help="Skip the first N edges when resuming (must be a multiple of the 25000-edge flush batch size).",
    ),
) -> None:
    make_river_tiles_impl(
        rivers=rivers,
        manifest_path=manifest,
        out=out,
        layer=layer,
        width_metres=width_metres,
        debug_geotiff=debug_geotiff,
        resume_memmaps=resume_memmaps,
        skip_edges=skip_edges,
    )


@app.command("make-vegetation-tiles")
def make_vegetation_tiles(
    landcover: Path = typer.Option(..., "--landcover"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    band: int = typer.Option(1, "--band"),
    cell_metres: float = typer.Option(50.0, "--cell-metres", help="Raster cell size in metres (default 50 m)."),
    vegetation_smoothing: str = typer.Option("none", "--vegetation-smoothing", help="none, light, or medium. Freshwater is preserved exactly."),
    generate_biome_regions: bool = typer.Option(True, "--generate-biome-regions/--no-generate-biome-regions", help="Write a coarse biome_regions layer for Minecraft biome selection."),
    biome_region_factor: int = typer.Option(8, "--biome-region-factor", help="Raw vegetation cells per biome region cell."),
    biome_region_smoothing_passes: int = typer.Option(2, "--biome-region-smoothing-passes", help="Conservative boundary smoothing passes for biome regions."),
    biome_region_min_area_cells: int = typer.Option(3, "--biome-region-min-area-cells", help="Tiny biome region components smaller than this are absorbed."),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
    jobs: int = typer.Option(1, "--jobs", help="Vegetation tile rows to process in parallel."),
) -> None:
    make_vegetation_tiles_impl(
        landcover=landcover,
        manifest_path=manifest,
        out=out,
        band=band,
        cell_metres=cell_metres,
        vegetation_smoothing=vegetation_smoothing,
        generate_biome_regions=generate_biome_regions,
        biome_region_factor=biome_region_factor,
        biome_region_smoothing_passes=biome_region_smoothing_passes,
        biome_region_min_area_cells=biome_region_min_area_cells,
        debug_geotiff=debug_geotiff,
        jobs=jobs,
    )


@app.command("make-clc-wms-vegetation-tiles")
def make_clc_wms_vegetation_tiles(
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    cache_dir: Path = typer.Option(..., "--cache-dir"),
    cell_metres: float = typer.Option(50.0, "--cell-metres", help="Vegetation raster cell size in metres."),
    max_request_size: int = typer.Option(4096, "--max-request-size", help="Maximum WMS request width/height in pixels."),
    generate_biome_regions: bool = typer.Option(True, "--generate-biome-regions/--no-generate-biome-regions"),
    biome_region_factor: int = typer.Option(8, "--biome-region-factor"),
    biome_region_smoothing_passes: int = typer.Option(2, "--biome-region-smoothing-passes"),
    biome_region_min_area_cells: int = typer.Option(3, "--biome-region-min-area-cells"),
    wms_url: str = typer.Option(
        "https://image.discomap.eea.europa.eu/arcgis/services/Corine/CLC2018_WM/MapServer/WMSServer",
        "--wms-url",
    ),
    layer: str = typer.Option("12", "--layer"),
) -> None:
    make_clc_wms_vegetation_tiles_impl(
        manifest_path=manifest,
        out=out,
        cache_dir=cache_dir,
        cell_metres=cell_metres,
        max_request_size=max_request_size,
        generate_biome_regions=generate_biome_regions,
        biome_region_factor=biome_region_factor,
        biome_region_smoothing_passes=biome_region_smoothing_passes,
        biome_region_min_area_cells=biome_region_min_area_cells,
        wms_url=wms_url,
        layer=layer,
    )


@app.command("mask-height-to-bgs-land")
def mask_height_to_bgs_land(
    bgs: Path = typer.Option(..., "--bgs"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    layer: list[str] | None = typer.Option(None, "--layer", help="BGS polygon layer to use as land. Repeat for multiple layers."),
    buffer_metres: float = typer.Option(250.0, "--buffer-metres"),
    max_height_metres: float = typer.Option(20.0, "--max-height-metres"),
    preserve_height_overlays: bool = typer.Option(False, "--preserve-height-overlays/--no-preserve-height-overlays"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
) -> None:
    mask_height_to_bgs_land_impl(
        bgs=bgs,
        manifest_path=manifest,
        out=out,
        layers=layer,
        buffer_metres=buffer_metres,
        max_height_metres=max_height_metres,
        preserve_height_overlays=preserve_height_overlays,
        debug_geotiff=debug_geotiff,
    )


@app.command("validate-tiles")
def validate_tiles_cmd(root: Path) -> None:
    errors = validate_tiles(root)
    if errors:
        for error in errors[:100]:
            console.print(f"[red]{error}[/red]")
        raise typer.Exit(1)
    console.print("[green]Tiles validated successfully.[/green]")


@app.command("stats")
def stats_cmd(root: Path) -> None:
    summary = tile_summary(root)
    world = summary["world"]
    geo = summary["georeferencing"]
    height = summary["height"]
    table = Table("metric", "value")
    table.add_row("world blocks", f"{world['width']} x {world['depth']} (padded {world['padded_width']} x {world['padded_depth']})")
    table.add_row("minecraft bounds", f"x {world['minecraft_min_x']}..{world['minecraft_max_x']}, z {world['minecraft_min_z']}..{world['minecraft_max_z']}")
    table.add_row("BNG extent", f"E {geo['bng_min_easting']}..{geo['bng_max_easting']}, N {geo['bng_min_northing']}..{geo['bng_max_northing']}")
    table.add_row("tile grid", f"{summary['tiles_x']} x {summary['tiles_z']} @ {summary['tile_size']} cells")
    table.add_row("height coverage", f"{height['valid_percent']:.2f}% valid ({height['nodata_cells']} nodata cells)")
    table.add_row("height min/mean/max", f"{height['min_metres']:.1f} m / {height['mean_metres']:.1f} m / {height['max_metres']:.1f} m")
    console.print(table)
    ore_table = Table("ore", "nonzero", "max score")
    for name, ore in summary["ores"].items():
        ore_table.add_row(name, f"{ore['nonzero_percent']:.2f}%", str(ore["max"]))
    console.print(ore_table)
    if "surface" in summary:
        surface_table = Table("surface id", "name", "percent")
        for item in summary["surface"]["classes"]:
            surface_table.add_row(str(item["id"]), item["name"], f"{item['percent']:.2f}%")
        console.print(surface_table)
    if "rivers" in summary:
        rivers = summary["rivers"]
        console.print(f"rivers: {rivers['nonzero_percent']:.2f}% coverage, max {rivers['max']}")
    if "vegetation" in summary:
        vegetation_table = Table("vegetation id", "name", "percent")
        for item in summary["vegetation"]["classes"]:
            vegetation_table.add_row(str(item["id"]), item["name"], f"{item['percent']:.2f}%")
        console.print(vegetation_table)


@app.command("pack-tile-regions")
def pack_tile_regions_cmd(
    root: Path,
    region_tiles: int = typer.Option(8, "--region-tiles", min=1, help="Square region size in source tiles."),
    delete_raw: bool = typer.Option(True, "--delete-raw/--keep-raw", help="Delete raw .u8/.r16 tiles after successful packing."),
) -> None:
    manifest_path = root / "manifest.json"
    manifest = read_manifest(manifest_path)
    pack_manifest_regions(root, manifest, region_tiles=region_tiles, delete_raw=delete_raw)
    write_manifest(manifest_path, manifest)
    console.print(f"[green]Packed tile layers into {region_tiles}x{region_tiles} binary regions.[/green]")


@app.command("preview")
def preview_cmd(
    root: Path,
    layer: str = typer.Option(..., "--layer"),
    out: Path = typer.Option(..., "--out"),
    max_size: int = typer.Option(4096, "--max-size", help="Longest side of the output image in pixels. Use 0 for native tile resolution."),
    style: str = typer.Option("auto", "--style", help="auto, gray, overlay. Overlay draws a single ore layer over the heightmap."),
    legend_scale: int = typer.Option(20, "--legend-scale", help="Scale factor for the combined ores legend."),
) -> None:
    if max_size == 0:
        console.print("[yellow]Rendering at native tile resolution can require several GB of RAM for the default 25k x 50k world.[/yellow]")
    try:
        make_preview(root, layer, out, max_size=max_size, style=style, legend_scale=legend_scale)
    except (FileNotFoundError, ValueError) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(1) from exc
    console.print(f"Wrote {out}")


@app.command("sample")
def sample(root: Path, x: int = typer.Option(..., "--x"), z: int = typer.Option(..., "--z")) -> None:
    manifest = read_manifest(root / "manifest.json")
    world = manifest["world"]
    bounds = WorldBounds(
        width=world["width"],
        depth=world["depth"],
        padded_width=world["padded_width"],
        padded_depth=world["padded_depth"],
        minecraft_min_x=world["minecraft_min_x"],
        minecraft_min_z=world["minecraft_min_z"],
        minecraft_max_x=world["minecraft_max_x"],
        minecraft_max_z=world["minecraft_max_z"],
        tile_size=manifest["tile_size"],
    )
    try:
        tx, tz, lx, lz = minecraft_to_tile_cell(x, z, bounds)
    except ValueError as exc:
        console.print(f"[red]{exc}[/red]")
        console.print(
            f"Valid Minecraft bounds: x {world['minecraft_min_x']}..{world['minecraft_max_x']}, "
            f"z {world['minecraft_min_z']}..{world['minecraft_max_z']}"
        )
        raise typer.Exit(1) from exc
    height_dm = int(read_layer_tile(root, manifest["height"], tx, tz, manifest["tile_size"])[lz, lx])
    if height_dm == HEIGHT_NODATA:
        console.print("height: nodata")
    else:
        console.print(f"height: {height_dm * 0.1:.1f} m")
    if "surface_geology" in manifest:
        surface = manifest["surface_geology"]
        class_id = int(read_layer_tile(root, surface, tx, tz, manifest["tile_size"])[lz, lx])
        meta = surface.get("classes", {}).get(str(class_id), {})
        console.print(f"surface_geology: {meta.get('name', class_id)} ({class_id})")
    if "rivers" in manifest:
        river = manifest["rivers"]
        score = int(read_layer_tile(root, river_u8_layer(river), tx, tz, manifest["tile_size"])[lz, lx])
        console.print(f"river: {score}")
        if "order_path" in river:
            order = int(read_layer_tile(root, river_u8_layer(river, "order_path", "order"), tx, tz, manifest["tile_size"])[lz, lx])
            console.print(f"river_order: {order}")
        if "half_width_path" in river:
            half_width = int(read_layer_tile(root, river_u8_layer(river, "half_width_path", "half_width"), tx, tz, manifest["tile_size"])[lz, lx])
            console.print(f"river_half_width: {half_width}")
    if "vegetation" in manifest:
        vegetation = manifest["vegetation"]
        cell_blocks = int(vegetation.get("cell_blocks", 1))
        vtx, vtz, vlx, vlz = minecraft_to_layer_cell(x, z, bounds, cell_blocks=cell_blocks)
        class_id = int(read_layer_tile(root, vegetation, vtx, vtz, manifest["tile_size"])[vlz, vlx])
        meta = vegetation.get("classes", {}).get(str(class_id), {})
        console.print(f"vegetation: {meta.get('name', class_id)} ({class_id})")
    if "biome_regions" in manifest:
        biome_regions = manifest["biome_regions"]
        cell_blocks = int(biome_regions.get("cell_blocks", 1))
        btx, btz, blx, blz = minecraft_to_layer_cell(x, z, bounds, cell_blocks=cell_blocks)
        class_id = int(read_layer_tile(root, biome_regions, btx, btz, manifest["tile_size"])[blz, blx])
        meta = biome_regions.get("classes", {}).get(str(class_id), {})
        console.print(f"biome_region: {meta.get('name', class_id)} ({class_id})")
    for ore, layer in manifest.get("ore_layers", {}).items():
        score = int(read_layer_tile(root, layer, tx, tz, manifest["tile_size"])[lz, lx])
        console.print(f"{ore}: {score}")


if __name__ == "__main__":
    app()
