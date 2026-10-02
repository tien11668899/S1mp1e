package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScoreboardFade;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fade the scoreboard sidebar in on appear and out on hide (vanilla pops it). The SIDEBAR objective lookup in the outer
 * {@code renderScoreboardSidebar(DrawContext, RenderTickCounter)} is routed through {@link ScoreboardFade#resolve}, which
 * keeps returning the last objective for ~150 ms after the slot clears so the inner sidebar draw (a deferred
 * {@code context.draw(Runnable)} whose body is the synthetic {@code method_55440}) keeps painting while the alpha eases to 0.
 * That body's two background {@code fill}s and three {@code drawText}s then take {@link ScoreboardFade#alpha()}, so the whole
 * panel fades as one. Only the SIDEBAR-slot lookup is wrapped — the rare team-colour-slot sidebar (ordinal 0) is left vanilla.
 */
@Mixin(InGameHud.class)
public abstract class ScoreboardGlassMixin {

   @Redirect(
      method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/scoreboard/Scoreboard;getObjectiveForSlot(Lnet/minecraft/scoreboard/ScoreboardDisplaySlot;)Lnet/minecraft/scoreboard/ScoreboardObjective;",
               ordinal = 1)
   )
   private ScoreboardObjective lg$sidebarObjective(Scoreboard scoreboard, ScoreboardDisplaySlot slot) {
      return ScoreboardFade.resolve(scoreboard.getObjectiveForSlot(slot));
   }

   @Redirect(
      method = "method_55440([Lnet/minecraft/client/gui/hud/InGameHud$SidebarEntry;Lnet/minecraft/client/gui/DrawContext;ILnet/minecraft/text/Text;I)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
   )
   private void lg$sidebarFill(DrawContext g, int x0, int y0, int x1, int y1, int color) {
      float a = ScoreboardFade.alpha();
      if (a <= 0.004F) return;
      int al = Math.round((color >>> 24 & 0xFF) * a) & 0xFF;
      g.fill(x0, y0, x1, y1, al << 24 | color & 0xFFFFFF);
   }

   @ModifyArg(
      method = "method_55440([Lnet/minecraft/client/gui/hud/InGameHud$SidebarEntry;Lnet/minecraft/client/gui/DrawContext;ILnet/minecraft/text/Text;I)V",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I"),
      index = 4
   )
   private int lg$sidebarText(int color) {
      float a = ScoreboardFade.alpha();
      if (a >= 1F) return color;
      int al = Math.round((color >>> 24 & 0xFF) * a) & 0xFF;
      return al << 24 | color & 0xFFFFFF;
   }
}
