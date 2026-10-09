package dev.s1mp1e.glass.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #15 — F3 debug text: a grey box behind every line ({@code fill(..., 0x90505050)}) → one glass card (plate + grey
 * scrim) per group of consecutive lines (groups split at vanilla's blank lines), sized to the widest line. The per-line
 * boxes are dropped while the cards draw.
 *
 * <p>1.15.2 (javap-verified): {@code renderLeftText()V} fetches {@code getLeftText()} itself and then APPENDS three more
 * lines ("", "Debug: Pie …", "For help: press F3 + Q") before its loop, so the list is read from the method's own local
 * at the loop's first {@code List.size()} (fires every iteration — guarded to once per call), not by calling
 * {@code getLeftText()} again. {@code renderRightText()V} the same. Lines: left at y = 2 + 9*i, right right-aligned; the
 * box fills are the inherited static {@code DebugHud.fill(IIIII)V}. The metrics-chart fills ({@code drawMetricsData})
 * are a different method and untouched.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudGlassMixin {

    @Unique private boolean s1mp1e$cards;
    @Unique private boolean s1mp1e$measured;

    @Inject(method = {"renderLeftText()V", "renderRightText()V"}, at = @At("HEAD"))
    private void s1mp1e$reset(CallbackInfo ci) {
        this.s1mp1e$measured = false;
        this.s1mp1e$cards = false;
    }

    @Inject(method = "renderLeftText()V", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private void s1mp1e$leftCards(CallbackInfo ci, @Local(ordinal = 0) List<String> lines) {
        if (this.s1mp1e$measured) return;
        this.s1mp1e$measured = true;
        this.s1mp1e$cards = s1mp1e$drawCards(lines, true);
    }

    @Inject(method = "renderRightText()V", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private void s1mp1e$rightCards(CallbackInfo ci, @Local(ordinal = 0) List<String> lines) {
        if (this.s1mp1e$measured) return;
        this.s1mp1e$measured = true;
        this.s1mp1e$cards = s1mp1e$drawCards(lines, false);
    }

    @WrapOperation(method = {"renderLeftText()V", "renderRightText()V"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/DebugHud;fill(IIIII)V"))
    private void s1mp1e$noLineBoxes(int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (!this.s1mp1e$cards) original.call(x0, y0, x1, y1, argb);
        else AllGlass.afterFill();
    }

    @Unique
    private boolean s1mp1e$drawCards(List<String> lines, boolean left) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer font = mc.textRenderer;
        int lh = 9, gw = mc.getWindow().getScaledWidth();
        int i = 0, n = lines.size();
        boolean any = false;
        while (i < n) {
            if (lines.get(i) == null || lines.get(i).isEmpty()) { i++; continue; }
            int start = i, w = 0;
            while (i < n && lines.get(i) != null && !lines.get(i).isEmpty()) { w = Math.max(w, font.getStringWidth(lines.get(i))); i++; }
            int y0 = 2 + lh * start - 2, y1 = 2 + lh * i;
            int x0 = left ? 0 : gw - 4 - w, x1 = left ? w + 4 : gw;
            AllGlass.plate(x0, y0, x1, y1, 1f, 0x60000000);
            any = true;
        }
        if (any) {
            // the raw-GL glass leaves texturing on and the colour white (GlassRenderer.endBatch); text draws next
            com.mojang.blaze3d.systems.RenderSystem.enableTexture();
        }
        return any;
    }
}
