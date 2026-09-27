package dev.s1mp1e.glass.render;

/**
 * Liquid glass for the status-effect boxes vanilla draws beside the survival / creative inventory
 * ({@code AbstractInventoryScreen.drawStatusEffects} — the column of active potion effects to the right of the
 * inventory). The 1.19.2 core-profile port of 26.2's {@code GlassEffects}.
 *
 * <p>Vanilla paints each effect box with the {@code inventory.png} dark rounded sprite. The mixin
 * {@code EffectsInInventoryGlassMixin} suppresses those per-box blits and calls {@link #strip} once, which draws ONE
 * continuous vertical glass plate (like the hotbar stood on its side) of the same material as the inventory panel,
 * with the HUD hotbar corner radius ({@link GlassCorners}, user rule R2) and faint separators between entries.
 * Everything else vanilla does — the effect ICON, NAME and remaining-TIME text — is drawn afterwards and stays on top
 * and readable.
 *
 * <p>Single refracting quad (no slot lattice), small-card pad 8. The backdrop is the world+dim already grabbed this
 * frame (the strip sits over the world to the right of the inventory, so refracting world+dim is correct); it never
 * re-grabs so it can never fold onto its own already-drawn glass. Gates on {@link GlassProgram#usable()}; a caller
 * that gets {@code false} keeps the vanilla sprites so a box never vanishes.
 */
public final class GlassEffects {

    private GlassEffects() {}

    /** AA / edge-refraction bleed around the strip, in GUI px — the small-card pad. */
    private static final int PAD = 8;
    /** Faint hotbar-style separator between two entries (same tint as the creative tab separators). */
    private static final int SEP_ARGB = 0x24000000;
    /** Separators stop short of the strip's sides, like the hotbar slot grooves. */
    private static final int SEP_INSET = 6;

    /**
     * The whole effect list as ONE continuous vertical glass strip from {@code (x0,y0)} to {@code (x1,y1)} with the
     * hotbar corner radius, plus a faint separator at the top of every entry after the first ({@code y0 + i*spacing}).
     * Must be called before any entry's icon/text is drawn so the strip stays underneath them.
     *
     * @return {@code false} when the glass pipeline / backdrop is unusable (caller keeps the vanilla per-box sprites)
     */
    public static boolean strip(int x0, int y0, int x1, int y1, int spacing, int count, float alpha) {
        if (x1 <= x0 || y1 <= y0 || count <= 0) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable() || !SceneCapture.hasBackdrop()) return false;
        float corner = GlassCorners.knob(x1 - x0, y1 - y0);
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, 0f, alpha, GlassRenderer.FROST_PANEL);
        if (GlassProgram.roundUsable()) {
            int sepA = Math.round(((SEP_ARGB >>> 24) & 0xFF) * alpha) & 0xFF;
            if (sepA > 0) {
                int argb = (sepA << 24) | (SEP_ARGB & 0xFFFFFF);
                for (int i = 1; i < count; i++) {
                    float sy = y0 + (float) i * spacing;
                    GlassRenderer.roundRect(x0 + SEP_INSET, sy - 1f, x1 - SEP_INSET, sy, 0.5f, argb);
                }
            }
        }
        return true;
    }
}
