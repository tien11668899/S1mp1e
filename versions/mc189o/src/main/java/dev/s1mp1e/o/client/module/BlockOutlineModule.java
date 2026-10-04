package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Chroma;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;

/**
 * Restyles the vanilla block-selection outline: colour (or an animated chroma hue), line width and an optional
 * translucent fill of the targeted block.
 *
 * <p><b>FAIR PLAY.</b> This only changes how the outline that vanilla ALREADY draws looks. It is driven from
 * {@code dev.s1mp1e.o.glass.hook.BlockOutlineHook}, which listens for Forge's {@code DrawBlockHighlightEvent} —
 * fired only when the player is looking at a block. It never adds an outline, never outlines a second block,
 * never disables depth and never shows anything through a wall: the fill is depth-tested (drawn under the same GL
 * state as vanilla's own outline, which keeps depth on) and covers only the targeted block's own selection box.
 *
 * <p><b>Porting.</b> The policy half ({@link #outlineColor}, {@link #lineWidth}, {@link #fillColor}) is plain int
 * math and is the byte-for-byte copy of 26.2's. 26.2 draws the outline through a {@code lines} pipeline whose
 * width is a per-vertex attribute; 1.8.9 sets the width with {@code GL11.glLineWidth} and the colour with the
 * {@code POSITION_COLOR} vertices of {@code WorldRenderer.renderOutlineShape}, both handled by the hook.
 */
public final class BlockOutlineModule extends Module {

    /** Vanilla's line width at 1080p ({@code Window.getAppropriateLineWidth} = max(2.5, width/1920 * 2.5)). */
    private static final float VANILLA_WIDTH_1080P = 2.5f;
    /** Outline chroma is a vivid hue; HUD chroma has its own saturation setting. */
    private static final float CHROMA_SAT = 0.85f;
    /** Push the fill faces this far off the block so they win the depth test instead of z-fighting. */
    public static final double FILL_INFLATE = 0.002;

    public final Setting color       = add(Setting.color("Colour", 0xCCFFFFFF));
    public final Setting width       = add(Setting.number("Line width", 2.5D, 1.0D, 8.0D));
    public final Setting chroma      = add(Setting.bool("Chroma", false));
    public final Setting chromaSpeed = add(Setting.number("Chroma speed", 1.0D, 0.1D, 5.0D));
    public final Setting fill        = add(Setting.bool("Fill", false));
    public final Setting fillColour  = add(Setting.color("Fill colour", 0x33FFFFFF));

    /** The live instance the outline hook reads (ModuleManager builds exactly one). */
    private static BlockOutlineModule instance;

    public BlockOutlineModule() {
        super("BlockOutline", "Visual");
        this.enabled = false;
        instance = this;
    }

    /** @return the module when it is registered AND enabled, else null (the hook then leaves vanilla alone). */
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
}
