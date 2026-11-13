package com.zenyfh.enginebalance;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EngineBalanceConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.DoubleValue LARGE_DIESEL_ENGINE_FUEL_CONSUMPTION_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue DISABLE_FREE_CONTRAPTION_ACTORS;
    public static final ModConfigSpec.DoubleValue FREE_CONTRAPTION_ACTOR_SPEED_FACTOR;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("dieselEngines");
        LARGE_DIESEL_ENGINE_FUEL_CONSUMPTION_MULTIPLIER = builder
                .comment("Multiplier applied only to Create Diesel Generators fixed diesel engine fuel drain. Portable engines and all other fuel users are unaffected.")
                .defineInRange("largeDieselEngineFuelConsumptionMultiplier", 5.0D, 1.0D, 100.0D);
        builder.pop();
        builder.push("contraptions");
        DISABLE_FREE_CONTRAPTION_ACTORS = builder
                .comment("When true, Create movement actors (drills, saws, harvesters, ploughs, rollers, deployers, etc.) on minecart or train contraptions are cumulatively speed-nerfed instead of running at full free power. Bearing/piston/pulley/gantry/elevator contraptions are unaffected.")
                .define("disableFreeContraptionActors", true);
        FREE_CONTRAPTION_ACTOR_SPEED_FACTOR = builder
                .comment("Per-actor multiplicative retention f for free minecart/train contraptions when disableFreeContraptionActors is true. Vehicle motion leftover = f^N (N = enabled actors; trains sum across carriages). Each actor's work rate = leftover/N. Many actors approach standstill. Bearing/piston/pulley/gantry/elevator contraptions are unaffected. 1.0 = vanilla Create speed.")
                .defineInRange("freeContraptionActorSpeedFactor", 0.70D, 0.01D, 1.0D);
        builder.pop();
        SERVER_SPEC = builder.build();
    }

    private EngineBalanceConfig() {
    }

    public static int scaleFixedDieselDrain(int amount) {
        if (amount <= 0) {
            return amount;
        }
        double multiplier = LARGE_DIESEL_ENGINE_FUEL_CONSUMPTION_MULTIPLIER.get();
        if (multiplier <= 1.0D) {
            return amount;
        }
        return Math.max(amount, (int) Math.ceil(amount * multiplier));
    }

    public static boolean shouldNerfFreeContraptionActors(Object contraption) {
        if (contraption == null || !DISABLE_FREE_CONTRAPTION_ACTORS.get()) {
            return false;
        }
        String n = contraption.getClass().getName();
        return n.endsWith(".MountedContraption") || n.endsWith(".CarriageContraption")
                || n.equals("com.simibubi.create.content.contraptions.mounted.MountedContraption")
                || n.equals("com.simibubi.create.content.trains.entity.CarriageContraption");
    }

    public static double freeContraptionActorSpeedFactor() {
        if (!DISABLE_FREE_CONTRAPTION_ACTORS.get()) {
            return 1.0D;
        }
        return FREE_CONTRAPTION_ACTOR_SPEED_FACTOR.get();
    }
}
