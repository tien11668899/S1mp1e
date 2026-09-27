package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Draws one liquid-glass quad. This is LiquidGlass26's GlassRectRenderState
 * translated to immediate mode, and it keeps that class's contracts exactly:
 *
 * <ul>
 *   <li>the quad is expanded by {@code pad} GUI px on every side so the shader
 *       can draw its drop shadow OUTSIDE the shape, while the UVs stay
 *       normalised to the INNER rect (extending past 0..1 over the padding) so
 *       the shader's fwidth px reconstruction is unchanged;</li>
 *   <li>the vertex colour carries the four knobs —
 *       <b>R</b> = corner-radius scale, <b>G</b> = 1-lift (neutral brighten),
 *       <b>B</b> = element opacity, <b>A</b> = frost / neighbour-mask /
 *       enabled-dim depending on the program.</li>
 * </ul>
 */
public final class GlassRenderer {

    private GlassRenderer() {}

    /** Default frost for panels (26.2's 0x80 alpha knob). */
    public static final float FROST_PANEL = 0.5f;
    /** No frost — pure sharp refraction, used by the hotbar selector. */
    public static final float FROST_NONE  = 1.0f;

    /** 26.2's shadow padding for panels / capsules. */
    public static final float PAD_PANEL = 12f;
    public static final float PAD_PILL  = 18f;

    /**
     * Core draw. {@code kind} selects the program
     * ({@link GlassProgram#GLASS}/{@link GlassProgram#LINE}/{@link GlassProgram#BTN}).
     */
    public static void draw(int kind, float x0, float y0, float x1, float y1,
                            float pad, float r, float g, float b, float a) {
        if (!beginBatch(kind)) return;
        batchQuad(x0, y0, x1, y1, pad, r, g, b, a);
        endBatch();
    }

    // ---- batching ---------------------------------------------------------
    //
    // Every quad used to do its own glPushAttrib/glPopAttrib, glUseProgram and
    // glBegin/glEnd. The inventory lattice is one quad PER SLOT (46 of them),
    // so that was 46 full attribute-stack pushes and program switches a frame —
    // and glPushAttrib forces a pipeline flush on modern drivers, which is what
    // made the animations feel like they were running at half rate. Now the
    // state is set once, every quad in a group streams into a single
    // glBegin/glEnd, and the state is restored once.
    //
    // State goes through RenderSystem rather than raw glEnable/glDisable so
    // MC's cached view of GL stays in sync; only the immediate-mode vertex
    // calls and the program bind are raw.

    private static int  batchKind = -1;
    private static boolean batchTex;

    /** Begin a group of quads sharing one program. False -> nothing to draw. */
    public static boolean beginBatch(int kind) {
        if (!beginState(kind)) return false;
        openQuads();
        return true;
    }

