package com.zenyfh.enginebalance;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class EngineBalanceConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.DoubleValue LARGE_DIESEL_ENGINE_FUEL_CONSUMPTION_MULTIPLIER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("dieselEngines");
        LARGE_DIESEL_ENGINE_FUEL_CONSUMPTION_MULTIPLIER = builder
                .comment("Multiplier applied only to Create Diesel Generators fixed diesel engine fuel drain. Portable engines and all other fuel users are unaffected.")
                .defineInRange("largeDieselEngineFuelConsumptionMultiplier", 5.0D, 1.0D, 100.0D);
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
}
