package dev.s1mp1e.glass.ui;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraftforge.fml.client.config.GuiUtils;

/**
 * Liquid-glass tooltip — the 1.12.2 counterpart of LiquidGlass26's
 * {@code TooltipGlass} + {@code TooltipGlassMixin} + {@code ClientTextTooltipMixin},
 * fused into one drop-in replacement for
 * {@link net.minecraftforge.fml.client.config.GuiUtils#drawHoveringText}.
 *
 * <p>1.12.2's {@code RenderTooltipEvent} only lets a listener retune vanilla's
 * layout and colours — it cannot replace the draw — and the real work happens
 * deep inside {@code GuiUtils.drawHoveringText}, where nothing can cancel it. So
 * instead of intercepting we expose this helper and the coremod splices it onto
 * the head of {@code GuiScreen.drawHoveringText}, through which every vanilla
 * tooltip funnels. It computes the very same
 * box vanilla computes, runs the morph springs, draws the glass panel, then
 * draws the text lines itself with the panel's faded alpha.
 *
 * <p>Everything else is copied verbatim from 26.2:
 * <ul>
 *   <li>four springs — position {@code x,y} and size {@code w,h} — all at
 *       {@code omega 27}, critically damped (~150 ms settle);</li>
 *   <li>fresh appear (alpha was &le; 0.02) SNAPS all four springs and resets
 *       {@code textAlpha = 0};</li>
 *   <li>a content change (|Δw|&gt;2 || |Δh|&gt;2 || |Δx|&gt;6 || |Δy|&gt;6)
 *       resets {@code textAlpha = 0} so the new text fades in;</li>
 *   <li>{@code textAlpha} ramps +0.11/frame; panel {@code alpha} ramps
 *       +0.16/frame on appear and -0.16/frame on the fade-out ghost pass;</li>
 *   <li>the drawn rect is the content box expanded by {@code PADDING 3} only —
 *       our shader draws its own drop shadow, so the sprite's baked 9 px shadow
 *       margin is NOT included;</li>
 *   <li>text below alpha 8 is skipped — MC's font treats alpha &lt; 4 as fully
 *       opaque, so near-invisible lines would otherwise pop to solid.</li>
 * </ul>
 */
public final class GlassTooltip {

    private GlassTooltip() {}

    /** 26.2's tooltip padding: content box + 3 px on every side. */
    private static final int PADDING = 3;

    // --- morph state (26.2 TooltipGlass, verbatim) --------------------------
    private static Spring sx, sy, sw, sh;   // omega 27, critically damped
    /** Panel opacity — eased and interruptible (hover away then back mid-fade
     *  continues from the current value instead of replaying from 0). */
    private static final Fade panelFade = new Fade(0f, 150f);
    /** Text opacity — dips to 0 on a content switch, then eases back in. */
    private static final Fade textFade  = new Fade(1f, 150f);
    private static float alpha = 0f;        // cached panelFade value this frame
    private static boolean activeThisFrame = false;

    /**
     * Drop-in for {@code GuiUtils.drawHoveringText}: draws the glass tooltip for
     * {@code lines} at the cursor. If the glass pipeline can't run it falls back
     * to the vanilla hovering text so tooltips always show.
     */
    public static void draw(List<String> lines, int mouseX, int mouseY,
                            int screenW, int screenH, FontRenderer font) {
        if (lines == null || lines.isEmpty()) return;

        // Pipeline down (driver/compile) -> plain vanilla tooltip, no glass.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            GuiUtils.drawHoveringText(lines, mouseX, mouseY, screenW, screenH, -1, font);
            return;
        }

