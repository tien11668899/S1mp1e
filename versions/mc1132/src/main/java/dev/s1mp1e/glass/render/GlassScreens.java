package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.class_4117;

/**
 * Feature (A) helpers for the non-container glass screens whose glass is more than a plain panel:
 * <ul>
 *   <li>the written-book page (view / edit / sign, lectern), whose dark ink needs a LIGHT parchment scrim (the
 *       documented exception to the usual grey readability scrim) so it stays readable over the refraction;</li>
 *   <li>the Statistics screen: one full-screen refracting glass plate + grey scrim {@code 0x0E0E14 @ 0xB4} replacing the
 *       dim / blurred world AND the list's tiled dirt (26.2 {@code StatsScreenGlassMixin}).</li>
 * </ul>
 *
 * <p>Every surface here is FRAME-PRIMARY, so it takes a fresh {@link SceneCapture#grabNow()} (R4) and fades in with the
 * shared {@link ScreenOpenFade} (150 ms, synced to the glass buttons on the same screen).
 */
public final class GlassScreens {

    private GlassScreens() {}

    // ==== book =========================================================================================

    /** Warm light parchment scrim so dark book ink stays readable over the glass (26.2 book exception). */
    private static final int PARCHMENT_ARGB = 0xD8EFE7D6;
    /**
     * Page geometry inside the 192 px {@code book.png} blit. Measured on the 1.13.2 client jar: the opaque book is
     * columns 20..165 / rows 1..180 of the texture (alpha &gt; 200), i.e. the {@code 146 x 180} page of the 26.2 spec
     * sits at (+20, +1) from the blit origin (26.2's newer texture puts it at the origin).
     */
    private static final int PAGE_INSET_X = 20;
    private static final int PAGE_INSET_Y = 1;
    private static final int PAGE_W = 146;
    private static final int PAGE_H = 180;
    private static final int SCRIM_INSET = 4;
    private static final float SCRIM_RADIUS = 4f;

