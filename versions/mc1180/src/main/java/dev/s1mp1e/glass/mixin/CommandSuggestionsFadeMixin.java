package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.gui.screen.CommandSuggestor;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The command-suggestion popup fades in (rising 3 px) when it appears and fades out when it goes, instead of popping.
 * 1.18.2 port of 26.2's {@code CommandSuggestionsFadeMixin} ({@code CommandSuggestor.tryRenderWindow(MatrixStack, II)}
 * is 26.2's {@code extractSuggestions}; the single {@code window.render(matrices, mouseX, mouseY)} call was verified
 * with javap on the 1.18.2 class).
 *
 * <p>Vanilla clears {@code window} on every keystroke and rebuilds it when the async suggestion lookup completes, so
 * there can be a frame or two with no list while typing. A {@link #S1_GRACE_NS} grace keeps the last list on screen at
 * full opacity through such a gap and treats a list that comes back within it as the same popup (no re-fade) — only a
 * real disappearance fades out, only a real appearance fades in.
 */
@Mixin(CommandSuggestor.class)
public abstract class CommandSuggestionsFadeMixin {

    @Unique private static final long S1_GRACE_NS = 70_000_000L;
    @Unique private static final float S1_OUT_S = 0.12f;
    @Unique private static final float S1_W = 22.0f;

    @Shadow private CommandSuggestor.SuggestionWindow window;

    @Unique private CommandSuggestor.SuggestionWindow s1mp1e$ghost;
    @Unique private boolean s1mp1e$had;
    @Unique private long s1mp1e$gapNs;      // when the list last went null (0 = none)
    @Unique private long s1mp1e$appearNs;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/CommandSuggestor$SuggestionWindow;render(Lnet/minecraft/client/util/math/MatrixStack;II)V"))
    private void s1mp1e$appear(CommandSuggestor.SuggestionWindow list, MatrixStack matrices, int mouseX, int mouseY,
                               Operation<Void> op) {
        long now = System.nanoTime();
        boolean continuous = s1mp1e$had || (s1mp1e$gapNs != 0L && now - s1mp1e$gapNs < S1_GRACE_NS);
        if (!continuous) s1mp1e$appearNs = now;
        s1mp1e$had = true;
        s1mp1e$gapNs = 0L;
        s1mp1e$ghost = list;
        float t = (now - s1mp1e$appearNs) / 1.0e9f;
        float p = s1mp1e$appearNs == 0L ? 1f : 1f - (1f + S1_W * t) * (float) Math.exp(-S1_W * t);
        if (p >= 0.998f) { op.call(list, matrices, mouseX, mouseY); return; }
        float inv = 1f - p;
        GuiAlpha.push(matrices, 1f - inv * inv);
        matrices.push();
        matrices.translate(0f, 3f * inv, 0f);
        try {
            op.call(list, matrices, mouseX, mouseY);
        } finally {
            GuiAlpha.pop(matrices);
            matrices.pop();
        }
    }

    /** No list this frame: hold the last one through the grace period, then fade it out. */
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$ghost(MatrixStack matrices, int mouseX, int mouseY, org.spongepowered.asm.mixin.injection.callback.CallbackInfo cir) {
        if (this.window != null) return;
        long now = System.nanoTime();
        if (s1mp1e$had) { s1mp1e$had = false; s1mp1e$gapNs = now; }
        if (s1mp1e$ghost == null || s1mp1e$gapNs == 0L) return;
        long gap = now - s1mp1e$gapNs;
        float a;
        if (gap < S1_GRACE_NS) {
            a = 1f;
        } else {
            float q = (gap - S1_GRACE_NS) / 1.0e9f / S1_OUT_S;
            if (q >= 1f) { s1mp1e$ghost = null; return; }
            a = (1f - q) * (1f - q);
        }
        GuiAlpha.push(matrices, a);
        try {
            s1mp1e$ghost.render(matrices, -10000, -10000);   // off-screen mouse: the ghost never re-selects
        } finally {
            GuiAlpha.pop(matrices);
        }
    }
}
