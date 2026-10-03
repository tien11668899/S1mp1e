package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
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
 * card lands under the text in the same deferred stratum), 1.21.1 is immediate/MatrixStack: {@code ProgressScreen.render}
 * calls {@code super.render} (background) and THEN draws the title/task text. We inject right AFTER that
 * {@code super.render} — flush the batched background, grab a fresh backdrop (R4) and draw the immediate glass card +
 * loader; the vanilla text is drawn afterward through the MatrixStack, which flushes ON TOP of the glass, so the text
 * stays legible above the card. On the {@code done} frame {@code super.render} is not called, so the card never shows.
 *
 * <p>1.20.1 (decompiled {@code ProgressScreen.render}): the order is {@code renderBackground}, the text, and {@code super.render}
 * (the buttons) LAST — so the card goes right AFTER the {@code renderBackground} call (1.21.1 hooks after
 * {@code super.render}, which there comes first and paints the background). Everything in 1.20.1's MatrixStack is
 * drawn as it is issued, so the vanilla text that follows lands on top of the card.
 */
@Mixin(ProgressScreen.class)
public abstract class ProgressScreenGlassMixin {
   @Shadow private String title;
   @Shadow private String task;
   @Shadow private int progress;

   /** 1.13.2 world entry: {@code System.nanoTime()} of the first loop frame on this screen. */
   @org.spongepowered.asm.mixin.Unique private long s1mp1e$loopStart;

   /**
    * World entry (1.13.2). There is no {@code LevelLoadingScreen} yet: singleplayer world loading shows this screen
    * (opened by {@code startIntegratedServer}, see {@code BootIntroMixin}) and it stays up until the client has joined.
    * On that one instance the vanilla dirt / text is replaced by the seamless brand loop on pure black, exactly as the
    * 1.14+ {@code LevelLoadingScreenGlassMixin} does; any other progress screen keeps the glass status card below.
    */
   @Inject(method = "render", at = @At("HEAD"), cancellable = true)
   private void s1mp1e$worldEntryLoop(int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if ((Object) this != dev.s1mp1e.client.gui.BrandIntro.worldEntryScreen) {
         return;
      }
      if (!dev.s1mp1e.client.gui.BrandIntro.ready(dev.s1mp1e.client.gui.BrandIntro.MODE_LOOP)) {
         dev.s1mp1e.client.gui.BrandIntro.worldEntryLoopShown = false;
         return;   // pipeline/strip not up - leave vanilla (+ the status card) alone
      }
      long now = System.nanoTime();
      if (this.s1mp1e$loopStart == 0L) {
         this.s1mp1e$loopStart = now;
         dev.s1mp1e.client.gui.BrandIntro.worldEntryFrames = 0;
         dev.s1mp1e.client.gui.BrandIntro.worldEntryFirstNs = now;
      }
      dev.s1mp1e.client.gui.BrandIntro.worldEntryFrames++;
      dev.s1mp1e.client.gui.BrandIntro.worldEntryLastNs = now;
      Screen self = (Screen) (Object) this;
      net.minecraft.client.gui.DrawableHelper.fill(0, 0, self.width, self.height, 0xFF000000);
      dev.s1mp1e.glass.render.GuiFlush.flush();
      dev.s1mp1e.client.gui.BrandIntro.draw((float) ((now - this.s1mp1e$loopStart) / 1.0E9),
            dev.s1mp1e.client.gui.BrandIntro.MODE_LOOP, 1.0F);
      dev.s1mp1e.client.gui.BrandIntro.worldEntryLoopShown = true;
      ci.cancel();
   }

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screen/ProgressScreen;renderBackground()V",
         shift = At.Shift.AFTER))
   private void s1mp1e$statusCard(int mouseX, int mouseY, float delta, CallbackInfo ci) {
      MinecraftClient mc = MinecraftClient.getInstance();
      TextRenderer font = mc.textRenderer;
      if (font == null || !GlassProgram.ensureReady() || !GlassProgram.usable()) {
         return;
      }
      Screen self = (Screen) (Object) this;
      boolean taskLine = this.task != null && this.progress != 0;
      int textW = this.title == null ? 0 : font.getStringWidth(this.title);
      if (taskLine) {
         textW = Math.max(textW, font.getStringWidth(this.task + " " + this.progress + "%"));
      }
      float cx = self.width / 2.0F;
      float textBottom = (taskLine ? 90.0F : 70.0F) + LoadingCard.TEXT_H;
      float loaderTop = textBottom + LoadingCard.GAP;
      float w = Math.max(textW, LiquidLoader.TRACK_W);

      dev.s1mp1e.glass.render.GuiFlush.flush();               // flush the batched background so the card refracts it, not an empty buffer
      SceneCapture.grabNow();   // frame-primary backdrop (R4)
      LoadingCard.box(cx - w / 2.0F, 70.0F, cx + w / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F, taskLine ? this.progress / 100.0F : -1.0F);
   }
}
