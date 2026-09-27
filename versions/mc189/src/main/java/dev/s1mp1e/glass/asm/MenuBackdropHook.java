package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.MenuBackdrop;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;

/**
 * Called from the head of {@code GuiScreen.drawBackground(int)}, from
 * {@code GuiMainMenu.drawScreen} right after {@code renderSkybox}, and from
 * {@code GuiSlot.drawContainerBackground} / {@code overlayBackground}.
 *
 * <p>Returning true means the blurred backdrop was drawn, so the transformer's
 * splice returns and vanilla's tiled dirt never renders. If no frame has been
 * captured yet (or the blur program failed to build) this returns false and the
 * dirt draws exactly as before.
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

    // ---- GuiSlot list screens (world select, servers, languages, packs, ...) ----

    // The backdrop used for the current list frame, remembered so overlayBackground
    // can re-blit the same texture over the header/footer strips.
    private static int listTex = 0;
    private static float listRadius = MenuBackdrop.RADIUS;
    private static float listDim = MenuBackdrop.DIM;
    private static boolean listActive = false;

    /**
     * Replaces {@code GuiSlot.drawContainerBackground} (the tiled dirt behind list
     * screens) with the blurred panorama (title screen) or blurred world (in-game),
     * plus a translucent darken over the list body so the rows read. False -> the
     * dirt draws.
     */
    public static boolean listBackground(GuiSlot slot) {
        listActive = false;
        try {
            if (slot == null) return false;
            Minecraft mc = Minecraft.getMinecraft();
            int tex;
            if (mc.theWorld == null) {
                // draw() also re-renders the live panorama (V-5) before blurring,
                // so it must be attempted before any readiness test.
                if (!MenuBackdrop.draw()) return false;    // full-screen panorama blur
                tex = MenuBackdrop.panoramaTex();
            } else {
                if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return false;
                if (!MenuBackdrop.drawLive(MenuBackdrop.RADIUS, MenuBackdrop.DIM)) return false;
                tex = SceneCapture.texture();
            }
            if (tex == 0) return false;
            listTex = tex;
            listRadius = MenuBackdrop.RADIUS;
            listDim = MenuBackdrop.DIM;
            listActive = true;
            // darken the list body so the rows read over the blur
            Gui.drawRect(slot.left, slot.top, slot.right, slot.bottom, 0x80000000);
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] list backdrop failed, using dirt: " + t);
            return false;
        }
    }

    /**
     * Replaces {@code GuiSlot.overlayBackground(startY,endY,...)} — the dirt strips
     * that mask rows scrolling past the header/footer. Redraws the same backdrop
     * only in {@code [startY,endY)} and adds a 1&nbsp;px separator on the edge
     * facing the list (1.21's header/footer separators). False -> the dirt draws.
     */
    public static boolean listOverlay(GuiSlot slot, int startY, int endY) {
        try {
            if (!listActive || listTex == 0 || slot == null) return false;
            MenuBackdrop.drawTexture(listTex, listRadius, listDim, startY, endY);
            // The top strip is (0, top); the bottom strip is (bottom, height). Put
            // the hairline on the edge that faces the list rows.
            boolean header = endY <= slot.top;
            int sepY = header ? endY - 1 : startY;
            Gui.drawRect(slot.left, sepY, slot.right, sepY + 1, 0x33FFFFFF);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
