package dev.s1mp1e.client.film;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

/**
 * DEV film director: shoots scripted, perfectly paced 4K footage of the mod for the promo film. Completely inert
 * unless the env var {@code S1MP1E_FILM} names a shot script (JSON); {@code S1MP1E_FILM_OUT} overrides its output dir.
 *
 * <p>It works like a motion-control rig: {@link FilmClock} freezes film time and advances it by exactly one frame per
 * rendered frame, the camera and the GUI cursor follow eased Catmull-Rom paths keyed in frames, and timed actions
 * (commands, screens, per-character typing, key presses, clicks, drags, scrolls) fire on exact frames. Every frame is
 * read back from the main render target and streamed to ffmpeg ({@link FilmEncoder}); a shot's {@code shutter} renders
 * that many sub-frames over the first half of each frame interval and averages them (180-degree motion blur).
 *
 * <p>Script shape: {@code {"out","width","height","fps","gui","seed","shots":[{"name","frames","preroll","settle",
 * "shutter","gui","setup":[...],"events":{"<frame>":[...]},"camera":[{"f","pos":[x,y,z],"yaw","pitch"}],
 * "cursor":[{"f","x","y"}],"relative":true}]}}}. Camera positions are eye positions; with "relative" they are offsets
 * from the world spawn eye position.
 */
public final class Film {
    private Film() {
    }

    private static final int BOOT = 0, WORLD = 1, SHOTS = 2, DONE = 3;
    private static final int SETUP = 0, SETTLE = 1, PREROLL = 2, CAPTURE = 3, DRAIN = 4, RELOAD = 5;

    private static boolean resolved, active;
    /** GUI scale forced past vanilla's window-size cap (macro close-ups of HUD elements); 0 = none. See WindowScaleMixin. */
    private static int guiOverride;
    /** Module settings a shot changed ("set"), with their values before the change; restored when the shot ends. */
    private static final Map<Setting, Object[]> touched = new HashMap<>();

    /** True while a film script is running (dev only). */
    public static boolean isActive() {
        return active;
    }

    /** GUI camera for the current frame (FilmGuiCamMixin): shift in GUI px and scale about the screen centre. */
    private static double gcamX, gcamY, gcamS = 1;

    private static boolean zoomHeld;
    private static final java.util.Set<KeyMapping> held = new java.util.HashSet<>();

    /** Film "zoom on|off": holds the Zoom module's key. */
    public static boolean zoomHeld() {
        return active && zoomHeld;
    }

    public static boolean gcamActive() {
        return active && (gcamX != 0 || gcamY != 0 || gcamS != 1);
    }

    public static double gcamX() {
        return gcamX;
    }

    public static double gcamY() {
        return gcamY;
    }

    public static double gcamS() {
        return gcamS;
    }

    /** The forced GUI scale, or 0. */
    public static int guiOverride() {
        return active ? guiOverride : 0;
    }
    /** Re-entrancy guard: {@code reloadResourcePacks()} can pump the render loop synchronously, re-firing the
     *  frame-TAIL hook. Nested frames pumped inside a step must do nothing (mirrors DevShot#busy). */
    private static boolean busy;
    /** Per shot ({@code "hand": false}): hide the first-person hand / held item (FilmHandMixin). */
    private static boolean hideHand;

    /** True while a film shot asked for the first-person hand to be hidden. */
    public static boolean hideHand() {
        return active && hideHand;
    }
    /** The resource reload triggered when a shot changes GUI scale, so the PingFang TTF atlas re-bakes at the new
     *  oversample (= the new GUI scale — see FontOversampleMixin). Null when no reload is pending. */
    private static java.util.concurrent.CompletableFuture<Void> reloadFuture;
    private static File outDir;
    private static int width = 3840, height = 2160, fps = 60, gui = 4;
    private static long seed = 0L;
    private static final List<JsonObject> shots = new ArrayList<>();
    private static int phase = BOOT, shotIdx = -1, stage, frameIdx, sub, bootFrames, lastEventFrame;
    private static long settleStartMs;
    private static JsonObject script, shot;
    /** Camera frame used while settling: the settle walks the whole camera path so its chunks are loaded first. */
    private static double settleF;
    private static final double[] PRELOAD = {0.0, 0.25, 0.5, 0.75, 1.0, 0.0};
    private static final long PRELOAD_STEP_MS = 1500;
    private static FilmEncoder encoder;
    private static int pending;
    private static Vec3 spawnEye;
    private static final Map<Integer, List<Runnable>> scheduled = new HashMap<>();
    private static double cursorX = -1, cursorY = -1;

    // ------------------------------------------------------------------------------------------------ frame hooks

