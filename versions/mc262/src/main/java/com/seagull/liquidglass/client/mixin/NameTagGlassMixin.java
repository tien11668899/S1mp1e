package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.gui.NameTagGlass;
import dev.s1mp1e.client.module.NameTagModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity name-tag background plate: restyled or removed per the {@code NameTags} module's
 * {@code Background} setting (Glass / Vanilla / Off).
 *
 * <p>Name tags are drawn in <b>world space</b>, so the GUI-space SDF refraction pipeline (which samples a per-frame GUI
 * backdrop grab) does not apply here — there is no backdrop to refract. {@code SubmitNodeCollection.submitNameTag} computes
 * the plate colour as {@code ARGB.color(getBackgroundOpacity(0.25f), 0)} (black at the options' background opacity) and
 * passes it as the {@code backgroundColor} of every {@code NameTagFeatureRenderer.Submit}. This {@code @ModifyArg} hands
 * that colour to {@link dev.s1mp1e.client.module.NameTagModule#plate(int)}, which keeps the vanilla alpha for Glass/Vanilla
 * (so the plate's presence, opacity and depth behaviour are identical to vanilla) and zeroes it for Off.
 *
 * <p><b>Fair play.</b> This only re-skins or hides the plate the game already draws. It does not change when or where a
 * name shows, does not touch depth / see-through-walls behaviour, and reads nothing about the entity. A zero-alpha colour
 * (a discrete / no-plate name) is passed straight through, so no mode ever adds a plate where vanilla draws none.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class NameTagGlassMixin {

   /**
    * GLASS mode only: capture the tag for the GUI-stage refraction pass and CANCEL the vanilla world-space submission,
    * so the backdrop grab is clean terrain where the plate will refract. Runs at HEAD and reconstructs the Submit's
    * model matrix exactly as the method does below (push a copy of the top pose, translate to the tag, billboard-rotate
    * by the camera orientation, apply the 0.025 nametag scale), plus x = -font.width/2 and y = the int y param.
    *
    * <p>Vanilla / Off modes do NOT cancel here — they fall through to the {@code @ModifyArg} below. When the plate
    * opacity is zero (a no-plate name) Glass mode also falls through, so no glass plate is ever added where vanilla
    * draws none (fair play).
    */
   @Inject(method = "submitNameTag", at = @At("HEAD"), cancellable = true)
   private void lg$nameGlassCapture(PoseStack poseStack, Vec3 pos, int yInt, Component text, boolean seeThrough,
                                    int color, CameraRenderState cam, CallbackInfo ci) {
      if (!NameTagModule.glassMode()) return;
      try {
         Minecraft mc = Minecraft.getInstance();
         // vanilla: ARGB.color(optionsRenderState.getBackgroundOpacity(0.25f), 0) — alpha 0 means no plate is drawn
         float op = mc.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25f);
         if (ARGB.alpha(ARGB.color(op, 0)) == 0) return;   // no plate -> leave the plateless name to vanilla

         // reconstruct the Submit's pose (matches submitNameTag's pushPose/translate/mulPose/scale on a copy)
         Matrix4f mat = new Matrix4f(poseStack.last().pose());
         mat.translate((float) pos.x, (float) (pos.y + 0.5), (float) pos.z);
         mat.rotate(cam.orientation);
         mat.scale(0.025f, -0.025f, 0.025f);
         float x = -mc.font.width(text) / 2.0f;

         // the submitNameTag `color` arg is a light value, not the text colour — the name's colour is its own style
         // (team / custom colour), defaulting to white like vanilla
         int rgb = 0xFFFFFF;
         net.minecraft.network.chat.TextColor tc = text.getStyle().getColor();
         if (tc != null) rgb = tc.getValue();

         if (NameTagGlass.capture(cam.projectionMatrix, cam.viewRotationMatrix, mat, x, (float) yInt, text, rgb)) {
            ci.cancel();   // drop the vanilla world-space tag; the glass plate + text is drawn at the HUD stage
         }
      } catch (Throwable ignored) {
         // any failure: do nothing, let the vanilla tag render normally (the @ModifyArg still re-skins it)
      }
   }

   @ModifyArg(
      method = "submitNameTag",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/renderer/feature/NameTagFeatureRenderer$Submit;<init>(Lorg/joml/Matrix4fc;FFLnet/minecraft/network/chat/Component;IIILnet/minecraft/client/gui/Font$DisplayMode;)V"
      ),
      index = 6
   )
   private int lg$nameBackground(int backgroundColor) {
      return dev.s1mp1e.client.module.NameTagModule.plate(backgroundColor);
   }
}
