package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.RoundRectRenderState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.ArrayDeque;

/**
 * 26.2 painter for the S1mp1e config GUI and HUD editor.
 *
 * <p>mc1211 drew immediately through custom GL programs (GLASS / BTN / ROUND / EDGE). 26.2 is a deferred
 * extract→render pipeline, so every primitive here ENQUEUES a {@link GuiElementRenderState} into the frame's
 * {@code GuiRenderState}; {@code GuiRenderer} layers overlapping states in insertion order, so the call order
 * of the screen code is still the paint order (a label enqueued after a capsule sits on top of it).
 * <ul>
 *   <li>{@link #panel} — REAL refractive glass: the recovered {@code liquidglass:pipeline/glass} (same one the
 *       hotbar / inventory panels use) + a whisper-dark readability scrim.</li>
 *   <li>{@link #capsule} — the recovered {@code glass_btn} pipeline (frosted white capsule + rim + soft shadow,
 *       no backdrop). Its vertex-colour knobs are exactly mc1211's BTN program: R = corner, G = 1-lift,
 *       B = opacity, A = enabled (1 / 0.4).</li>
 *   <li>{@link #fillRound} — there is no ROUND (SDF) program in 26.2, so rounded coloured fills are built as
 *       FLOAT-precise quads (centre band + circle-profile trapezoid slices on the vanilla {@code GUI}
 *       pipeline). Geometry is sub-pixel exact (smooth animation) but edges are not SDF anti-aliased.</li>
 *   <li>All custom states use float coordinates (the recovered {@code GlassRectRenderState} is int-only),
 *       so sliding pills / knobs still move sub-pixel like mc1211.</li>
 * </ul>
 * Scissor: {@code g.fill}/{@code g.text} pick up {@code g.enableScissor} automatically; custom states can't
 * read the extractor's private scissor stack, so screens must scissor through {@link #enableScissor} /
 * {@link #disableScissor}, which mirror it here.
 */
public final class GlassWidgets {

    private GlassWidgets() {}

    // ---- scissor mirror (custom render states need the rect explicitly) ----

    private static final ArrayDeque<ScreenRectangle> SCISSOR = new ArrayDeque<ScreenRectangle>();