    /** Minecraft.renderFrame HEAD: place the camera and the cursor for the frame about to be rendered. */
    public static void onFrameStart(Minecraft mc) {
        if (!active || phase != SHOTS || shot == null) return;
        double ft;
        if (stage == SETUP || stage == RELOAD) ft = 0;
        else if (stage == SETTLE) ft = settleF;
        else if (stage == PREROLL) ft = Math.max(0, frameIdx);
        else if (stage == CAPTURE) ft = frameIdx + (sub / (double) shutter()) * 0.5;
        else ft = frames();
        if ((stage == PREROLL || stage == CAPTURE) && sub == 0 && lastEventFrame != frameIdx) {
            lastEventFrame = frameIdx;
            fire(mc, frameIdx);
        }
        applyCamera(mc, ft);
        applyCursor(mc, ft);
        applyGcam(ft);
    }

    /** Minecraft.renderFrame TAIL: capture the finished frame, then move film time on. */
    public static void onFrameEnd(Minecraft mc) {
        if (!resolved) resolve();
        if (!active) return;
        if (busy) return;            // a frame pumped inside a heavy step (reloadResourcePacks) — do nothing
        busy = true;
        FilmClock.install();
        try {
            switch (phase) {
                case BOOT -> boot(mc);
                case WORLD -> world(mc);
                case SHOTS -> shots(mc);
                case DONE -> exit(mc);
                default -> {
                }
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e][Film] error in phase " + phase + " stage " + stage + ": " + t);
            t.printStackTrace();
            phase = DONE;
        } finally {
            busy = false;
        }
    }

    // ------------------------------------------------------------------------------------------------ setup

