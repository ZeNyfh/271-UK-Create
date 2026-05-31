from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def _iron_rules():
    return yaml.safe_load((ROOT / "examples" / "ore_rules.yml").read_text(encoding="utf-8"))["ores"]["iron"]


def test_50k_iron_rules_use_real_regional_layers_not_supplemental_pockets():
    text = (ROOT / "examples" / "ore_rules.yml").read_text(encoding="utf-8")
    iron = _iron_rules()

    assert "supplemental_occurrences" not in text
    assert iron["layers"] == ["gb_50k_bedrock_V9", "gb_50k_linear_V9", "gb_50k_superficial_V9"]
    for field in ["LEX_D", "LEX_RCS_D", "RCS_D", "RANK", "LITH_D", "NAME", "FEATURE_D", "MINERAL_D"]:
        assert field in iron["fields"]


def test_50k_iron_rules_include_major_mapped_ironstone_units():
    iron = _iron_rules()
    keywords = {
        keyword.lower()
        for group in iron["keyword_groups"]
        for keyword in group["keywords"]
    }

    for keyword in [
        "claxby ironstone",
        "cleveland ironstone",
        "frodingham ironstone",
        "northampton sand formation",
        "marlstone rock formation",
        "wadhurst clay formation",
        "raasay ironstone",
        "main ironstone seam",
        "pecten ironstone member",
        "ironstone formation",
        "ironstone member",
        "ooid-ironstone",
        "ooid ironstone",
        "ferruginous sandstone and ironstone",
        "ferruginous limestone and ironstone",
        "mudstone and ironstone",
        "ironstone bed",
        "ironstone",
        "ferruginous",
        "haematite",
        "hematite",
        "magnetite",
        "siderite",
        "sideritic",
    ]:
        assert keyword in keywords


def test_iron_docs_and_readme_point_to_real_bgs_sources():
    docs = (ROOT / "docs" / "iron_ore_data.md").read_text(encoding="utf-8").lower()
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    overview_rules = (ROOT / "examples" / "ore_rules_625k.yml").read_text(encoding="utf-8").lower()

    assert "not as hand-drawn" in docs
    assert "bgs geology 50k" in docs
    assert "git lfs pointer" in docs
    assert "mining hazard" in docs
    assert "northampton sand formation-ooid-ironstone" in docs
    assert "main ironstone seam-ironstone" in docs
    assert "marlstone rock formation-ooid-ironstone" in docs
    assert "pecten ironstone member-ironstone" in docs
    assert "wadhurst clay formation-ironstone" in docs

    assert "../../data/BGS_Geology_50k_GeoPackage.zip" in readme
    assert "--rules examples/ore_rules.yml" in readme
    assert "625k map is a national overview" in overview_rules
    assert "whole-region ironstone belts" in overview_rules
