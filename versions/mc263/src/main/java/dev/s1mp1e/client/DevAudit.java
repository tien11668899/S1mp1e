package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=audit}: open EVERY vanilla screen class of this version, one after another, and shoot
 * each ({@code aNNN-Name.png}) so a human can check what is not liquid glass yet. Screens are built by reflection — every
 * constructor parameter is filled with a harmless stand-in (the player's inventory, a fresh client-side container menu,
 * a pause screen as parent, empty callbacks via {@link Proxy}…); a class that cannot be built is logged and skipped.
 *
 * <p>Progress is written to {@code audit-progress.txt} BEFORE each screen opens, so a crash inside one screen only loses
 * that screen: rerun with {@code S1MP1E_AUDIT_FROM=<n+1>}. Every result line goes to {@code audit-log.txt}.
 * Dev only — never reachable in a normal game (DevShot is inert without {@code S1MP1E_SHOT}).
 */
final class DevAudit {
    private DevAudit() {}

    static final String[] CLASSES = {
        "net.minecraft.client.gui.screens.AccessibilityOnboardingScreen",
        "net.minecraft.client.gui.screens.AlertScreen",
        "net.minecraft.client.gui.screens.BackupConfirmScreen",
        "net.minecraft.client.gui.screens.ChatScreen",
        "net.minecraft.client.gui.screens.ConfirmLinkScreen",
        "net.minecraft.client.gui.screens.ConfirmScreen",
        "net.minecraft.client.gui.screens.ConnectScreen",
        "net.minecraft.client.gui.screens.CreateBuffetWorldScreen",
        "net.minecraft.client.gui.screens.CreateFlatWorldScreen",
        "net.minecraft.client.gui.screens.CreditsAndAttributionScreen",
        "net.minecraft.client.gui.screens.DatapackLoadFailureScreen",
        "net.minecraft.client.gui.screens.DeathScreen",
        "net.minecraft.client.gui.screens.DirectJoinServerScreen",
        "net.minecraft.client.gui.screens.DisconnectedScreen",
        "net.minecraft.client.gui.screens.ErrorScreen",
        "net.minecraft.client.gui.screens.FileFixerAbortedScreen",
        "net.minecraft.client.gui.screens.GenericMessageScreen",
        "net.minecraft.client.gui.screens.GenericWaitingScreen",
        "net.minecraft.client.gui.screens.InBedChatScreen",
        "net.minecraft.client.gui.screens.LevelLoadingScreen",
        "net.minecraft.client.gui.screens.ManageServerScreen",
        "net.minecraft.client.gui.screens.NoticeWithLinkScreen",
        "net.minecraft.client.gui.screens.OutOfMemoryScreen",
        "net.minecraft.client.gui.screens.PauseScreen",
        "net.minecraft.client.gui.screens.PresetFlatWorldScreen",
        "net.minecraft.client.gui.screens.PrivacyConfirmLinkScreen",
        "net.minecraft.client.gui.screens.ProgressScreen",
        "net.minecraft.client.gui.screens.RecoverWorldDataScreen",
        "net.minecraft.client.gui.screens.TitleScreen",
        "net.minecraft.client.gui.screens.WinScreen",
        "net.minecraft.client.gui.screens.WorldOptionsScreen",
        "net.minecraft.client.gui.screens.achievement.StatsScreen",
        "net.minecraft.client.gui.screens.advancements.AdvancementsScreen",
        "net.minecraft.client.gui.screens.debug.DebugOptionsScreen",
        "net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen",
        "net.minecraft.client.gui.screens.dialog.ButtonListDialogScreen",
        "net.minecraft.client.gui.screens.dialog.DialogListDialogScreen",
        "net.minecraft.client.gui.screens.dialog.DialogScreen",
        "net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen",
        "net.minecraft.client.gui.screens.dialog.ServerLinksDialogScreen",
        "net.minecraft.client.gui.screens.dialog.SimpleDialogScreen",
        "net.minecraft.client.gui.screens.dialog.WaitingForResponseScreen",
        "net.minecraft.client.gui.screens.friends.FriendsListConfirmScreen",
        "net.minecraft.client.gui.screens.friends.FriendsOverlayScreen",
        "net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen",
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
        "net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen",
        "net.minecraft.client.gui.screens.inventory.AbstractMountInventoryScreen",
        "net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen",
        "net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen",
        "net.minecraft.client.gui.screens.inventory.AnvilScreen",
        "net.minecraft.client.gui.screens.inventory.BeaconScreen",
        "net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen",
        "net.minecraft.client.gui.screens.inventory.BookEditScreen",
        "net.minecraft.client.gui.screens.inventory.BookSignScreen",
        "net.minecraft.client.gui.screens.inventory.BookViewScreen",
        "net.minecraft.client.gui.screens.inventory.BrewingStandScreen",
        "net.minecraft.client.gui.screens.inventory.CartographyTableScreen",
        "net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen",
        "net.minecraft.client.gui.screens.inventory.ContainerScreen",
        "net.minecraft.client.gui.screens.inventory.CrafterScreen",
        "net.minecraft.client.gui.screens.inventory.CraftingScreen",
        "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen",
        "net.minecraft.client.gui.screens.inventory.DispenserScreen",
        "net.minecraft.client.gui.screens.inventory.EnchantmentScreen",
        "net.minecraft.client.gui.screens.inventory.FurnaceScreen",
        "net.minecraft.client.gui.screens.inventory.GrindstoneScreen",
        "net.minecraft.client.gui.screens.inventory.HangingSignEditScreen",
        "net.minecraft.client.gui.screens.inventory.HopperScreen",
        "net.minecraft.client.gui.screens.inventory.HorseInventoryScreen",
        "net.minecraft.client.gui.screens.inventory.InventoryScreen",
        "net.minecraft.client.gui.screens.inventory.ItemCombinerScreen",
        "net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen",
        "net.minecraft.client.gui.screens.inventory.LecternScreen",
        "net.minecraft.client.gui.screens.inventory.LoomScreen",
        "net.minecraft.client.gui.screens.inventory.MerchantScreen",
        "net.minecraft.client.gui.screens.inventory.MinecartCommandBlockEditScreen",
        "net.minecraft.client.gui.screens.inventory.NautilusInventoryScreen",
        "net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen",
        "net.minecraft.client.gui.screens.inventory.SignEditScreen",
        "net.minecraft.client.gui.screens.inventory.SmithingScreen",
        "net.minecraft.client.gui.screens.inventory.SmokerScreen",
        "net.minecraft.client.gui.screens.inventory.StonecutterScreen",
        "net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen",
        "net.minecraft.client.gui.screens.inventory.TestBlockEditScreen",
        "net.minecraft.client.gui.screens.inventory.TestInstanceBlockEditScreen",
        "net.minecraft.client.gui.screens.multiplayer.CodeOfConductScreen",
        "net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen",
        "net.minecraft.client.gui.screens.multiplayer.RestrictionsScreen",
        "net.minecraft.client.gui.screens.multiplayer.SafetyScreen",
        "net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen",
        "net.minecraft.client.gui.screens.multiplayer.WarningScreen",
        "net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen",
        "net.minecraft.client.gui.screens.options.ChatOptionsScreen",
        "net.minecraft.client.gui.screens.options.FontOptionsScreen",
        "net.minecraft.client.gui.screens.options.InWorldGameRulesScreen",
        "net.minecraft.client.gui.screens.options.LanguageSelectScreen",
        "net.minecraft.client.gui.screens.options.MouseSettingsScreen",
        "net.minecraft.client.gui.screens.options.OnlineOptionsScreen",
        "net.minecraft.client.gui.screens.options.OptionsScreen",
        "net.minecraft.client.gui.screens.options.OptionsSubScreen",
        "net.minecraft.client.gui.screens.options.SkinCustomizationScreen",
        "net.minecraft.client.gui.screens.options.SoundOptionsScreen",
        "net.minecraft.client.gui.screens.options.UnsupportedGraphicsWarningScreen",
        "net.minecraft.client.gui.screens.options.VideoSettingsScreen",
        "net.minecraft.client.gui.screens.options.controls.ControlsScreen",
        "net.minecraft.client.gui.screens.options.controls.KeyBindsScreen",
        "net.minecraft.client.gui.screens.packs.PackSelectionScreen",
        "net.minecraft.client.gui.screens.reporting.AbstractReportScreen",
        "net.minecraft.client.gui.screens.reporting.ChatReportScreen",
        "net.minecraft.client.gui.screens.reporting.ChatSelectionScreen",
        "net.minecraft.client.gui.screens.reporting.NameReportScreen",
        "net.minecraft.client.gui.screens.reporting.ReportPlayerScreen",
        "net.minecraft.client.gui.screens.reporting.ReportReasonSelectionScreen",
        "net.minecraft.client.gui.screens.reporting.SkinReportScreen",
        "net.minecraft.client.gui.screens.social.SocialInteractionsScreen",
        "net.minecraft.client.gui.screens.telemetry.TelemetryInfoScreen",
        "net.minecraft.client.gui.screens.worldselection.AbstractGameRulesScreen",
        "net.minecraft.client.gui.screens.worldselection.ConfirmExperimentalFeaturesScreen",
        "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen",
        "net.minecraft.client.gui.screens.worldselection.EditWorldScreen",
        "net.minecraft.client.gui.screens.worldselection.ExperimentsScreen",
        "net.minecraft.client.gui.screens.worldselection.FileFixerProgressScreen",
        "net.minecraft.client.gui.screens.worldselection.OptimizeWorldScreen",
        "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen",
        "net.minecraft.client.gui.screens.worldselection.WorldCreationGameRulesScreen",
    };

