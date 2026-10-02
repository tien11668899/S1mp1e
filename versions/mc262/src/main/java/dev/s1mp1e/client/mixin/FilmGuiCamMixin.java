package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.film.Film;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Film mode only: a "GUI camera". The whole GUI layer (HUD, screen, toasts) is extracted through one
 * {@link GuiGraphicsExtractor}; right after it is created, the shot's gcam transform (shift + scale about the screen
 * centre, keyed per frame) is applied to its pose, so HUD elements can be framed centre-screen and pushed in on at
 * native resolution while the world behind stays put. Inert outside a film run.
 */
@Mixin(Gui.class)
public class FilmGuiCamMixin {
    @ModifyVariable(method = "extractRenderState", at = @At("STORE"), ordinal = 0)
    private GuiGraphicsExtractor s1mp1e$filmGuiCam(GuiGraphicsExtractor g) {
        if (Film.gcamActive()) {
            float w = g.guiWidth() / 2f, h = g.guiHeight() / 2f, s = (float) Film.gcamS();
            g.pose().translate(w, h);
            g.pose().scale(s, s);
            g.pose().translate(-w + (float) Film.gcamX(), -h + (float) Film.gcamY());
        }
        return g;
    }
}
