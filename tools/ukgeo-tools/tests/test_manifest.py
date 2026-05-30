from ukgeo.manifest import default_manifest, validate_manifest


def test_default_manifest_valid():
    manifest = default_manifest()
    assert validate_manifest(manifest) == []
    assert manifest["world"]["padded_width"] == 25088
    assert manifest["world"]["padded_depth"] == 50176
    assert manifest["world"]["minecraft_min_x"] == -17588
    assert manifest["world"]["minecraft_min_z"] == -36925
