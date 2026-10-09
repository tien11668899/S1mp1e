package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.File;

/**
 * DevShot v2 — deterministic screenshot harness for cross-version visual comparison.
 *
 * <p><b>Completely inert unless the environment variable {@code S1MP1E_SHOT} names an output
 * folder</b> (the shot pipeline) or {@code S1MP1E_AUDIT} is set (the one-shot mixin audit). It
 * ships in the jar but does nothing at all when both are unset — a normal game run never touches
 * any of this. Environment variables reach the forked game JVM of {@code runClient}; a {@code -D}
 * system property on the gradle command line does not, which is why this reads {@link System#getenv}.
 *
 * <p>When the shot pipeline is active, so that every S1mp1e version renders under identical
 * conditions, on the first rendered frame it forces the window to 1280x720, GUI scale 2 and the
 * language to {@code zh_tw}, then walks a fixed script and writes seven PNGs with the game's own
 * framebuffer writer ({@link ScreenshotRecorder#takeScreenshot} + {@link NativeImage#writeTo}),
 * straight into the folder {@code S1MP1E_SHOT} names, overwriting:
 * <ol>
 *   <li>{@code title.png}   — the title screen, 60 frames after it is fully shown;</li>
 *   <li>{@code config.png}  — the S1mp1e settings page over the title, +90 frames;</li>
 *   <li>{@code world.png}   — a fresh flat {@code devshot} world (seed 12345, survival, peaceful,
 *       cheats) with a scripted loadout (sword / cooked beef / stone / off-hand shield / full iron
 *       armor / Speed), +60 frames after the world has settled;</li>
 *   <li>{@code config-world.png} — the settings page over that world, +90 frames;</li>
 *   <li>{@code inventory.png}    — the survival inventory, +60 frames;</li>
 *   <li>{@code options.png}      — vanilla Video Settings, +60 frames;</li>
 * </ol>
 * then leaves the world and quits cleanly ({@link MinecraftClient#scheduleStop()}).
 *
 * <p>Every step is wrapped so a failure only skips that step ("{@code [S1mp1e] DevShot skipped
 * &lt;step&gt;: &lt;reason&gt;}") and the script moves on, and a global 240 s watchdog quits the
 * game so a stuck wait can never hang the run.
 *
 * <p>Driven from a single render-frame hook ({@code DevShotMixin} at {@code MinecraftClient.render}
 * TAIL).
 *
 * <p>1.21.1 API deltas from the 1.20.1 reference: {@code IntegratedServerLoader.createAndStart}
 * takes a trailing {@code Screen} (the on-cancel/parent screen); {@code VideoOptionsScreen}'s
 * constructor takes {@code (Screen, MinecraftClient, GameOptions)}. Everything else is identical.
 */
public final class DevShot {
    private DevShot() {}

    private static final int TITLE_FRAMES   = 60;
    private static final int CONFIG_FRAMES  = 90;
    private static final int WORLD_SETTLE    = 100;  // frames the world renders before the loadout
    private static final int WORLD_FRAMES    = 60;
    private static final int INV_FRAMES      = 60;
    private static final int OPTIONS_FRAMES  = 60;

    /** Per-wait frame caps so one stuck wait skips rather than eating the whole run. */
    private static final int WAIT_WORLD_CAP  = 2400;  // flat-world gen is quick; generous anyway
    private static final int WAIT_SCREEN_CAP = 300;

    /** Global watchdog: quit no matter what after this long. */
    private static final long WATCHDOG_MS = 240_000L;

    // State machine phases.
    private static final int P_INIT = 0, P_WAIT_TITLE = 1, P_TITLE = 2,
                             P_WAIT_CONFIG = 3, P_CONFIG = 4,
                             P_WAIT_WORLD = 5, P_WORLD_SETTLE = 6, P_WORLD = 7,
                             P_WAIT_CONFIG2 = 8, P_CONFIG_WORLD = 9,
                             P_WAIT_INV = 10, P_INV = 11,
                             P_WAIT_OPTIONS = 12, P_OPTIONS = 13,
                             P_STOP = 14, P_DONE = 15;
    // BATCH A extra phases (mode "batcha" only): after the inventory shot, switch to creative and capture the fused
    // tabs (B) + glass scrollbar (C), then the advancements window framed in glass (A). Inert unless S1MP1E_SHOT_MODE.
    private static final int P_BA_GM = 20, P_BA_CREATIVE_WAIT = 21, P_BA_CREATIVE = 22,
                             P_BA_ADV_WAIT = 23, P_BA_ADV = 24;
    // VERIFY sweeps (modes screens / tabs / lists / tooltips / effects / hud / modules / flicker, comma-separated, or
    // "all"): after the inventory shot, DevShotVerify drives the acceptance-checklist scenes. Inert unless
    // S1MP1E_SHOT_MODE names them.
    private static final int P_VERIFY = 60;
    // INTRO mode (S1MP1E_SHOT_MODE=intro): boot brand-intro frames -> after-title (glass intact after the intro ran
    // during the first reload) -> world-entry loop preview -> real world entry loop -> after-world. Inert otherwise.
    private static final int P_LOOPPREV = 70;
    private static final int P_AUDIT = 80;
    // ESSENTIAL mode (S1MP1E_SHOT_MODE=essential): menu-only shots of Essential's UI (title overlay, then its settings
    // screen opened by reflection) for the liquid-glass + zh_TW Essential compat. Never accepts anything.
    private static final int P_ESSENTIAL = 90;
    // NAMETAGS mode (S1MP1E_SHOT_MODE=nametags): after the world settles, spawn named armor stands against striped
    // walls and capture one frame per NameTags Background look (Glass / Vanilla / Off). Inert unless that mode is set.
    private static final int P_NAMETAGS = 100;