    public static void enableScissor(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.enableScissor(x0, y0, x1, y1);
        ScreenRectangle r = new ScreenRectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)).transformAxisAligned(g.pose());
        ScreenRectangle top = SCISSOR.peek();
        if (top != null) {
            ScreenRectangle i = top.intersection(r);
            r = i != null ? i : new ScreenRectangle(r.left(), r.top(), 0, 0);
        }
        SCISSOR.push(r);
    }

    public static void disableScissor(GuiGraphicsExtractor g) {
        g.disableScissor();
        if (!SCISSOR.isEmpty()) SCISSOR.pop();
    }

    /** Screens call this at the start of every extract so a throw mid-frame can't leave a stale clip. */
    public static void resetScissorMirror() { SCISSOR.clear(); }

    private static ScreenRectangle scissor() { return SCISSOR.peek(); }

    // ---- rounded fills ----

    /** Refractive liquid-glass container panel with a dark readability scrim on top. */
    public static void panel(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float alpha) {
        float minSide = Math.min(x1 - x0, y1 - y0);
        if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
            // mc1211: GlassRenderer.glass(..., PAD_PANEL=12, corner 0.19, lift 0, alpha, frost). glass.fsh radius =
            // min(half) * 0.5 * R, so R = 0.19 gives the same min*0.0475 body corner (~14px on the 300px panel)
            // and it scales down with the panel on small windows exactly like mc1211.
            // A = frost (0 = strongest, ~4px; mc1211 used 0.5 ≈ 2px). The 26.2 backdrop is grabbed BEFORE the
            // menu blur, so the panel uses max frost to read like mc1211's glass over the blurred background.
            int knobs = (0x00 << 24) | (clampByte(0.19f) << 16) | (0xFF << 8) | clampByte(alpha);
            TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
            add(g, new QuadState(GlassPipeline.glass(), ts, g.pose(), x0, y0, x1, y1, 12f, knobs, scissor()));
            // very light dark scrim (mc1211: alpha*0.16, radius tracks the GLASS body corner = min*0.0475)
            roundRect(g, x0, y0, x1, y1, minSide * 0.0475f, (clampByte(alpha * 0.16f) << 24) | 0x1C1C1E);
            return;
        }
        // No glass pipeline / no backdrop: opaque frosted dark fill fallback (mc1211: 14px).
        roundRect(g, x0, y0, x1, y1, 14f, (clampByte(alpha * 0.58f) << 24) | 0x1C1C1E);
    }

    /**
     * iOS-26 scroll-edge for a scroll list. mc1211 = progressive blur of the list content + a 4% dim.
     * 26.2's backdrop is grabbed before any GUI content exists, so the content blur is impossible; only the
     * whisper dim survives, drawn as a vertical gradient strongest at the outer edge.
     */
    public static void scrollEdges(GuiGraphicsExtractor g, float x0, float yTop, float x1, float yBot,
                                   float ext, float topK, float botK, float alpha) {
        final float DIM = 0.04f;
        if (topK > 0.001f) {
            int a = clampByte(alpha * clamp01(topK) * DIM);
            if (a > 0) gradientV(g, x0, yTop, x1, yTop + ext, (a << 24) | 0x000000, 0x00000000);
        }
        if (botK > 0.001f) {
            int a = clampByte(alpha * clamp01(botK) * DIM);
            if (a > 0) gradientV(g, x0, yBot - ext, x1, yBot, 0x00000000, (a << 24) | 0x000000);
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Frosted white highlight / chip capsule (glass_btn pipeline — AA, no backdrop). */
    public static void capsule(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1,
                               float corner, float lift, float alpha, boolean enabled) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassPipeline.ensureReady() && GlassPipeline.btnUsable()) {
            int col = ((enabled ? 255 : 102) << 24) | (clampByte(corner) << 16)
                    | (clampByte(1f - lift) << 8) | clampByte(alpha);
            add(g, new QuadState(GlassPipeline.btn(), TextureSetup.noTexture(), g.pose(), x0, y0, x1, y1, 10f, col, scissor()));
        } else {
            int a = clampByte(alpha * (0.12f + 0.18f * lift));
            roundRect(g, x0, y0, x1, y1, (y1 - y0) / 2f * corner, (a << 24) | 0xFFFFFF);
        }
    }

    /** Coloured rounded fill (float geometry). */
    public static void fillRound(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int argb, float r) {
        if (x1 <= x0 || y1 <= y0 || ((argb >>> 24) & 0xFF) == 0) return;
        if (GlassPipeline.ensureReady() && GlassPipeline.roundUsable()) {
            // true SDF anti-aliased corners (glass_round) — the float trapezoid fill below is only the fallback
            add(g, new RoundRectRenderState(GlassPipeline.round(), g.pose(), x0, y0, x1, y1, r, argb, scissor()));
        } else {
            roundRect(g, x0, y0, x1, y1, r, argb);
        }
    }

    /**
     * The iOS-26 Liquid Glass KNOB (switch knob / slider thumb): a solid white pill at rest that, as {@code morph}
     * goes 0 → 1, grows into a clear refracting lens and then fades back — matched frame-by-frame to the reference
     * recordings. Built from AA primitives, back to front:
     * <ol>
     *   <li>a dark base — the dark card refracted into the lens (shows as dark bands above/below the track);</li>
     *   <li>the track seen through the lens: a slightly magnified band in the track colour(s), clipped to the lens
     *       interior and to the track ends; with a split, the left (filled) part has its own rounded end at
     *       {@code splitX}, like a slider's fill line inside the thumb;</li>
     *   <li>a clear glass surface (glass_btn: milky body + Fresnel rim + soft drop shadow);</li>
     *   <li>the solid white knob on top at opacity {@code 1 - morph} — at mid-morph this crossfade reads as the
     *       frosted light-grey frame iOS shows while the lens re-forms into the knob.</li>
     * </ol>
     * The whole shape scales about its centre by {@code 1 + (lensScale - 1) * morph}, so it grows as it turns to
     * glass and shrinks as it turns back.
     *
     * @param cx         knob centre x
     * @param cy         knob centre y
     * @param hw         rest half-width
     * @param hh         rest half-height
     * @param morph      0 = solid white knob, 1 = full glass lens
     * @param lensScale  size multiplier at morph 1
     * @param trackX0    left end of the track under the knob
     * @param trackX1    right end of the track under the knob
     * @param trackHalfH half-height of the track (the band seen inside the lens)
     * @param splitX     x where the band colour changes ({@code colLeft} left of it), or {@link Float#NaN} for one colour
     * @param colLeft    band colour left of the split (ARGB; alpha respected)
     * @param colRight   band colour right of the split / whole band when not split (ARGB)
     * @param alpha      widget / screen opacity 0..1
     */
    public static void knobLens(GuiGraphicsExtractor g, float cx, float cy, float hw, float hh, float morph, float lensScale,
                                float trackX0, float trackX1, float trackHalfH, float splitX,
                                int colLeft, int colRight, float alpha) {
        knobLens(g, cx, cy, hw, hh, morph, lensScale, lensScale, 1f, trackX0, trackX1, trackHalfH, splitX, colLeft, colRight, alpha);
    }

    /**
     * {@link #knobLens} with separate width / height multipliers and a corner radius at full morph (the slider lens
     * is a wide rounded rectangle that stretches with drag speed, see {@link Motion#lensShape}).
     *
     * @param cornerFrac lens corner radius at full morph as a fraction of its half-height (1 = capsule); the rest
     *                   knob is always a capsule
     */
    public static void knobLens(GuiGraphicsExtractor g, float cx, float cy, float hw, float hh, float morph,
                                float scaleW, float scaleH, float cornerFrac,
                                float trackX0, float trackX1, float trackHalfH, float splitX,
                                int colLeft, int colRight, float alpha) {
        float m = clamp01(morph);
        float lw = hw * (1f + (scaleW - 1f) * m), lh = hh * (1f + (scaleH - 1f) * m);
        float lx0 = cx - lw, lx1 = cx + lw, ly0 = cy - lh, ly1 = cy + lh;
        float r = lh * (1f + (cornerFrac - 1f) * m);                   // capsule at rest → rounded rect when lifted
        float cornerKnob = clamp01(r / Math.max(0.001f, Math.min(lw, lh)));   // glass_btn: radius = min(half) × R

        float glassA = alpha * m;
        if (glassA > 0.004f) {
            // 1) REAL refracting lens: the world behind bent through the pill (glass_lens pipeline — clear, not
            //    dark). Knobs: R = corner (full-capsule range, unlike glass()), G = 0xFF -> lift 0 so the rim stays
            //    subtle (no selector amplification), B = opacity (fades in with morph), A = 0.65 -> a whisper of
            //    frost so it reads as clear glass rather than a sharp mirror. Backdrop is grabbed before the GUI,
            //    so it refracts the world/panorama; the track is then painted on top (step 2) as the magnified band.
            boolean drewLens = false;
            if (GlassPipeline.ensureReady() && GlassPipeline.lensUsable()) {
                int lensCol = (clampByte(0.65f) << 24) | (clampByte(cornerKnob) << 16) | (0xFF << 8) | clampByte(glassA);
                TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
                add(g, new QuadState(GlassPipeline.lens(), ts, g.pose(), lx0, ly0, lx1, ly1, 10f, lensCol, scissor()));
                drewLens = true;
            } else {
                // no glass pipeline / no backdrop: a faint frosted-white body, NEVER dark
                fillRound(g, lx0, ly0, lx1, ly1, (clampByte(glassA * 0.34f) << 24) | 0xF2F3F5, r);
            }
            // 2) the track through the lens, magnified, running edge to edge horizontally so only the TOP and
            //    BOTTOM show refraction bands. A capsule band of half-height bandH < lh spanning [lx0, lx1] always
            //    lies inside the lens capsule: for a band-cap point at angle θ, its distance² to the lens cap centre
            //    is lh² − 2·bandH·(1−cosθ)·(lh−bandH) ≤ lh².
            float bandH = Math.min(trackHalfH * 0.95f, lh * 0.76f);   // clip to the track: the overhang shows glass, not track colour
            float bx0 = Math.max(trackX0, lx0), bx1 = Math.min(trackX1, lx1);
            if (bx1 - bx0 > 0.5f) {
                fillRound(g, bx0, cy - bandH, bx1, cy + bandH, scaleAlpha(colRight, glassA), bandH);
                if (!Float.isNaN(splitX) && splitX > bx0) {
                    float fx1 = Math.min(bx1, Math.max(bx0 + 2f * bandH, splitX));
                    fillRound(g, bx0, cy - bandH, fx1, cy + bandH, scaleAlpha(colLeft, glassA), bandH);
                }
            }
            // 3) outline: the lens shader already carries a faint Fresnel rim + soft shadow, so with a real lens we
            //    add NOTHING extra (the old glass_btn milky capsule at 0.65 is what read as an over-strong outline).
            //    Only the fallback path draws a light rim so the frosted-white body still has an edge.
            if (!drewLens) capsule(g, lx0, ly0, lx1, ly1, cornerKnob, 0.10f, glassA * 0.40f, true);
        }

        // 4) the solid white knob, fading out as the lens forms (and back in as it re-forms)
        float whiteA = alpha * (1f - m);
        if (whiteA > 0.004f) fillRound(g, lx0, ly0, lx1, ly1, (clampByte(whiteA) << 24) | 0xFFFFFF, r);
    }

    /** Frame-rate-independent approach of {@code current} toward {@code target} with time constant {@code tauMs}. */
    public static float approach(float current, float target, float dtMs, float tauMs) {
        return current + (target - current) * (1f - (float) Math.exp(-dtMs / Math.max(1f, tauMs)));
    }

    /** {@code argb} with its alpha byte multiplied by {@code k} (0..1). */
    public static int scaleAlpha(int argb, float k) {
        int a = Math.round(((argb >>> 24) & 0xFF) * clamp01(k));
        return (a << 24) | (argb & 0xFFFFFF);
    }
    /** Alias kept for the shared widget code. */
    public static void fillRoundSmooth(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int argb, float r) {
        fillRound(g, x0, y0, x1, y1, argb, r);
    }

    // ---- sharp primitives ----

    public static void fill(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int argb) {
        int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x1), iy1 = Math.round(y1);
        if (ix1 <= ix0 || iy1 <= iy0) return;
        g.fill(ix0, iy0, ix1, iy1, argb);
    }
    public static void gradientV(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int top, int bottom) {
        int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x1), iy1 = Math.round(y1);
        if (ix1 <= ix0 || iy1 <= iy0) return;
        g.fillGradient(ix0, iy0, ix1, iy1, top, bottom);
    }
    public static void border(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int argb) {
        fill(g, x0, y0, x1, y0 + 1, argb);
        fill(g, x0, y1 - 1, x1, y1, argb);
        fill(g, x0, y0, x0 + 1, y1, argb);
        fill(g, x1 - 1, y0, x1, y1, argb);
    }

    public static void label(GuiGraphicsExtractor g, String s, float x, float y, int rgb, float alpha) {
        GlassFont.draw(g, s, x, y, rgb, alpha, true);
    }
    public static void labelNoShadow(GuiGraphicsExtractor g, String s, float x, float y, int rgb, float alpha) {
        GlassFont.draw(g, s, x, y, rgb, alpha, false);
    }

    public static int strW(String s) { return Math.round(GlassFont.width(s)); }
    public static int fontH() { return Math.round(GlassFont.height()); }

    public static boolean inside(int mx, int my, float x0, float y0, float x1, float y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    // ---- internals ----

    private static void add(GuiGraphicsExtractor g, GuiElementRenderState s) {
        if (s.bounds() == null) return;   // fully scissored away
        ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(s);
    }

    /** Rounded rectangle with radius {@code r} in GUI px (clamped to the half-size), float geometry. */
    private static void roundRect(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float r, int argb) {
        if (x1 <= x0 || y1 <= y0 || ((argb >>> 24) & 0xFF) == 0) return;
        add(g, new RoundRectState(g.pose(), x0, y0, x1, y1, r, argb, scissor()));
    }

    private static int clampByte(float a) {
        int v = Math.round(a * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static ScreenRectangle boundsOf(float x0, float y0, float x1, float y1, Matrix3x2fc pose, ScreenRectangle scissor) {
        int bx0 = (int) Math.floor(x0), by0 = (int) Math.floor(y0);
        int bx1 = (int) Math.ceil(x1),  by1 = (int) Math.ceil(y1);
        ScreenRectangle b = new ScreenRectangle(bx0, by0, Math.max(1, bx1 - bx0), Math.max(1, by1 - by0)).transformMaxBounds(pose);
        return scissor != null ? scissor.intersection(b) : b;
    }

    /**
     * Float-coordinate twin of the recovered {@code GlassRectRenderState}: one padded quad with a 0..1 local UV
     * (the glass / glass_btn shaders rebuild the rect from {@code fwidth(vLocal)}) and the knob colour.
     */
    private static final class QuadState implements GuiElementRenderState {
        private final RenderPipeline pipeline;
        private final TextureSetup textureSetup;
        private final Matrix3x2f pose;
        private final float x0, y0, x1, y1, pad;
        private final int color;
        private final ScreenRectangle scissor, bounds;

        QuadState(RenderPipeline pipeline, TextureSetup ts, Matrix3x2fc pose, float x0, float y0, float x1, float y1,
                  float pad, int color, ScreenRectangle scissor) {
            this.pipeline = pipeline; this.textureSetup = ts; this.pose = new Matrix3x2f(pose);
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.pad = pad; this.color = color;
            this.scissor = scissor;
            this.bounds = boundsOf(x0 - pad, y0 - pad, x1 + pad, y1 + pad, this.pose, scissor);
        }

        @Override public void buildVertices(VertexConsumer vc) {
            float w = Math.max(x1 - x0, 1f), h = Math.max(y1 - y0, 1f);
            float u0 = -pad / w, u1 = 1f + pad / w, v0 = -pad / h, v1 = 1f + pad / h;
            float qx0 = x0 - pad, qy0 = y0 - pad, qx1 = x1 + pad, qy1 = y1 + pad;
            vc.addVertexWith2DPose(pose, qx0, qy0).setUv(u0, v0).setColor(color);
            vc.addVertexWith2DPose(pose, qx0, qy1).setUv(u0, v1).setColor(color);
            vc.addVertexWith2DPose(pose, qx1, qy1).setUv(u1, v1).setColor(color);
            vc.addVertexWith2DPose(pose, qx1, qy0).setUv(u1, v0).setColor(color);
        }
        @Override public RenderPipeline pipeline() { return pipeline; }
        @Override public TextureSetup textureSetup() { return textureSetup; }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public ScreenRectangle bounds() { return bounds; }
    }

    /**
     * Solid rounded rect on the vanilla {@code GUI} pipeline (POSITION_COLOR, QUADS): a full-width centre band
     * plus N trapezoid slices per cap following the quarter-circle profile. Vertex order per quad matches
     * vanilla's {@code ColoredRectangleRenderState} (top-left, bottom-left, bottom-right, top-right).
     */
    private static final class RoundRectState implements GuiElementRenderState {
        private final Matrix3x2f pose;
        private final float x0, y0, x1, y1, r;
        private final int color;
        private final ScreenRectangle scissor, bounds;

        RoundRectState(Matrix3x2fc pose, float x0, float y0, float x1, float y1, float r, int color, ScreenRectangle scissor) {
            this.pose = new Matrix3x2f(pose);
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
            float half = Math.min(x1 - x0, y1 - y0) / 2f;
            this.r = r < 0f ? 0f : Math.min(r, half);
            this.color = color;
            this.scissor = scissor;
            this.bounds = boundsOf(x0, y0, x1, y1, this.pose, scissor);
        }

        private float inset(float dy) {   // dy = distance from the cap's circle centre line (0..r)
            float d = r * r - dy * dy;
            return d <= 0f ? r : r - (float) Math.sqrt(d);
        }

        private void quad(VertexConsumer vc, float ya, float xla, float xra, float yb, float xlb, float xrb) {
            vc.addVertexWith2DPose(pose, xla, ya).setColor(color);
            vc.addVertexWith2DPose(pose, xlb, yb).setColor(color);
            vc.addVertexWith2DPose(pose, xrb, yb).setColor(color);
            vc.addVertexWith2DPose(pose, xra, ya).setColor(color);
        }

        @Override public void buildVertices(VertexConsumer vc) {
            if (r <= 0.01f) { quad(vc, y0, x0, x1, y1, x0, x1); return; }
            float midTop = y0 + r, midBot = y1 - r;
            if (midBot > midTop) quad(vc, midTop, x0, x1, midBot, x0, x1);
            int n = Math.max(2, Math.min(12, (int) Math.ceil(r * 1.5f)));
            for (int i = 0; i < n; i++) {
                float ta = (float) i / n, tb = (float) (i + 1) / n;
                // top cap: y from y0 (dy = r) down to y0 + r (dy = 0)
                float ya = y0 + r * ta, yb = y0 + r * tb;
                float ia = inset(r - r * ta), ib = inset(r - r * tb);
                quad(vc, ya, x0 + ia, x1 - ia, yb, x0 + ib, x1 - ib);
                // bottom cap: y from y1 - r (dy = 0) down to y1 (dy = r)
                float yc = midBot + r * ta, yd = midBot + r * tb;
                float ic = inset(r * ta), id = inset(r * tb);
                if (midBot < midTop) { yc = Math.max(yc, midTop); yd = Math.max(yd, midTop); }
                if (yd > yc) quad(vc, yc, x0 + ic, x1 - ic, yd, x0 + id, x1 - id);
            }
        }
        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public ScreenRectangle bounds() { return bounds; }
    }
}
