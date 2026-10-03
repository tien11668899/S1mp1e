package dev.s1mp1e.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Reproducible in-game performance benchmark (dev only). Completely inert unless the environment variable
 * {@code S1MP1E_BENCH} is set:
 *
 * <ul>
 *   <li>{@code S1MP1E_BENCH=prep} — creates the benchmark world {@code bench_master} (fixed seed, normal terrain),
 *       flies every route once so all chunks on them exist on disk, builds the entity pen (400 still mobs on a platform
 *       in the air), saves and quits. Run once.</li>
 *   <li>{@code S1MP1E_BENCH=run} — copies {@code bench_master} to {@code bench}, opens it, pins the window and the
 *       graphics settings, then runs the scenarios in {@code S1MP1E_BENCH_SCENARIOS} (default
 *       {@code explore,chunkload,entities}): each holds still at its start for the warm-up (chunks around the start load
 *       and compile), then moves the camera along a fixed path for a fixed time while every frame time is recorded.
 *       Writes {@code <scenario>_frames.csv}, a mid-run screenshot and {@code summary.json} into
 *       {@code S1MP1E_BENCH_OUT}, then leaves (the world saves on the normal shutdown).</li>
 * </ul>
 *
 * <p>Frame time = time between two consecutive ends of {@code Minecraft.renderFrame} (the same hook DevShot uses), so
 * it is exactly what an FPS counter sees. Nothing is hidden or skipped to make numbers better: every rendered frame of a
 * measured window counts. GC pauses come from the JVM's GC notifications, CPU and heap from the platform MXBeans.
 * The camera is placed every frame (position and previous position both set, so interpolation adds no jitter); the
 * player is a survival player flying, so the normal survival HUD (hotbar, hearts, food) is drawn as in real play.
 */
public final class Bench {

    private Bench() {}

    // ---- configuration --------------------------------------------------------------------------------------------
    private static final long SEED = 20261003L;
    private static final String MASTER = "bench_master";
    private static final String COPY = "bench";
    /** Render resolution: {@code S1MP1E_BENCH_RES=WxH} (default 3840x2160), GUI scale {@code S1MP1E_BENCH_GUI} (default 4,
     *  so HUD and glass cover the same share of the screen as for a player at 4K). */
    private static int WIN_W = 3840, WIN_H = 2160, GUI = 4;
    private static final int RENDER_DISTANCE = 12, SIM_DISTANCE = 12;

    /** One measured scenario: hold still {@code warmS} at the start pose, then follow the path for {@code measureS}. */
    private static final class Scenario {
        final String name; final double x0, y0, z0, vx, vz, yaw0, yawAmp, yawPeriodS, pitch; final double warmS, measureS;
        Scenario(String name, double x0, double y0, double z0, double vx, double vz, double yaw0, double yawAmp,
                 double yawPeriodS, double pitch, double warmS, double measureS) {
            this.name = name; this.x0 = x0; this.y0 = y0; this.z0 = z0; this.vx = vx; this.vz = vz; this.yaw0 = yaw0;
            this.yawAmp = yawAmp; this.yawPeriodS = yawPeriodS; this.pitch = pitch; this.warmS = warmS; this.measureS = measureS;
        }
    }

    // yaw -90 faces +X in Minecraft
    private static final Scenario EXPLORE = new Scenario("explore", 0, -1, 0, 10, 0, -90, 25, 20, 20, 15, 60);
    private static final Scenario CHUNKLOAD = new Scenario("chunkload", 0, -1, 3000, 40, 0, -90, 0, 1, 25, 15, 45);
    // entity pen: 40x40 platform at y=150 centred on (-3000, -3000); camera at its west edge looking east and down
    private static final int PEN_X = -3000, PEN_Z = -3000, PEN_Y = 150, PEN_R = 20;
    private static final Scenario ENTITIES = new Scenario("entities", PEN_X - PEN_R - 6, PEN_Y + 9, PEN_Z, 0, 0, -90, 20,
            15, 25, 10, 30);
    private static final Scenario[] ALL = {EXPLORE, CHUNKLOAD, ENTITIES};

    // ---- state ----------------------------------------------------------------------------------------------------
    private static boolean resolved;
    private static String mode;                // "prep" | "run" | null
    private static File out;
    private static final List<Scenario> todo = new ArrayList<>();
    private static int phase;
    private static final int P_TITLE = 0, P_WAIT_WORLD = 1, P_SETUP = 2, P_SCEN = 3, P_PREP = 4, P_DRAIN = 5, P_DONE = 6,
            P_PART = 7, P_ROUTES = 8, P_PROFILE = 9, P_DONECHECK = 10;
    private static int dcStep, dcScrolls;
    private static int partStep;
    private static final String[] PART_MODES = {"off", "Reduced", "None"};
    private static long phaseStartNs;
    private static int frames;

    // scenario run state
    private static int scenIdx;
    private static boolean measuring;
    private static long scenStartNs, measureStartNs, measureStartMs;
    private static long lastFrameNs;
    private static long[] ft = new long[400_000];
    private static int ftN;
    private static boolean midShot;
    private static long lastSampleNs;
    private static final List<double[]> cpuSamples = Collections.synchronizedList(new ArrayList<>());   // {cpu 0..1, heapUsedMB}
    private static volatile boolean sampling;
    private static Thread sampler;
    private static final List<String> results = new ArrayList<>();
    /** Largest horizontal distance seen between the client camera and the server-side player during the window: if the
     *  server stops following the camera it stops sending chunks, and the window measures an empty world. */
    private static double maxLag;
    /** Measured frames rendered while the game window was not the foreground window (the driver may lower clocks). */
    private static int unfocusedFrames;
    /** Wall-clock end time and length of every frame over 15 ms in the window (to line up with a JFR recording). */
    private static final long[] spikeAt = new long[20_000];
    private static final double[] spikeMs = new double[20_000];
    private static int spikeN;
    private static Minecraft mcRef;

