package dev.s1mp1e.glass.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #15 — F3 debug text: a grey box behind every line ({@code fill(..., 0x90505050)}) → one glass card (plate + grey
 * scrim) per group of consecutive lines (groups split at vanilla's blank lines), sized to the widest line. The per-line
 * boxes are dropped while the cards draw. 1.19.2 has no {@code drawText(List,boolean)}: the lines are drawn by
 * {@code renderLeftText(MatrixStack)} / {@code renderRightText(MatrixStack)} (left side y=2+9*i at x=1; right side right-
 * aligned). The metrics-chart fills ({@code drawMetricsData}) are a different method and untouched.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudGlassMixin {

    @Shadow protected abstract List<String> getLeftText();
    @Shadow protected abstract List<String> getRightText();

    @Unique private boolean s1mp1e$cards;

    @Inject(method = "renderLeftText", at = @At("HEAD"))
    private void s1mp1e$leftCards(MatrixStack matrices, CallbackInfo ci) {
        s1mp1e$cards = s1mp1e$drawCards(matrices, getLeftText(), true);
    }

    @Inject(method = "renderRightText", at = @At("HEAD"))
    private void s1mp1e$rightCards(MatrixStack matrices, CallbackInfo ci) {
        s1mp1e$cards = s1mp1e$drawCards(matrices, getRightText(), false);
    }

    @WrapOperation(method = {"renderLeftText", "renderRightText"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/DebugHud;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$noLineBoxes(MatrixStack matrices, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (!this.s1mp1e$cards) original.call(matrices, x0, y0, x1, y1, argb);
    }

    @Unique
    private boolean s1mp1e$drawCards(MatrixStack matrices, List<String> lines, boolean left) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer font = mc.textRenderer;
        int lh = 9, gw = mc.getWindow().getScaledWidth();
        int i = 0, n = lines.size();
        boolean any = false;
        while (i < n) {
            if (lines.get(i) == null || lines.get(i).isEmpty()) { i++; continue; }
            int start = i, w = 0;
            while (i < n && lines.get(i) != null && !lines.get(i).isEmpty()) { w = Math.max(w, font.getWidth(lines.get(i))); i++; }
            int y0 = 2 + lh * start - 2, y1 = 2 + lh * i;
            int x0 = left ? 0 : gw - 4 - w, x1 = left ? w + 4 : gw;
            AllGlass.plate(matrices, x0, y0, x1, y1, 1f, 0x60000000);
            any = true;
        }
        return any;
    }
}