    private static boolean resolved;      // env vars checked exactly once
    private static String  mode;          // S1MP1E_SHOT_MODE (null => base pipeline only)
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions (createAndStart, reloadResources, disconnect) pump the
     *  render loop synchronously, which re-fires this render-TAIL hook. Without this, the current
     *  step would run again mid-action (e.g. re-creating the world every pumped frame → recursion
     *  → StackOverflow/OOM). Nested frames pumped inside a step do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** Target reference resolution — forced onto the framebuffer so shots are 1280x720 even when
     *  the desktop is smaller than that and the OS clamps the on-screen window. */
    private static final int SHOT_W = 1280, SHOT_H = 720;
    /** Current forced framebuffer size (DevShotVerify narrows it for the COMPACT effect column, then restores). */
    private static int targetW = SHOT_W, targetH = SHOT_H;

    // ---- intro mode state (S1MP1E_SHOT_MODE=intro) ----
    private static boolean nameTagsMode;   // S1MP1E_SHOT_MODE=nametags: the three Background looks
    private static int     ntStage;        // name-tag sweep stage index (0..NT_MODES.length)
    private static final String[] NT_MODES = { "Glass", "Vanilla", "Off" };

    private static boolean introMode;     // boot brand-intro capture path active
    private static int     introCount;    // boot overlay frame index
    private static long    introLastMs;   // last boot-overlay capture time (ms spacing)
    private static boolean introSeen;     // the boot SplashOverlay was seen at least once
    private static boolean introWorld;    // after boot capture: continue title -> loop -> world -> after-world
    private static int     wlCount;       // world-entry loop (real LevelLoadingScreen) frame index
    private static long    wlLastMs;
    private static long    lpLastMs;      // loop-preview frame spacing
    private static int     lpCount;       // loop-preview frame index

    /** Change the forced framebuffer size (dev sweeps only). */
    static void setTarget(int w, int h) { targetW = w; targetH = h; }

    /** Called at the end of every rendered frame (render thread). No-op when both vars are unset. */
    public static void onRenderEnd(MinecraftClient client) {
        if (!resolved) {
            resolved = true;
            try {
                String dir = System.getenv("S1MP1E_SHOT");
                if (dir != null && !dir.trim().isEmpty()) {
                    outDir = new File(dir.trim());
                    outDir.mkdirs();
                    startMs = System.currentTimeMillis();
                    System.out.println("[S1mp1e][DevShot] active -> " + outDir.getAbsolutePath());
                }
            } catch (Throwable t) {
                outDir = null;
            }
            try {
                String a = System.getenv("S1MP1E_AUDIT");
                auditPending = a != null && !a.trim().isEmpty();
            } catch (Throwable t) {
                auditPending = false;
            }
            try {
                String m = System.getenv("S1MP1E_SHOT_MODE");
                mode = (m == null || m.trim().isEmpty()) ? null : m.trim().toLowerCase();
                introMode = "intro".equals(mode);
                nameTagsMode = "nametags".equals(mode);
            } catch (Throwable t) {
                mode = null;
            }
        }

        // One-shot mixin audit, independent of the shot pipeline.
        if (auditPending) {
            auditPending = false;
            runAudit();
        }

        if (outDir == null || client == null) return;

        // Intro mode runs before the normal state machine: it shoots the boot SplashOverlay (brand intro) frame by
        // frame, then hands off (introWorld) to the title -> loop-preview -> world-entry path below.
        if (introMode) {
            if (busy) return;
            busy = true;
            try {
                stepIntro(client);
            } catch (Throwable t) {
                System.out.println("[S1mp1e][DevShot] intro capture error: " + t);
                introMode = false; introWorld = true; frames = 0; phase = P_WAIT_TITLE;
            } finally {
                busy = false;
            }
            return;
        }

        // Global watchdog — never hang.
        long watchdog = DevShotVerify.handles(mode) ? 1_500_000L : WATCHDOG_MS;
        if (phase != P_DONE && startMs > 0 && System.currentTimeMillis() - startMs > watchdog) {
            System.out.println("[S1mp1e][DevShot] watchdog fired (" + (watchdog / 1000)
                    + "s) at phase " + phase + " — quitting.");
            try { client.scheduleStop(); } catch (Throwable ignored) {}
            phase = P_DONE;
            return;
        }

        // A heavy step may re-fire this hook (nested render); ignore those re-entrant frames.
        if (busy) return;
        busy = true;
        try {
            // Keep the framebuffer at the reference resolution every frame (cheap no-op once set).
            forceFramebuffer(client);
            switch (phase) {
                case P_INIT:               stepInit(client);                     break;
                case P_WAIT_TITLE:         stepWaitTitle(client);                break;
                case P_TITLE:              stepTitle(client);                    break;
                case P_WAIT_CONFIG:        stepWaitConfig(client, P_CONFIG);     break;
                case P_CONFIG:             stepConfig(client);                   break;
                case P_WAIT_WORLD:         stepWaitWorld(client);                break;
                case P_WORLD_SETTLE:       stepWorldSettle(client);              break;
                case P_WORLD:              stepWorld(client);                    break;
                case P_WAIT_CONFIG2:       stepWaitConfig(client, P_CONFIG_WORLD); break;
                case P_CONFIG_WORLD:       stepConfigWorld(client);              break;
                case P_WAIT_INV:           stepWaitInventory(client);            break;
                case P_INV:                stepInventory(client);                break;
                case P_WAIT_OPTIONS:       stepWaitOptions(client);              break;
                case P_OPTIONS:            stepOptions(client);                  break;
                case P_BA_GM:              stepBatchaGameMode(client);           break;
                case P_BA_CREATIVE_WAIT:   stepBatchaCreativeWait(client);       break;
                case P_BA_CREATIVE:        stepBatchaCreative(client);           break;
                case P_BA_ADV_WAIT:        stepBatchaAdvWait(client);            break;
                case P_BA_ADV:             stepBatchaAdv(client);                break;
                case P_VERIFY:             if (DevShotVerify.step(client)) phase = P_STOP; break;
                case P_LOOPPREV:           stepLoopPreview(client);              break;
                case P_AUDIT:              if (DevAudit.step(client)) phase = P_STOP; break;
                case P_ESSENTIAL:          stepEssential(client);                break;
                case P_NAMETAGS:           stepNameTags(client);                 break;
                case P_STOP:               stepStop(client);                     break;
                default:                   break;
            }
        } catch (Throwable t) {
            // Last-resort guard: never wedge the frame loop. Individual steps already
            // catch their own failures; anything reaching here jumps straight to quit.
            System.out.println("[S1mp1e][DevShot] fatal error at phase " + phase + ": " + t);
            t.printStackTrace();
            phase = P_STOP;
        } finally {
            busy = false;
        }
    }

