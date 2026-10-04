package dev.s1mp1e.o.mixin;

import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** coremod「FontRenderer.renderString」：遊戲內所有文字都不要陰影——drawLayer 的 shadow 參數一律改成 false。 */
@Mixin(value = TextRenderer.class, priority = 1100)
public abstract class TextRendererMixin {
    @ModifyVariable(method = "drawLayer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean s1mp1e$noShadow(boolean shadow) {
        return false;
    }
}
