package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameModeSelectionScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** F3+F4 switcher panel ({@code gamemode_switcher.png}) → glass plate with a grey scrim; slots via AllGlassSpriteMixin. */
@Mixin(GameModeSelectionScreen.class)
public abstract class GameModeSelectionGlassMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"))
    private void s1mp1e$panel(DrawContext ctx, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
        AllGlass.plate(ctx, x, y, x + w, y + h, 1f, 0x30000000);
    }
}
