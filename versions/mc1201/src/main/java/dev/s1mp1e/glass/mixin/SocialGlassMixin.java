package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassSurface;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Social interactions screen → the nine-sliced panel becomes one refracting liquid-glass plate (26.2's
 * {@code SocialGlassMixin} ported to 1.20.1). The small search icon and the player rows/ping bars/heads stay vanilla and
 * on top. Only the big background nine-slice is redirected (the tiny search icon is a separate {@code drawTexture}).
 */
@Mixin(SocialInteractionsScreen.class)
public abstract class SocialGlassMixin {

    @Redirect(method = "renderBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawNineSlicedTexture(Lnet/minecraft/util/Identifier;IIIIIIIII)V"))
    private void s1mp1e$glassPanel(DrawContext context, Identifier texture, int x, int y, int w, int h,
                                   int outer, int centerW, int centerH, int u, int v) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            context.drawNineSlicedTexture(texture, x, y, w, h, outer, centerW, centerH, u, v);
            return;
        }
        // Frame-primary: fresh backdrop (super.renderBackground drew the dim just before this call). R4.
        SceneCapture.grabNow();
        GlassSurface.plateOrPaint(context, x, y, x + w, y + h, 1.0f, 0x99101018);
    }
}
