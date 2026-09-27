package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
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
 * widest visible line, and the vanilla per-line dark rectangles are dropped so the glass reads through while the text
 * stays legible over a soft grey scrim.
 *
 * <p><b>1.17.1 [MatrixStack] port of 26.2's ChatGlassMixin.</b> {@code ChatHud.render(MatrixStack, int)} runs its lines
 * under {@code translate(4,8) + scale(chatScale)} (verified: the sole {@code MatrixStack.scale(FFF)} call), stacking the
 * visible lines upward from a local {@code y = 0} baseline (each per-line dark rect is
 * {@code fill(matrices, -4, s - lineHeight, width + 4, s, black<<24)}). The panel is injected right AFTER that
 * {@code scale}, so the passed {@link MatrixStack} already carries the full chat transform;
 * {@link HudGlass#glassBoxLocalHotbar} projects the panel's local rect (x0=-4 .. maxW+pad, y0=-shown*lineHeight .. 0)
 * through that pose to absolute screen px (so the raw-GL glass, drawn under the RS model-view at the GUI base, lines up
 * with the text) with the hotbar corner (R2). The panel opacity tracks the newest visible line's vanilla fade curve
 * (full when focused), so it fades out with the messages and never lingers as an empty slab. The per-line background
 * {@code fill} (the first {@code ChatHud.fill}, ordinal 0) is redirected to nothing while the glass is up. When the
 * glass pipeline is unusable the panel is skipped and the vanilla rects are left intact, so chat is never worse than
 * vanilla.
 *
 * <p>1.17.1 API deltas from the 1.19.2 sibling (javap-verified): {@code visibleMessages} is
 * {@code List<ChatHudLine<OrderedText>>} (not {@code ChatHudLine.Visible}); {@code ChatHudLine.getText()} /
 * {@code getCreationTick()} replace {@code content()} / {@code addedTime()}; there is NO {@code getLineHeight()} so the
 * line height is computed as vanilla does ({@code 9 * (chatLineSpacing + 1)}); the per-line fill INVOKE resolves to
 * {@code ChatHud.fill} (the static helper is emitted against the current class).
 */
@Mixin(ChatHud.class)
public abstract class ChatGlassMixin {

    @Shadow @Final private List<ChatHudLine<OrderedText>> visibleMessages;
    @Shadow private int scrolledLines;
    @Shadow public abstract int getVisibleLineCount();
    @Shadow private boolean isChatFocused() { return false; }

    /** Base frosted-panel opacity before the message-fade multiplier. */
    private static final float PANEL_ALPHA = 0.82F;
    /** Grey readability scrim laid over the frosted panel, before the text. */
    private static final int PANEL_SCRIM = 0x66101018;
    /** Right padding past the widest visible line, so the panel hugs the text. */
    private static final int TEXT_RIGHT_PAD = 6;

    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;I)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V",
                     shift = At.Shift.AFTER))
    private void s1mp1e$chatPanel(MatrixStack matrices, int currentTick, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        int size = this.visibleMessages.size();
        int avail = size - this.scrolledLines;
        if (avail <= 0 || this.scrolledLines < 0 || this.scrolledLines >= size) return;
        int shown = Math.min(this.getVisibleLineCount(), avail);
        if (shown <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean focused = this.isChatFocused();
        ChatHudLine<OrderedText> newest = this.visibleMessages.get(this.scrolledLines);
        float fade = focused ? 1.0F : s1mp1e$lineFade(currentTick - newest.getCreationTick());
        if (fade <= 0.02F) return;

        // 1.17.1 chat line height (no getLineHeight()): vanilla uses 9 * (chatLineSpacing + 1).
        int l = (int) (9.0 * (mc.options.chatLineSpacing + 1.0));
        TextRenderer font = mc.textRenderer;
        int maxW = 0;
        for (int n = 0; n < shown && (n + this.scrolledLines) < size; n++) {
            ChatHudLine<OrderedText> v = this.visibleMessages.get(n + this.scrolledLines);
            if (v != null) maxW = Math.max(maxW, font.getWidth(v.getText()));
        }
        if (maxW <= 0) return;

        int x0 = -4, x1 = maxW + TEXT_RIGHT_PAD;
        int y0 = -shown * l, y1 = 0;
        HudGlass.glassBoxLocalHotbar(matrices, x0, y0, x1, y1, PANEL_ALPHA * fade);
        int sa = Math.round((PANEL_SCRIM >>> 24 & 0xFF) * fade) & 0xFF;
        DrawableHelper.fill(matrices, x0, y0, x1, y1, (sa << 24) | (PANEL_SCRIM & 0xFFFFFF));
    }

    /** Drop the vanilla full-width per-line dark rectangle when the glass panel is up (glass backs the text instead). */
    @Redirect(method = "render(Lnet/minecraft/client/util/math/MatrixStack;I)V",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/gui/hud/ChatHud;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$dropLineBg(MatrixStack matrices, int x0, int y0, int x1, int y1, int color) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            DrawableHelper.fill(matrices, x0, y0, x1, y1, color);
        }
    }

    /** Vanilla's unfocused per-line fade curve: fully visible for ~180 ticks, then a quick quadratic fade by 200. */
    private static float s1mp1e$lineFade(int ticksLived) {
        double v = 1.0 - ticksLived / 200.0;
        v = MathHelper.clamp(v * 10.0, 0.0, 1.0);
        return (float) (v * v);
    }
}
