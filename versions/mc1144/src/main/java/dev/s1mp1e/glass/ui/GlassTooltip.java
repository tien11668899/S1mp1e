package dev.s1mp1e.glass.ui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.DiffuseLighting;

/**
 * Liquid-glass tooltip morph+crossfade — the 1.14.4 Fabric port of the 1.8.9/1.12.2
 * {@code dev.s1mp1e.glass.ui.GlassTooltip}, itself LiquidGlass26's {@code TooltipGlass} +
 * {@code TooltipGlassMixin}. Same architecture as every other version: a stateless helper driven
 * by a per-frame ghost pass, called in place of the vanilla tooltip draw. The 1.8.9 body is reused
 * almost verbatim because both tiers draw through the same fixed-function immediate mode — only
 * the box math is re-derived from THIS version's vanilla and the GL bracket is this version's.
 *
 * <p>Everything else is 26.2:
 * <ul>
 *   <li>four springs — position {@code x,y} and size {@code w,h} — all at {@code omega 27},
 *       critically damped (~150 ms settle);</li>
 *   <li>fresh appear (alpha was &le; 0.02) SNAPS all four springs and resets the text fade to 0;</li>
 *   <li>a content change (|&Delta;w|&gt;2 || |&Delta;h|&gt;2 || |&Delta;x|&gt;6 || |&Delta;y|&gt;6)
 *       resets the text fade so the new text fades in;</li>
 *   <li>the drawn rect is the content box expanded by {@code PADDING 3} only — our shader draws its
 *       own drop shadow, so vanilla's baked shadow margin is NOT included;</li>
 *   <li>text below alpha 8 is skipped — MC's font treats {@code (argb &amp; 0xFC000000) == 0} as
 *       fully opaque, so near-invisible lines would otherwise pop to solid.</li>
 * </ul>
 *
 * <p><b>1.14.4 box math.</b> Taken from {@code Screen.renderTooltip(List<String>,int,int)} so the
 * glass panel registers exactly over the box vanilla would have drawn: {@code x+12 / y-12}, height
 * {@code 8} plus {@code 2 + (n-1)*10} for multi-line, the flip {@code tooltipX -= 28 + textWidth}
 * when it would overflow the right edge, and the {@code height - h - 6} clamp at the bottom. The
 * one addition is the 1.8.9 line's wrap: 1.14.4 lets a tooltip that fits on neither side run off
 * the left edge, so when the flip lands below {@code x = 4} the lines are re-wrapped to the room
 * that is actually available. Since vanilla's own draw is cancelled there is no text to
 * mis-register against.
 */
public final class GlassTooltip {

    private GlassTooltip() {}

    /** 26.2's tooltip padding: content box + 3 px on every side. */
    private static final int PADDING = 3;

    /** Grey readability scrim under the tooltip text (over the glass): colour + peak alpha.
     *  26.2 TooltipGlass: RGB 0x16161A, peak 0x48 (~28 %, "灰底不透明低一點"). */
    private static final int SCRIM_RGB   = 0x16161A;
    private static final int SCRIM_ALPHA = 0x48;

    // --- morph state (26.2 TooltipGlass, verbatim) --------------------------
    private static Spring sx, sy, sw, sh;   // omega 27, critically damped
    /** Panel opacity — eased and interruptible (hover away then back mid-fade continues from the
     *  current value instead of replaying from 0). */
    private static final Fade panelFade = new Fade(0f, 150f);
    /** Text opacity — dips to 0 on a content switch, then eases back in. */
    private static final Fade textFade  = new Fade(1f, 150f);
    private static float alpha = 0f;        // cached panelFade value this frame
    private static boolean activeThisFrame = false;

