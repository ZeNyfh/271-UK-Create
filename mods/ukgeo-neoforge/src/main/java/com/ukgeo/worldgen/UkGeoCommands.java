package com.ukgeo.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class UkGeoCommands {
    private UkGeoCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("ukgeo")
                .then(Commands.literal("check").requires(source -> source.hasPermission(2)).executes(ctx -> {
                    UkGeoChunkGenerator generator = generator(ctx.getSource().getLevel().getChunkSource().getGenerator());
                    ctx.getSource().sendSuccess(() -> Component.literal(generator == null ? "Active generator is not ukgeo:heightmap" : generator.status()), false);
                    return 1;
                }))
                .then(Commands.literal("cache").requires(source -> source.hasPermission(2)).executes(ctx -> {
                    UkGeoChunkGenerator generator = generator(ctx.getSource().getLevel().getChunkSource().getGenerator());
                    ctx.getSource().sendSuccess(() -> Component.literal(generator == null ? "No ukgeo generator cache" : generator.status()), false);
                    return 1;
                }))
                .then(Commands.literal("sample")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                int x = IntegerArgumentType.getInteger(ctx, "x");
                                int z = IntegerArgumentType.getInteger(ctx, "z");
                                UkGeoChunkGenerator generator = generator(ctx.getSource().getLevel().getChunkSource().getGenerator());
                                if (generator == null) {
                                    ctx.getSource().sendFailure(Component.literal("Active generator is not ukgeo:heightmap"));
                                    return 0;
                                }
                                StringBuilder message = new StringBuilder("surfaceY=").append(generator.surfaceY(x, z))
                                    .append(" surfaceGeology=").append(generator.sampleSurface(x, z))
                                    .append(" river=").append(generator.sampleRiver(x, z));
                                for (Map.Entry<String, Integer> entry : generator.sampleOres(x, z).entrySet()) {
                                    message.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
                                }
                                ctx.getSource().sendSuccess(() -> Component.literal(message.toString()), false);
                                return 1;
                            }))))
        );
    }

    private static UkGeoChunkGenerator generator(ChunkGenerator generator) {
        return generator instanceof UkGeoChunkGenerator ukGeo ? ukGeo : null;
    }
}
