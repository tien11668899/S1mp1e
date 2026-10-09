package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.screen.CommandSuggestor;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * #14 — command usage hints above the chat input: opaque black bars → rounded dark scrims at vanilla's opacity. 1.18.2
 * names the class {@code CommandSuggestor} (not {@code ChatInputSuggestor}); the usage/error {@code messages} are drawn
 * in the OUTER {@code CommandSuggestor.render} with a single {@code DrawableHelper.fill} behind them (javap-verified) —
 * the suggestion-window rows are a different method ({@code SuggestionWindow.render}, handled by
 * {@code SuggestionsListGlideMixin}).
 */
@Mixin(CommandSuggestor.class)
public abstract class UsageGlassMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$round(MatrixStack matrices, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        AllGlass.scrim(matrices, x0, y0, x1, y1, 3f, (argb & 0xFF000000) | 0x101014);
    }
}
