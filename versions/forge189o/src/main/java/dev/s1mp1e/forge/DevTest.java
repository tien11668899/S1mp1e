package dev.s1mp1e.forge;

import java.lang.reflect.Field;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.gui.screen.inventory.menu.SurvivalInventoryScreen;
import net.minecraft.inventory.slot.InventorySlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.entity.living.player.ServerPlayerEntity;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.WorldGeneratorType;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 開發用自動測試（S1FORGE_TEST=itemscroller）：建平坦世界、熱鍵列放 64 石頭、開背包、
 * 把滑鼠事件設成「在那格往上滾一格」再發 GuiScreenEvent.MouseInputEvent.Pre，看 Item Scroller 有沒有搬動物品。
 * 結束時照「儲存並離開」先離開世界回標題，再關遊戲。正常遊戲不設環境變數時完全不作用。
 */
public final class DevTest {
    private static final int HOTBAR_INDEX = 2;
    private int phase;
    private int frames;
    private String result = "未執行";

    private static boolean EVENTS;
    private static final java.util.Map<String, Integer> PROBE = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean confirmed;
    private long phase11Ms;

    /** events 測試：收所有 Forge 事件，記每種被發了幾次 */
    public static final class Probe {
        @SubscribeEvent(receiveCanceled = true)
        public void any(net.minecraftforge.fml.common.eventhandler.Event e) {
            String n = e.getClass().getName();
            n = n.substring(n.lastIndexOf('.') + 1);
            PROBE.merge(n, 1, Integer::sum);
        }
    }

    /** 探針要和模組一樣在 FML 載入時就註冊：Forge 1.8.9 的 ListenerList 對「晚註冊在父類別」的監聽者，已快取的子類別看不到 */
    public static void installProbe() {
        String t = System.getenv("S1FORGE_TEST");
        if (t != null && t.contains("events")) MinecraftForge.EVENT_BUS.register(new Probe());
    }

    private volatile long lastPhaseMs = System.currentTimeMillis();
    private volatile int watchedPhase = -1;

