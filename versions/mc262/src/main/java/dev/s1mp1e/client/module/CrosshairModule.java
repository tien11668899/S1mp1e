package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Custom crosshair — replaces the vanilla 15×15 sprite with shapes we draw ourselves
 * ({@code CrosshairHideMixin} cancels the vanilla {@code Hud.extractCrosshair} and calls
 * {@link #draw}).
 *
 * <p><b>FAIR PLAY.</b> The crosshair is a pure function of its settings and the screen
 * centre {@code (cx,cy)} handed in by the mixin — it NEVER reads {@code hitResult},
 * {@code crosshairPickEntity}, the targeted entity/block, or any distance, so it cannot leak
 * information the player doesn't already have on screen. A crosshair that changed with a
 * target would be a reach indicator and is prohibited.
 *
 * <p>26.2 port of mc1211: {@code DrawContext} → {@link GuiGraphicsExtractor}; the 4×4 matrix
 * stack + {@code RotationAxis.POSITIVE_Z.rotationDegrees} → the 2D {@code g.pose()}
 * ({@code Matrix3x2fStack}) with {@code rotate(radians)}. {@code g.fill} stores a copy of the
 * full 2D pose (rotation included) and transforms every vertex, so the rotated arms and the
 * ring segments render exactly as in mc1211.
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

    private static float rad(float deg) { return (float) Math.toRadians(deg); }

    /** Draw the crosshair centred on {@code (cx,cy)} (screen centre) via fills. */
    public void draw(GuiGraphicsExtractor g, int cx, int cy) {
        final int colour = this.colour.colorValue;
        final int oColour = this.outlineC.colorValue;
        final boolean drawOutline = this.outline.boolValue;
        final int s = this.size.intValue, t = this.thick.intValue, gp = this.gap.intValue, rot = this.rotation.intValue;
        final String mode = this.shape.modeValue;

        if ("Circle".equals(mode)) {
            float outer = gp + s;
            float inner = Math.max(0f, outer - t);
            if (drawOutline) ring(g, cx, cy, Math.max(0f, inner - 1f), outer + 1f, oColour);
            ring(g, cx, cy, inner, outer, colour);
        } else {
            int[][] rects = "Dot".equals(mode) ? dotRects(cx, cy, t)
                          : "T".equals(mode)   ? tRects(cx, cy, s, t, gp)
                                               : crossRects(cx, cy, s, t, gp);
            boolean rotated = rot != 0 && !"Dot".equals(mode);
            if (rotated) {
                g.pose().pushMatrix();
                g.pose().translate((float) cx, (float) cy);
                g.pose().rotate(rad((float) rot));
                g.pose().translate((float) -cx, (float) -cy);
            }
            try {
                // Two passes so an arm's outline never paints over a neighbour's fill (gap 0).
                if (drawOutline) for (int[] r : rects) g.fill(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, oColour);
                for (int[] r : rects) g.fill(r[0], r[1], r[2], r[3], colour);
            } finally {
                if (rotated) g.pose().popMatrix();
            }
        }

        if (this.centerDot.boolValue && !"Dot".equals(mode)) {
            int[][] d = dotRects(cx, cy, this.dotSize.intValue);
            if (drawOutline) for (int[] r : d) g.fill(r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, oColour);
            for (int[] r : d) g.fill(r[0], r[1], r[2], r[3], colour);
        }
    }

    /** Annulus as {@link #CIRCLE_SEGMENTS} rotated fills. Each segment is a band drawn at 12 o'clock in a
     *  frame rotated about the centre; a 1px overlap keeps segments touching. */
    private static void ring(GuiGraphicsExtractor g, int cx, int cy, float inner, float outer, int argb) {
        if (outer <= 0f) return;
        int b0 = Math.round(inner), b1 = Math.round(outer);
        if (b1 <= b0) b1 = b0 + 1;
        int K = CIRCLE_SEGMENTS;
        int half = Math.max(1, Math.round((float) (Math.PI * outer / K)) + 1);
        for (int i = 0; i < K; i++) {
            g.pose().pushMatrix();
            try {
                g.pose().translate((float) cx, (float) cy);
                g.pose().rotate(rad(360f * i / K));
                g.fill(-half, -b1, half, -b0, argb);
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    // ---- geometry (pure int math) ----
    //
    // CENTERING. The mixin hands us cx,cy = guiWidth/2, guiHeight/2 — a pixel BOUNDARY, not a pixel. A
    // 1-px-thick line therefore cannot straddle it; it must commit to one side, and whichever side it picks
    // is a half-pixel off. That half-pixel is unavoidable (it is the very reason vanilla's own 15×15
    // crosshair, blitted at (dim-15)/2, sits ~1px off centre). What we CAN guarantee — and what actually
    // reads as "centred" to the eye — is that the four arms are EXACTLY equal in length and gap. Both bar
    // bands are placed with a single {@code off = (t+1)/2}, biasing the sub-pixel the SAME way vanilla does
    // (up-left), and each arm is measured symmetrically from the band edge, so left==right and up==down for
    // every thickness. No per-arm fudge, no lean.

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
