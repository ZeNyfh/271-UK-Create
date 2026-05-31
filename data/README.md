# Data Files

Large source datasets are stored with Git LFS.

`FME_3564346A_1778997494261_5633.zip` is larger than GitHub's per-object LFS limit,
so it is committed as split LFS parts:

```sh
cat FME_3564346A_1778997494261_5633.zip.part* > FME_3564346A_1778997494261_5633.zip
```

The reconstructed `.zip` is ignored by Git and should not be committed directly.
