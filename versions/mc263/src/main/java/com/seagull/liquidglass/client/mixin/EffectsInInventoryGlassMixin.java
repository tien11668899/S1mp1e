package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassEffects;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
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
 * ({@code AbstractContainerScreen} itself has none, so plain containers show no effect panel - vanilla). Vanilla 26.2
 * (javap): {@code extractRenderState} picks {@code x = leftPos + imageWidth + 2}, {@code maxW} (avail - 7, or 32 when
 * narrow = compact layout) and {@code spacing} (33, or {@code 132 / (n - 1)} above five effects), then
 * {@code extractEffects} walks the sorted list: per entry {@code extractBackground} blits a {@code w x 32} box with
 * {@code w = min(maxW, max(32 + nameW + 7, 32 + timeW + 7))}, then text, then the 18 px icon, {@code y += spacing}.
 *
 * <p>Here, at {@code extractEffects} HEAD - before any icon or text of any entry is extracted - the strip for the whole
 * list is enqueued once: uniform width = the widest entry, height = {@code (n - 1) * spacing + 32}, hotbar corner
 * radius, separators at each entry's top. The per-box blit is then skipped, so there are no per-effect plates to
 * stagger or overlap. Text, icons, positions, hit areas and the compact-mode tooltip (promoted to the top layer by
 * {@code TooltipLayerMixin}) are untouched. When glass is unusable the vanilla per-box sprites are drawn as before.
 */
@Mixin(EffectsInInventory.class)
public abstract class EffectsInInventoryGlassMixin {
   @Shadow @Final private AbstractContainerScreen<?> screen;
   @Shadow @Final private Minecraft minecraft;

   @Shadow
   private Component getEffectName(MobEffectInstance effect) {
      throw new AssertionError();
   }

   /** True while extractEffects runs with the glass strip already enqueued: per-box sprites are then skipped. */
   @Unique
   private boolean lg$stripActive;

   @Inject(method = "extractEffects", at = @At("HEAD"))
   private void lg$glassStrip(GuiGraphicsExtractor g, Collection<MobEffectInstance> effects, int x, int spacing,
                              int mouseX, int mouseY, int maxW, CallbackInfo ci) {
      this.lg$stripActive = false;
      if (effects == null || effects.isEmpty()) {
         return;
      }
      Font font = this.screen.getFont();
      float tickrate = this.minecraft.level != null ? this.minecraft.level.tickRateManager().tickrate() : 20.0F;
      int width = 0;
      for (MobEffectInstance e : effects) {
         int nameW = 32 + font.width(this.getEffectName(e)) + 7;
         int timeW = 32 + font.width(MobEffectUtil.formatDuration(e, 1.0F, tickrate)) + 7;
         width = Math.max(width, Math.min(maxW, Math.max(nameW, timeW)));
      }
      int n = effects.size();
      int top = ((AbstractContainerScreenAccessor)this.screen).liquidglass$topPos();
      int bottom = top + (n - 1) * spacing + 32;
      this.lg$stripActive = GlassEffects.strip(g, x, top, x + width, bottom, spacing, n);
   }

   @Inject(method = "extractEffects", at = @At("RETURN"))
   private void lg$glassStripEnd(GuiGraphicsExtractor g, Collection<MobEffectInstance> effects, int x, int spacing,
                                 int mouseX, int mouseY, int maxW, CallbackInfo ci) {
      this.lg$stripActive = false;
   }

   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$glassEffectBox(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (this.lg$stripActive) {
         return;   // the whole list is one glass strip, enqueued at extractEffects HEAD
      }
      if (!GlassEffects.box(g, x, y, w, h)) {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
