# Data Files

Large source and generated datasets are stored with Git LFS.

## Landcover (FME)

`FME_3564346A_1778997494261_5633.zip` is larger than GitHub's per-object LFS limit,
so it is committed as split LFS parts:

```sh
cat FME_3564346A_1778997494261_5633.zip.part* > FME_3564346A_1778997494261_5633.zip
```

The reconstructed `.zip` is ignored by Git and should not be committed directly.

## UK world tiles (`uk_world_data_gb`)

Runtime Minecraft / ukgeo tile data is published as:

- `uk_world_data_gb.zip.part000` + `uk_world_data_gb.zip.part001` (split for the 2 GiB LFS limit)
- Reconstruct, then unpack into `tools/ukgeo-tools/`:

```sh
cat data/uk_world_data_gb.zip.part* > data/uk_world_data_gb.zip
unzip -o data/uk_world_data_gb.zip -d tools/ukgeo-tools/
```

Do **not** commit individual `.u8rg` / `.r16rg` tiles.

## Hover previews

Website preview assets are a single archive:

```sh
unzip -o data/uk_world_data_gb_hoverpreviews.zip -d tools/ukgeo-tools/uk_world_data_gb/
```

That writes `tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/`. The GitHub Pages
workflow unpacks this zip during deploy.
