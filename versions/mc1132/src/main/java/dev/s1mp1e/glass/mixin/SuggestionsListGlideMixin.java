package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.class_4228;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command-suggestion popup: a soft highlight bar glides to the selected suggestion (Tab / arrows / hover) and each
 * row's text colour follows it, easing between vanilla's grey and yellow instead of flipping. 1.13.2 port of 26.2's
 * {@code SuggestionsListGlideMixin}.
 *
 * <p>The popup paints row background then row text, one row at a time, so a single bar drawn once would be covered by
 * later rows' backgrounds: instead each 12 px row fill is followed by the part of the (moving) bar that overlaps that
 * row — a bar straddling two rows is drawn as two pieces that meet exactly. Verified with javap on the 1.13.2
 * {@code SuggestionWindow.render}: five {@code DrawableHelper.fill(MatrixStack, IIIII)} sites (four 1 px "more above /
 * below" marks and the 12 px row background) and one {@code TextRenderer.drawWithShadow(MatrixStack, String, FFI)};
 * {@code field_20229} is the first visible row, {@code field_20230} the selected suggestion.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ChatScreen$class_4155")
public abstract class SuggestionsListGlideMixin implements dev.s1mp1e.glass.render.SuggestionsFade.Window {

    @Unique private static final float S1_TAU = 0.06f;
    @Unique private static final int S1_ROW = 12;
    @Unique private static final int S1_BAR = 0x26FFFFFF;
    @Unique private static final int S1_GREY = 0xFFAAAAAA;
    @Unique private static final int S1_YELLOW = 0xFFFFFF00;

    @Shadow @Final private class_4228 field_20226;
    @Shadow private int field_20229;
    @Shadow private int field_20230;

    @Unique private float s1mp1e$sel = Float.NaN;   // eased selected index (list space)
    @Unique private long s1mp1e$ns;

    // ---- 1.13.2: the appear / ghost fade of the whole list (see SuggestionsFade; the 1.16 lines hook CommandSuggestor) ----

    @Shadow public abstract void method_18549(int mouseX, int mouseY);

    @Unique private boolean s1mp1e$fadeOpen;

    @Override
    public void s1mp1e$drawWindow(int mouseX, int mouseY) {
        this.method_18549(mouseX, mouseY);
    }

    @Inject(method = "method_18549", at = @At("HEAD"))
    private void s1mp1e$fadeIn(int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$fadeOpen = dev.s1mp1e.glass.render.SuggestionsFade.enter(this);
    }

    @Inject(method = "method_18549", at = @At("RETURN"))
    private void s1mp1e$fadeOut(int mouseX, int mouseY, CallbackInfo ci) {
        dev.s1mp1e.glass.render.SuggestionsFade.leave(s1mp1e$fadeOpen);
        s1mp1e$fadeOpen = false;
    }

    @Inject(method = "method_18549", at = @At("HEAD"))
    private void s1mp1e$easeSelection(int mouseX, int mouseY, CallbackInfo ci) {
        long now = System.nanoTime();
        if (Float.isNaN(s1mp1e$sel)) {
            s1mp1e$sel = this.field_20230;
        } else {
            float dt = s1mp1e$ns == 0L ? 1f / 60f : Math.min(0.05f, (now - s1mp1e$ns) / 1.0e9f);
            s1mp1e$sel += (this.field_20230 - s1mp1e$sel) * (1f - (float) Math.exp(-dt / S1_TAU));
            if (Math.abs(this.field_20230 - s1mp1e$sel) < 0.01f) s1mp1e$sel = this.field_20230;
        }
        s1mp1e$ns = now;
    }

    /** After each 12 px row background, the slice of the gliding bar that lies over that row. */
    @WrapOperation(method = "method_18549", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;fill(IIIII)V"))
    private void s1mp1e$rowFillThenBar(int x0, int y0, int x1, int y1, int color,
                                       Operation<Void> op) {
        op.call(x0, y0, x1, y1, color);
        if (y1 - y0 != S1_ROW || Float.isNaN(s1mp1e$sel)) return;   // only the row backgrounds (the marks are 1 px)
        float barTop = this.field_20226.method_19178() + S1_ROW * (s1mp1e$sel - this.field_20229);
        int top = Math.max(y0, Math.round(barTop));
        int bottom = Math.min(y1, Math.round(barTop) + S1_ROW);
        if (bottom > top) DrawableHelper.fill(x0, top, x1, bottom, S1_BAR);
    }

    /** Row text colour: grey → yellow by how close the gliding highlight is to this row. */
    @WrapOperation(method = "method_18549", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"))
    private int s1mp1e$rowText(TextRenderer font, String text, float x, float y, int color,
                               Operation<Integer> op) {
        if (Float.isNaN(s1mp1e$sel)) return op.call(font, text, x, y, color);
        int k = (Math.round(y) - 2 - this.field_20226.method_19178()) / S1_ROW;
        float w = 1f - Math.abs(k + this.field_20229 - s1mp1e$sel);
        w = w < 0f ? 0f : (w > 1f ? 1f : w);
        return op.call(font, text, x, y, s1mp1e$lerp(S1_GREY, S1_YELLOW, w));
    }

    @Unique
    private static int s1mp1e$lerp(int a, int b, float t) {
        int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8
                | Math.round(ab + (bb - ab) * t);
    }
}
