package dev.s1mp1e.glass;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

/**
 * Fabric client entrypoint. Glass render layer is shared; this also builds the
 * S1mp1e client modules and opens the in-game config screen on the menu key
 * (default RightShift, GLFW 344; rebindable — stored in modules.json).
 */
public final class S1mp1eClient implements ClientModInitializer {
    public static final String MODID = "s1mp1e";

    private boolean menuWasDown;
    /** Require the menu key to be seen RELEASED once before the first open is allowed. GLFW can
     *  report a key still "pressed" from before the window took focus (e.g. a modifier held while
     *  launching), which used to auto-open the config a few seconds after entering a world. */
    private boolean menuArmed;

    @Override
    public void onInitializeClient() {
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.20.1 Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in production): load the
        // widget classes our GUI mixins target right away, so a broken injection (defaultRequire 1) fails at launch
        // instead of the first time a menu opens.
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.ClickableWidget",
                    "net.minecraft.client.gui.widget.SliderWidget",
                    "net.minecraft.client.option.KeyBinding" }) {
                try {
                    Class.forName(target, true, S1mp1eClient.class.getClassLoader());
                    System.out.println("[S1mp1e] mixin target preload OK: " + target);
                } catch (Throwable t) {
                    System.out.println("[S1mp1e] mixin target preload FAILED: " + target + " -> " + t);
                    t.printStackTrace();
                }
            }
        }

        // Build modules + load saved config (guarded internally; never throws).
        try { ModuleManager.init(); } catch (Throwable t) { System.out.println("[S1mp1e] module init failed: " + t); }

        // HUD modules paint here, once per frame. Each module guards itself (F1 /
        // no player), and a throw in one is swallowed so it never kills the HUD pass.
        // 1.20.1 HudRenderCallback hands (DrawContext, float tickDelta) — we ignore the second arg.
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player == null) return;
            for (Module m : ModuleManager.all()) {
                if (m.enabled && m instanceof HudRenderer) {
                    try { ((HudRenderer) m).renderHud(ctx); } catch (Throwable t) { /* one bad module never breaks the HUD */ }
                }
            }
        });

        // Edge-detected menu-open poll: opens from menus too (currentScreen == null gate).
        // Guards against a spurious open at startup: the window must be focused, and the key must
        // have been observed released at least once (menuArmed) so a stale GLFW "pressed" state
        // carried over from launch can't fire it.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.getWindow() == null) return;
            int mk = S1mp1eConfig.getMenuKey();
            boolean down = mk > 0
                    && client.isWindowFocused()
                    && InputUtil.isKeyPressed(client.getWindow().getHandle(), mk);
            if (!down) menuArmed = true;   // released -> real presses from now on are intentional
            if (menuArmed && down && !menuWasDown && client.currentScreen == null) {
                client.setScreen(new S1mp1eConfigScreen());
            }
            menuWasDown = down;
        });
    }
}
