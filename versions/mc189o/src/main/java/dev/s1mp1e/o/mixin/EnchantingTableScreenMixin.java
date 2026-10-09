package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.hook.EnchantRowHook;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.inventory.menu.EnchantingTableScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * allglass #12 — the enchanting table's three offer rows become glass buttons instead of vanilla's opaque brown
 * strips. Wrap the {@code drawTexture} blits inside {@code renderMenuBackground(FII)}: the 108&times;19 row sprites
 * (v = 166/185/204) go to {@link EnchantRowHook}; everything else (the panel background, the 16&times;16 level icons)
 * flows on through {@code op} so any lower-priority wrapper (Argentum batching) still applies.
 */
@Mixin(value = EnchantingTableScreen.class, priority = 1100)
public abstract class EnchantingTableScreenMixin {

    @WrapOperation(method = "renderMenuBackground", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/inventory/menu/EnchantingTableScreen;drawTexture(IIIIII)V"))
    private void s1mp1e$rows(EnchantingTableScreen self, int x, int y, int u, int v, int w, int h, Operation<Void> op) {
        if (w == 108 && h == 19 && u == 0 && (v == 166 || v == 185 || v == 204)) {
            EnchantRowHook.blit((GuiElement) (Object) this, x, y, u, v, w, h);
        } else {
            op.call(self, x, y, u, v, w, h);
        }
    }
}
