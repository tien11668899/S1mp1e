package dev.s1mp1e.client.module;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.util.shape.VoxelShape;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Block Outline (H1): restyles the vanilla block-selection outline — colour (or an animated chroma hue), line width,
 * and an optional translucent fill of the targeted block. The policy half
 * ({@link #outlineColor}/{@link #lineWidth}/{@link #fillColor}/{@link #tint}) is plain int/float maths ported
 * byte-for-byte from the 26.2 reference; only the fill emitter is version-specific.
 *
 * <p><b>FAIR PLAY.</b> This only changes how the outline that vanilla ALREADY draws looks. It is driven from
 * {@code BlockOutlineMixin}, which hooks {@code WorldRenderer.drawBlockOutline} — a method that only runs while the
 * player is looking at a block. So it never adds an outline, never outlines a second block, never disables depth and
 * never shows anything through a wall: the fill is depth-tested (LEQUAL, depth-mask off) and covers only the targeted
 * block's own selection shape.
 *
 * <p>Per-family (1.14.4 MatrixStack, legacy GL — NOT core profile): the outline is drawn by the static
 * {@code WorldRenderer.drawShapeOutline(.., float r,g,b,a)} into the LINES {@code VertexConsumer} of the ENTITY
 * buffers; {@code BlockOutlineMixin} rewrites those four floats (colour) and forces a per-draw line width by
 * early-flushing that lines layer under a width override ({@code RenderPhaseLineWidthMixin} → {@code glLineWidth}).
 * 1.14.4's LINES layer sets its width through {@code GlStateManager.lineWidth}, so 1..8 px visibly thickens the outline
 * (verified with DevShot shots at width 1 vs width 8). The fill uses the legacy immediate {@code Tessellator} with the
 * POSITION_COLOR format (no {@code GlStateManager.setShader} on this line — the fixed-function pipeline binds it).
 */
public final class BlockOutlineModule extends Module {

    /** Vanilla's line width at 1080p ({@code max(2.5, framebufferWidth/1920 * 2.5)}). */
    private static final float VANILLA_WIDTH_1080P = 2.5f;
    /** Outline chroma is a vivid hue; HUD chroma has its own saturation setting. */
    private static final float CHROMA_SAT = 0.85f;
    /** Push the fill faces this far off the block so they win the depth test instead of z-fighting. */
    private static final double FILL_INFLATE = 0.002;

    public final Setting color       = add(Setting.color("Colour", 0xCCFFFFFF));
    public final Setting width        = add(Setting.number("Line width", 2.5D, 1.0D, 8.0D));
    public final Setting chroma       = add(Setting.bool("Chroma", false));
    public final Setting chromaSpeed  = add(Setting.number("Chroma speed", 1.0D, 0.1D, 5.0D));
    public final Setting fill         = add(Setting.bool("Fill", false));
    public final Setting fillColour   = add(Setting.color("Fill colour", 0x33FFFFFF));

    /** The live instance the outline mixin reads (ModuleManager builds exactly one). */
    private static BlockOutlineModule instance;

    /** Set true only for the duration of the outline's own lines-layer flush, so the width override in
     *  {@code RenderPhaseLineWidthMixin} touches the block outline and nothing else. */
    public static volatile boolean widthOverride;

    public BlockOutlineModule() {
        super("BlockOutline", "Visual");
        this.enabled = false;
        instance = this;
    }

    /** @return the module when it is registered AND enabled, else null (the mixin then leaves vanilla alone). */
    public static BlockOutlineModule active() {
        BlockOutlineModule m = instance;
        return (m != null && m.enabled) ? m : null;
    }

    // ---- policy: pure int/float maths (byte-identical to 26.2) -----------------------------------

    /** Outline colour to submit instead of vanilla's. */
    public static int outlineColor(int vanilla) {
        BlockOutlineModule m = active();
        if (m == null) return vanilla;
        return m.tint(m.color.colorValue);
    }

    /**
     * Line width to submit. The setting is in pixels at 1080p; scaled by the same resolution factor vanilla applies
     * ({@code vanilla / 2.5}) so "2.5" looks like vanilla at every window size.
     */
    public static float lineWidth(float vanilla) {
        BlockOutlineModule m = active();
        if (m == null) return vanilla;
        float scale = vanilla > 0f ? vanilla / VANILLA_WIDTH_1080P : 1f;
        return (float) m.width.doubleValue * Math.max(1f, scale);
    }

    /** Fill tint for the targeted block, or 0 when the fill is off / fully transparent. */
    public int fillColor() {
        if (!fill.boolValue) return 0;
        int c = tint(fillColour.colorValue);
        return Chroma.alpha(c) < 2 ? 0 : c;
    }

    /** Applies chroma (keeping the colour's own alpha) when it is on; otherwise the colour as stored. */
    private int tint(int argb) {
        int a = Chroma.alpha(argb);
        if (a == 0) a = 255;   // an all-zero alpha byte means "opaque" everywhere in S1mp1e's colour settings
        if (!chroma.boolValue) return (a << 24) | (argb & 0xFFFFFF);
        return Chroma.argb(a, Chroma.hue(chromaSpeed.doubleValue, 0.0), CHROMA_SAT, 1f);
    }

    // ---- 1.14.4 render: translucent, depth-tested fill of the targeted block ---------------------

    /**
     * Fills the targeted block's selection shape with a translucent, depth-tested box, drawn immediately through a
     * {@link Tessellator} whose vertices are transformed by the SAME matrix the outline uses ({@code matrices.peek()}),
     * so the fill lines up with the outline exactly. Depth test stays on and the depth mask is off, so it never shows
     * through walls and never writes depth. {@code dx/dy/dz} are the block origin relative to the camera
     * (drawBlockOutline's offset args).
     */
    public static void submitFill(VoxelShape shape, double dx, double dy, double dz) {
        BlockOutlineModule m = active();
        if (m == null || shape == null || shape.isEmpty()) return;
        final int argb = m.fillColor();
        if (argb == 0) return;
        final int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;

        // Fixed-function state: this immediate POSITION_COLOR draw runs at drawBlockOutline HEAD, OUTSIDE any RenderLayer
        // phase, so GL_TEXTURE_2D (unit 0) is usually still enabled with whatever texture the last layer bound, and the
        // current texcoord is whatever the last immediate-mode draw left (the GUI glass sets glTexCoord). The fill colour
        // was then multiplied by a random texel -> the fill came and went between runs. Texturing and fixed-function
        // lighting are switched off for the fill and put back exactly as found.
        GlStateManager.activeTexture(GL13.GL_TEXTURE0);
        final boolean hadTex = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        final boolean hadLight = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GlStateManager.disableTexture();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableDepthTest();
        GlStateManager.depthMask(false);          // depth-tested but never writes depth (no z-fighting halo)
        GlStateManager.disableCull();

        BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        shape.forEachBox((x0, y0, z0, x1, y1, z1) -> box(buf,
                (float) (dx + x0 - FILL_INFLATE), (float) (dy + y0 - FILL_INFLATE), (float) (dz + z0 - FILL_INFLATE),
                (float) (dx + x1 + FILL_INFLATE), (float) (dy + y1 + FILL_INFLATE), (float) (dz + z1 + FILL_INFLATE),
                r, g, b, a));
        Tessellator.getInstance().draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        if (hadLight) GlStateManager.enableLighting();
        if (hadTex) GlStateManager.enableTexture();
    }

    /** Six QUADS (POSITION_COLOR) of an axis-aligned box, transformed by {@code mat}. Culling is off. */
    private static void box(BufferBuilder buf,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            int r, int g, int b, int a) {
        // bottom (y0) / top (y1)
        quad(buf, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(buf, r, g, b, a, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        // north (z0) / south (z1)
        quad(buf, r, g, b, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(buf, r, g, b, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        // west (x0) / east (x1)
        quad(buf, r, g, b, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(buf, r, g, b, a, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(BufferBuilder buf, int r, int g, int b, int a,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx2, float dy2, float dz2) {
        buf.vertex(ax, ay, az).color(r, g, b, a).next();
        buf.vertex(bx, by, bz).color(r, g, b, a).next();
        buf.vertex(cx, cy, cz).color(r, g, b, a).next();
        buf.vertex(dx2, dy2, dz2).color(r, g, b, a).next();
    }
}
