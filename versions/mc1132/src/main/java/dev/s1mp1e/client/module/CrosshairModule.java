package dev.s1mp1e.client.module;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.gui.DrawableHelper;

/**
 * Custom crosshair — replaces the vanilla 15×15 sprite with shapes we draw ourselves
 * ({@code CrosshairMixin} cancels the vanilla crosshair — on 1.13.2 the private, unmapped
 * {@code InGameHud.method_18366(F)V} — and calls {@link #draw}).
 *
 * <p><b>FAIR PLAY.</b> The crosshair is a pure function of its settings and the screen
 * centre {@code (cx,cy)} handed in by the mixin — it NEVER reads {@code crosshairTarget},
 * the targeted entity/block, or any distance, so it cannot leak information the player
 * doesn't already have on screen. A crosshair that changed with a target would be a reach
 * indicator and is prohibited.
 *
 * <p>1.13.2 render (legacy yarn 1.13.2+build.604-v2, javap-verified): fixed-function immediate
 * mode. Rectangles go through the static {@link DrawableHelper#fill} (which ENDS with
 * {@code enableTexture + disableBlend}), rotation through the GL matrix stack
 * ({@code GlStateManager.pushMatrix/translate/rotate/popMatrix}, always balanced in a
 * {@code finally}). {@link #draw} restores blend + the standard GUI blend func and resets the
 * {@code GlStateManager} colour cache to white before returning, because vanilla's code after
 * the crosshair call (the {@code blendFuncSeparate} + the rest of the HUD) expects blending on.
 */
public final class CrosshairModule extends Module {

    /** Segment count for the ring; enough that a 20 px circle has no visible facets. */
    private static final int CIRCLE_SEGMENTS = 48;

    public final Setting shape     = add(Setting.mode("Shape", "Cross", "Cross", "T", "Dot", "Circle"));
    public final Setting size      = add(Setting.integer("Size", 4, 1, 20));
    public final Setting thick     = add(Setting.integer("Thickness", 1, 1, 5));
    public final Setting gap       = add(Setting.integer("Gap", 2, 0, 10));
    public final Setting rotation  = add(Setting.integer("Rotation", 0, 0, 45));
    public final Setting centerDot = add(Setting.bool("Center Dot", false));
    public final Setting dotSize   = add(Setting.integer("Dot Size", 2, 1, 6));
    public final Setting colour    = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting outline   = add(Setting.bool("Outline", true));
    public final Setting outlineC  = add(Setting.color("Outline Colour", 0xC0000000));

    public CrosshairModule() { super("Crosshair", "Visual"); }

    /** Draw the crosshair centred on {@code (cx,cy)} (screen centre) via immediate-mode fills. */
    public void draw(int cx, int cy) {
        try {
            drawShapes(cx, cy);
        } finally {
            restoreState();
        }
    }

    private void drawShapes(int cx, int cy) {
        final int colour = this.colour.colorValue;
        final int oColour = this.outlineC.colorValue;
        final boolean drawOutline = this.outline.boolValue;
        final int s = this.size.intValue, t = this.thick.intValue, g = this.gap.intValue, rot = this.rotation.intValue;
        final String mode = this.shape.modeValue;

        if ("Circle".equals(mode)) {
            float outer = g + s;
            float inner = Math.max(0f, outer - t);
            if (drawOutline) ring(cx, cy, Math.max(0f, inner - 1f), outer + 1f, oColour);
            ring(cx, cy, inner, outer, colour);
        } else {
            int[][] rects = "Dot".equals(mode) ? dotRects(cx, cy, t)
                          : "T".equals(mode)   ? tRects(cx, cy, s, t, g)
                                               : crossRects(cx, cy, s, t, g);
            boolean rotated = rot != 0 && !"Dot".equals(mode);
            if (rotated) {
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translate((float) cx, (float) cy, 0f);
                    GlStateManager.rotate((float) rot, 0f, 0f, 1f);
                    GlStateManager.translate((float) -cx, (float) -cy, 0f);
                    fillRects(rects, drawOutline, oColour, colour);
                } finally {
                    GlStateManager.popMatrix();
                }
            } else {
                fillRects(rects, drawOutline, oColour, colour);
            }
        }

