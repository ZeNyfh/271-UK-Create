from __future__ import annotations

import numpy as np

from ukgeo.surface import remove_isolated_surface_pixels


def test_remove_isolated_single_pixel():
    arr = np.full((9, 9), 3, dtype=np.uint8)
    arr[4, 4] = 7
    changed = remove_isolated_surface_pixels(arr, max_island=3)
    assert changed >= 1
    assert int(arr[4, 4]) == 3


def test_remove_small_cluster_keeps_large_region():
    arr = np.zeros((16, 16), dtype=np.uint8)
    arr[:, :] = 1
    arr[2:10, 2:10] = 2  # large region
    arr[12, 12] = 5
    arr[12, 13] = 5
    arr[13, 12] = 5  # 3-pixel island
    changed = remove_isolated_surface_pixels(arr, max_island=3)
    assert changed >= 3
    assert int(arr[12, 12]) != 5
    assert int(arr[5, 5]) == 2
    assert np.sum(arr == 2) == 64


def test_legitimate_boundary_stays_sharp():
    arr = np.zeros((12, 12), dtype=np.uint8)
    arr[:, :6] = 1
    arr[:, 6:] = 2
    before = arr.copy()
    changed = remove_isolated_surface_pixels(arr, max_island=3)
    assert changed == 0
    assert np.array_equal(arr, before)


def test_coherent_region_untouched():
    arr = np.full((20, 20), 4, dtype=np.uint8)
    arr[5:15, 5:15] = 9
    before = arr.copy()
    assert remove_isolated_surface_pixels(arr, max_island=3) == 0
    assert np.array_equal(arr, before)


def test_paleogene_clay_silt_sand_keywords():
    """BGS 625k keyword gap: Solent Group clay/silt/sand vs bare sand stealing sandstone."""
    import re
    from pathlib import Path

    import yaml

    rules = yaml.safe_load(
        (Path(__file__).resolve().parents[1] / "examples" / "surface_geology_625k.yml").read_text()
    )
    sand_kw = rules["classes"]["sand_gravel"]["keywords"]
    clay_kw = rules["classes"]["clay_silt"]["keywords"]
    assert "sand" not in sand_kw
    sand_pat = re.compile("|".join(re.escape(k.lower()) for k in sand_kw))
    clay_pat = re.compile("|".join(re.escape(k.lower()) for k in clay_kw))

    solent = "solent group - clay, silt and sand"
    assert clay_pat.search(solent), "clay/silt keywords should match Solent Group text"
    assert not sand_pat.search("sandstone"), "sand_gravel must not match sandstone (no bare sand)"
