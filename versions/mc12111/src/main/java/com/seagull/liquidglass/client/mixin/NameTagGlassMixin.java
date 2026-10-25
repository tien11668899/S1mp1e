package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.gui.NameTagGlass;
import dev.s1mp1e.client.module.NameTagModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity name-tag GLASS mode: capture the tag for the GUI-stage refraction pass and cancel the world-space submission.
 *
 * <p>Name tags are drawn in <b>world space</b>, so the GUI-space SDF refraction pipeline (which samples a per-frame GUI
 * backdrop grab) does not apply there — there is no backdrop to refract. 1.21.9+ (javap): every entity's tag goes through
 * {@code SubmitNodeCollection.submitNameTag(pose, attachment, y, text, seeThrough, light, distanceSq, camera)}, whose
 * {@code NameTagFeatureRenderer.Storage.add} builds the model matrix (push a copy of the top pose, translate to the
 * attachment + 0.5, billboard-rotate by {@code camera.orientation}, scale 0.025) and the plate colour
 * {@code (int) (getBackgroundOpacity(0.25f) * 255) << 24}. The re-skin for the Vanilla / Off looks is
 * {@code NameTagPlateMixin}. (26.x: the same submit without the distance; 1.21.6–1.21.8: {@code EntityRenderer.renderNameTag}.)
 *
 * <p><b>Fair play.</b> This only re-skins the plate the game already draws. It does not change when or where a name shows,
 * does not touch depth / see-through-walls behaviour, and reads nothing about the entity. When the plate opacity is zero
 * (a no-plate name) the tag is left to vanilla, so no glass plate is ever added where vanilla draws none.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class NameTagGlassMixin {

   @Inject(method = "submitNameTag", at = @At("HEAD"), cancellable = true)
   private void lg$nameGlassCapture(PoseStack poseStack, Vec3 pos, int yInt, Component text, boolean seeThrough,
                                    int light, double distanceSq, CameraRenderState cam, CallbackInfo ci) {
      if (!NameTagModule.glassMode() || pos == null || cam == null) return;
      try {
         Minecraft mc = Minecraft.getInstance();
         // vanilla: (int) (getBackgroundOpacity(0.25f) * 255) << 24 — alpha 0 means no plate is drawn
         if ((int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) == 0) return;   // no plate -> leave it to vanilla

         // reconstruct the submit's pose (matches Storage.add's pushPose/translate/mulPose/scale on a copy)
         Matrix4f mat = new Matrix4f(poseStack.last().pose());
         mat.translate((float) pos.x, (float) (pos.y + 0.5), (float) pos.z);
         mat.rotate(cam.orientation);
         mat.scale(0.025f, -0.025f, 0.025f);
         float x = -mc.font.width(text) / 2.0f;

         // the name's colour is its own style (team / custom colour), defaulting to white like vanilla
         int rgb = 0xFFFFFF;
         net.minecraft.network.chat.TextColor tc = text.getStyle().getColor();
         if (tc != null) rgb = tc.getValue();

         if (NameTagGlass.capture(mat, x, (float) yInt, text, rgb)) {
            ci.cancel();   // drop the vanilla world-space tag; the glass plate + text is drawn at the HUD stage
         }
      } catch (Throwable ignored) {
         // any failure: do nothing, let the vanilla tag render normally (the plate re-skin still applies)
      }
   }
}