        // GuiUtils.drawHoveringText's own GL prelude: item lighting/rescale off and
        // depth off, so the text draws pure white (not shaded by the item lighting
        // GuiContainer leaves enabled when it calls renderToolTip). Blend stays on.
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // ---- vanilla's exact box math (GuiUtils.drawHoveringText) ----------
        List<String> textLines = lines;
        // widest line
        int textWidth = 0;
        for (int i = 0; i < textLines.size(); i++) {
            int lw = font.getStringWidth(textLines.get(i));
            if (lw > textWidth) textWidth = lw;
        }
        // horizontal placement + the standard flip when near the right edge
        boolean needsWrap = false;
        int titleLinesCount = 1;
        int tooltipX = mouseX + 12;
        if (tooltipX + textWidth + 4 > screenW) {
            tooltipX = mouseX - 16 - textWidth;
            if (tooltipX < 4) {   // does not fit either side: wrap like GuiUtils
                textWidth = mouseX > screenW / 2 ? mouseX - 12 - 8 : screenW - 16 - mouseX;
                needsWrap = true;
            }
        }
        if (needsWrap) {
            int wrapped = 0;
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < textLines.size(); i++) {
                List<String> parts = font.listFormattedStringToWidth(textLines.get(i), textWidth);
                if (i == 0) titleLinesCount = parts.size();
                for (int j = 0; j < parts.size(); j++) {
                    String line = parts.get(j);
                    int lw = font.getStringWidth(line);
                    if (lw > wrapped) wrapped = lw;
                    out.add(line);
                }
            }
            textWidth = wrapped;
            textLines = out;
            tooltipX = mouseX > screenW / 2 ? mouseX - 16 - textWidth : mouseX + 12;
        }
        // vertical placement + clamp so it stays on screen.
        // Vanilla (GuiUtils) line box: base 8 px, +10 px per extra line, +2 px gap
        // after the title block. We must match it so the glass panel and the text
        // register exactly over a real vanilla tooltip.
        int tooltipY = mouseY - 12;
        int tooltipHeight = 8;
        if (textLines.size() > 1) {
            tooltipHeight += (textLines.size() - 1) * 10;
            if (textLines.size() > titleLinesCount) tooltipHeight += 2;
        }
        if (tooltipY + tooltipHeight + 6 > screenH) {
            tooltipY = screenH - tooltipHeight - 6;
        }

        // Grab the GUI drawn so far (slots, items, dimmer) as the refraction
        // backdrop. Tooltips draw LAST in a screen, so the framebuffer is complete
        // and does not yet contain this tooltip -> no self-ghosting.
        SceneCapture.forceGrab();

        // ---- panel geometry: content box + PADDING 3 -----------------------
        int x0 = tooltipX - PADDING;
        int y0 = tooltipY - PADDING;
        int w  = textWidth + PADDING * 2;
        int h  = tooltipHeight + PADDING * 2;

        // ---- springs (26.2 TooltipGlass, verbatim) -------------------------
        if (alpha <= 0.02f || sx == null) {
            // fresh appear: snap into place, fade the text in from 0
            sx = new Spring(x0, Spring.OMEGA_SLOW, Spring.DAMPING);
            sy = new Spring(y0, Spring.OMEGA_SLOW, Spring.DAMPING);
            sw = new Spring(w,  Spring.OMEGA_SLOW, Spring.DAMPING);
            sh = new Spring(h,  Spring.OMEGA_SLOW, Spring.DAMPING);
            textFade.snap(0f);
        } else {
            // content switch -> new text fades in from 0 (~150 ms)
            if (Math.abs(sw.target() - w) > 2f || Math.abs(sh.target() - h) > 2f
                    || Math.abs(sx.target() - x0) > 6f || Math.abs(sy.target() - y0) > 6f) {
                textFade.snap(0f);
            }
            sx.setTarget(x0); sy.setTarget(y0);
            sw.setTarget(w);  sh.setTarget(h);
        }
        textFade.to(1f);
        // 26.2 integrates a fixed 1/60 s here (sub-stepped at 1/120, NaN-guarded).
        sx.advance(1f / 60f); sy.advance(1f / 60f);
        sw.advance(1f / 60f); sh.advance(1f / 60f);
        panelFade.to(1f);
        alpha = panelFade.value();
        activeThisFrame = true;

        // ---- draw: glass panel at spring pose, text at final layout --------
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        drawPanel(alpha);
        GlStateManager.color(1f, 1f, 1f, 1f);

        int a = textAlphaByte();
        if (a >= 8) {
            int col = (a >= 252) ? 0xFFFFFFFF : ((a << 24) | 0xFFFFFF);
            int ty = tooltipY;
            for (int i = 0; i < textLines.size(); i++) {
                font.drawStringWithShadow(textLines.get(i), (float) tooltipX, (float) ty, col);
                if (i + 1 == titleLinesCount) ty += 2; // vanilla's title/body gap
                ty += 10;
            }
        }

        // GuiUtils epilogue: restore item lighting/rescale + depth. Blend stays on.
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        RenderHelper.enableStandardItemLighting();
        GlStateManager.enableRescaleNormal();
    }

    /**
     * Per-frame tail pass: when no tooltip drew this frame, fade the panel out.
     * Called once per frame from {@code GlassTooltipHandler}. Critically, this is
     * what decays {@code alpha} back to 0 so the NEXT appear snaps fresh instead
     * of morphing in from a stale box.
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
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            drawPanel(alpha);          // springs frozen at last pose, alpha decaying
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableDepth();
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
        // pad 8, corner 0.92 (glass.fsh radius = min(w,h)*0.25*0.92 = min(w,h)*0.23,
        // == 26.2's tooltip radius — do NOT change it, R2), no lift, frosted panel.
        GlassRenderer.glass(x, y, x + w, y + h, 8f, 0.92f, 0f, a, GlassRenderer.FROST_PANEL);
        // Grey readability scrim (26.2 §2E): RGB 0x16161A, peak alpha 0x48, inset
        // 1 px, radius max(0, min(w,h)*0.23 - 1). Stacks ABOVE the glass and BELOW
        // the text so light glyphs stay legible over a bright refracted GUI. Drawn
        // with the AA ROUND SDF (no backdrop) so it never flickers.
        int scrimA = Math.round(0x48 * a) & 0xFF;
        if (scrimA > 0) {
            int scrimArgb = (scrimA << 24) | 0x16161A;
            float radius = Math.max(0f, Math.min(w, h) * 0.23f - 1f);
            GlassRenderer.roundRect(x + 1, y + 1, x + w - 1f, y + h - 1f, radius, scrimArgb);
        }
    }
}
