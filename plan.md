# UKGeo river geometry and surface quality

## Status (current root causes)

Cross-track ownership (`|perp|`) is already baked. The remaining defects are not the old Euclidean Voronoi fan.

### 1. Spiky / triangular banks (highest priority)

**Cause:** Abrupt per-segment station tangents at polyline vertices plus hard oriented-rectangle stamp corners. Neighbouring stamps with different tangents leave angular ownership facets on the banks even when distance is cross-track.

**Fix (Python bake):**
- Blend each station tangent with neighbours (`_smooth_sample_tangents`).
- Rounded-rectangle stamp eligibility (quarter-circle corners via `CROSS_PAD`).
- Align `BANK_CARVE_REACH = 8` with Java runtime.

**Rebuild:** stations-only rebake (`bake-river-stations --resume-tiles`). Distance / half-width / centreline layers unchanged.

### 2. Excessive mountain incision

**Cause:** Absolute baked water Y on highland DEM stamps opened 10–15+ block canyons. The old extreme-drop gate (`EXTREME_CHANNEL_DROP = 48`) left moderate mountain cuts uncapped. Channel cap was applied before bed depth, so total DEM→bed removal exceeded the nominal channel cap.

**Fix (Java runtime):**
- `RiverBankFit.MOUNTAIN_TOTAL_INCISION = 3` includes channel depth.
- Highland gate: `originalTop >= MOUNTAIN_SURFACE_MIN (128)` and drop `> MOUNTAIN_DROP_TRIGGER`.
- Established lowland valleys stay on absolute baked water Y.
- Near-bank mountain incision capped via `MAX_BANK_INCISION_NEAR = 3`.

**Rebuild:** none for data; deploy ukgeo jar + wipe affected chunks / world as usual for terrain changes.

### 3. Blocky river water heights

**Cause:** Flat `water_y` per station is correct, but integer metre→Y conversion plus jagged ownership at bends made adjacent cells jump by more than one block. Runtime local water smoothing stays disabled (baked Y is authoritative).

**Fix (Python bake):**
- Slightly stronger metre profile smooth (`SMOOTH_RADIUS = 3`).
- `smooth_water_y_profile`: gradual ≤1 Y step along a reach; keep drops ≥3 Y as cascades.
- Do not blend across reaches / confluences / lakes.

**Rebuild:** stations-only rebake (updates `water/river_water_y`).

### 4. Isolated surface geology blocks

**Cause:**
- Geology raster can contain 1–3 pixel islands after vector burn-in.
- Runtime `allowRareExposedSurfaceOre` used an independent 5% per-column hash → single-block speckles (DH steep / rare exposures).

**Fix:**
- Python: `remove_isolated_surface_pixels` after burn-in (4-connected, `max_island=3`).
- Java: coherent value-noise patches instead of per-column 5%; steep tops use `exposedSurfaceRock`.

**Rebuild:** `make-surface-geology-tiles` for raster cleanup; jar for exposure logic.

## Dataset regeneration

Safe pattern: write to a new output directory or backup the existing dataset first. Do **not** overwrite live data in place without a backup. Do **not** deploy to the live server or modify an existing world from this plan alone.

### Stations-only (banks + water Y)

```bash
cd tools/ukgeo-tools
TMPDIR=/home/zenyfh/tmp ukgeo bake-river-stations \
  --manifest /path/to/manifest.json \
  --out /path/to/dataset \
  --resume-tiles
```

Requires existing centreline / width / height / river-mask inputs already referenced by the manifest. Workers default to 12; override with `STATION_BAKE_WORKERS`.

### Surface geology

```bash
ukgeo make-surface-geology-tiles \
  --bgs /path/to/bgs \
  --rules /path/to/surface_rules.yaml \
  --manifest /path/to/manifest.json \
  --out /path/to/dataset
```

### Full river rebuild (only if centreline / width / mask change)

Not required for the fixes above. If centreline geometry changes, rebuild mask → width → distance → stations → hoverpreview in that order.

## Out of scope

- Re-deriving OS river centreline geometry.
- Re-enabling runtime local water-Y smoothing across stations.
- Live server deploy / world overwrite from this document.


## Status update (banks / lakes / spawn water)

### Spawn water protrusion `(-255, 72, 380)`
**Cause:** Station `water_y=77` on DEM ≈71 from a false cascade (large integer drop without a metre-profile cascade). Ownership wedge of early stations left a triangle of elevated water.
**Fix:** `smooth_water_y_profile(..., cascade_at=)` only preserves large drops where `CASCADE_DROP_METRES` is met in the metre profile; runtime `clampWaterToLocalSurface` never floats water above the local DEM. Requires stations-only rebake of `water/river_water_y`.

### Uneven / diagonal mountain banks
**Cause:** Chunk-local bank averaging created border seams; mountain edge occupancy jitter created triangular facets inside the shallow incision budget.
**Fix:** Skip occupancy jitter on mountain stamps; replace chunk-local bank blur with world-deterministic talus re-anchor (±1). Keep `MOUNTAIN_TOTAL_INCISION=3`.

### Lake beds
**Cause:** Mask-edge bowl + high-frequency jitter + chunk-local smooth mixing river cells.
**Fix:** `LakeBathymetry` world-coord basin (no jitter); `RiverShape.lake` + lake-only components for residual smooth; rivers excluded from lake bed passes.
