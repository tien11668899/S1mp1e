package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 vanilla bug fix: a key event SDL cannot identify must never press every UNBOUND key binding.
 *
 * <p>26.3 builds {@code KeyEvent} from the SDL scan code, and {@code InputConstants.UNKNOWN} is
 * {@code KEYBOARD.getOrCreate(0)} — i.e. SDL's "unknown" scan code 0. Every unbound mapping is stored in
 * {@code KeyMapping.MAP} under that same UNKNOWN key, and {@code KeyboardHandler.keyPress} calls
 * {@code KeyMapping.set(key, true)} + {@code KeyMapping.click(key)} with no filter. So any key SDL reports as scan code 0
 * "presses" and "clicks" all unbound bindings at once.
 *
 * <p>This is exactly what an East-Asian IME triggers: Windows hands Shift (the IME's language toggle) to the game as
 * {@code VK_PROCESSKEY}, SDL reports scan code 0, and pressing Right Shift then opened Mod Menu (its open-menu key is
 * unbound by default) and toggled vanilla's cinematic "smooth camera" (also unbound by default), which is why mouse look
 * turned slow afterwards. GLFW-era versions never hit this: their unknown key was -1 and fell back to a SCANCODE key.
 *
 * <p>UNKNOWN means "no key", so dropping it here never loses a real binding.
 */
@Mixin(KeyMapping.class)
public abstract class UnknownKeyGuardMixin {

    @Unique private static int s1mp1e$blocked;
    /** Dev A/B switch only: {@code -Ds1mp1e.keyguard=false} restores the vanilla (buggy) behaviour. */
    @Unique private static final boolean S1MP1E$OFF = "false".equals(System.getProperty("s1mp1e.keyguard"));

    @Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$guardSet(InputConstants.Key key, boolean down, CallbackInfo ci) {
        if (!S1MP1E$OFF && InputConstants.UNKNOWN.equals(key)) {
            if (down) s1mp1e$note();
            ci.cancel();
        }
    }

    @Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$guardClick(InputConstants.Key key, CallbackInfo ci) {
        if (!S1MP1E$OFF && InputConstants.UNKNOWN.equals(key)) ci.cancel();
    }

    @Unique
    private static void s1mp1e$note() {
        if (s1mp1e$blocked++ < 3) {
            System.out.println("[S1mp1e] ignored a key event SDL reported as scan code 0 (unknown) - "
                    + "prevented it from firing every unbound key binding");
        }
    }
}
