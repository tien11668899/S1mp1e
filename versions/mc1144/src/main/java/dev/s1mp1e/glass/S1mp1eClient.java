package dev.s1mp1e.glass;

import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.util.InputUtil;

/**
 * Fabric client entrypoint for the 1.14.4 line. The glass render layer is
 * identical to the Forge lines; only the hook plumbing (mixins + Fabric
 * callbacks) is version-specific.
 */
public final class S1mp1eClient implements ClientModInitializer {
    public static final String MODID = "s1mp1e";

    private boolean menuWasDown;
    /** Require the menu key to be seen RELEASED once before the first open is allowed. GLFW can
     *  report a key still "pressed" from before the window took focus (e.g. a modifier held while
     *  launching), which would otherwise auto-open the config after entering a world. */
    private boolean menuArmed;

    @Override
    public void onInitializeClient() {
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.14.4 Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true in build.gradle, never in
        // production): load the classes our GUI/input/font mixins target right away, so a broken injection
        // (defaultRequire 1) fails at launch instead of the first time a menu, a keybind or a glyph page is
        // touched. Yarn names only work in the dev (named) namespace, which is exactly where the flag is set.
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.AbstractButtonWidget",
                    "net.minecraft.client.gui.widget.SliderWidget",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.options.KeyBinding",
                    "net.minecraft.client.network.AbstractClientPlayerEntity",
                    "net.minecraft.client.font.GlyphAtlasTexture" }) {
                try {
                    Class.forName(target, true, S1mp1eClient.class.getClassLoader());
                    System.out.println("[S1mp1e] mixin target preload OK: " + target);
                } catch (Throwable t) {
                    System.out.println("[S1mp1e] mixin target preload FAILED: " + target + " -> " + t);
                    t.printStackTrace();
                }
            }
        }

        // Build the module registry and apply the shared modules.json. ModuleManager.init()
        // guards itself against a second call (the entrypoint runs once anyway); a failure here
        // must cost only the client layer, never the verified glass render pipeline.
        try {
            ModuleManager.init();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] module init failed: " + t);
        }

        // Edge-detected menu-open poll (opens from menus too — currentScreen == null gate).
        // fabric.mod.json does NOT require fabric-api, so referencing ClientTickEvents (from
        // fabric-lifecycle-events-v1) is guarded: a missing class must disable this feature,
        // never crash client init. Guards against a spurious open at startup: the window must
        // be focused and the key must have been observed released at least once (menuArmed).
        try {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client == null || client.window == null) return;
                int mk = S1mp1eConfig.getMenuKey();
                // Only poll GLFW for a code it can actually accept (a real keyboard key). Guards the
                // old-GLFW "Invalid scancode -1" edge and a stray LWJGL2-namespace code arriving from
                // the 1.8.9 line through the shared modules.json — either just no-ops the open here.
                boolean down = S1mp1eConfig.isPollableKey(mk)
                        && client.isWindowFocused()
                        && InputUtil.isKeyPressed(client.window.getHandle(), mk);
                if (!down) menuArmed = true;   // released -> real presses from now on are intentional
                if (menuArmed && down && !menuWasDown && client.currentScreen == null) {
                    client.openScreen(new S1mp1eConfigScreen());
                }
                menuWasDown = down;
            });
        } catch (Throwable t) {
            System.out.println("[S1mp1e] menu-key hook registration failed: " + t);
        }
    }
}
