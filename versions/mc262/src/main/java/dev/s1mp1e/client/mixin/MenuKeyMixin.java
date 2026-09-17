package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the S1mp1e config screen on the menu key (default RightShift, rebindable — stored in modules.json as
 * "menuKey"). 26.2 port of mc1211's {@code ClientTickEvents.END_CLIENT_TICK} poll.
 *
 * <p>Hooked directly at {@code Minecraft.tick()} RETURN — the same place Fabric API's END_CLIENT_TICK fires —
 * so the mod needs NO Fabric API at all (like the recovered 26.2 glass it builds on). That matters beyond
 * tidiness: with 26.2's identity mappings, every Fabric API module that ships a class tweaker makes the dev
 * {@code runClient} abort on a namespace mismatch, so staying Fabric-API-free keeps the dev client launchable.
 *
 * <p>Guards against a spurious open at startup: the window must be focused, and the key must have been seen
 * RELEASED at least once (armed) so a stale GLFW "pressed" state carried over from launch can't fire it. Edge
 * detected, and only opens when no screen is open (26.2 keeps the current screen on {@code Minecraft.gui}).
 */
@Mixin(Minecraft.class)
public abstract class MenuKeyMixin {

    private static boolean s1mp1e$menuWasDown;
    private static boolean s1mp1e$menuArmed;

    @Inject(method = "tick", at = @At("RETURN"))
    private void s1mp1e$pollMenuKey(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.getWindow() == null || mc.gui == null) return;
        int mk = S1mp1eConfig.getMenuKey();
        boolean down = mk > 0 && mc.isWindowActive() && InputConstants.isKeyDown(mc.getWindow(), mk);
        if (!down) s1mp1e$menuArmed = true;   // released -> real presses from now on are intentional
        if (s1mp1e$menuArmed && down && !s1mp1e$menuWasDown && mc.gui.screen() == null) {
            mc.gui.setScreen(new S1mp1eConfigScreen());
        }
        s1mp1e$menuWasDown = down;
    }
}
