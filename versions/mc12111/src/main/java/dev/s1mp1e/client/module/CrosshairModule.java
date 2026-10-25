package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Custom crosshair — replaces the vanilla 15×15 sprite with shapes we draw ourselves
 * ({@code CrosshairHideMixin} cancels the vanilla {@code Gui.renderCrosshair} and calls
 * {@link #draw}).
 *
 * <p><b>FAIR PLAY.</b> The crosshair is a pure function of its settings and the screen
 * centre {@code (cx,cy)} handed in by the mixin — it NEVER reads {@code hitResult},
 * {@code crosshairPickEntity}, the targeted entity/block, or any distance, so it cannot leak
 * information the player doesn't already have on screen. A crosshair that changed with a
 * target would be a reach indicator and is prohibited.
 *
 * <p><b>Fine units.</b> Every length is a decimal in GUI pixels, but the shape is laid out in PHYSICAL pixels: the
 * pose is scaled by 1/guiScale around the centre and each length is rounded to whole physical pixels once. A fill
 * edge that fell between physical pixels would snap differently per arm (one arm a pixel fatter than its opposite);
 * rounding the inputs keeps the four arms exactly equal while giving 1/guiScale steps (0.5 at scale 2, 0.33 at 3).
 */
public final class CrosshairModule extends Module {

    /** Segment count for the ring (physical-pixel frame, so a bit finer than the GUI-pixel version). */
    private static final int CIRCLE_SEGMENTS = 64;

    public final Setting shape     = add(Setting.mode("Shape", "Cross", "Cross", "T", "Dot", "Circle"));
    public final Setting size      = add(Setting.number("Size", 4.0, 0.0, 20.0));
    /** Vertical arm length; 0 = same as {@link #size}. */
    public final Setting vSize     = add(Setting.number("Vertical Size", 0.0, 0.0, 20.0));
    public final Setting thick     = add(Setting.number("Thickness", 1.0, 0.25, 6.0));
    public final Setting gap       = add(Setting.number("Gap", 2.0, -3.0, 12.0));
    public final Setting rotation  = add(Setting.number("Rotation", 0.0, 0.0, 90.0));
    public final Setting armTop    = add(Setting.bool("Top Arm", true));
    public final Setting armBottom = add(Setting.bool("Bottom Arm", true));
    public final Setting armLeft   = add(Setting.bool("Left Arm", true));
    public final Setting armRight  = add(Setting.bool("Right Arm", true));
    public final Setting centerDot = add(Setting.bool("Center Dot", false));
    public final Setting dotSize   = add(Setting.number("Dot Size", 2.0, 0.25, 8.0));
    public final Setting colour    = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting outline   = add(Setting.bool("Outline", true));
    public final Setting outlineW  = add(Setting.number("Outline Thickness", 1.0, 0.25, 4.0));
    public final Setting outlineC  = add(Setting.color("Outline Colour", 0xC0000000));

    public CrosshairModule() { super("Crosshair", "Visual"); }

    private static float rad(float deg) { return (float) Math.toRadians(deg); }

    private static int guiScale() {
        try { return Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale())); }
        catch (Throwable t) { return 2; }
    }

    /** GUI-pixel length -> whole physical pixels. */
    private static int px(double guiPx, int scale) { return (int) Math.round(guiPx * scale); }

    /** The shape in physical pixels: horizontal / vertical arm length, thickness, gap, outline width. */
    private int[] dims(int sc) {
        int t = Math.max(1, px(thick.doubleValue, sc));
        int h = Math.max(0, px(size.doubleValue, sc));
        int v = vSize.doubleValue > 0 ? Math.max(0, px(vSize.doubleValue, sc)) : h;
        int g = px(gap.doubleValue, sc);
        int o = outline.boolValue ? Math.max(1, px(outlineW.doubleValue, sc)) : 0;
        return new int[] { h, v, t, g, o };
    }

    /** Draw the crosshair centred on {@code (cx,cy)} (screen centre, GUI px) via fills. */
    public void draw(GuiGraphics g, int cx, int cy) {
        final int sc = guiScale();
        final int[] d = dims(sc);
        final int h = d[0], v = d[1], t = d[2], gp = d[3], o = d[4];
        final int colour = this.colour.colorValue;
        final int oColour = this.outlineC.colorValue;
        final String mode = this.shape.modeValue;
        final float rot = (float) this.rotation.doubleValue;

        g.pose().pushMatrix();
        try {
            // origin = the centre boundary, 1 unit = 1 physical pixel
            g.pose().translate((float) cx, (float) cy);
            g.pose().scale(1f / sc, 1f / sc);

            if ("Circle".equals(mode)) {
                int outer = Math.max(1, gp + h);
                int inner = Math.max(0, outer - t);
                if (o > 0) ring(g, Math.max(0, inner - o), outer + o, oColour);
                ring(g, inner, outer, colour);
            } else {
                int[][] rects = "Dot".equals(mode) ? dotRects(t)
                              : armRects(h, v, t, gp, !"T".equals(mode) && armTop.boolValue,
                                         armBottom.boolValue, armLeft.boolValue, armRight.boolValue);
                boolean rotated = rot != 0f && !"Dot".equals(mode);
                if (rotated) { g.pose().pushMatrix(); g.pose().rotate(rad(rot)); }
                try {
                    // two passes so an arm's outline never paints over a neighbour's fill (gap 0 / negative)
                    if (o > 0) for (int[] r : rects) g.fill(r[0] - o, r[1] - o, r[2] + o, r[3] + o, oColour);
                    for (int[] r : rects) g.fill(r[0], r[1], r[2], r[3], colour);
                } finally {
                    if (rotated) g.pose().popMatrix();
                }
            }

            if (this.centerDot.boolValue && !"Dot".equals(mode)) {
                int[][] dot = dotRects(Math.max(1, px(dotSize.doubleValue, sc)));
                if (o > 0) for (int[] r : dot) g.fill(r[0] - o, r[1] - o, r[2] + o, r[3] + o, oColour);
                for (int[] r : dot) g.fill(r[0], r[1], r[2], r[3], colour);
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * The drawn crosshair for {@code AttackRingModule}'s "Wrap" shape, in GUI px:
     * {cx, cy, reach, armHalfThickness, rotationDeg, round, gap}. Uses the same physical-pixel rounding as
     * {@link #draw}, so the wrap hugs exactly what is on screen.
     */
    public float[] geom(int gw, int gh) {
        int sc = guiScale();
        int[] d = dims(sc);
        float h = d[0], v = d[1], t = d[2], gp = d[3], o = d[4];
        int icx = gw / 2, icy = gh / 2;
        // the bars sit at -(t+1)/2 physical px from the boundary: their true centre is that plus t/2
        float off = ((d[2] + 1) / 2) - t / 2f;
        float ccx = icx - off / sc, ccy = icy - off / sc;
        String mode = shape.modeValue;
        if ("Circle".equals(mode)) return new float[] { icx, icy, (gp + h + o) / sc, 0f, 0f, 1f, 0f };
        if ("Dot".equals(mode))    return new float[] { ccx, ccy, (t * 0.7072f + o) / sc, 0f, 0f, 1f, 0f };
        float reach = t / 2f + gp + Math.max(h, v) + o;
        return new float[] { ccx, ccy, reach / sc, (t / 2f + o) / sc, (float) rotation.doubleValue, 0f, Math.max(0f, gp) / sc };
    }

    /** Annulus as {@link #CIRCLE_SEGMENTS} rotated fills (origin = centre, physical px); 1 px overlap between segments. */
    private static void ring(GuiGraphics g, int inner, int outer, int argb) {
        if (outer <= 0) return;
        if (outer <= inner) outer = inner + 1;
        int K = CIRCLE_SEGMENTS;
        int half = Math.max(1, Math.round((float) (Math.PI * outer / K)) + 1);
        for (int i = 0; i < K; i++) {
            g.pose().pushMatrix();
            try {
                g.pose().rotate(rad(360f * i / K));
                g.fill(-half, -outer, half, -inner, argb);
            } finally {
                g.pose().popMatrix();
            }
        }
    }

    // ---- geometry (pure int math, physical px, origin = the centre boundary) ----
    //
    // CENTERING. The origin is a pixel BOUNDARY, so an odd-thickness bar must commit to one side. Both bands use the
    // single offset (t+1)/2 (up-left, the same bias vanilla's (dim-15)/2 sprite has) and every arm is measured from the
    // band edge, so opposite arms are exactly equal for every thickness, length and gap.

    private static int barOffset(int t) { return (t + 1) / 2; }

    /** Up to four arms: horizontal length {@code h}, vertical length {@code v}, each {@code g} px out from the band. */
    private static int[][] armRects(int h, int v, int t, int g, boolean up, boolean down, boolean left, boolean right) {
        int off = barOffset(t);
        int b0 = -off, b1 = b0 + t;
        java.util.ArrayList<int[]> r = new java.util.ArrayList<>(4);
        if (left  && h > 0) r.add(new int[] { b0 - g - h, b0, b0 - g,     b1 });
        if (right && h > 0) r.add(new int[] { b1 + g,     b0, b1 + g + h, b1 });
        if (up    && v > 0) r.add(new int[] { b0, b0 - g - v, b1, b0 - g });
        if (down  && v > 0) r.add(new int[] { b0, b1 + g,     b1, b1 + g + v });
        return r.toArray(new int[0][]);
    }

    /** Filled square of side {@code d}, on the same boundary bias as the bars. */
    private static int[][] dotRects(int d) {
        int off = barOffset(d);
        return new int[][] { { -off, -off, -off + d, -off + d } };
    }
}
