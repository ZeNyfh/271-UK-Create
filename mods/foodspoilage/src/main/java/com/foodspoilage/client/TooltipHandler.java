package com.foodspoilage.client;

import com.foodspoilage.config.FoodSpoilageConfig;
import com.foodspoilage.FoodSpoilage;
import com.foodspoilage.spoilage.FoodStackData;
import com.foodspoilage.spoilage.SpoilageManager;
import java.time.Duration;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = FoodSpoilage.MOD_ID, value = Dist.CLIENT)
public final class TooltipHandler {
    private TooltipHandler() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (!FoodSpoilageConfig.TOOLTIP.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        FoodStackData data = SpoilageManager.existingData(stack);
        if (data == null) {
            return;
        }
        long now = SpoilageManager.now();
        long remaining = Math.max(0L, data.expiryTime() - now);
        int percent = (int) Math.round(data.freshnessAt(now) * 100.0D);

        event.getToolTip().add(Component.translatable("tooltip.foodspoilage.state", Component.translatable("tooltip.foodspoilage.stage." + data.stageAt(now).name().toLowerCase())).withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("tooltip.foodspoilage.freshness", percent).withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable("tooltip.foodspoilage.remaining", formatDuration(remaining)).withStyle(ChatFormatting.GRAY));
        if (event.getFlags().isAdvanced()) {
            event.getToolTip().add(Component.translatable("tooltip.foodspoilage.complexity", String.format("%.2f", data.complexity())).withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(Component.translatable("tooltip.foodspoilage.classification", data.classification().toLowerCase()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(0L, minutes) + "m";
    }
}
