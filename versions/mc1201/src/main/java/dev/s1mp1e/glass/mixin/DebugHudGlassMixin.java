package dev.s1mp1e.glass.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3: a grey box behind every line → one glass card (plate + grey scrim) per group of consecutive lines (groups split at
 * vanilla's blank lines), sized to the widest line. The per-line boxes are dropped while the cards draw. 1.20.1's
 * {@code drawText(DrawContext, List<String>, boolean)} matches the 1.21.1 line.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudGlassMixin {

    @Unique private boolean s1mp1e$cards;

    @Inject(method = "drawText", at = @At("HEAD"))
    private void s1mp1e$cards(DrawContext ctx, List<String> lines, boolean left, CallbackInfo ci) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int lh = 9, gw = ctx.getScaledWindowWidth();
        int i = 0, n = lines.size();
        boolean any = false;
        while (i < n) {
            if (lines.get(i) == null || lines.get(i).isEmpty()) { i++; continue; }
            int start = i, w = 0;
            while (i < n && lines.get(i) != null && !lines.get(i).isEmpty()) { w = Math.max(w, font.getWidth(lines.get(i))); i++; }
            int y0 = 2 + lh * start - 2, y1 = 2 + lh * i;
            int x0 = left ? 0 : gw - 4 - w, x1 = left ? w + 4 : gw;
            AllGlass.plate(ctx, x0, y0, x1, y1, 1f, 0x60000000);
            any = true;
        }
        this.s1mp1e$cards = any;
    }

    @WrapOperation(method = "drawText", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$noLineBoxes(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (!this.s1mp1e$cards) original.call(ctx, x0, y0, x1, y1, argb);
    }
}
