package dev.s1mp1e.glass.asm;

/**
 * Lets us drop a container's panel texture WITHOUT losing everything else its
 * background layer draws.
 *
 * <p>Skipping the whole {@code drawGuiContainerBackgroundLayer} was wrong: in
 * 1.8.9 that method paints the panel texture <em>and</em> the things layered on
 * it — {@code GuiInventory} renders the player model there, the furnace its
 * fire and arrow, and so on. Cancelling the call took all of them with it.
 *
 * <p>So instead the hook arms this one-shot latch and calls vanilla through.
 * The first {@code Gui.drawTexturedModalRect} inside that call is the panel
 * blit (it always is — bind texture, blit the whole xSize×ySize rect, then draw
 * the extras), so it is consumed and skipped; everything after it draws
 * normally on top of our glass.
 */
public final class BlitSuppressor {

    private static boolean armed;

    private BlitSuppressor() {}

    /** Arm the latch for exactly one upcoming panel blit. */
    public static void arm() { armed = true; }

    /** Clear the latch — always call after the vanilla layer returns. */
    public static void disarm() { armed = false; }

    /** True once per arm(): the caller should skip this blit. */
    public static boolean consume() {
        if (!armed) return false;
        armed = false;
        return true;
    }
}
