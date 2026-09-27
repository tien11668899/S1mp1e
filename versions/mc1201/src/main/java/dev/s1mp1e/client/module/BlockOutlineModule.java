package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.shape.VoxelShape;

/**
 * Block Outline (H1): restyles the vanilla block-selection outline — colour (or an animated chroma
 * hue), line width and an optional translucent fill of the targeted block. Ported from the 26.2
 * reference; the policy half ({@link #outlineColor}/{@link #lineWidth}/{@link #fillColor}) is plain
 * int/float maths and is byte-identical to 26.2.
 *
 * <p><b>FAIR PLAY.</b> This only changes how the outline vanilla ALREADY draws looks. It is driven
 * from {@code BlockOutlineMixin}, which hooks {@code WorldRenderer.drawBlockOutline} — a method that
 * only runs when the player is looking at a block. So it never adds an outline, never outlines a
 * second block, never disables depth and never shows anything through a wall: the fill is
 * depth-tested ({@link net.minecraft.client.render.RenderLayer#getDebugFilledBox()}, LEQUAL depth)
 * and covers only the targeted block's own selection shape.
 *
 * <p>Per-family (1.20.1 DrawContext / core profile): the outline is drawn by
 * {@code WorldRenderer.drawCuboidShapeOutline(.., float r,g,b,a)} into the LINES {@code VertexConsumer}
 * of the ENTITY buffers; {@code BlockOutlineMixin} rewrites those four floats (colour) and forces a
 * per-draw line width by early-flushing that same lines layer under a width override
 * ({@code RenderPhaseLineWidthMixin}). The 1.17+ core profile does NOT clamp this: the LINES render
 * type expands to screen-space quads in the {@code rendertype_lines} vertex shader using the line
 * width, so the setting visibly thickens the outline across the whole 1..8 px range (verified with
 * DevShot shots at width 1 vs width 8).
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

    // ---- policy: pure int/float maths ------------------------------------------------------------

    /** Outline colour to submit instead of vanilla's. */
    public static int outlineColor(int vanilla) {
        BlockOutlineModule m = active();
        if (m == null) return vanilla;
        return m.tint(m.color.colorValue);
    }

    /**
     * Line width to submit. The setting is in pixels at 1080p; scaled by the same resolution factor
     * vanilla applies ({@code vanilla / 2.5}) so "2.5" looks like vanilla at every window size.
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

    // ---- 1.21.1 render: translucent, depth-tested fill of the targeted block ---------------------

    /**
     * Submits a translucent, depth-tested fill over the targeted block's selection shape. {@code dx/
     * dy/dz} are the block origin relative to the camera (drawBlockOutline's offset args); {@code
     * matrices} is positioned at the camera. Uses {@link DebugRenderer#drawBox} (the vanilla
     * {@code getDebugFilledBox} helper, LEQUAL depth, culling off) so it never shows through walls.
     */
    public static void submitFill(MatrixStack matrices, VertexConsumerProvider provider,
                                  VoxelShape shape, double dx, double dy, double dz) {
        BlockOutlineModule m = active();
        if (m == null || shape == null || shape.isEmpty() || provider == null) return;
        final int argb = m.fillColor();
        if (argb == 0) return;
        final float a = ((argb >>> 24) & 255) / 255f;
        final float r = ((argb >> 16) & 255) / 255f;
        final float g = ((argb >> 8)  & 255) / 255f;
        final float b = (argb & 255) / 255f;
        shape.forEachBox((x0, y0, z0, x1, y1, z1) ->
                DebugRenderer.drawBox(matrices, provider,
                        dx + x0 - FILL_INFLATE, dy + y0 - FILL_INFLATE, dz + z0 - FILL_INFLATE,
                        dx + x1 + FILL_INFLATE, dy + y1 + FILL_INFLATE, dz + z1 + FILL_INFLATE,
                        r, g, b, a));
    }
}
