package dev.s1mp1e.client;

/**
 * Implemented by modules that paint an on-screen HUD element. There is no Fabric API
 * at all for Legacy Fabric 1.13.2 in this setup, so there is no
 * {@code fabric-rendering-v1} and no {@code HudRenderCallback}. Dispatch happens from
 * our own {@code InGameHudMixin} at the TAIL of {@code InGameHud.render(float)}
 * ({@code render(F)V}): it calls {@link #renderHud()} once per frame for every enabled
 * module that implements this, so a module owns its own drawing and the entrypoint
 * stays agnostic of which modules exist.
 *
 * <p>1.13.2 has no {@code DrawContext}/{@code MatrixStack}: immediate-mode GL is bound
 * to the current scaled-GUI projection when the TAIL fires, so the module draws
 * straight through {@code GlassRenderer}/{@code Tessellator} with no context argument.
 */
public interface HudRenderer {
    void renderHud();
}
