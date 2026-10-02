package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TaskScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waiting screen ({@code TaskScreen} — Mojang's {@code GenericWaitingScreen}). 1.21.1 port of 26.2's
 * {@code GenericWaitingScreenGlassMixin}.
 *
 * <p>Vanilla 1.21.1 (verified with javap on {@code render}): {@code super.render}, the title centred at y 80, then
 * EITHER the "o O o" loading dots at y 120 (while there is no description yet) OR the wrapped description at y 120 — a
 * 31 px hole under the title. Here the rows are stacked with the one {@link LoadingCard#GAP}: title → GAP →
 * indeterminate {@link LiquidLoader} (replacing the dots), or title → GAP → description, inside one glass status card
 * with the uniform {@link LoadingCard} padding. The card is drawn right AFTER {@code super.render} (flush + fresh
 * backdrop, then immediate glass); the vanilla text is drawn afterwards through the DrawContext and so lands on top.
 *
 * <p>1.20.1 (decompiled {@code TaskScreen.render}): the order is {@code renderBackground}, the text, and {@code super.render}
 * (the buttons) LAST — so the card goes right AFTER the {@code renderBackground} call (1.21.1 hooks after
 * {@code super.render}, which there comes first and paints the background). Everything in 1.20.1's DrawContext is
 * drawn as it is issued, so the vanilla text that follows lands on top of the card.
 */
@Mixin(TaskScreen.class)
public abstract class TaskScreenGlassMixin {

    @Unique private static final float TITLE_Y = 80.0f;

    @Shadow private MultilineText description;

    @Unique
    private static boolean s1mp1e$glass() {
        return GlassProgram.ensureReady() && GlassProgram.usable();
    }

    /** Top of the row under the title (the loader, or the first description line). */
    @Unique
    private static float s1mp1e$rowTop() {
        return TITLE_Y + LoadingCard.TEXT_H + LoadingCard.GAP;
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/TaskScreen;renderBackground(Lnet/minecraft/client/gui/DrawContext;)V",
            shift = At.Shift.AFTER))
    private void s1mp1e$statusCard(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer font = mc.textRenderer;
        if (font == null || !s1mp1e$glass()) return;
        Screen self = (Screen) (Object) this;
        Text title = self.getTitle();
        float maxW = title == null ? 0.0f : font.getWidth(title);
        float bottom;
        boolean waiting = this.description == null;
        if (waiting) {
            maxW = Math.max(maxW, LiquidLoader.TRACK_W);
            bottom = s1mp1e$rowTop() + LiquidLoader.ROW_H;
        } else {
            int lines = this.description.count();
            maxW = Math.max(maxW, this.description.getMaxWidth());
            bottom = lines > 0 ? s1mp1e$rowTop() + lines * LoadingCard.TEXT_H : TITLE_Y + LoadingCard.TEXT_H;
        }
        if (maxW <= 0.0f) return;
        float cx = self.width / 2.0f;

        ctx.draw();               // flush the batched background so the card refracts it, not an empty buffer
        SceneCapture.grabNow();   // frame-primary backdrop (R4)
        LoadingCard.box(ctx, cx - maxW / 2.0f, TITLE_Y, cx + maxW / 2.0f, bottom);
        if (waiting) LiquidLoader.draw(ctx, this, cx, s1mp1e$rowTop() + LiquidLoader.ROW_H / 2.0f, -1.0f);
    }

    /** The "o O o" dots: replaced by the liquid loader drawn above (kept when the glass is unavailable). */
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V"))
    private void s1mp1e$noDots(DrawContext ctx, TextRenderer font, String text, int x, int y, int color) {
        if (!s1mp1e$glass()) ctx.drawCenteredTextWithShadow(font, text, x, y, color);
    }

    /** Description on the card's row grid: one GAP under the title instead of vanilla's y 120. */
    @ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/font/MultilineText;drawCenterWithShadow(Lnet/minecraft/client/gui/DrawContext;II)I"),   // returns int in 1.20.1
            index = 2)
    private int s1mp1e$descriptionY(int y) {
        return s1mp1e$glass() ? (int) s1mp1e$rowTop() : y;
    }
}
