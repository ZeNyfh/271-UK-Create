from __future__ import annotations

from pathlib import Path

import typer
from rich.console import Console

from .hover import open_height_hover_map
from .hover_previews import export_hover_previews

app = typer.Typer(no_args_is_help=True)
console = Console()


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
        written = export_hover_previews(root, out, max_size=max_size, style=style, clean=clean)
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
