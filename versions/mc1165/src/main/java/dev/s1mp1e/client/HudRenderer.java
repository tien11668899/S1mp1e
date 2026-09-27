package dev.s1mp1e.client;

import net.minecraft.client.util.math.MatrixStack;

/**
 * Implemented by modules that paint an on-screen HUD element. The Fabric
 * {@code HudRenderCallback} registered in {@code S1mp1eClient} calls
 * {@link #renderHud(MatrixStack)} once per frame for every enabled module that
 * implements this, so a module owns its own drawing and the entrypoint stays
 * agnostic of which modules exist.
 *
 * <p>1.16.5 has no {@code DrawContext}; the frame's {@link MatrixStack} is passed
 * straight through. It reaches only {@code fill}/text/{@code drawTexture}/
 * {@code drawSprite} draws — GUI item rendering and the immediate-mode glass path
 * follow the fixed-function modelview, so a module that scales those uses
 * {@code RenderSystem.pushMatrix}/{@code scalef} rather than this stack.
 */
public interface HudRenderer {
    void renderHud(MatrixStack matrices);
}
