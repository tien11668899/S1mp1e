package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.screen.GameModeSelectionScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * #19 — F3+F4 switcher panel ({@code gamemode_switcher.png}) → glass plate with a grey scrim. 1.18.2 draws it via the
 * static {@code drawTexture(MatrixStack,IIFFIIII)} overload. The slots are {@code GameModeSlotGlassMixin}.
 */
@Mixin(GameModeSelectionScreen.class)
public abstract class GameModeSelectionGlassMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/GameModeSelectionScreen;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIFFIIII)V"))
    private void s1mp1e$panel(MatrixStack matrices, int x, int y, float u, float v, int w, int h, int tw, int th) {
        AllGlass.plate(matrices, x, y, x + w, y + h, 1f, 0x30000000);
    }

    /** Dev capture only ({@code S1MP1E_SHOT_MODE=allglass}): the switcher closes itself the frame F3 is not physically
     *  held; keep it open while the harness shoots it. Inert in normal play (flag is never set). */
    @Inject(method = "checkForClose", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$devHold(CallbackInfoReturnable<Boolean> cir) {
        if (dev.s1mp1e.client.DevAllGlass.holdSwitcher) cir.setReturnValue(false);
    }
}
