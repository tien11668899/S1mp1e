package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassButtonPainter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Called from the head of every {@code GuiButton.drawButton}.
 *
 * <p>Returning {@code true} means "handled" — the transformer's splice then
 * returns from drawButton, so vanilla's widgets.png blit never happens and the
 * glass capsule is the only thing on screen. Because we own the whole draw we
 * must also render the label, which vanilla would otherwise have done.
 */
public final class ButtonHook {

    private ButtonHook() {}

    /** Text colours vanilla uses per state, kept so labels look untouched. */
    private static final int TEXT_DISABLED = 0xA0A0A0;
    private static final int TEXT_HOVER    = 0xFFFFA0;
    private static final int TEXT_NORMAL   = 0xE0E0E0;

    public static boolean draw(GuiButton button, Minecraft mc, int mouseX, int mouseY) {
        if (button == null || mc == null) return false;
        if (!button.visible) return false;               // vanilla also skips
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return false;

        try {
            // `hovered` is protected on GuiButton, but vanilla's own drawButton
            // sets it from exactly this test before drawing — and our splice
            // runs at the method head, i.e. before that assignment. So compute
            // it ourselves for painting and leave the field to the (still
            // reachable) vanilla code path; nothing downstream reads it earlier.
            boolean hovered = mouseX >= button.x
                           && mouseY >= button.y
                           && mouseX <  button.x + button.width
                           && mouseY <  button.y + button.height;

            GlassButtonPainter.paint(button, hovered);

            FontRenderer fr = mc.fontRenderer;
            if (fr != null && button.displayString != null) {
                int colour = !button.enabled ? TEXT_DISABLED
                           : hovered         ? TEXT_HOVER
                                             : TEXT_NORMAL;
                GlStateManager.enableBlend();
                fr.drawStringWithShadow(button.displayString,
                        button.x + button.width  / 2f
                            - fr.getStringWidth(button.displayString) / 2f,
                        button.y + (button.height - 8) / 2f,
                        colour);
            }
            return true;
        } catch (Throwable t) {
            // Any failure -> let vanilla draw, so a bad frame can't blank the UI.
            System.out.println("[S1mp1e] button hook failed, falling back: " + t);
            return false;
        }
    }
}
