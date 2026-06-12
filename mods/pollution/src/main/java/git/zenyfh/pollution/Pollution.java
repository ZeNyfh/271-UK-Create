package git.zenyfh.pollution;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Pollution.MODID)
public final class Pollution {
    public static final String MODID = "pollution";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public Pollution(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, PollutionConfig.SERVER_SPEC);
        NeoForge.EVENT_BUS.register(new PollutionManager());
    }
}
