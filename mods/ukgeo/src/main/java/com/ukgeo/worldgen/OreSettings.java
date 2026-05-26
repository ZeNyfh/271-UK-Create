package com.ukgeo.worldgen;

import java.util.List;

public final class OreSettings {
    private static final String BACKGROUND_ONLY = "";

    private OreSettings() {
    }

    public static List<OreDefinition> defaults() {
        return List.of(
            new OreDefinition("coal", "coal", "minecraft:coal_ore", "minecraft:deepslate_coal_ore", 1, 14, -64, 320, 17),
            new OreDefinition("iron", "iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", 1, 10, -64, 72, 9),
            new OreDefinition("copper", "copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore", 1, 10, -16, 112, 10),
            new OreDefinition("zinc", "zinc", "create:zinc_ore", "create:deepslate_zinc_ore", 1, 14, -64, 200, 12),
            new OreDefinition("gold", "gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore", 0, 5, -64, 32, 5),
            new OreDefinition("redstone", BACKGROUND_ONLY, "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", 8, 0, -96, 64, 8),
            new OreDefinition("lapis", BACKGROUND_ONLY, "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore", 2, 0, -64, 64, 7),
            new OreDefinition("diamond", BACKGROUND_ONLY, "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", 4, 0, -64, 16, 6),
            new OreDefinition("emerald", BACKGROUND_ONLY, "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", 2, 0, -16, 320, 3),
            new OreDefinition("andesite", "andesite", "minecraft:andesite", "minecraft:andesite", 0, 18, -64, 320, 28),
            new OreDefinition("diorite", "diorite", "minecraft:diorite", "minecraft:diorite", 0, 14, -64, 320, 24),
            new OreDefinition("granite", "granite", "minecraft:granite", "minecraft:granite", 0, 18, -64, 320, 30),
            new OreDefinition("ochrum", "ochrum", "create:ochrum", "create:ochrum", 0, 14, -64, 320, 24),
            new OreDefinition("calcite", "calcite", "minecraft:calcite", "minecraft:calcite", 0, 14, -64, 320, 18),
            new OreDefinition("scoria", "scoria", "create:scoria", "create:scoria", 0, 14, -64, 320, 24),
            new OreDefinition("tuff", "tuff", "minecraft:tuff", "minecraft:tuff", 0, 16, -64, 320, 28),
            new OreDefinition("crimsite", "crimsite", "create:crimsite", "create:crimsite", 0, 14, -64, 320, 24),
            new OreDefinition("limestone", "limestone", "create:limestone", "create:limestone", 0, 16, -64, 320, 30),
            new OreDefinition("asurine", "asurine", "create:asurine", "create:asurine", 0, 12, -64, 320, 22),
            new OreDefinition("veridium", "veridium", "create:veridium", "create:veridium", 0, 12, -64, 320, 22),
            new OreDefinition("smooth_basalt", "smooth_basalt", "minecraft:smooth_basalt", "minecraft:smooth_basalt", 0, 16, -64, 320, 28)
        );
    }
}
