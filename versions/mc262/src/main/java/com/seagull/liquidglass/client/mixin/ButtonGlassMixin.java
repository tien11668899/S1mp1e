package com.seagull.liquidglass.client.mixin;

import java.util.WeakHashMap;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every standard button becomes a translucent liquid-glass capsule (the BTN program, which samples no backdrop, so
 * it works on the title screen with no grab).
 *
 * <p>Knob colour for {@link GlassPipeline#btn()}: A = enabled (255) / disabled-dim (102), R = corner 1.0 (full
 * capsule), G = 1 − lift, B = opacity (the widget's own sprite alpha).
 *
 * <p><b>Fades (ported from the 1.21.1 ButtonGlassMixin, "System 6").</b> The recovered 26.2 code switched G
 * between 255 and 48 the instant the mouse crossed a button, so the hover highlight popped on and off. Now:
 * <ul>
 *   <li><b>Hover lift</b> — a per-button {@link Fade} eases the lift 0 ↔ 0.81 over {@link #HOVER_FADE_MS}
 *       (100 ms, same as 1.21.1). Keyed in a {@link WeakHashMap} so discarded widgets evict themselves and
 *       buttons never cross-wire; a fresh entry starts settled at the current state so a rebuilt screen doesn't
 *       flash. Settled endpoints are unchanged (G 255 at rest, round(255·0.19) = 48 hovered).</li>
 *   <li><b>Screen-open opacity</b> — {@link ScreenOpenFade} (shared with the glass option sliders) eases the capsule
 *       opacity 0 → 1 over 150 ms (same as 1.21.1), restarted whenever the current screen instance changes, so a newly opened
 *       screen's buttons fade in. Identity compare: a resize reuses the Screen instance and never restarts it.
 *       Endpoint 1.0 keeps a settled button's opacity exactly the widget's own alpha.</li>
 * </ul>
 * The 26.2 {@link Fade#to} restarts a leg from the previous TARGET rather than the live value, which would pop the
 * highlight when the mouse leaves mid-fade; {@link #lg$retarget} re-bases on the live value first. (Kept local to
 * this mixin so the other glass animations that use {@link Fade} are unaffected.)
 */
@Mixin({AbstractButton.class})
public abstract class ButtonGlassMixin {

   /** Hovered lift: G = 255·(1 − 0.81) ≈ 48, the value the recovered 26.2 code used. */
   private static final float LIFT_ON = 0.81F;
   /** Hover ease — same 100 ms as 1.21.1. */
   private static final float HOVER_FADE_MS = 100.0F;
   /** Per-button hover fade; WeakHashMap auto-evicts discarded widgets. Render thread only. */
   private static final WeakHashMap<AbstractButton, Fade> lg$hoverFades = new WeakHashMap<>();

   @Redirect(
      method = {"extractDefaultSprite"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"
      )
   )
   private void lg$glassButton(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int tint) {
      if (GlassPipeline.ensureReady() && GlassPipeline.btnUsable()) {
         AbstractButton self = (AbstractButton)(Object)this;

         // hover lift, eased per button
         boolean over = self.isHoveredOrFocused();
         Fade hover = lg$hoverFades.get(self);
         if (hover == null) {
            hover = new Fade(over ? 1.0F : 0.0F, HOVER_FADE_MS);   // start settled: no flash on a rebuilt screen
            lg$hoverFades.put(self, hover);
         }
         lg$retarget(hover, over ? 1.0F : 0.0F);
         float lift = LIFT_ON * hover.value();
         int liftG = Math.round(255.0F * (1.0F - lift)) & 0xFF;

         // screen-open opacity: the fade shared with the glass option sliders, restarted per screen instance
         Screen screen = Minecraft.getInstance().gui.screen();
         int alphaByte = Math.round((tint >>> 24 & 0xFF) * ScreenOpenFade.value(screen)) & 0xFF;

         int col = (self.active ? 255 : 102) << 24 | 0xFF0000 | liftG << 8 | alphaByte;
         ((GuiGraphicsExtractorAccessor)g)
            .liquidglass$guiRenderState()
            .addGuiElement(new GlassRectRenderState(GlassPipeline.btn(), TextureSetup.noTexture(), g.pose(), x, y, x + w, y + h, 10, col, null));
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h, tint);
      }
   }

   /** Retarget from the LIVE value (26.2 Fade.to would otherwise restart from the old target and pop). */
   private static void lg$retarget(Fade fade, float target) {
      if (fade.target() != target) {
         float current = fade.value();
         fade.snap(current);
         fade.to(target);
      }
   }
}
