import json

import numpy as np

from ukgeo.gold import _gold_record, make_gold_occurrence_tiles
from ukgeo.manifest import default_u8_layer
from ukgeo.tiles import read_u8_tile, write_u8_tile


def test_gold_record_accepts_gold_commodity():
    record = _gold_record(
        {
            "layerName": "Mineral.Occurrences",
            "properties": {
                "OCCURRENCE_ID": 123,
                "OCCURRENCE": "Clogau Mine",
                "COMMODITY": "Gold",
                "EASTING": 267000,
                "NORTHING": 320000,
            },
        }
    )

    assert record is not None
    assert record["name"] == "Clogau Mine"
    assert record["symbol"] == "Au"


def test_gold_record_rejects_bgs_test_delete_marker():
    assert (
        _gold_record(
            {
                "layerName": "Mineral.Occurrences",
                "properties": {
                    "OCCURRENCE": "TEST - to be deleted",
                    "COMMODITY": "Gold",
                    "EASTING": 111111,
                    "NORTHING": 123456,
                },
            }
        )
        is None
    )


def test_gold_record_accepts_au_symbol():
    record = _gold_record(
        {
            "layerName": "Metallic.Minerals.Wales",
            "properties": {
                "LOCALITY_D": "Hendre-forion",
                "SYMBOL": "Au",
                "BNG_EASTIN": 267100,
                "BNG_NORTHI": 320100,
            },
        }
    )

    assert record is not None
    assert record["commodity"] == "Gold"


def test_gold_occurrence_tiles_merge_existing_layer(tmp_path):
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(
        json.dumps(
            {
                "format": "uk-raster-tiles-v1",
                "tile_size": 512,
                "world": {"width": 512, "depth": 512, "padded_width": 512, "padded_depth": 512},
                "georeferencing": {
                    "bng_min_easting": 0,
                    "bng_min_northing": 0,
                    "bng_max_easting": 5120,
                    "bng_max_northing": 5120,
                },
                "ore_layers": {"gold": default_u8_layer("ores/gold")},
            }
        ),
        encoding="utf-8",
    )
    existing = np.full((512, 512), 77, dtype=np.uint8)
    write_u8_tile(tmp_path / "ores" / "gold" / "000_000.u8.gz", existing)
    source = tmp_path / "gold.geojson"
    source.write_text(
        json.dumps(
            {
                "type": "FeatureCollection",
                "features": [
                    {
                        "type": "Feature",
                        "geometry": {"type": "Point", "coordinates": [2560, 2560]},
                        "properties": {"name": "Gold occurrence"},
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    make_gold_occurrence_tiles(
        gold_occurrences=source,
        manifest_path=manifest_path,
        out=tmp_path,
        radius_metres=500,
        core_metres=100,
        merge_existing=True,
    )

    merged = read_u8_tile(tmp_path / "ores" / "gold" / "000_000.u8.gz")
    assert merged[0, 0] == 77
    assert merged[256, 256] == 255
