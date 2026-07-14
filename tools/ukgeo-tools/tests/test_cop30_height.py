import tarfile

import numpy as np
import rasterio
from pyproj import Transformer
from rasterio.transform import from_bounds
from typer.testing import CliRunner

from ukgeo.cli import app
from ukgeo.cop30_height import add_cop30_height_tiles, cop30_land_mask_lonlat, mainland_gb_protection_mask_lonlat, target_mask_lonlat
from ukgeo.landmask import _height_overlay_preserve_tile
from ukgeo.manifest import default_manifest, read_manifest, write_manifest
from ukgeo.tiles import read_r16_tile, write_r16_tile


def test_ireland_iom_target_mask_known_points():
    included = {
        "Belfast": (-5.9301, 54.5973),
        "Larne": (-5.8140, 54.8506),
        "Ballymena": (-6.2767, 54.8653),
        "Antrim": (-6.2140, 54.7195),
        "Carrickfergus": (-5.8101, 54.7158),
        "Bangor NI": (-5.6680, 54.6608),
        "Newtownards": (-5.6950, 54.5924),
        "Newry": (-6.3374, 54.1751),
        "Armagh": (-6.6546, 54.3503),
        "Dundalk": (-6.4058, 54.0037),
        "Dublin": (-6.2603, 53.3498),
        "Galway": (-9.0568, 53.2707),
        "Cork": (-8.4756, 51.8985),
        "Derry": (-7.3092, 54.9966),
        "Douglas": (-4.4821, 54.1523),
    }
    excluded = {
        "Cardiff": (-3.1791, 51.4816),
        "Swansea": (-3.9436, 51.6214),
        "Liverpool": (-2.9916, 53.4084),
        "Glasgow": (-4.2518, 55.8642),
        "Edinburgh": (-3.1883, 55.9533),
        "Oban": (-5.4718, 56.4154),
        "Anglesey": (-4.3634, 53.2653),
        "London": (-0.1276, 51.5072),
    }
    for name, (lon, lat) in included.items():
        assert bool(target_mask_lonlat(np.array([[lon]]), np.array([[lat]]), "ireland-iom")[0, 0]), name
    for name, (lon, lat) in excluded.items():
        assert not bool(target_mask_lonlat(np.array([[lon]]), np.array([[lat]]), "ireland-iom")[0, 0]), name


def test_mainland_gb_protection_known_points():
    protected = {
        "Cardiff": (-3.1791, 51.4816),
        "Swansea": (-3.9436, 51.6214),
        "Liverpool": (-2.9916, 53.4084),
        "Glasgow": (-4.2518, 55.8642),
        "Edinburgh": (-3.1883, 55.9533),
        "Oban": (-5.4718, 56.4154),
        "Anglesey": (-4.3634, 53.2653),
    }
    unprotected = {
        "Belfast": (-5.9301, 54.5973),
        "Dublin": (-6.2603, 53.3498),
        "Douglas": (-4.4821, 54.1523),
    }
    for name, (lon, lat) in protected.items():
        assert bool(mainland_gb_protection_mask_lonlat(np.array([[lon]]), np.array([[lat]]) )[0, 0]), name
    for name, (lon, lat) in unprotected.items():
        assert not bool(mainland_gb_protection_mask_lonlat(np.array([[lon]]), np.array([[lat]]) )[0, 0]), name


def test_cop30_land_mask_known_points():
    included = {
        "Belfast": (-5.9301, 54.5973),
        "Larne": (-5.8140, 54.8506),
        "Ballymena": (-6.2767, 54.8653),
        "Antrim": (-6.2140, 54.7195),
        "Bangor NI": (-5.6680, 54.6608),
        "Derry": (-7.3092, 54.9966),
        "Newtownards": (-5.6950, 54.5924),
        "Newry": (-6.3374, 54.1751),
        "Armagh": (-6.6546, 54.3503),
        "Dundalk": (-6.4058, 54.0037),
        "Dublin": (-6.2603, 53.3498),
        "Cork": (-8.4756, 51.8985),
        "Galway": (-9.0568, 53.2707),
        "Douglas": (-4.4821, 54.1523),
    }
    excluded = {
        "Irish Sea east of NI": (-5.54, 54.65),
        "Sea between Ireland and Isle of Man": (-5.20, 54.20),
        "Sea between Isle of Man and England": (-3.80, 54.10),
        "Liverpool": (-2.9916, 53.4084),
        "Anglesey": (-4.3634, 53.2653),
        "Glasgow": (-4.2518, 55.8642),
        "Edinburgh": (-3.1883, 55.9533),
    }
    for name, (lon, lat) in included.items():
        assert bool(cop30_land_mask_lonlat(np.array([[lon]]), np.array([[lat]]), "ireland-iom")[0, 0]), name
    for name, (lon, lat) in excluded.items():
        assert not bool(cop30_land_mask_lonlat(np.array([[lon]]), np.array([[lat]]), "ireland-iom")[0, 0]), name


