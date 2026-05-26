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

    public IceboxScreen(IceboxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);

        this.imageWidth = 176;
        this.imageHeight = menu.iceboxSlots() == IceboxMenu.SIMPLE_SLOTS
                ? SIMPLE_IMAGE_HEIGHT
                : ADVANCED_IMAGE_HEIGHT;

        this.inventoryLabelY = this.imageHeight - 94;
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

        // Top title/header strip.
        graphics.blit(VANILLA_CONTAINER, x, y, 0, 0, this.imageWidth, 17);

        // Custom blue icebox middle section.
        drawIceboxMiddle(graphics, x, y, topHeight);

        // Vanilla lower player-inventory section, tinted blue.
        graphics.blit(VANILLA_CONTAINER, x, y + topHeight, 0, 126, this.imageWidth, 96);

        // Draw only the icebox slot backgrounds.
        // Player inventory slot backgrounds are already part of the lower texture above.
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

        graphics.fill(x + 1, top, right - 1, bottom, ICEBOX_BG);

        graphics.fill(x, top, x + 1, bottom, BORDER_LIGHT);
        graphics.fill(right - 1, top, right, bottom, BORDER_DARK);
        graphics.fill(x + 1, bottom - 1, right - 1, bottom, BORDER_DARK);
    }
}