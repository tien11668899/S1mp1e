package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;

/**
 * CS2 knives on swords: while a sword is in the main hand, the first-person view shows the chosen CS2 knife with
 * CS2's own first-person animations (draw, idle, light/heavy, backstab, inspect) on CS2 arms/gloves. Assets come from
 * the user's own CS2 install via the knife pack ({@code <gameDir>/s1mp1e-knives}). Cosmetic only: never changes
 * reach, damage, cooldown or what the server sees.
 */
public final class KnifeModule extends Module {

    private static KnifeModule instance;

    public static final String[] KNIVES = {
        "knife_karambit", "knife_m9", "knife_butterfly", "knife_bayonet", "knife_talon", "knife_stiletto",
        "knife_skeleton", "knife_ursus", "knife_flip", "knife_gut", "knife_falchion", "knife_bowie",
        "knife_tactical", "knife_push", "knife_navaja", "knife_outdoor", "knife_canis", "knife_cord",
        "knife_css", "knife_kukri", "knife_default_ct", "knife_default_t",
    };
    public static final String[] GLOVES = {
        "default", "glove_sporty", "glove_specialist", "glove_slick", "glove_motorcycle", "glove_handwrap",
        "glove_hydra", "glove_bloodhound", "glove_brokenfang", "glove_fullfinger", "glove_fingerless", "glove_hardknuckle",
    };

    /** CS2 knife finishes (pack catalog keys); a finish a knife never had falls back to its vanilla look. */
    public static final String[] FINISHES = {
        "vanilla", "fade", "crimson_web", "case_hardened", "slaughter", "night",
        "blue_steel", "stained", "safari_mesh", "boreal_forest", "forest_ddpat", "scorched",
        "urban_masked", "doppler_p1", "doppler_p2", "doppler_p3", "doppler_p4", "ruby",
        "sapphire", "black_pearl", "tiger_tooth", "marble_fade", "damascus", "rust_coat",
        "ultraviolet", "gamma_p1", "gamma_p2", "gamma_p3", "gamma_p4", "emerald",
        "lore", "autotronic", "black_laminate", "bright_water", "freehand",
    };

    public final Setting knife   = add(Setting.mode("Knife", "knife_karambit", KNIVES));
    public final Setting skin    = add(Setting.mode("Skin", "vanilla", FINISHES));
    /** CS2 float wear (0 = Factory New ... 1 = Battle-Scarred), clamped to the finish's own range. */
    public final Setting wear    = add(Setting.number("Wear", 0.01D, 0.0D, 1.0D));
    /** CS2 paint seed ("pattern template", 0-1000): rolls the pattern / wear / grunge placement exactly like CS2. */
    public final Setting seed    = add(Setting.integer("Pattern seed", 0, 0, 1000));
    /** Whose arms hold the knife: CS2's arms/gloves, or the player's own Minecraft skin arms. */
    public final Setting arms    = add(Setting.mode("Arms", "cs2_arms", "cs2_arms", "mc_arms"));
    /** Show the CS2 gloved arms in ALL first-person contexts (empty hand, other items, eating, blocking...), not just the knife. */
    public final Setting allArms = add(Setting.bool("CS arms everywhere", false));
    // empty-hand boxing (CS arms everywhere): guard / straight punch / dig, live-tunable
    public final Setting guardX    = add(Setting.number("Guard X", 0.19D, 0.0D, 0.45D));
    public final Setting guardY    = add(Setting.number("Guard height", -0.35D, -0.70D, 0.10D));
    public final Setting guardZ    = add(Setting.number("Guard distance", 0.35D, 0.10D, 0.80D));
    public final Setting guardRoll = add(Setting.number("Guard angle", 0.0D, -180.0D, 360.0D));
    public final Setting punchX    = add(Setting.number("Punch X", 0.15D, 0.0D, 0.45D));
    public final Setting punchY    = add(Setting.number("Punch height", -0.06D, -0.60D, 0.20D));
    public final Setting punchZ    = add(Setting.number("Punch distance", 0.80D, 0.20D, 1.20D));
    public final Setting punchRoll = add(Setting.number("Punch angle", 90.0D, -180.0D, 360.0D));
    public final Setting shoulderDrive = add(Setting.number("Shoulder drive", 0.15D, 0.0D, 0.40D));
    public final Setting punchSpeed    = add(Setting.number("Punch out time", 0.30D, 0.10D, 0.70D));
    public final Setting digLift       = add(Setting.number("Dig lift", 0.33D, 0.0D, 0.60D));
    public final Setting digReach      = add(Setting.number("Dig reach", 0.27D, 0.0D, 0.60D));
    public final Setting gloves  = add(Setting.mode("Gloves", "default", GLOVES));
    /** CS2 glove paint kit (chosen in the knife locker); options come from the user's pack. */
    public final Setting gloveSkin = add(Setting.mode("Glove skin", "vanilla", gloveSkinOptions()).hide());
    public final Setting gloveWear = add(Setting.number("Glove wear", 0.01D, 0.0D, 1.0D).hide());
    /** CS2 viewmodel_fov (horizontal at 4:3, like the cvar). */
    public final Setting fov     = add(Setting.number("Viewmodel FOV", 68.0D, 54.0D, 90.0D));
    /** CS2 viewmodel_offset_x/y/z, inches. */
    public final Setting offX    = add(Setting.number("Offset X", 1.0D, -5.0D, 5.0D));
    public final Setting offY    = add(Setting.number("Offset Y", 1.0D, -5.0D, 5.0D));
    public final Setting offZ    = add(Setting.number("Offset Z", -1.0D, -5.0D, 5.0D));
    public final Setting inspect = add(Setting.bool("Inspect on F", true));
    public final Setting heavy   = add(Setting.bool("Heavy on right click", true));
    /** CS2 knife SFX (deploy / inspect / slash / hit / backstab) from the user's own CS2 audio in the pack. */
    public final Setting sounds  = add(Setting.bool("Sounds", true));
    public final Setting soundVolume = add(Setting.number("Sound volume", 1.0D, 0.0D, 1.0D));
    /** Opens the CS2-inventory-style knife locker (LWJGL key code; 48 = B, CS2's buy-menu key). */
    public final Setting lockerKey = add(Setting.integer("Locker key", 48, 0, 255));

