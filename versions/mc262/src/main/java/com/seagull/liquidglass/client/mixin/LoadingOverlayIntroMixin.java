package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.BrandIntro;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the vanilla red "Mojang Studios" boot loading screen with the S1mp1e brand intro.
 *
 * <p>Two phases. While the intro is <b>holding</b> ({@code fadeOutStart == -1}) we cancel vanilla's whole
 * {@code extractRenderState} and draw the intro on a solid black base instead; {@link #lg$hold} keeps the overlay up
 * until the intro has settled (so a fast resource reload can't cut it short). Once the resource reload is done AND the
 * intro has settled, vanilla starts its own fade-out — there we let it run (it renders the title screen behind and drops
 * the overlay for us), but we {@linkplain #lg$blackVeil force its veil fill to black} (vanilla's is the Mojang red, the
 * one the user asked to be rid of), {@linkplain #lg$noLogo suppress the Mojang logo} and {@linkplain #lg$noBar the
 * progress bar}, and {@linkplain #lg$fadeMark keep the settled mark on top}, fading out with the veil so the mark melts
 * into the title with no pop. When the pipeline or strip is unavailable we do nothing and vanilla shows as normal.
 */
@Mixin(net.minecraft.client.gui.screens.LoadingOverlay.class)
public abstract class LoadingOverlayIntroMixin {
   @Shadow private long fadeOutStart;

   /** {@link Util#getNanos()} of the first frame the intro was actually drawn; 0 until then. The intro clock runs on
    *  nanoseconds: millisecond time steps visibly judder at high refresh rates (a 144 Hz frame is 6.94 ms). */
   @Unique private long lg$introStart;
   /** This overlay's intro mode: the very first overlay (boot) plays the full intro; any later reload uses the short cut. */
   @Unique private boolean lg$short;
   /** Set once the first (boot) overlay has claimed the full intro, so subsequent reloads take the short cut. */
   @Unique private static boolean lg$bootSeen;

   @Unique
   private float lg$elapsed(long nowNs) {
      return this.lg$introStart == 0L ? 0.0F : (float) ((nowNs - this.lg$introStart) / 1.0E9);
   }

   @Unique
   private float lg$hold() {
      return this.lg$short ? BrandIntro.HOLD_SHORT : BrandIntro.HOLD_FULL;
   }

   /** Draw the intro on black while holding; hand off to (a tamed) vanilla for the fade-out. */
   @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
   private void lg$intro(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!BrandIntro.ready()) {
         return;   // pipeline/strip not up yet — leave vanilla alone
      }
      long now = Util.getNanos();
      if (this.lg$introStart == 0L) {
         this.lg$introStart = now;
         this.lg$short = lg$bootSeen;
         lg$bootSeen = true;
      }
      if (this.fadeOutStart == -1L) {
         int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
         int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
         g.fill(0, 0, w, h, 0xFF000000);
         BrandIntro.draw(g, this.lg$elapsed(now), this.lg$short, 1.0F);
         ci.cancel();
      }
      // else: fade-out — fall through to vanilla, tamed by the redirects/injects below
   }

   /**
    * During the fade-out, keep the settled mark on top of the black veil, fading with it. Vanilla holds an opaque veil
    * for the first second after {@code fadeOutStart} (its clear colour), then reveals the screen behind over the next
    * second — the mark tracks that reveal so it melts into the title with no pop.
    */
   @Inject(method = "extractRenderState", at = @At("RETURN"))
   private void lg$fadeMark(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!BrandIntro.ready() || this.fadeOutStart == -1L) {
         return;
      }
      float p = (Util.getMillis() - this.fadeOutStart) / 1000.0F;    // vanilla's fade-out progress (0..2), its ms clock
      float fo = 1.0F - Math.min(Math.max(p - 1.0F, 0.0F), 1.0F);    // 1 for the first second, then 1 -> 0
      BrandIntro.draw(g, this.lg$elapsed(Util.getNanos()), this.lg$short, fo);
   }

   /**
    * Every red the loading screen paints — the fade veils and the {@code clearColorOverride} — reads the Mojang
    * {@code BRAND_BACKGROUND} supplier; return black instead so the whole thing (boot hold and fade-out) stays black,
    * as requested. One redirect covers all three call sites.
    */
   @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Ljava/util/function/IntSupplier;getAsInt()I"))
   private int lg$brandBlack(java.util.function.IntSupplier brand) {
      return BrandIntro.ready() ? 0xFF000000 : brand.getAsInt();
   }

   /** Suppress the Mojang logo blits during the fade-out. */
   @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V"))
   private void lg$noLogo(GuiGraphicsExtractor g, com.mojang.blaze3d.pipeline.RenderPipeline pipeline, Identifier tex,
         int x, int y, float u, float v, int uw, int vh, int tw, int th, int tex1, int tex2, int col) {
      if (!BrandIntro.ready()) {
         g.blit(pipeline, tex, x, y, u, v, uw, vh, tw, th, tex1, tex2, col);
      }
   }

   /** Drop the vanilla progress bar during the fade-out (the intro is its own progress). */
   @Inject(method = "extractProgressBar", at = @At("HEAD"), cancellable = true)
   private void lg$noBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float delta, CallbackInfo ci) {
      if (BrandIntro.ready()) {
         ci.cancel();
      }
   }

   /** Hold the overlay up until the intro has settled — independent of vanilla's fade-in timer, which never runs here. */
   @Inject(method = "isReadyToFadeOut", at = @At("HEAD"), cancellable = true)
   private void lg$holdReady(CallbackInfoReturnable<Boolean> cir) {
      if (BrandIntro.ready()) {
         cir.setReturnValue(this.lg$introStart != 0L && this.lg$elapsed(Util.getNanos()) >= this.lg$hold());
      }
   }
}
