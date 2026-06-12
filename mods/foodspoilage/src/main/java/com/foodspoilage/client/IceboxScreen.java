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

    private static final int SIMPLE_IMAGE_HEIGHT = 163;
    private static final int ADVANCED_IMAGE_HEIGHT = 177;

    // Colours sampled from the user-provided UI reference strips.
    private static final int ICEBOX_BG = 0xFF7BAAC6;
    private static final int BORDER_LIGHT = 0xFF9EDBFF;
    private static final int BORDER_DARK = 0xFF354955;
    private static final int PANEL_BG = 0xFF88BAD9;
    private static final int PANEL_SHADOW = 0xFF5B8FA8;

    public IceboxScreen(IceboxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = 176;
        this.imageHeight = menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS
                ? SIMPLE_IMAGE_HEIGHT
                : ADVANCED_IMAGE_HEIGHT;

        this.inventoryLabelY = menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS ? IceboxMenu.SIMPLE_PLAYER_INVENTORY_Y - 10 : 83;
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

        drawWindowFrame(graphics, x, y, this.imageWidth, this.imageHeight);
        drawIceboxPanel(graphics, x, y);

        graphics.setColor(0.62F, 0.86F, 1.0F, 1.0F);
        for (Slot slot : this.menu.slots) {
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

    private void drawWindowFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, ICEBOX_BG);

        // Outer frame.
        graphics.fill(x, y, x + width - 1, y + 1, BORDER_LIGHT);
        graphics.fill(x, y, x + 1, y + height - 1, BORDER_LIGHT);
        graphics.fill(x + width - 1, y + 1, x + width, y + height, BORDER_DARK);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, BORDER_DARK);

        // Clean up the top corners so the frame matches the desired rounded-ish vanilla corners.
        graphics.fill(x, y + 1, x + 1, y + 2, BORDER_LIGHT);
        graphics.fill(x + width - 2, y, x + width - 1, y + 1, BORDER_LIGHT);
    }

    private void drawIceboxPanel(GuiGraphics graphics, int x, int y) {
        int columns = this.menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS ? 2 : 3;
        int rows = this.menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS ? 2 : 3;
        int firstSlotX = this.menu.slots.get(0).x - 5;
        int firstSlotY = this.menu.slots.get(0).y - 5;
        int panelWidth = columns * 18 + 10;
        int panelHeight = rows * 18 + 10;

        int left = x + firstSlotX;
        int top = y + firstSlotY;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        graphics.fill(left, top, right, bottom, PANEL_BG);
        graphics.fill(left, top, right, top + 1, BORDER_LIGHT);
        graphics.fill(left, top, left + 1, bottom, BORDER_LIGHT);
        graphics.fill(right - 1, top, right, bottom, PANEL_SHADOW);
        graphics.fill(left, bottom - 1, right, bottom, PANEL_SHADOW);
    }
}
