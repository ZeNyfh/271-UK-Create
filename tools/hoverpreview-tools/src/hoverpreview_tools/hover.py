from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import json
import math
import sys
import tkinter as tk
from tkinter import ttk

from PIL import Image, ImageTk

from .hover_previews import HOVER_PREVIEW_FORMAT, HOVER_PREVIEW_INDEX


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


class PreviewHeightSampler:
    def __init__(self, preview_root: Path, index: dict) -> None:
        self.preview_root = preview_root
        self.index = index
        self.scale = max(1, int(index["scale"]))
        self.tile_size = int(index.get("tile_size", 512))
        self.world = index["world"]
        self.geo = index.get("georeferencing", {})
        self.values: Image.Image | None = None
        height_entry = _preview_layer_entry(index, "height")
        sample_file = height_entry.get("sample_file") if height_entry else None
        if sample_file:
            path = preview_root / sample_file
            if path.exists():
                self.values = Image.open(path)

    def sample(self, data_x: int, data_z: int) -> HoverSample | None:
        width = int(self.world["width"])
        depth = int(self.world["depth"])
        if data_x < 0 or data_z < 0 or data_x >= width or data_z >= depth:
            return None
        tile_x = data_x // self.tile_size
        tile_z = data_z // self.tile_size
        local_x = data_x % self.tile_size
        local_z = data_z % self.tile_size
        height = None
        if self.values is not None:
            px = min(self.values.width - 1, max(0, data_x // self.scale))
            pz = min(self.values.height - 1, max(0, data_z // self.scale))
            raw = int(self.values.getpixel((px, pz)))
            height = None if raw == 0 else raw - 32768
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


class PreviewU8Sampler:
    def __init__(self, preview_root: Path, index: dict, entry_name: str) -> None:
        self.scale = max(1, int(index["scale"]))
        self.values: Image.Image | None = None
        entry = _preview_layer_entry(index, entry_name)
        sample_file = entry.get("sample_file") if entry else None
        if sample_file:
            path = preview_root / sample_file
            if path.exists():
                self.values = Image.open(path).convert("L")

    def sample(self, data_x: int, data_z: int) -> int | None:
        if self.values is None:
            return None
        px = data_x // self.scale
        pz = data_z // self.scale
        if px < 0 or pz < 0 or pz >= self.values.height or px >= self.values.width:
            return None
        return int(self.values.getpixel((px, pz)))


class _StartupProgress:
    def __init__(self, master: tk.Tk) -> None:
        self.master = master
        self.window = tk.Toplevel(master)
        self.window.title("Loading UKGeo hover map")
        self.window.resizable(False, False)
        frame = ttk.Frame(self.window, padding=(18, 14))
        frame.grid(row=0, column=0, sticky="nsew")
        ttk.Label(frame, text="Loading UKGeo hover map").grid(row=0, column=0, sticky="w")
        self.message = tk.StringVar(value="Starting")
        ttk.Label(frame, textvariable=self.message, width=46).grid(row=1, column=0, sticky="ew", pady=(8, 6))
        self.progress = ttk.Progressbar(frame, orient=tk.HORIZONTAL, length=360, mode="determinate", maximum=100)
        self.progress.grid(row=2, column=0, sticky="ew")
        self.master.update_idletasks()
        self._center()
        self.master.update()

    def step(self, message: str, value: int) -> None:
        self.message.set(message)
        self.progress["value"] = value
        self.master.update_idletasks()
        self.master.update()

    def close(self) -> None:
        self.window.destroy()
        self.master.update_idletasks()

    def _center(self) -> None:
        width = self.window.winfo_reqwidth()
        height = self.window.winfo_reqheight()
        screen_width = self.window.winfo_screenwidth()
        screen_height = self.window.winfo_screenheight()
        x = max(0, (screen_width - width) // 2)
        y = max(0, (screen_height - height) // 2)
        self.window.geometry(f"+{x}+{y}")


def open_height_hover_map(root: Path | None = None, max_size: int = 4096, style: str = "auto", previews_dir: Path | None = None) -> None:
    app = tk.Tk()
    app.withdraw()
    app.title("UKGeo hover map")
    loading = _StartupProgress(app)
    loading.step("Locating hoverpreviews folder", 5)
    preview_root = _resolve_hover_previews(root, previews_dir)
    loading.step("Reading hover preview index", 15)
    preview_index = _read_hover_preview_index(preview_root)
    loading.step("Preparing map metadata", 30)
    scale = max(1, int(preview_index["scale"]))
    image_width = int(preview_index["image_width"])
    image_height = int(preview_index["image_height"])
    layer_images: dict[tuple[str, int], Image.Image] = {}
    available_layers = _available_preview_layer_names(preview_index)
    loading.step("Opening height sample layer", 45)
    height_sampler = PreviewHeightSampler(preview_root, preview_index)
    loading.step("Opening hover sample layers", 60)
    u8_samplers = _make_preview_u8_samplers(preview_root, preview_index)

    app.title(f"UKGeo hover map - {preview_root}")
    loading.step("Building map window", 75)

    outer = ttk.Frame(app)
    outer.pack(fill=tk.BOTH, expand=True)
    controls = ttk.Frame(outer, padding=(8, 6))
    controls.grid(row=0, column=0, columnspan=2, sticky="ew")
    canvas = tk.Canvas(outer, width=min(image_width, 1400), height=min(image_height, 900), highlightthickness=0)
    hbar = ttk.Scrollbar(outer, orient=tk.HORIZONTAL)
    vbar = ttk.Scrollbar(outer, orient=tk.VERTICAL)
    canvas.grid(row=1, column=0, sticky="nsew")
    vbar.grid(row=1, column=1, sticky="ns")
    hbar.grid(row=2, column=0, sticky="ew")
    outer.columnconfigure(0, weight=1)
    outer.rowconfigure(1, weight=1)

    view = {
        "x": 0.0,
        "y": 0.0,
        "zoom": 1.0,
        "photo": None,
        "drag_x": 0,
        "drag_y": 0,
    }
    measure = {"start_sample": None, "start_x": 0, "start_y": 0, "moved": False}
    startup_fit = {"pending": True}
    enabled_layers = {
        name: tk.BooleanVar(value=name == "height")
        for name in available_layers
    }
    layer_buttons: dict[str, ttk.Checkbutton] = {}
    ore_names = _preview_ore_names(preview_index)
    ore_enabled: dict[str, tk.BooleanVar] = {ore: tk.BooleanVar(value=_default_preview_ore_enabled(ore)) for ore in ore_names}

    def on_ore_toggle(_ore: str | None = None) -> None:
        if enabled_layers.get("ores", tk.BooleanVar()).get():
            request_layer("ores")

    def rebuild_preview() -> None:
        render()

    def request_layer(name: str) -> None:
        rebuild_preview()

    col = 0
    for name in available_layers:
        label = name.replace("_", " ")
        if name == "ores":
            # main ores checkbox in its own column
            button = ttk.Checkbutton(controls, text=label, variable=enabled_layers[name], command=lambda layer=name: request_layer(layer))
            button.grid(row=0, column=col, padx=(0, 2), sticky="w")
            # dropdown menu for per-ore selection placed in the next column
            if ore_names:
                mb = ttk.Menubutton(controls, text="\u25BE")
                menu = tk.Menu(mb, tearoff=0)
                for ore in ore_names:
                    menu.add_checkbutton(label=ore.replace("_", " "), variable=ore_enabled[ore], command=lambda o=ore: on_ore_toggle(o))
                mb["menu"] = menu
                mb.grid(row=0, column=col + 1, padx=(2, 10), sticky="w")
                col += 2
            else:
                col += 1
            layer_buttons[name] = button
        else:
            button = ttk.Checkbutton(controls, text=label, variable=enabled_layers[name], command=lambda layer=name: request_layer(layer))
            button.grid(row=0, column=col, padx=(0, 10), sticky="w")
            layer_buttons[name] = button
            col += 1

    status = tk.StringVar(
        value="Mouse wheel zooms. Middle/right drag pans. Left click copies the current Minecraft coordinates."
    )
    current_text = {"value": status.get()}
    status_bg = canvas.create_rectangle(0, 0, 1, 1, fill="#111111", outline="", stipple="gray50", tags=("status_overlay",))
    status_text = canvas.create_text(
        8,
        8,
        text=status.get(),
        anchor="sw",
        fill="#ffffff",
        font=("TkDefaultFont", 9),
        width=max(1, int(canvas.cget("width")) - 16),
        tags=("status_overlay",),
    )
    measure_line = canvas.create_line(0, 0, 0, 0, fill="#111111", width=4, tags=("measure_overlay",), state="hidden")
    measure_text_bg = canvas.create_rectangle(0, 0, 1, 1, fill="#111111", outline="", tags=("measure_overlay",), state="hidden")
    measure_text = canvas.create_text(
        0,
        0,
        text="",
        anchor="s",
        fill="#ffffff",
        font=("TkDefaultFont", 9, "bold"),
        tags=("measure_overlay",),
        state="hidden",
    )

    def canvas_size() -> tuple[int, int]:
        width = canvas.winfo_width()
        height = canvas.winfo_height()
        if width <= 1:
            width = int(canvas.cget("width"))
        if height <= 1:
            height = int(canvas.cget("height"))
        return max(1, width), max(1, height)

    def update_status_overlay(*_args) -> None:
        canvas_width, canvas_height = canvas_size()
        wrap_width = max(80, canvas_width - 16)
        canvas.itemconfigure(status_text, text=status.get(), width=wrap_width)
        canvas.coords(status_text, 8, canvas_height - 8)
        bbox = canvas.bbox(status_text)
        if bbox is None:
            return
        x0, y0, x1, y1 = bbox
        canvas.coords(status_bg, max(0, x0 - 6), max(0, y0 - 4), min(canvas_width, x1 + 6), min(canvas_height, y1 + 4))
        canvas.tag_raise("status_overlay")

    def fit_initial_view() -> None:
        canvas_width, canvas_height = canvas_size()
        if canvas_width < 200 or canvas_height < 200:
            return
        view["zoom"] = max(0.02, min(1.0, canvas_width / image_width, canvas_height / image_height))
        view["x"] = 0.0
        view["y"] = 0.0
        startup_fit["pending"] = False
        render()

    def scaled_size() -> tuple[float, float]:
        return image_width * view["zoom"], image_height * view["zoom"]

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
        right = min(float(image_width), (view["x"] + canvas_width) / zoom)
        bottom = min(float(image_height), (view["y"] + canvas_height) / zoom)
        if right <= left or bottom <= top:
            return

        crop_box = (
            max(0, int(math.floor(left))),
            max(0, int(math.floor(top))),
            min(image_width, int(math.ceil(right))),
            min(image_height, int(math.ceil(bottom))),
        )
        crop = _compose_viewport(
            preview_root,
            preview_index,
            layer_images,
            crop_box,
            zoom,
            {name: var.get() for name, var in enabled_layers.items()},
            [ore for ore, var in ore_enabled.items() if var.get()],
        )
        display_width = max(1, min(canvas_width, int(round((crop_box[2] - crop_box[0]) * zoom))))
        display_height = max(1, min(canvas_height, int(round((crop_box[3] - crop_box[1]) * zoom))))
        rendered = crop.resize((display_width, display_height), Image.Resampling.BILINEAR)
        photo = ImageTk.PhotoImage(rendered)
        canvas.delete("map")
        canvas.create_image(0, 0, image=photo, anchor="nw", tags=("map",))
        canvas.tag_lower("map")
        view["photo"] = photo
        update_scrollbars()
        canvas.tag_raise("measure_overlay")
        update_status_overlay()

    def text_for_sample(sample: HoverSample | None) -> str:
        if sample is None:
            return "outside generated world"
        height = "nodata/ocean" if sample.height_metres is None else f"{sample.height_metres:.1f} m"
        bng = ""
        selected_ores = [ore for ore, var in ore_enabled.items() if var.get()]
        if sample.bng_easting is not None and sample.bng_northing is not None:
            bng = f" | BNG E {sample.bng_easting:.0f}, N {sample.bng_northing:.0f}"
        details = _sample_layer_text(
            u8_samplers,
            preview_index,
            sample.data_x,
            sample.data_z,
            {name: var.get() for name, var in enabled_layers.items()},
            selected_ores,
        )
        suffix = f" | {details}" if details else ""
        return (
            f"Minecraft x {sample.minecraft_x}, z {sample.minecraft_z} | "
            f"height {height} | data {sample.data_x},{sample.data_z} | "
            f"tile {sample.tile_x:03d}_{sample.tile_z:03d} cell {sample.local_x},{sample.local_z}"
            f"{bng}{suffix}"
        )

    def sample_at_event(event) -> HoverSample | None:
        image_x = (view["x"] + event.x) / view["zoom"]
        image_y = (view["y"] + event.y) / view["zoom"]
        if image_x < 0 or image_y < 0 or image_x >= image_width or image_y >= image_height:
            return None
        return height_sampler.sample(int(image_x * scale), int(image_y * scale))

    def on_motion(event) -> None:
        current_text["value"] = text_for_sample(sample_at_event(event))
        status.set(current_text["value"])

    def clear_measurement() -> None:
        canvas.itemconfigure(measure_line, state="hidden")
        canvas.itemconfigure(measure_text, state="hidden")
        canvas.itemconfigure(measure_text_bg, state="hidden")

    def on_left_press(event) -> None:
        measure["start_sample"] = sample_at_event(event)
        measure["start_x"] = event.x
        measure["start_y"] = event.y
        measure["moved"] = False
        clear_measurement()

    def on_left_drag(event) -> None:
        start_sample = measure.get("start_sample")
        current_sample = sample_at_event(event)
        if start_sample is None or current_sample is None:
            clear_measurement()
            return
        if abs(event.x - int(measure["start_x"])) + abs(event.y - int(measure["start_y"])) >= 4:
            measure["moved"] = True
        if not measure["moved"]:
            return
        label = _measurement_text(start_sample, current_sample)
        canvas.coords(measure_line, int(measure["start_x"]), int(measure["start_y"]), event.x, event.y)
        mid_x = (int(measure["start_x"]) + event.x) / 2
        mid_y = (int(measure["start_y"]) + event.y) / 2 - 8
        canvas.itemconfigure(measure_line, state="normal")
        canvas.itemconfigure(measure_text, text=label, state="normal")
        canvas.coords(measure_text, mid_x, mid_y)
        bbox = canvas.bbox(measure_text)
        if bbox is not None:
            x0, y0, x1, y1 = bbox
            canvas.coords(measure_text_bg, x0 - 5, y0 - 3, x1 + 5, y1 + 3)
            canvas.itemconfigure(measure_text_bg, state="normal")
        canvas.tag_raise("measure_overlay")
        update_status_overlay()

    def on_left_release(event) -> None:
        if measure.get("moved"):
            return
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
        view["zoom"] = max(0.02, min(64.0, old_zoom * factor))
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

    def close_window() -> None:
        app.destroy()

    hbar.configure(command=scrollbar_x)
    vbar.configure(command=scrollbar_y)
    canvas.bind("<Motion>", on_motion)
    canvas.bind("<ButtonPress-1>", on_left_press)
    canvas.bind("<B1-Motion>", on_left_drag)
    canvas.bind("<ButtonRelease-1>", on_left_release)
    canvas.bind("<ButtonPress-2>", start_pan)
    canvas.bind("<B2-Motion>", drag_pan)
    canvas.bind("<ButtonPress-3>", start_pan)
    canvas.bind("<B3-Motion>", drag_pan)
    canvas.bind("<Leave>", on_leave)
    canvas.bind("<MouseWheel>", on_mousewheel)
    canvas.bind("<Button-4>", lambda event: on_mousewheel_linux(event, 1.25))
    canvas.bind("<Button-5>", lambda event: on_mousewheel_linux(event, 0.8))
    status.trace_add("write", update_status_overlay)

    def on_canvas_configure(_event) -> None:
        if startup_fit["pending"]:
            fit_initial_view()
        else:
            render()
        update_status_overlay()

    canvas.bind("<Configure>", on_canvas_configure)
    app.protocol("WM_DELETE_WINDOW", close_window)
    app.geometry(_initial_window_geometry(app, image_width, image_height))
    app.update_idletasks()
    app.deiconify()
    app.update_idletasks()
    loading.step("Rendering first map view", 90)
    fit_initial_view()
    app.after(100, fit_initial_view)
    app.after(300, fit_initial_view)
    loading.step("Ready", 100)
    loading.close()
    app.mainloop()


def _resolve_hover_previews(root: Path | None, previews_dir: Path | None) -> Path:
    if previews_dir is not None:
        candidate = previews_dir
        if (candidate / HOVER_PREVIEW_INDEX).exists():
            return candidate
        raise FileNotFoundError(f"Hover previews are missing: {candidate / HOVER_PREVIEW_INDEX}")

    candidates: list[Path] = []
    if root is not None:
        candidates.append(root / "hoverpreviews")
        candidates.append(root)
    candidates.append(Path.cwd() / "hoverpreviews")
    candidates.append(_executable_dir() / "hoverpreviews")
    for candidate in candidates:
        if (candidate / HOVER_PREVIEW_INDEX).exists():
            return candidate
    searched = ", ".join(str(path) for path in candidates)
    raise FileNotFoundError(
        f"Could not find {HOVER_PREVIEW_INDEX}. Generate hover previews and place the hoverpreviews folder next to the executable/binary. Searched: {searched}"
    )


def _initial_window_geometry(app: tk.Tk, image_width: int, image_height: int) -> str:
    screen_width = max(640, app.winfo_screenwidth())
    screen_height = max(480, app.winfo_screenheight())
    target_canvas_width = min(image_width, 1400)
    target_canvas_height = min(image_height, 900)
    width = min(screen_width - 32, max(720, target_canvas_width + 24))
    height = min(screen_height - 80, max(520, target_canvas_height + 88))
    x = max(0, (screen_width - width) // 2)
    y = max(0, (screen_height - height) // 2)
    return f"{width}x{height}+{x}+{y}"


def _measurement_text(start: HoverSample, end: HoverSample) -> str:
    dx = abs(end.minecraft_x - start.minecraft_x)
    dz = abs(end.minecraft_z - start.minecraft_z)
    straight = math.hypot(dx, dz)
    return f"{straight:.1f} blocks | dx {dx}, dz {dz}"


def _executable_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def _read_hover_preview_index(preview_root: Path) -> dict:
    with (preview_root / HOVER_PREVIEW_INDEX).open("r", encoding="utf-8") as fh:
        index = json.load(fh)
    if index.get("format") != HOVER_PREVIEW_FORMAT:
        raise ValueError(f"{preview_root / HOVER_PREVIEW_INDEX} is not a {HOVER_PREVIEW_FORMAT} index")
    return index


def _preview_layer_entry(index: dict, name: str) -> dict | None:
    for entry in index.get("layers", []):
        if entry.get("name") == name:
            return entry
    return None


def _open_preview_layer(preview_root: Path, entry: dict | None, mode: str, mip_factor: int = 1) -> Image.Image:
    if entry is None:
        raise FileNotFoundError("Hover preview layer is not present in the index.")
    path = preview_root / _mip_file(entry, mip_factor)
    if not path.exists():
        raise FileNotFoundError(path)
    return Image.open(path).convert(mode)


def _mip_file(entry: dict, mip_factor: int) -> str:
    best = entry.get("file")
    for mip in entry.get("mips", []):
        if int(mip.get("factor", 1)) == mip_factor:
            return str(mip["file"])
        if int(mip.get("factor", 1)) == 1:
            best = str(mip["file"])
    return str(best)


def _mip_factors(index: dict) -> list[int]:
    height = _preview_layer_entry(index, "height") or {}
    factors = sorted({int(mip.get("factor", 1)) for mip in height.get("mips", [])})
    return factors or [1]


def _select_mip_factor(index: dict, zoom: float) -> int:
    desired = max(1.0, 1.0 / max(zoom, 0.001))
    factor = 1
    for candidate in _mip_factors(index):
        if candidate <= desired:
            factor = candidate
    return factor


def _layer_image(preview_root: Path, index: dict, cache: dict[tuple[str, int], Image.Image], name: str, mip_factor: int, mode: str) -> Image.Image:
    key = (name, mip_factor)
    image = cache.get(key)
    if image is None:
        image = _open_preview_layer(preview_root, _preview_layer_entry(index, name), mode, mip_factor)
        cache[key] = image
        if len(cache) > 24:
            oldest = next(iter(cache))
            cache.pop(oldest, None)
    return image


def _mip_crop_box(crop_box: tuple[int, int, int, int], mip_factor: int, image: Image.Image) -> tuple[int, int, int, int]:
    return (
        max(0, int(math.floor(crop_box[0] / mip_factor))),
        max(0, int(math.floor(crop_box[1] / mip_factor))),
        min(image.width, int(math.ceil(crop_box[2] / mip_factor))),
        min(image.height, int(math.ceil(crop_box[3] / mip_factor))),
    )


def _compose_viewport(
    preview_root: Path,
    index: dict,
    cache: dict[tuple[str, int], Image.Image],
    crop_box: tuple[int, int, int, int],
    zoom: float,
    enabled: dict[str, bool],
    selected_ores: list[str],
) -> Image.Image:
    mip_factor = _select_mip_factor(index, zoom)
    height = _layer_image(preview_root, index, cache, "height", mip_factor, "RGB")
    mip_box = _mip_crop_box(crop_box, mip_factor, height)
    if enabled.get("height", False):
        base = height.crop(mip_box).convert("RGBA")
    else:
        base = Image.new("RGBA", (mip_box[2] - mip_box[0], mip_box[3] - mip_box[1]), (16, 24, 32, 255))

    for name in ("surface", "vegetation"):
        if not enabled.get(name, False) or _preview_layer_entry(index, name) is None:
            continue
        overlay = _layer_image(preview_root, index, cache, name, mip_factor, "RGBA")
        base.alpha_composite(overlay.crop(_mip_crop_box(crop_box, mip_factor, overlay)))

    if enabled.get("ores", False):
        for ore in selected_ores:
            name = f"ore:{ore}"
            if _preview_layer_entry(index, name) is None:
                continue
            overlay = _layer_image(preview_root, index, cache, name, mip_factor, "RGBA")
            base.alpha_composite(overlay.crop(_mip_crop_box(crop_box, mip_factor, overlay)))

    if enabled.get("rivers", False) and _preview_layer_entry(index, "rivers") is not None:
        overlay = _layer_image(preview_root, index, cache, "rivers", mip_factor, "RGBA")
        base.alpha_composite(overlay.crop(_mip_crop_box(crop_box, mip_factor, overlay)))

    return base.convert("RGB")


def _available_preview_layer_names(index: dict) -> list[str]:
    entry_names = {entry.get("name") for entry in index.get("layers", [])}
    names = ["height"]
    for name in ("surface", "vegetation", "rivers"):
        if name in entry_names:
            names.append(name)
    if any(str(name).startswith("ore:") for name in entry_names):
        names.append("ores")
    return names


def _preview_ore_names(index: dict) -> list[str]:
    ores: list[str] = []
    for entry in index.get("layers", []):
        if entry.get("kind") == "ore":
            ore = str(entry.get("ore") or entry.get("name", "").split(":", 1)[-1])
            if ore != "tin":
                ores.append(ore)
    return ores


def _default_preview_ore_enabled(ore: str) -> bool:
    return ore in {"coal", "iron", "copper", "zinc", "gold"}


def _make_preview_u8_samplers(preview_root: Path, index: dict) -> dict[str, PreviewU8Sampler]:
    samplers: dict[str, PreviewU8Sampler] = {}
    for name in ("surface", "vegetation", "rivers"):
        if _preview_layer_entry(index, name) is not None:
            sampler_key = "river" if name == "rivers" else name
            samplers[sampler_key] = PreviewU8Sampler(preview_root, index, name)
    for ore in _preview_ore_names(index):
        samplers[f"ore:{ore}"] = PreviewU8Sampler(preview_root, index, f"ore:{ore}")
    return samplers


def _sample_layer_text(samplers: dict[str, PreviewU8Sampler], manifest: dict, data_x: int, data_z: int, enabled: dict[str, bool], selected_ores: list[str] | None = None) -> str:
    parts: list[str] = []
    surface = samplers.get("surface")
    if surface is not None and enabled.get("surface", False):
        class_id = surface.sample(data_x, data_z)
        meta = manifest.get("surface_geology", {}).get("classes", {}).get(str(class_id), {})
        parts.append(f"surface {meta.get('name', class_id)}")
    vegetation = samplers.get("vegetation")
    if vegetation is not None and enabled.get("vegetation", False):
        class_id = vegetation.sample(data_x, data_z)
        meta = manifest.get("vegetation", {}).get("classes", {}).get(str(class_id), {})
        parts.append(f"vegetation {meta.get('name', class_id)}")
    river = samplers.get("river")
    if river is not None and enabled.get("rivers", False):
        score = river.sample(data_x, data_z)
        if score:
            parts.append(f"river {score}")
    ore_scores: list[tuple[str, int]] = []
    if enabled.get("ores", False):
        for key, sampler in samplers.items():
            if not key.startswith("ore:"):
                continue
            ore_name = key.split(":", 1)[1]
            if selected_ores is not None and ore_name not in selected_ores:
                continue
            score = sampler.sample(data_x, data_z) or 0
            if score > 0:
                ore_scores.append((ore_name, score))
    if ore_scores:
        best = sorted(ore_scores, key=lambda item: item[1], reverse=True)[:4]
        parts.append("ores " + ", ".join(f"{name} {score}" for name, score in best))
    return " | ".join(parts)
