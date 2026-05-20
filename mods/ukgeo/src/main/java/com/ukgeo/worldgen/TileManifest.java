package com.ukgeo.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TileManifest {
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
    public final int seaLevelY;
    public final String heightPath;
    public final Map<String, String> orePaths;
    public final String surfaceGeologyPath;
    public final Map<Integer, SurfaceGeologyClass> surfaceGeologyClasses;
    public final String vegetationPath;
    public final int vegetationCellBlocks;
    public final Map<Integer, VegetationClass> vegetationClasses;
    public final String riversPath;

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
        JsonObject height = json.getAsJsonObject("height");
        this.seaLevelY = height.get("sea_level_y").getAsInt();
        this.heightPath = height.get("path").getAsString();
        this.surfaceGeologyClasses = new LinkedHashMap<>();
        JsonObject surface = json.getAsJsonObject("surface_geology");
        if (surface != null) {
            this.surfaceGeologyPath = surface.get("path").getAsString();
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
        }
        this.vegetationClasses = new LinkedHashMap<>();
        JsonObject vegetation = json.getAsJsonObject("vegetation");
        if (vegetation != null) {
            this.vegetationPath = vegetation.get("path").getAsString();
            this.vegetationCellBlocks = vegetation.has("cell_blocks") ? Math.max(1, vegetation.get("cell_blocks").getAsInt()) : 1;
            JsonObject classes = vegetation.getAsJsonObject("classes");
            if (classes != null) {
                for (Map.Entry<String, JsonElement> entry : classes.entrySet()) {
                    int id = Integer.parseInt(entry.getKey());
                    JsonObject value = entry.getValue().getAsJsonObject();
                    String name = value.has("name") ? value.get("name").getAsString() : entry.getKey();
                    String color = value.has("color") ? value.get("color").getAsString() : "#777777";
                    this.vegetationClasses.put(id, new VegetationClass(id, name, color));
                }
            }
        } else {
            this.vegetationPath = null;
            this.vegetationCellBlocks = 1;
        }
        JsonObject rivers = json.getAsJsonObject("rivers");
        this.riversPath = rivers == null ? null : rivers.get("path").getAsString();
        this.orePaths = new LinkedHashMap<>();
        JsonObject ores = json.getAsJsonObject("ore_layers");
        if (ores != null) {
            for (Map.Entry<String, JsonElement> entry : ores.entrySet()) {
                this.orePaths.put(entry.getKey(), entry.getValue().getAsJsonObject().get("path").getAsString());
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
}
