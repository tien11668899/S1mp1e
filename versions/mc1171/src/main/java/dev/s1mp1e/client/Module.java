package dev.s1mp1e.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every S1mp1e client feature.
 *
 * <p>Scope reminder for anyone extending this: modules are renders, HUD and
 * feedback only. A module never automates a player action and never sends or
 * alters a packet — it draws, or it changes how something already on screen
 * looks. That constraint is what keeps the client auditable.
 *
 * <p>Settings are declared as fields initialised through {@link #add(Setting)},
 * which registers and returns the setting in one expression, so a subclass reads
 * as a plain list of declarations:
 * <pre>
 *   public final Setting size = add(Setting.number("Size", 1.0, 0.5, 2.0));
 * </pre>
 * Declaration order is the order the ClickGUI draws them in.
 */
public abstract class Module {

    /** Display name; also the config key and the {@link ModuleManager} lookup key. */
    public final String name;
    /** "Combat" | "HUD" | "Visual" — the ClickGUI panel this lands in. */
    public final String category;

    public boolean enabled;

    public final List<Setting> settings = new ArrayList<Setting>();

    protected Module(String name, String category) {
        this.name     = name;
        this.category = category;
    }

    /** Registers {@code s} and hands it straight back, for field initialisers. */
    protected Setting add(Setting s) {
        settings.add(s);
        return s;
    }

    public void onEnable()  {}
    public void onDisable() {}

    /**
     * Toggles the module and fires the callbacks, but only on a real change —
     * config load, the ClickGUI and keybinds all funnel through here, and a
     * module that allocates in {@link #onEnable()} must not do it twice.
     */
    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        try {
            if (value) onEnable();
            else       onDisable();
        } catch (Throwable t) {
            // A module blowing up in its own callback must not take the client
            // with it; leave the flag as set and log.
            System.out.println("[S1mp1e] module '" + name + "' threw on "
                    + (value ? "enable" : "disable") + ": " + t);
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** @return the setting with that display name, or null. */
    public Setting setting(String settingName) {
        for (int i = 0; i < settings.size(); i++) {
            Setting s = settings.get(i);
            if (s.name.equalsIgnoreCase(settingName)) return s;
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
