import numpy as np

from ukgeo.tiles import HEIGHT_NODATA, pack_manifest_regions, read_layer_tile, read_r16_tile, read_u8_tile, write_r16_tile, write_u8_tile


def test_r16_roundtrip(tmp_path):
    arr = np.arange(512 * 512, dtype=np.int32).reshape(512, 512) % 32000
    arr = arr.astype("<i2")
    path = tmp_path / "000_000.r16.gz"
    write_r16_tile(path, arr)
    assert np.array_equal(read_r16_tile(path), arr)


def test_u8_roundtrip(tmp_path):
    arr = (np.arange(512 * 512, dtype=np.uint32).reshape(512, 512) % 256).astype(np.uint8)
    path = tmp_path / "000_000.u8.gz"
    write_u8_tile(path, arr)
    assert np.array_equal(read_u8_tile(path), arr)


def test_zero_u8_tile_is_sparse_and_reads_as_zero(tmp_path):
    path = tmp_path / "000_000.u8"
    write_u8_tile(path, np.zeros((512, 512), dtype=np.uint8))
    assert not path.exists()
    assert np.array_equal(read_u8_tile(path), np.zeros((512, 512), dtype=np.uint8))


def test_nodata_height_tile_is_sparse_and_reads_as_nodata(tmp_path):
    path = tmp_path / "000_000.r16"
    nodata = np.full((512, 512), HEIGHT_NODATA, dtype="<i2")
    write_r16_tile(path, nodata)
    assert not path.exists()
    assert np.array_equal(read_r16_tile(path), nodata)


def test_pack_manifest_regions_roundtrip_and_deletes_raw(tmp_path):
    manifest = {
        "format": "uk-raster-tiles-v1",
        "tile_size": 512,
        "world": {
            "width": 1024,
            "depth": 1024,
            "padded_width": 1024,
            "padded_depth": 1024,
            "minecraft_min_x": 0,
            "minecraft_min_z": 0,
        },
        "height": {
            "path": "height",
            "extension": ".r16",
            "dtype": "int16_le",
            "nodata": HEIGHT_NODATA,
            "sea_level_y": 64,
        },
        "ore_layers": {
            "copper": {
                "path": "ores/copper",
                "extension": ".u8",
                "dtype": "uint8",
            },
        },
    }
    height = np.full((512, 512), 120, dtype="<i2")
    ore = np.zeros((512, 512), dtype=np.uint8)
    ore[10, 20] = 200
    write_r16_tile(tmp_path / "height" / "000_000.r16", height)
    write_u8_tile(tmp_path / "ores" / "copper" / "001_001.u8", ore)

    pack_manifest_regions(tmp_path, manifest, region_tiles=2, delete_raw=True)

    assert manifest["height"]["storage"] == "regions"
    assert manifest["ore_layers"]["copper"]["storage"] == "regions"
    assert not (tmp_path / "height" / "000_000.r16").exists()
    assert not (tmp_path / "ores" / "copper" / "001_001.u8").exists()
    assert np.array_equal(read_layer_tile(tmp_path, manifest["height"], 0, 0, 512), height)
    assert np.array_equal(read_layer_tile(tmp_path, manifest["height"], 1, 1, 512), np.full((512, 512), HEIGHT_NODATA, dtype="<i2"))
    assert np.array_equal(read_layer_tile(tmp_path, manifest["ore_layers"]["copper"], 1, 1, 512), ore)
    assert np.array_equal(read_layer_tile(tmp_path, manifest["ore_layers"]["copper"], 0, 0, 512), np.zeros((512, 512), dtype=np.uint8))


def test_pack_manifest_regions_skips_already_packed_layers(tmp_path):
    manifest = {
        "format": "uk-raster-tiles-v1",
        "tile_size": 512,
        "world": {
            "width": 1024,
            "depth": 1024,
            "padded_width": 1024,
            "padded_depth": 1024,
            "minecraft_min_x": 0,
            "minecraft_min_z": 0,
        },
        "height": {
            "path": "height",
            "extension": ".r16",
            "dtype": "int16_le",
            "nodata": HEIGHT_NODATA,
            "sea_level_y": 64,
        },
        "ore_layers": {
            "copper": {
                "path": "ores/copper",
                "extension": ".u8",
                "dtype": "uint8",
            },
        },
    }
    height = np.full((512, 512), 120, dtype="<i2")
    ore = np.zeros((512, 512), dtype=np.uint8)
    ore[10, 20] = 200
    write_r16_tile(tmp_path / "height" / "000_000.r16", height)
    write_u8_tile(tmp_path / "ores" / "copper" / "001_001.u8", ore)

    pack_manifest_regions(tmp_path, manifest, region_tiles=2, delete_raw=True)
    # Simulate a crash before manifest write: drop storage metadata, then pack again.
    for layer in (manifest["height"], manifest["ore_layers"]["copper"]):
        for key in ("storage", "region_path", "region_extension", "region_tiles", "missing_tile"):
            layer.pop(key, None)

    pack_manifest_regions(tmp_path, manifest, region_tiles=2, delete_raw=True)

    assert manifest["height"]["storage"] == "regions"
    assert manifest["ore_layers"]["copper"]["storage"] == "regions"
    assert np.array_equal(read_layer_tile(tmp_path, manifest["height"], 0, 0, 512), height)
    assert np.array_equal(read_layer_tile(tmp_path, manifest["ore_layers"]["copper"], 1, 1, 512), ore)


def test_is_valid_region_file_rejects_empty_and_truncated(tmp_path):
    from ukgeo.tiles import REGION_HEADER, REGION_MAGIC, is_valid_region_file, write_region_file_from_tiles

    empty = tmp_path / "empty.u8rg"
    empty.write_bytes(b"")
    truncated = tmp_path / "trunc.u8rg"
    truncated.write_bytes(b"UKRG" + b"\x00" * 10)
    assert not is_valid_region_file(empty)
    assert not is_valid_region_file(truncated)

    good = tmp_path / "good.u8rg"
    tile = np.zeros((512, 512), dtype=np.uint8)
    tile[1, 2] = 255
    write_region_file_from_tiles(
        good,
        tiles=[tile] + [None] * 63,
        tile_size=512,
        region_tiles=8,
        dtype="uint8",
    )
    assert is_valid_region_file(good)
    assert good.read_bytes()[:4] == REGION_MAGIC
    assert good.stat().st_size >= REGION_HEADER.size


def test_write_region_file_from_tiles_roundtrip_mask(tmp_path):
    from ukgeo.tiles import read_region_tile, write_region_file_from_tiles

    region_root = tmp_path / "water" / "rivers" / "regions"
    out = region_root / "000_000.u8rg"
    order = np.zeros((512, 512), dtype=np.uint8)
    order[10:20, 30:40] = 3
    rivers = np.where(order > 0, np.uint8(255), np.uint8(0))
    write_region_file_from_tiles(
        out,
        tiles=[rivers] + [None] * 63,
        tile_size=512,
        region_tiles=8,
        dtype="uint8",
    )
    layer = {
        "path": "water/rivers",
        "storage": "regions",
        "region_path": "water/rivers/regions",
        "region_extension": ".u8rg",
        "region_tiles": 8,
        "dtype": "uint8",
        "missing_tile": 0,
    }
    assert np.array_equal(read_region_tile(tmp_path, layer, 0, 0, 512), rivers)