    /**
     * Draw the glass tooltip for {@code lines} at the cursor. Returns {@code true} when the glass
     * drew (the caller cancels vanilla); {@code false} when the pipeline is unavailable, so the
     * caller lets vanilla draw its flat tooltip.
     */
    public static boolean draw(List<String> lines, int mouseX, int mouseY,
                               int screenW, int screenH, TextRenderer font) {
        if (lines == null || lines.isEmpty() || font == null) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;

        // ---- vanilla's exact box math (Screen.renderTooltip(List,int,int)) --
        List<String> textLines = lines;
        int textWidth = 0;
        for (int i = 0; i < textLines.size(); i++) {
            int lw = font.getStringWidth(textLines.get(i));
            if (lw > textWidth) textWidth = lw;
        }
        int titleLinesCount = 1;
        int tooltipX = mouseX + 12;
        if (tooltipX + textWidth > screenW) {
            tooltipX -= 28 + textWidth;
        }
        if (tooltipX < 4) {
            // Fits on neither side. Vanilla would run off the left edge; wrap to the wider half
            // instead (the 1.8.9 line's behaviour), then re-place on that side.
            textWidth = mouseX > screenW / 2 ? mouseX - 12 - 8 : screenW - 16 - mouseX;
            if (textWidth < 16) textWidth = 16;
            int wrapped = 0;
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < textLines.size(); i++) {
                List<String> parts = font.wrapStringToWidthAsList(textLines.get(i), textWidth);
                if (parts.isEmpty()) parts = java.util.Collections.singletonList(textLines.get(i));
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
            if (tooltipX < 4) tooltipX = 4;
        }
        int tooltipY = mouseY - 12;
        int tooltipHeight = 8;
        if (textLines.size() > 1) {
            tooltipHeight += (textLines.size() - 1) * 10;
            if (textLines.size() > titleLinesCount) tooltipHeight += 2;
        }
        if (tooltipY + tooltipHeight + 6 > screenH) {
            tooltipY = screenH - tooltipHeight - 6;
        }

        // Vanilla's own GL prelude for this method: item lighting / rescale off and depth off, so
        // the text draws pure white rather than shaded by the item lighting a ContainerScreen
        // leaves enabled when it calls drawMouseoverTooltip. Blend stays on (hard rule 5).
        GlStateManager.disableRescaleNormal();
        DiffuseLighting.disable();
        GlStateManager.disableLighting();
        GlStateManager.disableDepthTest();

        // Grab the GUI drawn so far (slots, items, dimmer) as the refraction backdrop. Tooltips
        // draw LAST in a screen, so the framebuffer is complete and does not yet contain this
        // tooltip -> no self-ghosting. FORCE it: a container screen already ran a deduped grab of
        // the world this frame, and without the force the 3 ms guard would fold this one into it,
        // leaving the tooltip refracting the world instead of the inventory it sits on.
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
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        drawPanel(alpha);
        GlStateManager.color4f(1f, 1f, 1f, 1f);

        int a = textAlphaByte();
        if (a >= 8) {
            int col = (a >= 252) ? 0xFFFFFFFF : ((a << 24) | 0xFFFFFF);
            int ty = tooltipY;
            for (int i = 0; i < textLines.size(); i++) {
                font.drawWithShadow(textLines.get(i), (float) tooltipX, (float) ty, col);
                if (i + 1 == titleLinesCount) ty += 2;   // vanilla's title/body gap
                ty += 10;
            }
        }

        // Vanilla's epilogue: restore item lighting / rescale + depth. Blend stays on.
        GlStateManager.enableLighting();
        GlStateManager.enableDepthTest();
        DiffuseLighting.enable();
        GlStateManager.enableRescaleNormal();
        return true;
    }

    /**
     * Per-frame tail pass: when no tooltip drew this frame, fade the panel out. Called once per
     * frame from {@code InGameHudTooltipGhostMixin}. Critically, this is what decays {@code alpha}
     * back to 0 so the NEXT appear snaps fresh instead of morphing in from a stale box. Exactly one
     * call per frame is the only invariant — the active flag ping-pongs regardless of order.
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
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(770, 771, 1, 0);
            drawPanel(alpha);          // springs frozen at last pose, alpha decaying
            GlStateManager.color4f(1f, 1f, 1f, 1f);
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
        // pad 8, corner 0.92 (~26.2's 0xEB/255 knob -> radius = min(w,h)*0.23), no lift, frosted panel
        GlassRenderer.glass(x, y, x + w, y + h, 8f, 0.92f, 0f, a, GlassRenderer.FROST_PANEL);
        // Readability scrim (26.2 TooltipGlass, liquid-glass rule 2): a faint grey rounded rect
        // ON TOP of the glass and UNDER the text, so busy refracted content below (the next row of
        // items + count digits) never fights the tooltip lines. Added AFTER the card so it stacks
        // above the glass; alpha scales with the panel fade so it appears / ghosts out with it.
        // Inset 1 px, radius = the card radius (min(w,h)*0.23) minus the inset.
        int sa = Math.round(SCRIM_ALPHA * a) & 0xFF;
        if (sa > 0 && w > 2 && h > 2) {
            float r = Math.max(0f, Math.min(w, h) * 0.23f - 1f);
            GlassRenderer.roundRect(x + 1, y + 1, x + w - 1, y + h - 1, r, (sa << 24) | SCRIM_RGB);
        }
    }
}
