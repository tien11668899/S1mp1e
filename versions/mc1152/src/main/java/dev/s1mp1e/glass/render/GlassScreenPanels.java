package dev.s1mp1e.glass.render;

import dev.s1mp1e.client.gui.ScreenOpenFade;

/**
 * PORT_SPEC feature (A) — glass for the remaining vanilla screens that are NOT containers
 * (containers already glass via {@code HandledScreenGlassMixin}; the horse / mount inventory is a
 * {@code ContainerScreen} so it is already covered by that path). This is the 1.15.2 fixed-function
 * counterpart of LiquidGlass26's {@code render/GlassSurface} {@code plate}/{@code scrim} helpers +
 * {@code StatsScreenGlassMixin} / {@code BookGlassMixin} — identical to the 1.14.4 sibling because
 * both are the FF-Fabric family and these screens did not change between 1.14.4 and 1.15.2.
 *
 * <p>The book and stats surfaces are the panel family, so they use the SAME refracting panel
 * material and corner radius as the container panels ({@link GlassRenderer#panel}) rather than the
 * hotbar-corner {@link GlassCorners} that new non-panel pieces use (spec A: "keep their radius" for
 * panel-family surfaces; R2). Each is the only refracting glass surface on its screen, so it is
 * frame-primary and takes a fresh {@link SceneCapture#grabNow()} (rule R4: never fold onto a stale
 * snapshot / flicker at high fps). All fade in over 150 ms with {@link ScreenOpenFade}, synced to
 * the glass buttons on the same screen. When the pipeline is unusable each returns {@code false} and
 * the caller keeps the vanilla texture.
 */
public final class GlassScreenPanels {

    private GlassScreenPanels() {}

    /** Visible open-book art size in the {@code book.png} texture (spec A: "book page 146x180"). */
    private static final int BOOK_W = 146, BOOK_H = 180;
    /** Warm light parchment scrim so dark book ink stays readable (spec A: 0xD8EFE7D6, inset 4, r4). */
    private static final int PARCHMENT = 0xD8EFE7D6;
    /** Statistics grey scrim (spec A: RGB 0x0E0E14, alpha 0xB4). */
    private static final int STATS_RGB = 0x0E0E14, STATS_ALPHA = 0xB4;

    private static boolean ready() {
        return GlassProgram.ensureReady() && GlassProgram.usable();
    }

    /**
     * The book / lectern page: a glass plate over the {@value #BOOK_W}x{@value #BOOK_H} page at
     * {@code (x,y)} plus a LIGHT warm parchment scrim (the documented dark-ink exception to the
     * grey scrim) so the black ink stays readable. {@code (x,y)} is the vanilla
     * {@code blit(i, 2, ...)} origin.
     *
     * @return {@code false} when the pipeline is unusable (caller keeps the vanilla book texture)
     */
    public static boolean book(Object screen, int x, int y) {
        if (!ready()) return false;
        // Frame-primary surface: grab a fresh world/panorama+dim backdrop this frame (R4).
        SceneCapture.grabNow();
        if (!SceneCapture.hasBackdrop()) return false;
        float fade = ScreenOpenFade.value(screen);
        int x0 = x, y0 = y, x1 = x + BOOK_W, y1 = y + BOOK_H;
        GlassRenderer.panel(x0, y0, x1, y1, fade);
        int pa = Math.round(((PARCHMENT >>> 24) & 0xFF) * fade) & 0xFF;
        if (pa > 0) {
            GlassRenderer.roundRect(x0 + 4, y0 + 4, x1 - 4, y1 - 4, 4f, (pa << 24) | (PARCHMENT & 0xFFFFFF));
        }
        return true;
    }

    /**
     * The statistics screen: a glass plate over the stat list's bounds plus the grey readability
     * scrim (spec A: RGB 0x0E0E14 @ 0xB4). Drawn from the shared {@code EntryListWidget} render,
     * gated to {@code StatsScreen}, at the seam AFTER the vanilla dirt quad and BEFORE the rows, so
     * it overpaints the dirt and the stat rows draw on top.
     *
     * @return {@code false} when the pipeline is unusable (caller keeps the vanilla dirt)
     */
    public static boolean stats(Object screen, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top || !ready()) return false;
        // Frame-primary surface: fresh grab (R4). EntryListWidgetBackdropMixin is gated to skip
        // StatsScreen, so nothing else draws over the dirt here — this plate is the whole treatment.
        SceneCapture.grabNow();
        if (!SceneCapture.hasBackdrop()) return false;
        float fade = ScreenOpenFade.value(screen);
        GlassRenderer.panel(left, top, right, bottom, fade);
        int sa = Math.round(STATS_ALPHA * fade) & 0xFF;
        if (sa > 0) {
            GlassRenderer.roundRect(left, top, right, bottom, 6f, (sa << 24) | STATS_RGB);
        }
        return true;
    }
}
