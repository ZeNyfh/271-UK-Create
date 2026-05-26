from ukgeo.coal import _coal_layers, _score_coal_feature


def test_score_coal_resource_depth_classes():
    assert _score_coal_feature("Coal-bearing strata", "Coal bearing strata at surface") == 255
    assert _score_coal_feature("Coal-bearing strata", "Concealed coal bearing strata < 1200m from surface datum") == 190
    assert _score_coal_feature("Coal-bearing strata", "Concealed coal bearing strata > 1200m from surface datum") == 120

def test_only_coal_bearing_strata_layer_is_selected(monkeypatch):
    monkeypatch.setattr(
        "ukgeo.coal.fiona.listlayers",
        lambda _: [
            "Coal-bearing strata",
            "Area greater than 1200m from surface with potential for CO2 sequestration",
            "Coalbed methane (CBM) resource area",
            "Coastline_GB",
            "layer_styles",
        ],
    )

    assert _coal_layers("unused") == ["Coal-bearing strata"]
