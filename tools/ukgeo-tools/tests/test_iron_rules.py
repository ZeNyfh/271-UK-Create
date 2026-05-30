from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_50k_iron_rules_use_real_regional_layers_not_supplemental_pockets():
    text = (ROOT / "examples" / "ore_rules.yml").read_text(encoding="utf-8")

    assert "supplemental_occurrences" not in text
    assert "gb_50k_bedrock_V9" in text
    assert "gb_50k_linear_V9" in text
    assert "gb_50k_superficial_V9" in text


def test_50k_iron_rules_include_major_mapped_ironstone_units():
    text = (ROOT / "examples" / "ore_rules.yml").read_text(encoding="utf-8").lower()

    for keyword in [
        "cleveland ironstone",
        "frodingham ironstone",
        "northampton sand formation",
        "marlstone rock formation",
        "claxby ironstone",
        "wadhurst clay formation",
        "main ironstone seam",
        "pecten ironstone member",
    ]:
        assert keyword in text
