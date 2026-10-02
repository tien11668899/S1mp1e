package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScoreboardFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
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
 * Fade the scoreboard sidebar in on appear and out on hide (vanilla pops it). 1.14.4 port of the 1.20.1 / 1.21.1
 * lines' (26.2's) mixin.
 *
 * <p>1.14.4 ({@code InGameHud}, javap-read): {@code render} looks the objective up inline — three
 * {@code scoreboard.getObjectiveForSlot(int)} calls, in bytecode order the team-colour slot ({@code 3 + colour}), the
 * SIDEBAR slot ({@code 1}) and the player-list slot ({@code 0}) — and calls
 * {@code renderScoreboardSidebar(MatrixStack, ScoreboardObjective)} when one is set; that method draws directly: three
 * background {@code fill}s (compiled with {@code InGameHud} as owner) and three {@code TextRenderer.draw}s (names /
 * title = {@code Text}, scores = {@code String}).
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
   private ScoreboardObjective lg$sidebarObjective(Scoreboard scoreboard, int slot, float tickDelta) {
      ScoreboardObjective real = scoreboard.getObjectiveForSlot(slot);
      if (ScoreboardFade.resolve(real)) {
         float a = ScoreboardFade.alpha();
         TextRenderer font = MinecraftClient.getInstance().textRenderer;
         for (ScoreboardFade.Op op : ScoreboardFade.ghost()) {
            if (op.text == null) {
               DrawableHelper.fill(op.x0, op.y0, op.x1, op.y1, lg$fadeFill(op.color, a));
            } else {
               lg$drawFaded(font, (String) op.text, (float) op.x0, (float) op.y0, op.color, a);
            }
         }
      }
      return real;
   }

   @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"))
   private void lg$recordBegin(ScoreboardObjective objective, CallbackInfo ci) {
      ScoreboardFade.beginRecord();
   }

   @Inject(method = "renderScoreboardSidebar", at = @At("RETURN"))
   private void lg$recordEnd(ScoreboardObjective objective, CallbackInfo ci) {
      ScoreboardFade.endRecord();
   }

   @Redirect(
      method = "renderScoreboardSidebar",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/hud/InGameHud;fill(IIIII)V")
   )
   private void lg$sidebarFill(int x0, int y0, int x1, int y1, int color) {
      ScoreboardFade.recFill(x0, y0, x1, y1, color);
      float a = ScoreboardFade.alpha();
      if (a <= 0.004F) return;
      DrawableHelper.fill(x0, y0, x1, y1, lg$fadeFill(color, a));
   }

   @Redirect(
      method = "renderScoreboardSidebar",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I")
   )
   private int lg$sidebarScore(TextRenderer font, String text, float x, float y, int color) {
      ScoreboardFade.recText(text, Math.round(x), Math.round(y), color);
      return lg$drawFaded(font, text, x, y, color, ScoreboardFade.alpha());
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
      // 1.14.4: vanilla's sidebar colours carry a legacy alpha byte (0x20) that is never used (blending is off when the
      // text is drawn) - the text is opaque, so the fade starts from full alpha
      int al = Math.max(4, Math.round(0xFF * a)) & 0xFF;
      return al << 24 | color & 0xFFFFFF;
   }

   /** One sidebar string at the fade: vanilla state when opaque, blending switched on around the draw while fading. */
   @Unique
   private static int lg$drawFaded(TextRenderer font, String text, float x, float y, int color, float a) {
      if (a >= 1F) return font.draw(text, x, y, color);
      if (a <= 0.02F) return (int) x + font.getStringWidth(text);          // below the alpha test anyway
      com.mojang.blaze3d.platform.GlStateManager.enableBlend();
      com.mojang.blaze3d.platform.GlStateManager.blendFuncSeparate(770, 771, 1, 0);
      try {
         return font.draw(text, x, y, lg$fadeText(color, a));
      } finally {
         com.mojang.blaze3d.platform.GlStateManager.disableBlend();      // what DrawableHelper.fill left before the text
      }
   }
}
