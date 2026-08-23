package dev.s1mp1e.glass.ui;

import java.util.List;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.font.TextRenderer;

/**
 * Liquid-glass tooltip morph+crossfade — the 1.13.2 (Legacy Fabric) port of the
 * 1.16.5 {@code dev.s1mp1e.glass.ui.GlassTooltip}, itself LiquidGlass26's
 * {@code TooltipGlass} + {@code TooltipGlassMixin}. Same stateless-helper-driven-by-
 * a-ghost-pass architecture as every other tier. The box math, the four
 * position/size springs (omega 27, critically damped), the appear/switch fades and
 * the alpha-8 text floor are byte-for-byte 26.2.
 *
 * <h3>1.13.2 tier deltas vs the 1.16.5 source (behaviour unchanged)</h3>
 * <ul>
 *   <li>1.16.5 funnelled every tooltip through
 *       {@code Screen.renderOrderedTooltip(MatrixStack, List<? extends OrderedText>,
 *       int, int)}. 1.13.2 predates {@code OrderedText} and {@code MatrixStack}: the
 *       choke point is {@code Screen.renderTooltip(List<String>, int, int)}
 *       (intermediary {@code method_6754}), so this operates on {@code List<String>}
 *       and takes no matrix stack.</li>
 *   <li>Width comes from {@code TextRenderer.getStringWidth(String)}
 *       ({@code method_954}); lines are drawn with
 *       {@code TextRenderer.drawWithShadow(String, float, float, int)}
 *       ({@code method_956}).</li>
 *   <li>{@code RenderSystem} does not exist in 1.13.2 — depth is toggled through
 *       {@code GlStateManager.disableDepthTest()/enableDepthTest()}
 *       ({@code method_xxxx}, both mapped) exactly as the render core does.</li>
 * </ul>
 */
public final class GlassTooltip {

    private GlassTooltip() {}

    /** 26.2's tooltip padding: content box + 3 px on every side. */
    private static final int PADDING = 3;

    // --- morph state (26.2 TooltipGlass, verbatim) --------------------------
    private static Spring sx, sy, sw, sh;   // omega 27, critically damped
    private static final Fade panelFade = new Fade(0f, 150f);
    private static final Fade textFade  = new Fade(1f, 150f);
    private static float alpha = 0f;
    private static boolean activeThisFrame = false;

    /**
     * Draw the glass tooltip for {@code lines} at the cursor. Returns {@code true}
     * when the glass drew (caller cancels vanilla); {@code false} when the pipeline
     * is unavailable (caller lets vanilla draw its flat tooltip).
     */
    public static boolean draw(List<String> lines,
                               int mouseX, int mouseY, int screenW, int screenH,
                               TextRenderer font) {
        if (lines == null || lines.isEmpty()) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;

        // ---- vanilla's exact box math --------------------------------------
        int textWidth = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lw = font.getStringWidth(lines.get(i));
            if (lw > textWidth) textWidth = lw;
        }
        int tooltipX = mouseX + 12;
        if (tooltipX + textWidth + 4 > screenW) {
            tooltipX = mouseX - 16 - textWidth;
            if (tooltipX < 4) tooltipX = 4;
        }
        int tooltipY = mouseY - 12;
        int tooltipHeight = 8;
        if (lines.size() > 1) {
            tooltipHeight += (lines.size() - 1) * 10 + 2;
        }
        if (tooltipY + tooltipHeight + 6 > screenH) {
            tooltipY = screenH - tooltipHeight - 6;
        }

        // Grab the GUI drawn so far (slots, items, dimmer) as the refraction
        // backdrop. Tooltips draw LAST, so the framebuffer does not yet contain
        // this tooltip -> no self-ghosting.
        SceneCapture.grab();

        // ---- panel geometry: content box + PADDING 3 -----------------------
        int x0 = tooltipX - PADDING;
        int y0 = tooltipY - PADDING;
        int w  = textWidth + PADDING * 2;
        int h  = tooltipHeight + PADDING * 2;

        // ---- springs (26.2 TooltipGlass, verbatim) -------------------------
        if (alpha <= 0.02f || sx == null) {
            sx = new Spring(x0, Spring.OMEGA_SLOW, Spring.DAMPING);
            sy = new Spring(y0, Spring.OMEGA_SLOW, Spring.DAMPING);
            sw = new Spring(w,  Spring.OMEGA_SLOW, Spring.DAMPING);
            sh = new Spring(h,  Spring.OMEGA_SLOW, Spring.DAMPING);
            textFade.snap(0f);
        } else {
            if (Math.abs(sw.target() - w) > 2f || Math.abs(sh.target() - h) > 2f
                    || Math.abs(sx.target() - x0) > 6f || Math.abs(sy.target() - y0) > 6f) {
                textFade.snap(0f);
            }
            sx.setTarget(x0); sy.setTarget(y0);
            sw.setTarget(w);  sh.setTarget(h);
        }
        textFade.to(1f);
        sx.advance(1f / 60f); sy.advance(1f / 60f);
        sw.advance(1f / 60f); sh.advance(1f / 60f);
        panelFade.to(1f);
        alpha = panelFade.value();
        activeThisFrame = true;

        // ---- draw: glass panel at spring pose, text at final layout --------
        GlStateManager.disableDepthTest();
        drawPanel(alpha);

        int a = textAlphaByte();
        if (a >= 8) {
            int col = (a >= 252) ? 0xFFFFFFFF : ((a << 24) | 0xFFFFFF);
            int ty = tooltipY;
            for (int i = 0; i < lines.size(); i++) {
                font.drawWithShadow(lines.get(i), (float) tooltipX, (float) ty, col);
                if (i == 0) ty += 2; // vanilla's title gap
                ty += 10;
            }
        }
        GlStateManager.enableDepthTest();
        return true;
    }

    /**
     * Per-frame tail pass: when no tooltip drew this frame, fade the panel out.
     * Called once per frame from {@code InGameHudTooltipGhostMixin}. Exactly one
     * call per frame is the only invariant — the active flag ping-pongs regardless
     * of order.
     */
    public static void ghostPass() {
        if (activeThisFrame) {
            activeThisFrame = false;
            return;
        }
        if (alpha <= 0.02f || sx == null || !GlassProgram.usable()) {
            alpha = 0f;
            return;
        }
        panelFade.to(0f);
        alpha = panelFade.value();
        if (alpha > 0.02f) {
            GlStateManager.disableDepthTest();
            drawPanel(alpha);          // springs frozen at last pose, alpha decaying
            GlStateManager.enableDepthTest();
        }
    }

    /** Text alpha byte for tooltip lines (255 = no fade active). */
    public static int textAlphaByte() {
        if (!GlassProgram.usable() || sx == null) return 255;
        return Math.round(Math.min(panelFade.value(), textFade.value()) * 255f) & 0xFF;
    }

    private static void drawPanel(float a) {
        int x = Math.round(sx.value());
        int y = Math.round(sy.value());
        int w = Math.round(sw.value());
        int h = Math.round(sh.value());
        // pad 8, corner 0.92, no lift, frosted panel
        GlassRenderer.glass(x, y, x + w, y + h, 8f, 0.92f, 0f, a, GlassRenderer.FROST_PANEL);
    }
}
