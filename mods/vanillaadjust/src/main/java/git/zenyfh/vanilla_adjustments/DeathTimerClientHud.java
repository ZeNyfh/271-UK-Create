package git.zenyfh.vanilla_adjustments;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = Vanilla_adjustments.MODID, value = Dist.CLIENT)
public final class DeathTimerClientHud {
    private DeathTimerClientHud() {
    }

    @SubscribeEvent
    public static void onClientPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            DeathTimerClientState.clientTick();
            keepCurrentDeathScreenButtonsEnabled();
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof DeathScreen deathScreen) {
            keepDeathScreenButtonsEnabled(deathScreen);
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(event.getScreen() instanceof DeathScreen deathScreen)) {
            return;
        }
        keepDeathScreenButtonsEnabled(deathScreen);
        DeathTimerClientState.clientTick();
        if (!DeathTimerClientState.active()) {
            return;
        }
        if (!VanillaAdjustConfig.DEATH_TIMER_DISPLAY_ENABLED.get()) {
            return;
        }
        String text = DeathTimerClientState.text();
        if (text.isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        int x = (minecraft.getWindow().getGuiScaledWidth() - font.width(text)) / 2;
        int y = minecraft.getWindow().getGuiScaledHeight() / 4 + 56;
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.drawString(font, text, x, y, VanillaAdjustConfig.DEATH_TIMER_TEXT_COLOR.get(), true);
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DeathTimerClientState.clear();
    }

    @SubscribeEvent
    public static void onClientClone(ClientPlayerNetworkEvent.Clone event) {
        DeathTimerClientState.clear();
    }

    private static void keepCurrentDeathScreenButtonsEnabled() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof DeathScreen deathScreen)) {
            return;
        }
        keepDeathScreenButtonsEnabled(deathScreen);
    }

    private static void keepDeathScreenButtonsEnabled(DeathScreen deathScreen) {
        for (GuiEventListener child : deathScreen.children()) {
            if (child instanceof AbstractWidget widget) {
                widget.active = true;
            }
        }
    }
}
