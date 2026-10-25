package com.seagull.liquidglass.client.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3: vanilla put a grey 0x90505050 box behind every single text line, so each column was a ragged stack of grey bars.
 * Now each group of consecutive lines (groups are separated by vanilla's blank lines) sits on one glass card — a refracting
 * plate with a grey scrim for readability — sized to its widest line. The per-line boxes are dropped while the cards draw.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugOverlayGlassMixin {

   @Unique private boolean lg$cards;

   @Inject(method = "renderLines", at = @At("HEAD"))
   private void lg$cards(GuiGraphics g, List<String> lines, boolean left, CallbackInfo ci) {
      this.lg$cards = false;
      Font font = Minecraft.getInstance().font;
      int lh = 9, gw = g.guiWidth();
      int i = 0, n = lines.size();
      boolean any = false, ok = true;
      while (i < n) {
         if (lines.get(i) == null || lines.get(i).isEmpty()) { i++; continue; }
         int start = i, w = 0;
         while (i < n && lines.get(i) != null && !lines.get(i).isEmpty()) { w = Math.max(w, font.width(lines.get(i))); i++; }
         int y0 = 2 + lh * start - 2, y1 = 2 + lh * i;
         int x0 = left ? 0 : gw - 4 - w, x1 = left ? w + 4 : gw;
         if (GlassSurface.plate(g, x0, y0, x1, y1)) {
            GlassSurface.scrim(g, x0, y0, x1, y1, Math.min(x1 - x0, y1 - y0) / 2.0F * 0.5F * 0.24F, 0x60000000);
            any = true;
         } else {
            ok = false;
         }
      }
      this.lg$cards = any && ok;
   }

   @WrapOperation(method = "renderLines", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
   private void lg$noLineBoxes(GuiGraphics g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      if (!this.lg$cards) original.call(g, x0, y0, x1, y1, argb);
   }
}
