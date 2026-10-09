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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity name-tag background plate, per the {@code NameTags} module's {@code Background} setting (Glass / Vanilla / Off).
 *
 * <p>26.3 note: unlike 26.2, 26.3's name tag is built through {@code TextFeatureRenderer$Submit} (there is no
 * {@code NameTagFeatureRenderer$Submit} to {@code @ModifyArg}). So every non-Vanilla mode is handled the same way — at
 * {@code SubmitNodeCollection.submitNameTag} HEAD we reconstruct the tag's model matrix, hand it to {@link NameTagGlass}
 * for a GUI-stage redraw, and CANCEL the vanilla world-space submission:
 * <ul>
 *   <li><b>Glass</b> — redraw a refractive glass plate (clean-terrain backdrop) with the name crisp on top;</li>
 *   <li><b>Off</b> — redraw the name text alone, no plate;</li>
 *   <li><b>Vanilla</b> — do nothing; the game draws its normal translucent-black plate + text.</li>
 * </ul>
 *
 * <p><b>Fair play.</b> We only move or hide the tag the game already submits — we never add one it would not draw. When
 * the plate opacity is zero (a no-plate name) we leave it entirely to vanilla, and any failure falls through to the
 * normal vanilla tag.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class NameTagGlassMixin {

   @Inject(method = "submitNameTag", at = @At("HEAD"), cancellable = true)
   private void lg$nameTagRelocate(PoseStack poseStack, Vec3 pos, int yInt, Component text, boolean seeThrough,
                                   int color, CameraRenderState cam, CallbackInfo ci) {
      boolean glass = NameTagModule.glassMode();
      boolean off   = NameTagModule.offMode();
      if (!glass && !off) return;                         // Vanilla / module off: leave the vanilla tag alone
      try {
         Minecraft mc = Minecraft.getInstance();
         // vanilla: ARGB.color(getBackgroundOpacity(0.25f), 0) — alpha 0 means no plate is drawn for this name
         float op = mc.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25f);
         if (ARGB.alpha(ARGB.color(op, 0)) == 0) return;   // no plate -> leave the plateless name to vanilla

         // reconstruct the Submit's model matrix (matches submitNameTag's pushPose/translate/rotate/scale on a copy)
         Matrix4f mat = new Matrix4f(poseStack.last().pose());
         mat.translate((float) pos.x, (float) (pos.y + 0.5), (float) pos.z);
         mat.rotate(cam.orientation);
         mat.scale(0.025f, -0.025f, 0.025f);
         float x = -mc.font.width(text) / 2.0f;

         // the submitNameTag `color` arg is a light value, not the text colour — the name keeps its own style colour
         int rgb = 0xFFFFFF;
         net.minecraft.network.chat.TextColor tc = text.getStyle().getColor();
         if (tc != null) rgb = tc.getValue();

         if (NameTagGlass.capture(cam.projectionMatrix, cam.viewRotationMatrix, mat, x, (float) yInt, text, rgb, glass)) {
            ci.cancel();   // drop the vanilla world-space tag; the plate (Glass) + text is drawn at the HUD stage
         }
      } catch (Throwable ignored) {
         // any failure: do nothing, let the vanilla tag render normally
      }
   }
}
