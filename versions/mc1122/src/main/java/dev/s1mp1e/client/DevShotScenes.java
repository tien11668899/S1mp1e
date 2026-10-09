package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.hook.ItemFlightHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiCustomizeSkin;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiSnooper;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScreenChatOptions;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.inventory.ClickType;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * DevShot scene framework for the 1.12.2 line (mode-driven, like mc1144's {@code DevShotVerify}): a queue of small
 * scenes, each a per-frame step that returns true when finished. {@link DevShot} runs the title-phase queue right after
 * {@code title.png} and the world-phase queue after {@code world.png}, then quits. Modes come from the env var
 * {@code S1MP1E_SHOT_MODE} (comma list): {@code settings}, {@code gap}. With no mode the original fixed DevShot script
 * runs unchanged (the regression baseline). Everything is DEV-only and inert unless DevShot is active.
 */
final class DevShotScenes {

    private DevShotScenes() {}

    interface Scene { boolean step(Minecraft mc, int f); }

    private static final Deque<Scene> QUEUE = new ArrayDeque<Scene>();
    private static int frame;

    static String mode() {
        try {
            String m = System.getenv("S1MP1E_SHOT_MODE");
            return m == null ? "" : m.trim().toLowerCase();
        } catch (Throwable t) { return ""; }
    }

    static boolean has(String m) {
        for (String s : mode().split(",")) if (s.trim().equals(m)) return true;
        return false;
    }

    /** Run one frame of the queue. True once the queue is empty. */
    static boolean tick(Minecraft mc) {
        Scene s = QUEUE.peek();
        if (s == null) return true;
        boolean done;
        try { done = s.step(mc, frame++); }
        catch (Throwable t) { System.out.println("[S1mp1e][DevShot] scene failed: " + t); t.printStackTrace(); done = true; }
        if (done) { QUEUE.poll(); frame = 0; }
        return QUEUE.isEmpty();
    }

    // ============================================================================================
    //  queues
    // ============================================================================================

    static void queueTitle() {
        if (has("trans")) {
            // tr9: Create World -> "More World Options" (in-screen content switch dissolve), then back to the title
            final GuiScreen[] title = new GuiScreen[1];
            add(new Scene() { public boolean step(Minecraft mc, int f) {
                title[0] = mc.currentScreen;
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiCreateWorld(title[0])); return true; } });
            wait(40);
            transition("tr9-createworld-more", new Act() { void run(Minecraft mc) {
                try {
                    java.lang.reflect.Method m = null;
                    for (String n : new String[]{"func_146316_a", "showMoreWorldOptions"}) {
                        try { m = net.minecraft.client.gui.GuiCreateWorld.class.getDeclaredMethod(n, boolean.class); break; }
                        catch (NoSuchMethodException ignored) {}
                    }
                    if (m != null) { m.setAccessible(true); m.invoke(mc.currentScreen, true); }
                } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] more options: " + t); }
            } });
            add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(title[0]); return true; } });
            wait(20);
        }
        if (has("settings")) {
            final GuiScreen[] title = new GuiScreen[1];
            add(new Scene() { public boolean step(Minecraft mc, int f) {
                title[0] = mc.currentScreen;
                mc.displayGuiScreen(new GuiOptions(title[0], mc.gameSettings));
                return true; } });
            wait(40);
            shot("st-title.png");
            add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(title[0]); return true; } });
            wait(10);
        }
    }

    static void queueWorld() {
        if (has("settings")) queueSettings();
        if (has("gap")) queueGap();
        if (has("hud")) queueHud();
        if (has("load")) queueLoad();
        if (has("trans")) queueTrans();
        if (has("ag")) queueAg();
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(5);
    }

    // ---- ALLGLASS round extra captures (#15 F3, #23 advancements, #4 world-select selection) ----
    private static void queueAg() {
        // #15 — F3 debug overlay card (in-world)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(null);
            try { mc.gameSettings.showDebugInfo = true; } catch (Throwable ignored) {}
            return true; } });
        wait(6); shot("ag-f3.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try { mc.gameSettings.showDebugInfo = false; } catch (Throwable ignored) {}
            return true; } });
        // #23 — advancements window (selected tab = glass inset pill, tab plates dropped)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try {
                Object conn = mc.player.connection;
                Object mgr = null;
                for (String n : new String[]{"getAdvancementManager", "func_191982_f"}) {
                    try { java.lang.reflect.Method m = conn.getClass().getMethod(n); mgr = m.invoke(conn); break; }
                    catch (NoSuchMethodException ignored) {}
                }
                if (mgr != null) mc.displayGuiScreen(new net.minecraft.client.gui.advancements.GuiScreenAdvancements(
                        (net.minecraft.client.multiplayer.ClientAdvancementManager) mgr));
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] advancements open: " + t); }
            return true; } });
        wait(30); shot("ag-adv.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        // #4 — world-select list with a selected row (central selection box -> glass capsule)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try { mc.displayGuiScreen(new net.minecraft.client.gui.GuiWorldSelection(null)); }
            catch (Throwable t) { System.out.println("[S1mp1e][DevShot] world-select open: " + t); }
            return true; } });
        wait(30);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try {
                if (mc.currentScreen instanceof net.minecraft.client.gui.GuiWorldSelection) {
                    java.lang.reflect.Field lf = null;
                    for (String n : new String[]{"selectionList", "field_184866_u"}) {
                        try { lf = net.minecraft.client.gui.GuiWorldSelection.class.getDeclaredField(n); lf.setAccessible(true); break; }
                        catch (NoSuchFieldException ignored) {}
                    }
                    if (lf != null) {
                        Object list = lf.get(mc.currentScreen);
                        if (list != null) {
                            for (String n : new String[]{"selectWorld", "func_186792_d"}) {
                                try { java.lang.reflect.Method m = list.getClass().getMethod(n, int.class); m.invoke(list, 0); break; }
                                catch (NoSuchMethodException ignored) {}
                            }
                        }
                    }
                }
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] world-select select: " + t); }
            return true; } });
        wait(8); shot("ag-worldsel.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
    }

    // ---- settings pages (group 1 + slider/roll delta) ----

    private static void queueSettings() {
        open(new Factory() { GuiScreen make(Minecraft mc) { return new GuiOptions(null, mc.gameSettings); } });
        wait(40); shot("st-main.png");
        // sidebar hover (virtual pointer over the Video entry)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.devX = 60; SettingsShell.devY = 47 + 4 + 3 * 18 + 9; return true; } });
        wait(14); shot("st-main-hover.png");
        clearPointer();
        // FOV slider drag on the main page (st-drag burst) and the release settle (st-drag-end)
        dragSlider("st-drag", false);
        // category switch: General -> Music & Sounds, through a real shell click on the sidebar
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.mouseClicked(mc.currentScreen, 60, 47 + 4 + 2 * 18 + 9, 0); return true; } });
        burst("st-to-sound", 10);
        wait(30); shot("st-sound.png");
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiVideoSettings(null, mc.gameSettings); } }, "st-video.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.devScroll(mc.currentScreen, 9999f); return true; } });
        wait(20); shot("st-video-scrolled.png");
        // switch flip burst (a real shell click on the first On/Off row), then flip it back
        clickRow("switch", "st-switch", 10, true);
        // value roll burst on a cycle row (Particles: All -> Decreased -> Minimal -> All)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.render.TypingAnim.timeScale = 0.25f; return true; } });   // slow-mo: roll visible in stills
        clickRow("cycle", "st-roll", 12, false);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.render.TypingAnim.timeScale = 1f; return true; } });
        // Done must win over a half-clipped row right above it
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            GuiScreen s = mc.currentScreen;
            int[] d = SettingsShell.devStraddle(s);
            straddleScreen = s;
            straddleDone = d;
            return true; } });
        wait(6); shot("st-straddle.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            if (straddleDone != null) SettingsShell.mouseClicked(straddleScreen, straddleDone[0], straddleDone[1], 0);
            return true; } });
        wait(4);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            boolean ok = mc.currentScreen != straddleScreen;
            System.out.println("[S1mp1e][DevShot] Done-over-half-clipped-row click: " + (ok ? "PASS" : "FAIL")
                    + " (screen now " + (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()) + ")");
            return true; } });
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiControls(null, mc.gameSettings); } }, "st-controls.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.devScroll(mc.currentScreen, 9999f); return true; } });
        wait(20); shot("st-controls-scrolled.png");
        page(new Factory() { GuiScreen make(Minecraft mc) {
            return new GuiLanguage(null, mc.gameSettings, mc.getLanguageManager()); } }, "st-language.png");
        page(new Factory() { GuiScreen make(Minecraft mc) { return new ScreenChatOptions(null, mc.gameSettings); } }, "st-chat.png");
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiCustomizeSkin(null); } }, "st-skin.png");
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiScreenResourcePacks(null); } }, "st-respack.png");
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiSnooper(null, mc.gameSettings); } }, "st-snooper.png");
        // small window (854x480) — one page
        add(new Scene() { public boolean step(Minecraft mc, int f) { DevShot.shotW = 854; DevShot.shotH = 480; return true; } });
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiVideoSettings(null, mc.gameSettings); } }, "st-small.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { DevShot.shotW = 1280; DevShot.shotH = 720; return true; } });
        wait(20);
    }

    private static GuiScreen straddleScreen;
    private static int[] straddleDone;

    /** Drag the first slider on the current page: press on the pill, ride right then back, release (bursts). */
    private static void dragSlider(final String tag, final boolean unused) {
        final int[][] geo = new int[1][];
        final float[] fov = new float[1];
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            fov[0] = mc.gameSettings.fovSetting;
            geo[0] = SettingsShell.devRow(mc.currentScreen, "slider");
            return true; } });
        wait(4);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            int[] g = geo[0];
            if (g == null) { System.out.println("[S1mp1e][DevShot] no slider row for " + tag); return true; }
            SettingsShell.devX = g[0]; SettingsShell.devY = g[1];
            VanillaSliderSkin.devMouseDown = true;
            SettingsShell.mouseClicked(mc.currentScreen, g[0], g[1], 0);
            return true; } });
        // 14 frames: ride the pointer right across 60% of the track
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            int[] g = geo[0];
            if (g == null) return true;
            float span = (g[3] - g[2]) * 0.6f;
            SettingsShell.devX = g[0] + span * Math.min(1f, (f + 1) / 12f);
            if (f == 6) {
                // the event behind the b2 glitch: a window resize re-runs initGui mid-drag (new widgets, rebuilt FBO);
                // the drag must carry on with a continuous value. That frame's buffer is fresh, so it is not captured.
                mc.resize(mc.displayWidth, mc.displayHeight);
                System.out.println("[S1mp1e][DevShot] " + tag + " forced re-init (resize) mid-drag at f06");
            } else {
                capture(mc, String.format("%s-%02d.png", tag, f));
            }
            System.out.println(String.format("[S1mp1e][DevShot] %s f%02d fov=%.1f", tag, f, mc.gameSettings.fovSetting));
            return f >= 13; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            VanillaSliderSkin.devMouseDown = false; return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("%s-end-%02d.png", tag, f));
            return f >= 9; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.gameSettings.fovSetting = fov[0];   // put the user's FOV back
            clearPointerNow();
            return true; } });
        wait(6);
    }

    /** Click the first row of a kind through the shell and shoot a burst; then click it back to its value. */
    private static void clickRow(final String kind, final String tag, final int n, final boolean twoState) {
        final int[][] geo = new int[1][];
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            geo[0] = SettingsShell.devRow(mc.currentScreen, kind); return true; } });
        wait(6);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            if (geo[0] != null) SettingsShell.mouseClicked(mc.currentScreen, geo[0][0], geo[0][1], 0);
            else System.out.println("[S1mp1e][DevShot] no " + kind + " row for " + tag);
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("%s-%02d.png", tag, f));
            return f >= n - 1; } });
        // restore: a switch flips back once, a cycle row goes round to where it was (3-state options)
        final int restores = twoState ? 1 : 2;
        for (int i = 0; i < restores; i++) {
            add(new Scene() { public boolean step(Minecraft mc, int f) {
                if (geo[0] != null) SettingsShell.mouseClicked(mc.currentScreen, geo[0][0], geo[0][1], 0);
                return true; } });
            wait(4);
        }
    }

    // ---- item flights (PORT_DELTA_ITEM_FLIGHT) + containers ----

    private static void queueGap() {
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            ItemFlightHook.debugLog = true;
            mc.displayGuiScreen(new GuiInventory(mc.player)); return true; } });
        wait(30);
        // gp-flight: shift-click (QUICK_MOVE) the hotbar sword -> one spawn line
        mark("gp-flight");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            click(mc, 36, 0, ClickType.QUICK_MOVE); return true; } });
        burst("gp-flight", 8);
        wait(20);
        // gp-autoflight: PICKUP a filled slot + PICKUP an empty one in the SAME frame (what a mod does) -> one spawn
        mark("gp-autoflight");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            int from = filledSlot(mc), to = emptySlot(mc, from);
            click(mc, from, 0, ClickType.PICKUP);
            click(mc, to, 0, ClickType.PICKUP);
            return true; } });
        burst("gp-autoflight", 8);
        wait(20);
        // gp-noflight: pick up, wait ~300 ms (separate frames, like a hand), put down -> no spawn
        mark("gp-noflight");
        final int[] pair = new int[2];
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            pair[0] = filledSlot(mc); pair[1] = emptySlot(mc, pair[0]);
            click(mc, pair[0], 0, ClickType.PICKUP); return true; } });
        wait(18);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            click(mc, pair[1], 0, ClickType.PICKUP); return true; } });
        shot("gp-noflight.png");
        wait(10);
        mark("gp-end");
        add(new Scene() { public boolean step(Minecraft mc, int f) { ItemFlightHook.debugLog = false; return true; } });

        // ---- containers (group 8 coverage / regression) ----
        container("cn-chest", new Factory() { GuiScreen make(Minecraft mc) {
            net.minecraft.inventory.InventoryBasic inv = new net.minecraft.inventory.InventoryBasic("Chest", false, 27);
            inv.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.APPLE, 5));
            inv.setInventorySlotContents(13, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.GLASS, 32));
            return new net.minecraft.client.gui.inventory.GuiChest(mc.player.inventory, inv); } });
        // progress parts on the glass (keyed container textures): a REAL furnace smelting and a REAL brewing stand
        // brewing (server-side block, opened for the player -> synced progress), shot as timed sequences
        liveContainer("cn-furnace-lit", 0, 8);
        liveContainer("cn-brewing-live", 1, 7);
        // anvil error cross: a pair that cannot be combined (sword + stone) in a client container
        open(new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.GuiRepair(mc.player.inventory, mc.world); } });
        wait(20);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            GuiContainer gc = (GuiContainer) mc.currentScreen;
            gc.inventorySlots.getSlot(0).putStack(new net.minecraft.item.ItemStack(net.minecraft.init.Items.DIAMOND_SWORD));
            gc.inventorySlots.getSlot(1).putStack(new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.STONE, 3));
            return true; } });
        wait(20);
        shot("cn-anvil-cross.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            GuiContainer gc = (GuiContainer) mc.currentScreen;
            gc.inventorySlots.getSlot(0).putStack(net.minecraft.item.ItemStack.EMPTY);
            gc.inventorySlots.getSlot(1).putStack(net.minecraft.item.ItemStack.EMPTY);
            mc.displayGuiScreen(null); return true; } });
        wait(8);
        container("cn-enchanting", new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.GuiEnchantment(mc.player.inventory, mc.world, new net.minecraft.tileentity.TileEntityEnchantmentTable()); } });
        container("cn-beacon", new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.inventory.GuiBeacon(mc.player.inventory,
                    new net.minecraft.tileentity.TileEntityBeacon()); } });
        container("cn-crafting", new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.inventory.GuiCrafting(mc.player.inventory, mc.world); } });
        container("cn-merchant", new Factory() { GuiScreen make(Minecraft mc) {
            net.minecraft.entity.IMerchant m = new net.minecraft.entity.NpcMerchant(mc.player,
                    new net.minecraft.util.text.TextComponentString("Villager"));
            net.minecraft.village.MerchantRecipeList list = new net.minecraft.village.MerchantRecipeList();
            list.add(new net.minecraft.village.MerchantRecipe(new net.minecraft.item.ItemStack(net.minecraft.init.Items.EMERALD, 3),
                    new net.minecraft.item.ItemStack(net.minecraft.init.Items.BREAD, 6)));
            m.setRecipes(list);
            return new net.minecraft.client.gui.GuiMerchant(mc.player.inventory, m, mc.world); } });

        // ---- group 6: edit-box typing (chat input) ----
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat()); return true; } });
        wait(30);
        shot("gp-type-pre.png");
        final String typed = "S1mp1e 玻璃 gg";
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiTextField tf = chatField(mc);
            if (tf != null && f % 2 == 0 && f / 2 < typed.length()) tf.writeText(String.valueOf(typed.charAt(f / 2)));
            capture(mc, String.format("gp-type-%02d.png", f));
            return f >= typed.length() * 2 + 10; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiTextField tf = chatField(mc);
            if (tf != null && f % 3 == 0 && f / 3 < 3) tf.deleteFromCursor(-1);
            capture(mc, String.format("gp-backspace-%02d.png", f));
            return f >= 14; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiTextField tf = chatField(mc);
            if (tf != null) tf.setText("");
            mc.displayGuiScreen(null); return true; } });
        wait(10);

        // ---- group 6: smooth wheel on a vanilla GuiSlot list (the general statistics list) ----
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.achievement.GuiStats(null, mc.player.getStatFileWriter()));
            return true; } });
        wait(80);                          // the stats request round-trips through the integrated server
        shot("gp-list-pre.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiSlot l = statsList(mc);
            if (l != null) dev.s1mp1e.glass.hook.ListMotionHook.devNotches(l, 6);
            else System.out.println("[S1mp1e][DevShot] no stats list");
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiSlot l = statsList(mc);
            capture(mc, String.format("gp-list-%02d.png", f));
            System.out.println(String.format("[S1mp1e][DevShot] gp-list f%02d amount=%d gliding=%s", f,
                    l == null ? -1 : l.getAmountScrolled(), l != null && dev.s1mp1e.glass.hook.ListMotionHook.gliding(l)));
            return f >= 11; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(10);
    }

    private static net.minecraft.client.gui.GuiTextField chatField(Minecraft mc) {
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.GuiChat)) return null;
        for (String n : new String[]{"field_146415_a", "inputField"}) {
            try {
                java.lang.reflect.Field fl = net.minecraft.client.gui.GuiChat.class.getDeclaredField(n);
                fl.setAccessible(true);
                return (net.minecraft.client.gui.GuiTextField) fl.get(mc.currentScreen);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** The general-stats GuiSlot the stats screen is showing (its displaySlot), or null. */
    private static net.minecraft.client.gui.GuiSlot statsList(Minecraft mc) {
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.achievement.GuiStats)) return null;
        for (String n : new String[]{"field_146545_u", "displaySlot"}) {
            try {
                java.lang.reflect.Field fl = net.minecraft.client.gui.achievement.GuiStats.class.getDeclaredField(n);
                fl.setAccessible(true);
                Object v = fl.get(mc.currentScreen);
                if (v instanceof net.minecraft.client.gui.GuiSlot) return (net.minecraft.client.gui.GuiSlot) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ---- loading cards (group 10) ----

    private static void queueLoad() {
        // GuiConnecting: its constructor unloads the world and opens a connection, so it is allocated WITHOUT running
        // the constructor (networkManager stays null -> "Connecting to the server..."); only drawn, never ticked into
        // a connection.
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try {
                java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                uf.setAccessible(true);
                sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
                GuiScreen g = (GuiScreen) u.allocateInstance(net.minecraft.client.multiplayer.GuiConnecting.class);
                // fields a GuiScreen needs that the constructor would have set
                java.lang.reflect.Field bl = declared(GuiScreen.class, "field_146292_n", "buttonList");
                if (bl != null) bl.set(g, new java.util.ArrayList<Object>());
                java.lang.reflect.Field ll = declared(GuiScreen.class, "field_146293_o", "labelList");
                if (ll != null) ll.set(g, new java.util.ArrayList<Object>());
                connecting = g;
                mc.displayGuiScreen(g);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] connecting: " + t); }
            return true; } });
        wait(40);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("ld-connect-%02d.png", f)); return f >= 5; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); connecting = null; return true; } });
        wait(10);
        open(new Factory() { GuiScreen make(Minecraft mc) { return new net.minecraft.client.gui.GuiDownloadTerrain(); } });
        wait(40);
        shot("ld-terrain.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(10);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiScreenWorking w = new net.minecraft.client.gui.GuiScreenWorking();
            w.resetProgressAndMessage("Optimizing world");
            w.displayLoadingString("Converting chunks");
            w.setLoadingProgress(0);
            working = w;
            mc.displayGuiScreen(w); return true; } });
        wait(40);
        shot("ld-working-indeterminate.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            if (working != null) working.setLoadingProgress(42);
            return true; } });
        wait(40);
        shot("ld-working-42.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); working = null; return true; } });
        wait(10);
    }

    private static GuiScreen connecting;
    private static net.minecraft.client.gui.GuiScreenWorking working;

    private static java.lang.reflect.Field declared(Class<?> c, String srg, String mcp) {
        for (String n : new String[]{srg, mcp}) {
            try { java.lang.reflect.Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    // ---- HUD motion (group 7) ----

    private static void queueHud() {
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(20);
        // chat arrival: a couple of settled lines, then one new message -> the column glides up, the line rises in
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiNewChat c = mc.ingameGUI.getChatGUI();
            c.printChatMessage(new net.minecraft.util.text.TextComponentString("<Steve> 玻璃聊天"));
            c.printChatMessage(new net.minecraft.util.text.TextComponentString("<Alex> settled line"));
            return true; } });
        wait(40);
        shot("na-chat-arrival-pre.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.ingameGUI.getChatGUI().printChatMessage(
                    new net.minecraft.util.text.TextComponentString("§bS1mp1e§r 新訊息從底部升起"));
            return true; } });
        burst("na-chat-arrival", 14);
        wait(10);
        // chat close: open the input, type, close -> the bar ghost fades and the words lift off
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat()); return true; } });
        wait(20);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiTextField tf = chatField(mc);
            if (tf != null) tf.setText("gg wp 玻璃");
            return true; } });
        wait(10);
        shot("na-chat-close-pre.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        burst("na-chat-close", 12);
        wait(10);
        // tab list fade in / out (the list renders on a single-player world only with a scoreboard objective or
        // >1 player; a dummy objective in the list slot makes vanilla show it)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
                net.minecraft.scoreboard.Scoreboard sb = srv.worlds[0].getScoreboard();
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devshot");
                if (o == null) o = sb.addScoreObjective("devshot", net.minecraft.scoreboard.IScoreCriteria.DUMMY);
                sb.setObjectiveInDisplaySlot(0, o);
                sb.getOrCreateScore(mc.player.getName(), o).setScorePoints(7);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] tab objective: " + t); } } });
            return true; } });
        wait(30);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.render.TabListFade.devDown = true; return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("na-tablist-in-%02d.png", f));
            System.out.println(String.format("[S1mp1e][DevShot] tablist-in f%02d alpha=%.3f", f,
                    dev.s1mp1e.glass.render.TabListFade.alpha()));
            return f >= 11; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.render.TabListFade.devDown = false; return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("na-tablist-out-%02d.png", f));
            System.out.println(String.format("[S1mp1e][DevShot] tablist-out f%02d alpha=%.3f", f,
                    dev.s1mp1e.glass.render.TabListFade.alpha()));
            return f >= 11; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = mc.getIntegratedServer().worlds[0].getScoreboard();
                sb.setObjectiveInDisplaySlot(0, null);
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devshot");
                if (o != null) sb.removeObjective(o);
            } catch (Throwable ignored) {} } });
            return true; } });
        wait(10);
        // scoreboard sidebar fade in / out
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = mc.getIntegratedServer().worlds[0].getScoreboard();
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devside");
                if (o == null) o = sb.addScoreObjective("devside", net.minecraft.scoreboard.IScoreCriteria.DUMMY);
                o.setDisplayName("S1mp1e 計分板");
                sb.getOrCreateScore("玻璃", o).setScorePoints(12);
                sb.getOrCreateScore("Steve", o).setScorePoints(7);
                sb.getOrCreateScore("Alex", o).setScorePoints(3);
                sb.setObjectiveInDisplaySlot(1, o);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] sidebar: " + t); } } });
            return true; } });
        burst("na-scoreboard-in", 12);
        wait(20);
        shot("na-scoreboard-settled.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = mc.getIntegratedServer().worlds[0].getScoreboard();
                sb.setObjectiveInDisplaySlot(1, null);
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devside");
                if (o != null) sb.removeObjective(o);
            } catch (Throwable ignored) {} } });
            return true; } });
        burst("na-scoreboard-out", 12);
        wait(10);
        // health damage trail: 6 points of generic damage on the server player, then a burst (every 2nd frame)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
                net.minecraft.entity.player.EntityPlayerMP sp = srv.getPlayerList().getPlayers().get(0);
                sp.attackEntityFrom(net.minecraft.util.DamageSource.GENERIC, 6f);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] damage: " + t); } } });
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            if (f % 2 == 0) capture(mc, String.format("na-health-trail-%02d.png", f / 2));
            return f >= 47; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try { mc.getIntegratedServer().getPlayerList().getPlayers().get(0).setHealth(20f); } catch (Throwable ignored) {} } });
            return true; } });
        wait(10);
    }

    // ---- screen transitions (group 5) ----

    abstract static class Act { abstract void run(Minecraft mc); }

    /** {tag}-pre.png, then the action, then 9 consecutive frames with the dissolve alpha logged per frame. */
    private static void transition(final String tag, final Act act) {
        shot(tag + "-pre.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { act.run(mc); return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("%s-%02d.png", tag, f));
            System.out.println(String.format("[S1mp1e][DevShot] %s f%02d dissolveAlpha=%.3f t=%d", tag, f,
                    dev.s1mp1e.glass.render.ScreenDissolve.lastAlpha, System.nanoTime() / 1000000L));
            return f >= 8; } });
        wait(20);
    }

    private static void queueTrans() {
        final GuiScreen[] keep = new GuiScreen[3];
        wait(20);
        transition("tr0-game-pause", new Act() { void run(Minecraft mc) {
            keep[0] = new net.minecraft.client.gui.GuiIngameMenu(); mc.displayGuiScreen(keep[0]); } });
        transition("tr1-pause-options", new Act() { void run(Minecraft mc) {
            keep[1] = new GuiOptions(keep[0], mc.gameSettings); mc.displayGuiScreen(keep[1]); } });
        transition("tr2-options-video", new Act() { void run(Minecraft mc) {
            keep[2] = new GuiVideoSettings(keep[1], mc.gameSettings); mc.displayGuiScreen(keep[2]); } });
        transition("tr3-video-back", new Act() { void run(Minecraft mc) { mc.displayGuiScreen(keep[1]); } });
        transition("tr4-options-game", new Act() { void run(Minecraft mc) { mc.displayGuiScreen(null); } });
        transition("tr5-game-config", new Act() { void run(Minecraft mc) {
            mc.displayGuiScreen(new dev.s1mp1e.client.gui.S1mp1eConfigScreen()); } });
        transition("tr6-config-game", new Act() { void run(Minecraft mc) { mc.displayGuiScreen(null); } });
        // tr7: creative category switch (needs creative mode)
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
                if (srv != null && !srv.getPlayerList().getPlayers().isEmpty())
                    srv.getPlayerList().getPlayers().get(0).setGameType(net.minecraft.world.GameType.CREATIVE);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] creative: " + t); } } });
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            return (mc.playerController != null && mc.playerController.isInCreativeMode()) || f > 200; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiContainerCreative(mc.player)); return true; } });
        wait(30);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.BUILDING_BLOCKS); return true; } });
        wait(30);
        transition("tr7-creative-tab", new Act() { void run(Minecraft mc) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.COMBAT); } });
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(10);
    }

    private static void selectCreative(Minecraft mc, net.minecraft.creativetab.CreativeTabs tab) {
        try {
            if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainerCreative)) return;
            java.lang.reflect.Method m = null;
            for (String n : new String[]{"func_147050_b", "setCurrentCreativeTab"}) {
                try { m = net.minecraft.client.gui.inventory.GuiContainerCreative.class.getDeclaredMethod(n,
                        net.minecraft.creativetab.CreativeTabs.class); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (m != null) { m.setAccessible(true); m.invoke(mc.currentScreen, tab); }
        } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] select tab: " + t); }
    }

    /**
     * kind 0: a furnace with iron ore + coal; kind 1: a brewing stand with 3 water bottles + nether wart + blaze powder.
     * Placed 3 blocks east of the player on the SERVER thread, filled, and opened for the player (the client gets the
     * container + its progress fields from the server). {@code n} shots, one per 0.9 s, then the block is removed.
     */
    private static void liveContainer(final String tag, final int kind, final int n) {
        final net.minecraft.util.math.BlockPos[] at = new net.minecraft.util.math.BlockPos[1];
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
                net.minecraft.entity.player.EntityPlayerMP sp = srv.getPlayerList().getPlayers().get(0);
                net.minecraft.world.WorldServer w = srv.worlds[0];
                net.minecraft.util.math.BlockPos pos = sp.getPosition().add(3, 0, 0);
                at[0] = pos;
                if (kind == 0) {
                    w.setBlockState(pos, net.minecraft.init.Blocks.FURNACE.getDefaultState());
                    net.minecraft.tileentity.TileEntityFurnace te = (net.minecraft.tileentity.TileEntityFurnace) w.getTileEntity(pos);
                    te.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.IRON_ORE, 8));
                    te.setInventorySlotContents(1, new net.minecraft.item.ItemStack(net.minecraft.init.Items.COAL, 8));
                    sp.displayGUIChest(te);
                } else {
                    w.setBlockState(pos, net.minecraft.init.Blocks.BREWING_STAND.getDefaultState());
                    net.minecraft.tileentity.TileEntityBrewingStand te = (net.minecraft.tileentity.TileEntityBrewingStand) w.getTileEntity(pos);
                    for (int i = 0; i < 3; i++) te.setInventorySlotContents(i, net.minecraft.potion.PotionUtils.addPotionToItemStack(
                            new net.minecraft.item.ItemStack(net.minecraft.init.Items.POTIONITEM), net.minecraft.init.PotionTypes.WATER));
                    te.setInventorySlotContents(3, new net.minecraft.item.ItemStack(net.minecraft.init.Items.NETHER_WART));
                    te.setInventorySlotContents(4, new net.minecraft.item.ItemStack(net.minecraft.init.Items.BLAZE_POWDER, 4));
                    sp.displayGUIChest(te);
                }
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] " + tag + ": " + t); } } });
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            return mc.currentScreen instanceof GuiContainer || f > 300; } });
        final long[] t0 = new long[1];
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            long now = System.currentTimeMillis();
            if (f == 0) t0[0] = now;
            int k = (int) ((now - t0[0]) / 900L);
            if (k >= n) return true;
            String name = String.format("%s-%02d.png", tag, k);
            if (!new java.io.File(System.getenv("S1MP1E_SHOT"), name).exists()) {
                capture(mc, name);
                System.out.println("[S1mp1e][DevShot] " + tag + " shot " + k + " at " + (now - t0[0]) + " ms, keyed rebinds="
                        + dev.s1mp1e.glass.render.ContainerExtras.rebinds);
            }
            return false; } });
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            mc.displayGuiScreen(null);
            onServer(mc, new Runnable() { public void run() { try {
                if (at[0] != null) {
                    net.minecraft.world.WorldServer w = mc.getIntegratedServer().worlds[0];
                    net.minecraft.tileentity.TileEntity te = w.getTileEntity(at[0]);
                    if (te instanceof net.minecraft.inventory.IInventory) ((net.minecraft.inventory.IInventory) te).clear();
                    w.setBlockToAir(at[0]);
                }
            } catch (Throwable ignored) {} } });
            return true; } });
        wait(10);
    }

    private static void container(String name, Factory fac) {
        open(fac);
        wait(40);
        shot(name + ".png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(8);
    }

    /** Run on the integrated server's thread (touching server state from the render thread races the server tick). */
    private static void onServer(Minecraft mc, Runnable r) {
        net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
        if (srv != null) srv.addScheduledTask(r);
    }

    private static void mark(final String name) {
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            System.out.println("[S1mp1e][DevShot] mark " + name); return true; } });
    }

    private static void click(Minecraft mc, int slot, int button, ClickType type) {
        if (!(mc.currentScreen instanceof GuiContainer)) return;
        GuiContainer gc = (GuiContainer) mc.currentScreen;
        mc.playerController.windowClick(gc.inventorySlots.windowId, slot, button, type, mc.player);
    }

    /** The first non-empty main-inventory / hotbar slot index of the player container (9..44). */
    private static int filledSlot(Minecraft mc) {
        GuiContainer gc = (GuiContainer) mc.currentScreen;
        for (int i = 9; i < gc.inventorySlots.inventorySlots.size(); i++)
            if (!gc.inventorySlots.inventorySlots.get(i).getStack().isEmpty()) return i;
        return 36;
    }

    private static int emptySlot(Minecraft mc, int not) {
        GuiContainer gc = (GuiContainer) mc.currentScreen;
        for (int i = 9; i < 36; i++)
            if (i != not && gc.inventorySlots.inventorySlots.get(i).getStack().isEmpty()) return i;
        return 20;
    }

    // ============================================================================================
    //  primitives
    // ============================================================================================

    abstract static class Factory { abstract GuiScreen make(Minecraft mc); }

    private static void add(Scene s) { QUEUE.add(s); }

    private static void wait(final int n) {
        add(new Scene() { public boolean step(Minecraft mc, int f) { return f >= n - 1; } });
    }

    private static void open(final Factory fac) {
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(fac.make(mc)); return true; } });
    }

    private static void page(Factory fac, String name) {
        open(fac);
        wait(40);
        shot(name);
    }

    private static void shot(final String name) {
        add(new Scene() { public boolean step(Minecraft mc, int f) { capture(mc, name); return true; } });
    }

    private static void burst(final String tag, final int n) {
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("%s-%02d.png", tag, f)); return f >= n - 1; } });
    }

    private static void clearPointer() {
        add(new Scene() { public boolean step(Minecraft mc, int f) { clearPointerNow(); return true; } });
    }

    private static void clearPointerNow() {
        SettingsShell.devX = Float.NaN; SettingsShell.devY = Float.NaN;
        VanillaSliderSkin.devMouseDown = false;
    }

    private static void capture(Minecraft mc, String name) { DevShot.captureExternal(mc, name); }
}
