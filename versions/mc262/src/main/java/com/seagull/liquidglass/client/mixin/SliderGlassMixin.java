package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.ErrorOnce;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla option sliders → liquid glass ({@link VanillaSliderSkin}): glass row, label lifted, thin track with the
 * white-pill → glass-lens knob. The two sprite blits and the label placement are redirected; value, stepping,
 * keyboard handling and narration stay vanilla. While the skin is drawn, {@code setValueFromMouse} maps the pointer
 * onto the skin's knob travel so the knob sits under the pointer. Falls back to the vanilla sprites (and vanilla's
 * mapping) when the glass pipelines aren't available.
 *
 * <p>"Held" is tracked here from {@code onClick} / {@code onRelease} plus the real button state, not from vanilla's
 * {@code dragging}: that flag is only cleared by {@code onRelease}, which goes to the focused widget, so e.g. Tab
 * during a drag would leave it stuck and later presses anywhere would paint this slider as held.
 */
@Mixin({AbstractSliderButton.class})
public abstract class SliderGlassMixin {
   @Shadow
   protected double value;
   @Unique
   private VanillaSliderSkin lg$skin;
   @Unique
   private boolean lg$skinned;
   @Unique
   private boolean lg$held;

   @Shadow
   protected abstract void setValue(double value);

   @Inject(method = {"onClick"}, at = @At("HEAD"))
   private void lg$press(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
      this.lg$held = ((AbstractSliderButton)(Object)this).active;
   }

   @Inject(method = {"onRelease"}, at = @At("HEAD"))
   private void lg$release(MouseButtonEvent event, CallbackInfo ci) {
      this.lg$held = false;
   }

   /** Mouse → value on the skin's knob travel (vanilla maps onto its own 8 px handle travel). */
   @Inject(method = {"setValueFromMouse"}, at = @At("HEAD"), cancellable = true)
   private void lg$mapToSkin(MouseButtonEvent event, CallbackInfo ci) {
      if (this.lg$skinned) {
         AbstractSliderButton self = (AbstractSliderButton)(Object)this;
         this.setValue(VanillaSliderSkin.valueAt(event.x(), self.getX(), self.getWidth()));
         ci.cancel();
      }
   }

   @Redirect(
      method = {"extractWidgetRenderState"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"
      )
   )
   private void lg$glassSlider(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int tint) {
      boolean handle = sprite.getPath().contains("handle");
      if (handle) {
         // the knob was already painted with the row (the handle blit carries a handle-sized rect, not the row's)
         if (!this.lg$skinned) {
            g.blitSprite(pipeline, sprite, x, y, w, h, tint);
         }
         return;
      }
      this.lg$skinned = false;
      if (!GlassPipeline.ensureReady() || !GlassPipeline.btnUsable() || !VanillaSliderSkin.fits(w, h)) {
         g.blitSprite(pipeline, sprite, x, y, w, h, tint);
         return;
      }
      try {
         AbstractSliderButton self = (AbstractSliderButton)(Object)this;
         Minecraft mc = Minecraft.getInstance();
         if (this.lg$skin == null) {
            this.lg$skin = new VanillaSliderSkin();
         }
         if (GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
               || this.lg$skin.paintGap()) {
            this.lg$held = false;   // released where the release event didn't reach us, or a leftover from before
         }
         double pointerX = mc.mouseHandler.getScaledXPos(mc.getWindow());
         float alpha = (tint >>> 24 & 0xFF) / 255.0F * ScreenOpenFade.value(mc.gui.screen());
         this.lg$skin.paint(g, x, y, w, h, this.value, this.lg$held, pointerX, self.isHoveredOrFocused(), self.active, alpha);
         this.lg$skinned = true;
      } catch (Throwable t) {
         ErrorOnce.report("SliderGlassMixin", t);
         g.blitSprite(pipeline, sprite, x, y, w, h, tint);
      }
   }

   /** Label: vanilla centres it over the whole row; with the glass skin it sits above the track. */
   @Redirect(
      method = {"extractWidgetRenderState"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/AbstractSliderButton;extractScrollingStringOverContents(Lnet/minecraft/client/gui/ActiveTextCollector;Lnet/minecraft/network/chat/Component;I)V"
      )
   )
   private void lg$liftLabel(AbstractSliderButton self, ActiveTextCollector text, Component message, int margin) {
      int x = self.getX(), y = self.getY(), w = self.getWidth(), h = self.getHeight();
      int bottom = this.lg$skinned ? VanillaSliderSkin.labelBottom(y, h) : y + h;
      text.acceptScrollingWithDefaultCenter(message, x + margin, x + w - margin, y, bottom);
   }
}
