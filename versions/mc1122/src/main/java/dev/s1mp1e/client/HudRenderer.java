package dev.s1mp1e.client;

/**
 * Implemented by modules that paint an on-screen HUD element. The Forge render
 * hook (a {@code RenderGameOverlayEvent} handler) calls {@link #renderHud()} once
 * per frame for every enabled module that implements this, so a module owns its
 * own drawing and the entrypoint stays agnostic of which modules exist.
 *
 * <p>1.8.9 has no {@code DrawContext}: immediate-mode GL is bound to the current
 * scaled-GUI projection when the hook fires, so the module draws straight through
 * {@code GlassRenderer}/{@code Tessellator} with no context argument.
 */
public interface HudRenderer {
    void renderHud();
}
