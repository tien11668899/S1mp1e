package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.s1mp1e.client.MenuKeyState;
import dev.s1mp1e.client.S1mp1eConfig;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Event side of the 26.3 menu key (see {@link MenuKeyMixin} for the state poll).
 *
 * <p>26.3 builds every {@code KeyEvent} from SDL: {@code key()} is the SDL scan code, {@code keycode()} the SDL key code.
 * A PRESS whose scan code — or, for a non-printable key, SDL key code — matches the configured menu key (an SDL scan
 * code on 26.3; {@code S1mp1eConfig} converts the shared file's GLFW code on load) raises
 * {@link MenuKeyState#menuKeyEvent}; the tick hook opens the config screen. This catches a quick tap that a once-per-tick state poll can miss.
 *
 * <p>Also logs (a handful of times per session) the raw values of Shift-family and scan-code-0 events, so a report of
 * "the menu key does nothing" can be diagnosed from latest.log alone: whether SDL sees Right Shift as 229, or the IME
 * turned it into an unknown (0) event.
 */
@Mixin(KeyboardHandler.class)
public abstract class MenuKeyEventMixin {

    @Unique private static int s1mp1e$diag;

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
    private void s1mp1e$menuKeyEvent(long window, int action, KeyEvent event, CallbackInfo ci) {
        try {
            int sc = event.key(), kc = event.keycode();
            if (s1mp1e$diag < 12 && (sc == 0 || sc == 225 || sc == 229
                    || kc == MenuKeyState.sdlKeycode(225) || kc == MenuKeyState.sdlKeycode(229))) {
                s1mp1e$diag++;
                System.out.println("[S1mp1e] key diag: action=" + action + " scancode=" + sc + " keycode=0x"
                        + Integer.toHexString(kc) + " mods=0x" + Integer.toHexString(event.modifiers())
                        + " sdlState(229)=" + InputConstants.isKeyDown(229));
            }
            if (action != 1) return;                       // PRESS only (0 = release, 2 = repeat)
            int want = S1mp1eConfig.getMenuKey();   // already an SDL scan code on 26.3
            if (want <= 0) return;
            if (sc == want || kc == MenuKeyState.sdlKeycode(want)) MenuKeyState.menuKeyEvent = true;
        } catch (Throwable ignored) {
            // never break key handling
        }
    }
}
