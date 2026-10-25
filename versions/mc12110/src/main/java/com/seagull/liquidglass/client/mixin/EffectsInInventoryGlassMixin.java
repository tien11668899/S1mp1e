package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassEffects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The status-effect list beside the survival / creative inventory becomes ONE continuous liquid-glass strip
 * (user choice "A 合成一整條"), like the hotbar stood on its side.
 *
 * <p>{@link EffectsInInventory} is the helper both {@code InventoryScreen} and {@code CreativeModeInventoryScreen} use
 * ({@code AbstractContainerScreen} itself has none, so plain containers show no effect panel - vanilla). Vanilla 1.21.x
 * (javap): {@code renderEffects} picks {@code x = leftPos + imageWidth + 2}, {@code large} (room for 120 px, else the
 * 32 px compact boxes) and {@code spacing} (33, or {@code 132 / (n - 1)} above five effects), then
 * {@code renderBackgrounds} blits one {@code 120 x 32} / {@code 32 x 32} box per effect from {@code topPos} down, before
 * any icon or label is drawn.
 *
 * <p>Here, at {@code renderBackgrounds} HEAD, the strip for the whole list is drawn once: the box width, height =
 * {@code (n - 1) * spacing + 32}, hotbar corner radius, separators at each entry's top. The per-box blit is then
 * skipped, so there are no per-effect plates to stagger or overlap. Text, icons, positions, hit areas and the
 * compact-mode tooltip (promoted to the top layer by {@code TooltipLayerMixin}) are untouched. When glass is unusable
 * the vanilla per-box sprites are drawn as before. (26.x sizes each entry by its text; 1.21.x's boxes are fixed.)
 */
@Mixin(EffectsInInventory.class)
public abstract class EffectsInInventoryGlassMixin {
   @Shadow @Final private AbstractContainerScreen<?> screen;

   /** True while renderBackgrounds runs with the glass strip already drawn: per-box sprites are then skipped. */
   @Unique
   private boolean lg$stripActive;

   @Inject(method = "renderBackgrounds", at = @At("HEAD"))
   private void lg$glassStrip(GuiGraphics g, int x, int spacing, Iterable<MobEffectInstance> effects, boolean large,
                              CallbackInfo ci) {
      this.lg$stripActive = false;
      int n = 0;
      for (MobEffectInstance ignored : effects) n++;
      if (n == 0) {
         return;
      }
      int width = large ? 120 : 32;
      int top = ((AbstractContainerScreenAccessor)this.screen).liquidglass$topPos();
      int bottom = top + (n - 1) * spacing + 32;
      this.lg$stripActive = GlassEffects.strip(g, x, top, x + width, bottom, spacing, n);
   }

   @Inject(method = "renderBackgrounds", at = @At("RETURN"))
   private void lg$glassStripEnd(GuiGraphics g, int x, int spacing, Iterable<MobEffectInstance> effects, boolean large,
                                 CallbackInfo ci) {
      this.lg$stripActive = false;
   }

   @Redirect(
      method = {"renderBackgrounds"},   // 26.x: extractBackground
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIII)V"
      )
   )
   private void lg$glassEffectBox(GuiGraphics g, RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int w, int h) {
      if (this.lg$stripActive) {
         return;   // the whole list is one glass strip, drawn at renderBackgrounds HEAD
      }
      if (!GlassEffects.box(g, x, y, w, h)) {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
