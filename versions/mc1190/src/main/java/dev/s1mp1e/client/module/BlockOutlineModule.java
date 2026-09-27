package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.shape.VoxelShape;

/**
 * Restyles the vanilla block-selection outline: colour (or an animated chroma hue), line width and an optional
 * translucent fill of the targeted block.
 *
 * <p><b>FAIR PLAY.</b> This only changes how the outline that vanilla ALREADY draws looks. It is driven from
 * {@code BlockOutlineMixin}, which hooks {@code WorldRenderer.drawBlockOutline} - a method reached only when the
 * player is currently looking at a block. So it never adds an outline, never outlines a second block, never disables
 * depth and never shows anything through a wall: the fill is depth-tested and covers only the targeted block's own
 * shape.
 *
 * <p><b>Porting.</b> The policy half ({@link #outlineColor}, {@link #lineWidth}, {@link #fillColor}, {@link #tint}) is
 * plain int/float math and is byte-for-byte the 26.2 source. Only {@link #renderFill} touches 1.19.2 render types:
 * 26.2 submits {@code RenderTypes.debugFilledBox()} through a deferred collector; 1.19.2 core profile has no such
 * layer, so the fill is drawn immediately as depth-tested {@code POSITION_COLOR} quads of the shape's boxes (culling
 * off, depth-mask off so it never occludes). The outline colour and width are applied by the mixin
 * ({@code @ModifyArgs} on {@code drawShapeOutline}; width via the LINES layer's line-width phase).
 */
public final class BlockOutlineModule extends Module {

    /** Vanilla's line width at 1080p ({@code max(2.5, framebufferWidth/1920 * 2.5)}). */
    private static final float VANILLA_WIDTH_1080P = 2.5f;
    /** Outline chroma is a vivid hue; HUD chroma has its own saturation setting. */
    private static final float CHROMA_SAT = 0.85f;
    /** Push the fill faces this far off the block so they win the depth test instead of z-fighting. */
    private static final double FILL_INFLATE = 0.002;

    public final Setting color       = add(Setting.color("Colour", 0xCCFFFFFF));
    public final Setting width       = add(Setting.number("Line width", 2.5D, 1.0D, 8.0D));
    public final Setting chroma      = add(Setting.bool("Chroma", false));
    public final Setting chromaSpeed = add(Setting.number("Chroma speed", 1.0D, 0.1D, 5.0D));
    public final Setting fill        = add(Setting.bool("Fill", false));
    public final Setting fillColour  = add(Setting.color("Fill colour", 0x33FFFFFF));

    /** The live instance the outline mixin reads (ModuleManager builds exactly one). */
    private static BlockOutlineModule instance;

    /**
     * True only while the block outline is being drawn and its LINES buffer flushed (this frame). Set by
     * {@code BlockOutlineMixin} and read by {@code RenderPhaseLineWidthMixin} to gate the line-width override. Lives on
     * this regular class (not on a mixin, which may not hold a non-private static field) so both mixins can share it.
     */
    public static volatile boolean outlineDrawing;

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

    // ---- policy: pure int/float math (26.2 verbatim) ---------------------------------------------------

    /** Outline colour to submit instead of vanilla's (black at alpha ~102). */
    public static int outlineColor(int vanilla) {
        BlockOutlineModule m = active();
        if (m == null) return vanilla;
        return m.tint(m.color.colorValue);
    }

    /**
     * Line width to submit. The setting is in pixels at 1080p; it is scaled by the same resolution factor vanilla
     * applies ({@code vanilla / 2.5}), so "2.5" looks exactly like vanilla at every window size.
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

    // ---- 1.19.2 render: translucent depth-tested fill of the targeted block ----------------------------

    /**
     * Draws a translucent, depth-tested fill over the targeted block's selection shape. {@code matrices} must already
     * be translated to the block (the mixin translates it by {@code pos - camera} before calling this, matching the
     * outline's own offset). Immediate {@code POSITION_COLOR} quads: culling off, depth test on (so faces behind a
     * wall are hidden = fair play), depth-mask off (never occludes the block or its outline).
     */
    public static void renderFill(MatrixStack matrices, VoxelShape shape) {
        BlockOutlineModule m = active();
        if (m == null || shape == null || shape.isEmpty()) return;
        final int argb = m.fillColor();
        if (argb == 0) return;
        float a = ((argb >>> 24) & 255) / 255f, r = ((argb >>> 16) & 255) / 255f,
              g = ((argb >>> 8) & 255) / 255f, b = (argb & 255) / 255f;
        Matrix4f mat = matrices.peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (Box box : shape.getBoundingBoxes()) {
            box(bb, mat,
                (float) (box.minX - FILL_INFLATE), (float) (box.minY - FILL_INFLATE), (float) (box.minZ - FILL_INFLATE),
                (float) (box.maxX + FILL_INFLATE), (float) (box.maxY + FILL_INFLATE), (float) (box.maxZ + FILL_INFLATE),
                r, g, b, a);
        }
        Tessellator.getInstance().draw();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    /** Six quads of an axis-aligned box (culling is off, so winding is free). */
    private static void box(VertexConsumer vc, Matrix4f m,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            float r, float g, float b, float a) {
        // bottom / top
        quad(vc, m, r, g, b, a, x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1);
        quad(vc, m, r, g, b, a, x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0);
        // north / south
        quad(vc, m, r, g, b, a, x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  x1, y0, z0);
        quad(vc, m, r, g, b, a, x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1);
        // west / east
        quad(vc, m, r, g, b, a, x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0);
        quad(vc, m, r, g, b, a, x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  x1, y0, z1);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float r, float g, float b, float a,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        vc.vertex(m, ax, ay, az).color(r, g, b, a).next();
        vc.vertex(m, bx, by, bz).color(r, g, b, a).next();
        vc.vertex(m, cx, cy, cz).color(r, g, b, a).next();
        vc.vertex(m, dx, dy, dz).color(r, g, b, a).next();
    }
}