    public static void install() {
        String t = System.getenv("S1FORGE_TEST");
        if (t == null) return;
        S1Forge.LOG.info("[test] 啟用測試 {}", t);
        DevTest test = new DevTest();
        MinecraftForge.EVENT_BUS.register(test);
        EVENTS = t.contains("events");
        // 看門狗：同一階段卡超過 25 秒就印所有執行緒堆疊（找卡死點），60 秒強制結束
        Thread w = new Thread(() -> {
            boolean dumped = false;
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (test.phase != test.watchedPhase) {
                    test.watchedPhase = test.phase;
                    test.lastPhaseMs = System.currentTimeMillis();
                    S1Forge.LOG.info("[test] 階段 {}", test.phase);
                }
                long stuck = System.currentTimeMillis() - test.lastPhaseMs;
                if (stuck > 25_000 && !dumped) {
                    dumped = true;
                    StringBuilder sb = new StringBuilder("[test] 卡在階段 " + test.phase + "，執行緒堆疊：\n");
                    for (var e : Thread.getAllStackTraces().entrySet()) {
                        sb.append("--- ").append(e.getKey().getName()).append(" (").append(e.getKey().getState()).append(")\n");
                        for (StackTraceElement el : e.getValue()) sb.append("    at ").append(el).append("\n");
                    }
                    S1Forge.LOG.error(sb.toString());
                }
                if (stuck > 60_000) {
                    S1Forge.LOG.error("[test] 卡死，強制結束；結果：{}", test.result);
                    Runtime.getRuntime().halt(3);
                }
            }
        }, "s1forge-test-watchdog");
        w.setDaemon(true);
        w.start();
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        frames++;
        try {
            step(mc);
        } catch (Throwable t) {
            S1Forge.LOG.error("[test] 步驟 {} 失敗", phase, t);
            result = "例外：" + t;
            phase = 4;
            frames = 0;
        }
    }

    private void step(Minecraft mc) throws Exception {
        switch (phase) {
            case 0 -> {   // 標題畫面 → 建世界
                if (!(mc.screen instanceof TitleScreen) || frames < 30) return;
                try {
                    mc.getWorldStorageSource().flush();
                    mc.getWorldStorageSource().delete("s1ftest");
                } catch (Throwable ignored) {
                }
                WorldSettings ws = new WorldSettings(4242L, WorldSettings.GameMode.SURVIVAL, false, false, WorldGeneratorType.FLAT);
                ws.enableCommands();
                phase = 1;
                frames = 0;
                mc.startGame("s1ftest", "s1ftest", ws);
            }
            case 1 -> {   // 世界載好 → 給石頭、開背包
                if (mc.world == null || mc.player == null || mc.getServer() == null || frames < 100) return;
                ServerPlayerEntity sp = mc.getServer().getPlayerManager().getAll().get(0);
                sp.inventory.setItem(HOTBAR_INDEX, new ItemStack(Blocks.STONE, 64));
                sp.playerMenu.updateListeners();
                mc.player.inventory.setItem(HOTBAR_INDEX, new ItemStack(Blocks.STONE, 64));
                mc.openScreen(new SurvivalInventoryScreen(mc.player));
                phase = 2;
                frames = 0;
            }
            case 2 -> {   // 背包開了一陣子 → 模擬滾輪
                if (!(mc.screen instanceof InventoryMenuScreen gui) || frames < 40) return;
                InventorySlot target = null;
                for (InventorySlot s : gui.menu.slots) {
                    ItemStack st = s.getItem();
                    if (s.equals(mc.player.inventory, HOTBAR_INDEX) && st != null && st.getItem() == new ItemStack(Blocks.STONE).getItem()) target = s;
                }
                if (target == null) {
                    StringBuilder d = new StringBuilder();
                    for (InventorySlot s : gui.menu.slots) if (s.getItem() != null) d.append(" [").append(s.index).append(s.inventory == mc.player.inventory ? "P" : "o").append("]").append(s.getItem());
                    d.append(" | 玩家熱鍵列2=").append(mc.player.inventory.getItem(HOTBAR_INDEX)).append(" 石頭物品=").append(new ItemStack(Blocks.STONE).getItem());
                    result = "失敗：背包裡找不到熱鍵列第 " + HOTBAR_INDEX + " 格的石頭；" + d;
                    phase = 4;
                    frames = 0;
                    return;
                }
                int before = target.getItem().size;
                setStatic(InventoryMenuScreen.class, gui, rt("hoveredSlot", "f_71971935"), target);
                Class<?> mouse = Class.forName("org.lwjgl.input.Mouse");
                setStatic(mouse, null, "eventWheelDelta", 120.0);
                boolean canceled = MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Pre(gui));
                setStatic(mouse, null, "eventWheelDelta", 0.0);
                int after = target.getItem() == null ? 0 : target.getItem().size;
                int elsewhere = 0;
                for (InventorySlot s : gui.menu.slots) {
                    if (s == target || s.getItem() == null) continue;
                    if (s.getItem().getItem() == target.getItem().getItem() || (target.getItem() == null && s.getItem().getItem() == new ItemStack(Blocks.STONE).getItem())) elsewhere += s.getItem().size;
                }
                result = String.format("滾輪前 %d 個，滾輪後該格 %d 個、其他格 %d 個，事件%s取消", before, after, elsewhere, canceled ? "被" : "未被");
                result = (after != before ? "通過：" : "失敗：物品沒動；") + result;
                S1Forge.LOG.info("[test] itemscroller 結果：{}", result);
                phase = 3;
                frames = 0;
            }
            case 3 -> {   // 停一下（讓伺服器同步）→ 關背包；events 測試再主動觸發幾種事件
                if (frames < 20) return;
                mc.openScreen(null);
                frames = 0;
                if (!EVENTS) { phase = 4; return; }
                new ItemStack(Blocks.STONE).getTooltip(mc.player, false);   // ItemTooltipEvent
                mc.player.sendChat("s1forge event probe");                 // ClientChatReceivedEvent（伺服器回顯）
                mc.getSoundManager().play(net.minecraft.client.sound.instance.SimpleSoundInstance.of(new net.minecraft.resource.Identifier("gui.button.press"), 1.0F));
                mc.openScreen(new net.minecraft.client.gui.screen.ConfirmScreen((ok, id) -> {
                    confirmed = true;
                    Minecraft.getInstance().openScreen(null);
                }, "s1forge", "probe", 0));
                phase = 10;
            }
            case 10 -> {   // 點確認畫面的第一顆按鈕（ActionPerformedEvent）
                if (!(mc.screen instanceof net.minecraft.client.gui.screen.ConfirmScreen cs) || frames < 10) {
                    if (frames > 200) { phase = 11; frames = 0; }
                    return;
                }
                Field bf = net.minecraft.client.gui.screen.Screen.class.getDeclaredField(rt("buttons", "f_78519977"));
                bf.setAccessible(true);
                java.util.List<?> bs = (java.util.List<?>) bf.get(cs);
                net.minecraft.client.gui.widget.ButtonWidget b = (net.minecraft.client.gui.widget.ButtonWidget) bs.get(0);
                java.lang.reflect.Method mc1 = net.minecraft.client.gui.screen.Screen.class.getDeclaredMethod(rt("mouseClicked", "m_23755539"), int.class, int.class, int.class);
                mc1.setAccessible(true);
                mc1.invoke(cs, b.x + 2, b.y + 2, 0);
                phase = 11;
                frames = 0;
            }
            case 11 -> {   // 等聊天回顯（以時間計，高幀率下幀數不可靠）→ 報告
                if (phase11Ms == 0) phase11Ms = System.currentTimeMillis();
                if (System.currentTimeMillis() - phase11Ms < 1500) return;
                try {   // entityculling 的 DebugHudMixin 會在 F3 左欄加剔除統計
                    Object dbg = new net.minecraft.client.gui.overlay.DebugOverlay(mc);
                    java.lang.reflect.Method gi = dbg.getClass().getDeclaredMethod(rt("getGameInfo", "m_05947706"));
                    gi.setAccessible(true);
                    java.util.List<?> lines = (java.util.List<?>) gi.invoke(dbg);
                    StringBuilder cul = new StringBuilder();
                    for (Object l : lines) if (String.valueOf(l).toLowerCase().contains("cull")) cul.append(" | ").append(l);
                    S1Forge.LOG.info("[test] F3 左欄 {} 行，entityculling 行：{}", lines.size(), cul.length() == 0 ? "（無）" : cul);
                } catch (Throwable t) {
                    S1Forge.LOG.warn("[test] F3 檢查失敗", t);
                }
                java.util.TreeMap<String, Integer> sorted = new java.util.TreeMap<>(PROBE);
                S1Forge.LOG.info("[test] events 確認畫面回呼={}；觸發過的 Forge 事件（{} 種）：{}", confirmed, sorted.size(), sorted);
                phase = 4;
                frames = 0;
            }
            case 4 -> {   // 先離開世界（儲存）回標題
                if (mc.world != null) {
                    mc.world.disconnect();
                    mc.setWorld(null);
                    mc.openScreen(new TitleScreen());
                    frames = 0;
                    return;
                }
                if (frames < 20) return;
                S1Forge.LOG.info("[test] itemscroller 結果：{}", result);
                phase = 5;
                mc.stop();
            }
            default -> {
            }
        }
    }

    /** 反射用名稱：dev（named）或正式版（intermediary） */
    static String rt(String named, String intermediary) {
        return S1Forge.namespace().equals("named") ? named : intermediary;
    }

    static void setStatic(Class<?> c, Object obj, String name, Object v) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, v);
    }
}
