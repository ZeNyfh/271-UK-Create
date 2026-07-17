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
