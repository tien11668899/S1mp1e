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
import net.minecraft.client.util.math.MatrixStack;

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
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.17.1 Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in production): load the
        // classes our GUI/world mixins target right away, so a broken injection (defaultRequire 1) fails at launch
        // instead of the first time the target loads (a menu opens, a world is joined, glyphs are baked).
        // Class.forName(name, false, loader): define-time transformation is enough to surface a bad mixin, and
        // resolving without static init avoids the side effects of eagerly initialising screen/entity classes.
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            ClassLoader loader = S1mp1eClient.class.getClassLoader();
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.ClickableWidget",
                    // 1.21.1 feature set (ported 2026-10): every GUI class a new mixin targets
                    "net.minecraft.client.gui.screen.option.GameOptionsScreen",
                    "net.minecraft.client.gui.widget.ButtonListWidget$ButtonEntry",
                    "net.minecraft.client.gui.widget.CyclingButtonWidget",
                    "net.minecraft.client.gui.screen.option.ControlsListWidget$KeyBindingEntry",
                    "net.minecraft.client.gui.screen.option.ControlsListWidget$CategoryEntry",
                    "net.minecraft.client.gui.screen.option.LanguageOptionsScreen$LanguageSelectionListWidget$LanguageEntry",
                    "net.minecraft.client.font.TextRenderer",
                    "net.minecraft.client.render.GameRenderer",
                    "net.minecraft.client.gui.DrawableHelper",
                    "net.minecraft.client.gui.widget.PressableWidget",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget$ServerEntry",
                    "net.minecraft.client.gui.widget.TextFieldWidget",
                    "net.minecraft.client.gui.screen.ingame.SignEditScreen",
                    "net.minecraft.client.gui.screen.ingame.BookEditScreen",
                    "net.minecraft.client.gui.screen.CommandSuggestor",
                    "net.minecraft.client.gui.screen.CommandSuggestor$SuggestionWindow",
                    "net.minecraft.client.gui.screen.world.CreateWorldScreen",
                    "net.minecraft.client.gui.screen.ConnectScreen",
                    "net.minecraft.client.gui.screen.ProgressScreen",
                    "net.minecraft.client.gui.screen.LevelLoadingScreen",
                    "net.minecraft.client.gui.screen.world.WorldListWidget$Entry",
                    "net.minecraft.client.gui.screen.SplashOverlay",
                    "net.minecraft.client.gui.hud.BossBarHud",
                    "net.minecraft.client.gui.screen.recipebook.RecipeBookResults",
                    "net.minecraft.client.gui.screen.recipebook.AnimatedResultButton",
                    "net.minecraft.client.gui.widget.TexturedButtonWidget",
                    "net.minecraft.client.gui.widget.ToggleButtonWidget",
                    "com.mojang.blaze3d.systems.RenderSystem",
                    "net.minecraft.client.gui.widget.SliderWidget",
                    "net.minecraft.client.gui.screen.Screen",
                    "net.minecraft.client.gui.hud.InGameHud",
                    "net.minecraft.client.option.KeyBinding",
                    "net.minecraft.client.network.AbstractClientPlayerEntity",
                    "net.minecraft.client.gui.screen.ingame.HandledScreen",
                    "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                    "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
                    "net.minecraft.client.gui.screen.ingame.LoomScreen",
                    "net.minecraft.client.gui.screen.ingame.MerchantScreen",
                    "net.minecraft.client.gui.screen.ingame.MerchantScreen$WidgetButtonPage",
                    "net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen",
                    "net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen",
                    "net.minecraft.client.gui.screen.advancement.AdvancementsScreen",
                    "net.minecraft.client.gui.screen.StatsScreen",
                    "net.minecraft.client.gui.screen.ingame.BookScreen",
                    "net.minecraft.client.gui.screen.recipebook.RecipeBookWidget",
                    // BATCH-B (G) HUD overlays + (H) modules
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
                    "net.minecraft.client.render.RenderPhase$LineWidth",
                    "net.minecraft.client.font.GlyphAtlasTexture" }) {
                try {
                    Class.forName(target, false, loader);
                    System.out.println("[S1mp1e] mixin target preload OK: " + target);
                } catch (Throwable t) {
                    System.out.println("[S1mp1e] mixin target preload FAILED: " + target + " -> " + t);
                    t.printStackTrace();
                }
            }
        }

        // Build modules + load saved config (guarded internally; never throws).
        try { ModuleManager.init(); } catch (Throwable t) { System.out.println("[S1mp1e] module init failed: " + t); }

        // Fabric-API registration lives in its own method so its class references (HudRenderCallback,
        // ClientTickEvents) are verified only when it runs. Wrapped in try/catch(Throwable): if fabric-api is
        // absent the NoClassDefFoundError is swallowed and the client layer stays dark instead of crashing the game.
        try { registerFabricApi(); } catch (Throwable t) { System.out.println("[S1mp1e] fabric-api client hooks unavailable: " + t); }
    }

    private void registerFabricApi() {
        // HUD modules paint here, once per frame. Each module guards itself (F1 /
        // no player), and a throw in one is swallowed so it never kills the HUD pass.
        // 1.19.2 HudRenderCallback hands (MatrixStack, float tickDelta) — we ignore the second arg.
        HudRenderCallback.EVENT.register((MatrixStack matrices, float td) -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player == null) return;
            for (Module m : ModuleManager.all()) {
                if (m.enabled && m instanceof HudRenderer) {
                    try { ((HudRenderer) m).renderHud(matrices); } catch (Throwable t) { /* one bad module never breaks the HUD */ }
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