    /**
     * Everything {@link #beginBatch} does EXCEPT opening the {@code GL_QUADS} block.
     *
     * <p>Between {@code glBegin} and {@code glEnd} only the vertex-attribute commands
     * are legal; {@code glUniform*} raises {@code GL_INVALID_OPERATION} and is silently
     * dropped. So any program whose per-draw uniforms have to be pushed (ROUND's
     * {@code Corner}, EDGE's {@code Radius}/{@code Dim}) must set them here, between the
     * program bind and {@link #openQuads()}.
     */
    private static boolean beginState(int kind) {
        if (!GlassProgram.ensureReady()) return false;
        if (kind == GlassProgram.GLASS && !GlassProgram.usable())     return false;
        if (kind == GlassProgram.LINE  && !GlassProgram.lineUsable()) return false;
        if (kind == GlassProgram.BTN   && !GlassProgram.btnUsable())  return false;
        if (kind == GlassProgram.ROUND && !GlassProgram.roundUsable())return false;
        if (kind == GlassProgram.EDGE  && !GlassProgram.edgeUsable()) return false;
        if (kind == GlassProgram.LENS  && !GlassProgram.lensUsable()) return false;
        if (kind == GlassProgram.RING  && !GlassProgram.ringUsable()) return false;
        if (kind == GlassProgram.ARC   && !GlassProgram.arcUsable())  return false;
        batchTex = GlassProgram.needsBackdrop(kind);
        if (batchTex && !SceneCapture.hasBackdrop()) return false;

        batchKind = kind;
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0);
        RenderSystem.disableAlphaTest();
        RenderSystem.depthMask(false);
        if (batchTex) {
            RenderSystem.enableTexture();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(SceneCapture.texture());
        } else {
            RenderSystem.disableTexture();
        }
        GlassProgram.bind(kind);
        return true;
    }

    /** Open the immediate-mode quad block once every per-draw uniform is pushed. */
    private static void openQuads() {
        GL11.glBegin(GL11.GL_QUADS);
    }

    /** One quad inside an open batch. Same knob contract as {@link #draw}. */
    public static void batchQuad(float x0, float y0, float x1, float y1,
                                 float pad, float r, float g, float b, float a) {
        if (batchKind < 0) return;
        float w = Math.max(x1 - x0, 1f);
        float h = Math.max(y1 - y0, 1f);
        float u0 = -pad / w, u1 = 1f + pad / w;
        float v0 = -pad / h, v1 = 1f + pad / h;
        float qx0 = x0 - pad, qy0 = y0 - pad, qx1 = x1 + pad, qy1 = y1 + pad;

        GL11.glColor4f(r, g, b, a);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(qx0, qy0);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(qx0, qy1);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(qx1, qy1);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(qx1, qy0);
    }

    /** Close the group and put the state back the way MC expects it. */
    public static void endBatch() {
        if (batchKind < 0) return;
        GL11.glEnd();
        GlassProgram.unbind();
        batchKind = -1;
        RenderSystem.enableTexture();
        RenderSystem.depthMask(true);
        RenderSystem.enableAlphaTest();
        // COLOUR-CACHE DESYNC — 1.16.5's GlStateManager.color4f compares against a
        // cached Color4 and skips the real glColor4f when the argument equals the
        // cache. batchQuad set the vertex colour with RAW GL11.glColor4f, which never
        // touches that cache, so the cache still believes the colour is white; a plain
        // RenderSystem.color4f(1,1,1,1) here compares equal and NO-OPs, leaving the real
        // GL colour at whatever the LAST quad set. Which quad is last depends on
        // hover/mouse position, so every following draw (slot items, text) would be
        // multiplied by a colour that changes frame to frame — flicker. Force the cache
        // to a value it cannot already hold, then set white so the real state AND the
        // cache both end up white and in sync.
        RenderSystem.color4f(0f, 0f, 0f, 0f);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        // Blend stays ENABLED on exit — leaving a GUI draw path with blend off is the
        // classic trap that makes later translucent vanilla draws render opaque.
    }

    // ---- convenience wrappers matching 26.2's call sites -------------------

    /** Refractive glass: corner scale, neutral lift, opacity, frost. */
    public static void glass(float x0, float y0, float x1, float y1,
                             float pad, float corner, float lift,
                             float opacity, float frost) {
        draw(GlassProgram.GLASS, x0, y0, x1, y1, pad, corner, 1f - lift, opacity, frost);
    }

    /** Frosted container panel — 26.2 uses pad 12 and corner ~0.19 (0x31/255). */
    public static void panel(float x0, float y0, float x1, float y1, float opacity) {
        glass(x0, y0, x1, y1, PAD_PANEL, 0.19f, 0f, opacity, FROST_PANEL);
    }

    /**
     * One slot-separator cell. {@code mask} is the 4-bit neighbour mask
     * (1=E 2=W 4=S 8=N) the lattice shader reads from vertex alpha.
     */
    public static void latticeCell(float x0, float y0, float x1, float y1,
                                   int mask, float opacity) {
        draw(GlassProgram.LINE, x0, y0, x1, y1, 0f,
             1f, 1f, opacity, (mask * 17) / 255f);
    }

    /** Translucent capsule button: corner 1 = full capsule. */
    public static void button(float x0, float y0, float x1, float y1,
                              float corner, float lift, float opacity, boolean enabled) {
        draw(GlassProgram.BTN, x0, y0, x1, y1, 10f,
             corner, 1f - lift, opacity, enabled ? 1f : 0.4f);
    }

    /** Refracting LENS (switch knob / slider thumb): full-capsule corner, samples the backdrop.
     *  {@code corner} 0..1 of the half-size (1 = capsule), {@code frost} 1 = sharp, &lt;1 = softened. */
    public static void lens(float x0, float y0, float x1, float y1,
                            float corner, float lift, float opacity, float frost) {
        draw(GlassProgram.LENS, x0, y0, x1, y1, 10f, corner, 1f - lift, opacity, frost);
    }

    /**
     * Liquid-glass RING (glass_ring.fsh): the same glass material on a band of outer size {@code outerR} and width
     * {@code thickness} (GUI px) around ({@code cx},{@code cy}), on a shape: 0 circle, 1 rounded square, 2 plus that
     * wraps the crosshair (the shape code rides on the green knob). Frost 0.5 like the panels.
     */
    public static void ring(float cx, float cy, float outerR, float thickness, float opacity) {
        ring(cx, cy, outerR, thickness, opacity, 0);
    }

    public static void ring(float cx, float cy, float outerR, float thickness, float opacity, int shape) {
        ring(cx, cy, outerR, thickness, opacity, shape, 0.45f);
    }

    /** {@code ratio} = the plus arm's half-width / reach (shape 2); quantised to 16 steps on the green knob. */
    public static void ring(float cx, float cy, float outerR, float thickness, float opacity, int shape, float ratio) {
        ring(cx, cy, outerR, thickness, opacity, shape, ratio, 0f);
    }

    /** {@code gap} = the plus's central-hole half-size / reach; >0 wraps a separated crosshair arm by arm. */
    private static int gapCode(float gap) { return Math.max(0, Math.min(14, Math.round(gap / 0.92f * 14f))); }

    public static void ring(float cx, float cy, float outerR, float thickness, float opacity, int shape, float ratio, float gap) {
        if (outerR <= 0f || opacity <= 0f) return;
        float t = Math.max(0.02f, Math.min(1f, thickness / outerR));
        int rc = Math.max(0, Math.min(15, Math.round((ratio - 0.08f) / 0.84f * 15f)));
        float g = shape == 2 ? (gapCode(gap) * 16 + rc) / 255f : (shape == 1 ? 245 / 255f : 1f);   // glass_ring: 255 circle, 245 square, plus = gap*16+ratio
        draw(GlassProgram.RING, cx - outerR, cy - outerR, cx + outerR, cy + outerR, 10f, t, g, opacity, FROST_PANEL);
    }

    /**
     * Flat anti-aliased ARC with round caps (ring_arc.fsh) from 12 o'clock over {@code |progress|} of the shape
     * (clockwise for a positive progress, counter-clockwise for a negative one), stroke {@code thickness} px.
     */
    public static void arc(float cx, float cy, float outerR, float thickness, float progress, int argb) {
        arc(cx, cy, outerR, thickness, progress, argb, 0);
    }

    public static void arc(float cx, float cy, float outerR, float thickness, float progress, int argb, int shape) {
        arc(cx, cy, outerR, thickness, progress, argb, shape, 0.45f);
    }

    public static void arc(float cx, float cy, float outerR, float thickness, float progress, int argb, int shape,
                           float ratio) {
        arc(cx, cy, outerR, thickness, progress, argb, shape, ratio, 0f);
    }

    public static void arc(float cx, float cy, float outerR, float thickness, float progress, int argb, int shape,
                           float ratio, float gap) {
        if (outerR <= 0f || progress == 0f || (argb >>> 24) == 0) return;
        if (!beginState(GlassProgram.ARC)) return;   // fixed-function: uniforms before glBegin, then open the quads
        GlassProgram.setArc(progress, Math.max(0.02f, Math.min(1f, thickness / outerR)), shape, ratio, gap);
        openQuads();
        float a = ((argb >>> 24) & 255) / 255f, r = ((argb >> 16) & 255) / 255f,
              g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        batchQuad(cx - outerR, cy - outerR, cx + outerR, cy + outerR, 1f, r, g, b, a);
        endBatch();
    }

    /**
     * Solid/translucent COLOURED rounded rect with true AA SDF corners (no backdrop,
     * never flickers). {@code radiusPx} is the corner radius in GUI px (clamped to the
     * half-size); {@code argb} is the packed fill colour.
     */
    public static void roundRect(float x0, float y0, float x1, float y1, float radiusPx, int argb) {
        if (!beginState(GlassProgram.ROUND)) return;
        float half = Math.min(x1 - x0, y1 - y0) / 2f;
        float corner = half <= 0f ? 0f : Math.min(1f, radiusPx / half);
        GlassProgram.setCorner(corner);   // BEFORE glBegin — glUniform is illegal inside a primitive
        openQuads();
        float a = ((argb >>> 24) & 255) / 255f, r = ((argb >> 16) & 255) / 255f,
              g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        batchQuad(x0, y0, x1, y1, 1f, r, g, b, a);   // pad=1 -> 1px AA margin for the SDF
        endBatch();
    }

    /**
     * One iOS-26 scroll-edge band: samples the captured composite and ramps a
     * gaussian blur + dark fade strongest at the OUTER edge, feathering to sharp
     * inside. {@code (x0,y0)-(x1,y1)} is the band rect in GUI px; when
     * {@code outerIsTop} the band's top row (y0) is the outer/frame edge (v=0),
     * otherwise the bottom row (y1) is. {@code alpha} is the whole-band opacity
     * (open fade × scroll amount), carried in vertex BLUE per the knob contract.
     */
    public static void edgeBand(float x0, float y0, float x1, float y1,
                                boolean outerIsTop, float radiusPx, float dim, float alpha) {
        if (!beginState(GlassProgram.EDGE)) return;
        GlassProgram.setEdge(radiusPx, dim);   // BEFORE glBegin — glUniform is illegal inside a primitive
        openQuads();
        float vTop = outerIsTop ? 0f : 1f;
        float vBot = outerIsTop ? 1f : 0f;
        // edge.fsh reads only vColor.b (opacity) and vLocal (= texcoord); u carries the
        // 0->1 span (feathered corners), v the 0(outer)->1(inner) ramp. Immediate-mode
        // quad wound TL->BL->BR->TR (front-facing) — the GUI pass runs with cull on.
        vertEdge(x0, y0, 0f, vTop, alpha);
        vertEdge(x0, y1, 0f, vBot, alpha);
        vertEdge(x1, y1, 1f, vBot, alpha);
        vertEdge(x1, y0, 1f, vTop, alpha);
        endBatch();
    }

    /** One vertex of an EDGE band inside the open GL_QUADS batch. edge.fsh reads
     *  vColor.b for opacity, so R=G=1, B=opacity, A=1; texcoord carries (u,v). */
    private static void vertEdge(float x, float y, float u, float v, float opacity) {
        if (batchKind < 0) return;
        GL11.glColor4f(1f, 1f, opacity, 1f);
        GL11.glTexCoord2f(u, v);
        GL11.glVertex2f(x, y);
    }
}
