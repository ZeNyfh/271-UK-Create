from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
import math
import tkinter as tk
from tkinter import ttk

from PIL import Image, ImageTk

from .manifest import read_manifest
from .preview import _height_image, _read_height_preview
from .tiles import HEIGHT_NODATA, read_r16_tile


@dataclass(frozen=True)
class HoverSample:
    minecraft_x: int
    minecraft_z: int
    data_x: int
    data_z: int
    tile_x: int
    tile_z: int
    local_x: int
    local_z: int
    height_decimetres: int | None
    bng_easting: float | None
    bng_northing: float | None

    @property
    def height_metres(self) -> float | None:
        return None if self.height_decimetres is None else self.height_decimetres * 0.1


class HeightTileSampler:
    def __init__(self, root: Path, manifest: dict, max_tiles: int = 32) -> None:
        self.root = root
        self.manifest = manifest
        self.tile_size = int(manifest["tile_size"])
        self.height_root = root / manifest["height"]["path"]
        self.world = manifest["world"]
        self.geo = manifest.get("georeferencing", {})
        self.max_tiles = max(1, max_tiles)
        self.cache: OrderedDict[tuple[int, int], object] = OrderedDict()

    def sample(self, data_x: int, data_z: int) -> HoverSample | None:
        width = int(self.world["width"])
        depth = int(self.world["depth"])
        if data_x < 0 or data_z < 0 or data_x >= width or data_z >= depth:
            return None
        tile_x = data_x // self.tile_size
        tile_z = data_z // self.tile_size
        local_x = data_x % self.tile_size
        local_z = data_z % self.tile_size
        tile = self._tile(tile_x, tile_z)
        raw_height = int(tile[local_z, local_x])
        height = None if raw_height == HEIGHT_NODATA else raw_height
        return HoverSample(
            minecraft_x=int(self.world["minecraft_min_x"]) + data_x,
            minecraft_z=int(self.world["minecraft_min_z"]) + data_z,
            data_x=data_x,
            data_z=data_z,
            tile_x=tile_x,
            tile_z=tile_z,
            local_x=local_x,
            local_z=local_z,
            height_decimetres=height,
            bng_easting=self._bng_x(data_x),
            bng_northing=self._bng_z(data_z),
        )

    def _tile(self, tile_x: int, tile_z: int):
        key = (tile_x, tile_z)
        tile = self.cache.get(key)
        if tile is not None:
            self.cache.move_to_end(key)
            return tile
        path = self.height_root / f"{tile_x:03d}_{tile_z:03d}.r16.gz"
        tile = read_r16_tile(path, self.tile_size)
        self.cache[key] = tile
        if len(self.cache) > self.max_tiles:
            self.cache.popitem(last=False)
        return tile

    def _bng_x(self, data_x: int) -> float | None:
        min_e = self.geo.get("bng_min_easting")
        max_e = self.geo.get("bng_max_easting")
        if min_e is None or max_e is None:
            return None
        return float(min_e) + (data_x + 0.5) * (float(max_e) - float(min_e)) / int(self.world["width"])

    def _bng_z(self, data_z: int) -> float | None:
        min_n = self.geo.get("bng_min_northing")
        max_n = self.geo.get("bng_max_northing")
        if min_n is None or max_n is None:
            return None
        return float(max_n) - (data_z + 0.5) * (float(max_n) - float(min_n)) / int(self.world["depth"])


