package dev.s1mp1e.glass;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

/**
 * Fabric client entrypoint for the 1.15.2 line. The glass render layer is
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
        System.out.println("[S1mp1e] client init — liquid glass 0.1.0 (1.15.2 Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in production): load every
        // class our mixins target right away, so a broken injection (defaultRequire 1) fails at the title screen
        // instead of the first time that screen/HUD element happens to render. Covers the GUI widgets plus the
        // four late-loading targets whose injections are the retarget-sensitive ones on 1.15.2: InGameHud (XP
        // WrapOperation, effect overlay, crosshair), GameRenderer (getFov, bobViewWhenHurt),
        // LightmapTextureManager (the gamma GETFIELD) and Sprite (the `images` accessor).
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.hud.InGameHud",
                    "net.minecraft.client.gui.widget.AbstractButtonWidget",
                    "net.minecraft.client.gui.widget.SliderWidget",
                    "net.minecraft.client.gui.screen.Screen",
                    "net.minecraft.client.gui.screen.ingame.ContainerScreen",
                    "net.minecraft.client.gui.DrawableHelper",
                    "net.minecraft.client.gui.screen.ingame.GrindstoneScreen",
                    "net.minecraft.client.gui.screen.ingame.InventoryScreen",
                    "net.minecraft.client.gui.screen.ingame.CraftingTableScreen",
                    "net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen",
                    "net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen",
                    "net.minecraft.client.gui.screen.StatsScreen$GeneralStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen$ItemStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen$EntityStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.gui.screen.ingame.BookScreen",
                    "net.minecraft.client.gui.screen.ingame.BookEditScreen",
                    "net.minecraft.client.gui.screen.TitleScreen",
                    "net.minecraft.client.gui.screen.recipebook.RecipeBookWidget",
                    "net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget",
                    "net.minecraft.client.gui.widget.ToggleButtonWidget",
                    "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                    "net.minecraft.client.gui.screen.recipebook.AnimatedResultButton",
                    "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
                    "net.minecraft.client.gui.screen.ingame.MerchantScreen",
                    "net.minecraft.client.texture.Sprite",
                    "net.minecraft.client.gui.hud.InGameOverlayRenderer",
                    "net.minecraft.entity.player.PlayerEntity",
                    "net.minecraft.client.network.ClientPlayNetworkHandler",
                    "net.minecraft.entity.LivingEntity",
                    "net.minecraft.client.render.GameRenderer",
                    "net.minecraft.client.render.entity.LivingEntityRenderer",
                    "net.minecraft.client.render.item.HeldItemRenderer",
                    "net.minecraft.client.network.AbstractClientPlayerEntity",
                    "net.minecraft.client.render.LightmapTextureManager",
                    "net.minecraft.client.options.KeyBinding",
                    "net.minecraft.client.gui.hud.ChatHud",
                    "net.minecraft.client.gui.screen.ChatScreen",
                    "net.minecraft.client.gui.hud.PlayerListHud",
                    "net.minecraft.client.gui.hud.BossBarHud",
                    "net.minecraft.client.toast.AdvancementToast",
                    "net.minecraft.client.toast.RecipeToast",
                    "net.minecraft.client.toast.TutorialToast",
                    "net.minecraft.client.toast.SystemToast",
                    "net.minecraft.client.font.TrueTypeFontLoader",
                    "net.minecraft.client.gui.widget.ButtonListWidget$ButtonEntry",
                    "net.minecraft.client.gui.screen.options.ControlsListWidget$KeyBindingEntry",
                    "net.minecraft.client.gui.screen.options.ControlsListWidget$CategoryEntry",
                    "net.minecraft.client.gui.screen.options.LanguageOptionsScreen$LanguageSelectionListWidget$LanguageEntry",
                    "net.minecraft.client.font.TextRenderer",
                    "net.minecraft.client.gui.widget.AbstractPressableButtonWidget",
                    "net.minecraft.client.gui.screen.world.CreateWorldScreen",
                    "net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget$ServerEntry",
                    "net.minecraft.client.gui.widget.TextFieldWidget",
                    "net.minecraft.client.gui.screen.CommandSuggestor",
                    "net.minecraft.client.gui.screen.CommandSuggestor$SuggestionWindow",
                    "net.minecraft.client.gui.screen.recipebook.RecipeBookResults",
                    "net.minecraft.client.gui.widget.TexturedButtonWidget",
                    "net.minecraft.client.gui.screen.ConnectScreen",
                    "net.minecraft.client.gui.screen.ProgressScreen",
                    "net.minecraft.client.gui.screen.LevelLoadingScreen",
                    "net.minecraft.client.gui.screen.world.WorldListWidget$Entry",
                    "net.minecraft.client.gui.screen.SplashScreen",
                    "net.minecraft.client.gui.screen.advancement.AdvancementsScreen",
                    "net.minecraft.client.texture.AbstractTexture",
                    "net.minecraft.client.font.GlyphAtlasTexture",
                    "net.minecraft.client.gui.widget.OptionButtonWidget",
                    "net.minecraft.client.gui.screen.options.GameOptionsScreen",
                    "net.minecraft.client.gui.screen.ingame.LoomScreen",
                    "net.minecraft.client.gui.screen.ingame.SignEditScreen",
                    "net.minecraft.client.render.WorldRenderer",
                    "net.minecraft.client.render.entity.EntityRenderer",
                    "net.minecraft.client.render.RenderPhase$LineWidth" }) {
                try {
                    // load WITHOUT initialising: mixins are applied when the class is defined, and a static initialiser
                    // may need the GL context (LivingEntityRenderer creates a texture) that does not exist yet
                    Class.forName(target, false, S1mp1eClient.class.getClassLoader());
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
        // 1.15.2 HudRenderCallback hands (MatrixStack, float tickDelta) — we ignore the second arg.
        // The whole registration is guarded so a missing fabric-api disables the HUD pass instead of crashing.
        // HUD modules: 1.15.2's Fabric API has no fabric-rendering-v1 HudRenderCallback, so the dispatch
        // (dev.s1mp1e.client.HudDispatch.renderAll) runs from InGameHudMixin at InGameHud.render TAIL.

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
