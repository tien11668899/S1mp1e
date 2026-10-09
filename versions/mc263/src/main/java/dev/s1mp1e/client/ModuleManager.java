package dev.s1mp1e.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.s1mp1e.client.module.ArmorHudModule;
import dev.s1mp1e.client.module.BlockOutlineModule;
import dev.s1mp1e.client.module.ChromaHudModule;
import dev.s1mp1e.client.module.HitMarkerModule;
import dev.s1mp1e.client.module.AttackRingModule;
import dev.s1mp1e.client.module.LowFireModule;
import dev.s1mp1e.client.module.CoordinatesHudModule;
import dev.s1mp1e.client.module.CpsModule;
import dev.s1mp1e.client.module.CrosshairModule;
import dev.s1mp1e.client.module.FpsHudModule;
import dev.s1mp1e.client.module.FullbrightModule;
import dev.s1mp1e.client.module.HandPositionModule;
import dev.s1mp1e.client.module.HungerSaturationHudModule;
import dev.s1mp1e.client.module.InventoryHudModule;
import dev.s1mp1e.client.module.KeystrokesHudModule;
import dev.s1mp1e.client.module.NoHurtCamModule;
import dev.s1mp1e.client.module.OldAnimationsModule;
import dev.s1mp1e.client.module.PotionHudModule;
import dev.s1mp1e.client.module.SteadyFovModule;
import dev.s1mp1e.client.module.XpFlowHudModule;
import dev.s1mp1e.client.module.ZoomModule;

