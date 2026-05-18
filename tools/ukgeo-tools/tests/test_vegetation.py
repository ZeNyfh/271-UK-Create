from ukgeo.vegetation import LCM_TO_VEGETATION, VEGETATION_CLASSES


def test_lcm_classes_map_to_runtime_vegetation_classes():
    assert int(LCM_TO_VEGETATION[1]) == 1
    assert int(LCM_TO_VEGETATION[2]) == 2
    assert int(LCM_TO_VEGETATION[3]) == 3
    assert int(LCM_TO_VEGETATION[8]) == 8
    assert int(LCM_TO_VEGETATION[11]) == 8
    assert int(LCM_TO_VEGETATION[19]) == 8
    assert int(LCM_TO_VEGETATION[14]) == 10
    assert int(LCM_TO_VEGETATION[20]) == 11
    assert int(LCM_TO_VEGETATION[21]) == 11
    assert int(LCM_TO_VEGETATION[15]) == 12
    assert int(LCM_TO_VEGETATION[18]) == 12
    assert int(LCM_TO_VEGETATION[13]) == 0


def test_runtime_vegetation_classes_have_names_and_colors():
    for class_id in range(13):
        meta = VEGETATION_CLASSES[class_id]
        assert meta["name"]
        assert meta["color"].startswith("#")
