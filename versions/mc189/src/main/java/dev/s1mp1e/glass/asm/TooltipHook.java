package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/**
 * Called from the head of {@code GuiScreen.drawHoveringText(List,int,int,FontRenderer)}
 * — the single point every tooltip in the game funnels through on its way to
 * {@code GuiUtils.drawHoveringText}.
 *
 * <p>Forge 1.8.9 has no tooltip render event (that arrived in 1.12), which is
 * why {@link GlassTooltip} sat unused: it was written as a drop-in replacement
 * with nothing calling it. This is the call site.
 *
 * <p>Returning true means the glass tooltip drew the panel AND its text, so the
 * splice returns and vanilla's dark box never renders.
 */
public final class TooltipHook {

    private TooltipHook() {}

    public static boolean draw(GuiScreen screen, List<String> lines,
                               int x, int y, FontRenderer font) {
        try {
            if (lines == null || lines.isEmpty()) return false;
            if (font == null) return false;
            if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;
            GlassTooltip.draw(lines, x, y, screen.width, screen.height, font);
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] tooltip hook failed, using vanilla: " + t);
            return false;
        }
    }
}
