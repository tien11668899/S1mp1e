package dev.s1mp1e.glass;

import dev.s1mp1e.client.ModuleManager;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entrypoint for the 1.13.2 Legacy Fabric line. The glass render layer is
 * identical to the other lines; only the hook plumbing (mixins — there is no Fabric API
 * here) is version-specific.
 *
 * <p>Fabric-loader 0.15.11 inserts this entrypoint at the HEAD of
 * {@code MinecraftClient.initializeGame()}, BEFORE GameOptions, the Window, the
 * ResourceManager and the GL context exist — only {@code getInstance()},
 * {@code runDirectory} and the pack manager are set. {@link ModuleManager#init()} and
 * {@code S1mp1eConfig.load()} only need {@code runDirectory}, so they are safe here; nothing
 * on this path may touch {@code mc.options}, the Window, the ResourceManager, the text
 * renderer or GL.
 */
public final class S1mp1eClient implements ClientModInitializer {
    public static final String MODID = "s1mp1e";

    @Override
    public void onInitializeClient() {
        System.out.println("[S1mp1e] client init - liquid glass 0.1.0 (1.13.2 Legacy Fabric)");

        // DEV ONLY (set by `runClient` via -Ds1mp1e.preloadMixinTargets=true in build.gradle, never in
        // production): eagerly load the classes our GUI/input/font mixins target that are otherwise only
        // loaded LAZILY (option/sound sliders, the list widget, KeyBinding, AbstractClientPlayerEntity,
        // PlayerInventory at world join, the glyph atlas, the Mouse). With defaultRequire 1 a broken
        // injection crashes at CLASS LOAD, so this surfaces such a break at launch instead of the first
        // time a video/sound slider, a keybind, a glyph page or a mouse event is touched. The targets that
        // load inside initializeGame anyway (InGameHud, MinecraftClient, ...) fail at startup regardless.
        //
        // Yarn names resolve only in the dev (named) namespace, which is exactly where the flag is set.
        // These run at the HEAD of initializeGame (no GL context yet), so ONLY GL-free classes are listed:
        // every one below is a plain container/data class or an AbstractTexture subclass with no static GL
        // initialiser (all verified). NEVER LivingEntityRenderer (its static TEX is a NativeImageBacked-
        // Texture), and NEVER MixinEnvironment.audit() (it hard-crashed the JVM in the mc1144 boot).
        if (Boolean.getBoolean("s1mp1e.preloadMixinTargets")) {
            for (String target : new String[] {
                    "net.minecraft.client.gui.widget.ButtonWidget",
                    "net.minecraft.client.gui.widget.OptionSliderWidget",
                    "net.minecraft.client.gui.screen.SoundsScreen$SoundButtonWidget",
                    "net.minecraft.client.gui.widget.ListWidget",
                    "net.minecraft.client.option.KeyBinding",
                    "net.minecraft.client.network.AbstractClientPlayerEntity",
                    "net.minecraft.entity.player.PlayerInventory",
                    "net.minecraft.class_4132",   // glyph atlas texture (GlyphAtlasSmoothMixin)
                    "net.minecraft.class_4112" }) { // Mouse (MouseClickMixin)
                try {
                    Class.forName(target, true, S1mp1eClient.class.getClassLoader());
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
