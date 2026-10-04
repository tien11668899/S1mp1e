package dev.s1mp1e.o.glass.asm;

import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.ui.GlassTooltip;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;

/**
 * Called from the head of {@code Screen.renderTooltip(List,int,int,TextRenderer)}
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

    public static boolean draw(Screen screen, List<String> lines,
                               int x, int y, TextRenderer font) {
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
