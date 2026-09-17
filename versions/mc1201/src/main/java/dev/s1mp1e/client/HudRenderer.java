package dev.s1mp1e.client;

import net.minecraft.client.gui.DrawContext;

/**
 * Implemented by modules that paint an on-screen HUD element. The Fabric
 * {@code HudRenderCallback} registered in {@code S1mp1eClient} calls
 * {@link #renderHud(DrawContext)} once per frame for every enabled module that
 * implements this, so a module owns its own drawing and the entrypoint stays
 * agnostic of which modules exist.
 */
public interface HudRenderer {
    void renderHud(DrawContext ctx);
}
