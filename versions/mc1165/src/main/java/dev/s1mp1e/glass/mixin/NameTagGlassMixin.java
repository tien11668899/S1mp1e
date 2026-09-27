package dev.s1mp1e.glass.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Entity name-tag background plates get the frosted-glass tint (G6).
 *
 * <p>Name tags are drawn in <b>world space</b>, so the GUI-space SDF refraction pipeline (which samples a per-frame GUI
 * backdrop) does not apply — there is no backdrop to refract. Instead this restyles the existing translucent plate
 * cosmetically: 1.16.5's {@code EntityRenderer.renderLabelIfPresent} computes the plate colour as
 * {@code (int)(getTextBackgroundOpacity(0.25f) * 255) << 24} (black at the options' background opacity) and passes it as
 * the {@code backgroundColor} (arg index 8) of {@code TextRenderer.draw(Text,FFIZ,Matrix4f,VertexConsumerProvider,ZII)}.
 * This {@code @ModifyArg} keeps that alpha exactly (so the plate's presence, opacity and depth behaviour are identical
 * to vanilla) and only shifts the RGB from pure black to a cool frosted charcoal. The second draw call (the text pass)
 * passes {@code backgroundColor == 0} and is returned untouched by the {@code a == 0} guard. (decompile-verified: arg
 * index 8 = backgroundColor, two draw INVOKEs.)
 *
 * <p><b>Fair play.</b> Pure re-skin of the plate colour. It does not change when or where a name shows, does not touch
 * depth / see-through-walls behaviour, and reads nothing about the entity — it cannot reveal anything vanilla does not
 * already draw.
 */
@Mixin(EntityRenderer.class)
public abstract class NameTagGlassMixin {

    /** Cool frosted charcoal RGB substituted for the plate's black; the vanilla alpha is preserved. */
    private static final int FROST_RGB = 0x0B0E16;

    @ModifyArg(method = "renderLabelIfPresent",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/text/Text;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I"),
               index = 8)
    private int s1mp1e$frostNameBackground(int backgroundColor) {
        int a = backgroundColor >>> 24 & 0xFF;
        if (a == 0) return backgroundColor;          // text pass / no-plate: leave untouched
        return a << 24 | FROST_RGB;
    }
}