    // GC pauses: {epochMs, durationMs, isPause}
    private static final List<Object[]> gcEvents = Collections.synchronizedList(new ArrayList<>());
    private static boolean gcHooked;

    // prep state
    private static int prepStep;
    private static double prepX;

    /** Called at the end of every rendered frame (render thread), from the same hook as DevShot. */
    public static void onRenderEnd(Minecraft mc) {
        if (!resolved) {
            resolved = true;
            try {
                String m = System.getenv("S1MP1E_BENCH");
                if (m != null && !m.isBlank()) {
                    mode = m.trim().toLowerCase(Locale.ROOT);
                    String o = System.getenv("S1MP1E_BENCH_OUT");
                    out = new File(o == null || o.isBlank() ? "bench-out" : o.trim());
                    out.mkdirs();
                    String res = System.getenv("S1MP1E_BENCH_RES");
                    if (res != null && res.matches("\\d+x\\d+")) {
                        WIN_W = Integer.parseInt(res.split("x")[0]); WIN_H = Integer.parseInt(res.split("x")[1]);
                    }
                    String gui = System.getenv("S1MP1E_BENCH_GUI");
                    if (gui != null && gui.matches("\\d+")) GUI = Integer.parseInt(gui);
                    String list = System.getenv("S1MP1E_BENCH_SCENARIOS");
                    for (Scenario s : ALL) {
                        if (list == null || list.isBlank() || Arrays.asList(list.split(",")).contains(s.name)) todo.add(s);
                    }
                    log("active: mode=" + mode + " out=" + out.getAbsolutePath() + " scenarios=" + names(todo));
                    hookGc();
                }
            } catch (Throwable t) {
                mode = null;
            }
        }
        if (mode == null || mc == null) return;
        mcRef = mc;
        long now = System.nanoTime();
        long dt = lastFrameNs == 0 ? 0 : now - lastFrameNs;
        lastFrameNs = now;
        try {
            step(mc, now, dt);
        } catch (Throwable t) {
            log("step failed in phase " + phase + ": " + t);
            t.printStackTrace();
            phase = P_DRAIN;
        }
    }

