package dev.s1mp1e.client;

/**
 * Implemented by modules that paint an on-screen HUD element. In 26.2 the driver is a mixin into
 * {@code net.minecraft.client.gui.Hud} (see {@code HudDriverMixin}) rather than a Fabric
 * {@code HudRenderCallback} — it calls {@link #renderHud(S1mp1eHudCtx)} once per frame for every enabled
 * module that implements this, so a module owns its own drawing and the driver stays agnostic of which
 * modules exist. The {@link S1mp1eHudCtx} carries the 26.2 {@code GuiGraphicsExtractor}/{@code Font}/player.
 */
public interface HudRenderer {
    void renderHud(S1mp1eHudCtx ctx);
}
