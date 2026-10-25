package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.module.NameTagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Entity name-tag background plate for the {@code NameTags} module's Vanilla / Off looks (Glass is
 * {@code NameTagGlassMixin}). 1.21.9+ (javap): {@code NameTagFeatureRenderer.Storage.add} hands every tag's
 * background colour to {@code new SubmitNodeStorage.NameTagSubmit(pose, x, y, text, light, colour, background,
 * distanceSq)} — the see-through pass carries {@code (int) (getBackgroundOpacity(0.25f) * 255) << 24}, the normal pass
 * 0. This {@code @ModifyArg} hands each to {@link NameTagModule#plate(int)}, which keeps the vanilla alpha for
 * Glass/Vanilla and zeroes it for Off; a zero colour passes straight through, so no plate is ever added.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.NameTagFeatureRenderer$Storage")
public abstract class NameTagPlateMixin {

   @ModifyArg(
      method = "add",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/SubmitNodeStorage$NameTagSubmit;<init>(Lorg/joml/Matrix4f;FFLnet/minecraft/network/chat/Component;IIID)V"
      ),
      index = 6
   )
   private int lg$nameBackground(int backgroundColor) {
      return NameTagModule.plate(backgroundColor);
   }
}
