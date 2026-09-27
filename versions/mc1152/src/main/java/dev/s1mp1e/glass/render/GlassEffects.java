package dev.s1mp1e.glass.render;

/**
 * PORT_SPEC feature (F) — the status-effect list beside the survival / creative inventory becomes
 * ONE continuous vertical liquid-glass strip (user choice "A 合成一整條"), like the hotbar stood
 * on its side. The 1.15.2 Fabric fixed-function counterpart of LiquidGlass26's
 * {@code render/GlassEffects} (identical to the 1.14.4 sibling).
 *
 * <h3>1.15.2 geometry (from {@code AbstractInventoryScreen.drawStatusEffects}, javap)</h3>
 * Unlike 26.2, 1.15.2 has NO compact mode and NO per-entry width: every box is a fixed
 * {@code 140 x 32} sprite blit at {@code x = this.x - 124} (the panel sits to the LEFT of the
 * inventory, which {@code init} shifts right when effects are active). {@code spacing} is 33, or
 * {@code 132/(n-1)} above five effects. So the strip is trivially uniform-width (140) and spans
 * {@code (x, topPos) .. (x+140, topPos + (n-1)*spacing + 32)}. Verified against the mapped 1.15.2
 * jar (constants 124 / 33 / 132 / 166 / 140 / 32 in {@code method_2477}/{@code method_18642}).
 *
 * <h3>Backdrop (rule R4 — never sample glass, never flicker)</h3>
 * The strip is drawn from {@code method_18642} (the box drawer), which runs AFTER the container
 * panel has already grabbed the frame's world backdrop and drawn itself. The strip's rightmost
 * ~16 px overlap the inventory's left edge, so a FORCED {@code grabNow()} here would fold the
 * already-drawn panel glass + slot lattice into the strip's backdrop = glass sampling glass in
 * that sliver. Instead it uses the 3 ms-deduped {@link SceneCapture#grab()}, which folds onto the
 * inventory's own pre-dim {@code grabNow()} of this same frame ({@code InventoryGlassMixin} grabs
 * the bright world at {@code render} HEAD, and the container panel already folded onto it) — so the
 * strip refracts the same world+dim as the panel and never samples the panel. This is the
 * mc1165-documented fixed-function rule: the frame-primary grab is owned once, secondary same-screen
 * surfaces fold onto it with {@code grab()}. It is deterministic per frame (tied to that grab), so
 * it cannot flicker independently of the panel.
 *
 * <p>Corner radius = the HUD hotbar radius via {@link GlassCorners} (rule R2: NEW pieces use the
 * hotbar corner). Material = the inventory panel material ({@link GlassRenderer#FROST_PANEL}),
 * small-card pad 8. Faint separators mark each entry top after the first. Icons, names and times
 * are drawn by the following vanilla passes ({@code method_18643}/{@code method_18644}), so they
 * stay on top and untouched; the compact tooltip does not exist here (no compact mode). When the
 * glass pipeline is unusable {@link #strip} returns {@code false} and the caller keeps the vanilla
 * per-box sprites so a box never vanishes.
 */
public final class GlassEffects {

    private GlassEffects() {}

    /** AA / edge-refraction bleed around the strip, in GUI px — the small-card pad (matches the tooltip). */
    private static final int PAD = 8;

    /** Faint hotbar-style separator between two entries (same tint as the 26.2 tab / effect separators). */
    private static final int SEP_ARGB = 0x24000000;
    /** Separators stop short of the strip's sides, like the hotbar slot grooves. */
    private static final int SEP_INSET = 6;

    /**
     * The whole effect list as ONE continuous vertical glass strip: a single plate from
     * {@code (x0, y0)} to {@code (x1, y1)} with the hotbar corner radius, in the inventory panel
     * material, plus a faint separator at the top of every entry after the first
     * ({@code y0 + i*spacing}). Must be called before any entry's icon / text is drawn so the strip
     * stays underneath them.
     *
     * @param fade the screen-open fade (0..1) so the strip fades in with the rest of the inventory glass
     * @return {@code false} when the glass pipeline is not usable (caller keeps the vanilla per-box sprites)
     */
    public static boolean strip(int x0, int y0, int x1, int y1, int spacing, int count, float fade) {
        if (x1 <= x0 || y1 <= y0 || count <= 0) return false;
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return false;

        // Fold onto the inventory's world grab of this frame (see class note): refracts the
        // world+dim to the left of the inventory, never the panel glass, never flickers alone.
        SceneCapture.grab();
        if (!SceneCapture.hasBackdrop()) return true;   // pipeline usable: still suppress vanilla boxes

        float a = fade < 0f ? 0f : (fade > 1f ? 1f : fade);
        float w = x1 - x0, h = y1 - y0;
        // ONE continuous glass plate, hotbar corner radius (R2 / GlassCorners), panel material.
        GlassRenderer.glass(x0, y0, x1, y1, PAD, GlassCorners.cornerKnob(w, h), 0f, a, GlassRenderer.FROST_PANEL);

        // Faint separators at each entry top after the first, alpha scaled by the panel fade.
        int sepA = Math.round(((SEP_ARGB >>> 24) & 0xFF) * a) & 0xFF;
        if (sepA > 0) {
            int sepCol = (sepA << 24) | (SEP_ARGB & 0xFFFFFF);
            for (int i = 1; i < count; i++) {
                int sy = y0 + i * spacing;
                GlassRenderer.roundRect(x0 + SEP_INSET, sy - 1, x1 - SEP_INSET, sy, 0.5f, sepCol);
            }
        }
        return true;
    }
}
