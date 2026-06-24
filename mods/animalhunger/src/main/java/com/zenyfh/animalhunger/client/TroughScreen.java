package com.zenyfh.animalhunger.client;

import com.zenyfh.animalhunger.world.TroughMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public class TroughScreen extends AbstractContainerScreen<TroughMenu> {
    private static final ResourceLocation VANILLA_CONTAINER = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int IMAGE_HEIGHT = 176;
    private static final int IMAGE_WIDTH = 216;
    private static final int ANIMAL_PANEL_X = 124;
    private static final int ANIMAL_PANEL_Y = 17;
    private static final int ANIMAL_PANEL_WIDTH = 84;
    private static final int ANIMAL_PANEL_HEIGHT = 57;
    private static final int SLOT_TEXTURE_X = 7;
    private static final int SLOT_TEXTURE_Y = 17;
    private static final int BG = 0xFF8A6847;
    private static final int PANEL = 0xFFA77B51;
    private static final int LIGHT = 0xFFC39662;
    private static final int DARK = 0xFF4B3424;

    public TroughScreen(TroughMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
        this.inventoryLabelY = TroughMenu.PLAYER_INVENTORY_Y - 10;
        this.titleLabelX = 8;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
        this.renderAnimalPanelTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        drawFrame(graphics, x, y, this.imageWidth, this.imageHeight);
        graphics.fill(x + 57, y + 12, x + 119, y + 74, PANEL);
        graphics.fill(x + 57, y + 12, x + 119, y + 13, LIGHT);
        graphics.fill(x + 57, y + 12, x + 58, y + 74, LIGHT);
        graphics.fill(x + 118, y + 13, x + 119, y + 74, DARK);
        graphics.fill(x + 58, y + 73, x + 119, y + 74, DARK);
        graphics.fill(x + ANIMAL_PANEL_X, y + ANIMAL_PANEL_Y, x + ANIMAL_PANEL_X + ANIMAL_PANEL_WIDTH, y + ANIMAL_PANEL_Y + ANIMAL_PANEL_HEIGHT, 0xFFB88A5C);
        graphics.fill(x + ANIMAL_PANEL_X, y + ANIMAL_PANEL_Y, x + ANIMAL_PANEL_X + ANIMAL_PANEL_WIDTH, y + ANIMAL_PANEL_Y + 1, LIGHT);
        graphics.fill(x + ANIMAL_PANEL_X, y + ANIMAL_PANEL_Y, x + ANIMAL_PANEL_X + 1, y + ANIMAL_PANEL_Y + ANIMAL_PANEL_HEIGHT, LIGHT);
        graphics.fill(x + ANIMAL_PANEL_X + ANIMAL_PANEL_WIDTH - 1, y + ANIMAL_PANEL_Y + 1, x + ANIMAL_PANEL_X + ANIMAL_PANEL_WIDTH, y + ANIMAL_PANEL_Y + ANIMAL_PANEL_HEIGHT, DARK);
        graphics.fill(x + ANIMAL_PANEL_X + 1, y + ANIMAL_PANEL_Y + ANIMAL_PANEL_HEIGHT - 1, x + ANIMAL_PANEL_X + ANIMAL_PANEL_WIDTH, y + ANIMAL_PANEL_Y + ANIMAL_PANEL_HEIGHT, DARK);

        graphics.setColor(0.78F, 0.60F, 0.40F, 1.0F);
        for (Slot slot : this.menu.slots) {
            graphics.blit(VANILLA_CONTAINER, x + slot.x - 1, y + slot.y - 1, SLOT_TEXTURE_X, SLOT_TEXTURE_Y, 18, 18);
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, BG);
        graphics.fill(x, y, x + width - 1, y + 1, LIGHT);
        graphics.fill(x, y, x + 1, y + height - 1, LIGHT);
        graphics.fill(x + width - 1, y + 1, x + width, y + height, DARK);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, DARK);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x3F2A1A, false);
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x3F2A1A, false);
        graphics.drawString(this.font, Component.translatable("screen.animalhunger.trough.can_feed"), ANIMAL_PANEL_X + 5, ANIMAL_PANEL_Y + 5, 0x3F2A1A, false);

        List<Component> animals = this.menu.supportedAnimalDisplayNames();
        if (animals.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.animalhunger.trough.no_animals"), ANIMAL_PANEL_X + 5, ANIMAL_PANEL_Y + 19, 0x5B4532, false);
            return;
        }

        int y = ANIMAL_PANEL_Y + 18;
        int maxRows = 3;
        int rows = Math.min(maxRows, animals.size());
        for (int i = 0; i < rows; i++) {
            String label = this.font.plainSubstrByWidth(animals.get(i).getString(), ANIMAL_PANEL_WIDTH - 17);
            graphics.drawString(this.font, "- " + label, ANIMAL_PANEL_X + 6, y, 0x3F2A1A, false);
            y += 9;
        }
        if (animals.size() > maxRows) {
            graphics.drawString(this.font, Component.translatable("screen.animalhunger.trough.more_animals", animals.size() - maxRows), ANIMAL_PANEL_X + 6, y, 0x5B4532, false);
        }
    }

    private void renderAnimalPanelTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isMouseOverAnimalPanel(mouseX, mouseY)) {
            return;
        }
        List<Component> animals = this.menu.supportedAnimalDisplayNames();
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.translatable("screen.animalhunger.trough.can_feed"));
        if (animals.isEmpty()) {
            tooltip.add(Component.translatable("screen.animalhunger.trough.no_animals"));
        } else {
            tooltip.addAll(animals);
        }
        graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
    }

    private boolean isMouseOverAnimalPanel(double mouseX, double mouseY) {
        int x = this.leftPos + ANIMAL_PANEL_X;
        int y = this.topPos + ANIMAL_PANEL_Y;
        return mouseX >= x
            && mouseX < x + ANIMAL_PANEL_WIDTH
            && mouseY >= y
            && mouseY < y + ANIMAL_PANEL_HEIGHT;
    }
}
