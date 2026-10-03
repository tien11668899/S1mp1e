package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * No drop shadow under any text. Vanilla draws every "shadowed" string twice — a dark copy offset by one GUI pixel,
 * then the text — which is a pixel-font habit; under the smooth PingFang face on glass it reads as a smeared double
 * image. Both private {@code drawInternal} overloads (String / String) are the single point every shadowed draw
 * passes through in 1.13.2 (javap-verified: every public {@code draw} / {@code drawWithShadow} ends in one of them —
 * HUD, menus, tooltips, chat, other mods' screens), so the flag is cleared there. The glowing-sign outline
 * ({@code drawWithOutline}) is a different path and stays.
 *
 * <p>1.13.2 signatures: the matrix is Mojang's own {@code Matrix4f} and the layer is still the {@code seeThrough}
 * boolean (JOML and {@code TextLayerType} arrive with 1.19.3 / 1.19.4); the first boolean argument of each overload is
 * {@code shadow}.
 */
@Mixin(TextRenderer.class)
public abstract class TextShadowMixin {

    /**
     * 1.13.2: {@code drawWithShadow(String, float, float, int)} and {@code draw(String, float, float, int)} both end in
     * the private {@code draw(String, float, float, int, boolean shadow)} - the one point every shadowed draw passes.
     */
    @ModifyVariable(method = "drawLayer(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean s1mp1e$noShadow(boolean shadow) {
        return false;
    }
}
