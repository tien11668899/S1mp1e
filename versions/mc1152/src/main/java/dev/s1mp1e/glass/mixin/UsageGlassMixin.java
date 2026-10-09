package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.screen.CommandSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * #14 — command usage hints above the chat input: opaque black bars → rounded dark scrims at vanilla's opacity. 1.15.2
 * draws the usage/error {@code messages} in the OUTER {@code CommandSuggestor.render(II)V} with a single static
 * {@code DrawableHelper.fill(IIIII)V} behind them (javap-verified; owner = DrawableHelper because CommandSuggestor does
 * not extend it) — the suggestion-window rows are a different method ({@code SuggestionWindow.render}, handled by
 * {@code SuggestionsListGlideMixin}).
 */
@Mixin(CommandSuggestor.class)
public abstract class UsageGlassMixin {

    @WrapOperation(method = "render(II)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;fill(IIIII)V"))
    private void s1mp1e$round(int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        AllGlass.scrim(x0, y0, x1, y1, 3f, (argb & 0xFF000000) | 0x101014);
        AllGlass.afterFill();
    }
}
