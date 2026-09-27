package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.toasts.GuiToast;

/**
 * Feature G4 — toasts. {@code S1mp1eTransformer} swaps the FIRST
 * {@code toastGui.drawTexturedModalRect(0, 0, u, v, w, h)} of each concrete toast's {@code draw}
 * method (advancement / recipe / system / tutorial) for a call to {@link #card}. Because the redirect
 * sits at the blit call site — after {@code GuiToast.ToastInstance.render} has applied the toast's
 * slide-in {@code translate} — the glass card inherits the slide, so it moves with the toast. The
 * toast's own icon and text draw on top unchanged.
 *
 * <p>New glass piece, so it rounds with the HUD hotbar corner radius (R2), refracts the world backdrop
 * grabbed at the overlay-pass head (R4), and adds a faint grey scrim for text readability. Falls back
 * to the vanilla frame sprite when the glass pipeline is down (the toast texture is still bound at the
 * redirect point).
 */
public final class GlassToast {

    private GlassToast() {}

    /** Grey readability scrim over the card. */
    private static final int SCRIM = 0x30101018;

    /**
     * Redirect target for the toast frame's {@code Gui.drawTexturedModalRect(int,int,int,int,int,int)}.
     * The receiver {@code gui} (the {@code GuiToast} argument the toast draws through) becomes the
     * first parameter when the transformer rewrites the INVOKEVIRTUAL to an INVOKESTATIC.
     */
    public static void card(GuiToast gui, int x, int y, int u, int v, int w, int h) {
        if (w <= 0 || h <= 0) return;
        try {
            if (HudGlass.glassHotbar(x, y, x + w, y + h, 0.92f, GlassRenderer.FROST_PANEL)) {
                float r = dev.s1mp1e.glass.render.GlassCorners.hotbarRadiusPx(w, h);
                HudGlass.scrim(x, y, x + w, y + h, r, SCRIM);
                return;
            }
        } catch (Throwable ignored) {
            // fall through to the vanilla sprite (nothing meaningful drew)
        }
        try {
            gui.drawTexturedModalRect(x, y, u, v, w, h);
        } catch (Throwable ignored) {}
    }
}
