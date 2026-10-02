package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The command-suggestion popup fades in (rising 3 px) when it appears and fades out when it goes, instead of popping.
 *
 * <p>Vanilla clears {@code suggestions} on every keystroke and rebuilds the list when the async suggestion lookup
 * completes, so there can be a frame or two with no list while typing. A {@link #LG_GRACE_NS} grace keeps the last list
 * on screen at full opacity through such a gap and treats a list that comes back within it as the same popup (no
 * re-fade) — only a real disappearance fades out, only a real appearance fades in.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsFadeMixin {

   @Unique private static final long LG_GRACE_NS = 70_000_000L;
   @Unique private static final float LG_OUT_S = 0.12F;
   @Unique private static final float LG_W = 22.0F;

   @Shadow private CommandSuggestions.SuggestionsList suggestions;

   @Unique private CommandSuggestions.SuggestionsList lg$ghost;
   @Unique private boolean lg$had;
   @Unique private long lg$gapNs;      // when the list last went null (0 = none)
   @Unique private long lg$appearNs;

   @WrapOperation(method = "extractSuggestions", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/CommandSuggestions$SuggestionsList;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"))
   private void lg$appear(CommandSuggestions.SuggestionsList list, GuiGraphicsExtractor g, int mouseX, int mouseY, Operation<Void> op) {
      long now = net.minecraft.util.Util.getNanos();
      boolean continuous = lg$had || (lg$gapNs != 0L && now - lg$gapNs < LG_GRACE_NS);
      if (!continuous) lg$appearNs = now;
      lg$had = true;
      lg$gapNs = 0L;
      lg$ghost = list;
      float t = (now - lg$appearNs) / 1.0e9F;
      float p = lg$appearNs == 0L ? 1F : 1F - (1F + LG_W * t) * (float) Math.exp(-LG_W * t);
      if (p >= 0.998F) { op.call(list, g, mouseX, mouseY); return; }
      float inv = 1F - p;
      GuiAlpha.push(1F - inv * inv);
      g.pose().pushMatrix();
      g.pose().translate(0F, 3F * inv);
      try {
         op.call(list, g, mouseX, mouseY);
      } finally {
         g.pose().popMatrix();
         GuiAlpha.pop();
      }
   }

   /** No list this frame: hold the last one through the grace period, then fade it out. */
   @Inject(method = "extractSuggestions", at = @At("HEAD"))
   private void lg$ghost(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfoReturnable<Boolean> cir) {
      if (this.suggestions != null) return;
      long now = net.minecraft.util.Util.getNanos();
      if (lg$had) { lg$had = false; lg$gapNs = now; }
      if (lg$ghost == null || lg$gapNs == 0L) return;
      long gap = now - lg$gapNs;
      float a;
      if (gap < LG_GRACE_NS) {
         a = 1F;
      } else {
         float q = (gap - LG_GRACE_NS) / 1.0e9F / LG_OUT_S;
         if (q >= 1F) { lg$ghost = null; return; }
         a = (1F - q) * (1F - q);
      }
      GuiAlpha.push(a);
      try {
         lg$ghost.extractRenderState(g, -10000, -10000);   // off-screen mouse: the ghost never re-selects
      } finally {
         GuiAlpha.pop();
      }
   }
}