    private static void resolve() {
        resolved = true;
        String path = System.getenv("S1MP1E_FILM");
        if (path == null || path.isBlank()) return;
        try (Reader r = new FileReader(path.trim())) {
            JsonObject s = JsonParser.parseReader(r).getAsJsonObject();
            script = s;
            String out = System.getenv("S1MP1E_FILM_OUT");
            outDir = new File(out != null && !out.isBlank() ? out.trim() : str(s, "out", "C:/Temp/s1film"));
            outDir.mkdirs();
            width = num(s, "width", 3840);
            height = num(s, "height", 2160);
            fps = num(s, "fps", 60);
            gui = num(s, "gui", 4);
            seed = s.has("seed") ? s.get("seed").getAsLong() : 0L;
            for (JsonElement e : s.getAsJsonArray("shots")) shots.add(e.getAsJsonObject());
            active = true;
            rememberOptions(Minecraft.getInstance());
            System.out.println("[S1mp1e][Film] active: " + shots.size() + " shots, " + width + "x" + height + "@" + fps
                    + " -> " + outDir.getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("[S1mp1e][Film] could not read script " + path + ": " + t);
        }
    }

    // ------------------------------------------------------------------------------------------------ safe exit

    private static boolean origBob, origPause;
    private static int origGui, origRender, origSim, origFov;
    private static net.minecraft.server.level.ParticleStatus origParticles;
    private static InactivityFpsLimit origInactivity;
    private static int exitStage, exitFrames;
    private static boolean inExit;

    private static void rememberOptions(Minecraft mc) {
        origGui = mc.options.guiScale().get();
        origBob = mc.options.bobView().get();
        origPause = mc.options.pauseOnLostFocus;
        origInactivity = mc.options.inactivityFpsLimit().get();
        origRender = mc.options.renderDistance().get();
        origSim = mc.options.simulationDistance().get();
        origFov = mc.options.fov().get();
        origParticles = mc.options.particles().get();
    }

    /**
     * Leave safely, never mid-save: (0) film time back on the wall clock (a frozen clock stalls the integrated server's
     * save and shutdown forever) and full screen off; (1) "save and quit" the world the normal way; (2) wait until
     * the level is gone and the server has stopped, then restore every option the director changed and save them;
     * (3) only then stop the game, from the title screen.
     */
    private static void exit(Minecraft mc) {
        if (inExit) return;   // disconnecting pumps frames re-entrantly
        inExit = true;
        try {
            switch (exitStage) {
                case 0 -> {
                    FilmClock.real();
                    if (pending > 0) return;
                    if (encoder != null) {
                        encoder.close();
                        encoder = null;
                    }
                    if (mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                    mc.options.fullscreen().set(false);
                    System.out.println("[S1mp1e][Film] exit: saving and leaving the world");
                    exitStage = 1;
                }
                case 1 -> {
                    if (mc.level != null) {
                        try {
                            mc.disconnectWithSavingScreen();
                        } catch (Throwable t) {
                            System.out.println("[S1mp1e][Film] exit: disconnect failed: " + t);
                        }
                    }
                    exitStage = 2;
                    exitFrames = 0;
                }
                case 2 -> {
                    if (mc.level != null || mc.getSingleplayerServer() != null) return;   // still saving
                    if (!(mc.gui.screen() instanceof TitleScreen)) mc.gui.setScreen(new TitleScreen());
                    if (++exitFrames < 30) return;
                    mc.options.fullscreen().set(false);             // the dev client never keeps full screen
                    mc.options.guiScale().set(origGui);
                    mc.options.bobView().set(origBob);
                    if (origParticles != null) mc.options.particles().set(origParticles);
                    mc.options.pauseOnLostFocus = origPause;
                    if (origInactivity != null) mc.options.inactivityFpsLimit().set(origInactivity);
                    mc.options.renderDistance().set(origRender);
                    mc.options.simulationDistance().set(origSim);
                    mc.options.fov().set(origFov);
                    mc.options.save();
                    System.out.println("[S1mp1e][Film] exit: world saved, options restored");
                    exitStage = 3;
                }
                default -> mc.stop();
            }
        } finally {
            inExit = false;
        }
    }

    private static void boot(Minecraft mc) {
        forceWindow(mc);
        boolean loaded = mc.isGameLoadFinished() && mc.gui != null && mc.gui.overlay() == null;
        if (!(mc.gui.screen() instanceof TitleScreen) || !loaded) return;
        if (++bootFrames < 30) return;
        mc.options.pauseOnLostFocus = false;
        mc.options.bobView().set(false);
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        createWorld(mc);
        phase = WORLD;
        bootFrames = 0;
    }

    private static void world(Minecraft mc) {
        forceWindow(mc);
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || mc.gui.screen() != null) return;
        if (++bootFrames < 20) return;
        LocalPlayer p = mc.player;
        spawnEye = p.getEyePosition();
        command(mc, "gamemode creative");
        command(mc, "weather clear 100000");
        command(mc, "difficulty peaceful");
        command(mc, "gamerule send_command_feedback false");
        command(mc, "gamerule advance_time false");
        p.getAbilities().flying = true;
        mc.options.renderDistance().set(num(script, "renderDistance", 16));
        mc.options.simulationDistance().set(12);
        // HUD modules that would clutter the film (each shot can switch any back on with "module <name> on")
        JsonArray off = script.has("modulesOff") ? script.getAsJsonArray("modulesOff") : null;
        String[] defOff = {"CPS", "ArmorHUD", "PotionHUD", "FpsHUD", "XpFlow"};
        if (off != null) for (JsonElement e : off) act(mc, "module " + e.getAsString() + " off");
        else for (String n : defOff) act(mc, "module " + n + " off");
        System.out.println("[S1mp1e][Film] world ready, spawn eye " + spawnEye);
        StringBuilder names = new StringBuilder();
        for (Module m : ModuleManager.all()) names.append(m.name).append(m.enabled ? "(on) " : "(off) ");
        System.out.println("[S1mp1e][Film] modules: " + names);
        phase = SHOTS;
        nextShot(mc);
    }

    // ------------------------------------------------------------------------------------------------ shots

    private static void nextShot(Minecraft mc) {
        restoreSettings();
        zoomHeld = false;
        shotIdx++;
        if (shotIdx >= shots.size()) {
            shot = null;
            phase = DONE;
            System.out.println("[S1mp1e][Film] all shots done");
            return;
        }
        shot = shots.get(shotIdx);
        stage = SETUP;
        scheduled.clear();
        lastEventFrame = Integer.MIN_VALUE;
        cursorX = cursorY = -1;
    }

    private static void shots(Minecraft mc) {
        forceWindow(mc);
        switch (stage) {
            case SETUP -> {
                FilmClock.real();
                if (mc.gui.screen() != null) mc.gui.setScreen(null);   // never inherit the previous shot's screen
                for (KeyMapping k : held) k.setDown(false);
                held.clear();
                boolean scaleChanged = shot.has("gui") ? setGui(mc, shot.get("gui").getAsInt()) : setGui(mc, gui);
                if (scaleChanged) {
                    // GUI scale changed -> re-bake the font atlas at the new oversample so PingFang stays crisp
                    // (FontOversampleMixin reads guiScale only at bake time). Without this the atlas stays baked at
                    // the launch scale and gets LINEAR-upscaled -> looks like the vanilla font, not the Apple font.
                    // Setup actions (which open screens / give items) run AFTER the reload, in the RELOAD case.
                    reloadFuture = mc.reloadResourcePacks();
                    stage = RELOAD;
                    System.out.println("[S1mp1e][Film] shot " + name() + ": gui scale changed, reloading fonts");
                } else {
                    beginShotAfterSetup(mc);
                }
            }
            case RELOAD -> {
                boolean done = (reloadFuture == null || reloadFuture.isDone())
                        && mc.gui != null && mc.gui.overlay() == null;
                if (done) {
                    reloadFuture = null;
                    System.out.println("[S1mp1e][Film] shot " + name() + ": fonts reloaded");
                    beginShotAfterSetup(mc);
                }
            }
            case SETTLE -> {
                long el = System.currentTimeMillis() - settleStartMs;
                boolean moving = shot.has("camera") && shot.getAsJsonArray("camera").size() > 1;
                long preload = moving ? PRELOAD.length * PRELOAD_STEP_MS : 0;
                if (el < preload) {                        // walk the camera path so every chunk it sees is built
                    settleF = PRELOAD[(int) (el / PRELOAD_STEP_MS)] * frames();
                    return;
                }
                settleF = 0;
                el -= preload;
                double settle = shot.has("settle") ? shot.get("settle").getAsDouble() : 3.0;
                boolean chunks = mc.levelRenderer == null || mc.levelRenderer.hasRenderedAllSections();
                if (el >= settle * 1000 && (chunks || el > settle * 1000 + 15000)) {
                    try { mc.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) { }
                    // Recipe-unlock / advancement toasts from the world setup ("已解鎖新的合成配方") must not creep
                    // into the frame — clear the toast queue just before rolling.
                    try { mc.gui.toastManager().clear(); } catch (Throwable ignored) { }
                    FilmClock.step();
                    frameIdx = -num(shot, "preroll", 30);
                    sub = 0;
                    stage = frameIdx < 0 ? PREROLL : CAPTURE;
                    System.out.println("[S1mp1e][Film] shot " + name() + ": rolling (" + frames() + " frames, shutter "
                            + shutter() + ")");
                }
            }
            case PREROLL -> {
                FilmClock.advance(1_000_000_000L / fps);
                frameIdx++;
                if (frameIdx >= 0) stage = CAPTURE;
            }
            case CAPTURE -> {
                capture(mc);
                int s = shutter();
                long frameNs = 1_000_000_000L / fps;
                long subNs = frameNs / 2 / s;
                FilmClock.advance(sub < s - 1 ? subNs : frameNs - subNs * (s - 1));
                if (++sub >= s) {
                    sub = 0;
                    frameIdx++;
                }
                if (frameIdx >= frames()) stage = DRAIN;
            }
            case DRAIN -> {
                if (pending > 0) return;
                if (encoder != null) {
                    int n = encoder.frames();
                    encoder.close();
                    encoder = null;
                    System.out.println("[S1mp1e][Film] shot " + name() + ": wrote " + n + " frames");
                }
                nextShot(mc);
            }
            default -> {
            }
        }
    }

    private static void capture(Minecraft mc) {
        RenderTarget rt = mc.gameRenderer.mainRenderTarget();
        final String n = name();
        final int s = shutter();
        pending++;
        Screenshot.takeScreenshot(rt, img -> {
            try (NativeImage im = img) {
                int w = im.getWidth(), h = im.getHeight();
                if (encoder == null) {
                    encoder = new FilmEncoder(new File(outDir, n + ".mov"), w, h, fps, s);
                    System.out.println("[S1mp1e][Film] shot " + n + ": encoding " + w + "x" + h);
                }
                ByteBuffer bb = MemoryUtil.memByteBuffer(im.getPointer(), w * h * 4);
                byte[] b = new byte[w * h * 4];
                bb.get(b);
                encoder.add(b);
            } catch (Throwable t) {
                System.out.println("[S1mp1e][Film] capture failed: " + t);
            } finally {
                pending--;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ actions

    private static void fire(Minecraft mc, int f) {
        if (shot.has("events")) {
            JsonObject ev = shot.getAsJsonObject("events");
            String k = Integer.toString(f);
            if (ev.has(k)) for (JsonElement a : ev.getAsJsonArray(k)) act(mc, a.getAsString());
        }
        List<Runnable> due = scheduled.remove(f);
        if (due != null) for (Runnable r : due) r.run();
    }

    private static void later(int frame, Runnable r) {
        scheduled.computeIfAbsent(frame, k -> new ArrayList<>()).add(r);
    }

    private static void act(Minecraft mc, String a) {
        String[] t = a.trim().split(" ", 2);
        String op = t[0];
        String arg = t.length > 1 ? t[1] : "";
        Screen sc = mc.gui.screen();
        LocalPlayer p = mc.player;
        switch (op) {
            case "cmd" -> command(mc, arg);
            case "screen" -> mc.gui.setScreen(screen(mc, arg));
            case "type" -> {
                String[] q = arg.split(" ", 2);
                int every = Integer.parseInt(q[0]);
                int[] cps = q.length > 1 ? q[1].codePoints().toArray() : new int[0];
                for (int i = 0; i < cps.length; i++) {
                    final int cp = cps[i];
                    later(frameIdx + i * every, () -> {
                        Screen s = mc.gui.screen();
                        if (s != null) s.charTyped(new CharacterEvent(cp));
                    });
                }
            }
            case "key" -> {
                if (sc != null) {
                    int code = Integer.parseInt(arg.trim());
                    sc.keyPressed(new KeyEvent(code, 0, 0));
                    sc.keyReleased(new KeyEvent(code, 0, 0));
                }
            }
            case "hold" -> {
                String[] q = arg.split(" ");
                KeyMapping km = mapping(mc, q[0]);
                if (km != null) {
                    boolean down = q.length < 2 || q[1].equals("on");
                    km.setDown(down);
                    if (down) held.add(km); else held.remove(km);
                }
            }
            case "attack" -> {
                if (p == null) break;
                if (mc.hitResult instanceof EntityHitResult eh && mc.gameMode != null) mc.gameMode.attack(p, eh.getEntity());
                else p.resetAttackStrengthTicker();
                p.swing(InteractionHand.MAIN_HAND);
            }
            case "click", "press", "release", "drag" -> {
                if (sc == null) break;
                String[] q = arg.trim().split(" ");
                double x = coord(mc, q[0], true), y = coord(mc, q[1], false);
                MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
                switch (op) {
                    case "click" -> {
                        sc.mouseClicked(ev, false);
                        sc.mouseReleased(ev);
                    }
                    case "press" -> sc.mouseClicked(ev, false);
                    case "release" -> sc.mouseReleased(ev);
                    default -> sc.mouseDragged(ev, Double.parseDouble(q[2]), Double.parseDouble(q[3]));
                }
            }
            case "scroll" -> {
                if (sc == null) break;
                String[] q = arg.trim().split(" ");
                sc.mouseScrolled(coord(mc, q[0], true), coord(mc, q[1], false), 0, Double.parseDouble(q[2]));
            }
            case "slot" -> {
                if (p != null) p.getInventory().setSelectedSlot(Integer.parseInt(arg.trim()));
            }
            case "module" -> {
                String[] q = arg.trim().split(" ");
                Module m = ModuleManager.byName(q[0]);
                if (m != null) {
                    if (phase == SHOTS) touchedModules.putIfAbsent(m, m.enabled);   // per-shot; world() sets the baseline
                    m.setEnabled(q.length < 2 || q[1].equals("on"));
                } else System.out.println("[S1mp1e][Film] no module " + q[0]);
            }
            case "fov" -> mc.options.fov().set(Integer.parseInt(arg.trim()));
            case "progress" -> {
                if (sc instanceof net.minecraft.client.gui.screens.ProgressScreen ps) ps.progressStagePercentage(Integer.parseInt(arg.trim()));
            }
            case "gui" -> setGui(mc, Integer.parseInt(arg.trim()));
            case "set" -> setSetting(arg);
            case "hud" -> {
                if (shotHideGui == null) shotHideGui = mc.gui.hud.isHidden();
                setHudHidden(mc, arg.trim().equals("off"));
            }
            case "particles" -> {
                if (shotParticles == null) shotParticles = mc.options.particles().get();
                mc.options.particles().set(switch (arg.trim()) {
                    case "minimal" -> net.minecraft.server.level.ParticleStatus.MINIMAL;
                    case "decreased" -> net.minecraft.server.level.ParticleStatus.DECREASED;
                    default -> net.minecraft.server.level.ParticleStatus.ALL;
                });
            }
            case "zoom" -> zoomHeld = arg.trim().equals("on");
            case "use" -> {
                if (p != null && mc.gameMode != null && mc.hitResult instanceof BlockHitResult bh) mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bh);
            }
            case "useitem" -> {
                if (p != null && mc.gameMode != null) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            }
            case "sclick", "rclick" -> {
                if (sc == null) break;
                String[] q = arg.trim().split(" ");
                double x = coord(mc, q[0], true), y = coord(mc, q[1], false);
                MouseButtonEvent ev = op.equals("sclick")
                        ? new MouseButtonEvent(x, y, new MouseButtonInfo(0, GLFW.GLFW_MOD_SHIFT))
                        : new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
                sc.mouseClicked(ev, false);
                sc.mouseReleased(ev);
            }
            case "close" -> {
                if (sc != null) sc.onClose();
            }
            case "log" -> System.out.println("[S1mp1e][Film] " + arg);
            default -> System.out.println("[S1mp1e][Film] unknown action: " + a);
        }
    }

    /** A GUI coordinate: plain number, or "c<offset>" = offset from the screen centre (e.g. "c-40"). */
    private static double coord(Minecraft mc, String v, boolean x) {
        if (v.startsWith("c")) {
            Window w = mc.getWindow();
            double c = (x ? w.getGuiScaledWidth() : w.getGuiScaledHeight()) / 2.0;
            return c + (v.length() > 1 ? Double.parseDouble(v.substring(1)) : 0);
        }
        return Double.parseDouble(v);
    }

    private static Screen screen(Minecraft mc, String what) {
        LocalPlayer p = mc.player;
        return switch (what.trim()) {
            case "inventory" -> p == null ? null : new InventoryScreen(p);
            case "creative" -> p == null ? null : new CreativeModeInventoryScreen(p, p.level().enabledFeatures(), true);
            case "chat" -> new ChatScreen("", false);
            case "config" -> new S1mp1eConfigScreen();
            case "pause" -> new PauseScreen(true);
            case "progress" -> {
                net.minecraft.client.gui.screens.ProgressScreen ps = new net.minecraft.client.gui.screens.ProgressScreen(false);
                ps.progressStartNoAbort(net.minecraft.network.chat.Component.translatable("menu.savingLevel"));
                ps.progressStage(net.minecraft.network.chat.Component.translatable("menu.savingChunks"));
                yield ps;
            }
            case "options" -> new OptionsScreen(mc.gui.screen(), mc.options, true);
            case "video" -> new VideoSettingsScreen(mc.gui.screen(), mc, mc.options);
            case "worlds" -> new SelectWorldScreen(mc.gui.screen());
            case "advancements" -> p == null ? null : new AdvancementsScreen(p.connection.getAdvancements());
            case "stats" -> p == null ? null : new StatsScreen(mc.gui.screen(), p.getStats());
            case "book" -> {
                if (p == null) yield null;
                ItemStack st = p.getMainHandItem();
                WritableBookContent c = st.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
                yield new BookEditScreen(p, st, InteractionHand.MAIN_HAND, c);
            }
            case "bookview" -> {
                if (p == null) yield null;
                BookViewScreen.BookAccess acc = BookViewScreen.BookAccess.fromItem(p.getMainHandItem());
                yield acc == null ? null : new BookViewScreen(acc);
            }
            case "none" -> null;
            default -> {
                String w = what.trim();
                if (w.startsWith("sign ") && mc.level != null) {       // "sign x y z": edit the sign block entity there
                    String[] q = w.substring(5).trim().split(" ");
                    BlockPos pos = new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
                    if (mc.level.getBlockEntity(pos) instanceof SignBlockEntity be) yield new SignEditScreen(be, true, false);
                    System.out.println("[S1mp1e][Film] no sign at " + pos);
                    yield null;
                }
                if (w.startsWith("message ")) yield new GenericMessageScreen(Component.literal(w.substring(8)));
                if (w.startsWith("tmessage ")) yield new GenericMessageScreen(Component.translatable(w.substring(9).trim()));
                yield null;
            }
        };
    }

    private static KeyMapping mapping(Minecraft mc, String n) {
        return switch (n) {
            case "tab" -> mc.options.keyPlayerList;
            case "attack" -> mc.options.keyAttack;
            case "use" -> mc.options.keyUse;
            case "shift" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            case "jump" -> mc.options.keyJump;
            case "forward" -> mc.options.keyUp;
            default -> {
                for (KeyMapping k : mc.options.keyMappings) if (k.getName().equals(n)) yield k;   // any binding by id
                yield null;
            }
        };
    }

    /** Prints command failures (feedback is off in the film world, so a typo would otherwise be silent). */
    private static final net.minecraft.commands.CommandSource CMD_LOG = new net.minecraft.commands.CommandSource() {
        @Override
        public void sendSystemMessage(Component m) {
            System.out.println("[S1mp1e][Film] cmd> " + m.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    };

    private static void command(Minecraft mc, String cmd) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        final String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        server.execute(() -> {
            try {
                List<ServerPlayer> ps = server.getPlayerList().getPlayers();
                if (!ps.isEmpty()) server.getCommands().performPrefixedCommand(ps.get(0).createCommandSourceStack().withSource(CMD_LOG), c);
            } catch (Throwable t) {
                System.out.println("[S1mp1e][Film] command failed: " + c + " : " + t);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ paths

    /** Eye position + yaw/pitch at film frame {@code f} along the shot's camera keys (Catmull-Rom, eased ends). */
    private static void applyCamera(Minecraft mc, double f) {
        if (!shot.has("camera") || mc.player == null) return;
        JsonArray keys = shot.getAsJsonArray("camera");
        if (keys.isEmpty()) return;
        double[] v = sample(keys, f, new String[]{"pos0", "pos1", "pos2", "yaw", "pitch"});
        double x = v[0], y = v[1], z = v[2];
        if (shot.has("relative") && shot.get("relative").getAsBoolean() && spawnEye != null) {
            x += spawnEye.x;
            y += spawnEye.y;
            z += spawnEye.z;
        }
        LocalPlayer p = mc.player;
        double fy = y - p.getEyeHeight();
        p.getAbilities().flying = true;
        p.noPhysics = true;
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(x, fy, z);
        p.xo = x; p.yo = fy; p.zo = z;
        p.xOld = x; p.yOld = fy; p.zOld = z;
        float yaw = (float) v[3], pitch = (float) v[4];
        p.setYRot(yaw); p.yRotO = yaw;
        p.setXRot(pitch); p.xRotO = pitch;
        p.setYHeadRot(yaw); p.yHeadRotO = yaw;
        p.yBodyRot = yaw; p.yBodyRotO = yaw;
    }

    /** "gcam":[{"f","x","y","z"}] - GUI camera keys (shift in GUI px, z = extra scale, 1 + z), eased like the camera. */
    private static void applyGcam(double f) {
        if (shot == null || !shot.has("gcam")) {
            gcamX = gcamY = 0;
            gcamS = 1;
            return;
        }
        double[] v = sample(shot.getAsJsonArray("gcam"), f, new String[]{"x", "y", "z"});
        gcamX = v[0];
        gcamY = v[1];
        gcamS = 1 + v[2];
    }

    private static void applyCursor(Minecraft mc, double f) {
        Window w = mc.getWindow();
        if (shot.has("cursor")) {
            double[] v = sample(shot.getAsJsonArray("cursor"), f, new String[]{"x", "y"});
            boolean rel = shot.has("cursorRel") && shot.get("cursorRel").getAsBoolean();   // offsets from screen centre
            cursorX = v[0] + (rel ? w.getGuiScaledWidth() / 2.0 : 0);
            cursorY = v[1] + (rel ? w.getGuiScaledHeight() / 2.0 : 0);
        }
        if (cursorX < 0) return;
        try {
            double px = cursorX * w.getScreenWidth() / (double) w.getGuiScaledWidth();
            double py = cursorY * w.getScreenHeight() / (double) w.getGuiScaledHeight();
            java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
            java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setDouble(mc.mouseHandler, px);
            fy.setDouble(mc.mouseHandler, py);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Catmull-Rom through keys placed at their frames, with zero tangents at the first and last key so a move always
     * starts and lands at rest (slow in / slow out). Channels named "posN" read element N of "pos".
     */
    private static double[] sample(JsonArray keys, double f, String[] ch) {
        int n = keys.size();
        double[] out = new double[ch.length];
        double[] kf = new double[n];
        double[][] kv = new double[n][ch.length];
        for (int i = 0; i < n; i++) {
            JsonObject k = keys.get(i).getAsJsonObject();
            kf[i] = k.get("f").getAsDouble();
            for (int c = 0; c < ch.length; c++) {
                if (ch[c].startsWith("pos")) kv[i][c] = k.getAsJsonArray("pos").get(ch[c].charAt(3) - '0').getAsDouble();
                else kv[i][c] = k.has(ch[c]) ? k.get(ch[c]).getAsDouble() : (i > 0 ? kv[i - 1][c] : 0);
            }
        }
        if (n == 1 || f <= kf[0]) return kv[0].clone();
        if (f >= kf[n - 1]) return kv[n - 1].clone();
        int i = 0;
        while (i < n - 2 && f > kf[i + 1]) i++;
        double t0 = kf[i], t1 = kf[i + 1], u = (f - t0) / (t1 - t0), dt = t1 - t0;
        for (int c = 0; c < ch.length; c++) {
            double p0 = kv[i][c], p1 = kv[i + 1][c];
            double m0 = i == 0 ? 0 : (p1 - kv[i - 1][c]) / (t1 - kf[i - 1]) * dt;
            double m1 = i + 1 == n - 1 ? 0 : (kv[i + 2][c] - p0) / (kf[i + 2] - t0) * dt;
            double u2 = u * u, u3 = u2 * u;
            out[c] = (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void forceWindow(Minecraft mc) {
        Window win = mc.getWindow();
        // Never exclusive full screen: it hides the desktop and, if anything stalls, locks the whole machine. A
        // windowed 4K framebuffer captures real 4K regardless of the desktop resolution; the window is placed so it is
        // visible (top-left) unless the script parks it. windowX/windowY override the position.
        if (win.getWidth() != width || win.getHeight() != height) {
            win.setWindowed(width, height);
            int wx = script != null && script.has("windowX") ? script.get("windowX").getAsInt() : 0;
            int wy = script != null && script.has("windowY") ? script.get("windowY").getAsInt() : 0;
            GLFW.glfwSetWindowPos(win.handle(), wx, wy);
            mc.framebufferSizeChanged();
        }
    }

    /** Sets the GUI scale and returns whether the EFFECTIVE (window-clamped) scale actually changed, so the caller
     *  knows whether the font atlas must be re-baked. Comparing the effective scale (not the requested value) avoids
     *  a reload loop when the request is clamped to the same value the window already had. */
    private static boolean setGui(Minecraft mc, int scale) {
        int before = mc.getWindow().getGuiScale();
        // Always forced (FilmGuiScaleMixin): independent of the window size (a 1080p layout test at gui/2 matches the
        // 4K shot exactly) and past vanilla's cap for macro shots; the player's option value is never touched.
        guiOverride = scale;
        if (before != scale) mc.framebufferSizeChanged();
        return mc.getWindow().getGuiScale() != before;
    }

    /** Modules a shot switched ("module"), with their state before; restored when the shot ends. */
    private static final Map<Module, Boolean> touchedModules = new HashMap<>();
    private static net.minecraft.server.level.ParticleStatus shotParticles;
    private static Boolean shotHideGui;

    /** 26.2 keeps the F1 state on Hud (private isHidden, no setter). */
    private static void setHudHidden(Minecraft mc, boolean hidden) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.gui.Hud.class.getDeclaredField("isHidden");
            f.setAccessible(true);
            f.setBoolean(mc.gui.hud, hidden);
        } catch (Throwable t) {
            System.out.println("[S1mp1e][Film] hud toggle failed: " + t);
        }
    }

    private static void restoreSettings() {
        for (Map.Entry<Module, Boolean> e : touchedModules.entrySet()) e.getKey().setEnabled(e.getValue());
        touchedModules.clear();
        if (shotHideGui != null) {
            setHudHidden(Minecraft.getInstance(), shotHideGui);
            shotHideGui = null;
        }
        if (shotParticles != null) {
            Minecraft.getInstance().options.particles().set(shotParticles);
            shotParticles = null;
        }
        for (Map.Entry<Setting, Object[]> e : touched.entrySet()) {
            Setting st = e.getKey();
            Object[] v = e.getValue();
            st.boolValue = (Boolean) v[0];
            st.intValue = (Integer) v[1];
            st.doubleValue = (Double) v[2];
            st.colorValue = (Integer) v[3];
            st.modeValue = (String) v[4];
        }
        touched.clear();
    }

    /** "set Module|Setting name|value": change one module setting for the current shot only. */
    private static void setSetting(String arg) {
        String[] q = arg.split("\\|");
        if (q.length < 3) return;
        Module m = ModuleManager.byName(q[0].trim());
        if (m == null) {
            System.out.println("[S1mp1e][Film] no module " + q[0]);
            return;
        }
        for (Setting st : m.settings) {
            if (!st.name.equalsIgnoreCase(q[1].trim())) continue;
            touched.putIfAbsent(st, new Object[]{st.boolValue, st.intValue, st.doubleValue, st.colorValue, st.modeValue});
            String v = q[2].trim();
            switch (st.type) {
                case BOOL -> st.boolValue = Boolean.parseBoolean(v);
                case INT -> st.setInt(Integer.parseInt(v));
                case DOUBLE -> st.setDouble(Double.parseDouble(v));
                case COLOR -> st.colorValue = (int) Long.parseLong(v.replace("#", ""), 16);
                case MODE -> st.setMode(v);
            }
            return;
        }
        System.out.println("[S1mp1e][Film] no setting " + q[1] + " in " + q[0]);
    }

    /** Runs the shot's setup actions (open screens, give items, ...) then enters the SETTLE stage. Called once the
     *  font atlas is ready — either immediately (no scale change) or after the RELOAD completes. */
    private static void beginShotAfterSetup(Minecraft mc) {
        hideHand = shot.has("hand") && !shot.get("hand").getAsBoolean();
        if (shot.has("setup")) for (JsonElement a : shot.getAsJsonArray("setup")) act(mc, a.getAsString());
        settleStartMs = System.currentTimeMillis();
        stage = SETTLE;
        System.out.println("[S1mp1e][Film] shot " + name() + ": settling");
    }

    private static void createWorld(Minecraft mc) {
        try {
            deleteRecursively(new File(new File(mc.gameDirectory, "saves"), "s1mp1e-film"));
            LevelSettings settings = new LevelSettings("s1mp1e-film", GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            WorldOptions opts = new WorldOptions(seed, true, false);
            Function<HolderLookup.Provider, WorldDimensions> dims = provider ->
                    provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
            WorldOpenFlows flows = mc.createWorldOpenFlows();
            flows.createFreshLevel("s1mp1e-film", settings, opts, dims, new TitleScreen());
        } catch (Throwable t) {
            System.out.println("[S1mp1e][Film] create world failed: " + t);
            phase = DONE;
        }
    }

    private static void deleteRecursively(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String name() {
        return shot == null ? "?" : str(shot, "name", "shot" + shotIdx);
    }

    private static int frames() {
        return num(shot, "frames", 120);
    }

    private static int shutter() {
        return Math.max(1, num(shot, "shutter", 1));
    }

    private static int num(JsonObject o, String k, int d) {
        return o != null && o.has(k) ? o.get(k).getAsInt() : d;
    }

    private static String str(JsonObject o, String k, String d) {
        return o != null && o.has(k) ? o.get(k).getAsString() : d;
    }
}
