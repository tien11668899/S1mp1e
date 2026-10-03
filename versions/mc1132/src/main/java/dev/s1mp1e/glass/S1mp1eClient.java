package dev.s1mp1e.glass;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint for the 1.13.2 line. The glass render layer is
 * identical to the Forge lines; only the hook plumbing (mixins + Fabric
 * callbacks) is version-specific. This builds the S1mp1e client modules and
 * opens the in-game config screen on the menu key (default RightShift, GLFW 344;
 * rebindable — stored in modules.json). It also registers the HUD render pass
 * ({@link HudRenderCallback}) that paints every enabled {@link HudRenderer} module.
 */
public final class S1mp1eClient implements ClientModInitializer {
    public static final String MODID = "s1mp1e";

    @Override
    public void onInitializeClient() {
        System.out.println("[S1mp1e] client init - liquid glass 0.1.0 (1.13.2 Legacy Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true, never in production): load every
        // class our mixins target right away, so a broken injection (defaultRequire 1) fails at the title screen
        // instead of the first time that screen/HUD element happens to render. Covers the GUI widgets plus the
        // four late-loading targets whose injections are the retarget-sensitive ones on 1.13.2: InGameHud (XP
        // WrapOperation, effect overlay, crosshair), GameRenderer (getFov, bobViewWhenHurt),
        // LightmapTextureManager (the gamma GETFIELD) and Sprite (the `images` accessor).
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.ButtonWidget",
                    "net.minecraft.client.gui.hud.InGameHud",
                    "net.minecraft.entity.player.PlayerInventory",
                    "net.minecraft.client.gui.screen.Screen",
                    "net.minecraft.client.gui.screen.ingame.HandledScreen",
                    "net.minecraft.client.gui.screen.TitleScreen",
                    "net.minecraft.client.gui.screen.RecipeBookScreen",
                    "net.minecraft.class_3284",
                    "net.minecraft.class_3285",
                    "net.minecraft.class_3257",
                    "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                    "net.minecraft.class_4132",
                    "net.minecraft.client.texture.Sprite",
                    "net.minecraft.class_4112",
                    "net.minecraft.class_4225",
                    "net.minecraft.entity.player.PlayerEntity",
                    "net.minecraft.client.network.ClientPlayNetworkHandler",
                    "net.minecraft.entity.LivingEntity",
                    "net.minecraft.class_4226",
                    "net.minecraft.client.network.AbstractClientPlayerEntity",
                    "net.minecraft.class_4218",
                    "net.minecraft.client.option.KeyBinding",
                    "net.minecraft.client.render.entity.LivingEntityRenderer",
                    "net.minecraft.client.gui.widget.OptionSliderWidget",
                    "net.minecraft.client.gui.screen.SoundsScreen$SoundButtonWidget",
                    "net.minecraft.client.gui.DrawableHelper",
                    "net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen",
                    "net.minecraft.client.gui.screen.ingame.CraftingTableScreen",
                    "net.minecraft.client.gui.screen.ingame.FurnaceScreen",
                    "net.minecraft.client.gui.screen.ingame.InventoryScreen",
                    "net.minecraft.client.gui.screen.StatsScreen$GeneralStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen$ItemStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen$EntityStatsListWidget",
                    "net.minecraft.client.gui.screen.StatsScreen",
                    "net.minecraft.client.gui.widget.ListWidget",
                    "net.minecraft.client.gui.screen.ingame.BookEditScreen",
                    "net.minecraft.client.gui.hud.ChatHud",
                    "net.minecraft.client.gui.screen.ChatScreen",
                    "net.minecraft.client.gui.hud.PlayerListHud",
                    "net.minecraft.client.gui.hud.BossBarHud",
                    "net.minecraft.class_3258",
                    "net.minecraft.class_3259",
                    "net.minecraft.class_3266",
                    "net.minecraft.class_3260",
                    "net.minecraft.class_4148$class_4149",
                    "net.minecraft.client.gui.widget.OptionPairWidget$Pair",
                    "net.minecraft.client.gui.screen.options.ControlsListWidget$KeyBindingEntry",
                    "net.minecraft.client.gui.screen.options.ControlsListWidget$CategoryEntry",
                    "net.minecraft.client.font.TextRenderer",
                    "net.minecraft.client.gui.screen.world.CreateWorldScreen",
                    "net.minecraft.client.gui.widget.ServerEntry",
                    "net.minecraft.client.gui.widget.TextFieldWidget",
                    "net.minecraft.client.gui.screen.ChatScreen$class_4155",
                    "net.minecraft.class_3283",
                    "net.minecraft.class_3256",
                    "net.minecraft.client.gui.screen.ConnectScreen",
                    "net.minecraft.client.gui.screen.ProgressScreen",
                    "net.minecraft.client.class_2847",
                    "net.minecraft.client.gui.screen.AdvancementsScreen",
                    "net.minecraft.client.gui.screen.options.ChatOptionsScreen",
                    "net.minecraft.client.gui.screen.options.ControlsOptionsScreen",
                    "net.minecraft.client.gui.screen.options.LanguageOptionsScreen",
                    "net.minecraft.client.gui.screen.options.SkinOptionsScreen",
                    "net.minecraft.client.gui.screen.SoundsScreen",
                    "net.minecraft.client.gui.screen.VideoOptionsScreen",
                    "net.minecraft.client.render.block.entity.SignBlockEntityRenderer",
                    "net.minecraft.client.render.WorldRenderer",
                    "net.minecraft.class_4158",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.gui.widget.LanguageButton",
                    "net.minecraft.client.gui.screen.options.LanguageOptionsScreen$LanguageSelectionListWidget",
                    "net.minecraft.client.gui.screen.ingame.SignEditScreen" }) { // Mouse (MouseClickMixin)
                try {
                    // load WITHOUT initialising: mixins are applied when the class is defined, and a static initialiser
                    // may need the GL context (LivingEntityRenderer creates a texture) that does not exist yet
                    Class.forName(target, false, S1mp1eClient.class.getClassLoader());
                    System.out.println("[S1mp1e] mixin target preload OK: " + target);
                } catch (Throwable t) {
                    System.out.println("[S1mp1e] mixin target preload FAILED: " + target + " -> " + t);
                }
            }
        }

        // Build the module registry and apply the shared modules.json. ModuleManager.init() guards itself
        // against a second call; a failure here must cost only the client layer, never the verified glass
        // render pipeline. The menu-open poll lives in MenuKeyMixin (no Fabric API on 1.13.2), not here.
        try {
            ModuleManager.init();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] module init failed: " + t);
        }
    }
}
