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
 * Fabric client entrypoint for the 1.16.5 line. The glass render layer is
 * identical to the Forge lines; only the hook plumbing (mixins + Fabric
 * callbacks) is version-specific. This builds the S1mp1e client modules and
 * opens the in-game config screen on the menu key (default RightShift, GLFW 344;
 * rebindable — stored in modules.json). It also registers the HUD render pass
 * ({@link HudRenderCallback}) that paints every enabled {@link HudRenderer} module.
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
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.16.5 Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in production): load every
        // class our mixins target right away, so a broken injection (defaultRequire 1) fails at the title screen
        // instead of the first time that screen/HUD element happens to render. Covers the GUI widgets plus the
        // four late-loading targets whose injections are the retarget-sensitive ones on 1.16.5: InGameHud (XP
        // WrapOperation, effect overlay, crosshair), GameRenderer (getFov, bobViewWhenHurt),
        // LightmapTextureManager (the gamma GETFIELD) and Sprite (the `images` accessor).
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.ClickableWidget",
                    "net.minecraft.client.gui.widget.SliderWidget",
                    "net.minecraft.client.option.KeyBinding",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.gui.hud.InGameHud",
                    "net.minecraft.client.render.GameRenderer",
                    "net.minecraft.client.render.LightmapTextureManager",
                    "net.minecraft.client.texture.Sprite",
                    // BATCH-A screen glass added this round: Social Interactions (A) + the shared
                    // effect-strip host (F). Both are screen classes, so preloading them here makes a
                    // broken injection fail at the title screen instead of when that screen first opens.
                    "net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen",
                    "net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen",
                    // Feature A remainder: advancements window, statistics (+ its three inner stat lists) and the
                    // book view / edit screens (the lectern inherits BookScreen.render).
                    "net.minecraft.client.gui.screen.advancement.AdvancementsScreen",
                    "net.minecraft.client.gui.screen.StatsScreen",
                    "net.minecraft.client.gui.screen.StatsScreen$GeneralStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen$ItemStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen$EntityStatsListWidget",
                    "net.minecraft.client.gui.screen.ingame.BookScreen",
                    "net.minecraft.client.gui.screen.ingame.BookEditScreen",
                    // Container glass now replaces only the body-PNG blit (DrawableHelper funnel) + the screens whose
                    // render calls drawBackground directly (grindstone always; inventory / crafting / furnace in the
                    // narrow recipe-book layout).
                    "net.minecraft.client.gui.DrawableHelper",
                    "net.minecraft.client.gui.screen.ingame.GrindstoneScreen",
                    "net.minecraft.client.gui.screen.ingame.InventoryScreen",
                    "net.minecraft.client.gui.screen.ingame.CraftingScreen",
                    "net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen",
                    // BATCH-A stage 2: fused creative tabs (B), the glass scrollbar (C) + sub-pixel glide (D) targets.
                    "net.minecraft.client.gui.screen.ingame.HandledScreen",
                    "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                    "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
                    "net.minecraft.client.gui.screen.ingame.LoomScreen",
                    "net.minecraft.client.gui.screen.ingame.MerchantScreen",
                    // BATCH-B stage 3: HUD overlays (G) + Block Outline (H). InGameHud (action bar G5) is already
                    // preloaded above. Preloading these makes any broken injection fail at the title screen instead
                    // of when the chat/tab list/boss bar/toast/name tag/outline first draws in-world.
                    "net.minecraft.client.gui.hud.ChatHud",
                    "net.minecraft.client.gui.screen.ChatScreen",
                    "net.minecraft.client.gui.hud.PlayerListHud",
                    "net.minecraft.client.gui.hud.BossBarHud",
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

        // Build modules + load saved config (guarded internally; never throws).
        try { ModuleManager.init(); } catch (Throwable t) { System.out.println("[S1mp1e] module init failed: " + t); }

        // HUD modules paint here, once per frame. Each module guards itself (F1 /
        // no player), and a throw in one is swallowed so it never kills the HUD pass.
        // 1.16.5 HudRenderCallback hands (MatrixStack, float tickDelta) — we ignore the second arg.
        // The whole registration is guarded so a missing fabric-api disables the HUD pass instead of crashing.
        try {
            HudRenderCallback.EVENT.register((matrices, tickDelta) -> {
                MinecraftClient c = MinecraftClient.getInstance();
                if (c.player == null) return;
                for (Module m : ModuleManager.all()) {
                    if (m.enabled && m instanceof HudRenderer) {
                        try { ((HudRenderer) m).renderHud(matrices); } catch (Throwable t) { /* one bad module never breaks the HUD */ }
                    }
                }
            });
        } catch (Throwable t) {
            System.out.println("[S1mp1e] HUD render registration failed: " + t);
        }

        // Edge-detected menu-open poll: opens from menus too (currentScreen == null gate).
        // Guards against a spurious open at startup: the window must be focused, and the key must
        // have been observed released at least once (menuArmed) so a stale GLFW "pressed" state
        // carried over from launch can't fire it. The whole registration is guarded so a missing
        // fabric-api disables the poll instead of crashing the client.
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
            System.out.println("[S1mp1e] menu-key poll registration failed: " + t);
        }
    }
}
