# Iron ore source data notes

The historic iron map should be handled as regional geology, not as hand-drawn
point buffers. The preferred source is the full **BGS Geology 50k** GeoPackage.

## What is in the BGS 50k layers?

The BGS 50k bedrock theme contains the main regional ironstone units needed by
the reference map. The public BGS ArcGIS layer metadata
(<https://map.bgs.ac.uk/arcgis/rest/services/BGS_Detailed_Geology/MapServer/4>)
includes entries such as:

- `Northampton Sand Formation-Ooid-ironstone`
- `Northampton Sand Formation-Ferruginous sandstone and ironstone`
- `Main Ironstone Seam-Ironstone`
- `Marlstone Rock Formation-Ooid-ironstone`
- `Marlstone Rock Formation-Ferruginous sandstone and ironstone`
- `Pecten Ironstone Member-Ironstone`
- `Wadhurst Clay Formation-Ironstone`

BGS also documents the Cleveland Ironstone Formation
(<https://webapps.bgs.ac.uk/lexicon/lexicon.cfm?pub=CDI>) as a mapped
lithostratigraphic unit with ironstone seams and lists the 1:50k map sheets on
which it appears. The BGS Geology product page
(<https://www.bgs.ac.uk/datasets/bgs-geology/>) states that the 50k dataset
covers almost all of Great Britain and gives detailed local-to-regional geology,
while linear features can include rock lines such as ironstone beds.

## Recommended input

Use the full DiGMapGB-50 / BGS Geology 50k GeoPackage when generating production
ore layers:

```bash
.venv/bin/ukgeo make-ore-tiles \
  --bgs ../../data/BGS_Geology_50k_GeoPackage.zip \
  --rules examples/ore_rules.yml \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --jobs 4
```

The checked-in `data/BGS_Geology_50k_GeoPackage_SAMPLE.zip` is a Git LFS pointer
in this checkout, not an inspectable GeoPackage payload. Fetch LFS objects or
provide the full BGS download before validating local layer contents with:

```bash
.venv/bin/ukgeo inspect-bgs ../../data/BGS_Geology_50k_GeoPackage.zip
```

## If you need a separate mining-hazard polygon dataset

BGS's **Mining Hazard (not including coal) GB** dataset is a better whole-area
fallback than manually digitised circles if the geology package is unavailable or
if you specifically want legacy workings/hazard extents. BGS describes that
product (<https://earthwise.bgs.ac.uk/index.php/OR/14/037_Technical_information>)
as polygons derived from DiGMapGB-50 plus expert knowledge and literature, and
its bedded-ore category includes iron ores.
