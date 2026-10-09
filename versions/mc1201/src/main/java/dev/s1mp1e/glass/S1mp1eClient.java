package dev.s1mp1e.glass;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.hud.HudFade;
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
                    // 1.21.1 feature set (ported 2026-10): every GUI class a new mixin targets
                    "net.minecraft.client.gui.screen.Screen",
                    "net.minecraft.client.gui.screen.option.GameOptionsScreen",
                    "net.minecraft.client.gui.widget.OptionListWidget$WidgetEntry",
                    "net.minecraft.client.gui.widget.CyclingButtonWidget",
                    "net.minecraft.client.gui.screen.option.ControlsListWidget$KeyBindingEntry",
                    "net.minecraft.client.gui.screen.option.ControlsListWidget$CategoryEntry",
                    "net.minecraft.client.gui.screen.option.LanguageOptionsScreen$LanguageSelectionListWidget$LanguageEntry",
                    "net.minecraft.client.font.TextRenderer",
                    "net.minecraft.client.render.GameRenderer",
                    "net.minecraft.client.gui.tab.TabManager",
                    "net.minecraft.client.gui.DrawContext",
                    "net.minecraft.client.gui.widget.EntryListWidget",
                    "net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget$ServerEntry",
                    "net.minecraft.client.gui.widget.TextFieldWidget",
                    "net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen",
                    "net.minecraft.client.gui.screen.ingame.BookEditScreen",
                    "net.minecraft.client.gui.screen.ChatInputSuggestor",
                    "net.minecraft.client.gui.screen.ChatInputSuggestor$SuggestionWindow",
                    "net.minecraft.client.gui.hud.InGameHud",
                    "net.minecraft.client.gui.hud.ChatHud",
                    "net.minecraft.client.gui.screen.ChatScreen",
                    "net.minecraft.client.gui.hud.PlayerListHud",
                    "net.minecraft.client.gui.hud.BossBarHud",
                    "net.minecraft.client.gui.screen.ingame.HandledScreen",
                    "net.minecraft.client.gui.screen.ingame.MerchantScreen",
                    "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
                    "net.minecraft.client.gui.screen.ingame.LoomScreen",
                    "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                    "net.minecraft.client.gui.screen.advancement.AdvancementsScreen",
                    "net.minecraft.client.gui.screen.recipebook.RecipeBookWidget",
                    "net.minecraft.client.gui.screen.recipebook.RecipeBookResults",
                    "net.minecraft.client.gui.screen.recipebook.AnimatedResultButton",
                    "net.minecraft.client.gui.widget.TexturedButtonWidget",
                    "net.minecraft.client.gui.widget.ToggleButtonWidget",
                    "net.minecraft.client.gui.widget.TabButtonWidget",
                    "net.minecraft.client.gui.widget.PressableWidget",
                    "net.minecraft.client.gui.screen.SplashOverlay",
                    "net.minecraft.client.gui.screen.LevelLoadingScreen",
                    "net.minecraft.client.gui.screen.ProgressScreen",
                    "net.minecraft.client.gui.screen.ConnectScreen",
                    "net.minecraft.client.gui.screen.TaskScreen",
                    "net.minecraft.client.gui.screen.world.WorldListWidget$WorldEntry",
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
        // The 1.20.1 equivalent of 26.2's HudDriverMixin: VISIBILITY (not the raw enabled flag) decides drawing, so a
        // module switched on fades in and one switched off keeps drawing while it fades out. Each module is scaled about
        // its own HudBounds centre (0.85 -> 1 ease-out) and HudFade.alpha is published for the draw helpers (HudText /
        // HudGlass / Silhouette) to multiply. The scale is applied through the ctx MatrixStack, which carries text,
        // fills and item icons; the raw-GL liquid-glass backgrounds (drawn under the identity RenderSystem model-view
        // in the HUD pass) can't follow that matrix, so they only alpha-fade — see the per-module notes. Each module
        // runs in its own matrix push/pop inside a try/catch, so one that throws or leaves the stack unbalanced can't
        // shift the next, and HudFade.alpha is always restored to 1.
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player == null) return;
            for (Module m : ModuleManager.all()) {
                if (!(m instanceof HudRenderer)) continue;
                float vis = HudFade.visibility(m, m.enabled);
                if (vis <= 0.004f) continue;
                ctx.getMatrices().push();
                try {
                    if (vis < 1f && m instanceof HudBounds) {
                        HudBounds hb = (HudBounds) m;
                        float s = HudFade.SCALE_FROM + (1f - HudFade.SCALE_FROM) * HudFade.easeOut(vis);
                        float cx = hb.hudX() + hb.hudW() * 0.5f, cy = hb.hudY() + hb.hudH() * 0.5f;
                        ctx.getMatrices().translate(cx, cy, 0f);
                        ctx.getMatrices().scale(s, s, 1f);
                        ctx.getMatrices().translate(-cx, -cy, 0f);
                    }
                    HudFade.alpha = vis;
                    ((HudRenderer) m).renderHud(ctx);
                } catch (Throwable t) {
                    /* one bad module never breaks the HUD */
                } finally {
                    HudFade.alpha = 1f;
                    ctx.getMatrices().pop();
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
