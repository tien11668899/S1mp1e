package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the S1mp1e config screen on the menu key (default RightShift, rebindable, stored in
 * modules.json as "menuKey" as a GLFW code). 1.13.2 port of mc1211's
 * {@code ClientTickEvents.END_CLIENT_TICK} poll, following mc262's MenuKeyMixin.
 *
 * <p>There is NO Fabric API on this Legacy-Fabric 1.13.2 line, so the tick poll is a plain
 * Mixin at {@code MinecraftClient.tick()V} RETURN, the same point END_CLIENT_TICK would fire.
 * {@code tick()} is public, the only method named {@code tick}, and has exactly one return
 * (verified). All the unmapped Window / focus / key names go through the {@link Mc1132} bridge.
 *
 * <p>Guards against a spurious open at startup: the window must exist and be focused, and the
 * key must have been seen RELEASED at least once (armed) so a stale GLFW "pressed" state
 * carried over from launch cannot fire it. The open is edge-detected and only fires when no
 * screen is currently open. Everything runs inside a try/catch so a failure disables the
 * feature instead of crashing the game (hard rule 6).
 */
@Mixin(MinecraftClient.class)
public abstract class MenuKeyMixin {

    private static boolean s1mp1e$menuWasDown;
    private static boolean s1mp1e$menuArmed;

    @Inject(method = "tick", at = @At("RETURN"))
    private void s1mp1e$pollMenuKey(CallbackInfo ci) {
        try {
            MinecraftClient mc = (MinecraftClient) (Object) this;
            if (Mc1132.window() == null) return;
            int mk = S1mp1eConfig.getMenuKey();
            boolean down = mk > 0 && Mc1132.focused() && Mc1132.keyDown(mk);
            if (!down) s1mp1e$menuArmed = true;   // released -> real presses from now on are intentional
            if (s1mp1e$menuArmed && down && !s1mp1e$menuWasDown && mc.currentScreen == null) {
                mc.setScreen(new S1mp1eConfigScreen());
            }
            s1mp1e$menuWasDown = down;
        } catch (Throwable ignored) {
            // A failure here must never crash the client; the menu key just stops opening.
        }
    }
}