def test_cop30_overlay_writes_target_cells_preserves_nodata_and_metadata(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-5.9301, lat=54.5973)
    archive = _cop30_archive(tmp_path, bounds, value=12.3, nodata_center=True)

    add_cop30_height_tiles(
        cop30_archive=archive,
        manifest_path=manifest_path,
        out=root,
        resampling="nearest",
        smoothing="none",
        deterrace=False,
        target="ireland-iom",
        protect_mainland_gb=True,
    )

    tile = read_r16_tile(root / "height" / "000_000.r16")
    assert tile.dtype == np.dtype("<i2")
    assert np.count_nonzero(tile == 123) > 200_000
    assert tile[256, 256] == 100
    manifest = read_manifest(manifest_path)
    assert manifest["height_processing"]["source"] == "OS Terrain 50"
    assert manifest["height_overlays"][-1]["source"] == "Copernicus DEM COP30 GeoTIFF"
    assert manifest["height_overlays"][-1]["target"] == "ireland-iom"
    assert manifest["height_overlays"][-1]["minecraft_y_offset"] == 0
    assert manifest["height_overlays"][-1]["height_offset_decimetres"] == 0


def test_cop30_ocean_inside_target_is_not_written(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-5.54, lat=54.65)
    archive = _cop30_archive(tmp_path, bounds, value=0.0)

    add_cop30_height_tiles(
        cop30_archive=archive,
        manifest_path=manifest_path,
        out=root,
        resampling="nearest",
        smoothing="none",
        deterrace=False,
        target="ireland-iom",
        protect_mainland_gb=True,
        allow_empty=True,
    )

    tile = read_r16_tile(root / "height" / "000_000.r16")
    assert tile[256, 256] == 100
    assert read_manifest(manifest_path)["height_overlays"][-1]["minecraft_y_offset"] == 0


def test_cop30_sea_level_samples_do_not_write_even_inside_land_mask(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-6.2603, lat=53.3498)
    archive = _cop30_archive(tmp_path, bounds, value=0.0)

    add_cop30_height_tiles(
        cop30_archive=archive,
        manifest_path=manifest_path,
        out=root,
        resampling="nearest",
        smoothing="none",
        deterrace=False,
        target="ireland-iom",
        protect_mainland_gb=True,
        allow_empty=True,
    )

    tile = read_r16_tile(root / "height" / "000_000.r16")
    assert tile[256, 256] == 100


def test_cop30_target_mask_prevents_non_target_write(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-3.1791, lat=51.4816)
    archive = _cop30_archive(tmp_path, bounds, value=50.0)

    add_cop30_height_tiles(
        cop30_archive=archive,
        manifest_path=manifest_path,
        out=root,
        resampling="nearest",
        smoothing="none",
        deterrace=False,
        target="ireland-iom",
        protect_mainland_gb=False,
        minecraft_y_offset=0,
        allow_empty=True,
    )

    assert np.all(read_r16_tile(root / "height" / "000_000.r16") == 100)


def test_cop30_mainland_protection_prevents_gb_overwrite(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-3.1791, lat=51.4816)
    archive = _cop30_archive(tmp_path, bounds, value=50.0)

    add_cop30_height_tiles(
        cop30_archive=archive,
        manifest_path=manifest_path,
        out=root,
        resampling="nearest",
        smoothing="none",
        deterrace=False,
        target="all-cop30",
        protect_mainland_gb=True,
        minecraft_y_offset=0,
        allow_empty=True,
    )

    assert np.all(read_r16_tile(root / "height" / "000_000.r16") == 100)


def test_cop30_minecraft_y_offset_converts_to_decimetres(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-5.9301, lat=54.5973)
    manifest = read_manifest(manifest_path)
    manifest["minecraft_height"] = {"height_scale": 0.5}
    write_manifest(manifest_path, manifest)
    archive = _cop30_archive(tmp_path, bounds, value=50.0)

    add_cop30_height_tiles(
        cop30_archive=archive,
        manifest_path=manifest_path,
        out=root,
        resampling="nearest",
        smoothing="none",
        deterrace=False,
        target="ireland-iom",
        protect_mainland_gb=True,
        minecraft_y_offset=-2,
    )

    tile = read_r16_tile(root / "height" / "000_000.r16")
    assert np.count_nonzero(tile == 460) > 200_000
    overlay = read_manifest(manifest_path)["height_overlays"][-1]
    assert overlay["minecraft_y_offset"] == -2
    assert overlay["height_offset_decimetres"] == -40


