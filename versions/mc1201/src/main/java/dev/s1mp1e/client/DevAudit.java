package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=audit} (1.20.1 / Yarn port of the 26.2 / 1.21.1 tool): open EVERY vanilla screen class
 * one after another and shoot each ({@code aNNN-Name.png}) so a human can check what is not liquid glass yet. Screens are
 * built by reflection with harmless stand-in arguments; a class that cannot be built is logged and skipped. Progress is
 * written before each screen opens so the runner can resume after a crash. {@code S1MP1E_AUDIT_ONLY=Name,...} limits the
 * sweep. Before each capture the first list gets a selected row and the first scroll area is nudged (selection glass + the
 * overlay scroller in frame). Dev only — inert without {@code S1MP1E_SHOT}.
 *
 * <p>1.20.1 deltas from the 1.21.1 port: {@code ServerInfo} takes a {@code boolean} local flag (no {@code ServerType});
 * {@code mouseScrolled} is the 3-arg (no horizontal) form; the class list drops screens introduced after 1.20.1.
 */
final class DevAudit {
    private DevAudit() {}

    static final String[] CLASSES = {
        "net.minecraft.client.gui.screen.AccessibilityOnboardingScreen",
        "net.minecraft.client.gui.screen.ChatScreen",
        "net.minecraft.client.gui.screen.ConfirmLinkScreen",
        "net.minecraft.client.gui.screen.ConfirmScreen",
        "net.minecraft.client.gui.screen.CreditsScreen",
        "net.minecraft.client.gui.screen.DeathScreen",
        "net.minecraft.client.gui.screen.DeathScreen$TitleScreenConfirmScreen",
        "net.minecraft.client.gui.screen.DemoScreen",
        "net.minecraft.client.gui.screen.DisconnectedScreen",
        "net.minecraft.client.gui.screen.DownloadingTerrainScreen",
        "net.minecraft.client.gui.screen.FatalErrorScreen",
        "net.minecraft.client.gui.screen.GameMenuScreen",
        "net.minecraft.client.gui.screen.GameModeSelectionScreen",
        "net.minecraft.client.gui.screen.MessageScreen",
        "net.minecraft.client.gui.screen.NoticeScreen",
        "net.minecraft.client.gui.screen.OpenToLanScreen",
        "net.minecraft.client.gui.screen.OutOfMemoryScreen",
        "net.minecraft.client.gui.screen.ProgressScreen",
        "net.minecraft.client.gui.screen.SleepingChatScreen",
        "net.minecraft.client.gui.screen.StatsScreen",
        "net.minecraft.client.gui.screen.TaskScreen",
        "net.minecraft.client.gui.screen.TitleScreen",
        "net.minecraft.client.gui.screen.advancement.AdvancementsScreen",
        "net.minecraft.client.gui.screen.ingame.AnvilScreen",
        "net.minecraft.client.gui.screen.ingame.BeaconScreen",
        "net.minecraft.client.gui.screen.ingame.BlastFurnaceScreen",
        "net.minecraft.client.gui.screen.ingame.BookEditScreen",
        "net.minecraft.client.gui.screen.ingame.BookScreen",
        "net.minecraft.client.gui.screen.ingame.BrewingStandScreen",
        "net.minecraft.client.gui.screen.ingame.CartographyTableScreen",
        "net.minecraft.client.gui.screen.ingame.CommandBlockScreen",
        "net.minecraft.client.gui.screen.ingame.CraftingScreen",
        "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
        "net.minecraft.client.gui.screen.ingame.EnchantmentScreen",
        "net.minecraft.client.gui.screen.ingame.FurnaceScreen",
        "net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen",
        "net.minecraft.client.gui.screen.ingame.GenericContainerScreen",
        "net.minecraft.client.gui.screen.ingame.GrindstoneScreen",
        "net.minecraft.client.gui.screen.ingame.HangingSignEditScreen",
        "net.minecraft.client.gui.screen.ingame.HopperScreen",
        "net.minecraft.client.gui.screen.ingame.HorseScreen",
        "net.minecraft.client.gui.screen.ingame.InventoryScreen",
        "net.minecraft.client.gui.screen.ingame.JigsawBlockScreen",
        "net.minecraft.client.gui.screen.ingame.LecternScreen",
        "net.minecraft.client.gui.screen.ingame.LoomScreen",
        "net.minecraft.client.gui.screen.ingame.MerchantScreen",
        "net.minecraft.client.gui.screen.ingame.MinecartCommandBlockScreen",
        "net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen",
        "net.minecraft.client.gui.screen.ingame.SignEditScreen",
        "net.minecraft.client.gui.screen.ingame.SmithingScreen",
        "net.minecraft.client.gui.screen.ingame.SmokerScreen",
        "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
        "net.minecraft.client.gui.screen.ingame.StructureBlockScreen",
        "net.minecraft.client.gui.screen.AddServerScreen",
        "net.minecraft.client.gui.screen.multiplayer.ConnectScreen",
        "net.minecraft.client.gui.screen.DirectConnectScreen",
        "net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen",
        "net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen",
        "net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen",
        "net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen",
        "net.minecraft.client.gui.screen.option.ChatOptionsScreen",
        "net.minecraft.client.gui.screen.option.ControlsOptionsScreen",
        "net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen",
        "net.minecraft.client.gui.screen.option.FontOptionsScreen",
        "net.minecraft.client.gui.screen.option.KeybindsScreen",
        "net.minecraft.client.gui.screen.option.LanguageOptionsScreen",
        "net.minecraft.client.gui.screen.option.MouseOptionsScreen",
        "net.minecraft.client.gui.screen.option.OnlineOptionsScreen",
        "net.minecraft.client.gui.screen.option.OptionsScreen",
        "net.minecraft.client.gui.screen.option.SkinOptionsScreen",
        "net.minecraft.client.gui.screen.option.SoundOptionsScreen",
        "net.minecraft.client.gui.screen.option.TelemetryInfoScreen",
        "net.minecraft.client.gui.screen.option.VideoOptionsScreen",
        "net.minecraft.client.gui.screen.pack.ExperimentalWarningScreen",
        "net.minecraft.client.gui.screen.pack.PackScreen",
        "net.minecraft.client.gui.screen.report.AbuseReportReasonScreen",
        "net.minecraft.client.gui.screen.report.ChatSelectionScreen",
        "net.minecraft.client.gui.screen.world.BackupPromptScreen",
        "net.minecraft.client.gui.screen.world.CreateWorldScreen",
        "net.minecraft.client.gui.screen.world.CustomizeBuffetLevelScreen",
        "net.minecraft.client.gui.screen.world.CustomizeFlatLevelScreen",
        "net.minecraft.client.gui.screen.world.DataPackFailureScreen",
        "net.minecraft.client.gui.screen.world.EditGameRulesScreen",
        "net.minecraft.client.gui.screen.world.EditWorldScreen",
        "net.minecraft.client.gui.screen.world.LevelLoadingScreen",
        "net.minecraft.client.gui.screen.world.OptimizeWorldScreen",
        "net.minecraft.client.gui.screen.world.PresetsScreen",
        "net.minecraft.client.gui.screen.world.SelectWorldScreen",
    };

