package com.foodspoilage.client;

import com.foodspoilage.world.IceboxMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public class IceboxScreen extends AbstractContainerScreen<IceboxMenu> {
    private static final ResourceLocation VANILLA_CONTAINER =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int SLOT_TEXTURE_X = 7;
    private static final int SLOT_TEXTURE_Y = 17;

    private static final int SIMPLE_TOP_HEIGHT = 53;
    private static final int ADVANCED_TOP_HEIGHT = 71;

    private static final int SIMPLE_IMAGE_HEIGHT = 149;
    private static final int ADVANCED_IMAGE_HEIGHT = 167;

    private static final int ICEBOX_BG = 0xFFC7EBF7;
    private static final int BORDER_LIGHT = 0xFFE6FBFF;
    private static final int BORDER_DARK = 0xFF6FAEC8;
    private static final int PANEL_BG = 0xFFA9D2E6;
    private static final int PANEL_SHADOW = 0xFF5B8FA8;

    public IceboxScreen(IceboxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = 176;
        this.imageHeight = menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS
                ? SIMPLE_IMAGE_HEIGHT
                : ADVANCED_IMAGE_HEIGHT;

        this.inventoryLabelY = this.imageHeight - 94;
        this.titleLabelX = 8;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        int topHeight = this.menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS
                ? SIMPLE_TOP_HEIGHT
                : ADVANCED_TOP_HEIGHT;

        graphics.setColor(0.62F, 0.86F, 1.0F, 1.0F);

        // Header strip and lower inventory section use the vanilla container texture.
        graphics.blit(VANILLA_CONTAINER, x, y, 0, 0, this.imageWidth, 17);
        drawIceboxMiddle(graphics, x, y, topHeight);
        graphics.blit(VANILLA_CONTAINER, x, y + topHeight, 0, 126, this.imageWidth, 96);

        // Draw only the custom icebox slot backgrounds.
        for (int i = 0; i < this.menu.iceboxSlots(); i++) {
            Slot slot = this.menu.slots.get(i);
            graphics.blit(
                    VANILLA_CONTAINER,
                    x + slot.x - 1,
                    y + slot.y - 1,
                    SLOT_TEXTURE_X,
                    SLOT_TEXTURE_Y,
                    18,
                    18
            );
        }

        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawIceboxMiddle(GuiGraphics graphics, int x, int y, int topHeight) {
        int top = y + 17;
        int bottom = y + topHeight;
        int right = x + this.imageWidth;

        // Main icebox body.
        graphics.fill(x + 1, top, right - 1, bottom, ICEBOX_BG);
        graphics.fill(x, top, x + 1, bottom, BORDER_LIGHT);
        graphics.fill(right - 1, top, right, bottom, BORDER_DARK);
        graphics.fill(x + 1, bottom - 1, right - 1, bottom, BORDER_DARK);

        // Recessed slot panel so the top section no longer looks like one long blue bar.
        int columns = this.menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS ? 2 : 3;
        int rows = this.menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS ? 2 : 3;
        int firstSlotX = this.menu.slots.get(0).x - 5;
        int firstSlotY = this.menu.slots.get(0).y - 5;
        int panelWidth = columns * 18 + 10;
        int panelHeight = rows * 18 + 10;

        graphics.fill(x + firstSlotX, y + firstSlotY, x + firstSlotX + panelWidth, y + firstSlotY + panelHeight, PANEL_BG);
        graphics.fill(x + firstSlotX, y + firstSlotY, x + firstSlotX + panelWidth, y + firstSlotY + 1, BORDER_LIGHT);
        graphics.fill(x + firstSlotX, y + firstSlotY, x + firstSlotX + 1, y + firstSlotY + panelHeight, BORDER_LIGHT);
        graphics.fill(x + firstSlotX + panelWidth - 1, y + firstSlotY, x + firstSlotX + panelWidth, y + firstSlotY + panelHeight, PANEL_SHADOW);
        graphics.fill(x + firstSlotX, y + firstSlotY + panelHeight - 1, x + firstSlotX + panelWidth, y + firstSlotY + panelHeight, PANEL_SHADOW);
    }
}