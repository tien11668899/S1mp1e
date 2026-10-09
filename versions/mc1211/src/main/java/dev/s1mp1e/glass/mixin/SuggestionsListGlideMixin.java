package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.util.math.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command-suggestion popup: a soft highlight bar glides to the selected suggestion (Tab / arrows / hover) and each
 * row's text colour follows it, easing between vanilla's grey and yellow instead of flipping. 1.21.1 port of 26.2's
 * {@code SuggestionsListGlideMixin}.
 *
 * <p>The popup paints row background then row text, one row at a time, so a single bar drawn once would be covered by
 * later rows' backgrounds: instead each 12 px row fill is followed by the part of the (moving) bar that overlaps that
 * row — a bar straddling two rows is drawn as two pieces that meet exactly. Verified with javap on
 * {@code SuggestionWindow.render}: five {@code fill(IIIII)} sites (four 1 px "more above / below" marks and the 12 px
 * row background) and one {@code drawTextWithShadow(TextRenderer, String, III)}; {@code inWindowIndex} is the first
 * visible row, {@code selection} the selected suggestion.
 */
@Mixin(ChatInputSuggestor.SuggestionWindow.class)
public abstract class SuggestionsListGlideMixin {

    @Unique private static final float S1_TAU = 0.06f;
    @Unique private static final int S1_ROW = 12;
    @Unique private static final int S1_BAR = 0x26FFFFFF;
    @Unique private static final int S1_GREY = 0xFFAAAAAA;
    @Unique private static final int S1_YELLOW = 0xFFFFFF00;

    @Shadow @Final private Rect2i area;
    @Shadow private int inWindowIndex;
    @Shadow private int selection;

    @Unique private float s1mp1e$sel = Float.NaN;   // eased selected index (list space)
    @Unique private long s1mp1e$ns;

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$easeSelection(DrawContext ctx, int mouseX, int mouseY, CallbackInfo ci) {
        long now = System.nanoTime();
        if (Float.isNaN(s1mp1e$sel)) {
            s1mp1e$sel = this.selection;
        } else {
            float dt = s1mp1e$ns == 0L ? 1f / 60f : Math.min(0.05f, (now - s1mp1e$ns) / 1.0e9f);
            s1mp1e$sel += (this.selection - s1mp1e$sel) * (1f - (float) Math.exp(-dt / S1_TAU));
            if (Math.abs(this.selection - s1mp1e$sel) < 0.01f) s1mp1e$sel = this.selection;
        }
        s1mp1e$ns = now;
        // All-glass: one glass panel for the whole popup + the gliding selection as a glass capsule (spec #13)
        int x0 = this.area.getX() - 1, y0 = this.area.getY() - 1;
        int x1 = this.area.getX() + this.area.getWidth() + 1, y1 = this.area.getY() + this.area.getHeight() + 1;
        dev.s1mp1e.client.gui.AllGlass.plate(ctx, x0, y0, x1, y1, 1f, 0x78000000);
        float barTop = this.area.getY() + S1_ROW * (s1mp1e$sel - this.inWindowIndex);
        float top = Math.max(this.area.getY(), barTop), bottom = Math.min(this.area.getY() + this.area.getHeight(), barTop + S1_ROW);
        if (bottom - top > 1f) {
            dev.s1mp1e.client.gui.AllGlass.capsule(ctx, this.area.getX(), top, this.area.getX() + this.area.getWidth(), bottom,
                    dev.s1mp1e.client.gui.AllGlass.hotbarCorner(this.area.getWidth(), S1_ROW), 0.81f, 1f);
        }
        s1mp1e$glass = true;
    }

    @Unique private boolean s1mp1e$glass;

    /** After each 12 px row background, the slice of the gliding bar that lies over that row. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$rowFillThenBar(DrawContext ctx, int x0, int y0, int x1, int y1, int color, Operation<Void> op) {
        if (s1mp1e$glass) return;   // rows, dotted marks and the bar are all the glass panel + capsule drawn at HEAD
        op.call(ctx, x0, y0, x1, y1, color);
        if (y1 - y0 != S1_ROW || Float.isNaN(s1mp1e$sel)) return;   // only the row backgrounds (the marks are 1 px)
        float barTop = this.area.getY() + S1_ROW * (s1mp1e$sel - this.inWindowIndex);
        int top = Math.max(y0, Math.round(barTop));
        int bottom = Math.min(y1, Math.round(barTop) + S1_ROW);
        if (bottom > top) ctx.fill(x0, top, x1, bottom, S1_BAR);
    }

    /** Row text colour: grey → yellow by how close the gliding highlight is to this row. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I"))
    private int s1mp1e$rowText(DrawContext ctx, TextRenderer font, String text, int x, int y, int color,
                               Operation<Integer> op) {
        if (Float.isNaN(s1mp1e$sel)) return op.call(ctx, font, text, x, y, color);
        int k = (y - 2 - this.area.getY()) / S1_ROW;
        float w = 1f - Math.abs(k + this.inWindowIndex - s1mp1e$sel);
        w = w < 0f ? 0f : (w > 1f ? 1f : w);
        return op.call(ctx, font, text, x, y, s1mp1e$glass ? s1mp1e$lerp(0xFFE0E0E0, 0xFFFFFFFF, w) : s1mp1e$lerp(S1_GREY, S1_YELLOW, w));
    }

    @Unique
    private static int s1mp1e$lerp(int a, int b, float t) {
        int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8
                | Math.round(ab + (bb - ab) * t);
    }
}
