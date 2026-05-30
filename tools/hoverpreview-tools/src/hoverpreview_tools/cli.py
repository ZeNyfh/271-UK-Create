from __future__ import annotations

from pathlib import Path

import typer
from rich.console import Console
from rich.progress import BarColumn, Progress, TaskProgressColumn, TextColumn, TimeElapsedColumn

from ukgeo.manifest import read_manifest

from .hover import open_height_hover_map
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
) -> None:
    """Export stackable PNG layers consumed by the hover map."""
    if max_size == 0:
        console.print("[yellow]Native resolution can require several GB of RAM for the default 25k x 50k world.[/yellow]")
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

            written = export_hover_previews(root, out, max_size=max_size, style=style, clean=clean, progress=advance)
    except (FileNotFoundError, ValueError) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(1) from exc
    console.print(f"Wrote hover previews to {written}")


@app.command("open")
def open_cmd(
    root: Path = typer.Argument(Path("."), help="Dataset root containing hoverpreviews, or the hoverpreviews folder itself."),
    previews: Path | None = typer.Option(None, "--previews", help="Explicit hoverpreviews directory."),
    max_size: int = typer.Option(4096, "--max-size", help="Deprecated; hover-map now uses pre-exported hoverpreviews."),
    style: str = typer.Option("auto", "--style", help="auto or gray"),
) -> None:
    """Open an interactive pre-rendered hover map."""
    try:
        from tkinter import TclError

        open_height_hover_map(root, max_size=max_size, style=style, previews_dir=previews)
    except ImportError as exc:
        console.print(f"[red]Could not import tkinter/Pillow GUI support: {exc}[/red]")
        raise typer.Exit(1) from exc
    except (FileNotFoundError, ValueError) as exc:
        console.print(f"[red]{exc}[/red]")
        raise typer.Exit(1) from exc
    except TclError as exc:
        console.print(f"[red]Could not open a GUI window: {exc}[/red]")
        raise typer.Exit(1) from exc


if __name__ == "__main__":
    app()
