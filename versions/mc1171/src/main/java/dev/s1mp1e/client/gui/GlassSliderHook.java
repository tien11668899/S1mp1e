package dev.s1mp1e.client.gui;

import net.minecraft.client.util.math.MatrixStack;

/**
 * Duck interface for vanilla option sliders. The slider mixin on {@code SliderWidget} implements it, and the
 * single {@code ClickableWidget.renderButton} HEAD injector dispatches to it first, so the slider skin is
 * drawn without a second injector on the same method (1.19.2's {@code SliderWidget} inherits renderButton
 * and draws its knob in renderBackground).
 */
public interface GlassSliderHook {

    /**
     * Draw this slider as liquid glass.
     *
     * @return true when the glass skin was drawn and the vanilla draw must be skipped; false to fall back to
     *         the regular button path
     */
    boolean s1mp1e$renderGlass(MatrixStack m, int mouseX, int mouseY, float delta);
}
