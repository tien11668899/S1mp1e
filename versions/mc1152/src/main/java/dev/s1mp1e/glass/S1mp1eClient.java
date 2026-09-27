package dev.s1mp1e.glass;

import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.util.InputUtil;

/**
 * Fabric client entrypoint for the 1.15.2 line. The glass render layer is
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
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.15.2 Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in the produced jar —
        // production reads the flag as false): force-load every class our mixins target right away, so a
        // broken injection (defaultRequire 1) fails at the title screen instead of the first time that
        // screen/HUD element/widget happens to render. Covers the retarget-sensitive late-loading targets:
        // the GUI widgets (AbstractButtonWidget/SliderWidget/EntryListWidget), the foreign-zoom KeyBinding,
        // the FOV multiplier on AbstractClientPlayerEntity, and the two font targets (GlyphAtlasTexture for
        // the @Accessor, AbstractTexture for the setFilter @ModifyVariable). InGameHud/GameRenderer/
        // LightmapTextureManager load at the title screen anyway on 1.15.2.
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.AbstractButtonWidget",
                    "net.minecraft.client.gui.widget.SliderWidget",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.options.KeyBinding",
                    "net.minecraft.client.network.AbstractClientPlayerEntity",
                    "net.minecraft.client.font.GlyphAtlasTexture",
                    "net.minecraft.client.texture.AbstractTexture",
                    // BATCH A / F screen targets (all late-loading, opened only in-world): force the
                    // application of AdvancementsGlassMixin, EffectsInInventoryGlassMixin (F strip),
                    // StatsListGlassMixin (shares EntryListWidget above) and the two Book mixins now,
                    // so a bad @Inject/@Redirect (defaultRequire 1) fails at the title screen.
                    "net.minecraft.client.gui.screen.advancement.AdvancementsScreen",
                    "net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen",
                    // BATCH B/C/D: creative fused tabs + glass scrollbar + grid glide, and the stonecutter / loom
                    // dedicated scroll+glide mixins. AbstractInventoryScreen above does NOT force the CreativeInventoryScreen
                    // subclass, so name it explicitly (and the two recipe screens) to apply their @Redirect/@Inject now.
                    "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                    "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
                    "net.minecraft.client.gui.screen.ingame.LoomScreen",
                    "net.minecraft.client.gui.screen.ingame.MerchantScreen",
                    "net.minecraft.client.gui.screen.ingame.BookScreen",
                    "net.minecraft.client.gui.screen.ingame.BookEditScreen",
                    // BATCH B stage 3: HUD overlays (G) + modules (H). ChatHud/BossBarHud/PlayerListHud load with
                    // InGameHud at the title, but the chat input screen, the toast classes, the world/entity
                    // renderers and the line-width render phase are in-world only — name them so their
                    // @Inject/@Redirect/@ModifyArg(s) (defaultRequire 1) apply and fail fast at the title.
                    "net.minecraft.client.gui.screen.ChatScreen",
                    "net.minecraft.client.toast.AdvancementToast",
                    "net.minecraft.client.toast.RecipeToast",
                    "net.minecraft.client.toast.TutorialToast",
                    "net.minecraft.client.toast.SystemToast",
                    "net.minecraft.client.render.entity.EntityRenderer",
                    "net.minecraft.client.render.WorldRenderer",
                    "net.minecraft.client.render.RenderPhase$LineWidth" }) {
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
        // 1.15.2: the window field is private -> client.getWindow(). Keys are GLFW codes natively.
        try {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client == null || client.getWindow() == null) return;
                int mk = S1mp1eConfig.getMenuKey();
                boolean down = mk > 0
                        && client.isWindowFocused()
                        && InputUtil.isKeyPressed(client.getWindow().getHandle(), mk);
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
