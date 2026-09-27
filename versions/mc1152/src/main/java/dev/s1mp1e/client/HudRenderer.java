package dev.s1mp1e.client;

/**
 * Implemented by modules that paint an on-screen HUD element. On 1.15.2 we do not
 * declare fabric-api as a dependency (its {@code fabric-rendering-v1} would also fire
 * {@code HudRenderCallback} at {@code InGameHud.render} RETURN), so for parity with the
 * fixed-function line dispatch happens from our own {@code InGameHudMixin} at the TAIL
 * of {@code InGameHud.render(float)}: it calls {@link #renderHud()} once per frame for
 * every enabled module that implements this, so a module owns its own drawing and the
 * entrypoint stays agnostic of which modules exist.
 *
 * <p>1.15.2 has no {@code DrawContext}: GUI/HUD rendering still uses no {@code MatrixStack},
 * so immediate-mode GL is bound to the current scaled-GUI projection when the TAIL fires
 * and the module draws straight through {@code GlassRenderer}/{@code Tessellator} with no
 * context argument.
 */
public interface HudRenderer {
    void renderHud();
}
