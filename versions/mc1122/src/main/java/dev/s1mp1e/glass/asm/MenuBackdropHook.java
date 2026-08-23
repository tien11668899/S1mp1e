package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.GuiScreen;

/**
 * Called from the head of {@code GuiScreen.drawBackground(int)}.
 *
 * <p>Returning true means the blurred title-screen backdrop was drawn, so the
 * transformer's splice returns and vanilla's tiled dirt never renders. If no
 * frame has been captured yet (or the blur program failed to build) this
 * returns false and the dirt draws exactly as before.
 */
public final class MenuBackdropHook {

    private MenuBackdropHook() {}

    /**
     * Called the instant {@code GuiMainMenu.renderSkybox} returns — the one
     * moment the panorama is on screen with nothing drawn over it.
     */
    public static void capturePanorama() {
        try {
            MenuBackdrop.capture();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] panorama capture failed: " + t);
        }
    }

    public static boolean draw(GuiScreen screen, int tint) {
        try {
            return MenuBackdrop.draw();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] menu backdrop failed, using dirt: " + t);
            return false;
        }
    }
}
