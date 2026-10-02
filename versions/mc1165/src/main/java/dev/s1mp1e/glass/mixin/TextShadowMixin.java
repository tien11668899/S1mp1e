package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * No drop shadow under any text. Vanilla draws every "shadowed" string twice — a dark copy offset by one GUI pixel,
 * then the text — which is a pixel-font habit; under the smooth PingFang face on glass it reads as a smeared double
 * image. Both private {@code drawInternal} overloads (String / OrderedText) are the single point every shadowed draw
 * passes through in 1.16.5 (javap-verified: every public {@code draw} / {@code drawWithShadow} ends in one of them —
 * HUD, menus, tooltips, chat, other mods' screens), so the flag is cleared there. The glowing-sign outline
 * ({@code drawWithOutline}) is a different path and stays.
 *
 * <p>1.16.5 signatures: the matrix is Mojang's own {@code Matrix4f} and the layer is still the {@code seeThrough}
 * boolean (JOML and {@code TextLayerType} arrive with 1.19.3 / 1.19.4); the first boolean argument of each overload is
 * {@code shadow}.
 */
@Mixin(TextRenderer.class)
public abstract class TextShadowMixin {

    @ModifyVariable(method = "drawInternal(Ljava/lang/String;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZIIZ)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean s1mp1e$noShadowString(boolean shadow) {
        return false;
    }

    @ModifyVariable(method = "drawInternal(Lnet/minecraft/text/OrderedText;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean s1mp1e$noShadowOrdered(boolean shadow) {
        return false;
    }
}
