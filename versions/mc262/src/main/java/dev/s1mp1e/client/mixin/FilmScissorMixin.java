package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.film.Film;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Film mode only: the GUI camera (FilmGuiCamMixin) can push a scissored area (a scrolling list) partly off screen, and
 * RenderPass.enableScissor rejects out-of-bounds rectangles with an exception. Clamp the rectangle to the screen first.
 */
@Mixin(GuiRenderer.class)
public class FilmScissorMixin {
    @ModifyVariable(method = "enableScissor", at = @At("HEAD"), argsOnly = true)
    private ScreenRectangle s1mp1e$filmClampScissor(ScreenRectangle rect) {
        if (!Film.gcamActive() || rect == null) return rect;
        var w = Minecraft.getInstance().getWindow();
        ScreenRectangle screen = new ScreenRectangle(0, 0, w.getGuiScaledWidth(), w.getGuiScaledHeight());
        ScreenRectangle in = rect.intersection(screen);
        return in != null ? in : new ScreenRectangle(0, 0, 1, 1);
    }
}
