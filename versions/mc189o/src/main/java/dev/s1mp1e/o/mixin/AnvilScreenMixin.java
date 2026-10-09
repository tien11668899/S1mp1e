package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.hook.AnvilFieldHook;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.inventory.menu.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * allglass #12 — the anvil rename field's {@code anvil.png} frame becomes a glass scrim (same look as #3). The field's
 * {@code TextFieldWidget} has its border turned off ({@code setHasBorder(false)}), so {@link dev.s1mp1e.o.glass.hook.EditBoxHook}
 * never frames it; the frame is this {@code drawTexture(i+59, j+20, 0, backgroundHeight(+16 when empty), 110, 16)} blit
 * inside {@code renderMenuBackground(FII)}. Wrap it: the 110&times;16 field sprite (v = 166 editable, 182 empty) goes to
 * {@link AnvilFieldHook}; every other blit (the panel, the error-cross) flows on through {@code op}.
 */
@Mixin(value = AnvilScreen.class, priority = 1100)
public abstract class AnvilScreenMixin {

    @WrapOperation(method = "renderMenuBackground", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/inventory/menu/AnvilScreen;drawTexture(IIIIII)V"))
    private void s1mp1e$field(AnvilScreen self, int x, int y, int u, int v, int w, int h, Operation<Void> op) {
        if (w == 110 && h == 16 && u == 0 && (v == 166 || v == 182)) {
            AnvilFieldHook.blit((GuiElement) (Object) this, x, y, u, v, w, h);
        } else {
            op.call(self, x, y, u, v, w, h);
        }
    }
}
