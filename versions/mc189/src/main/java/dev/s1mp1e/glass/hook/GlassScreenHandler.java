package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.asm.BlitSuppressor;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Glass for the remaining non-container screens that do NOT run through
 * {@code GlassContainerHandler} (PORT_SPEC feature A). Currently: the book
 * view/edit/sign screen.
 *
 * <h3>Book (GuiScreenBook)</h3>
 * {@code GuiScreenBook.drawScreen} does not call {@code drawDefaultBackground}
 * (there is no world dim and no {@code BackgroundDrawnEvent}); it binds
 * {@code book.png} and blits the 192x192 page at its head. So we hook
 * {@link GuiScreenEvent.DrawScreenEvent.Pre} instead: grab a fresh backdrop, draw
 * a frosted-glass page (panel family — keeps the panel radius) plus a LIGHT warm
 * parchment scrim (the documented dark-ink exception to the grey scrim, so the
 * black book text stays readable), and arm {@link BlitSuppressor} to drop the
 * vanilla book texture. The page-turn arrows (23x13 blits) and the Done/Sign
 * buttons (glass via ButtonHook) are untouched, and the text draws on top.
 * Disarmed at {@link GuiScreenEvent.DrawScreenEvent.Post}.
 */
public final class GlassScreenHandler {

    /** Visible book graphic inside the 192-wide sprite (parchment page). */
    private static final int BOOK_W = 146;
    private static final int BOOK_H = 180;
    /** Light warm parchment scrim (A): 0xD8EFE7D6, inset 4, radius 4. */
    private static final int   PARCHMENT = 0xD8EFE7D6;
    private static final int   PARCH_INSET = 4;
    private static final float PARCH_RADIUS = 4f;

    @SubscribeEvent
    public void onDrawScreenPre(GuiScreenEvent.DrawScreenEvent.Pre e) {
        GuiScreen gui = e.gui;
        if (!(gui instanceof GuiScreenBook)) return;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;

        int i = (gui.width - 192) / 2;   // vanilla book left (bookImageWidth 192)
        int j = 2;                       // vanilla book top

        // Fresh backdrop for this frame-primary surface (R4). The book draws no dim,
        // so this captures the live world (+ HUD) behind it.
        SceneCapture.forceGrab();

        // Frosted glass page (panel family -> panel radius, unchanged per R2/A note).
        GlassRenderer.panel(i, j, i + BOOK_W, j + BOOK_H, 1.0f);

        // Light warm parchment scrim so the dark ink stays readable (A).
        GlassRenderer.roundRect(i + PARCH_INSET, j + PARCH_INSET,
                                i + BOOK_W - PARCH_INSET, j + BOOK_H - PARCH_INSET,
                                PARCH_RADIUS, PARCHMENT);

        // Drop the vanilla book texture blit (192x192 at i,j); text + arrows survive.
        BlitSuppressor.arm(i, j, 192, 192);
    }

    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post e) {
        if (e.gui instanceof GuiScreenBook) {
            BlitSuppressor.disarm();
        }
    }
}
