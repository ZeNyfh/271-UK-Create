package com.ukgeo.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ukgeo.worldgen.geo.UkGeoReference;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TileManifest {
    public static final String DEFAULT_HEIGHT_EXTENSION = ".r16";
    public static final String DEFAULT_U8_EXTENSION = ".u8";

    public final Path root;
    public final int tileSize;
    public final int width;
    public final int depth;
    public final int paddedWidth;
    public final int paddedDepth;
    public final int minecraftMinX;
    public final int minecraftMinZ;
    public final int minecraftMaxX;
    public final int minecraftMaxZ;
    public final String crs;
    public final double bngMinEasting;
    public final double bngMinNorthing;
    public final double bngMaxEasting;
    public final double bngMaxNorthing;
    public final int seaLevelY;
    public final String heightPath;
    public final String heightExtension;
    public final Map<String, String> orePaths;
    public final Map<String, String> oreExtensions;
    public final String surfaceGeologyPath;
    public final String surfaceGeologyExtension;
    public final Map<Integer, SurfaceGeologyClass> surfaceGeologyClasses;
    public final String vegetationPath;
    public final String vegetationExtension;
    public final int vegetationCellBlocks;
    public final Map<Integer, VegetationClass> vegetationClasses;
    public final String biomeRegionsPath;
    public final String biomeRegionsExtension;
    public final int biomeRegionsCellBlocks;
    public final Map<Integer, VegetationClass> biomeRegionClasses;
    public final String riversPath;
    public final String riversExtension;
    public final String riverOrderPath;
    public final String riverHalfWidthPath;
    public final int maxRiverHalfWidth;

    private TileManifest(Path root, JsonObject json) {
        this.root = root;
        this.tileSize = json.get("tile_size").getAsInt();
        JsonObject world = json.getAsJsonObject("world");
        this.width = world.get("width").getAsInt();
        this.depth = world.get("depth").getAsInt();
        this.paddedWidth = world.get("padded_width").getAsInt();
        this.paddedDepth = world.get("padded_depth").getAsInt();
        this.minecraftMinX = world.get("minecraft_min_x").getAsInt();
        this.minecraftMinZ = world.get("minecraft_min_z").getAsInt();
        this.minecraftMaxX = world.get("minecraft_max_x").getAsInt();
        this.minecraftMaxZ = world.get("minecraft_max_z").getAsInt();
        JsonObject georeferencing = json.getAsJsonObject("georeferencing");
        this.crs = georeferencing != null && georeferencing.has("crs") ? georeferencing.get("crs").getAsString() : "EPSG:27700";
        this.bngMinEasting = optionalDouble(georeferencing, "bng_min_easting");
        this.bngMinNorthing = optionalDouble(georeferencing, "bng_min_northing");
        this.bngMaxEasting = optionalDouble(georeferencing, "bng_max_easting");
        this.bngMaxNorthing = optionalDouble(georeferencing, "bng_max_northing");
        JsonObject height = json.getAsJsonObject("height");
        this.seaLevelY = height.get("sea_level_y").getAsInt();
        this.heightPath = height.get("path").getAsString();
        this.heightExtension = extension(height, DEFAULT_HEIGHT_EXTENSION);
        this.surfaceGeologyClasses = new LinkedHashMap<>();
        JsonObject surface = json.getAsJsonObject("surface_geology");
        if (surface != null) {
            this.surfaceGeologyPath = surface.get("path").getAsString();
            this.surfaceGeologyExtension = extension(surface, DEFAULT_U8_EXTENSION);
            JsonObject classes = surface.getAsJsonObject("classes");
            if (classes != null) {
                for (Map.Entry<String, JsonElement> entry : classes.entrySet()) {
                    int id = Integer.parseInt(entry.getKey());
                    JsonObject value = entry.getValue().getAsJsonObject();
                    String name = value.has("name") ? value.get("name").getAsString() : entry.getKey();
                    String block = value.has("block") ? value.get("block").getAsString() : "minecraft:stone";
                    String fallback = value.has("fallback_block") ? value.get("fallback_block").getAsString() : "minecraft:stone";
                    this.surfaceGeologyClasses.put(id, new SurfaceGeologyClass(id, name, block, fallback));
                }
            }
        } else {
            this.surfaceGeologyPath = null;
            this.surfaceGeologyExtension = DEFAULT_U8_EXTENSION;
        }
        this.vegetationClasses = new LinkedHashMap<>();
        JsonObject vegetation = json.getAsJsonObject("vegetation");
        if (vegetation != null) {
            this.vegetationPath = vegetation.get("path").getAsString();
            this.vegetationExtension = extension(vegetation, DEFAULT_U8_EXTENSION);
            this.vegetationCellBlocks = vegetation.has("cell_blocks") ? Math.max(1, vegetation.get("cell_blocks").getAsInt()) : 1;
            this.vegetationClasses.putAll(parseVegetationClasses(vegetation));
        } else {
            this.vegetationPath = null;
            this.vegetationExtension = DEFAULT_U8_EXTENSION;
            this.vegetationCellBlocks = 1;
        }
        this.biomeRegionClasses = new LinkedHashMap<>();
        JsonObject biomeRegions = json.getAsJsonObject("biome_regions");
        if (biomeRegions != null) {
            this.biomeRegionsPath = biomeRegions.get("path").getAsString();
            this.biomeRegionsExtension = extension(biomeRegions, DEFAULT_U8_EXTENSION);
            this.biomeRegionsCellBlocks = biomeRegions.has("cell_blocks") ? Math.max(1, biomeRegions.get("cell_blocks").getAsInt()) : this.vegetationCellBlocks;
            this.biomeRegionClasses.putAll(parseVegetationClasses(biomeRegions));
        } else {
            this.biomeRegionsPath = null;
            this.biomeRegionsExtension = DEFAULT_U8_EXTENSION;
            this.biomeRegionsCellBlocks = 1;
        }
        JsonObject rivers = json.getAsJsonObject("rivers");
        if (rivers == null) {
            this.riversPath = null;
            this.riversExtension = DEFAULT_U8_EXTENSION;
            this.riverOrderPath = null;
            this.riverHalfWidthPath = null;
            this.maxRiverHalfWidth = 0;
        } else {
            this.riversPath = rivers.get("path").getAsString();
            this.riversExtension = extension(rivers, DEFAULT_U8_EXTENSION);
            this.riverOrderPath = rivers.has("order_path") ? rivers.get("order_path").getAsString() : null;
            this.riverHalfWidthPath = rivers.has("half_width_path") ? rivers.get("half_width_path").getAsString() : null;
            this.maxRiverHalfWidth = rivers.has("max_half_width") ? Math.max(0, rivers.get("max_half_width").getAsInt()) : 0;
        }
        this.orePaths = new LinkedHashMap<>();
        this.oreExtensions = new LinkedHashMap<>();
        JsonObject ores = json.getAsJsonObject("ore_layers");
        if (ores != null) {
            for (Map.Entry<String, JsonElement> entry : ores.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                this.orePaths.put(entry.getKey(), value.get("path").getAsString());
                this.oreExtensions.put(entry.getKey(), extension(value, DEFAULT_U8_EXTENSION));
            }
        }
    }

    public static TileManifest load(Path root) throws IOException {
        Path path = root.resolve("manifest.json");
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"uk-raster-tiles-v1".equals(json.get("format").getAsString())) {
                throw new IOException("Unsupported ukgeo tile format");
            }
            return new TileManifest(root, json);
        }
    }

    public int tilesX() {
        return paddedWidth / tileSize;
    }

    public int tilesZ() {
        return paddedDepth / tileSize;
    }

    public String u8ExtensionFor(String layerName) {
        return switch (layerName) {
            case "surface_geology" -> surfaceGeologyExtension;
            case "vegetation" -> vegetationExtension;
            case "biome_regions" -> biomeRegionsExtension;
            case "rivers", "river_order", "river_half_width" -> riversExtension;
            default -> oreExtensions.getOrDefault(layerName, DEFAULT_U8_EXTENSION);
        };
    }

    public String originSummary() {
        if (Double.isNaN(bngMinEasting) || Double.isNaN(bngMinNorthing) || Double.isNaN(bngMaxEasting) || Double.isNaN(bngMaxNorthing)) {
            return "BNG unavailable";
        }
        double dataX = 0 - minecraftMinX;
        double dataZ = 0 - minecraftMinZ;
        double easting = bngMinEasting + (dataX + 0.5D) * (bngMaxEasting - bngMinEasting) / width;
        double northing = bngMaxNorthing - (dataZ + 0.5D) * (bngMaxNorthing - bngMinNorthing) / depth;
        return "BNG E %.0f N %.0f".formatted(easting, northing);
    }

    public UkGeoReference toReference() {
        return new UkGeoReference(
            crs,
            minecraftMinX,
            minecraftMinZ,
            minecraftMaxX,
            minecraftMaxZ,
            bngMinEasting,
            bngMinNorthing,
            bngMaxEasting,
            bngMaxNorthing,
            width,
            depth
        );
    }

    private static String extension(JsonObject object, String fallback) {
        if (object == null || !object.has("extension") || object.get("extension").isJsonNull()) {
            return fallback;
        }
        String value = object.get("extension").getAsString();
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double optionalDouble(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return Double.NaN;
        }
        return object.get(key).getAsDouble();
    }

    private static Map<Integer, VegetationClass> parseVegetationClasses(JsonObject layer) {
        Map<Integer, VegetationClass> result = new LinkedHashMap<>();
        JsonObject classes = layer.getAsJsonObject("classes");
        if (classes != null) {
            for (Map.Entry<String, JsonElement> entry : classes.entrySet()) {
                int id = Integer.parseInt(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                String name = value.has("name") ? value.get("name").getAsString() : entry.getKey();
                String color = value.has("color") ? value.get("color").getAsString() : "#777777";
                result.put(id, new VegetationClass(id, name, color));
            }
        }
        return result;
    }
}
