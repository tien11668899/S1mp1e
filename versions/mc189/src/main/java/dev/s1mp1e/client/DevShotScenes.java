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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 1.8.9 線的 DevShot 場景框架（照 mc1122 {@code DevShotScenes} 的做法）：一個小場景佇列，每個場景是「每幀跑一步、
 * 做完回傳 true」。{@link DevShot} 在 {@code title.png} 之後跑標題階段的佇列、在 {@code world.png} 之後跑世界階段的
 * 佇列，跑完就結束。要跑哪些場景由環境變數 {@code S1MP1E_SHOT_MODE}（逗號分隔）決定；沒設定時照舊跑原本固定的
 * DevShot 腳本（回歸基準）。全部只在 DevShot 啟用時才有作用，一般遊戲完全不碰。
 *
 * <p>模式：
 * <ul>
 *   <li>{@code gap}：物品飛行（PORT_DELTA_ITEM_FLIGHT）—— shift 快速移動要飛、同一幀拿起＋放下（Item Scroller 這類
 *       模組的做法）要飛、手拿起隔幾幀才放下不飛。log 用 {@code mark} 分段，每段之間的 {@code [ItemFlights] spawn}
 *       行數就是判定依據。</li>
 * </ul>
 *
 * <p>1.8.9 的差異：{@code PlayerControllerMP.windowClick} 的模式是整數（0 = 拿起、1 = shift 快速移動），空格是
 * {@code null}，玩家是 {@code mc.thePlayer}。
 */
final class DevShotScenes {

    private DevShotScenes() {}

    interface Scene { boolean step(Minecraft mc, int f); }

    /** {@code windowClick} 的模式（1.8.9 用整數，1.9+ 才有 ClickType）。 */
    private static final int PICKUP = 0, QUICK_MOVE = 1;

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

    /** 跑佇列的一幀。佇列空了回傳 true。 */
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
    //  佇列
    // ============================================================================================

