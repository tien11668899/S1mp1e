package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #26 — the credits roll scrolls its own tiled dirt. 1.15.2 draws it in the private {@code renderBackground(IIF)V} (raw
 * Tessellator, javap-verified), NOT through the Screen's dirt background, so {@code ScreenMenuBackdropMixin} never sees
 * it. Opened from the title (no world) the roll becomes the blurred title panorama; the end-of-game roll (world loaded)
 * keeps vanilla's look. HEAD-cancel the vanilla dirt when the panorama backdrop is drawn ({@link MenuBackdrop#draw()}
 * false -> no panorama frame yet / blur unusable -> vanilla dirt draws).
 */
@Mixin(CreditsScreen.class)
public abstract class CreditsBackdropMixin {

    @Inject(method = "renderBackground(IIF)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$panorama(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().world != null) return;   // end-game roll keeps vanilla scrolling dirt
        if (MenuBackdrop.draw()) ci.cancel();
    }
}
