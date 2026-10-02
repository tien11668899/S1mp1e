package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * No drop shadow under any text. Vanilla draws every "shadowed" string twice — a dark copy offset by one GUI pixel,
 * then the text — which is a pixel-font habit; under the smooth PingFang face on glass it reads as a smeared double
 * image. Both {@code drawInternal} overloads (String / OrderedText) are the single point every shadowed draw passes
 * through (HUD, menus, tooltips, chat, other mods' screens), so the flag is cleared there. The glowing-sign outline
 * ({@code drawWithOutline}) is a different path and stays.
 */
@Mixin(TextRenderer.class)
public abstract class TextShadowMixin {

    @ModifyVariable(method = "drawInternal(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;IIZ)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean s1mp1e$noShadowString(boolean shadow) {
        return false;
    }

    @ModifyVariable(method = "drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean s1mp1e$noShadowOrdered(boolean shadow) {
        return false;
    }
}
