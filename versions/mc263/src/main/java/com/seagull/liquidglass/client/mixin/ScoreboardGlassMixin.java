package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ScoreboardFade;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fade the scoreboard sidebar in on appear and out on hide (vanilla pops it). The SIDEBAR objective lookup in
 * {@code extractScoreboardSidebar} is routed through {@link ScoreboardFade#resolve(Objective)}, which keeps returning the
 * last objective for ~150 ms after the slot clears so {@code displayScoreboardSidebar} keeps drawing while the alpha eases
 * to 0. The sidebar's two background {@code fill}s and three {@code text}s then take {@link ScoreboardFade#alpha()}, so the
 * whole panel fades as one. (Only the SIDEBAR-slot lookup is wrapped — the rare team-colour-slot sidebar is left vanilla.)
 */
@Mixin(Hud.class)
public class ScoreboardGlassMixin {

   @Redirect(
      method = "extractScoreboardSidebar",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/world/scores/Scoreboard;getDisplayObjective(Lnet/minecraft/world/scores/DisplaySlot;)Lnet/minecraft/world/scores/Objective;",
               ordinal = 1)
   )
   private Objective lg$sidebarObjective(Scoreboard scoreboard, DisplaySlot slot) {
      return ScoreboardFade.resolve(scoreboard.getDisplayObjective(slot));
   }

   @Redirect(
      method = "displayScoreboardSidebar",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V")
   )
   private void lg$sidebarFill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
      float a = ScoreboardFade.alpha();
      if (a <= 0.004F) return;
      int al = Math.round((color >>> 24 & 0xFF) * a) & 0xFF;
      g.fill(x0, y0, x1, y1, al << 24 | color & 0xFFFFFF);
   }

   @ModifyArg(
      method = "displayScoreboardSidebar",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
      index = 4
   )
   private int lg$sidebarText(int color) {
      float a = ScoreboardFade.alpha();
      if (a >= 1F) return color;
      int al = Math.round((color >>> 24 & 0xFF) * a) & 0xFF;
      return al << 24 | color & 0xFFFFFF;
   }
}
