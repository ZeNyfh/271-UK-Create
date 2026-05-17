package com.ukgeo.worldgen;

import java.util.List;

public final class OreSettings {
    private OreSettings() {
    }

    public static List<OreDefinition> defaults() {
        return List.of(
            new OreDefinition("coal", "coal", "minecraft:coal_ore", "minecraft:deepslate_coal_ore", 1, 14, 10, 180, 17),
            new OreDefinition("iron", "iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", 1, 10, 20, 400, 9),
            new OreDefinition("copper", "copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore", 1, 10, 20, 250, 10),
            new OreDefinition("zinc", "zinc", "create:zinc_ore", "create:deepslate_zinc_ore", 1, 14, 80, 500, 12),
            new OreDefinition("tin", "tin", "minecraft:copper_ore", "minecraft:deepslate_copper_ore", 0, 6, 100, 600, 6),
            new OreDefinition("gold", "gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore", 0, 5, 200, 900, 5),
            new OreDefinition("andesite", "andesite", "minecraft:andesite", "minecraft:andesite", 0, 18, 5, 700, 28),
            new OreDefinition("diorite", "diorite", "minecraft:diorite", "minecraft:diorite", 0, 14, 5, 700, 24),
            new OreDefinition("granite", "granite", "minecraft:granite", "minecraft:granite", 0, 18, 5, 900, 30),
            new OreDefinition("ochrum", "ochrum", "create:ochrum", "create:ochrum", 0, 14, 5, 500, 24),
            new OreDefinition("calcite", "calcite", "minecraft:calcite", "minecraft:calcite", 0, 14, 10, 500, 18),
            new OreDefinition("scoria", "scoria", "create:scoria", "create:scoria", 0, 14, 10, 700, 24),
            new OreDefinition("tuff", "tuff", "minecraft:tuff", "minecraft:tuff", 0, 16, 20, 800, 28),
            new OreDefinition("crimsite", "crimsite", "create:crimsite", "create:crimsite", 0, 14, 10, 650, 24),
            new OreDefinition("limestone", "limestone", "create:limestone", "create:limestone", 0, 16, 5, 450, 30),
            new OreDefinition("asurine", "asurine", "create:asurine", "create:asurine", 0, 12, 20, 700, 22),
            new OreDefinition("veridium", "veridium", "create:veridium", "create:veridium", 0, 12, 20, 700, 22),
            new OreDefinition("smooth_basalt", "smooth_basalt", "minecraft:smooth_basalt", "minecraft:smooth_basalt", 0, 16, 20, 900, 28)
        );
    }
}
