from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import json
import urllib.parse
import urllib.request

import numpy as np
from pyproj import Transformer


DEFAULT_OPEN_METEO_BASE_URL = "https://api.open-meteo.com/v1/forecast"


@dataclass(frozen=True)
class WeatherOverlayGrid:
    rows: int
    columns: int
    latitudes: tuple[float, ...]
    longitudes: tuple[float, ...]


@dataclass(frozen=True)
class WeatherOverlaySnapshot:
    cloud_cover: np.ndarray
    downfall_coverage: np.ndarray
    cloud_observed_at_unix: int | None
    downfall_observed_at_unix: int | None
    api_base_url: str
    weather_model: str
    grid_rows: int
    grid_columns: int


def build_weather_overlay_grid(manifest: dict[str, Any], *, grid_columns: int) -> WeatherOverlayGrid:
    world = manifest.get("world") or {}
    geo = manifest.get("georeferencing") or {}
    required_geo = ("bng_min_easting", "bng_max_easting", "bng_min_northing", "bng_max_northing")
    missing = [key for key in required_geo if geo.get(key) is None]
    if missing:
        raise ValueError(f"Manifest is missing georeferencing keys for weather overlays: {', '.join(missing)}")

    width = int(world["width"])
    depth = int(world["depth"])
    columns = max(2, int(grid_columns))
    rows = max(2, int(round(columns * depth / max(1, width))))

    min_e = float(geo["bng_min_easting"])
    max_e = float(geo["bng_max_easting"])
    min_n = float(geo["bng_min_northing"])
    max_n = float(geo["bng_max_northing"])
    crs = str(geo.get("crs") or "EPSG:27700")
    transformer = Transformer.from_crs(crs, "EPSG:4326", always_xy=True)

    sample_x = np.linspace(0.5, max(0.5, width - 0.5), columns, dtype=np.float64)
    sample_z = np.linspace(0.5, max(0.5, depth - 0.5), rows, dtype=np.float64)
    eastings = min_e + sample_x * (max_e - min_e) / max(1, width)
    northings = max_n - sample_z * (max_n - min_n) / max(1, depth)
    eastings_grid, northings_grid = np.meshgrid(eastings, northings)
    lon, lat = transformer.transform(eastings_grid, northings_grid)

    return WeatherOverlayGrid(
        rows=rows,
        columns=columns,
        latitudes=tuple(float(value) for value in np.asarray(lat).reshape(-1)),
        longitudes=tuple(float(value) for value in np.asarray(lon).reshape(-1)),
    )


def fetch_open_meteo_weather_overlay(
    grid: WeatherOverlayGrid,
    *,
    api_base_url: str = DEFAULT_OPEN_METEO_BASE_URL,
    weather_model: str = "auto",
    timeout_seconds: float = 20.0,
    batch_points: int = 64,
) -> WeatherOverlaySnapshot:
    latitudes = list(grid.latitudes)
    longitudes = list(grid.longitudes)
    if len(latitudes) != len(longitudes):
        raise ValueError("Latitude and longitude lists must have the same length")
    if not latitudes:
        raise ValueError("Weather overlay grid is empty")

    cloud_values: list[int] = []
    downfall_values: list[int] = []
    cloud_times: list[int] = []
    downfall_times: list[int] = []

    for start in range(0, len(latitudes), max(1, int(batch_points))):
        batch_latitudes = latitudes[start : start + max(1, int(batch_points))]
        batch_longitudes = longitudes[start : start + max(1, int(batch_points))]
        response = _open_meteo_batch_request(
            batch_latitudes,
            batch_longitudes,
            api_base_url=api_base_url,
            weather_model=weather_model,
            timeout_seconds=timeout_seconds,
        )
        for location in response:
            current = location.get("current") or {}
            cloud = _coerce_percent(current.get("cloud_cover"))
            downfall = _coerce_precipitation_mm(current.get("precipitation"))
            cloud_values.append(cloud)
            downfall_values.append(downfall)
            cloud_times.append(_coerce_int(current.get("time")))
            downfall_times.append(_coerce_int(current.get("time")))

    total = grid.rows * grid.columns
    if len(cloud_values) != total or len(downfall_values) != total:
        raise ValueError(
            f"Open-Meteo returned {len(cloud_values)} cloud values and {len(downfall_values)} downfall values for a {grid.rows}x{grid.columns} grid"
        )

    return WeatherOverlaySnapshot(
        cloud_cover=np.asarray(cloud_values, dtype=np.uint8).reshape(grid.rows, grid.columns),
        downfall_coverage=np.asarray(downfall_values, dtype=np.float32).reshape(grid.rows, grid.columns),
        cloud_observed_at_unix=max((value for value in cloud_times if value is not None), default=None),
        downfall_observed_at_unix=max((value for value in downfall_times if value is not None), default=None),
        api_base_url=api_base_url,
        weather_model=weather_model,
        grid_rows=grid.rows,
        grid_columns=grid.columns,
    )


def _open_meteo_batch_request(
    latitudes: list[float],
    longitudes: list[float],
    *,
    api_base_url: str,
    weather_model: str,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    params = {
        "latitude": ",".join(f"{value:.6f}" for value in latitudes),
        "longitude": ",".join(f"{value:.6f}" for value in longitudes),
        "current": "cloud_cover,precipitation",
        "forecast_hours": 1,
        "timezone": "GMT",
        "timeformat": "unixtime",
    }
    model = str(weather_model or "").strip()
    if model and model.lower() != "auto":
        params["models"] = model
    url = f"{api_base_url}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"User-Agent": "UKGeo-HoverPreview/1.0"})
    with urllib.request.urlopen(request, timeout=max(1.0, float(timeout_seconds))) as response:
        payload = json.load(response)
    if not isinstance(payload, list):
        raise ValueError(f"Expected Open-Meteo list response for multi-location query, got {type(payload).__name__}")
    return payload


def _coerce_percent(value: Any) -> int:
    number = float(value or 0)
    if not np.isfinite(number):
        return 0
    return int(np.clip(round(number), 0, 100))


def _coerce_precipitation_mm(value: Any) -> float:
    number = float(value or 0)
    if not np.isfinite(number):
        return 0.0
    return float(max(0.0, number))


def _coerce_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
