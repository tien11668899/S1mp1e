package dev.s1mp1e.glass.hook;

import net.minecraft.client.renderer.WorldRenderer;

/**
 * Feature G6 — name tags. The vanilla name-tag background plate is a flat black at alpha 0.25; this restyles it to
 * a cool frosted charcoal ({@code 0x0B0E16}) while keeping the exact same alpha, so presence / opacity / depth are
 * identical to vanilla. {@code S1mp1eTransformer} redirects the {@code WorldRenderer.color(float,float,float,float)}
 * calls inside {@code Render.renderLivingLabel} (the plate's four vertices) to {@link #color}.
 *
 * <p><b>FAIR PLAY (world space).</b> Name tags are drawn in world space, so there is no GUI backdrop to refract —
 * this is a pure colour re-skin. It does NOT change when or where a name shows, does NOT disable depth or show
 * names through walls, and reads no distance / target data. Only the plate's RGB changes; the alpha (visibility)
 * is passed through untouched.
 */
public final class GlassNameTag {

    private GlassNameTag() {}

    /** Frosted charcoal for the name-tag plate. */
    private static final float PR = 0x0B / 255f;
    private static final float PG = 0x0E / 255f;
    private static final float PB = 0x16 / 255f;

    /**
     * Redirect target for {@code WorldRenderer.color(float,float,float,float)} inside {@code renderLivingLabel}.
     * Swaps the plate RGB to the frosted charcoal, keeping the caller's alpha exactly.
     */
    public static WorldRenderer color(WorldRenderer wr, float r, float g, float b, float a) {
        if (a <= 0f) return wr.color(r, g, b, a);   // fully transparent: leave as-is (keep vanilla visibility)
        return wr.color(PR, PG, PB, a);
    }
}
