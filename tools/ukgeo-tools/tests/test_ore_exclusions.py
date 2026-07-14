from __future__ import annotations

import numpy as np

from ukgeo.ores import _apply_component_exclusions, _clear_connected_component, _resolve_exclusion_seed


def _world() -> dict[str, int]:
    return {
        "minecraft_min_x": -10,
        "minecraft_min_z": -10,
        "width": 32,
        "depth": 32,
    }


def test_clear_connected_component_uses_four_way_adjacency() -> None:
    arr = np.zeros((5, 5), dtype=np.uint8)
    arr[1, 1] = 1
    arr[1, 2] = 1
    arr[2, 1] = 1
    arr[3, 3] = 1

    removed = _clear_connected_component(arr, 1, 1)

    assert removed == 3
    assert arr[1, 1] == 0
    assert arr[1, 2] == 0
    assert arr[2, 1] == 0
    assert arr[3, 3] == 1


def test_resolve_exclusion_seed_finds_nearest_positive_cell_within_radius() -> None:
    arr = np.zeros((10, 10), dtype=np.uint8)
    arr[4, 6] = 90

    seed = _resolve_exclusion_seed(arr, 4, 4, search_radius=3)

    assert seed == (6, 4)


def test_apply_component_exclusions_removes_nearest_component_and_logs() -> None:
    arr = np.zeros((12, 12), dtype=np.uint8)
    arr[5, 7] = 90
    arr[5, 8] = 90
    arr[6, 7] = 90
    messages: list[str] = []

    _apply_component_exclusions(
        arr,
        {
            "exclude_nearest_components_minecraft": [
                {"x": -4, "z": -5, "search_radius": 4},
            ]
        },
        _world(),
        "copper",
        messages,
    )

    assert not arr.any()
    assert len(messages) == 1
    assert "removed 3 cells" in messages[0]


def test_apply_component_exclusions_reports_when_nothing_is_found() -> None:
    arr = np.zeros((12, 12), dtype=np.uint8)
    messages: list[str] = []

    _apply_component_exclusions(
        arr,
        {
            "exclude_nearest_components_minecraft": [
                {"x": 0, "z": 0, "search_radius": 2},
            ]
        },
        _world(),
        "copper",
        messages,
    )

    assert messages == [
        "[yellow]copper: no positive component found within 2 blocks of (0, 0); nothing removed.[/yellow]"
    ]