    /**
     * Force MC's main framebuffer to {@link #SHOT_W}x{@link #SHOT_H} so every reference shot is
     * 1280x720 regardless of the desktop size. On a desktop smaller than 1280x720 the OS clamps
     * the on-screen window, but the off-screen framebuffer we screenshot can still be full size —
     * the frame renders into it at 1280x720 and only the (unwatched) on-screen blit is scaled.
     *
     * <p>Dev-only: DevShot runs solely under {@code S1MP1E_SHOT} (never in a launcher build), so
     * reflecting the yarn-named {@code framebufferWidth/Height} fields is safe here. No-op once the
     * size already matches, so this is cheap to call every frame and self-heals after any stray
     * GLFW framebuffer-resize callback.
     */
    private static void forceFramebuffer(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            if (win.getFramebufferWidth() == targetW && win.getFramebufferHeight() == targetH) return;
            java.lang.reflect.Field fw = net.minecraft.client.util.Window.class.getDeclaredField("framebufferWidth");
            java.lang.reflect.Field fh = net.minecraft.client.util.Window.class.getDeclaredField("framebufferHeight");
            fw.setAccessible(true); fh.setAccessible(true);
            fw.setInt(win, targetW); fh.setInt(win, targetH);
            client.onResolutionChanged();   // resizes the framebuffer + GUI scale to the forced size
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + targetW + "x" + targetH);
        } catch (Throwable t) {
            skip("force framebuffer " + SHOT_W + "x" + SHOT_H, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(MinecraftClient client) {
        try {
            client.getWindow().setWindowedSize(1280, 720);
            client.options.getGuiScale().setValue(2);
            // Other game windows (parallel runs) may steal focus; never let the world auto-pause behind a shot.
            client.options.pauseOnLostFocus = false;
            // Only pay the resource-reload cost when the language actually differs.
            if (!"zh_tw".equals(client.getLanguageManager().getLanguage())) {
                client.getLanguageManager().setLanguage("zh_tw");
                client.options.language = "zh_tw";
                client.reloadResources();
            }
            client.onResolutionChanged();
        } catch (Throwable t) {
            skip("init (window/scale/language)", t);
        }
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static void stepWaitTitle(MinecraftClient client) {
        // Wait past the Mojang splash / any resource reload; the TitleScreen becomes currentScreen
        // behind the SplashOverlay while it is still fading, so also require the overlay to be gone.
        if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
            frames = 0; phase = P_TITLE;
        } else if (++frames > WAIT_SCREEN_CAP * 4) {
            skip("wait title screen", new IllegalStateException("title never shown"));
            frames = 0; phase = P_TITLE;   // try to shoot whatever is on screen, then continue
        }
    }

    private static void stepEssential(MinecraftClient client) {
        frames++;
        if (frames == 90) { esNote(client, "title"); capture(client, "ess-title.png"); return; }
        if (frames == 91) {
            try {
                Class<?> c = Class.forName("gg.essential.config.McEssentialConfig");
                Object inst = c.getField("INSTANCE").get(null);
                java.lang.reflect.Method m = c.getMethod("gui$default", c, String.class, int.class, Object.class);
                // a long category ("Quality of Life") so the scrolled shot shows how glass clips under the header
                open(client, (net.minecraft.client.gui.screen.Screen) m.invoke(null, inst, "Quality of Life", 0, null), "open Essential settings");
            } catch (Throwable t) {
                skip("essential: open settings", t);
                frames = 0; phase = P_STOP;
            }
            return;
        }
        if (frames == 150) { esNote(client, "settings"); capture(client, "ess-settings.png"); return; }
        if (frames >= 200 && frames <= 215 && frames % 5 == 0) { esScroll(client, 0.72, -5); return; }   // glass must clip under the header
        if (frames == 260) { esNote(client, "settings-late"); capture(client, "ess-settings2.png"); return; }
        if (frames == 262) { close(client); return; }
        if (frames == 330) { esNote(client, "after-close"); capture(client, "ess-after.png"); return; }
        if (frames == 332) {   // Essential's "Select world to host" modal over the title screen (no ToS needed to open it)
            try {
                Class<?> gu = Class.forName("gg.essential.util.GuiUtil");
                Object inst = gu.getField("INSTANCE").get(null);
                Class<?> wsm = Class.forName("gg.essential.gui.sps.WorldSelectionModal");
                java.lang.reflect.Constructor<?> ctor = null;
                for (java.lang.reflect.Constructor<?> k : wsm.getConstructors()) if (k.getParameterCount() == 1) ctor = k;
                final java.lang.reflect.Constructor<?> fc = ctor;
                kotlin.jvm.functions.Function1<Object, Object> fn = mgr -> {
                    try { return fc.newInstance(mgr); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                };
                gu.getMethod("pushModal", kotlin.jvm.functions.Function1.class).invoke(inst, fn);
            } catch (ClassNotFoundException e) {   // Essential 1.5+: the modal is reached through its invite-or-host flow
                try {
                    Class.forName("gg.essential.gui.sps.InviteOrHostModalFlowKt").getMethod("launchInviteOrHostModalFlow").invoke(null);
                } catch (Throwable t) { skip("essential: host-world flow", t); }
            } catch (Throwable t) { skip("essential: host-world modal", t); }
            return;
        }
        if (frames == 400) { esNote(client, "host-modal"); capture(client, "ess-host.png"); return; }
        if (frames == 402) { open(client, new net.minecraft.client.gui.screen.TitleScreen(), "back to title"); return; }
        if (frames == 405) {   // Essential's screenshot browser (no login needed): header bar vs window corner, scrolling
            try {
                open(client, (net.minecraft.client.gui.screen.Screen) Class.forName("gg.essential.gui.screenshot.components.ScreenshotBrowser")
                        .getConstructor().newInstance(), "open Essential pictures");
            } catch (Throwable t) { skip("essential: pictures", t); }
            return;
        }
        if (frames == 470) { esNote(client, "pictures"); capture(client, "ess-pics.png"); return; }
        if (frames >= 472 && frames <= 487 && frames % 5 == 2) { esScroll(client, 0.5, -5); return; }
        if (frames == 530) { esNote(client, "pictures-scroll"); capture(client, "ess-pics-scroll.png"); return; }
        if (frames == 532) { open(client, new net.minecraft.client.gui.screen.TitleScreen(), "back to title"); return; }
        if (frames >= 540) { frames = 0; phase = P_STOP; }
    }

    /** Mouse-wheel the current screen at (fx · width, middle); Essential reads the mouse from Mouse.x/y (parked there). */
    private static void esScroll(MinecraftClient client, double fx, double amount) {
        try {
            net.minecraft.client.gui.screen.Screen s = client.currentScreen;
            if (s == null) return;
            double w = client.getWindow().getScaledWidth(), h = client.getWindow().getScaledHeight();
            java.lang.reflect.Field mx = net.minecraft.client.Mouse.class.getDeclaredField("x");   // dev = Yarn names
            java.lang.reflect.Field my = net.minecraft.client.Mouse.class.getDeclaredField("y");
            {
                mx.setAccessible(true);
                my.setAccessible(true);
                mx.setDouble(client.mouse, client.getWindow().getWidth() * fx);
                my.setDouble(client.mouse, client.getWindow().getHeight() * 0.5);
            }
            s.mouseScrolled(w * fx, h * 0.5, 0, amount);
        } catch (Throwable t) { skip("essential: scroll", t); }
    }

    private static void esNote(MinecraftClient client, String tag) {
        try {
            if (outDir == null) return;
            net.minecraft.client.gui.screen.Screen s = client.currentScreen;
            java.nio.file.Files.writeString(new File(outDir, "ess-screens.txt").toPath(),
                    tag + " -> " + (s == null ? "null" : s.getClass().getName()) + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) { }
    }

    private static void stepTitle(MinecraftClient client) {
        if (++frames >= TITLE_FRAMES) {
            if (introWorld) {
                // Glass buttons must still render after the boot intro ran during the first resource reload.
                capture(client, "after-title.png");
                // A dev flat world loads in ~2 s, too short to see a whole loop cycle: preview the world-entry loop on
                // its own screen first, then do the real world entry (its LevelLoadingScreen carries the same loop).
                open(client, new LoopPreview(), "open world-entry loop preview");
                frames = 0; lpLastMs = 0L; lpCount = 0; phase = P_LOOPPREV;
                return;
            }
            if ("essential".equals(mode)) { frames = 0; phase = P_ESSENTIAL; return; }   // menus only, no world
            capture(client, "title.png");
            open(client, new S1mp1eConfigScreen(), "open settings (over title)");
            frames = 0; phase = P_WAIT_CONFIG;
        }
    }

    /** Dev-only screen that plays the world-entry loop ({@link BrandIntro#MODE_LOOP}) on pure black, exactly as
     *  {@code LevelLoadingScreen} does under the glass mixin, for as long as it is open. */
    private static final class LoopPreview extends net.minecraft.client.gui.screen.Screen {
        private final long t0 = System.nanoTime();
        LoopPreview() { super(net.minecraft.text.Text.literal("loop preview")); }
        @Override public void render(net.minecraft.client.gui.DrawContext ctx, int mx, int my, float d) {
            int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
            ctx.fill(0, 0, w, h, 0xFF000000);
            ctx.draw();   // flush the black under the immediate-GL loop
            dev.s1mp1e.client.gui.BrandIntro.draw(ctx, (System.nanoTime() - t0) / 1.0E9F,
                    dev.s1mp1e.client.gui.BrandIntro.MODE_LOOP, 1.0F);
        }
        @Override public void renderBackground(net.minecraft.client.gui.DrawContext ctx, int mx, int my, float d) {}
    }

    /** Loop preview: ~8 s at 10 fps into {@code lp_NNN.png}, then on into the real world entry. */
    private static void stepLoopPreview(MinecraftClient client) {
        if (!(client.currentScreen instanceof LoopPreview)) {
            if (++frames > WAIT_SCREEN_CAP) { skip("loop preview", new IllegalStateException("never opened")); lpCount = 80; }
            else return;
        }
        long ms = System.currentTimeMillis();
        if (lpCount < 80) {
            if (ms - lpLastMs >= 100) { lpLastMs = ms; capture(client, String.format("lp_%03d.png", lpCount++)); }
            return;
        }
        close(client);
        if (createWorld(client)) { frames = 0; phase = P_WAIT_WORLD; }
        else { skip("create world", new IllegalStateException("world creation did not start")); phase = P_STOP; }
    }

    /**
     * Boot brand-intro capture: while the {@code SplashOverlay} is up, shoot the framebuffer ~33 fps into
     * {@code intro_NNN.png}; once it is gone (or it never appeared within 20 s) continue through the title (glass must
     * still be intact after the intro ran during the reload) into the world-entry loop, shooting on the way.
     */
    private static void stepIntro(MinecraftClient client) {
        forceFramebuffer(client);
        Object ov = client.getOverlay();
        long now = System.currentTimeMillis();
        if (ov instanceof net.minecraft.client.gui.screen.SplashOverlay) {
            introSeen = true;
            if (now - introLastMs >= 30 && introCount < 220) {
                introLastMs = now;
                capture(client, String.format("intro_%03d.png", introCount++));
            }
            return;
        }
        // Overlay gone (or never showed within 20 s): boot part done. Hand off to the title -> loop -> world path.
        if (introSeen || now - startMs > 20000) {
            System.out.println("[S1mp1e][DevShot] intro capture done: " + introCount + " frames");
            try { client.options.pauseOnLostFocus = false; } catch (Throwable ignored) {}
            introMode = false;
            introWorld = true;
            frames = 0;
            phase = P_WAIT_TITLE;
        }
    }

    // ---- shared config-screen wait -----------------------------------------

    private static void stepWaitConfig(MinecraftClient client, int next) {
        if (client.currentScreen instanceof S1mp1eConfigScreen) {
            frames = 0; phase = next;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait settings screen", new IllegalStateException("settings screen never opened"));
            frames = 0; phase = next;
        }
    }

    // ---- step 3: config over title, then create the world ------------------

    private static void stepConfig(MinecraftClient client) {
        if (++frames < CONFIG_FRAMES) return;
        if (frames == CONFIG_FRAMES) {
            capture(client, "config.png");
            // sodium mode: the Sodium settings screen over the title panorama too (no world to refract)
            String mode = System.getenv("S1MP1E_SHOT_MODE");
            if (mode != null && mode.contains("sodium")) {
                close(client);
                DevShotVerify.openSodiumOptions(client);
                return;
            }
            if (mode != null && mode.contains("settings")) {           // the settings shell over the title panorama
                close(client);
                client.setScreen(new net.minecraft.client.gui.screen.option.OptionsScreen(
                        new net.minecraft.client.gui.screen.TitleScreen(), client.options));
                return;
            }
        } else if (frames < CONFIG_FRAMES + 90) {
            return;
        } else {
            capture(client, "sd-title.png");
        }
        close(client);                      // back to the title
        if (createWorld(client)) {
            frames = 0; phase = P_WAIT_WORLD;
        } else {
            skip("create world", new IllegalStateException("world creation did not start"));
            phase = P_STOP;                 // no world -> skip every world-dependent shot
        }
    }

    // ---- step 4: world load, settle, loadout, shot ------------------------

    private static void stepWaitWorld(MinecraftClient client) {
        // Intro mode: while the real world-entry LevelLoadingScreen is up (it carries the MODE_LOOP loop under the
        // glass mixin), grab a ~20 fps strip so the seamless loop can be judged frame by frame.
        if (introWorld && client.currentScreen instanceof net.minecraft.client.gui.screen.world.LevelLoadingScreen) {
            long ms = System.currentTimeMillis();
            if (ms - wlLastMs >= 50 && wlCount < 400) { wlLastMs = ms; capture(client, String.format("wl_%03d.png", wlCount++)); }
        }
        if (client.world != null && client.player != null && client.getServer() != null
                && client.currentScreen == null) {
            frames = 0; phase = P_WORLD_SETTLE;
        } else if (++frames > WAIT_WORLD_CAP) {
            skip("wait world load", new IllegalStateException("world never became ready"));
            phase = P_STOP;
        }
    }

    private static void stepWorldSettle(MinecraftClient client) {
        frames++;
        if (introWorld) {                   // glass HUD must be intact in game too after the intro; then finish
            if (frames == 20) applyWorldSetup(client);   // apply mid-settle so the loadout syncs before the shot
            if (frames < WORLD_SETTLE) return;
            System.out.println("[S1mp1e][DevShot] world-entry loop frames: " + wlCount);
            capture(client, "after-world.png");
            frames = 0; phase = P_STOP;
            return;
        }
        if (frames < WORLD_SETTLE) return;
        applyWorldSetup(client);            // time/weather/position/loadout (own try/catch inside)
        if (nameTagsMode) { ntStage = 0; frames = 0; phase = P_NAMETAGS; return; }
        frames = 0; phase = P_WORLD;
    }

    private static void stepWorld(MinecraftClient client) {
        // Suppress the transient advancement/recipe toasts and the advancement chat line that the
        // scripted loadout triggers, so the over-world reference frames (world / config-world /
        // inventory) are clean and deterministic. Cleared every frame here; no new toasts appear
        // after the loadout, so the later over-world shots stay clean too.
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        if (++frames >= WORLD_FRAMES) {
            capture(client, "world.png");
            open(client, new S1mp1eConfigScreen(), "open settings (over world)");
            frames = 0; phase = P_WAIT_CONFIG2;
        }
    }

    // ---- step 5: config over world ----------------------------------------

    private static void stepConfigWorld(MinecraftClient client) {
        if (++frames < CONFIG_FRAMES) return;
        capture(client, "config-world.png");
        close(client);
        open(client, new InventoryScreen(client.player), "open inventory");
        frames = 0; phase = P_WAIT_INV;
    }

    // ---- step 6: inventory -------------------------------------------------

    private static void stepWaitInventory(MinecraftClient client) {
        if (client.currentScreen instanceof InventoryScreen) {
            frames = 0; phase = P_INV;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait inventory screen", new IllegalStateException("inventory never opened"));
            frames = 0; phase = P_INV;
        }
    }

    private static void stepInventory(MinecraftClient client) {
        if (++frames < INV_FRAMES) return;
        capture(client, "inventory.png");
        // BATCH A extra scenes: creative (fused tabs B + glass scrollbar C) then advancements (A). Only in that mode.
        if ("batcha".equals(mode)) {
            close(client);
            frames = 0; phase = P_BA_GM;
            return;
        }
        if ("audit".equals(mode)) { close(client); frames = 0; phase = P_AUDIT; return; }
        // VERIFY sweeps (acceptance checklist): screens / tabs / lists / tooltips / effects / hud / modules / flicker.
        if (DevShotVerify.handles(mode)) {
            close(client);
            DevShotVerify.init(outDir, mode);
            frames = 0; phase = P_VERIFY;
            return;
        }
        // VideoOptionsScreen's constructor dereferences its client, so build it with the live
        // client and an already-shown parent. Reuse the inventory that is still current. Build
        // inside try because the construction runs as an argument — outside open()'s own try/catch
        // — so any failure would otherwise escape.
        try {
            net.minecraft.client.gui.screen.Screen parent = client.currentScreen;
            client.setScreen(new VideoOptionsScreen(parent, client, client.options));
        } catch (Throwable t) {
            skip("open video settings", t);
        }
        frames = 0; phase = P_WAIT_OPTIONS;
    }

    // ---- step 7: video settings -------------------------------------------

    private static void stepWaitOptions(MinecraftClient client) {
        if (client.currentScreen instanceof VideoOptionsScreen) {
            frames = 0; phase = P_OPTIONS;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait video settings screen", new IllegalStateException("video settings never opened"));
            frames = 0; phase = P_OPTIONS;
        }
    }

    private static void stepOptions(MinecraftClient client) {
        if (++frames < OPTIONS_FRAMES) return;
        capture(client, "options.png");
        close(client);
        // Step 8: leave the world and quit. scheduleStop() (in P_STOP) ends the run loop; MC's
        // normal shutdown then stops the integrated server and saves/unloads the world — a clean
        // leave+quit. We deliberately do NOT call client.disconnect() here: disconnect runs its own
        // nested world-unload/"saving" render loop, and invoking that from inside this render-TAIL
        // hook wedges the render thread (the run then only ends when the watchdog force-quits).
        phase = P_STOP;
    }

    // ---- BATCH A extra scenes (mode "batcha"): creative (B tabs + C scrollbar), advancements (A) ----

    private static void stepBatchaGameMode(MinecraftClient client) {
        if (frames == 0) {
            try {
                MinecraftServer server = client.getServer();
                if (server != null && !server.getPlayerManager().getPlayerList().isEmpty()) {
                    server.getPlayerManager().getPlayerList().get(0).changeGameMode(GameMode.CREATIVE);
                }
            } catch (Throwable t) { skip("set creative gamemode", t); }
        }
        if (++frames < 30) return;   // let the gamemode sync to the client so creative populates its tabs
        try {
            client.setScreen(new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(
                    client.player, client.world.getEnabledFeatures(),
                    client.options.getOperatorItemsTab().getValue()));
        } catch (Throwable t) { skip("open creative", t); }
        frames = 0; phase = P_BA_CREATIVE_WAIT;
    }

    private static void stepBatchaCreativeWait(MinecraftClient client) {
        if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen) {
            frames = 0; phase = P_BA_CREATIVE;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait creative", new IllegalStateException("creative never opened"));
            frames = 0; phase = P_BA_ADV_WAIT;
        }
    }

    private static void stepBatchaCreative(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        if (++frames < 60) return;
        capture(client, "creative.png");
        close(client);
        try {
            client.setScreen(new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                    client.getNetworkHandler().getAdvancementHandler()));
        } catch (Throwable t) { skip("open advancements", t); }
        frames = 0; phase = P_BA_ADV_WAIT;
    }

    private static void stepBatchaAdvWait(MinecraftClient client) {
        if (client.currentScreen instanceof net.minecraft.client.gui.screen.advancement.AdvancementsScreen) {
            frames = 0; phase = P_BA_ADV;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait advancements", new IllegalStateException("advancements never opened"));
            phase = P_STOP;
        }
    }

    private static void stepBatchaAdv(MinecraftClient client) {
        if (++frames < 60) return;
        capture(client, "advancements.png");
        close(client);
        phase = P_STOP;
    }

    // ---- name-tag sweep (S1MP1E_SHOT_MODE=nametags): the three Background looks (Glass / Vanilla / Off) ----

    /**
     * Spawns three named armor stands at different distances against high-contrast striped walls once, then captures one
     * frame per {@code NameTags} Background look, cycling Glass -> Vanilla -> Off via the live setting. Inert unless
     * {@code S1MP1E_SHOT_MODE=nametags}. Fully guarded.
     */
    private static void stepNameTags(MinecraftClient client) {
        if (client.player == null || client.inGameHud == null) { phase = P_STOP; return; }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}

        if (ntStage >= NT_MODES.length) { phase = P_STOP; return; }

        if (ntStage == 0 && frames == 0) { nameTagCamera(client); spawnNameTags(client); }  // aim + spawn once
        nameTagCamera(client);                                 // hold the aim every frame (vanilla may drift it)
        frames++;

        if (frames == 1) nameTagSetMode(NT_MODES[ntStage]);    // flip the live Background setting
        int warm = (ntStage == 0 ? 40 : 10);                   // first stage waits for the stands + walls to sync/render
        if (frames == warm + 6) {
            capture(client, "nametag-" + NT_MODES[ntStage].toLowerCase(java.util.Locale.ROOT) + ".png");
        } else if (frames >= warm + 6 + 3) {
            ntStage++; frames = 0;
        }
    }

    /** Point the camera at the terrain (yaw 0 = +Z, a gentle downward pitch) so the name tags sit against ground, not
     *  sky — the only way to see the Glass mode actually refract the world behind each tag. */
    private static void nameTagCamera(MinecraftClient client) {
        try {
            ClientPlayerEntity cp = client.player;
            if (cp != null) {
                cp.setYaw(0f);  cp.prevYaw = 0f;  cp.setHeadYaw(0f);
                cp.setPitch(14f); cp.prevPitch = 14f;
            }
        } catch (Throwable t) { skip("nametag camera", t); }
    }

    /** Spawn three named armor stands in front of the camera at different distances + lateral offsets, each against a
     *  5-wide/7-tall vertical obsidian/snow striped wall, so the Glass mode visibly refracts sharp vertical edges. */
    private static void spawnNameTags(MinecraftClient client) {
        try {
            MinecraftServer server = client.getServer();
            if (server == null) return;
            if (server.getPlayerManager().getPlayerList().isEmpty()) return;
            ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().get(0);
            ServerWorld level = sp.getServerWorld();
            double bx = sp.getX(), by = sp.getEyeY() - 1.85, bz = sp.getZ();   // feet height; name floats ~eye level
            int floorY = (int) Math.floor(by);
            spawnNameTag(level, bx - 1.3, by, bz + 3.2,  "S1mp1e", floorY);
            spawnNameTag(level, bx + 0.9, by, bz + 6.2,  "Near",   floorY);
            spawnNameTag(level, bx - 0.6, by, bz + 11.0, "Far",    floorY);
        } catch (Throwable t) {
            skip("hud name tag spawn", t);
        }
    }

    private static void spawnNameTag(ServerWorld level, double x, double y, double z, String name, int floorY) {
        try {
            ArmorStandEntity stand = new ArmorStandEntity(level, x, y, z);
            stand.setCustomName(Text.literal(name));
            stand.setCustomNameVisible(true);
            stand.setNoGravity(true);
            level.spawnEntity(stand);
            nameTagWall(level, (int) Math.floor(x), floorY, (int) Math.floor(z) + 2);
        } catch (Throwable t) { skip("name tag spawn " + name, t); }
    }

    /** A 5-wide, 7-tall wall of vertical obsidian / snow stripes behind a name tag: the plate is short but wide, so
     *  vertical stripes put several sharp edges behind it — the glass visibly bends those straight edges (proof of real
     *  refraction; a uniform sky would hide the distortion). */
    private static void nameTagWall(ServerWorld level, int cx, int floorY, int cz) {
        try {
            for (int dx = -2; dx <= 2; dx++) {
                var state = (dx % 2 == 0 ? Blocks.SNOW_BLOCK : Blocks.OBSIDIAN).getDefaultState();
                for (int dy = 0; dy <= 7; dy++) {
                    level.setBlockState(new BlockPos(cx + dx, floorY + dy, cz), state);
                }
            }
        } catch (Throwable t) { skip("name tag wall", t); }
    }

    private static void nameTagSetMode(String m) {
        try {
            dev.s1mp1e.client.Module mod = dev.s1mp1e.client.ModuleManager.byName("NameTags");
            if (mod != null) {
                dev.s1mp1e.client.Setting s = mod.setting("Background");
                if (s != null) s.setMode(m);
            }
        } catch (Throwable t) { skip("nametag set mode", t); }
    }

    private static void stepStop(MinecraftClient client) {
        System.out.println("[S1mp1e][DevShot] done, quitting.");
        try { client.scheduleStop(); } catch (Throwable ignored) {}
        phase = P_DONE;
    }

    // ---- world creation + setup -------------------------------------------

    /** Delete any previous {@code devshot} save and start a fresh flat world. Attempted once only. */
    private static boolean createWorld(MinecraftClient client) {
        if (worldTried) return false;   // never start world creation twice
        worldTried = true;
        try {
            File saves = new File(client.runDirectory, "saves");
            deleteRecursively(new File(saves, "devshot"));
        } catch (Throwable t) {
            skip("delete previous devshot save", t);
        }
        try {
            LevelInfo info = new LevelInfo("devshot", GameMode.SURVIVAL, false, Difficulty.PEACEFUL,
                    true /* cheats */, new GameRules(), DataConfiguration.SAFE_MODE);
            GeneratorOptions gen = new GeneratorOptions(12345L, false /* structures */, false /* bonus chest */);
            // 1.21.1: createAndStart takes a trailing Screen (shown on cancel / experimental-warning).
            // A SAFE_MODE flat world never triggers that path; a fresh TitleScreen is a safe fallback.
            client.createIntegratedServerLoader().createAndStart(
                    "devshot", info, gen,
                    drm -> drm.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                              .createDimensionsRegistryHolder(),
                    new TitleScreen());
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /** Time / weather / camera angle / scripted loadout. Each sub-part is independently guarded. */
    private static void applyWorldSetup(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        // time + weather (server-authoritative)
        try {
            ServerWorld ow = server.getOverworld();
            ow.setTimeOfDay(6000L);
            ow.setWeather(1_000_000, 0, false, false);
        } catch (Throwable t) {
            skip("set time/weather", t);
        }
        // loadout on the server player (auto-syncs to the client within a couple of ticks)
        try {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().isEmpty()
                    ? null : server.getPlayerManager().getPlayerList().get(0);
            if (sp != null) {
                sp.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
                sp.getInventory().setStack(1, new ItemStack(Items.COOKED_BEEF, 32));
                sp.getInventory().setStack(2, new ItemStack(Items.STONE, 64));
                sp.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                sp.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
                sp.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                sp.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
                sp.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
                // A spread of effects (icons on, no particles) so the PotionHUD shows stacked glass tiles,
                // varied silhouette colours, counting time labels and the infinite (∞) case.
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 3600, 0, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 600, 0, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, -1, 0, false, false, true));
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the flat plain).
                sp.refreshPositionAndAngles(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
                sp.networkHandler.requestTeleport(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
        // mirror the loadout + camera on the client player so the very next frame already shows it
        try {
            ClientPlayerEntity cp = client.player;
            cp.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
            cp.getInventory().setStack(1, new ItemStack(Items.COOKED_BEEF, 32));
            cp.getInventory().setStack(2, new ItemStack(Items.STONE, 64));
            cp.getInventory().selectedSlot = 0;               // hold the sword
            cp.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            cp.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
            cp.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            cp.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
            cp.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
            cp.setYaw(0f);  cp.prevYaw = 0f;  cp.headYaw = 0f;
            cp.setPitch(15f); cp.prevPitch = 15f;
        } catch (Throwable t) {
            skip("mirror loadout on client", t);
        }
    }

    // ---- mixin audit -------------------------------------------------------

    private static void runAudit() {
        try {
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            System.out.println("[S1mp1e] MIXIN AUDIT COMPLETE");
        } catch (Throwable t) {
            System.out.println("[S1mp1e] MIXIN AUDIT FAILED: " + t);
            t.printStackTrace();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static void open(MinecraftClient client, net.minecraft.client.gui.screen.Screen screen, String what) {
        try {
            client.setScreen(screen);
        } catch (Throwable t) {
            skip(what, t);
        }
    }

    private static void close(MinecraftClient client) {
        try {
            client.setScreen(null);
        } catch (Throwable ignored) {}
    }

    private static void skip(String step, Throwable t) {
        System.out.println("[S1mp1e] DevShot skipped " + step + ": " + t);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Capture the current framebuffer to {@code outDir/name}, overwriting. */
    static void capture(MinecraftClient client, String name) {
        NativeImage img = null;
        try {
            img = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            File out = new File(outDir, name);
            img.writeTo(out);
            System.out.println("[S1mp1e][DevShot] wrote " + out.getAbsolutePath()
                    + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] capture failed for " + name + ": " + t);
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
