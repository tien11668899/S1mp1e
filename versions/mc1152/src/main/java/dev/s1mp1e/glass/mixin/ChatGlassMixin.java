package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chat becomes liquid glass (G1): ONE refracting frosted panel sits behind the visible message column, hugging the
 * widest visible line, with a soft grey readability scrim, and the vanilla per-line dark rectangles are dropped so the
 * glass reads through while the text stays legible.
 *
 * <p><b>1.15.2 [FF-Fabric] port of the 1.16.5 ChatGlassMixin.</b> 1.15.2's {@code ChatHud.render(int)} scales the
 * GLOBAL GL model-view with {@code RenderSystem.pushMatrix(); translatef(2,8,0); scaled(chatScale,chatScale,1)}
 * (javap-verified — the per-line rects are drawn from a local {@code y = 0} baseline going up, each being
 * {@code fill(matrix4f, -2, s-9, k+4, s, bgColor)} with {@code k = ceil(getWidth()/chatScale)}; the fixed line height is
 * 9 — 1.15.2 has no {@code chatLineSpacing}). The panel is injected right AFTER that {@code RenderSystem.scaled}, so the
 * active GL model-view already carries the full chat transform. {@link HudGlass#glassBoxHotbar} draws the glass at LOCAL
 * coords straight under that model-view (raw {@code GlassRenderer} quads, like the glass hotbar), so it lands on the
 * lines, and the grey scrim (a static {@code DrawableHelper.fill} that also rides the SAME GL model-view) lands there
 * too. The panel opacity tracks the newest visible line's vanilla fade curve (full when the chat is focused). The first
 * per-line {@code ChatHud.fill(Matrix4f, ...)} (ordinal 0) is redirected to nothing while the glass is up. When the
 * glass pipeline is unusable the panel is skipped and the vanilla rects are left intact, so chat is never worse than
 * vanilla.
 */
@Mixin(ChatHud.class)
public abstract class ChatGlassMixin {

    @Shadow @Final private List<ChatHudLine> visibleMessages;
    @Shadow private int scrolledLines;
    @Shadow public abstract int getVisibleLineCount();
    @Shadow public abstract double getChatScale();
    @Shadow public abstract int getWidth();
    @Shadow public abstract boolean isChatFocused();

    /** Base frosted-panel opacity before the message-fade multiplier. */
    private static final float PANEL_ALPHA = 0.82F;
    /** Grey readability scrim laid over the frosted panel, before the text. */
    private static final int PANEL_SCRIM = 0x66101018;
    /** Right padding past the widest visible line, so the panel hugs the text. */
    private static final int TEXT_RIGHT_PAD = 6;
    /** 1.15.2 fixed chat line height (no chatLineSpacing option before 1.16). */
    private static final int LINE_H = 9;

    @Inject(method = "render(I)V",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/systems/RenderSystem;scaled(DDD)V",
                     shift = At.Shift.AFTER))
    private void s1mp1e$chatPanel(int currentTick, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        int size = this.visibleMessages.size();
        if (size <= 0 || this.scrolledLines < 0 || this.scrolledLines >= size) return;
        int avail = size - this.scrolledLines;
        int shown = Math.min(this.getVisibleLineCount(), avail);
        if (shown <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean focused = this.isChatFocused();
        ChatHudLine newest = this.visibleMessages.get(this.scrolledLines);
        float fade = focused ? 1.0F : s1mp1e$lineFade(currentTick - newest.getCreationTick());
        if (fade <= 0.02F) return;

        double scale = this.getChatScale();
        int k = scale > 0 ? MathHelper.ceil(this.getWidth() / scale) : this.getWidth();
        TextRenderer font = mc.textRenderer;
        int maxW = 0;
        for (int n = 0; n < shown && (n + this.scrolledLines) < size; n++) {
            ChatHudLine v = this.visibleMessages.get(n + this.scrolledLines);
            if (v != null) maxW = Math.max(maxW, font.getStringWidth(v.getText().asFormattedString()));
        }
        if (maxW <= 0) return;

        int x0 = -2, x1 = Math.min(k + 4, maxW + TEXT_RIGHT_PAD);
        int y0 = -shown * LINE_H, y1 = 0;
        HudGlass.glassBoxHotbar(x0, y0, x1, y1, PANEL_ALPHA * fade);
        int sa = Math.round((PANEL_SCRIM >>> 24 & 0xFF) * fade) & 0xFF;
        DrawableHelper.fill(x0, y0, x1, y1, (sa << 24) | (PANEL_SCRIM & 0xFFFFFF));
    }

    /** Drop the vanilla full-width per-line dark rectangle when the glass panel is up (glass backs the text instead). */
    @Redirect(method = "render(I)V",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/gui/hud/ChatHud;fill(Lnet/minecraft/client/util/math/Matrix4f;IIIII)V"))
    private void s1mp1e$dropLineBg(Matrix4f matrix, int x0, int y0, int x1, int y1, int color) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            DrawableHelper.fill(matrix, x0, y0, x1, y1, color);
        }
    }

    /** Vanilla's unfocused per-line fade curve: fully visible for ~180 ticks, then a quick quadratic fade by 200. */
    private static float s1mp1e$lineFade(int ticksLived) {
        double v = 1.0 - ticksLived / 200.0;
        v = MathHelper.clamp(v * 10.0, 0.0, 1.0);
        return (float) (v * v);
    }
}
