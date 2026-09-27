package dev.s1mp1e.glass.render;

import net.minecraft.client.util.math.MatrixStack;

/**
 * Duck interface implemented on every {@code HandledScreen} by {@code HandledScreenGlassMixin}: draws the screen's
 * background the glass way (its own {@code drawBackground} with only the body-PNG blit replaced by the container glass,
 * see {@link ContainerBodyBlit}; vanilla when the glass pipeline is down). Used by the few vanilla screens whose
 * {@code render} calls {@code drawBackground} directly instead of through {@code HandledScreen.render}.
 */
public interface GlassBackgroundHost {
    void s1mp1e$glassBackground(MatrixStack matrices, float delta, int mouseX, int mouseY);
}
