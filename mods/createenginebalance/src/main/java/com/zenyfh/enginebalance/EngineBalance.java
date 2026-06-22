package com.zenyfh.enginebalance;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(EngineBalance.MODID)
public final class EngineBalance {
    public static final String MODID = "enginebalance";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public EngineBalance(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, EngineBalanceConfig.SERVER_SPEC);
    }
}
