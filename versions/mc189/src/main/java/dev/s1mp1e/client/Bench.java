package dev.s1mp1e.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.lwjgl.opengl.Display;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 1.8.9 的可重現效能量測（只給開發／量測用）。照 26.2 {@code Bench} 的方法論縮寫成 1.8.9 版。
 * <b>沒有設環境變數 {@code S1MP1E_BENCH} 時完全不作用</b>。
 *
 * <ul>
 *   <li>{@code S1MP1E_BENCH=prep}：建立量測世界 {@code bench_master}（固定種子、正常地形、和平、生存＋飛行），沿 explore
 *       路線慢慢飛一趟，讓路線上的區塊都存在磁碟上，同時記下每一格的地面高度（相機貼地飛用），存檔離開世界後關遊戲。只要跑一次。</li>
 *   <li>{@code S1MP1E_BENCH=run}：把 {@code bench_master} 複製成 {@code bench} 打開，固定畫面設定與渲染解析度，依序跑
 *       {@code S1MP1E_BENCH_SCENARIOS}（預設 {@code explore,inventory}）。每個場景先在起點停住暖機（區塊載入、編譯），
 *       再照固定路線移動一段固定時間，<b>每一幀</b>都記錄；寫出 {@code <場景>_frames.csv} 和 {@code summary.json}
 *       到 {@code S1MP1E_BENCH_OUT}，最後存檔離開世界、回標題、關遊戲。</li>
 * </ul>
 *
 * <p>幀時間 = 兩次 RenderTickEvent END 之間的時間（和 DevShot 同一個點），就是 FPS 計數器看到的東西；量測窗內的每一幀
 * 都算，沒有挑幀。GC 停頓來自 JVM 的 GC 通知；視窗失焦的幀另外計數（失焦時顯卡可能降頻，那一輪判為無效）。
 *
 * <p>固定設定：高畫質、視距 12、不限幀、關垂直同步、視野 70、粒子全開、渲染解析度 {@code S1MP1E_BENCH_RES}（預設
 * 3840x2160）、介面大小 {@code S1MP1E_BENCH_GUI}（預設 4，和玩家 4K 時一樣大）。不降任何畫質、不藏任何東西。
 */
public final class Bench {

    private Bench() {}

    private static final long SEED = 20261003L;
    private static final String MASTER = "bench_master";
    private static final String COPY = "bench";
    private static final String PROFILE = "s1bench_profile.csv";
    private static int winW = 3840, winH = 2160, gui = 4;
    private static final int RENDER_DISTANCE = 12;

    // explore：每秒 10 格往 +X 飛 60 秒，貼地高 25 格，視角左右擺 ±25°（週期 20 秒），往下看 20°
    private static final double EXPLORE_SPEED = 10, EXPLORE_S = 60, WARM_S = 15, CLEAR = 25;
    private static final int ROUTE_LEN = (int) (EXPLORE_SPEED * EXPLORE_S) + 120;
    // inventory：在 explore 起點開著背包 30 秒
    private static final double INV_S = 30, INV_WARM_S = 5;

    private static boolean resolved;
    private static String mode;          // null = 不作用
    private static File out;
    private static String[] scenarios;

    private static final int P_WAIT_TITLE = 0, P_OPEN = 1, P_WAIT_WORLD = 2, P_SETUP = 3,
            P_PREP_FLY = 10, P_SCENARIO = 20, P_LEAVE = 90, P_QUIT = 91, P_DONE = 99;
    private static int phase = P_WAIT_TITLE;
    private static int frames;
    private static long phaseStartNs, lastNs;

    private static int[] ground;          // 地面高度（x = 0..ROUTE_LEN）
    private static double[] camY;         // 相機高度剖面
    private static double prepX;

    private static int scenarioIdx;
    private static boolean measuring;
    private static long[] ft = new long[1 << 16];
    private static int ftN;
    private static int unfocused;
    private static long measureStartMs;
    private static final List<String> results = new ArrayList<String>();
    private static final List<long[]> gcEvents = new ArrayList<long[]>();   // {時間 ms, 停頓 µs}
    private static boolean gcHooked;

    private static void log(String s) { System.out.println("[S1mp1e][Bench] " + s); }

