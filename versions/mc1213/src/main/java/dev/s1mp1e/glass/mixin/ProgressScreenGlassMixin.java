package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Progress screen (level conversion / save): a glass status card around the title (y 70), the task/percent line (y 90 —
 * vanilla draws it only when there is a task and the progress is non-zero) and a {@link LiquidLoader} one
 * {@link LoadingCard#GAP} below the text: determinate at the percent, indeterminate while it is still 0.
 *
 * <p>1.21.1 port of 26.2's {@code ProgressScreenGlassMixin}. Where 26.2 drew at {@code extractRenderState} HEAD (the
 * card lands under the text in the same deferred stratum), 1.21.1 is immediate/DrawContext: {@code ProgressScreen.render}
 * calls {@code super.render} (background) and THEN draws the title/task text. We inject right AFTER that
 * {@code super.render} — flush the batched background, grab a fresh backdrop (R4) and draw the immediate glass card +
 * loader; the vanilla text is drawn afterward through the DrawContext, which flushes ON TOP of the glass, so the text
 * stays legible above the card. On the {@code done} frame {@code super.render} is not called, so the card never shows.
 */
@Mixin(ProgressScreen.class)
public abstract class ProgressScreenGlassMixin {
   @Shadow private Text title;
   @Shadow private Text task;
   @Shadow private int progress;

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V",
         shift = At.Shift.AFTER))
   private void s1mp1e$statusCard(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      MinecraftClient mc = MinecraftClient.getInstance();
      TextRenderer font = mc.textRenderer;
      if (font == null || !GlassProgram.ensureReady() || !GlassProgram.usable()) {
         return;
      }
      Screen self = (Screen) (Object) this;
      boolean taskLine = this.task != null && this.progress != 0;
      int textW = this.title == null ? 0 : font.getWidth(this.title);
      if (taskLine) {
         textW = Math.max(textW, font.getWidth(Text.empty().append(this.task).append(" " + this.progress + "%")));
      }
      float cx = self.width / 2.0F;
      float textBottom = (taskLine ? 90.0F : 70.0F) + LoadingCard.TEXT_H;
      float loaderTop = textBottom + LoadingCard.GAP;
      float w = Math.max(textW, LiquidLoader.TRACK_W);

      ctx.draw();               // flush the batched background so the card refracts it, not an empty buffer
      SceneCapture.grabNow();   // frame-primary backdrop (R4)
      LoadingCard.box(ctx, cx - w / 2.0F, 70.0F, cx + w / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(ctx, this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F, taskLine ? this.progress / 100.0F : -1.0F);
   }
}
