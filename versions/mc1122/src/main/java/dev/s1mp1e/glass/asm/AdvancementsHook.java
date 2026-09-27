package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.advancements.GuiScreenAdvancements;

/**
 * Feature (A) — the advancements window framed in glass, wooden frame gone.
 *
 * <p>1.12.2's {@code GuiScreenAdvancements.drawScreen} draws the tree
 * ({@code renderInside}, its own opaque biome background filling the 234&times;113
 * interior) and THEN the wooden window frame ({@code renderWindow}). This hook:
 * <ul>
 *   <li>{@link #panel} is spliced onto {@code renderInside}'s head — it grabs a
 *       fresh backdrop (world + dim, drawn by {@code drawDefaultBackground} just
 *       before) and lays a 252&times;140 frosted glass panel UNDER the tree, so
 *       the tree's opaque interior paints over the centre and only a glass border
 *       shows: the tree framed in glass (rule R4 — its own fresh grab);</li>
 *   <li>{@link #frame} replaces the wooden {@code WINDOW} frame blit — it draws
 *       nothing once the glass panel drew this frame, or the vanilla frame if the
 *       glass pipeline is down (so the screen never loses its border).</li>
 * </ul>
 * The panel uses the container-panel radius (this is the large body-panel family,
 * matching the inventory/chest panels), not the small-accent hotbar radius.
 */
public final class AdvancementsHook {

    private AdvancementsHook() {}

    private static final Gui BLIT = new Gui();

    /** The screen whose glass panel drew this frame (so {@link #frame} drops the
     *  wooden frame); null = glass down, keep the vanilla frame. */
    private static Object panelDrewFor;

    /** Glass panel under the tree, at {@code renderInside} head. */
    public static void panel(GuiScreenAdvancements screen, int i, int j) {
        panelDrewFor = null;
        try {
            if (GlassProgram.ensureReady() && GlassProgram.usable()) {
                // World + dim is on the framebuffer (drawDefaultBackground ran); grab it
                // fresh so the panel refracts it and never self-samples the tree drawn next.
                SceneCapture.forceGrab();
                if (SceneCapture.hasBackdrop()) {
                    GlassRenderer.panel(i, j, i + 252, j + 140, 1f);
                    panelDrewFor = screen;
                }
            }
        } catch (Throwable t) {
            panelDrewFor = null;
        }
    }

    /** Replaces the wooden WINDOW frame blit: drop it when the glass panel drew,
     *  else draw the vanilla frame (WINDOW is already bound at the call site). */
    public static void frame(GuiScreenAdvancements screen, int i, int j, int u, int v, int w, int h) {
        if (panelDrewFor == screen) return;
        try {
            BLIT.drawTexturedModalRect(i, j, u, v, w, h);
        } catch (Throwable ignored) {
        }
    }
}
