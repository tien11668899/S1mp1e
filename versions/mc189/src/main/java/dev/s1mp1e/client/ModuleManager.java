package dev.s1mp1e.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.s1mp1e.client.module.ArmorHudModule;
import dev.s1mp1e.client.module.CpsModule;
import dev.s1mp1e.client.module.CrosshairModule;
import dev.s1mp1e.client.module.NoHurtCamModule;
import dev.s1mp1e.client.module.OldAnimationsModule;
import dev.s1mp1e.client.module.PotionHudModule;

/**
 * The single registry every other subsystem reads from — ClickGUI, keybinds,
 * HUD handlers and the config writer all iterate {@link #all()}.
 *
 * <p>Registration order is the order things are drawn, so it is fixed here by
 * hand rather than derived from a scan; there is no classpath scanning at all,
 * which keeps mod startup off the disk.
 */
public final class ModuleManager {

    private static final List<Module> MODULES = new ArrayList<Module>();
    private static final List<Module> VIEW    = Collections.unmodifiableList(MODULES);

    private static boolean initialised;

    private ModuleManager() {}

    public static void register(Module m) {
        if (m == null) return;
        // A duplicate name would make byName() and the config file ambiguous.
        if (byName(m.name) != null) {
            System.out.println("[S1mp1e] duplicate module name ignored: " + m.name);
            return;
        }
        MODULES.add(m);
    }

    public static List<Module> all() {
        return VIEW;
    }

    /** @return the module with that display name, or null. */
    public static Module byName(String name) {
        if (name == null) return null;
        for (int i = 0; i < MODULES.size(); i++) {
            Module m = MODULES.get(i);
            if (m.name.equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    /** @return every module in {@code category}, in registration order. */
    public static List<Module> byCategory(String category) {
        List<Module> out = new ArrayList<Module>();
        for (int i = 0; i < MODULES.size(); i++) {
            Module m = MODULES.get(i);
            if (m.category.equalsIgnoreCase(category)) out.add(m);
        }
        return out;
    }

    /**
     * Builds the registry, then applies the saved config over the defaults.
     *
     * <p>Each construction is guarded on its own: a module that fails to load
     * (missing class after a partial build, a constructor that touches an API
     * that moved) must cost only that one feature, never the whole client.
     */
    public static void init() {
        if (initialised) return;
        initialised = true;

        add("CpsModule",           safeCps());
        add("CrosshairModule",     safeCrosshair());
        add("ArmorHudModule",      safeArmorHud());
        add("PotionHudModule",     safePotionHud());
        add("OldAnimationsModule", safeOldAnimations());
        add("NoHurtCamModule",     safeNoHurtCam());

        S1mp1eConfig.load();

        // load() writes the `enabled` flag straight onto the field so it can
        // stay quiet about modules it does not know; fire the enable callbacks
        // once here, after everything is registered and populated.
        for (int i = 0; i < MODULES.size(); i++) {
            Module m = MODULES.get(i);
            if (!m.enabled) continue;
            try {
                m.onEnable();
            } catch (Throwable t) {
                System.out.println("[S1mp1e] module '" + m.name + "' threw on enable: " + t);
            }
        }

        System.out.println("[S1mp1e] " + MODULES.size() + " modules registered");
    }

    private static void add(String label, Module m) {
        if (m == null) {
            System.out.println("[S1mp1e] module " + label + " unavailable — skipped");
            return;
        }
        register(m);
    }

    // Separate methods rather than one big try block: a failure in the middle
    // of a shared block would silently drop every module after it.

    private static Module safeCps() {
        try { return new CpsModule(); } catch (Throwable t) { return fail("CpsModule", t); }
    }

    private static Module safeCrosshair() {
        try { return new CrosshairModule(); } catch (Throwable t) { return fail("CrosshairModule", t); }
    }

    private static Module safeArmorHud() {
        try { return new ArmorHudModule(); } catch (Throwable t) { return fail("ArmorHudModule", t); }
    }

    private static Module safePotionHud() {
        try { return new PotionHudModule(); } catch (Throwable t) { return fail("PotionHudModule", t); }
    }

    private static Module safeOldAnimations() {
        try { return new OldAnimationsModule(); } catch (Throwable t) { return fail("OldAnimationsModule", t); }
    }

    private static Module safeNoHurtCam() {
        try { return new NoHurtCamModule(); } catch (Throwable t) { return fail("NoHurtCamModule", t); }
    }

    private static Module fail(String label, Throwable t) {
        System.out.println("[S1mp1e] failed to construct " + label + ": " + t);
        return null;
    }
}
