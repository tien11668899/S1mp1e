package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * All-glass #26: the credits roll scrolls its own tiled dirt (private {@code renderBackground}, one
 * {@code drawTexture(OPTIONS_BACKGROUND_TEXTURE, …)}). Opened from the title (no world) that tile becomes the blurred
 * title panorama; the end-of-game roll (world loaded) keeps vanilla's look ({@link MenuBackdrop#draw} returns false).
 */
@Mixin(CreditsScreen.class)
public abstract class CreditsBackdropMixin {

    @Redirect(method = "renderBackground(Lnet/minecraft/client/gui/DrawContext;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIFFIIII)V"))
    private void s1mp1e$panorama(DrawContext ctx, Identifier tex, int x, int y, int z, float u, float v, int w, int h,
                                 int tw, int th) {
        if (Screen.OPTIONS_BACKGROUND_TEXTURE.equals(tex) && MenuBackdrop.cover(ctx)) return;
        ctx.drawTexture(tex, x, y, z, u, v, w, h, tw, th);
    }
}
