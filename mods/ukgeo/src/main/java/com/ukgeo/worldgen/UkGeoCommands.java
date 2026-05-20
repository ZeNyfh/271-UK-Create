package com.ukgeo.worldgen;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                                    .append(" vegetation=").append(generator.sampleVegetation(x, z))
                                    .append(" river=").append(generator.sampleRiver(x, z))
                                    .append(" oilMb=").append(generator.sampleOilAmount(ctx.getSource().getLevel().getSeed(), x, z));
                                for (Map.Entry<String, Integer> entry : generator.sampleOres(x, z).entrySet()) {
                                    message.append(" ").append(entry.getKey()).append("=").append(entry.getValue());
                                }
                                ctx.getSource().sendSuccess(() -> Component.literal(message.toString()), false);
                                return 1;
                            }))))
                .then(spawnVanillaTreeCommand())
        );
        event.getDispatcher().register(spawnVanillaTreeCommand());
    }

    private static UkGeoChunkGenerator generator(ChunkGenerator generator) {
        return generator instanceof UkGeoChunkGenerator ukGeo ? ukGeo : null;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnVanillaTreeCommand() {
        return Commands.literal("spawnvanillatree")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("type", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(treeTypeIds(), builder))
                .executes(ctx -> spawnVanillaTree(ctx.getSource().getLevel(), ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "type"))));
    }

    private static int spawnVanillaTree(ServerLevel level, ServerPlayer player, String typeName) {
        BlockPos pos = player.blockPosition();
        return VanillaTreeFeatures.type(typeName).map(type -> {
            boolean placed = VanillaTreeFeatures.placeTree(level, pos, type);
            if (placed) {
                player.sendSystemMessage(Component.literal("Placed vanilla tree " + type.id() + " at " + pos.toShortString()));
                return 1;
            }
            player.sendSystemMessage(Component.literal("Could not place vanilla tree " + type.id() + " at " + pos.toShortString()));
            return 0;
        }).orElseGet(() -> {
            player.sendSystemMessage(Component.literal("Unknown tree type '" + typeName + "'. Try: " + String.join(", ", treeTypeIds())));
            return 0;
        });
    }

    private static String[] treeTypeIds() {
        return Stream.of(VanillaTreeFeatures.TreeType.values())
            .map(VanillaTreeFeatures.TreeType::id)
            .toArray(String[]::new);
    }
}
