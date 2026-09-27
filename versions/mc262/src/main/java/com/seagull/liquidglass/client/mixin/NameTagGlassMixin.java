package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Entity name-tag background plates get the frosted-glass tint.
 *
 * <p>Name tags are drawn in <b>world space</b>, so the GUI-space SDF refraction pipeline (which samples a per-frame GUI
 * backdrop grab) does not apply here — there is no backdrop to refract. Instead this restyles the existing translucent
 * plate cosmetically: {@code SubmitNodeCollection.submitNameTag} computes the plate colour as
 * {@code ARGB.color(getBackgroundOpacity(0.25f), 0)} (black at the options' background opacity) and passes it as the
 * {@code backgroundColor} of every {@code NameTagFeatureRenderer.Submit}. This {@code @ModifyArg} keeps that alpha exactly
 * (so the plate's presence, opacity and depth behaviour are identical to vanilla) and only shifts the RGB from pure black
 * to a cool frosted charcoal, so the plate reads like tinted glass rather than a flat black box.
 *
 * <p><b>Fair play.</b> This is a pure re-skin of the plate colour. It does not change when or where a name shows, does not
 * touch depth / see-through-walls behaviour, and reads nothing about the entity — it cannot reveal anything vanilla does
 * not already draw. A true rounded world-space glass pill would need a separate quad batch; that is intentionally not done
 * here to avoid any risk to the vanilla visibility rules.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class NameTagGlassMixin {

   /** Cool frosted charcoal RGB substituted for the plate's black; the vanilla alpha is preserved. */
   private static final int FROST_RGB = 0x0B0E16;

   @ModifyArg(
      method = "submitNameTag",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/feature/NameTagFeatureRenderer$Submit;<init>(Lorg/joml/Matrix4fc;FFLnet/minecraft/network/chat/Component;IIILnet/minecraft/client/gui/Font$DisplayMode;)V"
      ),
      index = 6
   )
   private int lg$frostNameBackground(int backgroundColor) {
      int a = backgroundColor >>> 24 & 0xFF;
      if (a == 0) return backgroundColor;          // discrete / no-plate: leave untouched
      return a << 24 | FROST_RGB;
   }
}
