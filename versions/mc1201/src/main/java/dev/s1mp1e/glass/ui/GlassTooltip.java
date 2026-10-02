package dev.s1mp1e.glass.ui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;

/**
 * Liquid-glass tooltip morph+crossfade — the 1.20.1 Fabric port of the 1.16.5
 * {@code dev.s1mp1e.glass.ui.GlassTooltip}, itself LiquidGlass26's
 * {@code TooltipGlass} + {@code TooltipGlassMixin}. Same architecture as the Forge
 * line: a stateless helper driven by a per-frame ghost pass, called in place of
 * the vanilla tooltip draw. Only the plumbing changes — the box math, the four
 * position/size springs (omega 27, critically damped), the appear/switch fades and
 * the alpha-8 text floor are byte-for-byte 26.2.
 *
 * <p>This file is a verbatim port of the 1.16.5 helper: it was ABSENT from the
 * 1.17.1 line's render/ui core and is required by System 4
 * ({@code ScreenTooltipMixin} + {@code InGameHudTooltipGhostMixin}). It is
 * <b>core-profile legal</b> — it draws only through {@link GlassRenderer} (the
 * already-ported core renderer), toggles depth via {@link RenderSystem} (a
 * core-profile API), and blits text through {@link TextRenderer}. It contains NO
 * {@code glBegin}/{@code GL_QUADS}/fixed-function calls, so unlike
 * {@code ScreenFade}/{@code MenuBackdrop} it needs no core rewrite and is not
 * stubbed.
 *
 * <p>1.17.1 (as 1.16.5) funnels every tooltip through
 * {@code Screen.renderOrderedTooltip(MatrixStack, List<? extends OrderedText>,
 * int, int)}, so this operates on {@code OrderedText} and takes the
 * {@code MatrixStack} for the text draw. Width comes from
 * {@code TextRenderer.getWidth(OrderedText)} ({@code method_30880}); lines are drawn
 * with {@code TextRenderer.drawWithShadow(MatrixStack, OrderedText, float, float,
 * int)} ({@code method_27517}) — both verified against yarn 1.17.1+build.65.
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
     * is unavailable (caller lets vanilla draw its flat tooltip). The 1.12.2 helper
     * fell back to {@code GuiUtils.drawHoveringText} internally; 1.17.1 has no such
     * helper, so the fallback is "return false, don't cancel".
     */
    public static boolean draw(DrawContext context, List<? extends OrderedText> lines,
                               int mouseX, int mouseY, int screenW, int screenH,
                               TextRenderer font) {
        if (lines == null || lines.isEmpty()) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;
        if (deferring) {
            // Feature E / R1: inside a screen render — record it and draw it as the LAST GUI layer of the frame
            // (after the screen, the autosave indicator and the TOASTS), see GameRendererTooltipLayerMixin.
            dLines = new ArrayList<OrderedText>(lines);
            dMouseX = mouseX; dMouseY = mouseY; dScreenW = screenW; dScreenH = screenH; dFont = font;
            dPending = true;
            return true;
        }
        return drawNow(context, lines, mouseX, mouseY, screenW, screenH, font);
    }

    // ---- top-layer deferral (feature E / R1) ------------------------------------------------------------------
    // In 1.21.1 GameRenderer.render draws the screen (renderWithTooltip), THEN the autosave indicator and the toasts;
    // a tooltip drawn inside the screen would therefore sit UNDER a toast. 26.2 promotes the tooltip strata to the end
    // of the frame; the immediate-mode equivalent is to record the tooltip during the screen render and draw it (and
    // the 150 ms fade-out ghost) right after the toasts — the very last GUI draw — with a fresh grab of everything
    // drawn below it, so the card refracts the GUI beneath it and nothing can draw over it.
    private static boolean deferring, dPending;
    private static List<OrderedText> dLines;
    private static int dMouseX, dMouseY, dScreenW, dScreenH;
    private static TextRenderer dFont;

    /** Open the deferral window (GameRenderer, just before {@code Screen.renderWithTooltip}). */
    public static void beginDefer() {
        deferring = true;
        dPending = false;
        dLines = null;
    }

    /**
     * Close the window and draw the recorded tooltip + the ghost as the top layer (GameRenderer, right after
     * {@code ToastManager.draw}). No-op when no window is open (no screen rendered this frame), so the ghost still runs
     * exactly once per frame: from here when a screen rendered, from the HUD tail otherwise.
     */
    public static void endDefer(DrawContext context) {
        if (!deferring) return;
        deferring = false;
        if (context == null) { dPending = false; dLines = null; return; }
        if (dPending) {
            dPending = false;
            List<OrderedText> l = dLines;
            dLines = null;
            drawNow(context, l, dMouseX, dMouseY, dScreenW, dScreenH, dFont);
        }
        ghostPass(context);
    }

    /** True while a screen is rendering inside the deferral window (the HUD-tail ghost pass must then stand down). */
    public static boolean deferring() { return deferring; }

    private static boolean drawNow(DrawContext context, List<? extends OrderedText> lines,
                                   int mouseX, int mouseY, int screenW, int screenH, TextRenderer font) {
        if (lines == null || lines.isEmpty() || font == null) return false;

        // ---- vanilla's exact box math --------------------------------------
        int textWidth = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lw = font.getWidth(lines.get(i));
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

        // Flush EVERY GUI draw buffered so far (slot items, stack counts, durability bars, the dim) to
        // the framebuffer before we touch it. Two reasons: (a) the refraction backdrop grabbed next must
        // actually contain those pixels, and (b) our panel is immediate raw GL — anything still sitting in
        // the DrawContext batch would otherwise flush AFTER the panel and paint over it. That un-flushed
        // batch is exactly why the tooltip was hiding behind the item icons it overlaps.
        context.draw();

        // Grab the GUI drawn so far (dim, container panel, slots, ITEMS) as the refraction backdrop, and
        // do it with grabNow() rather than the de-duplicated grab(): in a container screen the panel grabs
        // its own backdrop BEFORE the slots/items are drawn (world + dim only), so a deduped grab() here
        // would just fold onto that stale copy and the tooltip would refract the background instead of the
        // items sitting right behind it. grabNow() forces a fresh full-frame copy AFTER the context.draw()
        // above flushed the items — so the glass samples the actual inventory. The tooltip's own glass is
        // drawn just below this point, so it is never in the capture -> no self-ghosting.
        SceneCapture.grabNow();

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
        RenderSystem.disableDepthTest();
        drawPanel(alpha);

        int a = textAlphaByte();
        if (a >= 8) {
            int col = (a >= 252) ? 0xFFFFFFFF : ((a << 24) | 0xFFFFFF);
            // Lift the text onto the tooltip Z-layer. Vanilla's drawTooltip translates +400 here so the
            // tooltip sorts ABOVE the item models (drawn at Z~150) when the buffered text finally flushes;
            // because our @Inject cancels that vanilla method, we must re-apply the same lift ourselves —
            // otherwise the item icons the tooltip overlaps depth-test over the letters.
            // 1.20.1 port: 400 is no longer enough. The card is now drawn AFTER the toasts (top layer), and a toast
            // draws at Z 800 (ToastManager) — its scrim, icon and text write depth there, so letters at 400 failed the
            // depth test wherever the card overlapped a toast (seen in tt-toast: the end of the title was cut off).
            // 2000 clears the toast layer and its item icons; the GUI projection reaches Z 10000.
            context.getMatrices().push();
            context.getMatrices().translate(0f, 0f, 2000f);
            int ty = tooltipY;
            for (int i = 0; i < lines.size(); i++) {
                // 1.20: TextRenderer.drawWithShadow -> DrawContext.drawTextWithShadow
                // (OrderedText overload, int coords).
                context.drawTextWithShadow(font, lines.get(i), tooltipX, ty, col);
                if (i == 0) ty += 2; // vanilla's title gap
                ty += 10;
            }
            context.getMatrices().pop();
        }
        RenderSystem.enableDepthTest();
        return true;
    }

    /**
     * Per-frame tail pass: when no tooltip drew this frame, fade the panel out.
     * Called once per frame from {@code InGameHudTooltipGhostMixin} (the 1.17.1
     * analogue of {@code GlassTooltipHandler}'s overlay Post). Exactly one call per
     * frame is the only invariant — the active flag ping-pongs regardless of order.
     */
    public static void ghostPass() { ghostPass(null); }

    /**
     * {@link #ghostPass()} with the frame's {@link DrawContext}: when given (the top-layer call after the toasts), the
     * GUI drawn so far is flushed and freshly grabbed first, so the fading ghost refracts the GUI beneath it (not a
     * stale world grab) and sits on the very top layer like the live card (R1, R4).
     */
    public static void ghostPass(DrawContext context) {
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
            if (context != null) {
                context.draw();
                SceneCapture.grabNow();
            }
            RenderSystem.disableDepthTest();
            drawPanel(alpha);          // springs frozen at last pose, alpha decaying
            RenderSystem.enableDepthTest();
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
        greyScrim(x, y, w, h, a);
    }

    /**
     * Feature E — the grey readability scrim between the glass card and the text: RGB {@code 0x16161A}, peak alpha
     * {@code 0x48} (~28%), inset 1 px, radius {@code max(0, min(w,h)*0.23 - 1)} (the card corner 0.92 gives radius
     * {@code min(w,h)*0.23}, so this tracks it minus the 1 px inset). Drawn AFTER the card so it stacks above the glass
     * and below the text — keeps text legible while the card still refracts the GUI beneath it.
     */
    public static void greyScrim(int x, int y, int w, int h, float a) {
        int sa = Math.round(0x48 * (a < 0f ? 0f : (a > 1f ? 1f : a)));
        if (sa <= 0) return;
        float rIn = Math.max(0f, Math.min(w, h) * 0.23f - 1f);
        GlassRenderer.roundRect(x + 1, y + 1, x + w - 1, y + h - 1, rIn, (sa << 24) | 0x16161A);
    }
}
