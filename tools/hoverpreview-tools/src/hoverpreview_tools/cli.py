from __future__ import annotations

from pathlib import Path

import typer
from rich.console import Console
from rich.progress import BarColumn, Progress, TaskProgressColumn, TextColumn, TimeElapsedColumn

from ukgeo.manifest import read_manifest

from .hover_previews import export_hover_previews, hover_preview_steps

app = typer.Typer(no_args_is_help=True)
console = Console()


def _progress_label(step: str) -> str:
    if step.startswith("ore:"):
        return f"Generating ore layer: {step.removeprefix('ore:')}"
    labels = {
        "height": "Generating height layer",
        "surface": "Generating surface layer",
        "vegetation": "Generating vegetation layer",
        "rivers": "Generating river layer",
        "manifest": "Writing hover manifest",
    }
    return labels.get(step, f"Generating {step}")


@app.command("export")
def export_cmd(
    root: Path,
    out: Path = typer.Option(Path("hoverpreviews"), "--out", help="Output folder for stackable hover-map layer images."),
    max_size: int = typer.Option(4096, "--max-size", help="Longest side of the exported preview images. Use 0 for native tile resolution."),
    style: str = typer.Option("auto", "--style", help="Height layer style: auto or gray."),
    clean: bool = typer.Option(False, "--clean", help="Delete the output folder before writing previews."),
    tile_size: int = typer.Option(256, "--tile-size", help="Visual and sample tile size in pixels."),
    workers: int = typer.Option(0, "--workers", help="Tile encoder workers. Use 0 for a bounded auto value."),
    visual_format: str = typer.Option("png", "--visual-format", help="Visual tile format: png or webp. Sample tiles are always lossless PNG."),
    renderer: str = typer.Option("auto", "--renderer", help="Preferred site renderer written into the manifest: auto, webgl, or 2d."),
    force: bool = typer.Option(False, "--force", help="Regenerate tile/image files even if they already exist."),
    clean_stale: bool = typer.Option(False, "--clean-stale", help="Delete stale tile files that are no longer referenced by the generated manifest."),
    deploy_minimal: bool = typer.Option(False, "--deploy-minimal", help="Delete redundant full-size layers, mips, and sample images after export. Keep only manifest, visual tiles, and sample tiles."),
    profile: bool = typer.Option(False, "--profile", help="Print rough per-layer export timings."),
) -> None:
    """Export stackable PNG layers consumed by the hover map."""
    if max_size == 0:
        console.print("[yellow]Native resolution can require several GB of RAM for large generated worlds.[/yellow]")
    try:
        manifest = read_manifest(root / "manifest.json")
        total_steps = len(hover_preview_steps(root, manifest))
        with Progress(
            TextColumn("[progress.description]{task.description}"),
            BarColumn(),
            TaskProgressColumn(),
            TimeElapsedColumn(),
            console=console,
        ) as progress:
            task_id = progress.add_task("Generating hover previews", total=total_steps)

            def advance(step: str) -> None:
                progress.update(task_id, description=_progress_label(step), advance=1)

            written = export_hover_previews(
                root,
                out,
                max_size=max_size,
                style=style,
                clean=clean,
                tile_size=tile_size,
                workers=workers,
                visual_format=visual_format,
                renderer=renderer,
                force=force,
                clean_stale=clean_stale,
                deploy_minimal=deploy_minimal,
                profile=profile,
                progress=advance,
            )
    except (FileNotFoundError, ValueError) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(1) from exc
    console.print(f"Wrote hover previews to {written}")


if __name__ == "__main__":
    app()
