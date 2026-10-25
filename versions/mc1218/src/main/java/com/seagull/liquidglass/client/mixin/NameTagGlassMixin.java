package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.gui.NameTagGlass;
import dev.s1mp1e.client.module.NameTagModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity name-tag background plate: restyled or removed per the {@code NameTags} module's
 * {@code Background} setting (Glass / Vanilla / Off).
 *
 * <p>Name tags are drawn in <b>world space</b>, so the GUI-space SDF refraction pipeline (which samples a per-frame GUI
 * backdrop grab) does not apply here — there is no backdrop to refract. 1.21.8's {@code EntityRenderer.renderNameTag}
 * (javap) computes the plate colour as {@code (int) (options.getBackgroundOpacity(0.25f) * 255) << 24} (black at the
 * options' background opacity) and passes it as the background of the first {@code Font.drawInBatch} (the see-through
 * pass). This {@code @ModifyArg} hands that colour to {@link NameTagModule#plate(int)}, which keeps the vanilla alpha for
 * Glass/Vanilla (so the plate's presence, opacity and depth behaviour are identical to vanilla) and zeroes it for Off.
 * (26.x: {@code SubmitNodeCollection.submitNameTag} / {@code NameTagFeatureRenderer.Submit}.)
 *
 * <p><b>Fair play.</b> This only re-skins or hides the plate the game already draws. It does not change when or where a
 * name shows, does not touch depth / see-through-walls behaviour, and reads nothing about the entity. A zero-alpha colour
 * (a discrete / no-plate name) is passed straight through, so no mode ever adds a plate where vanilla draws none.
 */
@Mixin(EntityRenderer.class)
public abstract class NameTagGlassMixin {

   @Shadow @Final protected EntityRenderDispatcher entityRenderDispatcher;

   /**
    * GLASS mode only: capture the tag for the GUI-stage refraction pass and CANCEL the vanilla world-space draw, so the
    * backdrop grab is clean terrain where the plate will refract. Runs at HEAD and reconstructs the tag's model matrix
    * exactly as the method does below (a copy of the top pose, translated to the attachment + 0.5, billboard-rotated by
    * the camera orientation, the 0.025 name-tag scale), plus x = -font.width/2 and y = -10 for "deadmau5", else 0.
    *
    * <p>Vanilla / Off modes do NOT cancel here — they fall through to the {@code @ModifyArg} below. When the plate
    * opacity is zero (a no-plate name) Glass mode also falls through, so no glass plate is ever added where vanilla
    * draws none (fair play).
    */
   @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
   private void lg$nameGlassCapture(EntityRenderState state, Component text, PoseStack poseStack, MultiBufferSource buffers,
                                    int light, CallbackInfo ci) {
      if (!NameTagModule.glassMode()) return;
      try {
         Vec3 pos = state.nameTagAttachment;
         if (pos == null) return;
         Minecraft mc = Minecraft.getInstance();
         // vanilla: (int) (getBackgroundOpacity(0.25f) * 255) << 24 — alpha 0 means no plate is drawn
         if ((int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) == 0) return;   // no plate -> leave it to vanilla

         Matrix4f mat = new Matrix4f(poseStack.last().pose());
         mat.translate((float) pos.x, (float) (pos.y + 0.5), (float) pos.z);
         mat.rotate(this.entityRenderDispatcher.cameraOrientation());
         mat.scale(0.025f, -0.025f, 0.025f);
         float x = -mc.font.width(text) / 2.0f;
         float y = "deadmau5".equals(text.getString()) ? -10.0f : 0.0f;

         // the name's colour is its own style (team / custom colour), defaulting to white like vanilla
         int rgb = 0xFFFFFF;
         net.minecraft.network.chat.TextColor tc = text.getStyle().getColor();
         if (tc != null) rgb = tc.getValue();

         if (NameTagGlass.capture(mat, x, y, text, rgb)) {
            ci.cancel();   // drop the vanilla world-space tag; the glass plate + text is drawn at the HUD stage
         }
      } catch (Throwable ignored) {
         // any failure: do nothing, let the vanilla tag render normally (the @ModifyArg still re-skins it)
      }
   }

   @ModifyArg(
      method = "renderNameTag",
      at = @At(
         value = "INVOKE", ordinal = 0,
         target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"
      ),
      index = 8
   )
   private int lg$nameBackground(int backgroundColor) {
      return NameTagModule.plate(backgroundColor);
   }
}
