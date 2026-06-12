package git.zenyfh.pollution;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class PollutionSourceDetector {
    private static final double LOW = 1.0;
    private static final double MEDIUM = 3.0;
    private static final double HIGH = 8.0;
    private static final double VERY_HIGH = 20.0;

    private static final Map<String, Double> FIXED_RATES = Map.ofEntries(
            Map.entry("minecraft:furnace", MEDIUM),
            Map.entry("minecraft:smoker", MEDIUM),
            Map.entry("minecraft:blast_furnace", HIGH),
            Map.entry("minecraft:campfire", LOW),
            Map.entry("minecraft:soul_campfire", LOW),
            Map.entry("farmersdelight:stove", MEDIUM),
            Map.entry("farmersdelight:cooking_pot", LOW),
            Map.entry("farmersdelight:skillet", LOW),
            Map.entry("create:blaze_burner", HIGH),
            Map.entry("create:encased_fan", MEDIUM),
            Map.entry("create:crushing_wheel", MEDIUM),
            Map.entry("create:millstone", LOW),
            Map.entry("create:mechanical_drill", MEDIUM),
            Map.entry("create:mechanical_saw", MEDIUM),
            Map.entry("create:mechanical_press", LOW),
            Map.entry("create:mechanical_mixer", MEDIUM),
            Map.entry("create:steam_engine", HIGH),
            Map.entry("createdieselgenerators:diesel_engine", VERY_HIGH),
            Map.entry("createdieselgenerators:large_diesel_engine", VERY_HIGH * 1.5),
            Map.entry("createdieselgenerators:diesel_generator", VERY_HIGH),
            Map.entry("createdieselgenerators:pumpjack", HIGH),
            Map.entry("createdieselgenerators:oil_pump", HIGH),
            Map.entry("createdieselgenerators:distillation_controller", VERY_HIGH),
            Map.entry("createdieselgenerators:oil_refinery", VERY_HIGH),
            Map.entry("createdieselgenerators:refinery", VERY_HIGH),
            Map.entry("createdieselgenerators:refinery_controller", VERY_HIGH)
    );

    private static final Set<String> CREATE_KINETIC_SOURCES = Set.of(
            "encased_fan",
            "crushing_wheel",
            "millstone",
            "mechanical_drill",
            "mechanical_saw",
            "mechanical_press",
            "mechanical_mixer",
            "steam_engine"
    );

    private PollutionSourceDetector() {
    }

    public static double emissionRate(Level level, BlockEntity blockEntity) {
        BlockState state = blockEntity.getBlockState();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String key = id.toString();
        Double fixedRate = FIXED_RATES.get(key);
        if (fixedRate == null) {
            fixedRate = inferOptionalModRate(id);
        }
        if (fixedRate == null || !isActive(level, blockEntity, state, id)) {
            return 0.0;
        }
        return applyStateMultiplier(state, id, fixedRate);
    }

    private static Double inferOptionalModRate(ResourceLocation id) {
        String namespace = id.getNamespace();
        String path = id.getPath();
        if ("createbigcannons".equals(namespace)) {
            return null;
        }
        if ("create_aeronautics".equals(namespace) || "create-aeronautics".equals(namespace)) {
            if (path.contains("combust") || path.contains("burner") || path.contains("engine") || path.contains("thruster")) {
                return HIGH;
            }
        }
        if ("createdieselgenerators".equals(namespace)) {
            if (path.contains("diesel") || path.contains("engine") || path.contains("generator")) {
                return VERY_HIGH;
            }
            if (path.contains("pump") || path.contains("jack")) {
                return HIGH;
            }
            if (path.contains("refinery") || path.contains("distillation")) {
                return VERY_HIGH;
            }
        }
        return null;
    }

    private static boolean isActive(Level level, BlockEntity blockEntity, BlockState state, ResourceLocation id) {
        Boolean stateActive = activeFromBlockState(state);
        if (stateActive != null) {
            return stateActive;
        }

        String namespace = id.getNamespace();
        String path = id.getPath();
        if ("create".equals(namespace) && CREATE_KINETIC_SOURCES.contains(path)) {
            return activeFromBlockEntityData(level, blockEntity);
        }
        if ("createdieselgenerators".equals(namespace)
                || "farmersdelight".equals(namespace)
                || "create_aeronautics".equals(namespace)
                || "create-aeronautics".equals(namespace)) {
            return activeFromBlockEntityData(level, blockEntity);
        }
        return false;
    }

    private static Boolean activeFromBlockState(BlockState state) {
        Boolean positiveActiveProperty = null;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            Comparable<?> value = state.getValue(property);
            if ("lit".equals(name) || "active".equals(name) || "running".equals(name)) {
                return booleanLike(value);
            }
            if ("heat_level".equals(name)) {
                String heat = String.valueOf(value).toLowerCase(Locale.ROOT);
                return !heat.equals("none") && !heat.equals("smouldering") && !heat.equals("smoldering");
            }
            if ("powered".equals(name) || "enabled".equals(name)) {
                positiveActiveProperty = booleanLike(value);
            }
        }
        return positiveActiveProperty;
    }

    private static boolean activeFromBlockEntityData(Level level, BlockEntity blockEntity) {
        CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
        return hasPositiveNumeric(tag, "BurnTime")
                || hasPositiveNumeric(tag, "burnTime")
                || hasPositiveNumeric(tag, "Fuel")
                || hasPositiveNumeric(tag, "fuel")
                || hasPositiveNumeric(tag, "Speed")
                || hasPositiveNumeric(tag, "speed")
                || hasPositiveNumeric(tag, "ProcessingTicks")
                || hasPositiveNumeric(tag, "processingTicks")
                || hasPositiveBoolean(tag, "Running")
                || hasPositiveBoolean(tag, "running")
                || hasPositiveBoolean(tag, "Active")
                || hasPositiveBoolean(tag, "active");
    }

    private static double applyStateMultiplier(BlockState state, ResourceLocation id, double baseRate) {
        for (Property<?> property : state.getProperties()) {
            if ("heat_level".equals(property.getName())) {
                String heat = String.valueOf(state.getValue(property)).toLowerCase(Locale.ROOT);
                if (heat.contains("seething") || heat.contains("superheated")) {
                    return baseRate * 1.75;
                }
                if (heat.contains("kindled")) {
                    return baseRate * 1.25;
                }
            }
        }
        if ("createdieselgenerators".equals(id.getNamespace()) && id.getPath().contains("large")) {
            return baseRate * 1.25;
        }
        return baseRate;
    }

    private static boolean hasPositiveNumeric(CompoundTag tag, String key) {
        return tag.contains(key) && tag.getDouble(key) > 0.001;
    }

    private static boolean hasPositiveBoolean(CompoundTag tag, String key) {
        return tag.contains(key) && tag.getBoolean(key);
    }

    private static Boolean booleanLike(Comparable<?> value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
