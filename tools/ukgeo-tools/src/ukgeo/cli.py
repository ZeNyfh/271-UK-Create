from __future__ import annotations

from pathlib import Path
import math

import fiona
import typer
from rich.console import Console
from rich.table import Table

from .asc import iter_nested_asc_headers
from .bgs import likely_geology_fields, resolve_gpkg
from .coords import WorldBounds, minecraft_to_tile_cell
from .height import make_height_tiles as make_height_tiles_impl
from .landmask import mask_height_to_bgs_land as mask_height_to_bgs_land_impl
from .manifest import read_manifest
from .ores import make_ore_tiles as make_ore_tiles_impl
from .preview import make_preview
from .rivers import make_river_tiles as make_river_tiles_impl
from .surface import make_surface_geology_tiles as make_surface_geology_tiles_impl
from .ni_height import add_osni_height_tiles as add_osni_height_tiles_impl
from .tiles import HEIGHT_NODATA, read_r16_tile, read_u8_tile
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
    minecraft_min_x: int = typer.Option(-12500, "--minecraft-min-x"),
    minecraft_min_z: int = typer.Option(-25000, "--minecraft-min-z"),
    sea_level_y: int = typer.Option(64, "--sea-level-y"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
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
        debug_geotiff=debug_geotiff,
    )


@app.command("make-ore-tiles")
def make_ore_tiles(
    bgs: Path = typer.Option(..., "--bgs"),
    rules: Path = typer.Option(..., "--rules"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    debug_geotiff_dir: Path | None = typer.Option(None, "--debug-geotiff-dir"),
) -> None:
    make_ore_tiles_impl(bgs=bgs, rules=rules, manifest_path=manifest, out=out, debug_geotiff_dir=debug_geotiff_dir)


@app.command("make-surface-geology-tiles")
def make_surface_geology_tiles(
    bgs: Path = typer.Option(..., "--bgs"),
    rules: Path = typer.Option(..., "--rules"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
) -> None:
    make_surface_geology_tiles_impl(bgs=bgs, rules=rules, manifest_path=manifest, out=out, debug_geotiff=debug_geotiff)


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


@app.command("make-river-tiles")
def make_river_tiles(
    rivers: Path = typer.Option(..., "--rivers"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    layer: str | None = typer.Option(None, "--layer"),
    width_metres: float = typer.Option(30.0, "--width-metres"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
) -> None:
    make_river_tiles_impl(rivers=rivers, manifest_path=manifest, out=out, layer=layer, width_metres=width_metres, debug_geotiff=debug_geotiff)


@app.command("mask-height-to-bgs-land")
def mask_height_to_bgs_land(
    bgs: Path = typer.Option(..., "--bgs"),
    manifest: Path = typer.Option(..., "--manifest"),
    out: Path = typer.Option(..., "--out"),
    layer: list[str] | None = typer.Option(None, "--layer", help="BGS polygon layer to use as land. Repeat for multiple layers."),
    buffer_metres: float = typer.Option(250.0, "--buffer-metres"),
    max_height_metres: float = typer.Option(20.0, "--max-height-metres"),
    debug_geotiff: Path | None = typer.Option(None, "--debug-geotiff"),
) -> None:
    mask_height_to_bgs_land_impl(
        bgs=bgs,
        manifest_path=manifest,
        out=out,
        layers=layer,
        buffer_metres=buffer_metres,
        max_height_metres=max_height_metres,
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


@app.command("hover-map")
def hover_map_cmd(
    root: Path,
    max_size: int = typer.Option(4096, "--max-size", help="Longest side of the displayed image in pixels. Use 0 for native tile resolution."),
    style: str = typer.Option("auto", "--style", help="auto or gray"),
) -> None:
    """Open an interactive heightmap and show Minecraft coordinates under the cursor."""
    if max_size == 0:
        console.print("[yellow]Native resolution can require several GB of RAM for the default 25k x 50k world.[/yellow]")
    try:
        from tkinter import TclError

        from .hover import open_height_hover_map

        open_height_hover_map(root, max_size=max_size, style=style)
    except ImportError as exc:
        console.print(f"[red]Could not import tkinter/Pillow GUI support: {exc}[/red]")
        raise typer.Exit(1) from exc
    except TclError as exc:
        console.print(f"[red]Could not open a GUI window: {exc}[/red]")
        raise typer.Exit(1) from exc


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
    height_path = root / manifest["height"]["path"] / f"{tx:03d}_{tz:03d}.r16.gz"
    height_dm = int(read_r16_tile(height_path, manifest["tile_size"])[lz, lx])
    if height_dm == HEIGHT_NODATA:
        console.print("height: nodata")
    else:
        console.print(f"height: {height_dm * 0.1:.1f} m")
    if "surface_geology" in manifest:
        surface = manifest["surface_geology"]
        path = root / surface["path"] / f"{tx:03d}_{tz:03d}.u8.gz"
        if path.exists():
            class_id = int(read_u8_tile(path, manifest["tile_size"])[lz, lx])
            meta = surface.get("classes", {}).get(str(class_id), {})
            console.print(f"surface_geology: {meta.get('name', class_id)} ({class_id})")
    if "rivers" in manifest:
        river = manifest["rivers"]
        path = root / river["path"] / f"{tx:03d}_{tz:03d}.u8.gz"
        if path.exists():
            score = int(read_u8_tile(path, manifest["tile_size"])[lz, lx])
            console.print(f"river: {score}")
    for ore, layer in manifest.get("ore_layers", {}).items():
        path = root / layer["path"] / f"{tx:03d}_{tz:03d}.u8.gz"
        if path.exists():
            score = int(read_u8_tile(path, manifest["tile_size"])[lz, lx])
            console.print(f"{ore}: {score}")


if __name__ == "__main__":
    app()
