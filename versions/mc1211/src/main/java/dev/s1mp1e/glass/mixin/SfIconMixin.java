package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the game's interactive glyph sprites (ticks, crosses, page arrows, the recipe filter, …) for Apple SF Symbols —
 * see {@link SfIcons}. In 1.21.1 {@code drawGuiTexture(id, x, y, w, h)} delegates to the z-carrying
 * {@code drawGuiTexture(id, x, y, z, w, h)}, so one HEAD hook covers every whole-sprite draw (the region overloads, used
 * for progress bars and the like, are deliberately not hooked). Unlisted sprites are untouched.
 */
@Mixin(DrawContext.class)
public abstract class SfIconMixin {

    @Inject(method = "drawGuiTexture(Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$sfIcon(Identifier sprite, int x, int y, int z, int w, int h, CallbackInfo ci) {
        if (!"minecraft".equals(sprite.getNamespace())) return;
        if (SfIcons.draw((DrawContext) (Object) this, sprite.getPath(), x, y, w, h)) ci.cancel();
    }
}
