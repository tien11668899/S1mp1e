package dev.s1mp1e.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One rebindable key per module, so the suite is usable before a settings GUI
 * exists.
 *
 * <p>Every binding is registered into vanilla's Controls screen under an
 * "S1mp1e" category, which means the user discovers and rebinds them exactly
 * where they'd look for any other keybind — no JSON editing, no custom UI.
 * Bindings default to {@code KEY_NONE} so a fresh install never steals a key
 * the player already uses; they opt in by assigning one.
 *
 * <p>The toggle writes straight through to {@link S1mp1eConfig}, so a change
 * made mid-game survives the next launch.
 *
 * <p>1.12.2 deltas vs the 1.8.9 reference: chat feedback goes through
 * {@code mc.player.sendMessage(new TextComponentString(...))} with
 * {@link TextFormatting} colour codes, and the global-font install writes onto
 * the renamed {@code mc.fontRenderer}. A fresh {@link dev.s1mp1e.client.gui.S1mp1eFontRenderer}
 * has no glyph widths until {@code onResourceManagerReload} runs, so the swap
 * calls it once and registers the renderer as a reload listener (so the vanilla
 * fallback path keeps valid widths) before assigning it.
 */
public final class KeybindHandler {

    private static final String CATEGORY = "S1mp1e";
    /** LWJGL's "unbound" code — see Keyboard.KEY_NONE. */
    private static final int KEY_NONE = 0;

    private final List<Entry> entries = new ArrayList<Entry>();

    /** module name -> its vanilla toggle KeyBinding, so the config GUI can rebind it. */
    private static final Map<String, KeyBinding> BINDINGS = new HashMap<String, KeyBinding>();
    public static KeyBinding bindingFor(String moduleName) { return BINDINGS.get(moduleName); }

    /** register() must run exactly once, however many handlers get built. */
    private static boolean registered;

    /** Edge-detect state for the menu-open key. */
    private boolean menuWasDown;
    /** Require the menu key to be seen RELEASED once before the first open is allowed. LWJGL can
     *  report a key still "down" from before the window took focus (e.g. a modifier held while
     *  launching), which would otherwise auto-open the config right after entering a world. */
    private boolean menuArmed;
    /** Our global PingFang FontRenderer, built once and re-asserted whenever another mod displaces it. */
    private dev.s1mp1e.client.gui.S1mp1eFontRenderer glassFont;
    /** Set if constructing the renderer failed, so a broken build isn't retried every tick. */
    private boolean fontFailed;
    /** Log the install line only once (the swap itself may re-run if displaced). */
    private boolean fontLogged;

    private static final class Entry {
        final KeyBinding key;
        final Module     module;
        Entry(KeyBinding key, Module module) { this.key = key; this.module = module; }
    }

    /** Registers a binding for every module currently in the registry. Guarded so a second
     *  call (e.g. an accidental double-wiring) never registers a duplicate KeyBinding. */
    public void register() {
        if (registered) return;
        registered = true;
        for (Module m : ModuleManager.all()) {
            KeyBinding kb = new KeyBinding("key.s1mp1e." + slug(m.name), KEY_NONE, CATEGORY);
            ClientRegistry.registerKeyBinding(kb);
            entries.add(new Entry(kb, m));
            BINDINGS.put(m.name, kb);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        // END only: the binding's press queue is drained once per tick, and
        // polling in both phases would consume a press twice.
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        // Install — and RE-ASSERT — the global PingFang font over mc.fontRenderer, so ALL vanilla
        // text (chat, item names, GUIs…) matches the launcher. Built once (a fresh FontRenderer has
        // no glyph widths until onResourceManagerReload runs, and it's registered as a reload
        // listener so its widths stay valid across reloads even while displaced), then re-installed
        // on any later tick where another font mod (OptiFine Custom Fonts, Smooth Font) or a
        // resource-reload path has replaced mc.fontRenderer — so a competing reloader can't
        // permanently drop PingFang. In steady state the instanceof check is a no-op (no re-assign,
        // no flicker); we only reassign when it is NOT already ours.
        if (!fontFailed
                && mc.fontRenderer != null
                && !(mc.fontRenderer instanceof dev.s1mp1e.client.gui.S1mp1eFontRenderer)
                && dev.s1mp1e.client.gui.GlassFont.available()) {
            try {
                if (glassFont == null) {
                    glassFont = new dev.s1mp1e.client.gui.S1mp1eFontRenderer(mc);
                    glassFont.onResourceManagerReload(mc.getResourceManager());
                    try {
                        if (mc.getResourceManager() instanceof IReloadableResourceManager) {
                            ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(glassFont);
                        }
                    } catch (Throwable ignored) { /* registration failure must not lose the swap */ }
                }
                mc.fontRenderer = glassFont;
                if (!fontLogged) { fontLogged = true; System.out.println("[S1mp1e] global PingFang font installed"); }
            } catch (Throwable t) {
                fontFailed = true;   // don't retry a failing construction every tick
                System.out.println("[S1mp1e] font swap failed, keeping vanilla font: " + t);
            }
        }

        // Menu-open key (default RightShift, launcher/GUI configurable). Edge-detected so
        // a held key opens once; polled before the in-game guard so it works from menus too.
        // Guarded like the newer versions: the window must be active and the key must have
        // been observed released once (menuArmed), so a stale "down" state carried over from
        // launch can't auto-open the config a few seconds after entering a world.
        int mkc = S1mp1eConfig.getMenuKey();
        boolean md = mkc > 0 && org.lwjgl.opengl.Display.isActive() && Keyboard.isKeyDown(mkc);
        if (!md) menuArmed = true;   // released -> real presses from now on are intentional
        if (menuArmed && md && !menuWasDown && mc.currentScreen == null) {
            mc.displayGuiScreen(new S1mp1eConfigScreen());
        }
        menuWasDown = md;

        if (mc.player == null) return;

        for (int i = 0; i < entries.size(); i++) {
            Entry en = entries.get(i);
            // isPressed() pops one queued press, so a held key toggles once.
            if (!en.key.isPressed()) continue;
            try {
                en.module.toggle();
                S1mp1eConfig.save();
                say(mc, en.module);
            } catch (Throwable t) {
                // A misbehaving module must never take the client down mid-game.
                System.out.println("[S1mp1e] toggle of " + en.module.name + " failed: " + t);
            }
        }
    }

    private static void say(Minecraft mc, Module m) {
        String state = m.enabled
                ? TextFormatting.GREEN + "開啟"
                : TextFormatting.RED   + "關閉";
        mc.player.sendMessage(new TextComponentString(
                TextFormatting.AQUA + "[S1mp1e] " + TextFormatting.RESET
                        + m.name + " " + state));
    }

    /** "Old Animations" -> "old_animations", so the lang key is well-formed. */
    private static String slug(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }
}