def open_height_hover_map(root: Path, max_size: int = 4096, style: str = "auto") -> None:
    manifest = read_manifest(root / "manifest.json")
    tile_size = int(manifest["tile_size"])
    tiles_x = math.ceil(int(manifest["world"]["padded_width"]) / tile_size)
    tiles_z = math.ceil(int(manifest["world"]["padded_depth"]) / tile_size)
    full_width = tiles_x * tile_size
    full_depth = tiles_z * tile_size
    scale = 1 if max_size <= 0 else max(1, math.ceil(max(full_width, full_depth) / max_size))
    preview_values = _read_height_preview(root, manifest, tiles_x, tiles_z, tile_size, scale)
    preview_image = _height_image(preview_values, style)
    sampler = HeightTileSampler(root, manifest)

    app = tk.Tk()
    app.title(f"UKGeo height hover map - {root}")

    outer = ttk.Frame(app)
    outer.pack(fill=tk.BOTH, expand=True)
    canvas = tk.Canvas(outer, width=min(preview_image.width, 1400), height=min(preview_image.height, 900), highlightthickness=0)
    hbar = ttk.Scrollbar(outer, orient=tk.HORIZONTAL)
    vbar = ttk.Scrollbar(outer, orient=tk.VERTICAL)
    canvas.grid(row=0, column=0, sticky="nsew")
    vbar.grid(row=0, column=1, sticky="ns")
    hbar.grid(row=1, column=0, sticky="ew")
    outer.columnconfigure(0, weight=1)
    outer.rowconfigure(0, weight=1)

    view = {
        "x": 0.0,
        "y": 0.0,
        "zoom": 1.0,
        "photo": None,
        "drag_x": 0,
        "drag_y": 0,
    }

    status = tk.StringVar(
        value="Mouse wheel zooms. Middle/right drag pans. Left click copies the current Minecraft coordinates."
    )
    status_bar = ttk.Label(app, textvariable=status, anchor="w", padding=(8, 4))
    status_bar.pack(fill=tk.X)
    current_text = {"value": status.get()}

    def canvas_size() -> tuple[int, int]:
        width = canvas.winfo_width()
        height = canvas.winfo_height()
        if width <= 1:
            width = int(canvas.cget("width"))
        if height <= 1:
            height = int(canvas.cget("height"))
        return max(1, width), max(1, height)

    def scaled_size() -> tuple[float, float]:
        return preview_image.width * view["zoom"], preview_image.height * view["zoom"]

    def clamp_view() -> None:
        canvas_width, canvas_height = canvas_size()
        scaled_width, scaled_height = scaled_size()
        view["x"] = 0.0 if scaled_width <= canvas_width else max(0.0, min(view["x"], scaled_width - canvas_width))
        view["y"] = 0.0 if scaled_height <= canvas_height else max(0.0, min(view["y"], scaled_height - canvas_height))

    def update_scrollbars() -> None:
        canvas_width, canvas_height = canvas_size()
        scaled_width, scaled_height = scaled_size()
        if scaled_width <= canvas_width:
            hbar.set(0.0, 1.0)
        else:
            hbar.set(view["x"] / scaled_width, min(1.0, (view["x"] + canvas_width) / scaled_width))
        if scaled_height <= canvas_height:
            vbar.set(0.0, 1.0)
        else:
            vbar.set(view["y"] / scaled_height, min(1.0, (view["y"] + canvas_height) / scaled_height))

    def render() -> None:
        clamp_view()
        canvas_width, canvas_height = canvas_size()
        zoom = view["zoom"]
        left = max(0.0, view["x"] / zoom)
        top = max(0.0, view["y"] / zoom)
        right = min(float(preview_image.width), (view["x"] + canvas_width) / zoom)
        bottom = min(float(preview_image.height), (view["y"] + canvas_height) / zoom)
        if right <= left or bottom <= top:
            return

        crop_box = (
            max(0, int(math.floor(left))),
            max(0, int(math.floor(top))),
            min(preview_image.width, int(math.ceil(right))),
            min(preview_image.height, int(math.ceil(bottom))),
        )
        crop = preview_image.crop(crop_box)
        display_width = max(1, min(canvas_width, int(round((crop_box[2] - crop_box[0]) * zoom))))
        display_height = max(1, min(canvas_height, int(round((crop_box[3] - crop_box[1]) * zoom))))
        rendered = crop.resize((display_width, display_height), Image.Resampling.BILINEAR)
        photo = ImageTk.PhotoImage(rendered)
        canvas.delete("map")
        canvas.create_image(0, 0, image=photo, anchor="nw", tags=("map",))
        canvas.tag_lower("map")
        view["photo"] = photo
        update_scrollbars()

    def text_for_sample(sample: HoverSample | None) -> str:
        if sample is None:
            return "outside generated world"
        height = "nodata/ocean" if sample.height_metres is None else f"{sample.height_metres:.1f} m"
        bng = ""
        if sample.bng_easting is not None and sample.bng_northing is not None:
            bng = f" | BNG E {sample.bng_easting:.0f}, N {sample.bng_northing:.0f}"
        return (
            f"Minecraft x {sample.minecraft_x}, z {sample.minecraft_z} | "
            f"height {height} | data {sample.data_x},{sample.data_z} | "
            f"tile {sample.tile_x:03d}_{sample.tile_z:03d} cell {sample.local_x},{sample.local_z}"
            f"{bng}"
        )

    def sample_at_event(event) -> HoverSample | None:
        image_x = (view["x"] + event.x) / view["zoom"]
        image_y = (view["y"] + event.y) / view["zoom"]
        if image_x < 0 or image_y < 0 or image_x >= preview_image.width or image_y >= preview_image.height:
            return None
        return sampler.sample(int(image_x * scale), int(image_y * scale))

    def on_motion(event) -> None:
        current_text["value"] = text_for_sample(sample_at_event(event))
        status.set(current_text["value"])

    def on_click(event) -> None:
        sample = sample_at_event(event)
        if sample is None:
            return
        text = f"{sample.minecraft_x} {sample.minecraft_z}"
        app.clipboard_clear()
        app.clipboard_append(text)
        status.set(f"Copied Minecraft coordinates: {text}")

    def on_leave(_event) -> None:
        status.set(current_text["value"])

    def zoom_at(canvas_x: int, canvas_y: int, factor: float) -> None:
        old_zoom = view["zoom"]
        anchor_x = (view["x"] + canvas_x) / old_zoom
        anchor_y = (view["y"] + canvas_y) / old_zoom
        view["zoom"] = max(0.25, min(64.0, old_zoom * factor))
        view["x"] = anchor_x * view["zoom"] - canvas_x
        view["y"] = anchor_y * view["zoom"] - canvas_y
        render()

    def on_mousewheel(event) -> None:
        if event.delta == 0:
            return
        zoom_at(event.x, event.y, 1.25 if event.delta > 0 else 0.8)

    def on_mousewheel_linux(event, factor: float) -> None:
        zoom_at(event.x, event.y, factor)

    def start_pan(event) -> None:
        view["drag_x"] = event.x
        view["drag_y"] = event.y

    def drag_pan(event) -> None:
        view["x"] -= event.x - view["drag_x"]
        view["y"] -= event.y - view["drag_y"]
        view["drag_x"] = event.x
        view["drag_y"] = event.y
        render()

    def scrollbar_x(*args) -> None:
        canvas_width, _ = canvas_size()
        scaled_width, _ = scaled_size()
        if args[0] == "moveto":
            view["x"] = float(args[1]) * scaled_width
        elif args[0] == "scroll":
            step = 48 if args[2] == "units" else max(48, int(canvas_width * 0.9))
            view["x"] += int(args[1]) * step
        render()

    def scrollbar_y(*args) -> None:
        _, canvas_height = canvas_size()
        _, scaled_height = scaled_size()
        if args[0] == "moveto":
            view["y"] = float(args[1]) * scaled_height
        elif args[0] == "scroll":
            step = 48 if args[2] == "units" else max(48, int(canvas_height * 0.9))
            view["y"] += int(args[1]) * step
        render()

    hbar.configure(command=scrollbar_x)
    vbar.configure(command=scrollbar_y)
    canvas.bind("<Motion>", on_motion)
    canvas.bind("<Button-1>", on_click)
    canvas.bind("<ButtonPress-2>", start_pan)
    canvas.bind("<B2-Motion>", drag_pan)
    canvas.bind("<ButtonPress-3>", start_pan)
    canvas.bind("<B3-Motion>", drag_pan)
    canvas.bind("<Leave>", on_leave)
    canvas.bind("<MouseWheel>", on_mousewheel)
    canvas.bind("<Button-4>", lambda event: on_mousewheel_linux(event, 1.25))
    canvas.bind("<Button-5>", lambda event: on_mousewheel_linux(event, 0.8))
    canvas.bind("<Configure>", lambda _event: render())
    app.update_idletasks()
    render()
    app.mainloop()
