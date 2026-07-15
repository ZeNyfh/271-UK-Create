from __future__ import annotations

from pathlib import Path
from collections.abc import Mapping

from rich.console import Console

from .manifest import default_u8_layer, read_manifest, write_manifest
from .ore_image_overlay import _full_source_bbox, _full_target_bbox, _merge_mask_into_u8_layer, _read_named_svg_mask
from .tiles import u8_extension

console = Console()

DEFAULT_ANIMAL_SVG_LAYER_BY_ENTITY: dict[str, str] = {
    "wildernature:deer": "Deer",
    "wildernature:bison": "Bison",
    "wildernature:boar": "WildBoar-Pig",
    "wildernature:hedgehog": "Hedgehogs",
    "wildernature:minisheep": "Mini-Sheep",
    "wildernature:owl": "Owls-Bats-Bees-Sheep-Cows",
    "wildernature:squirrel": "Squirrels",
    "minecraft:bat": "Owls-Bats-Bees-Sheep-Cows",
    "minecraft:cow": "Owls-Bats-Bees-Sheep-Cows",
    "minecraft:sheep": "Owls-Bats-Bees-Sheep-Cows",
    "minecraft:pig": "WildBoar-Pig",
    "minecraft:chicken": "Chckens",
    "minecraft:rabbit": "Frogs-Rabbits",
    "minecraft:wolf": "GrayWolf-WildCat",
    "minecraft:fox": "Foxes",
}


def make_animal_habitat_tiles(
    *,
    image: Path,
    manifest_path: Path,
    out: Path,
    mappings: Mapping[str, str] | None = None,
    fit: str = "full-frame",
    svg_raster_scale: int = 1,
) -> None:
    manifest = read_manifest(manifest_path)
    mapping_table = dict(DEFAULT_ANIMAL_SVG_LAYER_BY_ENTITY if mappings is None else mappings)
    entities_by_path: dict[str, list[str]] = {}
    for entity_id, path_id in mapping_table.items():
        entities_by_path.setdefault(path_id, []).append(entity_id)
    target_bbox = _full_target_bbox(manifest) if fit == "full-frame" else None
    fit_mode = "outline" if fit == "full-frame" else fit

    entity_entries: dict[str, dict[str, object]] = {}
    for path_id, entity_ids in entities_by_path.items():
        mask = _read_named_svg_mask(image, path_id=path_id, svg_raster_scale=svg_raster_scale)
        source_bbox = _full_source_bbox(mask)
        for entity_id in entity_ids:
            namespace, _, entity_path = entity_id.partition(":")
            if not namespace or not entity_path:
                raise ValueError(f"Invalid entity id: {entity_id}")
            layer_path = f"animals/habitats/{namespace}/{entity_path}"
            changed_tiles, _placement = _merge_mask_into_u8_layer(
                mask=mask,
                manifest=manifest,
                out=out,
                layer_name=entity_id,
                layer_path=layer_path,
                layer_extension=u8_extension(),
                score=255,
                fit=fit_mode,
                source_bbox=source_bbox,
                target_bbox=target_bbox,
                control_matrix=None,
                desc=f"{entity_id} habitat rows",
            )
            entity_entries[entity_id] = {
                **default_u8_layer(layer_path),
                "svg_path_id": path_id,
                "fit": fit,
                "svg_raster_scale": int(svg_raster_scale),
                "source": str(image),
            }
            console.print(f"{entity_id}: wrote habitat mask from {path_id} in {changed_tiles} tiles")

    manifest["animal_habitats"] = {
        "entities": entity_entries,
        "note": "Animal habitat masks are rasterized from the British Isles animal SVG and sampled by ukgeo-animals at spawn time.",
    }
    write_manifest(manifest_path, manifest)
