package com.mekexdeorum.client.gui;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.IProgressInfoHandler;
import mekanism.client.gui.element.progress.ProgressType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Mekanism's downward progress arrow, drawn into the small rectangle the layout leaves for it.
 *
 * <p>{@link GuiProgress} always draws {@link ProgressType#DOWN} at the sprite's own size, 8x20,
 * which is taller than the gap between an input slot and the output grid and therefore reaches into
 * the output slots. This widget keeps the sprite, but blits the background column and the filled
 * overlay column into a rectangle of the caller's size, so the arrow is as wide as the input slot's
 * inner width and only as tall as {@link com.mekexdeorum.SieveLayout#PROGRESS_HEIGHT}.
 *
 * <p>The warning overlay of the parent is not drawn; the sieve has no warning state to show.
 */
public class GuiSieveProgress extends GuiProgress {

    public GuiSieveProgress(IProgressInfoHandler handler, IGuiWrapper gui, int x, int y, int width, int height) {
        // The parent sizes the widget from the sprite; the layout's rectangle is what we keep.
        super(handler, ProgressType.DOWN, gui, x, y);
        this.width = width;
        this.height = height;
    }

    @Override
    public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        if (!handler.isActive()) {
            return;
        }
        ResourceLocation texture = getResource();
        int textureWidth = type.getTextureWidth();
        int textureHeight = type.getTextureHeight();
        // The whole background column, squashed into the arrow's rectangle.
        guiGraphics.blit(texture, relativeX, relativeY, width, height,
                0.0F, 0.0F, type.getWidth(), textureHeight, textureWidth, textureHeight);
        int filled = (int) (getProgress() * height);
        if (filled > 0) {
            // The same fraction of the overlay column, so the fill boundary lands on the same pixel
            // row of the sprite that the parent would have used.
            int sourceRows = Math.max(1, Math.round((float) filled / height * textureHeight));
            guiGraphics.blit(texture, relativeX, relativeY, width, filled,
                    (float) type.getOverlayX(false), 0.0F, type.getWidth(), sourceRows, textureWidth, textureHeight);
        }
    }
}
