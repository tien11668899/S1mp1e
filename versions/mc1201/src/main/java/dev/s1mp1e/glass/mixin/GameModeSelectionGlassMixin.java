package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameModeSelectionScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** F3+F4 switcher panel ({@code gamemode_switcher.png}) → glass plate with a grey scrim. 1.20.1 draws it via the
 *  {@code drawTexture(Id,IIFFIIII)} overload. The slots are {@code GameModeSlotGlassMixin}. */
@Mixin(GameModeSelectionScreen.class)
public abstract class GameModeSelectionGlassMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"))
    private void s1mp1e$panel(DrawContext ctx, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
        AllGlass.plate(ctx, x, y, x + w, y + h, 1f, 0x30000000);
    }

    /** Dev capture only ({@code S1MP1E_SHOT_MODE=allglass}): the switcher closes itself the frame F3 is not physically
     *  held; keep it open while the harness shoots it. Inert in normal play (flag is never set). */
    @Inject(method = "checkForClose", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$devHold(CallbackInfoReturnable<Boolean> cir) {
        if (dev.s1mp1e.client.DevAllGlass.holdSwitcher) cir.setReturnValue(false);
    }
}
