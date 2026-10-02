package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chat becomes liquid glass (G1): one refracting frosted panel behind the visible message column
 * plus a soft grey scrim, and the vanilla per-line dark rectangles are dropped so the glass reads
 * through while the text stays legible.
 *
 * <p>1.20.1 path (verified by decompile): {@code ChatHud.render(DrawContext, int currentTick, int
 * mouseX, int mouseY)} — note there is NO {@code boolean focused} parameter here (that arrived in
 * 1.21), so focus is read from the private {@code isChatFocused()} instead. Vanilla pushes a pose
 * scaled by the chat scale then translated by (4, 0), draws each line's dark background with
 * {@code context.fill(-4, x-o, k+8, x, alpha<<24)} (pure black, alpha only) and the text after. The
 * panel is enqueued at HEAD (ctx pose still identity), computed in the exact scaled chat space vanilla
 * uses and BAKED to absolute screen px (the raw-GL glass draws under the RenderSystem model-view, not
 * the ctx pose), so it lines up at any GUI/chat scale. The per-line full-width black rect (x0=-4, wide,
 * RGB 0) is then redirected away when the glass is up; the narrow coloured message-indicator stripe
 * (width 2) and the "N unread" queue bar (x0 == -2) and the scroll bar (x0 &gt; 0) fall through.
 *
 * <p><b>Fade.</b> The panel opacity tracks the newest visible line's fade (vanilla's time curve when
 * unfocused, full when the chat is open), so it fades out with the messages and never lingers as an
 * empty slab. Frame-primary; refracts the world grab from {@code InGameHud.render} HEAD (R4). When the
 * glass pipeline is not usable the panel is skipped and the vanilla rects are left at full strength.
 */
@Mixin(ChatHud.class)
public abstract class ChatGlassMixin {

    @Shadow private int scrolledLines;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;

    @Shadow public abstract int getVisibleLineCount();
    @Shadow public abstract double getChatScale();
    @Shadow private boolean isChatHidden() { return false; }
    @Shadow private boolean isChatFocused() { return false; }
    @Shadow private int getLineHeight() { return 0; }

    private static final int CHAT_BOTTOM_MARGIN = 40;
    private static final float PANEL_ALPHA = 0.82F;
    private static final int PANEL_SCRIM = 0x66101018;
    private static final int TEXT_RIGHT_PAD = 6;

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;III)V", at = @At("HEAD"))
    private void s1mp1e$chatPanel(DrawContext ctx, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (isChatHidden()) return;
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;

        int size = this.visibleMessages.size();
        int shown = size - this.scrolledLines;
        if (shown <= 0 || this.scrolledLines < 0 || this.scrolledLines >= size) return;
        int n = Math.min(this.getVisibleLineCount(), shown);
        if (n <= 0) return;

        boolean focused = this.isChatFocused();
        float fade = focused ? 1.0F
                : lg$lineFade(currentTick - this.visibleMessages.get(this.scrolledLines).addedTime());
        if (fade <= 0.02F) return;

        double scale = this.getChatScale();
        if (!(scale > 0.0)) return;
        int lineHeight = this.getLineHeight();
        int bottomY = MathHelper.floor((ctx.getScaledWindowHeight() - CHAT_BOTTOM_MARGIN) / (float) scale);
        int y0 = bottomY - n * lineHeight;
        int y1 = bottomY;

        // Hug the actual text: width = widest visible line (not the full chat column).
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int textW = 0;
        for (int k = this.scrolledLines; k < this.scrolledLines + n && k < size; k++) {
            textW = Math.max(textW, font.getWidth(this.visibleMessages.get(k).content()));
        }
        if (textW <= 0) return;

        // Local chat-space rect (x0=-4 .. x1=textW+pad), baked to absolute screen px through the chat's
        // own scale + translate(4,0): screen = scale * (local + (4,0)).
        float ax0 = (float) (scale * (-4 + 4));
        float ax1 = (float) (scale * ((textW + TEXT_RIGHT_PAD) + 4));
        // New lines rise in / older lines glide up: ChatArrivalMixin shifts the whole line column down by this much in
        // chat space and lets it ease back. The panel takes the same shift (as on 1.21.1, where it is enqueued inside
        // the shifted chat pose), so it stays glued to the lines it backs.
        dev.s1mp1e.glass.render.ChatArrival.track(this.visibleMessages, this.scrolledLines);
        float slide = dev.s1mp1e.glass.render.ChatArrival.offset(lineHeight);
        float ay0 = (float) (scale * (y0 + slide));
        float ay1 = (float) (scale * (y1 + slide));

        HudGlass.glassBoxCtx(ctx, ax0, ay0, ax1, ay1, PANEL_ALPHA * fade);
        int sa = Math.round((PANEL_SCRIM >>> 24 & 0xFF) * fade) & 0xFF;
        HudGlass.roundFillCtx(ctx, ax0, ay0, ax1, ay1, 0F, sa << 24 | PANEL_SCRIM & 0xFFFFFF);
    }

    /** Vanilla's unfocused per-line fade curve: fully visible for ~180 ticks, then a quick fade by 200. */
    private static float lg$lineFade(int ticksLived) {
        double v = 1.0 - ticksLived / 200.0;
        v = MathHelper.clamp(v * 10.0, 0.0, 1.0);
        return (float) (v * v);
    }

    /**
     * Drop the vanilla full-chat-width per-line dark rectangle when the glass is up (x0=-4, wide, pure
     * black alpha-only). The narrow coloured indicator stripe (x1-x0 == 2) and the "N unread" queue bar
     * (x0 == -2) fall through to the vanilla draw, as does everything when the glass is unusable.
     */
    @Redirect(
        method = "render(Lnet/minecraft/client/gui/DrawContext;III)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
    )
    private void s1mp1e$dropLineBg(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()
                && x0 <= -4 && (x1 - x0) >= 12 && (color & 0xFFFFFF) == 0) {
            return;
        }
        ctx.fill(x0, y0, x1, y1, color);
    }
}