    private static final int SETTLE = 40, HOLD = 50;
    /** Not auditable by reflection: they answer a server dialog (a bogus answer gets us kicked for a protocol error). */
    /** S1MP1E_AUDIT_ONLY=Name,Name,…: visit just these screens (quick re-verification). */
    private static final java.util.Set<String> ONLY = onlySet();

    private static java.util.Set<String> onlySet() {
        String v = System.getenv("S1MP1E_AUDIT_ONLY");
        if (v == null || v.isBlank()) return null;
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String n : v.split(",")) if (!n.isBlank()) out.add(n.trim());
        return out;
    }

    private static final java.util.Set<String> EXCLUDE = java.util.Set.of("WaitingForResponseScreen");
    private static int idx = -1, frames;
    private static String current = "";

    private static File dir() {
        String d = System.getenv("S1MP1E_SHOT");
        return d == null ? new File(".") : new File(d.trim());
    }

    private static void log(String line) {
        try (FileWriter w = new FileWriter(new File(dir(), "audit-log.txt"), true)) {
            w.write(line + System.lineSeparator());
        } catch (Throwable ignored) {}
        System.out.println("[S1mp1e][Audit] " + line);
    }

    /** One frame of the sweep; true once every class was visited. */
    static boolean step(Minecraft mc) {
        if (idx < 0) {
            idx = 0;
            try {
                String from = System.getenv("S1MP1E_AUDIT_FROM");
                if (from != null && !from.isBlank()) idx = Integer.parseInt(from.trim());
            } catch (Throwable ignored) {}
        }
        if (idx >= CLASSES.length) {
            try { mc.gui.setScreen(null); } catch (Throwable ignored) {}
            log("DONE " + CLASSES.length);
            return true;
        }
        if (frames == 0) {
            if (mc.player == null || mc.getConnection() == null) {
                // A screen disconnected us (no world any more): stop, and let the runner resume AT this index.
                try (FileWriter w = new FileWriter(new File(dir(), "audit-progress.txt"), false)) {
                    w.write(Integer.toString(idx - 1));
                } catch (Throwable ignored) {}
                log("LOST-WORLD before " + idx + " (resume here)");
                return true;
            }
            try (FileWriter w = new FileWriter(new File(dir(), "audit-progress.txt"), false)) {
                w.write(Integer.toString(idx));
            } catch (Throwable ignored) {}
            String cn = CLASSES[idx];
            current = cn.substring(cn.lastIndexOf('.') + 1);
            if (ONLY != null && !ONLY.contains(current)) { idx++; return false; }
            if (EXCLUDE.contains(current)) {
                log(String.format("%03d %s EXCLUDED (needs real server data / sends packets)", idx, current));
                idx++;
                return false;
            }
            Screen s;
            try {
                s = build(mc, cn);
            } catch (Throwable t) {
                log(String.format("%03d %s BUILD-FAIL %s", idx, current, rootMsg(t)));
                idx++;
                return false;
            }
            if (s == null) {
                log(String.format("%03d %s SKIP abstract/not-a-screen", idx, current));
                idx++;
                return false;
            }
            if (!vanillaOpens) {
                try {
                    mc.gui.setScreen(s);
                } catch (Throwable t) {
                    log(String.format("%03d %s OPEN-FAIL %s", idx, current, rootMsg(t)));
                    forceClear(mc);
                    idx++;
                    return false;
                }
            }
        }
        frames++;
        int settle = vanillaOpens ? 160 : SETTLE, hold = vanillaOpens ? 170 : HOLD;
        if (frames == settle - 14) prep(mc);
        if (frames == settle) {
            Screen now = mc.gui.screen();
            String shown = now == null ? "none" : now.getClass().getSimpleName();
            DevShot.capture(mc, String.format("a%03d-%s.png", idx, current));
            log(String.format("%03d %s OK shown=%s", idx, current, shown));
        } else if (frames >= hold) {
            try { mc.gui.setScreen(null); } catch (Throwable t) { forceClear(mc); }
            vanillaOpens = false;
            frames = 0;
            idx++;
        }
        return false;
    }

    /** A screen whose init threw may still be current, and then every later setScreen fails in its removed():
     *  drop it from the Gui WITHOUT calling removed(). */
    private static void forceClear(Minecraft mc) {
        try {
            mc.gui.setScreen(null);
            return;
        } catch (Throwable ignored) {}
        try {
            for (Class<?> k = mc.gui.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (Screen.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        f.set(mc.gui, null);
                    }
                }
            }
        } catch (Throwable t) {
            log("forceClear failed: " + rootMsg(t));
        }
    }

    /** Before the capture: select a row in the first list (shows the selection glass) and nudge the first scrollable
     *  area (shows the overlay scroller, still up 1 s later at capture time). */
    private static void prep(Minecraft mc) {
        try {
            Screen s = mc.gui.screen();
            if (s == null) return;
            java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<Object>(s.children());
            boolean selected = false, scrolled = false;
            while (!q.isEmpty() && !(selected && scrolled)) {
                Object c = q.poll();
                if (!selected && c instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> l && !l.children().isEmpty()
                        && l.getSelected() == null) {
                    Class<?> ec = Class.forName("net.minecraft.client.gui.components.AbstractSelectionList$Entry");
                    Object e = l.children().get(Math.min(1, l.children().size() - 1));
                    net.minecraft.client.gui.components.AbstractSelectionList.class.getMethod("setSelected", ec).invoke(l, e);
                    selected = true;
                }
                if (!scrolled && c instanceof net.minecraft.client.gui.components.AbstractScrollArea a && a.maxScrollAmount() > 0) {
                    a.mouseScrolled(a.getX() + a.getWidth() / 2.0, a.getY() + a.getHeight() / 2.0, 0, -2);
                    scrolled = true;
                }
                if (c instanceof net.minecraft.client.gui.components.events.ContainerEventHandler ce) q.addAll(ce.children());
            }
        } catch (Throwable t) {
            log("prep failed: " + rootMsg(t));
        }
    }

    private static String rootMsg(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return r.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 140 ? m.substring(0, 140) : m));
    }

    /** Set when a special builder made vanilla open the screen itself (async): don't setScreen, wait longer. */
    private static boolean vanillaOpens;

    private static Screen special(Minecraft mc, String simple) throws Exception {
        var p = mc.player;
        var inv = p.getInventory();
        var lit = net.minecraft.network.chat.Component.literal("S1mp1e 液態玻璃");
        var parent = new net.minecraft.client.gui.screens.PauseScreen(true);
        switch (simple) {
            case "PauseScreen": return new net.minecraft.client.gui.screens.PauseScreen(true);
            case "CreativeModeInventoryScreen":
                return new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(p, p.connection.enabledFeatures(), true);
            case "ContainerScreen":
                return new net.minecraft.client.gui.screens.inventory.ContainerScreen(
                        net.minecraft.world.inventory.ChestMenu.threeRows(1, inv), inv, lit);
            case "BeaconScreen":
                return new net.minecraft.client.gui.screens.inventory.BeaconScreen(
                        new net.minecraft.world.inventory.BeaconMenu(1, new net.minecraft.world.SimpleContainer(1)), inv, lit);
            case "LecternScreen":
                return new net.minecraft.client.gui.screens.inventory.LecternScreen(
                        new net.minecraft.world.inventory.LecternMenu(1), inv, lit);
            case "SignEditScreen":
                return new net.minecraft.client.gui.screens.inventory.SignEditScreen(
                        new net.minecraft.world.level.block.entity.SignBlockEntity(net.minecraft.core.BlockPos.ZERO,
                                net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState()),
                        net.minecraft.world.level.block.entity.SignTextSlot.FRONT, false);
            case "HangingSignEditScreen":
                return new net.minecraft.client.gui.screens.inventory.HangingSignEditScreen(
                        new net.minecraft.world.level.block.entity.HangingSignBlockEntity(net.minecraft.core.BlockPos.ZERO,
                                net.minecraft.world.level.block.Blocks.OAK_HANGING_SIGN.defaultBlockState()),
                        net.minecraft.world.level.block.entity.SignTextSlot.FRONT, false);
            case "LanguageSelectScreen":
                return new net.minecraft.client.gui.screens.options.LanguageSelectScreen(parent, mc.options, mc.getLanguageManager());
            case "PackSelectionScreen":
                return new net.minecraft.client.gui.screens.packs.PackSelectionScreen(mc.getResourcePackRepository(), r -> {},
                        mc.getResourcePackDirectory(), lit);
            case "BookSignScreen": {
                net.minecraft.client.gui.screens.inventory.BookEditScreen be = new net.minecraft.client.gui.screens.inventory.BookEditScreen(p,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK),
                        net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.WritableBookContent.EMPTY);
                return new net.minecraft.client.gui.screens.inventory.BookSignScreen(be, p, net.minecraft.world.InteractionHand.MAIN_HAND,
                        java.util.List.of("S1mp1e"));
            }
            case "CommandBlockEditScreen": {
                net.minecraft.world.level.block.entity.CommandBlockEntity cb = new net.minecraft.world.level.block.entity.CommandBlockEntity(
                        p.blockPosition(), net.minecraft.world.level.block.Blocks.COMMAND_BLOCK.defaultBlockState());
                cb.setLevel(mc.level);
                return new net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen(cb);
            }
            case "DisconnectedScreen":
                return new net.minecraft.client.gui.screens.DisconnectedScreen(parent, lit, lit);
            case "ManageServerScreen":
                return new net.minecraft.client.gui.screens.ManageServerScreen(parent, lit, b -> {},
                        new net.minecraft.client.multiplayer.ServerData("S1mp1e", "localhost",
                                net.minecraft.client.multiplayer.ServerData.Type.OTHER));
            case "BookEditScreen":
                return new net.minecraft.client.gui.screens.inventory.BookEditScreen(p,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK),
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        net.minecraft.world.item.component.WritableBookContent.EMPTY);
            case "CreateWorldScreen":
                net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc, () -> {});
                vanillaOpens = true;
                return parent;   // placeholder; vanilla sets the real screen once its data packs are loaded
            default: return null;
        }
    }

    private static Screen build(Minecraft mc, String cn) throws Exception {
        String simple = cn.substring(cn.lastIndexOf('.') + 1);
        Screen sp = special(mc, simple);
        if (sp != null) return sp;
        Class<?> c = Class.forName(cn);
        if (Modifier.isAbstract(c.getModifiers()) || !Screen.class.isAssignableFrom(c)) return null;
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Arrays.sort(cs, Comparator.comparingInt(Constructor::getParameterCount));
        Throwable last = null;
        for (Constructor<?> k : cs) {
            try {
                k.setAccessible(true);
                Class<?>[] ts = k.getParameterTypes();
                Object[] args = new Object[ts.length];
                for (int i = 0; i < ts.length; i++) args[i] = arg(mc, ts[i]);
                return (Screen) k.newInstance(args);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("no usable constructor", last);
    }

    private static Object arg(Minecraft mc, Class<?> t) throws Exception {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0.0;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == char.class) return 'a';
        if (t == String.class) return "S1mp1e";
        if (t == Minecraft.class) return mc;
        if (Screen.class.isAssignableFrom(t)) return new net.minecraft.client.gui.screens.PauseScreen(true);
        if (t != Object.class && t.isAssignableFrom(net.minecraft.network.chat.Component.class))
            return net.minecraft.network.chat.Component.literal("S1mp1e 液態玻璃");
        if (t == net.minecraft.client.Options.class) return mc.options;
        if (t == net.minecraft.core.BlockPos.class) return net.minecraft.core.BlockPos.ZERO;
        if (t == java.net.URI.class) return java.net.URI.create("https://www.minecraft.net");
        if (t == java.nio.file.Path.class) return java.nio.file.Path.of(".");
        if (t == java.util.UUID.class) return java.util.UUID.randomUUID();
        if (t == net.minecraft.world.Container.class) return new net.minecraft.world.SimpleContainer(27);
        if (t == net.minecraft.world.inventory.ContainerLevelAccess.class) return net.minecraft.world.inventory.ContainerLevelAccess.NULL;
        if (mc.getConnection() != null) {
            if (t.isInstance(mc.getConnection())) return mc.getConnection();
            if (t.isInstance(mc.getConnection().getAdvancements())) return mc.getConnection().getAdvancements();
            if (t == net.minecraft.world.flag.FeatureFlagSet.class) return mc.getConnection().enabledFeatures();
        }
        if (mc.level != null && t.isInstance(mc.level)) return mc.level;
        if (mc.player != null) {
            if (t.isInstance(mc.player)) return mc.player;
            if (t.isInstance(mc.player.getInventory())) return mc.player.getInventory();
            if (net.minecraft.world.inventory.AbstractContainerMenu.class.isAssignableFrom(t)) {
                Constructor<?>[] mks = t.getDeclaredConstructors();
                Arrays.sort(mks, Comparator.comparingInt(Constructor::getParameterCount));
                for (Constructor<?> mk : mks) {
                    try {
                        mk.setAccessible(true);
                        Class<?>[] ps = mk.getParameterTypes();
                        Object[] as = new Object[ps.length];
                        for (int i = 0; i < ps.length; i++) as[i] = ps[i] == int.class ? 1 : arg(mc, ps[i]);
                        return mk.newInstance(as);
                    } catch (Throwable ignored) {}
                }
                return null;
            }
        }
        if (t == java.util.Optional.class) return java.util.Optional.empty();
        if (t == java.util.List.class || t == java.util.Collection.class) return java.util.List.of();
        if (t == java.util.Set.class) return java.util.Set.of();
        if (t == java.util.Map.class) return java.util.Map.of();
        if (t.isEnum()) {
            Object[] k = t.getEnumConstants();
            return k.length > 0 ? k[0] : null;
        }
        if (t.isInterface()) {
            return Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t}, (p, m, a) -> {
                String n = m.getName();
                if (n.equals("toString")) return "audit-proxy";
                if (n.equals("hashCode")) return System.identityHashCode(p);
                if (n.equals("equals")) return a != null && a.length == 1 && a[0] == p;
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return false;
                if (r == int.class) return 0;
                if (r == long.class) return 0L;
                if (r == float.class) return 0f;
                if (r == double.class) return 0.0;
                if (r == java.util.Optional.class) return java.util.Optional.empty();
                return null;
            });
        }
        return null;
    }
}