    /** 每一幀的結尾呼叫（DevShotDriver）。 */
    public static void onRenderEnd(Minecraft mc) {
        if (!resolved) {
            resolved = true;
            try {
                String m = System.getenv("S1MP1E_BENCH");
                if (m == null || m.trim().isEmpty()) return;
                mode = m.trim().toLowerCase(Locale.ROOT);
                String o = System.getenv("S1MP1E_BENCH_OUT");
                out = new File(o == null || o.trim().isEmpty() ? "bench_out" : o.trim());
                out.mkdirs();
                String res = System.getenv("S1MP1E_BENCH_RES");
                if (res != null && res.matches("\\d+x\\d+")) {
                    winW = Integer.parseInt(res.split("x")[0]); winH = Integer.parseInt(res.split("x")[1]);
                }
                String g = System.getenv("S1MP1E_BENCH_GUI");
                if (g != null && g.matches("\\d+")) gui = Integer.parseInt(g);
                String sc = System.getenv("S1MP1E_BENCH_SCENARIOS");
                scenarios = (sc == null || sc.trim().isEmpty() ? "explore,inventory" : sc.trim()).split(",");
                log("mode=" + mode + " res=" + winW + "x" + winH + " gui=" + gui + " scenarios=" + Arrays.toString(scenarios)
                        + " out=" + out.getAbsolutePath() + " glass=" + (System.getenv("S1MP1E_BENCH_NOGLASS") == null ? "on" : "OFF"));
                hookGc();
            } catch (Throwable t) {
                mode = null;
            }
        }
        if (mode == null || mc == null || phase == P_DONE) return;
        long now = System.nanoTime();
        long dt = lastNs == 0 ? 0 : now - lastNs;
        lastNs = now;
        try {
            forceFramebuffer(mc);
            step(mc, now, dt);
        } catch (Throwable t) {
            log("failed at phase " + phase + ": " + t);
            t.printStackTrace(System.out);
            phase = P_LEAVE;
        }
    }

    private static void step(Minecraft mc, long now, long dt) {
        switch (phase) {
            case P_WAIT_TITLE:
                if (mc.currentScreen instanceof GuiMainMenu && ++frames > 60) {
                    pinSettings(mc);
                    frames = 0; phase = P_OPEN;
                }
                break;
            case P_OPEN:
                if ("prep".equals(mode)) createMaster(mc);
                else openCopy(mc);
                frames = 0; phase = P_WAIT_WORLD;
                break;
            case P_WAIT_WORLD:
                if (mc.theWorld != null && mc.thePlayer != null && mc.getIntegratedServer() != null) {
                    if (mc.currentScreen != null) mc.displayGuiScreen(null);
                    frames = 0; phase = P_SETUP;
                } else if (++frames > 6000) {
                    log("world never loaded"); phase = P_QUIT;
                }
                break;
            case P_SETUP:
                if (frames++ == 0) worldSetup(mc);
                if (frames < 40) break;
                if ("prep".equals(mode)) {
                    ground = new int[ROUTE_LEN + 1];
                    Arrays.fill(ground, Integer.MIN_VALUE);
                    prepX = 0; phaseStartNs = now; phase = P_PREP_FLY;
                } else {
                    if (!loadProfile(mc)) { log("no terrain profile: run S1MP1E_BENCH=prep first"); phase = P_LEAVE; break; }
                    scenarioIdx = 0; startScenario(mc, now);
                }
                break;
            case P_PREP_FLY:
                prepFly(mc);
                break;
            case P_SCENARIO:
                runScenario(mc, now, dt);
                break;
            case P_LEAVE:
                leaveWorld(mc);
                frames = 0; phase = P_QUIT;
                break;
            case P_QUIT:
                if (mc.theWorld != null) { leaveWorld(mc); frames = 0; break; }
                if (++frames < 20) break;
                log("done, quitting");
                phase = P_DONE;
                try { mc.shutdown(); } catch (Throwable ignored) {}
                break;
            default:
                break;
        }
    }

    // ---- prep：沿路線飛一趟、記地面高度 ---------------------------------------------------------------------------

    private static void prepFly(Minecraft mc) {
        int x = (int) prepX;
        // 這一格（z = −6, 0, 6）的區塊在客戶端載入了才記、才往前；沒載入就停在原地等
        int g = Integer.MIN_VALUE;
        boolean ready = true;
        for (int z = -6; z <= 6; z += 6) {
            BlockPos p = new BlockPos(x, 0, z);
            if (!mc.theWorld.getChunkFromBlockCoords(p).isLoaded()) { ready = false; break; }
            g = Math.max(g, mc.theWorld.getHeight(p).getY());
        }
        if (ready && g > 0) {
            ground[x] = g;
            prepX += 0.5;                       // 每幀前進半格：區塊產生跟得上
        }
        double y = Math.max(g, 64) + 30;
        place(mc, prepX, y, 0, -90f, 30f);
        if (((int) prepX) % 32 == 0 && ready) syncServer(mc, prepX, y, 0);
        if (prepX >= ROUTE_LEN) {
            saveProfile(mc);
            phase = P_LEAVE;
        }
    }

