package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.class_4112;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The wheel over a settings page. On 1.13.2 {@code Screen.mouseScrolled(double)} has no pointer position and the
 * default implementation only forwards to the focused child (the video / language pages even pin "focused" to their
 * own list), so the settings shell could never see it as a child. The Mouse ({@code class_4112}) calls it from
 * {@code method_18241} (the GLFW scroll callback): the shell is asked first, with the cursor in GUI pixels.
 */
@Mixin(class_4112.class)
public abstract class MouseShellMixin {
    @WrapOperation(method = "method_18241",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;mouseScrolled(D)Z"))
    private boolean s1mp1e$shellWheel(Screen screen, double amount, Operation<Boolean> original) {
        try {
            if (SettingsShell.handles(screen)
                    && SettingsShell.wheel(screen, Mc1132.scaledMouseX(), Mc1132.scaledMouseY(), amount)) return true;
        } catch (Throwable ignored) {
            // the vanilla path below still runs
        }
        return original.call(screen, amount);
    }
}