/**
 * The single registry every other subsystem reads from — ClickGUI, keybinds, the HUD driver and the config
 * writer all iterate {@link #all()}.
 *
 * <p>Registration order is the order things are drawn, so it is fixed here by hand rather than derived from a
 * scan; there is no classpath scanning at all, which keeps mod startup off the disk. The order matches mc1211
 * exactly, so the ClickGUI lists read the same on every version.
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
     * <p>Each construction is guarded on its own: a module that fails to load (missing class after a partial
     * build, a constructor that touches an API that moved) must cost only that one feature, never the whole
     * client.
     */
    public static void init() {
        if (initialised) return;
        initialised = true;

        add("CpsModule",           safeCps());
        add("CrosshairModule",     safeCrosshair());
        add("ArmorHudModule",      safeArmorHud());
        add("PotionHudModule",     safePotionHud());
        // 26.3: OldAnimations「使用中仍揮手」靠 HeldItemSwingMixin 的 renderItem 注入點，
        // 26.3 第一人稱渲染改成 render-state API、該注入點消失 → 先停用待重寫（HeldItemSwingMixin 已從 mixins.json 移除）。
        // add("OldAnimationsModule", safeOldAnimations());
        add("NoHurtCamModule",     safeNoHurtCam());
        add("FpsHudModule",        safeFpsHud());
        add("CoordsHudModule",     safeCoordsHud());
        add("InventoryHudModule",  safeInventoryHud());
        add("HungerHudModule",     safeHungerHud());
        add("KeystrokesModule",    safeKeystrokes());
        add("HandPositionModule",  safeHandPosition());
        add("SteadyFovModule",     safeSteadyFov());
        add("ZoomModule",          safeZoom());
        add("FullbrightModule",    safeFullbright());
        add("XpFlowModule",        safeXpFlow());
        // 26.2-first additions (appended so the mc1211 order above is unchanged on every version)
        add("BlockOutlineModule",  safeBlockOutline());
        add("ChromaHudModule",     safeChromaHud());
        // combat visuals (26.2 first): fire overlay, attack-cooldown glass ring, hit marker
        add("LowFireModule",       safeLowFire());
        add("AttackRingModule",    safeAttackRing());
        add("HitMarkerModule",     safeHitMarker());
        // particles: one switch for every particle (reduced share or none)
        add("ParticlesModule",     safeParticles());
        // name-tag plate: glass (real refraction) / vanilla / off
        add("NameTagModule",       safeNameTag());
        // 正在播放：靈動島外觀已棄用（改做 iOS 控制中心「正在播放」玻璃卡，打開畫面才顯示）。
        // MediaClient/AlbumArt/DynamicIslandModule 類別先保留給卡片重用，但不再註冊成 HUD 模組。
        // add("DynamicIslandModule", safeDynamicIsland());

        S1mp1eConfig.load();

        // load() writes the `enabled` flag straight onto the field so it can stay quiet about modules it does
        // not know; fire the enable callbacks once here, after everything is registered and populated.
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

    private static Module safeDynamicIsland() {
        try { return new dev.s1mp1e.client.module.DynamicIslandModule(); } catch (Throwable t) { return fail("DynamicIslandModule", t); }
    }

    private static void add(String label, Module m) {
        if (m == null) {
            System.out.println("[S1mp1e] module " + label + " unavailable — skipped");
            return;
        }
        register(m);
    }

    // Separate methods rather than one big try block: a failure in the middle of a shared block would silently
    // drop every module after it.

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

    private static Module safeFpsHud() {
        try { return new FpsHudModule(); } catch (Throwable t) { return fail("FpsHudModule", t); }
    }

    private static Module safeCoordsHud() {
        try { return new CoordinatesHudModule(); } catch (Throwable t) { return fail("CoordinatesHudModule", t); }
    }

    private static Module safeInventoryHud() {
        try { return new InventoryHudModule(); } catch (Throwable t) { return fail("InventoryHudModule", t); }
    }

    private static Module safeHungerHud() {
        try { return new HungerSaturationHudModule(); } catch (Throwable t) { return fail("HungerHudModule", t); }
    }

    private static Module safeKeystrokes() {
        try { return new KeystrokesHudModule(); } catch (Throwable t) { return fail("KeystrokesModule", t); }
    }

    private static Module safeHandPosition() {
        try { return new HandPositionModule(); } catch (Throwable t) { return fail("HandPositionModule", t); }
    }

    private static Module safeSteadyFov() {
        try { return new SteadyFovModule(); } catch (Throwable t) { return fail("SteadyFovModule", t); }
    }

    private static Module safeZoom() {
        try { return new ZoomModule(); } catch (Throwable t) { return fail("ZoomModule", t); }
    }

    private static Module safeFullbright() {
        try { return new FullbrightModule(); } catch (Throwable t) { return fail("FullbrightModule", t); }
    }

    private static Module safeXpFlow() {
        try { return new XpFlowHudModule(); } catch (Throwable t) { return fail("XpFlowModule", t); }
    }

    private static Module safeBlockOutline() {
        try { return new BlockOutlineModule(); } catch (Throwable t) { return fail("BlockOutlineModule", t); }
    }

    private static Module safeChromaHud() {
        try { return new ChromaHudModule(); } catch (Throwable t) { return fail("ChromaHudModule", t); }
    }

    private static Module safeParticles() {
        try { return new dev.s1mp1e.client.module.ParticlesModule(); } catch (Throwable t) { return fail("ParticlesModule", t); }
    }

    private static Module safeNameTag() {
        try { return new dev.s1mp1e.client.module.NameTagModule(); } catch (Throwable t) { return fail("NameTagModule", t); }
    }

    private static Module safeLowFire() {
        try { return new LowFireModule(); } catch (Throwable t) { return fail("LowFireModule", t); }
    }

    private static Module safeAttackRing() {
        try { return new AttackRingModule(); } catch (Throwable t) { return fail("AttackRingModule", t); }
    }

    private static Module safeHitMarker() {
        try { return new HitMarkerModule(); } catch (Throwable t) { return fail("HitMarkerModule", t); }
    }

    private static Module fail(String label, Throwable t) {
        System.out.println("[S1mp1e] failed to construct " + label + ": " + t);
        return null;
    }
}
