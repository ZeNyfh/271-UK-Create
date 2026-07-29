import numpy as np

from ukgeo.clc import classify_clc_rgba
from ukgeo.egdi import classify_lithology_text


def test_classify_clc_water_body_color_as_freshwater():
    rgba = np.array([[[128, 242, 230, 255]]], dtype=np.uint8)
    assert int(classify_clc_rgba(rgba)[0, 0]) == 10


def test_classify_clc_sea_color_as_ocean():
    rgba = np.array([[[230, 242, 255, 255]]], dtype=np.uint8)
    assert int(classify_clc_rgba(rgba)[0, 0]) == 0


def test_classify_clc_transparent_as_none():
    rgba = np.array([[[128, 242, 230, 0]]], dtype=np.uint8)
    assert int(classify_clc_rgba(rgba)[0, 0]) == 0


def test_classify_egdi_irish_lithology_terms():
    assert classify_lithology_text("representativeLithology peat") == 12
    assert classify_lithology_text("diamicton till") == 11
    assert classify_lithology_text("granitoid") == 3
    assert classify_lithology_text("doleriticRock") == 4
    assert classify_lithology_text("sandstone") == 8
