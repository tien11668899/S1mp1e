package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The wheel over a settings page scrolls the page (or its sidebar). A screen hands the wheel to the first child under
 * the cursor — there a cycle-button row, which would change its value — so the shell takes it here, before the screen.
 */
@Mixin(MouseHandler.class)
public abstract class MouseWheelShellMixin {

    @WrapOperation(method = "onScroll", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"))
    private boolean s1mp1e$settingsWheel(Screen screen, double mouseX, double mouseY, double horizontal, double vertical,
                                         Operation<Boolean> original) {
        if (SettingsShell.wheel(screen, mouseX, mouseY, vertical)) return true;
        return original.call(screen, mouseX, mouseY, horizontal, vertical);
    }
}
