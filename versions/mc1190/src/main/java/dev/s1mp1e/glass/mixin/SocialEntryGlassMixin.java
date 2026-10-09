package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.screen.multiplayer.SocialInteractionsPlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * #10 — Social Interactions rows: solid grey blocks ({@code GRAY_COLOR 0xFF4A4A4A} /
 * {@code DARK_GRAY_COLOR 0xFF303030}) → rounded GREY scrims (white names stay readable on the glass panel; a white
 * scrim would hide them).
 */
@Mixin(SocialInteractionsPlayerListEntry.class)
public abstract class SocialEntryGlassMixin {

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$row(MatrixStack matrices, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (argb == SocialInteractionsPlayerListEntry.GRAY_COLOR || argb == SocialInteractionsPlayerListEntry.DARK_GRAY_COLOR) {
            AllGlass.scrim(matrices, x0, y0, x1, y1, Math.min(6.3f, (y1 - y0) / 2f),
                    argb == SocialInteractionsPlayerListEntry.GRAY_COLOR ? 0x40000000 : 0x24000000);
        } else {
            original.call(matrices, x0, y0, x1, y1, argb);
        }
    }
}