def test_landmask_preserve_height_overlays_marks_only_cop30_land_cells(tmp_path):
    root, manifest_path, _bounds = _dataset(tmp_path, lon=-5.9301, lat=54.5973)
    manifest = read_manifest(manifest_path)
    manifest["height_overlays"] = [{"source": "Copernicus DEM COP30 GeoTIFF", "target": "ireland-iom"}]
    write_manifest(manifest_path, manifest)

    preserve = _height_overlay_preserve_tile(manifest, 0, 0, ["ireland-iom"])

    assert preserve.shape == (512, 512)
    assert preserve[256, 256]

    _root, sea_manifest_path, _sea_bounds = _dataset(tmp_path, lon=-5.54, lat=54.65)
    sea_manifest = read_manifest(sea_manifest_path)
    sea_manifest["height_overlays"] = [{"source": "Copernicus DEM COP30 GeoTIFF", "target": "ireland-iom"}]
    write_manifest(sea_manifest_path, sea_manifest)
    sea_preserve = _height_overlay_preserve_tile(sea_manifest, 0, 0, ["ireland-iom"])
    assert not sea_preserve[256, 256]


def test_add_cop30_cli_smoke(tmp_path):
    root, manifest_path, bounds = _dataset(tmp_path, lon=-4.4821, lat=54.1523)
    archive = _cop30_archive(tmp_path, bounds, value=20.0)

    result = CliRunner().invoke(
        app,
        [
            "add-cop30-height-tiles",
            "--cop30",
            str(archive),
            "--manifest",
            str(manifest_path),
            "--out",
            str(root),
            "--resampling",
            "nearest",
            "--smoothing",
            "none",
            "--no-height-deterrace",
            "--target",
            "iom-only",
            "--protect-mainland-gb",
        ],
    )

    assert result.exit_code == 0, result.output
    assert np.count_nonzero(read_r16_tile(root / "height" / "000_000.r16") == 200) > 200_000


def _dataset(tmp_path, *, lon: float, lat: float):
    stem = f"world_{lon:.4f}_{lat:.4f}".replace("-", "m").replace(".", "_")
    root = tmp_path / stem
    height = root / "height"
    height.mkdir(parents=True)
    to_bng = Transformer.from_crs("EPSG:4326", "EPSG:27700", always_xy=True)
    easting, northing = to_bng.transform(lon, lat)
    half = 5120.0
    manifest = default_manifest(
        width=512,
        depth=512,
        tile_size=512,
        minecraft_min_x=0,
        minecraft_min_z=0,
        bng_min_easting=easting - half,
        bng_min_northing=northing - half,
        bng_max_easting=easting + half,
        bng_max_northing=northing + half,
    )
    manifest["height_processing"] = {"source": "OS Terrain 50", "resampling": "bilinear", "smoothing": "light", "deterrace": True}
    manifest_path = root / "manifest.json"
    write_manifest(manifest_path, manifest)
    write_r16_tile(height / "000_000.r16", np.full((512, 512), 100, dtype="<i2"))
    to_lonlat = Transformer.from_crs("EPSG:27700", "EPSG:4326", always_xy=True)
    west, south = to_lonlat.transform(easting - half * 1.2, northing - half * 1.2)
    east, north = to_lonlat.transform(easting + half * 1.2, northing + half * 1.2)
    return root, manifest_path, (min(west, east), min(south, north), max(west, east), max(south, north))


def _cop30_archive(tmp_path, bounds, *, value: float, nodata_center: bool = False):
    tif = tmp_path / "source.tif"
    data = np.full((32, 32), value, dtype=np.float32)
    nodata = -9999.0
    if nodata_center:
        data[15:17, 15:17] = nodata
    with rasterio.open(
        tif,
        "w",
        driver="GTiff",
        height=data.shape[0],
        width=data.shape[1],
        count=1,
        dtype="float32",
        crs="EPSG:4326",
        transform=from_bounds(*bounds, data.shape[1], data.shape[0]),
        nodata=nodata,
    ) as dst:
        dst.write(data, 1)
    archive = tmp_path / "rasters_COP30.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(tif, arcname="nested/source.tif")
    return archive