    private static final int SETTLE = 40, HOLD = 50;
    private static final java.util.Set<String> ONLY = onlySet();
    private static int idx = -1, frames;
    private static String current = "";
    private static boolean vanillaOpens;

    private static java.util.Set<String> onlySet() {
        String v = System.getenv("S1MP1E_AUDIT_ONLY");
        if (v == null || v.isBlank()) return null;
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String n : v.split(",")) if (!n.isBlank()) out.add(n.trim());
        return out;
    }

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
    static boolean step(MinecraftClient mc) {
        if (idx < 0) {
            idx = 0;
            try {
                String from = System.getenv("S1MP1E_AUDIT_FROM");
                if (from != null && !from.isBlank()) idx = Integer.parseInt(from.trim());
            } catch (Throwable ignored) {}
        }
        if (idx >= CLASSES.length) {
            try { mc.setScreen(null); } catch (Throwable ignored) {}
            log("DONE " + CLASSES.length);
            return true;
        }
        if (frames == 0) {
            if (mc.player == null || mc.getNetworkHandler() == null) {
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
                    mc.setScreen(s);
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
            Screen now = mc.currentScreen;
            String shown = now == null ? "none" : now.getClass().getSimpleName();
            DevShot.capture(mc, String.format("a%03d-%s.png", idx, current));
            log(String.format("%03d %s OK shown=%s", idx, current, shown));
        } else if (frames >= hold) {
            try { mc.setScreen(null); } catch (Throwable t) { forceClear(mc); }
            vanillaOpens = false;
            frames = 0;
            idx++;
        }
        return false;
    }

    private static void forceClear(MinecraftClient mc) {
        try {
            mc.setScreen(null);
            return;
        } catch (Throwable ignored) {}
        try {
            mc.currentScreen = null;
        } catch (Throwable t) {
            log("forceClear failed: " + rootMsg(t));
        }
    }

    /** Select a row in the first list and nudge the first scroll area, so the capture shows both. */
    private static void prep(MinecraftClient mc) {
        try {
            Screen s = mc.currentScreen;
            if (s == null) return;
            java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<Object>(s.children());
            boolean selected = false, scrolled = false;
            while (!q.isEmpty() && !(selected && scrolled)) {
                Object c = q.poll();
                if (!selected && c instanceof net.minecraft.client.gui.widget.EntryListWidget<?> l && !l.children().isEmpty()
                        && l.getSelectedOrNull() == null) {
                    Class<?> ec = Class.forName("net.minecraft.client.gui.widget.EntryListWidget$Entry");
                    Object e = l.children().get(Math.min(1, l.children().size() - 1));
                    net.minecraft.client.gui.widget.EntryListWidget.class.getMethod("setSelected", ec).invoke(l, e);
                    selected = true;
                }
                if (!scrolled && c instanceof net.minecraft.client.gui.widget.EntryListWidget<?> a && a.getMaxScroll() > 0) {
                    a.mouseScrolled(0.0, 0.0, -2);   // 1.20.1 EntryListWidget.mouseScrolled ignores x/y
                    scrolled = true;
                }
                if (!scrolled && c instanceof net.minecraft.client.gui.widget.ScrollableWidget a) {
                    a.mouseScrolled(a.getX() + a.getWidth() / 2.0, a.getY() + a.getHeight() / 2.0, -2);
                    scrolled = true;
                }
                if (c instanceof net.minecraft.client.gui.ParentElement pe) q.addAll(pe.children());
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

    private static Screen special(MinecraftClient mc, String simple) throws Exception {
        var p = mc.player;
        var inv = p.getInventory();
        var lit = net.minecraft.text.Text.literal("S1mp1e 液態玻璃");
        var parent = new net.minecraft.client.gui.screen.GameMenuScreen(true);
        switch (simple) {
            case "GameMenuScreen": return new net.minecraft.client.gui.screen.GameMenuScreen(true);
            case "CreativeInventoryScreen":
                return new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(p, p.networkHandler.getEnabledFeatures(), true);
            case "GenericContainerScreen":
                return new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(
                        net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(1, inv), inv, lit);
            case "SignEditScreen": {
                // 1.20.1 sign screens tick isPlayerTooFarToEdit -> world.getPlayerByUuid: give the stand-in a world and
                // the player's position (a world-less stand-in NPE'd the render thread in the first audit).
                var be = new net.minecraft.block.entity.SignBlockEntity(p.getBlockPos(),
                        net.minecraft.block.Blocks.OAK_SIGN.getDefaultState());
                be.setWorld(mc.world);
                return new net.minecraft.client.gui.screen.ingame.SignEditScreen(be, true, false);
            }
            case "HangingSignEditScreen": {
                var be = new net.minecraft.block.entity.HangingSignBlockEntity(p.getBlockPos(),
                        net.minecraft.block.Blocks.OAK_HANGING_SIGN.getDefaultState());
                be.setWorld(mc.world);
                return new net.minecraft.client.gui.screen.ingame.HangingSignEditScreen(be, true, false);
            }
            case "LanguageOptionsScreen":
                return new net.minecraft.client.gui.screen.option.LanguageOptionsScreen(parent, mc.options, mc.getLanguageManager());
            case "PackScreen":
                return new net.minecraft.client.gui.screen.pack.PackScreen(mc.getResourcePackManager(), r -> {},
                        mc.getResourcePackDir(), lit);
            case "BookEditScreen":
                return new net.minecraft.client.gui.screen.ingame.BookEditScreen(p,
                        new net.minecraft.item.ItemStack(net.minecraft.item.Items.WRITABLE_BOOK), net.minecraft.util.Hand.MAIN_HAND);
            case "CommandBlockScreen": {
                var cb = new net.minecraft.block.entity.CommandBlockBlockEntity(p.getBlockPos(),
                        net.minecraft.block.Blocks.COMMAND_BLOCK.getDefaultState());
                cb.setWorld(mc.world);
                return new net.minecraft.client.gui.screen.ingame.CommandBlockScreen(cb);
            }
            case "DisconnectedScreen":
                return new net.minecraft.client.gui.screen.DisconnectedScreen(parent, lit, lit);
            case "AddServerScreen":
                return new net.minecraft.client.gui.screen.AddServerScreen(parent, b -> {},
                        new net.minecraft.client.network.ServerInfo("S1mp1e", "localhost", false));
            case "CreateWorldScreen":
                net.minecraft.client.gui.screen.world.CreateWorldScreen.create(mc, parent);
                vanillaOpens = true;
                return parent;
            default: return null;
        }
    }

    private static Screen build(MinecraftClient mc, String cn) throws Exception {
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

    private static Object arg(MinecraftClient mc, Class<?> t) throws Exception {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0.0;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == char.class) return 'a';
        if (t == String.class) return "S1mp1e";
        if (t == MinecraftClient.class) return mc;
        if (Screen.class.isAssignableFrom(t)) return new net.minecraft.client.gui.screen.GameMenuScreen(true);
        if (t != Object.class && t.isAssignableFrom(net.minecraft.text.Text.class)) return net.minecraft.text.Text.literal("S1mp1e 液態玻璃");
        if (t == net.minecraft.client.option.GameOptions.class) return mc.options;
        if (t == net.minecraft.util.math.BlockPos.class) return net.minecraft.util.math.BlockPos.ORIGIN;
        if (t == java.net.URI.class) return java.net.URI.create("https://www.minecraft.net");
        if (t == java.nio.file.Path.class) return java.nio.file.Path.of(".");
        if (t == java.util.UUID.class) return java.util.UUID.randomUUID();
        if (t == net.minecraft.inventory.Inventory.class) return new net.minecraft.inventory.SimpleInventory(27);
        if (t == net.minecraft.screen.ScreenHandlerContext.class) return net.minecraft.screen.ScreenHandlerContext.EMPTY;
        if (mc.getNetworkHandler() != null) {
            if (t.isInstance(mc.getNetworkHandler())) return mc.getNetworkHandler();
            if (t.isInstance(mc.getNetworkHandler().getAdvancementHandler())) return mc.getNetworkHandler().getAdvancementHandler();
            if (t == net.minecraft.resource.featuretoggle.FeatureSet.class) return mc.getNetworkHandler().getEnabledFeatures();
        }
        if (mc.world != null && t.isInstance(mc.world)) return mc.world;
        if (mc.player != null) {
            if (t.isInstance(mc.player)) return mc.player;
            if (t.isInstance(mc.player.getInventory())) return mc.player.getInventory();
            if (net.minecraft.screen.ScreenHandler.class.isAssignableFrom(t)) {
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
            return Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t}, (px, m, a) -> {
                String n = m.getName();
                if (n.equals("toString")) return "audit-proxy";
                if (n.equals("hashCode")) return System.identityHashCode(px);
                if (n.equals("equals")) return a != null && a.length == 1 && a[0] == px;
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
