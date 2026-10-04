package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.module.HudGlass;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.gui.BossBar;

/**
 * Feature G3 — the boss health bar. 1.8.9 has a SINGLE dragon/wither bar (no colour / multi-bar system), driven
 * by {@link BossBar}. {@code S1mp1eTransformer} splices {@code if (GlassBossBar.draw()) return;} onto the head
 * of {@code GameGui.renderBossBars}, so this fully replaces the vanilla texture bar.
 *
 * <p>The health fill is the iOS slider blue ({@code 0xFF0A84FF}) as a capsule with round ends; UNDER it sits a
 * concentric liquid-glass capsule 2 px larger on every side with TRUE semicircle ends. The normal glass shader
 * caps its corner at a quarter of the short side, so the true-capsule ends come from the LENS program
 * ({@code glass_lens.fsh}, CORNER_FRAC 1.0) — the same refracting-glass-with-full-capsule-corner variant the
 * slider thumb uses. The blue fill is drawn with {@link GlassRenderer#roundRect} (radius = half the height = a
 * true semicircle). The boss name keeps its vanilla position and shadow.
 */
public final class GlassBossBar {

    private GlassBossBar() {}

    /** iOS systemBlue — the config-slider / boss-fill accent. */
    private static final int FILL = 0xFF0A84FF;
    private static final int BAR_W = 182;
    private static final int BAR_H = 5;
    private static final int BAR_TOP = 12;
    private static final int MARGIN = 2;

    /** @return true when a boss bar exists (and was drawn), so vanilla's own bar is skipped. */
    public static boolean draw() {
        if (BossBar.name == null || BossBar.timer <= 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        try {
            --BossBar.timer;   // vanilla decrements here; we own the draw now

            Window sr = new Window(mc);
            int sw = sr.getWidth();
            int k = sw / 2 - BAR_W / 2;
            int fillW = (int) (BossBar.health * (float) (BAR_W + 1));
            if (fillW < 0) fillW = 0;
            if (fillW > BAR_W) fillW = BAR_W;

            int cx0 = k - MARGIN, cy0 = BAR_TOP - MARGIN;
            int cx1 = k + BAR_W + MARGIN, cy1 = BAR_TOP + BAR_H + MARGIN;   // 9 px tall -> true capsule

            boolean lens = GlassProgram.ensureReady() && GlassProgram.lensUsable() && SceneCapture.hasBackdrop();
            // Suppress the drop shadow while a screen dims the scene, like the hotbar.
            GlassProgram.setShadowScale(mc.screen != null ? 0f : 1f);
            try {
                if (lens) {
                    // concentric liquid-glass capsule, TRUE semicircle ends (LENS = CORNER_FRAC 1.0)
                    GlassRenderer.lens(cx0, cy0, cx1, cy1, 1.0f, 0f, 0.902f, GlassRenderer.FROST_PANEL);
                } else {
                    HudGlass.glassBox(cx0, cy0, cx1, cy1, 0.9f);
                }
            } finally {
                GlassProgram.setShadowScale(1f);
            }

            // blue health fill, round ends (radius = half the bar height)
            if (fillW > 0) {
                boolean round = GlassProgram.ensureReady() && GlassProgram.roundUsable();
                if (round) {
                    GlassRenderer.roundRect(k, BAR_TOP, k + fillW, BAR_TOP + BAR_H, BAR_H / 2f, FILL);
                } else {
                    HudGlass.roundFill(k, BAR_TOP, fillW, BAR_H, BAR_H / 2, FILL);
                }
            }

            // boss name — vanilla position + shadow (information, unchanged)
            TextRenderer font = mc.textRenderer;
            String s = BossBar.name;
            font.drawWithShadow(s, (float) (sw / 2 - font.getWidth(s) / 2), (float) (BAR_TOP - 10), 0xFFFFFF);
            GlStateManager.color4f(1f, 1f, 1f, 1f);
        } catch (Throwable t) {
            // fall back to a flat bar so a boss fight never loses its health bar
            try {
                Window sr = new Window(mc);
                int sw = sr.getWidth();
                int k = sw / 2 - BAR_W / 2;
                int fillW = (int) (BossBar.health * (float) (BAR_W + 1));
                GuiElement.fill(k - MARGIN, BAR_TOP - MARGIN, k + BAR_W + MARGIN, BAR_TOP + BAR_H + MARGIN, 0x80101014);
                if (fillW > 0) GuiElement.fill(k, BAR_TOP, k + fillW, BAR_TOP + BAR_H, FILL);
                GlStateManager.color4f(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {}
        }
        return true;
    }
}