    public KnifeModule() {
        super("CS2Knife", "Visual");
        instance = this;
        // dev-only (DevShot): S1MP1E_KNIFE_ON forces the module on so the harness screenshot shows the knife.
        if (System.getenv("S1MP1E_KNIFE_ON") != null) this.enabled = true;
    }

    @Override
    public void onEnable() {
        // dev-only (DevShot): applied here, after the config load, so the saved choice doesn't overwrite them
        devSet(skin, "S1MP1E_KNIFE_SKIN");
        devSet(knife, "S1MP1E_KNIFE_NAME");
        devSet(gloves, "S1MP1E_KNIFE_GLOVE");
        devSet(gloveSkin, "S1MP1E_GLOVE_SKIN");
        dev.s1mp1e.o.event.MinecraftForge.EVENT_BUS.register(dev.s1mp1e.o.knife.KnifeInput.INSTANCE);
    }

    @Override
    public void onDisable() {
        dev.s1mp1e.o.event.MinecraftForge.EVENT_BUS.unregister(dev.s1mp1e.o.knife.KnifeInput.INSTANCE);
    }

    private static void devSet(Setting s, String env) {
        String v = System.getenv(env);
        if (v != null && !v.isEmpty()) s.modeValue = v;
    }

    public static KnifeModule get() { return instance; }

    /** Re-read the glove skin options (the pack may have been built after launch). */
    public void refreshGloveSkins() {
        String[] opts = gloveSkinOptions();
        if (opts.length > gloveSkin.modes.length) gloveSkin.modes = opts;
    }

    /** "vanilla" + every glove skin in the pack (read at construction so a saved choice survives config load). */
    private static String[] gloveSkinOptions() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("vanilla");
        try {
            java.nio.file.Path cat = dev.s1mp1e.o.knife.KnifePack.root()
                    .resolve("skins").resolve("gloves").resolve("catalog.json");
            out.addAll(dev.s1mp1e.o.knife.GloveSkins.allSkinNames(cat));
        } catch (Throwable ignored) {}
        return out.toArray(new String[0]);
    }

    public static boolean active() {
        KnifeModule m = instance;
        return m != null && m.enabled;
    }
}