        if (this.centerDot.boolValue && !"Dot".equals(mode)) {
            fillRects(dotRects(cx, cy, this.dotSize.intValue), drawOutline, oColour, colour);
        }
    }

    /** Two passes so an arm's outline never paints over a neighbour's fill (gap 0). */
    private static void fillRects(int[][] rects, boolean drawOutline, int oColour, int colour) {
        if (drawOutline) for (int[] r : rects) DrawableHelper.fill(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, oColour);
        for (int[] r : rects) DrawableHelper.fill(r[0], r[1], r[2], r[3], colour);
    }

    /** Annulus as {@link #CIRCLE_SEGMENTS} rotated fills (the same construction as the
     *  1.21.1 reference). Each segment is a band drawn at 12 o'clock in a frame rotated about
     *  the centre; a 1px overlap keeps segments touching. */
    private static void ring(int cx, int cy, float inner, float outer, int argb) {
        if (outer <= 0f) return;
        int b0 = Math.round(inner), b1 = Math.round(outer);
        if (b1 <= b0) b1 = b0 + 1;
        int K = CIRCLE_SEGMENTS;
        int half = Math.max(1, Math.round((float) (Math.PI * outer / K)) + 1);
        for (int i = 0; i < K; i++) {
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate((float) cx, (float) cy, 0f);
                GlStateManager.rotate(360f * i / K, 0f, 0f, 1f);
                DrawableHelper.fill(-half, -b1, half, -b0, argb);
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    /** {@code DrawableHelper.fill} leaves blend DISABLED and the colour cache at the last fill
     *  colour; vanilla's code after the crosshair call (and the HUD drawn after it) expects the
     *  GUI blend state, so put it back. The no-arg {@code clearColor()} invalidates the colour
     *  cache (1.13.2's clearCurrentColor) so the following {@code color(1,1,1,1)} is really issued
     *  to GL even if something between the fills desynchronised the cache. */
    private static void restoreState() {
        GlStateManager.enableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.clearColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ---- geometry (pure int math, identical to the 1.21.1 reference) ----
    //
    // CENTERING. The mixin hands us cx,cy = scaledWidth/2, scaledHeight/2 — a pixel BOUNDARY, not a
    // pixel. A 1-px-thick line therefore cannot straddle it; it must commit to one side, and whichever
    // side it picks is a half-pixel off. That half-pixel is unavoidable (it is the very reason vanilla's
    // own 15×15 crosshair, blitted at (dim-15)/2, sits ~1px off centre). What we CAN guarantee — and
    // what actually reads as "centred" to the eye — is that the four arms are EXACTLY equal in length and
    // gap: both bar bands are placed with a single {@code off = (t+1)/2}, biasing the sub-pixel the SAME
    // way vanilla does (up-left), and each arm is measured symmetrically from the band edge, so
    // left==right and up==down for every thickness.

    /** Vertical-bar / horizontal-bar offset from the centre boundary. {@code (t+1)/2} puts a 1-px mark on
     *  the up-left pixel of the boundary — matching vanilla's {@code (dim-15)/2} bias — and keeps an even
     *  mark centred on the boundary. */
    private static int barOffset(int t) { return (t + 1) / 2; }

    /** Cross: four arms of equal length {@code s}, each {@code g} px out from the bar edge, symmetric
     *  about the bar centre on both axes. */
    private static int[][] crossRects(int cx, int cy, int s, int t, int g) {
        int off = barOffset(t);
        int bx0 = cx - off, bx1 = bx0 + t;   // vertical bar columns
        int by0 = cy - off, by1 = by0 + t;   // horizontal bar rows
        return new int[][] {
            { bx0 - g - s, by0, bx0 - g,     by1 },   // left  (horizontal band)
            { bx1 + g,     by0, bx1 + g + s, by1 },   // right
            { bx0, by0 - g - s, bx1, by0 - g },       // up    (vertical band)
            { bx0, by1 + g,     bx1, by1 + g + s }    // down
        };
    }

    /** T-crosshair: the cross minus its up arm (opening upward). Same symmetric left/right/down arms. */
    private static int[][] tRects(int cx, int cy, int s, int t, int g) {
        int off = barOffset(t);
        int bx0 = cx - off, bx1 = bx0 + t;
        int by0 = cy - off, by1 = by0 + t;
        return new int[][] {
            { bx0 - g - s, by0, bx0 - g,     by1 },
            { bx1 + g,     by0, bx1 + g + s, by1 },
            { bx0, by1 + g,     bx1, by1 + g + s }
        };
    }

    /** Filled square of side {@code d}, centred on the same boundary bias as the bars. */
    private static int[][] dotRects(int cx, int cy, int d) {
        int off = barOffset(d);
        int x0 = cx - off, y0 = cy - off;
        return new int[][] { { x0, y0, x0 + d, y0 + d } };
    }
}
