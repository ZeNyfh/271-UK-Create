from ukgeo.manifest import default_manifest, validate_manifest


def test_default_manifest_valid():
    manifest = default_manifest()
    assert validate_manifest(manifest) == []
    assert manifest["world"]["padded_width"] == 25088
    assert manifest["world"]["padded_depth"] == 50176