    private static void saveProfile(Minecraft mc) {
        File f = new File(new File(new File(mc.mcDataDir, "saves"), MASTER), PROFILE);
        try {
            PrintWriter w = new PrintWriter(new FileWriter(f));
            try { for (int x = 0; x <= ROUTE_LEN; x++) w.println(x + "," + ground[x]); } finally { w.close(); }
            log("profile written: " + f);
        } catch (IOException e) {
            log("profile write failed: " + e);
        }
    }

    private static boolean loadProfile(Minecraft mc) {
        File f = new File(new File(new File(mc.mcDataDir, "saves"), COPY), PROFILE);
        if (!f.isFile()) return false;
        try {
            ground = new int[ROUTE_LEN + 1];
            for (String line : Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] p = line.split(",");
                int x = Integer.parseInt(p[0].trim());
                if (x >= 0 && x <= ROUTE_LEN) ground[x] = Integer.parseInt(p[1].trim());
            }
        } catch (Throwable t) {
            return false;
        }
        // 相機高度：前方 30 格、後方 10 格內的最高地面 + CLEAR，再做 40 格移動平均（平順，不會撞山）
        double[] raw = new double[ROUTE_LEN + 1];
        for (int x = 0; x <= ROUTE_LEN; x++) {
            int m = 64;
            for (int k = Math.max(0, x - 10); k <= Math.min(ROUTE_LEN, x + 30); k++) m = Math.max(m, ground[k]);
            raw[x] = m + CLEAR;
        }
        camY = new double[ROUTE_LEN + 1];
        for (int x = 0; x <= ROUTE_LEN; x++) {
            double s = 0; int n = 0;
            for (int k = Math.max(0, x - 20); k <= Math.min(ROUTE_LEN, x + 20); k++) { s += raw[k]; n++; }
            camY[x] = Math.max(raw[x], s / n);
        }
        return true;
    }

    private static double camYAt(double x) {
        int i = (int) Math.max(0, Math.min(ROUTE_LEN - 1, Math.floor(x)));
        double f = x - i;
        return camY[i] + (camY[i + 1] - camY[i]) * f;
    }

    // ---- run：場景 ----------------------------------------------------------------------------------------------

    private static void startScenario(Minecraft mc, long now) {
        if (scenarioIdx >= scenarios.length) { writeSummary(); phase = P_LEAVE; return; }
        String s = scenarios[scenarioIdx].trim();
        log("scenario " + s + ": warm-up");
        phaseStartNs = now; measuring = false; ftN = 0; unfocused = 0;
        if ("inventory".equals(s)) mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
        else if (mc.currentScreen != null) mc.displayGuiScreen(null);
        phase = P_SCENARIO;
    }

    private static void runScenario(Minecraft mc, long now, long dt) {
        String s = scenarios[scenarioIdx].trim();
        boolean inv = "inventory".equals(s);
        double warm = inv ? INV_WARM_S : WARM_S, len = inv ? INV_S : EXPLORE_S;
        double t = (now - phaseStartNs) / 1e9;
        double mt = Math.max(0, t - warm);                    // 量測窗內經過的秒數
        double x = inv ? 0 : EXPLORE_SPEED * mt;
        float yaw = (float) (-90 + (inv ? 0 : 25 * Math.sin(2 * Math.PI * mt / 20)));
        place(mc, x, camYAt(x), 0, yaw, 20f);
        if (frames++ % 10 == 0) syncServer(mc, x, camYAt(x), 0);
        if (inv && !(mc.currentScreen instanceof GuiInventory)) mc.displayGuiScreen(new GuiInventory(mc.thePlayer));

        if (!measuring && t >= warm) {
            measuring = true; ftN = 0; unfocused = 0; measureStartMs = System.currentTimeMillis();
            log("scenario " + s + ": measuring " + len + " s");
            return;                                           // 這一幀的 dt 屬於暖機
        }
        if (measuring) {
            if (ftN == ft.length) ft = Arrays.copyOf(ft, ft.length * 2);
            ft[ftN++] = dt;
            if (!Display.isActive()) unfocused++;
            if (t >= warm + len) {
                results.add(summarize(s));
                writeFrames(s);
                scenarioIdx++;
                startScenario(mc, now);
            }
        }
    }

    private static String summarize(String name) {
        long[] a = Arrays.copyOf(ft, ftN);
        long total = 0;
        for (long v : a) total += v;
        Arrays.sort(a);
        int n = a.length;
        double secs = total / 1e9, avg = n / secs;
        int worst = Math.max(1, n / 100);
        long ws = 0;
        for (int i = n - worst; i < n; i++) ws += a[i];
        double low1 = worst / (ws / 1e9);
        double p50 = a[Math.min(n - 1, Math.round(n * 0.50f))] / 1e6;
        double p99 = a[Math.min(n - 1, Math.round(n * 0.99f))] / 1e6;
        double max = a[n - 1] / 1e6;
        int over20 = 0;
        for (long v : a) if (v > 20_000_000L) over20++;
        long endMs = System.currentTimeMillis();
        int gcN = 0; double gcTot = 0, gcMax = 0;
        synchronized (gcEvents) {
            for (long[] e : gcEvents) {
                if (e[0] < measureStartMs || e[0] > endMs) continue;
                gcN++; gcTot += e[1] / 1000.0; gcMax = Math.max(gcMax, e[1] / 1000.0);
            }
        }
        String r = String.format(Locale.ROOT,
                "{\"scenario\":\"%s\",\"frames\":%d,\"seconds\":%.2f,\"avgFps\":%.1f,\"low1Fps\":%.1f,\"p50Ms\":%.3f,"
                        + "\"p99Ms\":%.3f,\"maxMs\":%.2f,\"framesOver20ms\":%d,\"gcPauses\":%d,\"gcTotalMs\":%.1f,"
                        + "\"gcMaxMs\":%.1f,\"unfocusedFrames\":%d,\"valid\":%s}",
                name, n, secs, avg, low1, p50, p99, max, over20, gcN, gcTot, gcMax, unfocused, unfocused == 0 ? "true" : "false");
        log("result " + r);
        return r;
    }

    private static void writeFrames(String name) {
        try {
            PrintWriter w = new PrintWriter(new FileWriter(new File(out, name + "_frames.csv")));
            try { for (int i = 0; i < ftN; i++) w.println(ft[i]); } finally { w.close(); }
        } catch (IOException ignored) {}
    }

    private static void writeSummary() {
        StringBuilder b = new StringBuilder("{\n\"mc\":\"1.8.9\",\n");
        b.append("\"res\":\"").append(winW).append('x').append(winH).append("\",\n\"gui\":").append(gui).append(",\n");
        b.append("\"renderDistance\":").append(RENDER_DISTANCE).append(",\n");
        b.append("\"glass\":\"").append(System.getenv("S1MP1E_BENCH_NOGLASS") == null ? "on" : "off").append("\",\n");
        b.append("\"java\":\"").append(System.getProperty("java.version")).append("\",\n");
        b.append("\"gpu\":\"").append(String.valueOf(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)).replace("\"", "'")).append("\",\n");
        b.append("\"results\":[\n");
        for (int i = 0; i < results.size(); i++) b.append(results.get(i)).append(i + 1 < results.size() ? ",\n" : "\n");
        b.append("]}\n");
        try {
            FileWriter w = new FileWriter(new File(out, "summary.json"));
            try { w.write(b.toString()); } finally { w.close(); }
            log("summary written");
        } catch (IOException e) {
            log("summary write failed: " + e);
        }
    }

    // ---- 世界與設定 ---------------------------------------------------------------------------------------------

    private static void pinSettings(Minecraft mc) {
        net.minecraft.client.settings.GameSettings o = mc.gameSettings;
        o.fancyGraphics = true;
        o.renderDistanceChunks = RENDER_DISTANCE;
        o.limitFramerate = 260;                 // 260 = 不限
        o.enableVsync = false;
        try { Display.setVSyncEnabled(false); } catch (Throwable ignored) {}
        o.fovSetting = 70f;
        o.particleSetting = 0;                  // 全部
        o.guiScale = gui;
        o.pauseOnLostFocus = false;
        log("settings: fancy rd=" + RENDER_DISTANCE + " fps=unlimited vsync=off fov=70 particles=all gui=" + gui
                + " mipmap=" + o.mipmapLevels + " clouds=" + o.clouds + " smoothLighting=" + o.ambientOcclusion);
    }

    private static void forceFramebuffer(Minecraft mc) {
        if (mc.displayWidth == winW && mc.displayHeight == winH) return;
        mc.resize(winW, winH);
    }

    private static void createMaster(Minecraft mc) {
        try { mc.getSaveLoader().flushCache(); mc.getSaveLoader().deleteWorldDirectory(MASTER); } catch (Throwable ignored) {}
        WorldSettings ws = new WorldSettings(SEED, WorldSettings.GameType.SURVIVAL, true, false, WorldType.DEFAULT);
        ws.enableCommands();
        log("creating " + MASTER + " (seed " + SEED + ")");
        mc.launchIntegratedServer(MASTER, MASTER, ws);
    }

    private static void openCopy(Minecraft mc) {
        File saves = new File(mc.mcDataDir, "saves");
        File src = new File(saves, MASTER), dst = new File(saves, COPY);
        try {
            if (dst.exists()) deleteTree(dst.toPath());
            copyTree(src.toPath(), dst.toPath());
        } catch (IOException e) {
            log("copy failed: " + e);
        }
        log("opening " + COPY);
        mc.launchIntegratedServer(COPY, COPY, null);
    }

    private static void worldSetup(final Minecraft mc) {
        final IntegratedServer srv = mc.getIntegratedServer();
        srv.addScheduledTask(new Runnable() { public void run() {
            try {
                net.minecraft.world.WorldServer w = srv.worldServers[0];
                w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                w.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
                w.setWorldTime(6000);
                w.getWorldInfo().setRaining(false);
                w.getWorldInfo().setThundering(false);
                w.getWorldInfo().setDifficulty(net.minecraft.world.EnumDifficulty.PEACEFUL);
                EntityPlayerMP sp = srv.getConfigurationManager().playerEntityList.get(0);
                sp.capabilities.allowFlying = true;
                sp.capabilities.isFlying = true;
                sp.sendPlayerAbilities();
            } catch (Throwable t) { log("world setup: " + t); }
        } });
        try { mc.ingameGUI.getChatGUI().clearChatMessages(); } catch (Throwable ignored) {}
    }

    /** 相機每幀放到路線上（位置與上一幀位置一起設，內插不會抖）。 */
    private static void place(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) return;
        p.capabilities.isFlying = true;
        p.setPosition(x, y, z);
        p.prevPosX = x; p.prevPosY = y; p.prevPosZ = z;
        p.lastTickPosX = x; p.lastTickPosY = y; p.lastTickPosZ = z;
        p.rotationYaw = yaw; p.prevRotationYaw = yaw; p.rotationYawHead = yaw; p.prevRotationYawHead = yaw;
        p.rotationPitch = pitch; p.prevRotationPitch = pitch;
        p.motionX = 0; p.motionY = 0; p.motionZ = 0;
    }

    /** 伺服器端的玩家也搬過去，區塊才會在相機附近載入（只能在伺服器執行緒上改伺服器狀態）。 */
    private static void syncServer(Minecraft mc, final double x, final double y, final double z) {
        final IntegratedServer srv = mc.getIntegratedServer();
        if (srv == null) return;
        srv.addScheduledTask(new Runnable() { public void run() {
            try {
                EntityPlayerMP sp = srv.getConfigurationManager().playerEntityList.get(0);
                sp.setPositionAndUpdate(x, y, z);
            } catch (Throwable ignored) {}
        } });
    }

    private static void leaveWorld(Minecraft mc) {
        if (mc.theWorld == null) return;
        log("leaving the world (save + quit to title)");
        try {
            mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
            mc.displayGuiScreen(new GuiMainMenu());
        } catch (Throwable t) {
            log("leave world: " + t);
        }
    }

    private static void hookGc() {
        if (gcHooked) return;
        gcHooked = true;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter)) continue;
            ((NotificationEmitter) gc).addNotificationListener(new NotificationListener() {
                public void handleNotification(Notification n, Object hb) {
                    try {
                        if (!"com.sun.management.gc.notification".equals(n.getType())) return;
                        CompositeData cd = (CompositeData) n.getUserData();
                        CompositeData info = (CompositeData) cd.get("gcInfo");
                        long durMs = (Long) info.get("duration");
                        synchronized (gcEvents) { gcEvents.add(new long[]{System.currentTimeMillis(), durMs * 1000}); }
                    } catch (Throwable ignored) {}
                }
            }, null, null);
        }
    }

    private static void copyTree(final Path src, final Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(d)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (!f.getFileName().toString().equals("session.lock"))
                    Files.copy(f, dst.resolve(src.relativize(f)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) throws IOException {
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }
}