    /** 標題階段（還沒有世界）。 */
    static void queueTitle() {
        if (has("trans")) {
            // tr9：建立世界 →「更多世界選項」（同一畫面內的內容切換淡化），再回標題
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
            wait(30);
        }
        if (has("settings")) {
            // 從標題畫面開的選項頁（背景是全景圖，不是世界）
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

    /** 世界階段（world.png 之後，玩家已拿到固定的裝備）。 */
    static void queueWorld() {
        if (has("settings")) queueSettings();
        if (has("gap")) queueGap();
        if (has("hud")) queueHud();
        if (has("inv")) queueInv();
        if (has("load")) queueLoad();
        if (has("trans")) queueTrans();
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(5);
    }

    // ---- settings：設定頁外殼（第 1 組＋滑桿／數值滾動 delta）-----------------------------------------

    private static void queueSettings() {
        open(new Factory() { GuiScreen make(Minecraft mc) { return new GuiOptions(null, mc.gameSettings); } });
        wait(40); shot("st-main.png");
        // 側邊欄懸停（虛擬指標放在「視訊設定」那一項上）
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.devX = 60; SettingsShell.devY = 47 + 4 + 3 * 18 + 9; return true; } });
        wait(14); shot("st-main-hover.png");
        clearPointer();
        // 主頁的視野滑桿拖曳（st-drag 連拍）和放開後的回彈（st-drag-end）
        dragSlider("st-drag");
        // 切換分類：一般 → 音樂與音效，透過外殼真的點側邊欄
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.mouseClicked(mc.currentScreen, 60, 47 + 4 + 2 * 18 + 9, 0); return true; } });
        burst("st-to-sound", 10);
        wait(30); shot("st-sound.png");
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiVideoSettings(null, mc.gameSettings); } }, "st-video.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            SettingsShell.devScroll(mc.currentScreen, 9999f); return true; } });
        wait(20); shot("st-video-scrolled.png");
        // 開關切換連拍（外殼真的點第一個開／關列），再切回來
        clickRow("switch", "st-switch", 10, true);
        // 循環列的數值滾動連拍（例如粒子：全部 → 減少 → 最少 → 全部）；慢動作 0.25 倍，靜態截圖才看得到
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.render.TypingAnim.timeScale = 0.25f; return true; } });
        clickRow("cycle", "st-roll", 12, false);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.render.TypingAnim.timeScale = 1f; return true; } });
        // 下緣有一列被切一半時，點「完成」必須點到完成，不是那一列
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            GuiScreen s = mc.currentScreen;
            straddleDone = SettingsShell.devStraddle(s);
            straddleScreen = s;
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
        // 小視窗（854x480）——一頁
        add(new Scene() { public boolean step(Minecraft mc, int f) { DevShot.shotW = 854; DevShot.shotH = 480; return true; } });
        page(new Factory() { GuiScreen make(Minecraft mc) { return new GuiVideoSettings(null, mc.gameSettings); } }, "st-small.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { DevShot.shotW = 1280; DevShot.shotH = 720; return true; } });
        wait(20);
    }

    private static GuiScreen straddleScreen;
    private static int[] straddleDone;

    /** 拖曳目前頁面的第一個滑桿：按在藥丸上、往右拉再回來、放開（連拍）。中途強制一次 re-init。 */
    private static void dragSlider(final String tag) {
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
        // 14 幀：指標往右滑過軌道的 60%
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            int[] g = geo[0];
            if (g == null) return true;
            float span = (g[3] - g[2]) * 0.6f;
            SettingsShell.devX = g[0] + span * Math.min(1f, (f + 1) / 12f);
            if (f == 6) {
                // 拖曳途中視窗大小改變會重跑 initGui（換新 widget、重建 FBO），拖曳必須接續、數值連續。
                // 那一幀的 buffer 是剛重建的，所以不截。
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
            mc.gameSettings.fovSetting = fov[0];   // 把使用者的視野設回去
            clearPointerNow();
            return true; } });
        wait(6);
    }

    /** 透過外殼點某一種列的第一列並連拍；之後再點回原本的值。 */
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
        // 還原：開關點回一次；循環列繞一圈回到原值（三態選項）
        final int restores = twoState ? 1 : 2;
        for (int i = 0; i < restores; i++) {
            add(new Scene() { public boolean step(Minecraft mc, int f) {
                if (geo[0] != null) SettingsShell.mouseClicked(mc.currentScreen, geo[0][0], geo[0][1], 0);
                return true; } });
            wait(4);
        }
    }

    // ---- trans：畫面切換交叉淡化 -------------------------------------------------------------------

    abstract static class Act { abstract void run(Minecraft mc); }

    /** 先拍 {tag}-pre.png，做動作，再連拍 9 幀，每幀把淡化 alpha 寫進 log。 */
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
            keep[1] = new net.minecraft.client.gui.GuiOptions(keep[0], mc.gameSettings); mc.displayGuiScreen(keep[1]); } });
        transition("tr2-options-video", new Act() { void run(Minecraft mc) {
            keep[2] = new net.minecraft.client.gui.GuiVideoSettings(keep[1], mc.gameSettings); mc.displayGuiScreen(keep[2]); } });
        transition("tr3-video-back", new Act() { void run(Minecraft mc) { mc.displayGuiScreen(keep[1]); } });
        transition("tr4-options-game", new Act() { void run(Minecraft mc) { mc.displayGuiScreen(null); } });
        transition("tr5-game-config", new Act() { void run(Minecraft mc) {
            mc.displayGuiScreen(new dev.s1mp1e.client.gui.S1mp1eConfigScreen()); } });
        transition("tr6-config-game", new Act() { void run(Minecraft mc) { mc.displayGuiScreen(null); } });
        // tr7：創造模式分類切換（要先切成創造模式；伺服器狀態只能在伺服器執行緒上改）
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
                if (srv != null && !srv.getConfigurationManager().playerEntityList.isEmpty())
                    srv.getConfigurationManager().playerEntityList.get(0)
                       .setGameType(net.minecraft.world.WorldSettings.GameType.CREATIVE);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] creative: " + t); } } });
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            return (mc.playerController != null && mc.playerController.isInCreativeMode()) || f > 200; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiContainerCreative(mc.thePlayer)); return true; } });
        wait(30);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.tabBlock); return true; } });
        wait(30);
        transition("tr7-creative-tab", new Act() { void run(Minecraft mc) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.tabCombat); } });
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

    /** 在整合伺服器的執行緒上跑（從 render 執行緒碰伺服器狀態會跟伺服器 tick 搶）。 */
    private static void onServer(Minecraft mc, Runnable r) {
        net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
        if (srv != null) srv.addScheduledTask(r);
    }

    // ---- gap：物品飛行 ----------------------------------------------------------------------------

    private static void queueGap() {
        // 飛行只有 180 ms，而每張截圖（寫 PNG）就要上百毫秒，原速連拍只會拍到起點和終點。
        // 驗證期間把時間放慢到 0.12 倍（飛行約 1.5 秒），連拍才看得到中途的弧線。
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            ItemFlightHook.debugLog = true;
            ItemFlightHook.timeScale = 0.12F;
            mc.displayGuiScreen(new GuiInventory(mc.thePlayer)); return true; } });
        wait(30);
        // gp-flight：shift 點熱鍵列第一格（劍）→ 應該生成一次飛行
        mark("gp-flight");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            click(mc, 36, 0, QUICK_MOVE); return true; } });
        burst("gp-flight", 10);
        wait(60);
        // gp-autoflight：同一幀「拿起有東西的格子＋放進空格子」（模組代替玩家搬）→ 應該生成一次飛行
        mark("gp-autoflight");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            int from = filledSlot(mc), to = emptySlot(mc, from);
            click(mc, from, 0, PICKUP);
            click(mc, to, 0, PICKUP);
            return true; } });
        burst("gp-autoflight", 10);
        wait(60);
        // gp-noflight：拿起，隔約 300 ms（分開的幀，像用手），再放下 → 不該生成飛行
        mark("gp-noflight");
        final int[] pair = new int[2];
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            pair[0] = filledSlot(mc); pair[1] = emptySlot(mc, pair[0]);
            click(mc, pair[0], 0, PICKUP); return true; } });
        wait(18);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            click(mc, pair[1], 0, PICKUP); return true; } });
        shot("gp-noflight.png");
        wait(10);
        mark("gp-end");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            ItemFlightHook.debugLog = false;
            ItemFlightHook.timeScale = 1.0F;
            mc.displayGuiScreen(null); return true; } });
        wait(8);

        // ---- 第 8 組：容器（ContainerExtras 的進度零件在玻璃上不能是灰色方塊）＋各種容器的回歸 ----
        container("cn-chest", new Factory() { GuiScreen make(Minecraft mc) {
            net.minecraft.inventory.InventoryBasic inv = new net.minecraft.inventory.InventoryBasic("Chest", false, 27);
            inv.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.apple, 5));
            inv.setInventorySlotContents(13, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.glass, 32));
            return new net.minecraft.client.gui.inventory.GuiChest(mc.thePlayer.inventory, inv); } });
        // 玻璃上的進度零件：伺服器端真的在燒的熔爐、真的在釀的釀造台（打開後進度由伺服器同步），每 0.9 秒拍一張
        liveContainer("cn-furnace-lit", 0, 8);
        liveContainer("cn-brewing-live", 1, 7);
        // 鐵砧的紅叉：放一組不能合成的東西（劍＋石頭）
        open(new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.GuiRepair(mc.thePlayer.inventory, mc.theWorld); } });
        wait(20);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            GuiContainer gc = (GuiContainer) mc.currentScreen;
            gc.inventorySlots.getSlot(0).putStack(new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond_sword));
            gc.inventorySlots.getSlot(1).putStack(new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone, 3));
            return true; } });
        wait(20);
        shot("cn-anvil-cross.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            GuiContainer gc = (GuiContainer) mc.currentScreen;
            gc.inventorySlots.getSlot(0).putStack(null);
            gc.inventorySlots.getSlot(1).putStack(null);
            mc.displayGuiScreen(null); return true; } });
        wait(8);
        container("cn-enchanting", new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.GuiEnchantment(mc.thePlayer.inventory, mc.theWorld,
                    new net.minecraft.tileentity.TileEntityEnchantmentTable()); } });
        container("cn-beacon", new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.inventory.GuiBeacon(mc.thePlayer.inventory,
                    new net.minecraft.tileentity.TileEntityBeacon()); } });
        container("cn-crafting", new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.inventory.GuiCrafting(mc.thePlayer.inventory, mc.theWorld); } });
        container("cn-merchant", new Factory() { GuiScreen make(Minecraft mc) {
            net.minecraft.entity.IMerchant m = new net.minecraft.entity.NpcMerchant(mc.thePlayer,
                    new net.minecraft.util.ChatComponentText("Villager"));
            net.minecraft.village.MerchantRecipeList list = new net.minecraft.village.MerchantRecipeList();
            list.add(new net.minecraft.village.MerchantRecipe(new net.minecraft.item.ItemStack(net.minecraft.init.Items.emerald, 3),
                    new net.minecraft.item.ItemStack(net.minecraft.init.Items.bread, 6)));
            m.setRecipes(list);
            return new net.minecraft.client.gui.GuiMerchant(mc.thePlayer.inventory, m, mc.theWorld); } });

        // ---- 第 6 組：文字框打字（聊天輸入框）----
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

        // ---- 第 6 組：原版 GuiSlot 清單的滾輪平滑捲動（一般統計清單）----
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.achievement.GuiStats(null, mc.thePlayer.getStatFileWriter()));
            return true; } });
        wait(80);                          // 統計資料要跟整合伺服器來回一次
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

    private static void container(String name, Factory fac) {
        open(fac);
        wait(40);
        shot(name + ".png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(8);
    }

    /**
     * kind 0：熔爐，放鐵礦＋煤；kind 1：釀造台，放 3 瓶水＋地獄疙瘩（1.8.9 的釀造台沒有燃料格）。
     * 在伺服器執行緒上放在玩家東邊 3 格、填好、替玩家打開（客戶端從伺服器拿到容器和它的進度欄位）。
     * 每 0.9 秒拍一張，共 {@code n} 張，之後把方塊移除。
     */
    private static void liveContainer(final String tag, final int kind, final int n) {
        final net.minecraft.util.BlockPos[] at = new net.minecraft.util.BlockPos[1];
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.server.MinecraftServer srv = mc.getIntegratedServer();
                net.minecraft.entity.player.EntityPlayerMP sp = srv.getConfigurationManager().playerEntityList.get(0);
                net.minecraft.world.WorldServer w = srv.worldServers[0];
                net.minecraft.util.BlockPos pos = sp.getPosition().add(3, 0, 0);
                at[0] = pos;
                if (kind == 0) {
                    w.setBlockState(pos, net.minecraft.init.Blocks.furnace.getDefaultState());
                    net.minecraft.tileentity.TileEntityFurnace te = (net.minecraft.tileentity.TileEntityFurnace) w.getTileEntity(pos);
                    te.setInventorySlotContents(0, new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.iron_ore, 8));
                    te.setInventorySlotContents(1, new net.minecraft.item.ItemStack(net.minecraft.init.Items.coal, 8));
                    sp.displayGUIChest(te);
                } else {
                    w.setBlockState(pos, net.minecraft.init.Blocks.brewing_stand.getDefaultState());
                    net.minecraft.tileentity.TileEntityBrewingStand te = (net.minecraft.tileentity.TileEntityBrewingStand) w.getTileEntity(pos);
                    for (int i = 0; i < 3; i++) te.setInventorySlotContents(i,
                            new net.minecraft.item.ItemStack(net.minecraft.init.Items.potionitem, 1, 0));   // 水瓶
                    te.setInventorySlotContents(3, new net.minecraft.item.ItemStack(net.minecraft.init.Items.nether_wart));
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
                    net.minecraft.world.WorldServer w = mc.getIntegratedServer().worldServers[0];
                    net.minecraft.tileentity.TileEntity te = w.getTileEntity(at[0]);
                    if (te instanceof net.minecraft.inventory.IInventory) ((net.minecraft.inventory.IInventory) te).clear();
                    w.setBlockToAir(at[0]);
                }
            } catch (Throwable ignored) {} } });
            return true; } });
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

    /** 統計畫面目前顯示的 GuiSlot（displaySlot），沒有就 null。 */
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

    // ---- load：第 10 組載入卡＋液態載入動畫 ------------------------------------------------------------

    private static GuiScreen connecting;
    private static net.minecraft.client.gui.GuiScreenWorking working;

    private static java.lang.reflect.Field declared(Class<?> c, String srg, String mcp) {
        for (String n : new String[]{srg, mcp}) {
            try { java.lang.reflect.Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static void queueLoad() {
        // GuiConnecting 的建構子會卸載世界並開始連線，所以「不跑建構子」直接配置一個實例（networkManager 留 null →
        // 顯示「正在連線到伺服器…」）；只畫，不讓它 tick 去真的連線。
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try {
                java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                uf.setAccessible(true);
                sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
                GuiScreen g = (GuiScreen) u.allocateInstance(net.minecraft.client.multiplayer.GuiConnecting.class);
                // 建構子本來會設好、GuiScreen 需要的欄位
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
        open(new Factory() { GuiScreen make(Minecraft mc) {
            return new net.minecraft.client.gui.GuiDownloadTerrain(mc.getNetHandler()); } });
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

    // ---- inv：使用者回報的背包問題（2026-10-04）---------------------------------------------------------
    //  1. 創造背包滑桿按住拖曳時要「變大」、不能是藍色（和設定頁的滑桿一樣）
    //  4. 創造分類 pill 要是正方形、圖示置中（26.2／1.21.1 的算法）
    //  5. 背包玻璃要折射「沒變暗的世界」（26.2 的做法），不再發暗

    private static java.lang.reflect.Field creativeField(String srg, String mcp) {
        for (String n : new String[]{srg, mcp}) {
            try {
                java.lang.reflect.Field f = net.minecraft.client.gui.inventory.GuiContainerCreative.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void queueInv() {
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(10);
        // 問題 5：生存背包（和修改前的 inventory.png 比亮度）
        open(new Factory() { GuiScreen make(Minecraft mc) { return new GuiInventory(mc.thePlayer); } });
        wait(40);
        shot("iv-inventory.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(8);
        // 問題 4：創造背包分類列
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                mc.getIntegratedServer().getConfigurationManager().playerEntityList.get(0)
                  .setGameType(net.minecraft.world.WorldSettings.GameType.CREATIVE);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] creative: " + t); } } });
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            return (mc.playerController != null && mc.playerController.isInCreativeMode()) || f > 200; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiContainerCreative(mc.thePlayer)); return true; } });
        wait(30);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.tabBlock); return true; } });
        wait(40);
        shot("iv-creative.png");
        // 下排的分類（選中後 pill 在下排）
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.tabFood); return true; } });
        wait(40);
        shot("iv-creative-bottom.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            selectCreative(mc, net.minecraft.creativetab.CreativeTabs.tabBlock); return true; } });
        wait(30);
        // 問題 1：滑桿按住拖曳（devDragging 代替按住滑鼠，currentScroll 由腳本每幀往下推）
        final java.lang.reflect.Field fScroll = creativeField("field_147067_x", "currentScroll");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.hook.GlassCreative.devDragging = true; return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try { if (fScroll != null) fScroll.setFloat(mc.currentScreen, Math.min(1f, f * 0.06f)); } catch (Throwable ignored) {}
            capture(mc, String.format("iv-scroll-held-%02d.png", f));
            return f >= 11; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            dev.s1mp1e.glass.hook.GlassCreative.devDragging = false; return true; } });
        burst("iv-scroll-release", 8);
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            try { if (fScroll != null) fScroll.setFloat(mc.currentScreen, 0f); } catch (Throwable ignored) {}
            mc.displayGuiScreen(null); return true; } });
        wait(10);
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                mc.getIntegratedServer().getConfigurationManager().playerEntityList.get(0)
                  .setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
            } catch (Throwable ignored) {} } });
            return true; } });
        wait(20);
    }

    // ---- hud：第 7 組 HUD 動態 -------------------------------------------------------------------------

    private static net.minecraft.scoreboard.Scoreboard serverScoreboard(Minecraft mc) {
        return mc.getIntegratedServer().worldServers[0].getScoreboard();
    }

    private static void queueHud() {
        add(new Scene() { public boolean step(Minecraft mc, int f) { mc.displayGuiScreen(null); return true; } });
        wait(20);
        // 聊天進場：先放兩行舊訊息，再來一則新訊息 → 整欄往上滑、新行從下面升起淡入
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            net.minecraft.client.gui.GuiNewChat c = mc.ingameGUI.getChatGUI();
            c.printChatMessage(new net.minecraft.util.ChatComponentText("<Steve> 玻璃聊天"));
            c.printChatMessage(new net.minecraft.util.ChatComponentText("<Alex> settled line"));
            return true; } });
        wait(40);
        shot("na-chat-arrival-pre.png");
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            mc.ingameGUI.getChatGUI().printChatMessage(
                    new net.minecraft.util.ChatComponentText("§bS1mp1e§r 新訊息從底部升起"));
            return true; } });
        burst("na-chat-arrival", 14);
        wait(10);
        // 聊天關閉：打開輸入框、打字、關掉 → 輸入框玻璃淡出、字往上飄走
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
        // Tab 清單淡入／淡出（單人世界只有在有計分項目或多於一個玩家時才顯示；放一個假的計分項目到清單欄位）
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = serverScoreboard(mc);
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devshot");
                if (o == null) o = sb.addScoreObjective("devshot", net.minecraft.scoreboard.IScoreObjectiveCriteria.DUMMY);
                sb.setObjectiveInDisplaySlot(0, o);
                sb.getValueFromObjective(mc.thePlayer.getName(), o).setScorePoints(7);
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
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = serverScoreboard(mc);
                sb.setObjectiveInDisplaySlot(0, null);
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devshot");
                if (o != null) sb.removeObjective(o);
            } catch (Throwable ignored) {} } });
            return true; } });
        wait(10);
        // 計分板側欄淡入／淡出
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = serverScoreboard(mc);
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devside");
                if (o == null) o = sb.addScoreObjective("devside", net.minecraft.scoreboard.IScoreObjectiveCriteria.DUMMY);
                o.setDisplayName("S1mp1e 計分板");
                sb.getValueFromObjective("玻璃", o).setScorePoints(12);
                sb.getValueFromObjective("Steve", o).setScorePoints(7);
                sb.getValueFromObjective("Alex", o).setScorePoints(3);
                sb.setObjectiveInDisplaySlot(1, o);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] sidebar: " + t); } } });
            return true; } });
        burst("na-scoreboard-in", 12);
        wait(20);
        shot("na-scoreboard-settled.png");
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.scoreboard.Scoreboard sb = serverScoreboard(mc);
                sb.setObjectiveInDisplaySlot(1, null);
                net.minecraft.scoreboard.ScoreObjective o = sb.getObjective("devside");
                if (o != null) sb.removeObjective(o);
            } catch (Throwable ignored) {} } });
            return true; } });
        burst("na-scoreboard-out", 12);
        wait(10);
        // 扣血拖尾：伺服器端對玩家造成 6 點一般傷害，然後連拍（每 2 幀一張）
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                net.minecraft.entity.player.EntityPlayerMP sp =
                        mc.getIntegratedServer().getConfigurationManager().playerEntityList.get(0);
                sp.attackEntityFrom(net.minecraft.util.DamageSource.generic, 6f);
            } catch (Throwable t) { System.out.println("[S1mp1e][DevShot] damage: " + t); } } });
            return true; } });
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            if (f % 2 == 0) capture(mc, String.format("na-health-trail-%02d.png", f / 2));
            return f >= 47; } });
        add(new Scene() { public boolean step(final Minecraft mc, int f) {
            onServer(mc, new Runnable() { public void run() { try {
                mc.getIntegratedServer().getConfigurationManager().playerEntityList.get(0).setHealth(20f);
            } catch (Throwable ignored) {} } });
            return true; } });
        wait(10);
    }

    // ============================================================================================
    //  共用
    // ============================================================================================

    private static void mark(final String name) {
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            System.out.println("[S1mp1e][DevShot] mark " + name); return true; } });
    }

    private static void click(Minecraft mc, int slot, int button, int mode) {
        if (!(mc.currentScreen instanceof GuiContainer)) return;
        GuiContainer gc = (GuiContainer) mc.currentScreen;
        mc.playerController.windowClick(gc.inventorySlots.windowId, slot, button, mode, mc.thePlayer);
    }

    /** 玩家容器裡第一個有東西的主背包／熱鍵列格子（9..44）。 */
    private static int filledSlot(Minecraft mc) {
        GuiContainer gc = (GuiContainer) mc.currentScreen;
        for (int i = 9; i < gc.inventorySlots.inventorySlots.size(); i++)
            if (gc.inventorySlots.getSlot(i).getStack() != null) return i;
        return 36;
    }

    private static int emptySlot(Minecraft mc, int not) {
        GuiContainer gc = (GuiContainer) mc.currentScreen;
        for (int i = 9; i < 36; i++)
            if (i != not && gc.inventorySlots.getSlot(i).getStack() == null) return i;
        return 20;
    }

    // ============================================================================================
    //  基本動作
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

    private static void clearPointer() {
        add(new Scene() { public boolean step(Minecraft mc, int f) { clearPointerNow(); return true; } });
    }

    private static void clearPointerNow() {
        SettingsShell.devX = Float.NaN; SettingsShell.devY = Float.NaN;
        VanillaSliderSkin.devMouseDown = false;
    }

    private static void shot(final String name) {
        add(new Scene() { public boolean step(Minecraft mc, int f) { capture(mc, name); return true; } });
    }

    private static void burst(final String tag, final int n) {
        add(new Scene() { public boolean step(Minecraft mc, int f) {
            capture(mc, String.format("%s-%02d.png", tag, f)); return f >= n - 1; } });
    }

    private static void capture(Minecraft mc, String name) { DevShot.captureExternal(mc, name); }
}