    private static void step(Minecraft mc, long now, long dt) throws Exception {
        switch (phase) {
            case P_TITLE -> {
                pinWindow(mc);
                if (!(mc.gui.screen() instanceof TitleScreen)) return;
                if (++frames < 30) return;   // let the title settle
                pinSettings(mc);
                File saves = new File(mc.gameDirectory, "saves");
                if ("prep".equals(mode)) {
                    deleteTree(new File(saves, MASTER).toPath());
                    createWorld(mc, MASTER);
                } else if ("profile".equals(mode)) {
                    mc.createWorldOpenFlows().openWorld(MASTER, () -> log("open world cancelled"));
                } else {
                    File master = new File(saves, MASTER);
                    if (!master.isDirectory()) throw new IllegalStateException("no " + MASTER + " — run S1MP1E_BENCH=prep first");
                    Path copy = new File(saves, COPY).toPath();
                    deleteTree(copy);
                    copyTree(master.toPath(), copy);
                    deleteTree(copy.resolve("session.lock"));
                    mc.createWorldOpenFlows().openWorld(COPY, () -> log("open world cancelled"));
                }
                phase = P_WAIT_WORLD; frames = 0;
            }
            case P_WAIT_WORLD -> {
                pinWindow(mc);
                if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && mc.gui.screen() == null) {
                    phase = P_SETUP; frames = 0;
                } else if (++frames > 20_000) {
                    throw new IllegalStateException("world never became ready");
                }
            }
            case P_SETUP -> {
                pinWindow(mc);
                if (++frames < 20) return;
                worldSetup(mc);
                if ("prep".equals(mode)) { phase = P_PREP; prepStep = 0; frames = 0; phaseStartNs = now; }
                else if ("particles".equals(mode)) {
                    phase = P_PART; partStep = -1; phaseStartNs = now;
                    serverTp(0, routeY("explore", 0), 0, -90, 15);   // the server sends chunks and particles where it is
                }
                else if ("routes".equals(mode)) { phase = P_ROUTES; scenIdx = 0; frames = 0; beginScenario(now); }
                else if ("profile".equals(mode)) { phase = P_PROFILE; scenIdx = 0; }
                else if ("donecheck".equals(mode)) { phase = P_DONECHECK; frames = 0; dcStep = 0; }
                else { phase = P_SCEN; scenIdx = 0; beginScenario(now); }
            }
            case P_SCEN -> runScenario(mc, now, dt);
            case P_PREP -> runPrep(mc, now);
            case P_PART -> runParticles(mc, now, dt);
            case P_ROUTES -> runRoutes(mc, now);
            case P_PROFILE -> runProfile(mc, now);
            case P_DONECHECK -> runDoneCheck(mc);
            case P_DRAIN -> {
                if (++frames < 60) return;   // let pending screenshot writes flush
                log("done, quitting.");
                quit(mc);
                phase = P_DONE;
            }
            default -> { }
        }
    }

    // ---- scenarios ------------------------------------------------------------------------------------------------

    private static void beginScenario(long now) {
        Scenario s = todo.get(scenIdx);
        sampling = false;
        unfocusedFrames = 0; spikeN = 0;
        scenStartNs = now; measuring = false; ftN = 0; midShot = false; cpuSamples.clear(); lastSampleNs = 0; maxLag = 0;
        startSampler();
        serverTp(s.x0, s.y0 >= 0 ? s.y0 : routeY(s.name, s.x0), s.z0, (float) s.yaw0, (float) s.pitch);
        log("scenario " + s.name + ": warm " + s.warmS + " s, measure " + s.measureS + " s");
    }

    private static void runScenario(Minecraft mc, long now, long dt) {
        pinWindow(mc);
        Scenario s = todo.get(scenIdx);
        double t = (now - scenStartNs) / 1e9;
        if (!measuring) {
            place(mc, s, 0);
            if (t >= s.warmS) {
                measuring = true; measureStartNs = now; measureStartMs = System.currentTimeMillis();
                log("scenario " + s.name + ": measuring");
            }
            return;
        }
        double m = (now - measureStartNs) / 1e9;
        if (dt > 0 && ftN < ft.length) ft[ftN++] = dt;
        if (dt > 15_000_000L && spikeN < spikeAt.length) { spikeAt[spikeN] = System.currentTimeMillis(); spikeMs[spikeN++] = dt / 1e6; }
        if (!mc.isWindowActive()) unfocusedFrames++;
        sampling = true;   // CPU / heap / server-lag samples come from the sampler thread, never the render thread
        if (m >= s.measureS) {
            sampling = false;
            shot(mc, s.name + "_end.png");          // after the window: writing a 4K PNG stalls the render thread
            long endMs = System.currentTimeMillis();
            results.add(summarize(s, measureStartMs, endMs));
            writeFrames(s);
            writeSpikes(s);
            scenIdx++;
            if (scenIdx >= todo.size()) { writeSummary(mc); phase = P_DRAIN; frames = 0; }
            else beginScenario(now);
            return;
        }
        place(mc, s, m);
    }

    /** Camera pose at {@code m} seconds into the measured part of {@code s}. */
    private static void place(Minecraft mc, Scenario s, double m) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        double x = s.x0 + s.vx * m, z = s.z0 + s.vz * m;
        double y = s.y0 >= 0 ? s.y0 : routeY(s.name, x);
        float yaw = (float) (s.yaw0 + s.yawAmp * Math.sin(2 * Math.PI * m / s.yawPeriodS));
        float pitch = (float) s.pitch;
        p.setPos(x, y, z);
        p.xo = x; p.yo = y; p.zo = z;
        p.xOld = x; p.yOld = y; p.zOld = z;
        p.setYRot(yaw); p.setXRot(pitch);
        p.yRotO = yaw; p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.setDeltaMovement(0, 0, 0);
        p.getAbilities().flying = true;
    }

    // ---- terrain profile -------------------------------------------------------------------------------------------

    /** Ground height (MOTION_BLOCKING) per block along each route, recorded once by {@code S1MP1E_BENCH=profile} and
     *  stored with the master world ({@code s1bench_profile_<route>.csv}), so every run flies the same height curve. */
    private static final java.util.Map<String, int[]> profiles = new java.util.HashMap<>();
    private static final int PROFILE_LEN = 3000;   // blocks along +X from the route start
    private static final int CLEARANCE = 25, LOOKAHEAD = 40;

    /** Camera height over {@code x}: the highest ground within +-LOOKAHEAD blocks plus CLEARANCE (never inside terrain,
     *  never above the clouds for long). Falls back to y 200 without a profile. */
    private static double routeY(String route, double x) {
        int[] h = profiles.get(route);
        if (h == null) h = loadProfile(route);
        Scenario r = route.equals("explore") ? EXPLORE : CHUNKLOAD;
        if (h == null) return 200;
        int i = (int) Math.round(x - r.x0);
        int top = Integer.MIN_VALUE;
        for (int k = Math.max(0, i - LOOKAHEAD); k <= Math.min(h.length - 1, i + LOOKAHEAD); k++) top = Math.max(top, h[k]);
        if (top == Integer.MIN_VALUE) return 200;
        return Math.max(80, top + CLEARANCE);
    }

    private static int[] loadProfile(String route) {
        try {
            Minecraft mc = mcRef;
            File f = new File(new File(mc.gameDirectory, "saves/" + COPY), "s1bench_profile_" + route + ".csv");
            if (!f.exists()) f = new File(new File(mc.gameDirectory, "saves/" + MASTER), "s1bench_profile_" + route + ".csv");
            if (!f.exists()) return null;
            int[] h = new int[PROFILE_LEN];
            Arrays.fill(h, Integer.MIN_VALUE);
            for (String line : Files.readAllLines(f.toPath())) {
                String[] a = line.split(",");
                if (a.length != 2 || !a[0].matches("-?\\d+")) continue;
                int i = Integer.parseInt(a[0]);
                if (i >= 0 && i < h.length) h[i] = Integer.parseInt(a[1].trim());
            }
            profiles.put(route, h);
            return h;
        } catch (Throwable t) {
            log("profile " + route + ": " + t);
            return null;
        }
    }

    private static int[] recording;
    private static String recordingRoute;

    /** Fly each route at y 300 at 12 b/s and record the ground height under every block column of the path. */
    private static void runProfile(Minecraft mc, long now) {
        pinWindow(mc);
        Scenario s = todo.get(scenIdx);
        if (recording == null || !s.name.equals(recordingRoute)) {
            recording = new int[PROFILE_LEN];
            Arrays.fill(recording, Integer.MIN_VALUE);
            recordingRoute = s.name;
            serverTp(s.x0, 300, s.z0, -90, 60);
            scenStartNs = now;
        }
        double t = (now - scenStartNs) / 1e9;
        double travelled = Math.max(0, (t - 5) * 12);
        double x = s.x0 + travelled;
        place(mc, new Scenario("p", x, 300, s.z0, 0, 0, -90, 0, 1, 60, 0, 0), 0);
        // the columns up to 24 blocks behind the camera are loaded on the client by now: read them
        for (int i = Math.max(0, (int) travelled - 40); i <= (int) travelled - 24 && i < PROFILE_LEN; i++) {
            if (recording[i] != Integer.MIN_VALUE) continue;
            int bx = (int) Math.floor(s.x0 + i), bz = (int) Math.floor(s.z0);
            int top = Integer.MIN_VALUE;
            for (int dz = -6; dz <= 6; dz++) {   // a strip as wide as the view straight down the path
                top = Math.max(top, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz + dz));
            }
            recording[i] = top;
        }
        double need = s.vx * s.measureS + LOOKAHEAD + 60;
        if (travelled >= need + 40) {
            try {
                File f = new File(new File(mc.gameDirectory, "saves/" + MASTER), "s1bench_profile_" + s.name + ".csv");
                StringBuilder b = new StringBuilder("i,height\n");
                int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
                for (int i = 0; i < PROFILE_LEN; i++) {
                    if (recording[i] == Integer.MIN_VALUE) continue;
                    b.append(i).append(',').append(recording[i]).append('\n');
                    max = Math.max(max, recording[i]); min = Math.min(min, recording[i]);
                }
                Files.writeString(f.toPath(), b.toString());
                log("profile " + s.name + ": ground " + min + ".." + max + " -> " + f);
            } catch (IOException e) {
                log("profile write failed: " + e);
            }
            recording = null;
            scenIdx++;
            if (scenIdx >= todo.size()) { phase = P_DRAIN; frames = 0; }
        }
    }

    // ---- settings shell: footer click check -------------------------------------------------------------------------

    /**
     * Video settings page: scroll until a row straddles the bottom of the body over the Done capsule, then click the
     * capsule's top pixel row. Done must win (the page closes) and the row's value must not change.
     */
    private static void runDoneCheck(Minecraft mc) {
        frames++;
        var scr = mc.gui.screen();
        switch (dcStep) {
            case 0 -> {
                mc.gui.setScreen(new net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen(null, mc.options));
                dcStep = 1; frames = 0;
            }
            case 1 -> {
                if (frames < 600) return;   // ~0.5-1 s: the eased scroll has settled
                net.minecraft.client.gui.components.AbstractWidget done = doneButton(scr);
                if (done == null) { log("donecheck: no Done button on " + (scr == null ? "null" : scr.getClass().getName())); dcStep = 9; return; }
                double x = done.getX() + done.getWidth() / 2.0, y = done.getY() + 0.5;
                net.minecraft.client.gui.components.AbstractWidget over = null;
                for (var c : scr.children()) {
                    if (c instanceof net.minecraft.client.gui.components.AbstractWidget w && w != done && w.visible && w.isMouseOver(x, y)) { over = w; break; }
                }
                if (over == null && dcScrolls < 60) {   // scroll a little and look again
                    dev.s1mp1e.client.gui.SettingsShell.wheel(scr, scr.width * 0.6, scr.height * 0.5, -0.25);
                    dcScrolls++; frames = 0;
                    return;
                }
                String before = over == null ? "-" : over.getMessage().getString();
                log("donecheck: Done at " + done.getX() + "," + done.getY() + " " + done.getWidth() + "x" + done.getHeight()
                        + "; widget under its top edge: " + (over == null ? "none (no straddling row found)" : "'" + before + "'")
                        + " after " + dcScrolls + " scroll steps");
                shot(mc, "donecheck_before.png");
                dcOver = over; dcBefore = before;
                var ev = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                scr.mouseClicked(ev, false);
                scr.mouseReleased(ev);
                dcStep = 2; frames = 0;
            }
            case 2 -> {
                if (frames < 200) return;
                boolean closed = !(scr instanceof net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen);
                String after = dcOver == null ? "-" : dcOver.getMessage().getString();
                boolean unchanged = after.equals(dcBefore);
                log("donecheck: " + (closed && unchanged ? "PASS" : "FAIL") + " (page closed=" + closed + ", row under the edge "
                        + (dcOver == null ? "n/a" : "'" + dcBefore + "' -> '" + after + "'") + ")");
                shot(mc, "donecheck_after.png");
                if (!closed) mc.gui.setScreen(null);
                dcStep = 9; frames = 0;
            }
            default -> { if (frames > 30) { phase = P_DRAIN; frames = 0; } }
        }
    }

    private static net.minecraft.client.gui.components.AbstractWidget dcOver;
    private static String dcBefore;

    private static net.minecraft.client.gui.components.AbstractWidget doneButton(net.minecraft.client.gui.screens.Screen scr) {
        if (scr == null) return null;
        String doneText = net.minecraft.network.chat.Component.translatable("gui.done").getString();
        for (var c : scr.children()) {
            if (c instanceof net.minecraft.client.gui.components.AbstractWidget w && doneText.equals(w.getMessage().getString())) return w;
        }
        return null;
    }

    // ---- route check ---------------------------------------------------------------------------------------------

    /** Fly every route like the measurement does and take a screenshot every 5 s of its measured part (no numbers). */
    private static void runRoutes(Minecraft mc, long now) {
        pinWindow(mc);
        Scenario s = todo.get(scenIdx);
        double t = (now - scenStartNs) / 1e9;
        double m = Math.max(0, t - s.warmS);
        place(mc, s, m);
        int k = (int) Math.floor(m / 5.0);
        if (t >= s.warmS && k > frames - 1 && m < s.measureS) {
            frames = k + 1;
            shot(mc, String.format(Locale.ROOT, "route_%s_%02d.png", s.name, k));
        }
        if (m >= s.measureS) {
            scenIdx++; frames = 0;
            if (scenIdx >= todo.size()) { phase = P_DRAIN; frames = 0; }
            else beginScenario(now);
            return;
        }
    }

    // ---- particles check -------------------------------------------------------------------------------------------

    /**
     * Particles module check: for each setting (module off / Reduced 33 % / None) emit the same particle stream for
     * 6 s on the explore start (flames + crit bursts in front of the camera, every tick), then record the live
     * particle count, a screenshot and the frame times. Not a performance scenario for the pack: it shows what the
     * module does.
     */
    private static void runParticles(Minecraft mc, long now, long dt) {
        pinWindow(mc);
        if (partStep < 0) {   // let the chunks around the spot arrive first
            place(mc, new Scenario("p", 0, routeY("explore", 0), 0, 0, 0, -90, 0, 1, 15, 0, 0), 0);
            if ((now - phaseStartNs) / 1e9 >= 8) { partStep = 0; phaseStartNs = now; }
            return;
        }
        int set = partStep / 2;
        if (set >= PART_MODES.length) {   // leave the module off again (its state is saved with the config)
            var m = ModuleManager.byName("Particles");
            if (m != null) m.setEnabled(false);
            writeSummary(mc); phase = P_DRAIN; frames = 0; return;
        }
        double t = (now - phaseStartNs) / 1e9;
        double py = routeY("explore", 0);
        place(mc, new Scenario("p", 0, py, 0, 0, 0, -90, 0, 1, 15, 0, 0), 0);
        if (partStep % 2 == 0) {   // configure + clear
            var m = ModuleManager.byName("Particles");
            if (m instanceof dev.s1mp1e.client.module.ParticlesModule pm) {
                if ("off".equals(PART_MODES[set])) pm.setEnabled(false);
                else { pm.amount.setMode(PART_MODES[set]); pm.keep.setDouble(33); pm.setEnabled(true); }
            } else log("particles module missing");
            try { mc.particleEngine.clearParticles(); } catch (Throwable ignored) { }
            ftN = 0; measuring = false; midShot = false; phaseStartNs = now; partStep++;
            log("particles: setting " + PART_MODES[set]);
            return;
        }
        if (t >= 1.0 && !measuring) { measuring = true; measureStartMs = System.currentTimeMillis(); }
        if (frames++ % 3 == 0) {   // the same command stream every time, ~ every 3rd frame
            cmd(mc, String.format(Locale.ROOT, "particle minecraft:flame 12 %.1f 0 1.5 1 1.5 0.01 40 force", py - 2));
            cmd(mc, String.format(Locale.ROOT, "particle minecraft:crit 12 %.1f 0 1 1 1 0.2 20 force", py - 2));
        }
        if (measuring && dt > 0 && ftN < ft.length) ft[ftN++] = dt;
        if (measuring && !midShot && t >= 4.0) {
            midShot = true;
            String count = "?";
            try { count = mc.particleEngine.countParticles(); } catch (Throwable ignored) { }
            log("particles: setting " + PART_MODES[set] + " live particles=" + count);
            shot(mc, "particles_" + PART_MODES[set] + ".png");
        }
        if (t >= 6.0) {
            Scenario s = new Scenario("particles_" + PART_MODES[set], 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
            results.add(summarize(s, measureStartMs, System.currentTimeMillis()));
            partStep++; phaseStartNs = now; frames = 0;
        }
    }

    // ---- prep -----------------------------------------------------------------------------------------------------

    /** Fly every route once at a moderate speed so its chunks are generated and saved, then build the entity pen. */
    private static void runPrep(Minecraft mc, long now) {
        pinWindow(mc);
        double t = (now - phaseStartNs) / 1e9;
        switch (prepStep) {
            case 0 -> { prepX = EXPLORE.x0; if (frames++ == 0) serverTp(EXPLORE.x0, EXPLORE.y0, EXPLORE.z0, -90, 15); placeAt(mc, EXPLORE.x0, EXPLORE.y0, EXPLORE.z0, -90, 15); if (t < 3) return; prepStep = 1; phaseStartNs = now; log("prep: explore route"); }
            case 1 -> {   // explore route: x0 .. x0 + v*measure (+ render distance margin) at 8 b/s
                double end = EXPLORE.x0 + EXPLORE.vx * EXPLORE.measureS + 200;
                prepX = EXPLORE.x0 + 8 * t;
                placeAt(mc, prepX, EXPLORE.y0, EXPLORE.z0, -90, 15);
                if (prepX >= end) { prepStep = 2; phaseStartNs = now; serverTp(CHUNKLOAD.x0, CHUNKLOAD.y0, CHUNKLOAD.z0, -90, 30); log("prep: chunkload route"); }
            }
            case 2 -> {
                double end = CHUNKLOAD.x0 + CHUNKLOAD.vx * CHUNKLOAD.measureS + 200;
                prepX = CHUNKLOAD.x0 + 8 * Math.max(0, t - 3);
                placeAt(mc, prepX, CHUNKLOAD.y0, CHUNKLOAD.z0, -90, 30);
                if (now - lastSampleNs >= 1_000_000_000L) { lastSampleNs = now; sampleLag(mc); }
                if (prepX >= end) { prepStep = 3; phaseStartNs = now; serverTp(ENTITIES.x0, ENTITIES.y0, ENTITIES.z0, -90, 25); log("prep: entity pen (chunkload route max server lag " + Math.round(maxLag) + " blocks)"); }
            }
            case 3 -> {   // go to the pen, wait for the chunks, then build
                placeAt(mc, ENTITIES.x0, ENTITIES.y0, ENTITIES.z0, -90, 25);
                if (t < 8) return;
                buildPen(mc);
                prepStep = 4; phaseStartNs = now;
            }
            case 4 -> {
                placeAt(mc, ENTITIES.x0, ENTITIES.y0, ENTITIES.z0, -90, 25);
                if (t < 5) return;
                shot(mc, "prep_pen.png");
                cmd(mc, "save-all flush");
                prepStep = 5; phaseStartNs = now;
            }
            case 5 -> { if (t >= 3) { log("prep: done"); phase = P_DRAIN; frames = 0; } }
            default -> { }
        }
    }

    private static void placeAt(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        Scenario s = new Scenario("prep", x, y, z, 0, 0, yaw, 0, 1, pitch, 0, 0);
        place(mc, s, 0);
    }

    private static void buildPen(Minecraft mc) {
        int x0 = PEN_X - PEN_R, x1 = PEN_X + PEN_R, z0 = PEN_Z - PEN_R, z1 = PEN_Z + PEN_R;
        cmd(mc, "forceload add " + x0 + " " + z0 + " " + x1 + " " + z1);
        cmd(mc, "fill " + x0 + " " + PEN_Y + " " + z0 + " " + x1 + " " + PEN_Y + " " + z1 + " minecraft:smooth_stone");
        // waist-high fence ring so nobody slides off (NoAI mobs do not move anyway)
        cmd(mc, "fill " + x0 + " " + (PEN_Y + 1) + " " + z0 + " " + x1 + " " + (PEN_Y + 1) + " " + z0 + " minecraft:oak_fence");
        cmd(mc, "fill " + x0 + " " + (PEN_Y + 1) + " " + z1 + " " + x1 + " " + (PEN_Y + 1) + " " + z1 + " minecraft:oak_fence");
        cmd(mc, "fill " + x0 + " " + (PEN_Y + 1) + " " + z0 + " " + x0 + " " + (PEN_Y + 1) + " " + z1 + " minecraft:oak_fence");
        cmd(mc, "fill " + x1 + " " + (PEN_Y + 1) + " " + z0 + " " + x1 + " " + (PEN_Y + 1) + " " + z1 + " minecraft:oak_fence");
        String[] kinds = {"minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:villager", "minecraft:chicken"};
        int n = 0;
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) {
                double x = x0 + 1.5 + i * 1.9, z = z0 + 1.5 + j * 1.9;
                String kind = kinds[(i * 7 + j * 3) % kinds.length];
                cmd(mc, String.format(Locale.ROOT, "summon %s %.2f %d %.2f {NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[%df,0f]}",
                        kind, x, PEN_Y + 1, z, (i * 37 + j * 53) % 360));
                n++;
            }
        }
        log("prep: summoned " + n + " mobs");
    }

    // ---- setup ----------------------------------------------------------------------------------------------------

    private static void createWorld(Minecraft mc, String name) {
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        WorldOptions opts = new WorldOptions(SEED, true, false);
        Function<HolderLookup.Provider, WorldDimensions> dims = provider ->
                provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
        WorldOpenFlows flows = mc.createWorldOpenFlows();
        flows.createFreshLevel(name, settings, opts, dims, new TitleScreen());
    }

    /** Fixed, documented graphics state — the same for every configuration that is compared. */
    private static void pinSettings(Minecraft mc) {
        var o = mc.options;
        try { o.applyGraphicsPreset(GraphicsPreset.FANCY); } catch (Throwable t) { log("preset: " + t); }
        o.renderDistance().set(RENDER_DISTANCE);
        o.simulationDistance().set(SIM_DISTANCE);
        o.framerateLimit().set(260);          // 260 = unlimited
        o.enableVsync().set(false);
        o.fov().set(70);
        o.particles().set(ParticleStatus.ALL);
        o.entityDistanceScaling().set(1.0);
        o.guiScale().set(GUI);
        o.pauseOnLostFocus = false;
        log("settings: preset=FANCY rd=" + RENDER_DISTANCE + " sim=" + SIM_DISTANCE + " fps=unlimited vsync=off fov=70"
                + " particles=all entityDistance=100% gui=" + GUI + " window=" + WIN_W + "x" + WIN_H);
    }

    private static void pinWindow(Minecraft mc) {
        try {
            Window w = mc.getWindow();
            if (w.isFullscreen()) w.toggleFullScreen();
            if (w.getWidth() != WIN_W || w.getHeight() != WIN_H) { w.setWindowed(WIN_W, WIN_H); mc.framebufferSizeChanged(); }
        } catch (Throwable ignored) { }
    }

    private static void worldSetup(Minecraft mc) {
        for (String c : new String[]{"gamerule advance_time false", "gamerule advance_weather false",
                "gamerule spawn_mobs false", "time set 6000", "weather clear 1000000", "difficulty peaceful",
                "gamemode survival @a"}) cmd(mc, c);
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.getAbilities().invulnerable = true;
                sp.onUpdateAbilities();
            }
        });
        try { mc.gui.toastManager().clear(); } catch (Throwable ignored) { }
    }

    // ---- measurement ----------------------------------------------------------------------------------------------

    private static void hookGc() {
        if (gcHooked) return;
        gcHooked = true;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof NotificationEmitter em)) continue;
            final String bean = gc.getName();
            final boolean pause = !(bean.contains("Cycles") || bean.contains("Concurrent"));
            em.addNotificationListener((n, h) -> {
                try {
                    if (!"com.sun.management.gc.notification".equals(n.getType())) return;
                    CompositeData cd = (CompositeData) n.getUserData();
                    CompositeData info = (CompositeData) cd.get("gcInfo");
                    long dur = ((Number) info.get("duration")).longValue();
                    gcEvents.add(new Object[]{System.currentTimeMillis(), (double) dur, pause, bean});
                } catch (Throwable ignored) { }
            }, null, null);
        }
    }

    /** Server-side teleport (no movement check), so the server — and the chunks it sends — are where the camera is. */
    private static void serverTp(double x, double y, double z, float yaw, float pitch) {
        Minecraft mc = mcRef;
        if (mc == null) return;
        cmd(mc, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
    }

    private static void sampleLag(Minecraft mc) {
        try {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) return;
            var list = server.getPlayerList().getPlayers();
            if (list.isEmpty()) return;
            var sp = list.get(0).position();
            double dx = sp.x - mc.player.getX(), dz = sp.z - mc.player.getZ();
            maxLag = Math.max(maxLag, Math.sqrt(dx * dx + dz * dz));
        } catch (Throwable ignored) { }
    }

    /** Background sampler (1 Hz while a window is being measured); never touches GL or the render thread's state. */
    private static void startSampler() {
        if (sampler != null) return;
        sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (!sampling) continue;
                    sampleCpu();
                    Minecraft mc = mcRef;
                    if (mc != null) sampleLag(mc);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) { }
            }
        }, "S1mp1e-Bench-Sampler");
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();
    }

    private static void sampleCpu() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double cpu = os.getProcessCpuLoad();
            double heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
            cpuSamples.add(new double[]{cpu, heap});
        } catch (Throwable ignored) { }
    }

    private static String summarize(Scenario s, long startMs, long endMs) {
        long[] a = Arrays.copyOf(ft, ftN);
        long total = 0;
        for (long v : a) total += v;
        Arrays.sort(a);
        int n = a.length;
        double secs = total / 1e9;
        double avgFps = n / secs;
        int worst = Math.max(1, n / 100);
        long worstSum = 0;
        for (int i = n - worst; i < n; i++) worstSum += a[i];
        double low1 = worst / (worstSum / 1e9);                 // average FPS over the slowest 1% of frames
        int worst01 = Math.max(1, n / 1000);
        long worst01Sum = 0;
        for (int i = n - worst01; i < n; i++) worst01Sum += a[i];
        double low01 = worst01 / (worst01Sum / 1e9);
        double p50 = a[(int) Math.min(n - 1, Math.round(n * 0.50))] / 1e6;
        double p95 = a[(int) Math.min(n - 1, Math.round(n * 0.95))] / 1e6;
        double p99 = a[(int) Math.min(n - 1, Math.round(n * 0.99))] / 1e6;
        double max = a[n - 1] / 1e6;
        int over20 = 0, over50 = 0;
        for (long v : a) { if (v > 20_000_000L) over20++; if (v > 50_000_000L) over50++; }
        int gcN = 0; double gcTotal = 0, gcMax = 0; int gcConc = 0;
        synchronized (gcEvents) {
            for (Object[] e : gcEvents) {
                long tms = (Long) e[0];
                if (tms < startMs || tms > endMs) continue;
                if ((Boolean) e[2]) { gcN++; double d = (Double) e[1]; gcTotal += d; gcMax = Math.max(gcMax, d); }
                else gcConc++;
            }
        }
        double cpuAvg = 0, heapAvg = 0, heapMax = 0;
        List<double[]> samples;
        synchronized (cpuSamples) { samples = new ArrayList<>(cpuSamples); }
        for (double[] c : samples) { cpuAvg += c[0]; heapAvg += c[1]; heapMax = Math.max(heapMax, c[1]); }
        if (!samples.isEmpty()) { cpuAvg /= samples.size(); heapAvg /= samples.size(); }
        String r = String.format(Locale.ROOT,
                "{\"scenario\":\"%s\",\"startMs\":%d,\"endMs\":%d,\"frames\":%d,\"seconds\":%.3f,\"avgFps\":%.1f,"
                        + "\"low1Fps\":%.1f,\"low01Fps\":%.1f,\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"p99Ms\":%.3f,\"maxMs\":%.2f,"
                        + "\"framesOver20ms\":%d,\"framesOver50ms\":%d,\"gcPauses\":%d,\"gcPauseTotalMs\":%.1f,"
                        + "\"gcPauseMaxMs\":%.1f,\"gcConcurrentCycles\":%d,\"cpuProcessAvg\":%.3f,\"cores\":%d,"
                        + "\"heapAvgMB\":%.0f,\"heapMaxMB\":%.0f,\"serverLagMaxBlocks\":%.1f,\"unfocusedFrames\":%d,\"valid\":%s}",
                s.name, startMs, endMs, n, secs, avgFps, low1, low01, p50, p95, p99, max, over20, over50, gcN, gcTotal,
                gcMax, gcConc, cpuAvg, Runtime.getRuntime().availableProcessors(), heapAvg, heapMax, maxLag, unfocusedFrames,
                maxLag < 48 && unfocusedFrames == 0 ? "true" : "false");
        log("result " + r);
        return r;
    }

    private static void writeSpikes(Scenario s) {
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, s.name + "_spikes.csv")))) {
            w.println("epochMs,ms");
            for (int i = 0; i < spikeN; i++) w.println(spikeAt[i] + "," + String.format(Locale.ROOT, "%.2f", spikeMs[i]));
        } catch (IOException e) {
            log("write spikes failed: " + e);
        }
    }

    private static void writeFrames(Scenario s) {
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, s.name + "_frames.csv")))) {
            w.println("frame,ms");
            for (int i = 0; i < ftN; i++) w.println(i + "," + String.format(Locale.ROOT, "%.4f", ft[i] / 1e6));
        } catch (IOException e) {
            log("write frames failed: " + e);
        }
    }

    private static void writeSummary(Minecraft mc) {
        StringBuilder b = new StringBuilder();
        b.append("{\n\"java\":\"").append(esc(System.getProperty("java.vendor") + " " + System.getProperty("java.version"))).append("\",\n");
        b.append("\"jvmArgs\":[");
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < args.size(); i++) b.append(i == 0 ? "" : ",").append('"').append(esc(args.get(i))).append('"');
        b.append("],\n\"gc\":[");
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        for (int i = 0; i < gcs.size(); i++) b.append(i == 0 ? "" : ",").append('"').append(esc(gcs.get(i).getName())).append('"');
        b.append("],\n\"mods\":[");
        try {
            var all = new ArrayList<String>();
            for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                var meta = mod.getMetadata();
                if (meta.getId().startsWith("fabric-") && !meta.getId().equals("fabric-api")) continue;
                all.add(meta.getId() + "@" + meta.getVersion().getFriendlyString());
            }
            Collections.sort(all);
            for (int i = 0; i < all.size(); i++) b.append(i == 0 ? "" : ",").append('"').append(esc(all.get(i))).append('"');
        } catch (Throwable t) { b.append("\"?\""); }
        b.append("],\n\"gpu\":\"").append(esc(gpuName())).append("\",\n");
        b.append("\"window\":\"").append(mc.getWindow().getWidth()).append('x').append(mc.getWindow().getHeight()).append("\",\n");
        var rt = mc.gameRenderer.mainRenderTarget();
        b.append("\"renderTarget\":\"").append(rt.width).append('x').append(rt.height).append("\",\n");
        b.append("\"guiScale\":").append(mc.getWindow().getGuiScale()).append(",\n");
        b.append("\"results\":[\n");
        for (int i = 0; i < results.size(); i++) b.append(i == 0 ? "" : ",\n").append(results.get(i));
        b.append("\n]\n}\n");
        try {
            Files.writeString(new File(out, "summary.json").toPath(), b.toString());
            log("wrote summary.json");
        } catch (IOException e) {
            log("write summary failed: " + e);
        }
    }

    private static String gpuName() {
        try { var d = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo(); return d.name() + " / " + d.backendName() + " / " + d.driverInfo(); }
        catch (Throwable t) { return "?"; }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static void shot(Minecraft mc, String name) {
        try {
            RenderTarget target = mc.gameRenderer.mainRenderTarget();
            final File f = new File(out, name);
            Screenshot.takeScreenshot(target, image -> {
                try (NativeImage img = image) { img.writeToFile(f); } catch (Throwable t) { log("shot write: " + t); }
            });
        } catch (Throwable t) {
            log("shot " + name + ": " + t);
        }
    }

    private static void cmd(Minecraft mc, String c) {
        try {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
        } catch (Throwable t) {
            log("cmd failed: " + c + ": " + t);
        }
    }

    /** Clean leave: the run loop exits and MC's normal shutdown stops the integrated server and saves the world. */
    private static void quit(Minecraft mc) {
        try {
            java.lang.reflect.Field running = Minecraft.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(mc, false);
        } catch (Throwable t) {
            log("quit: " + t);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(d);
                else Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            Collections.reverse(all);
            for (Path x : all) Files.deleteIfExists(x);
        }
    }

    private static String names(List<Scenario> l) {
        StringBuilder b = new StringBuilder();
        for (Scenario s : l) b.append(b.length() == 0 ? "" : ",").append(s.name);
        return b.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void log(String s) {
        System.out.println("[S1mp1e][Bench] " + s);
    }
}
