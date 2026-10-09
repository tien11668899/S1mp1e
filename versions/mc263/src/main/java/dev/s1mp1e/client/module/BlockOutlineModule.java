package dev.s1mp1e.client.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Restyles the vanilla block-selection outline: colour (or an animated chroma hue), line width and an optional
 * translucent fill of the targeted block.
 *
 * <p><b>FAIR PLAY.</b> This only changes how the outline that vanilla ALREADY draws looks. It is driven from
 * {@code BlockOutlineMixin}, which hooks {@code LevelRenderer.submitBlockOutline} — a method that returns early
 * unless the player is currently looking at a block ({@code blockOutlineRenderState != null}). So it never adds an
 * outline, never outlines a second block, never disables depth and never shows anything through a wall: the fill is
 * depth-tested ({@code RenderTypes.debugFilledBox()}) and covers only the targeted block's own shape.
 *
 * <p><b>Porting.</b> The policy half ({@link #outlineColor}, {@link #lineWidth}, {@link #fillColor}) is plain int
 * math and ports unchanged. Only {@link #submitFill} and the mixin touch 26.2 render types: 26.2 draws the outline
 * through {@code SubmitNodeCollector.submitShapeOutline(.., int colour, float lineWidth, ..)}, where the width is a
 * per-vertex attribute of the {@code lines} pipeline (the high-contrast path submits 7.0f), so overriding the float
 * genuinely changes the thickness. Older versions set the width with {@code RenderSystem.lineWidth} instead.
 */
public final class BlockOutlineModule extends Module {

    /** Vanilla's line width at 1080p ({@code Window.getAppropriateLineWidth} = max(2.5, width/1920 * 2.5)). */
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

    // ---- policy: pure int/float math -------------------------------------------------------------------

    /** Outline colour to submit instead of vanilla's (black at alpha 102, or the high-contrast grey). */
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

    // ---- 26.2 render: translucent fill of the targeted block --------------------------------------------

    /**
     * Submits a translucent, depth-tested fill over the targeted block's selection shape. {@code pose} must already
     * be translated to the block (it is, at the mixin's injection point inside {@code submitBlockOutline}).
     */
    public static void submitFill(PoseStack pose, SubmitNodeCollector collector, BlockOutlineRenderState state) {
        BlockOutlineModule m = active();
        if (m == null || state == null) return;
        final int argb = m.fillColor();
        if (argb == 0) return;
        final VoxelShape shape = state.shape();
        if (shape == null || shape.isEmpty()) return;
        collector.submitCustomGeometry(pose, RenderTypes.debugFilledBox(), (p, vc) ->
                shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> box(vc, p,
                        (float) (x0 - FILL_INFLATE), (float) (y0 - FILL_INFLATE), (float) (z0 - FILL_INFLATE),
                        (float) (x1 + FILL_INFLATE), (float) (y1 + FILL_INFLATE), (float) (z1 + FILL_INFLATE), argb)));
    }

    /** Six quads (QUADS topology, POSITION_COLOR) of an axis-aligned box. The pipeline has culling off. */
    private static void box(VertexConsumer vc, PoseStack.Pose p,
                            float x0, float y0, float z0, float x1, float y1, float z1, int c) {
        // bottom (y0) / top (y1)
        quad(vc, p, c, x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1);
        quad(vc, p, c, x0, y1, z0,  x0, y1, z1,  x1, y1, z1,  x1, y1, z0);
        // north (z0) / south (z1)
        quad(vc, p, c, x0, y0, z0,  x0, y1, z0,  x1, y1, z0,  x1, y0, z0);
        quad(vc, p, c, x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1);
        // west (x0) / east (x1)
        quad(vc, p, c, x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0);
        quad(vc, p, c, x1, y0, z0,  x1, y1, z0,  x1, y1, z1,  x1, y0, z1);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose p, int c,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        vc.addVertex(p, ax, ay, az).setColor(c);
        vc.addVertex(p, bx, by, bz).setColor(c);
        vc.addVertex(p, cx, cy, cz).setColor(c);
        vc.addVertex(p, dx, dy, dz).setColor(c);
    }
}
