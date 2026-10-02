package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScoreboardFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fade the scoreboard sidebar in on appear and out on hide (vanilla pops it). 1.20.1 port of the 1.21.1 line's (26.2's)
 * mixin.
 *
 * <p>1.20.1 (decompiled {@code InGameHud}): {@code render} looks the objective up inline — three
 * {@code scoreboard.getObjectiveForSlot(int)} calls, in bytecode order the team-colour slot ({@code 3 + colour}), the
 * SIDEBAR slot ({@code 1}) and the player-list slot ({@code 0}) — and calls {@code renderScoreboardSidebar(DrawContext,
 * ScoreboardObjective)} when one is set; that method draws directly (no deferred lambda as in 1.21): three background
 * {@code fill}s and three {@code drawText}s (names / title = {@code Text}, scores = {@code String}).
 *
 * <p>The SIDEBAR lookup (ordinal 1) reports the objective to {@link ScoreboardFade#resolve} every frame; every fill and
 * text of the sidebar takes {@link ScoreboardFade#alpha()} and is recorded. When the sidebar is hidden the client has
 * no scores left to draw (a vanilla server removes an undisplayed objective from the client in the same tick), so the
 * fade-out replays that recording at the falling alpha right where the lookup happens — see {@link ScoreboardFade}.
 * The rare team-colour-slot sidebar (ordinal 0) is drawn at whatever the alpha is and is not tracked.
 */
@Mixin(InGameHud.class)
public abstract class ScoreboardGlassMixin {

   @Redirect(
      method = "render",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/scoreboard/Scoreboard;getObjectiveForSlot(I)Lnet/minecraft/scoreboard/ScoreboardObjective;",
               ordinal = 1)
   )
   private ScoreboardObjective lg$sidebarObjective(Scoreboard scoreboard, int slot, DrawContext context, float tickDelta) {
      ScoreboardObjective real = scoreboard.getObjectiveForSlot(slot);
      if (ScoreboardFade.resolve(real)) {
         float a = ScoreboardFade.alpha();
         TextRenderer font = MinecraftClient.getInstance().textRenderer;
         for (ScoreboardFade.Op op : ScoreboardFade.ghost()) {
            if (op.text == null) {
               context.fill(op.x0, op.y0, op.x1, op.y1, lg$fadeFill(op.color, a));
            } else if (op.text instanceof Text t) {
               context.drawText(font, t, op.x0, op.y0, lg$fadeText(op.color, a), false);
            } else {
               context.drawText(font, (String) op.text, op.x0, op.y0, lg$fadeText(op.color, a), false);
            }
         }
      }
      return real;
   }

   @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"))
   private void lg$recordBegin(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
      ScoreboardFade.beginRecord();
   }

   @Inject(method = "renderScoreboardSidebar", at = @At("RETURN"))
   private void lg$recordEnd(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
      ScoreboardFade.endRecord();
   }

   @Redirect(
      method = "renderScoreboardSidebar",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
   )
   private void lg$sidebarFill(DrawContext g, int x0, int y0, int x1, int y1, int color) {
      ScoreboardFade.recFill(x0, y0, x1, y1, color);
      float a = ScoreboardFade.alpha();
      if (a <= 0.004F) return;
      g.fill(x0, y0, x1, y1, lg$fadeFill(color, a));
   }

   @Redirect(
      method = "renderScoreboardSidebar",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I")
   )
   private int lg$sidebarText(DrawContext g, TextRenderer font, Text text, int x, int y, int color, boolean shadow) {
      ScoreboardFade.recText(text, x, y, color);
      return g.drawText(font, text, x, y, lg$fadeText(color, ScoreboardFade.alpha()), shadow);
   }

   @Redirect(
      method = "renderScoreboardSidebar",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I")
   )
   private int lg$sidebarScore(DrawContext g, TextRenderer font, String text, int x, int y, int color, boolean shadow) {
      ScoreboardFade.recText(text, x, y, color);
      return g.drawText(font, text, x, y, lg$fadeText(color, ScoreboardFade.alpha()), shadow);
   }

   @Unique
   private static int lg$fadeFill(int color, float a) {
      if (a >= 1F) return color;
      int al = Math.round((color >>> 24 & 0xFF) * a) & 0xFF;
      return al << 24 | color & 0xFFFFFF;
   }

   /** A text colour at the fade (a missing alpha byte means opaque; alpha 0..3 would be read as opaque again). */
   @Unique
   private static int lg$fadeText(int color, float a) {
      if (a >= 1F) return color;
      int base = color >>> 24 & 0xFF;
      if (base < 4) base = 0xFF;
      int al = Math.max(4, Math.round(base * a)) & 0xFF;
      return al << 24 | color & 0xFFFFFF;
   }
}
