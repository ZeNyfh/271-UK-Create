from ukgeo.asc import parse_asc_header


def test_parse_asc_header():
    header = parse_asc_header(
        [
            "ncols 200\n",
            "nrows 200\n",
            "xllcorner 100000\n",
            "yllcorner 200000\n",
            "cellsize 50\n",
            "NODATA_value -9999\n",
        ]
    )
    assert header.ncols == 200
    assert header.nrows == 200
    assert header.xllcorner == 100000
    assert header.yllcorner == 200000
    assert header.cellsize == 50