    /**
     * Replace the vanilla book PNG (blitted at {@code (x,y)}, 192 px wide) with a frosted glass page: a refracting plate
     * over the {@code 146 x 180} parchment area plus a light parchment scrim (inset 4, radius 4) so dark ink stays
     * readable. Takes a fresh {@link SceneCapture#grabNow()} (FRAME-PRIMARY, R4). The book text is drawn by vanilla AFTER
     * the replaced blit, so it sits on top of the scrim.
     *
     * @return {@code false} when the glass pipeline is not usable (caller draws the vanilla book PNG).
     */
    public static boolean bookPage(int x, int y) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;
        float fade = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
        int x0 = x + PAGE_INSET_X, y0 = y + PAGE_INSET_Y;
        int x1 = x0 + PAGE_W, y1 = y0 + PAGE_H;
        SceneCapture.grabNow();
        GlassRenderer.panel(x0, y0, x1, y1, fade);
        if (GlassProgram.roundUsable()) {
            int a = Math.round(((PARCHMENT_ARGB >>> 24) & 0xFF) * fade) & 0xFF;
            GlassRenderer.roundRect(x0 + SCRIM_INSET, y0 + SCRIM_INSET, x1 - SCRIM_INSET, y1 - SCRIM_INSET,
                    SCRIM_RADIUS, (a << 24) | (PARCHMENT_ARGB & 0xFFFFFF));
        }
        // The glass bind left the SceneCapture texture on unit 0; the page-turn arrows / text that follow rebind their own.
        return true;
    }

    // ==== statistics ===================================================================================

    /** 26.2 stats scrim: RGB {@code 0x0E0E14} at alpha {@code 0xB4}. */
    private static final int STATS_SCRIM_RGB = 0x0E0E14;
    private static final int STATS_SCRIM_ALPHA = 0xB4;
    /** Hairline on the list edges facing the rows (this version's list-separator look, see EntryListGlassMixin). */
    private static final int STATS_HAIRLINE = 0x33FFFFFF;

    /** Identity of the stat list whose frame is being drawn over the plate (null = none this frame). */
    private static Object statsList;
    private static float statsFade;
    private static int statsGen;

    /**
     * Statistics background: one full-screen refracting glass plate + grey scrim over the RAW world (replaces the
     * vanilla dim gradient, the in-world blur and — via {@link #isStatsList} — the list's tiled dirt). {@code list} is
     * the stat list whose frame is starting (its dirt interior / header+footer strips are then handled by
     * {@code EntryListGlassMixin}); {@code null} for the "downloading statistics" frame.
     *
     * @return {@code false} when glass is unusable or there is no world (caller draws the vanilla background).
     */
    public static boolean statsPlate(Object list) {
        MinecraftClient mc = MinecraftClient.getInstance();
        statsList = null;
        if (mc.world == null) return false;                 // world-less: keep the menu-backdrop path
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;
        class_4117 win = mc.field_19944;
        int w = win.method_18321(), h = win.method_18322();
        float fade = ScreenOpenFade.value(mc.currentScreen);
        // FRAME-PRIMARY: fresh backdrop of the raw world (nothing of this screen drawn yet) -> never self-samples (R4).
        SceneCapture.grabNow();
        statsGen = SceneCapture.generation();
        drawStatsPlate(w, h, fade);
        statsList = list;
        statsFade = fade;
        return true;
    }

    /** True when {@code list} is the stat list whose glass plate was laid this frame (skip its dirt / strips). */
    public static boolean isStatsList(Object list) {
        return list != null && list == statsList;
    }

    /**
     * Re-lay the SAME plate + scrim, scissored to the header {@code [0,top)} and footer {@code [bottom,h)} bands, after
     * the rows drew — the 1.13.2 list has no scissor, so this masks rows scrolled past the list edges exactly like the
     * vanilla dirt strips did, while staying pixel-identical to the plate (same backdrop texture, same geometry). If the
     * backdrop was re-grabbed in between (it never is on this screen) a plain scrim masks instead so the glass never
     * samples the rows it is hiding.
     */
    public static void statsStrips(int left, int right, int top, int bottom) {
        Object list = statsList;
        statsList = null;
        if (list == null) return;
        class_4117 win = MinecraftClient.getInstance().field_19944;
        int w = win.method_18321(), h = win.method_18322();
        GlStateManager.disableDepthTest();
        if (SceneCapture.generation() == statsGen && SceneCapture.hasBackdrop()) {
            if (top > 0) {
                GlassWidgets.beginScissor(0, 0, w, top);
                try { drawStatsPlate(w, h, statsFade); } finally { GlassWidgets.endScissor(); }
            }
            if (bottom < h) {
                GlassWidgets.beginScissor(0, bottom, w, h);
                try { drawStatsPlate(w, h, statsFade); } finally { GlassWidgets.endScissor(); }
            }
        } else {
            int a = Math.round(0xF0 * statsFade) & 0xFF;
            DrawableHelper.fill(0, 0, w, top, (a << 24) | STATS_SCRIM_RGB);
            DrawableHelper.fill(0, bottom, w, h, (a << 24) | STATS_SCRIM_RGB);
        }
        int ha = Math.round(((STATS_HAIRLINE >>> 24) & 0xFF) * statsFade) & 0xFF;
        int hair = (ha << 24) | (STATS_HAIRLINE & 0xFFFFFF);
        DrawableHelper.fill(left, top - 1, right, top, hair);
        DrawableHelper.fill(left, bottom, right, bottom + 1, hair);
    }

    private static void drawStatsPlate(int w, int h, float fade) {
        GlassRenderer.panel(0, 0, w, h, fade);
        if (GlassProgram.roundUsable()) {
            int a = Math.round(STATS_SCRIM_ALPHA * fade) & 0xFF;
            GlassRenderer.roundRect(0, 0, w, h, 0f, (a << 24) | STATS_SCRIM_RGB);
        }
    }
}
