package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConnectScreen;   // 1.20.1 package
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Connecting screen: a glass field_20238 card around the field_20238 line (centred at height/2 - 50) and an indeterminate
 * {@link LiquidLoader} one {@link LoadingCard#GAP} below it. The cancel button stays its own glass button, well clear
 * of the card.
 *
 * <p>1.21.1 port of 26.2's {@code ConnectScreenGlassMixin}. {@code ConnectScreen.render} calls {@code super.render}
 * (background) and then draws the field_20238 text; we inject right AFTER {@code super.render} to flush + grab + draw the
 * card and loader under that text (see {@link ProgressScreenGlassMixin} for the layering rationale). The field_20238 field
 * is written from the network thread; we only read it.
 *
 * <p>1.20.1 (decompiled {@code ConnectScreen.render}): the order is {@code renderBackground}, the text, and {@code super.render}
 * (the buttons) LAST — so the card goes right AFTER the {@code renderBackground} call (1.21.1 hooks after
 * {@code super.render}, which there comes first and paints the background). Everything in 1.20.1's MatrixStack is
 * drawn as it is issued, so the vanilla text that follows lands on top of the card.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenGlassMixin {
   @Shadow private Text field_20238;

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screen/ConnectScreen;renderBackground()V",
         shift = At.Shift.AFTER))
   private void s1mp1e$statusCard(int mouseX, int mouseY, float delta, CallbackInfo ci) {
      MinecraftClient mc = MinecraftClient.getInstance();
      TextRenderer font = mc.textRenderer;
      Text s = this.field_20238;
      if (font == null || s == null || !GlassProgram.ensureReady() || !GlassProgram.usable()) {
         return;
      }
      Screen self = (Screen) (Object) this;
      float cx = self.width / 2.0F;
      float y = self.height / 2 - 50;
      float loaderTop = y + LoadingCard.TEXT_H + LoadingCard.GAP;
      float w = Math.max(font.getStringWidth(s.asFormattedString()), LiquidLoader.TRACK_W);

      dev.s1mp1e.glass.render.GuiFlush.flush();               // flush the batched background so the card refracts it, not an empty buffer
      SceneCapture.grabNow();   // frame-primary backdrop (R4)
      LoadingCard.box(cx - w / 2.0F, y, cx + w / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F, -1.0F);
   }
}
