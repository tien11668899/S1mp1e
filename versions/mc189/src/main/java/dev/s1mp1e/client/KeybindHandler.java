package dev.s1mp1e.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class KeybindHandler {

    private static final String CATEGORY = "S1mp1e";
    /** LWJGL's "unbound" code — see Keyboard.KEY_NONE. */
    private static final int KEY_NONE = 0;

    private final List<Entry> entries = new ArrayList<Entry>();

    private static final class Entry {
        final KeyBinding key;
        final Module     module;
        Entry(KeyBinding key, Module module) { this.key = key; this.module = module; }
    }

    /** Registers a binding for every module currently in the registry. */
    public void register() {
        for (Module m : ModuleManager.all()) {
            KeyBinding kb = new KeyBinding("key.s1mp1e." + slug(m.name), KEY_NONE, CATEGORY);
            ClientRegistry.registerKeyBinding(kb);
            entries.add(new Entry(kb, m));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        // END only: the binding's press queue is drained once per tick, and
        // polling in both phases would consume a press twice.
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

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
                ? EnumChatFormatting.GREEN + "開啟"
                : EnumChatFormatting.RED   + "關閉";
        mc.thePlayer.addChatMessage(new ChatComponentText(
                EnumChatFormatting.AQUA + "[S1mp1e] " + EnumChatFormatting.RESET
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
