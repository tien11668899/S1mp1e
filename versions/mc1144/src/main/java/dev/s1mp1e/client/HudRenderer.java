package dev.s1mp1e.client;

/**
 * Implemented by modules that paint an on-screen HUD element. 1.14.4's Fabric API
 * (0.28.5+1.14) has no {@code fabric-rendering-v1} and therefore no
 * {@code HudRenderCallback}, so dispatch happens from our own {@code InGameHudMixin}
 * at the TAIL of {@code InGameHud.render(float)}: it calls {@link #renderHud()} once
 * per frame for every enabled module that implements this, so a module owns its own
 * drawing and the entrypoint stays agnostic of which modules exist.
 *
 * <p>1.14.4 has no {@code DrawContext}: immediate-mode GL is bound to the current
 * scaled-GUI projection when the TAIL fires, so the module draws straight through
 * {@code GlassRenderer}/{@code Tessellator} with no context argument.
 */
public interface HudRenderer {
    void renderHud();
}
