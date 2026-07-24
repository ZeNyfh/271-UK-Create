package com.ukgeo.realtimelocalisedweather;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.ukgeo.realtimelocalisedweather.weather.GameplaySeverity;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.WeatherAuthorityMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class RealtimeWeatherCommands {
    private RealtimeWeatherCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("realtimeweather")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(RealtimeLocalisedWeatherMod.serverWeatherManager().status(context.getSource().getLevel())), false);
                    return 1;
                }))
                .then(Commands.literal("mode")
                    .then(Commands.literal("live").executes(context -> setMode(context.getSource().getLevel(), WeatherAuthorityMode.LIVE, context.getSource())))
                    .then(Commands.literal("manual").executes(context -> setMode(context.getSource().getLevel(), WeatherAuthorityMode.MANUAL, context.getSource())))
                    .then(Commands.literal("vanilla").executes(context -> setMode(context.getSource().getLevel(), WeatherAuthorityMode.VANILLA, context.getSource()))))
                .then(Commands.literal("refresh").executes(context -> {
                    RealtimeLocalisedWeatherMod.serverWeatherManager().requestRefresh(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal("Realtime Localised Weather refresh requested."), true);
                    return 1;
                }))
                .then(Commands.literal("clearoverride").executes(context -> {
                    RealtimeLocalisedWeatherMod.serverWeatherManager().clearOverride(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal("Realtime Localised Weather override cleared."), true);
                    return 1;
                }))
                .then(Commands.literal("override")
                    .then(Commands.literal("clear").executes(context -> applyOverride(context, ResolvedPrecipitation.NONE, GameplaySeverity.TRACE, 30 * 60 * 1000L)))
                    .then(Commands.literal("rain")
                        .then(Commands.argument("severity", StringArgumentType.word())
                            .executes(context -> applyOverride(context, ResolvedPrecipitation.RAIN, parseSeverity(StringArgumentType.getString(context, "severity")), 30 * 60 * 1000L))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(1))
                                .executes(context -> applyOverride(context, ResolvedPrecipitation.RAIN, parseSeverity(StringArgumentType.getString(context, "severity")), IntegerArgumentType.getInteger(context, "duration") * 1000L)))))
                    .then(Commands.literal("snow")
                        .then(Commands.argument("severity", StringArgumentType.word())
                            .executes(context -> applyOverride(context, ResolvedPrecipitation.SNOW, parseSeverity(StringArgumentType.getString(context, "severity")), 30 * 60 * 1000L))))
                    .then(Commands.literal("thunder")
                        .then(Commands.argument("severity", StringArgumentType.word())
                            .executes(context -> applyOverride(context, ResolvedPrecipitation.THUNDER_RAIN, parseSeverity(StringArgumentType.getString(context, "severity")), 30 * 60 * 1000L))))
                    .then(Commands.literal("region")
                        .then(Commands.argument("zoneX", IntegerArgumentType.integer())
                            .then(Commands.argument("zoneZ", IntegerArgumentType.integer())
                                .then(Commands.argument("type", StringArgumentType.word())
                                    .then(Commands.argument("severity", StringArgumentType.word())
                                        .executes(context -> {
                                            RealtimeLocalisedWeatherMod.serverWeatherManager().applyRegionalOverride(
                                                context.getSource().getLevel(),
                                                IntegerArgumentType.getInteger(context, "zoneX"),
                                                IntegerArgumentType.getInteger(context, "zoneZ"),
                                                parsePrecipitation(StringArgumentType.getString(context, "type")),
                                                parseSeverity(StringArgumentType.getString(context, "severity")),
                                                30 * 60 * 1000L
                                            );
                                            context.getSource().sendSuccess(() -> Component.literal("Realtime Localised Weather regional override applied."), true);
                                            return 1;
                                        })))))))
                .then(Commands.literal("sample")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(context -> {
                                int x = IntegerArgumentType.getInteger(context, "x");
                                int z = IntegerArgumentType.getInteger(context, "z");
                                context.getSource().sendSuccess(() -> Component.literal(RealtimeLocalisedWeatherMod.serverWeatherManager().sampleStatus(context.getSource().getLevel(), x, z)), false);
                                return 1;
                            }))))
        );
        event.getDispatcher().register(
            Commands.literal("clouds")
                .executes(context -> sendCloudStatus(context.getSource()))
        );
        event.getDispatcher().register(
            Commands.literal("rain")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(context -> {
                    int percent = IntegerArgumentType.getInteger(context, "percent");
                    CommandSourceStack source = context.getSource();
                    RealtimeLocalisedWeatherMod.serverWeatherManager().applyVisualRainOverride(source.getLevel(), BlockPos.containing(source.getPosition()), percent);
                    context.getSource().sendSuccess(() -> Component.literal("Rain visual override set to " + percent + "% for this weather tile."), false);
                    return 1;
                }))
        );
        event.getDispatcher().register(
            Commands.literal("cloud")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(context -> {
                    int percent = IntegerArgumentType.getInteger(context, "percent");
                    CommandSourceStack source = context.getSource();
                    RealtimeLocalisedWeatherMod.serverWeatherManager().applyVisualCloudOverride(source.getLevel(), BlockPos.containing(source.getPosition()), percent);
                    context.getSource().sendSuccess(() -> Component.literal("Cloud visual override set to " + percent + "% for this weather tile."), false);
                    return 1;
                }))
        );
        event.getDispatcher().register(
            Commands.literal("precipitation")
                .executes(context -> sendPrecipitationStatus(context.getSource()))
        );
    }

    private static int setMode(net.minecraft.server.level.ServerLevel level, WeatherAuthorityMode mode, net.minecraft.commands.CommandSourceStack source) {
        RealtimeLocalisedWeatherMod.serverWeatherManager().setMode(level, mode);
        source.sendSuccess(() -> Component.literal("Realtime Localised Weather mode set to " + mode + "."), true);
        return 1;
    }

    private static int sendCloudStatus(CommandSourceStack source) {
        Vec3 position = source.getPosition();
        String message = RealtimeLocalisedWeatherMod.serverWeatherManager().cloudStatus(
            source.getLevel(),
            (int) Math.floor(position.x),
            (int) Math.floor(position.z)
        );
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int sendPrecipitationStatus(CommandSourceStack source) {
        Vec3 position = source.getPosition();
        String message = RealtimeLocalisedWeatherMod.serverWeatherManager().precipitationStatus(
            source.getLevel(),
            (int) Math.floor(position.x),
            (int) Math.floor(position.z)
        );
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int applyOverride(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, ResolvedPrecipitation precipitation, GameplaySeverity severity, long durationMillis) {
        RealtimeLocalisedWeatherMod.serverWeatherManager().applyGlobalOverride(context.getSource().getLevel(), precipitation, severity, durationMillis);
        context.getSource().sendSuccess(() -> Component.literal("Realtime Localised Weather override applied."), true);
        return 1;
    }

    private static GameplaySeverity parseSeverity(String value) {
        return GameplaySeverity.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private static ResolvedPrecipitation parsePrecipitation(String value) {
        return ResolvedPrecipitation.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
