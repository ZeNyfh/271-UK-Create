package git.zenyfh.vanilla_adjustments;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class VanillaAdjustConfig {
    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.BooleanValue ENDER_PEARL_RADIUS_LIMIT_ENABLED;
    public static final ModConfigSpec.IntValue ENDER_PEARL_MAX_RADIUS;
    public static final ModConfigSpec.BooleanValue ENDER_PEARL_LIMIT_USES_HORIZONTAL_DISTANCE;
    public static final ModConfigSpec.EnumValue<EnderPearlLimitBehaviour> ENDER_PEARL_LIMIT_BEHAVIOUR;
    public static final ModConfigSpec.BooleanValue ENDER_PEARL_LIMIT_SEND_MESSAGE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("enderPearlRadiusLimit");
        ENDER_PEARL_RADIUS_LIMIT_ENABLED = builder
                .comment("When true, player-thrown ender pearls cannot teleport farther than enderPearlMaxRadius from their throw position.")
                .define("enderPearlRadiusLimitEnabled", true);
        ENDER_PEARL_MAX_RADIUS = builder
                .comment("Maximum allowed ender pearl travel distance in blocks. Horizontal X/Z distance is used by default.")
                .defineInRange("enderPearlMaxRadius", 256, 1, 30_000);
        ENDER_PEARL_LIMIT_USES_HORIZONTAL_DISTANCE = builder
                .comment("When true, only X/Z distance counts toward the pearl radius limit. When false, full 3D distance is used.")
                .define("enderPearlLimitUsesHorizontalDistance", true);
        ENDER_PEARL_LIMIT_BEHAVIOUR = builder
                .comment("What to do when an ender pearl exceeds the configured radius. CANCEL blocks the teleport safely.")
                .defineEnum("enderPearlLimitBehaviour", EnderPearlLimitBehaviour.CANCEL);
        ENDER_PEARL_LIMIT_SEND_MESSAGE = builder
                .comment("When true, send the player a short actionbar message when an ender pearl teleport is blocked by the radius limit.")
                .define("enderPearlLimitSendMessage", true);
        builder.pop();
        SERVER_SPEC = builder.build();
    }

    private VanillaAdjustConfig() {
    }

    public enum EnderPearlLimitBehaviour {
        CANCEL
    }
}
