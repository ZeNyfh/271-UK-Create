from __future__ import annotations

from pathlib import Path

from ukgeo.config import get_config_value, parse_simple_yaml, shell_assignments


def test_parse_simple_yaml_supports_nested_mappings_and_scalars():
    parsed = parse_simple_yaml(
        """
runtime:
  UKGEO_TILE_COMPRESSION: gzip
  ENABLED: true
  COUNT: 4
  OFFSET: -2
"""
    )

    assert parsed == {
        "runtime": {
            "UKGEO_TILE_COMPRESSION": "gzip",
            "ENABLED": True,
            "COUNT": 4,
            "OFFSET": -2,
        }
    }


def test_shell_assignments_exports_flat_section(tmp_path: Path):
    config = tmp_path / "config.yml"
    config.write_text(
        """
section:
  FOO: bar
  FLAG: true
  EMPTY: null
""",
        encoding="utf-8",
    )

    assert shell_assignments(config, "section").splitlines() == [
        "FOO=bar",
        "FLAG=true",
        "EMPTY=''",
    ]
    assert get_config_value(config, "section.FOO") == "bar"
