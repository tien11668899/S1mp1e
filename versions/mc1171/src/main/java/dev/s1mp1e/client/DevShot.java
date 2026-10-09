package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.level.LevelInfo;

import java.io.File;

/**
 * DevShot v2 — deterministic screenshot harness for cross-version visual comparison (1.17.1 port).
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
 *   <li>{@code world.png}   — a fresh {@code devshot} world (seed 12345, survival, peaceful,
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
 * <p><b>1.17.1 API deltas vs the 1.20.1 reference harness</b> (all verified against the remapped
 * 1.17.1 jar): {@code options.guiScale} is a plain {@code int} field (not {@code getGuiScale()
 * .setValue()}); {@link net.minecraft.client.resource.language.LanguageManager} works in
 * {@link LanguageDefinition} (compared via {@code getCode()}); {@link net.minecraft.client.util.Window}
 * exposes public {@code setFramebufferWidth/Height} setters, so the forced-resolution path needs no
 * reflection; world creation predates the {@code IntegratedServerLoader} (1.19) and {@code WorldPresets}
 * (1.19) — it goes through {@code MinecraftClient.createWorld(name, info, DynamicRegistryManager.Impl,
 * GeneratorOptions)} with a seeded default (noise) overworld built from the built-in registry manager
 * (superflat needs a fragile {@code FlatChunkGenerator} on this version, and terrain is a never-judged
 * cross-version difference, so default terrain is used — the same choice the 1.16.5 / 1.18.2 lines make);
 * {@link LevelInfo} takes {@link DataPackSettings#SAFE_MODE} (not {@code DataConfiguration}); the
 * player inventory is reached via {@code getInventory()} (the field is private on 1.17.1).
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
    private static final int WAIT_WORLD_CAP  = 2400;  // default-terrain gen; generous
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
                             P_COMBAT = 9090, P_STOP = 14, P_DONE = 15,
                             // BATCH-A screens sweep (mode "screens"): open each glass screen, settle, capture.
                             P_SCREENS_OPEN = 16, P_SCREENS_WAIT = 17, P_SCREENS_SHOT = 18,
                             // Stage-2 VERIFY sweeps (modes "tabs" / "lists" / "flicker" / "all"): DevShotVerify.
                             P_VERIFY = 19,
                             // VERIFY sweeps of the 2026-10 port round (DevShotVerify, the neighbour lines' scene
                             // framework: settings / sodium / trans / gap / newmenu / newanim / vcombat, or any of its
                             // sweeps behind a "v:" prefix).
                             P_VERIFY2 = 120,
                             // INTRO mode (S1MP1E_SHOT_MODE=intro): boot brand-intro frames -> after-title -> world-entry
                             // loop preview -> real world entry loop -> after-world. Inert otherwise.
                             P_LOOPPREV = 130,
                             // ALL-GLASS round sweeps (ported from mc1180): DevAudit (every vanilla Screen, mode
                             // "audit"), DevAllGlass (state-dependent all-glass evidence, mode "allglass", after world),
                             // DevMenus (#26 world-less menus from the title, mode "menus", never creates a world).
                             P_AUDIT = 140, P_ALLGLASS = 150, P_MENUS = 160;

    // ---- intro mode state (S1MP1E_SHOT_MODE=intro) ----
    private static boolean introMode;     // boot brand-intro capture path active
    private static int     introCount;    // boot overlay frame index
    private static long    introLastMs;   // last boot-overlay capture time (ms spacing)
    private static boolean introSeen;     // the boot SplashOverlay was seen at least once
    private static boolean introWorld;    // after boot capture: continue title -> loop -> world -> after-world
    private static int     wlCount;       // world-entry loop (real LevelLoadingScreen) frame index
    private static long    wlLastMs;
    private static long    lpLastMs;      // loop-preview frame spacing
    private static int     lpCount;       // loop-preview frame index

    /** S1MP1E_SHOT_MODE: null/"base" = the six base shots; "screens" = the BATCH-A screen sweep after the world
     *  (scenes: effects-survival / tooltip / social / advancements / stats / book). Other values -> base pipeline. */
    private static String  mode;
    private static int     sweepIdx;      // index into the screens sweep (see openSweepScreen)
    private static int     sweepFrames;   // frames waited in the current sweep sub-step
    private static String  sweepName;     // filename of the current sweep shot; null ends the sweep
    private static boolean sweepTooltip;  // current sweep step warps a virtual cursor over an item (tooltip scene)

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions (createWorld, reloadResources) pump the render loop
     *  synchronously, which re-fires this render-TAIL hook. Without this, the current step would
     *  run again mid-action (e.g. re-creating the world every pumped frame → recursion →
     *  StackOverflow/OOM). Nested frames pumped inside a step do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** The resource reload kicked off by the language switch in {@link #stepInit}. It is
     *  asynchronous — MC schedules the reload and only installs its Mojang splash overlay a few
     *  frames later — so there is a window where the title is still {@code currentScreen} and
     *  {@code getOverlay()} is momentarily null. Waiting on this future (in addition to the overlay
     *  being gone) closes that race so {@code title.png} never bakes in the reload splash. */
    private static java.util.concurrent.CompletableFuture<Void> reloadFuture;
    /** Target reference resolution — forced onto the framebuffer so shots are 1280x720 even when
     *  the desktop is smaller than that and the OS clamps the on-screen window. */
    private static final int SHOT_W = 1280, SHOT_H = 720;
    /** The framebuffer size currently forced (dev sweeps shrink it for the small-window shots, then restore). */
    private static int curW = SHOT_W, curH = SHOT_H;

    /** Change the forced framebuffer size (dev sweeps only). */
    static void setTarget(int w, int h) { curW = w; curH = h; }

    /** The old sweeps of this line (DevShotLegacy) unless the mode list asks for the new framework. */
    private static boolean legacyVerify() { return !DevShotVerify.handles(mode) && DevShotLegacy.handles(mode); }

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
                mode = (m == null) ? null : m.trim().toLowerCase();
                introMode = "intro".equals(mode);
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
        long watchdog = (DevShotVerify.handles(mode) || "audit".equalsIgnoreCase(mode)
                         || "allglass".equalsIgnoreCase(mode) || "menus".equalsIgnoreCase(mode)) ? 1_500_000L
                : DevShotLegacy.handles(mode) ? 3L * WATCHDOG_MS : WATCHDOG_MS;   // the verify / all-glass sweeps run long
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
                case P_INIT:               stepInit(client);                       break;
                case P_WAIT_TITLE:         stepWaitTitle(client);                  break;
                case P_TITLE:              stepTitle(client);                      break;
                case P_WAIT_CONFIG:        stepWaitConfig(client, P_CONFIG);       break;
                case P_CONFIG:             stepConfig(client);                     break;
                case P_WAIT_WORLD:         stepWaitWorld(client);                  break;
                case P_WORLD_SETTLE:       stepWorldSettle(client);                break;
                case P_WORLD:              stepWorld(client);                      break;
                case P_WAIT_CONFIG2:       stepWaitConfig(client, P_CONFIG_WORLD); break;
                case P_CONFIG_WORLD:       stepConfigWorld(client);                break;
                case P_WAIT_INV:           stepWaitInventory(client);              break;
                case P_INV:                stepInventory(client);                  break;
                case P_WAIT_OPTIONS:       stepWaitOptions(client);                break;
                case P_OPTIONS:            stepOptions(client);                    break;
                case P_SCREENS_OPEN:       stepSweepOpen(client);                  break;
                case P_SCREENS_WAIT:       stepSweepWait(client);                  break;
                case P_SCREENS_SHOT:       stepSweepShot(client);                  break;
                case P_COMBAT:             if (CombatShot.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_AUDIT:              if (DevAudit.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_ALLGLASS:           if (DevAllGlass.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_MENUS:              if (DevMenus.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_VERIFY:             if (DevShotLegacy.step(client, mode)) phase = P_STOP; break;
                case P_VERIFY2:            if (DevShotVerify.step(client)) phase = P_STOP; break;
                case P_LOOPPREV:           stepLoopPreview(client);                break;
                case P_STOP:               stepStop(client);                       break;
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
     * <p>1.17.1 exposes public {@code setFramebufferWidth/Height} setters, so unlike the 1.20.1
     * reference this needs no reflection. No-op once the size already matches, so it is cheap to
     * call every frame and self-heals after any stray GLFW framebuffer-resize callback.
     */
    private static void forceFramebuffer(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            if (win.getFramebufferWidth() == curW && win.getFramebufferHeight() == curH) return;
            win.setFramebufferWidth(curW);
            win.setFramebufferHeight(curH);
            client.onResolutionChanged();   // resizes the framebuffer + GUI scale to the forced size
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + curW + "x" + curH);
        } catch (Throwable t) {
            skip("force framebuffer " + curW + "x" + curH, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(MinecraftClient client) {
        try {
            client.getWindow().setWindowedSize(1280, 720);
            // Harness only: swap without vsync. With the display asleep (unattended night runs) a vsynced swap is
            // throttled to ~4 frames a second, which turns every motion burst into a slide show and every frame-counted
            // wait into minutes. Only the window's swap interval is changed - the enableVsync OPTION is left alone, so
            // nothing is written to options.txt; the frame limiter (maxFps) still paces the loop.
            try { client.getWindow().setVsync(false); } catch (Throwable ignored) {}
            // No tutorial hints over the shots (a fresh dev world starts the movement tutorial and its toast sits on
            // top of every screen). Dev run directory only.
            try { client.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE); } catch (Throwable ignored) {}
            // The harness window runs unfocused; with the default pauseOnLostFocus a singleplayer world would sit
            // behind the pause menu. In-memory only for this run.
            try { client.options.pauseOnLostFocus = false; } catch (Throwable ignored) {}
            client.options.guiScale = 2;   // 1.17.1: plain int field, not getGuiScale().setValue()
            // Only pay the resource-reload cost when the language actually differs.
            LanguageDefinition cur = client.getLanguageManager().getLanguage();
            if (cur == null || !"zh_tw".equals(cur.getCode())) {
                // 1.17.1 delta vs the 1.20.1 reference (which sets the code string directly):
                // setLanguage() here needs a LanguageDefinition. getLanguage("zh_tw") resolves it
                // from the available-languages map, but that map is populated from each resource
                // pack's pack.mcmeta "language" section — which the dev default pack does NOT expose,
                // so the lookup returns null in dev and the language would silently stay en_us
                // (only S1mp1e's own hardcoded-Chinese UI would look right; vanilla text — title
                // buttons, held-item names — would render in English, breaking comparability with
                // the reference). Construct the definition directly when the lookup fails: the
                // reload's TranslationStorage keys off the current code and still loads
                // lang/zh_tw.json from every namespace (the asset exists in the store regardless).
                LanguageDefinition def = client.getLanguageManager().getLanguage("zh_tw");
                if (def == null) {
                    def = new LanguageDefinition("zh_tw", "China", "Chinese (Taiwan)", false);
                    System.out.println("[S1mp1e][DevShot] zh_tw not listed by dev pack; using a "
                            + "direct LanguageDefinition so vanilla text is also translated.");
                }
                client.getLanguageManager().setLanguage(def);
                client.options.language = "zh_tw";
                reloadFuture = client.reloadResources();
                System.out.println("[S1mp1e][DevShot] language set to zh_tw (was "
                        + (cur == null ? "null" : cur.getCode()) + "), resources reloaded.");
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
        // The language reload is async and its splash appears a few frames after the call, so also
        // require that reload's future to have completed — otherwise we could advance in the gap
        // before the splash shows and capture it mid-reload.
        boolean reloadDone = reloadFuture == null || reloadFuture.isDone();
        if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null && reloadDone) {
            frames = 0; phase = P_TITLE;
        } else if (++frames > WAIT_SCREEN_CAP * 4) {
            skip("wait title screen", new IllegalStateException("title never shown"));
            frames = 0; phase = P_TITLE;   // try to shoot whatever is on screen, then continue
        }
    }

    private static void stepTitle(MinecraftClient client) {
        if (++frames >= TITLE_FRAMES) {
            if ("menus".equalsIgnoreCase(mode)) {   // #26 world-less menus only (never create a world / run the intro loop)
                capture(client, "title.png");
                frames = 0; phase = P_MENUS;
                return;
            }
            if (introWorld) {
                // Glass buttons must still render after the boot intro ran during the first resource reload.
                capture(client, "after-title.png");
                // A dev world can load too fast to see a whole loop cycle: preview the world-entry loop on its own
                // screen first, then do the real world entry (its LevelLoadingScreen carries the same loop).
                open(client, new LoopPreview(), "open world-entry loop preview");
                frames = 0; lpLastMs = 0L; lpCount = 0; phase = P_LOOPPREV;
                return;
            }
            capture(client, "title.png");
            open(client, new S1mp1eConfigScreen(), "open settings (over title)");
            frames = 0; phase = P_WAIT_CONFIG;
        }
    }

    /** Dev-only screen that plays the world-entry loop ({@code BrandIntro.MODE_LOOP}) on pure black, exactly as
     *  {@code LevelLoadingScreen} does under the glass mixin, for as long as it is open. */
    private static final class LoopPreview extends net.minecraft.client.gui.screen.Screen {
        private final long t0 = System.nanoTime();
        LoopPreview() { super(new net.minecraft.text.LiteralText("loop preview")); }
        @Override public void render(net.minecraft.client.util.math.MatrixStack matrices, int mx, int my, float d) {
            net.minecraft.client.gui.DrawableHelper.fill(matrices, 0, 0, this.width, this.height, 0xFF000000);
            DevShotVerify.loopPreview(matrices, (System.nanoTime() - t0) / 1.0E9F);
        }
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
        if (startMs == 0L) startMs = System.currentTimeMillis();
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
            try { client.getWindow().setVsync(false); } catch (Throwable ignored) {}
            try { client.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE); } catch (Throwable ignored) {}
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
            // sodium mode: the Sodium settings screen over the title background too (no world to refract)
            if (mode != null && mode.contains("sodium")) {
                close(client);
                DevShotVerify.openSodiumOptions(client);
                return;
            }
            if (mode != null && mode.contains("settings")) {     // the settings shell over the title background
                close(client);
                client.setScreen(new net.minecraft.client.gui.screen.option.OptionsScreen(
                        new TitleScreen(), client.options));
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
        if (introWorld && client.currentScreen instanceof net.minecraft.client.gui.screen.LevelLoadingScreen) {
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
        frames = 0;
        if ("audit".equalsIgnoreCase(mode)) { frames = 0; phase = P_AUDIT; return; }       // #all-glass: full-screen audit
        if ("allglass".equalsIgnoreCase(mode)) { frames = 0; phase = P_ALLGLASS; return; } // #all-glass: state shots
        // In the BATCH-A "screens" mode, branch straight into the glass-screen sweep after the world is ready
        // (the base title/config shots already ran; the world-dependent base shots are skipped). Any other
        // mode / unset runs the original six-shot base pipeline.
        if ("combat".equalsIgnoreCase(mode)) { frames = 0; phase = P_COMBAT; return; }   // 26.2 combat trio
        if (isScreensMode())                   { sweepIdx = 0; phase = P_SCREENS_OPEN; }
        else if (legacyVerify())               { phase = P_VERIFY; }   // stage-2 B/C/D + R4 sweeps (DevShotLegacy)
        else                                   { phase = P_WORLD; }
    }

    /** True when S1MP1E_SHOT_MODE selects this stage's BATCH-A screen sweep. */
    private static boolean isScreensMode() {
        return "screens".equals(mode) || "effects".equals(mode)
                || "tooltips".equals(mode) || "social".equals(mode);
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
            if (DevShotVerify.handles(mode)) {  // the 1.21.1 feature-set sweeps
                DevShotVerify.init(outDir, mode);
                frames = 0; phase = P_VERIFY2;
                return;
            }
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
        // VideoOptionsScreen's constructor dereferences parent.client, so the parent must be an
        // already-shown screen (a freshly-constructed one, or null, has no client yet). Reuse the
        // inventory that is still current. Build inside try because the construction runs as an
        // argument — outside open()'s own try/catch — so a null-parent NPE would otherwise escape.
        try {
            net.minecraft.client.gui.screen.Screen parent = client.currentScreen;
            client.setScreen(new VideoOptionsScreen(parent, client.options));
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

    // ---- BATCH-A screens sweep (mode "screens") ---------------------------
    //
    // Opens each stage-1 glass screen in turn, waits for it to settle, captures it, moves on; the tooltip step warps
    // the GLFW cursor over an inventory item so the top-layer glass tooltip (rule E / R1) is captured refracting the
    // items + stack-count digits beneath it. Guarded per step so a failure only skips that shot. Ends at P_STOP.

    private static final int SWEEP_SETTLE = 45;   // frames a screen renders before capture

    private static void stepSweepOpen(MinecraftClient client) {
        sweepTooltip = false;
        boolean opened = openSweepScreen(client, sweepIdx);
        if (!opened && sweepName == null) {   // end of list
            phase = P_STOP;
            return;
        }
        sweepFrames = 0;
        phase = P_SCREENS_WAIT;
    }

    private static void stepSweepWait(MinecraftClient client) {
        // Give the screen a couple frames to become current, then settle.
        if (++sweepFrames > 6) { sweepFrames = 0; phase = P_SCREENS_SHOT; }
    }

    private static void stepSweepShot(MinecraftClient client) {
        // For the tooltip scene, pin the virtual cursor over an inventory item from a few frames before the capture
        // so the hovered-item glass tooltip is on screen AND its morph springs have settled when we shoot. Re-pinned
        // every frame because MC's per-frame updateMouse() runs between our sets.
        if (sweepTooltip && sweepFrames >= 8) hoverInventoryItem(client);
        if (++sweepFrames < SWEEP_SETTLE) return;
        capture(client, sweepName);
        try { client.setScreen(null); } catch (Throwable ignored) {}
        sweepIdx++;
        phase = P_SCREENS_OPEN;
    }

    /** Open the sweep screen for {@code idx}; sets {@link #sweepName}. Returns false (name null) at the end. */
    private static boolean openSweepScreen(MinecraftClient client, int idx) {
        try {
            switch (idx) {
                case 0:   // Feature F — the ONE continuous glass effect strip beside the survival inventory.
                    sweepName = "effects-survival.png";
                    client.setScreen(new InventoryScreen(client.player));
                    return true;
                case 1:   // Feature E / R1 — hovered-item glass tooltip on the top layer, refracting items + counts.
                    sweepName = "tooltip.png";
                    sweepTooltip = true;
                    client.setScreen(new InventoryScreen(client.player));
                    return true;
                case 2:   // Feature A — Social Interactions panel as one glass plate + grey scrim.
                    sweepName = "social.png";
                    client.setScreen(new net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen());
                    return true;
                case 3:   // Feature A — advancements window framed in glass (wooden WINDOW_TEXTURE frame dropped).
                    sweepName = "advancements.png";
                    client.setScreen(new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                            client.player.networkHandler.getAdvancementHandler()));
                    return true;
                case 4:   // Feature A — Statistics screen as a full glass plate + grey scrim (tiled dirt dropped).
                    sweepName = "stats.png";
                    client.setScreen(new net.minecraft.client.gui.screen.StatsScreen(
                            null, client.player.getStatHandler()));
                    return true;
                case 5:   // Feature A — written-book page as a glass plate + LIGHT warm parchment scrim.
                    sweepName = "book.png";
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.BookScreen());
                    return true;
                default:
                    sweepName = null;
                    return false;
            }
        } catch (Throwable t) {
            skip("sweep open idx " + idx, t);
            sweepName = null;
            sweepIdx = idx + 1;         // do not end the sweep on one bad screen: advance past it
            return idx + 1 <= 5;
        }
    }

    /** Pin the virtual cursor over the survival-inventory hotbar slot 0 (the diamond sword) so its glass tooltip
     *  shows, extending right across slots 1/2 (cooked beef x32, stone x64) so the card refracts their stack-count
     *  digits — the R1 "card over items + counts" check.
     *
     *  <p>{@code glfwSetCursorPos} fails silently on an unfocused window (GLFW requires input focus), and the
     *  DevShot window is a background gradle-launched window, so instead we reflection-set {@code Mouse.x/y}
     *  directly. {@code MinecraftClient.render} passes the screen {@code mouseX = mouse.getX() * scaledW / width}
     *  ({@code getX()} reads that field), so setting the field pins the hovered slot with no OS input at all. */
    private static void hoverInventoryItem(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            int sw = win.getScaledWidth(), sh = win.getScaledHeight();
            // Survival inventory panel geometry (176x166, centred). Hotbar row slot 0 centre.
            int left = (sw - 176) / 2, top = (sh - 166) / 2;
            double scx = left + 8 + 4;      // slot 0 x + half
            double scy = top + 142 + 4;     // hotbar row y + half
            double sxr = (double) win.getWidth() / sw, syr = (double) win.getHeight() / sh;
            net.minecraft.client.Mouse mouse = client.mouse;
            setMouseField(mouse, "x", scx * sxr);
            setMouseField(mouse, "y", scy * syr);
        } catch (Throwable t) {
            skip("pin cursor for tooltip", t);
        }
    }

    /** Reflection-set one private {@code double} field of {@link net.minecraft.client.Mouse} (dev = yarn names). */
    private static void setMouseField(net.minecraft.client.Mouse mouse, String name, double value) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.Mouse.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setDouble(mouse, value);
        } catch (Throwable t) {
            skip("set Mouse." + name, t);
        }
    }

    private static void stepStop(MinecraftClient client) {
        System.out.println("[S1mp1e][DevShot] done, quitting.");
        try { client.scheduleStop(); } catch (Throwable ignored) {}
        phase = P_DONE;
    }

    // ---- world creation + setup -------------------------------------------

    /** Delete any previous {@code devshot} save and start a fresh world. Attempted once only. */
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
            // survival, not hardcore, PEACEFUL, cheats on; safe-mode datapacks (vanilla only).
            LevelInfo info = new LevelInfo("devshot", GameMode.SURVIVAL, false, Difficulty.PEACEFUL,
                    true /* cheats */, new GameRules(), DataPackSettings.SAFE_MODE);
            DynamicRegistryManager.Impl reg = DynamicRegistryManager.create();
            GeneratorOptions gen = buildGeneratorOptions(reg, 12345L);
            // 1.17.1: the older MinecraftClient.createWorld path (no IntegratedServerLoader yet).
            client.createWorld("devshot", info, reg, gen);
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /**
     * Seeded default (noise) overworld for seed {@code seed}, built exactly the way vanilla's
     * default "create world" does on 1.17.1: {@link DimensionType#createDefaultDimensionOptions}
     * yields the base dimension registry from the built-in manager, the overworld generator is
     * replaced with the seeded one, and it is wrapped in a {@link GeneratorOptions}. Falls back to
     * MC's random-seed default if the seeded assembly throws. Superflat needs a fragile
     * {@code FlatChunkGenerator} build on 1.17.1 and terrain is a never-judged cross-version
     * difference, so default terrain is used here (same choice the 1.16.5 / 1.18.2 lines make).
     */
    private static GeneratorOptions buildGeneratorOptions(DynamicRegistryManager.Impl reg, long seed) {
        Registry<DimensionType> dimTypes = reg.get(Registry.DIMENSION_TYPE_KEY);
        Registry<Biome> biomes = reg.get(Registry.BIOME_KEY);
        Registry<ChunkGeneratorSettings> chunkGenSettings = reg.get(Registry.CHUNK_GENERATOR_SETTINGS_KEY);
        if (introWorld || DevShotVerify.handles(mode)) {
            // 2026-10 port round: the new sweeps run on a SUPERFLAT overworld (what the create-world screen's
            // "Superflat" type builds on 1.17.1, javap-read): a bright, level, deterministic backdrop for the glass,
            // like the 1.18.2+ harness. The line's old modes keep the seeded default world below.
            try {
                net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig cfg =
                        net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig.getDefaultConfig(biomes);
                ChunkGenerator flat = new net.minecraft.world.gen.chunk.FlatChunkGenerator(cfg);
                SimpleRegistry<DimensionOptions> dims = GeneratorOptions.getRegistryWithReplacedOverworldGenerator(dimTypes,
                        DimensionType.createDefaultDimensionOptions(dimTypes, biomes, chunkGenSettings, seed), flat);
                return new GeneratorOptions(seed, false /* structures */, false /* bonus chest */, dims);
            } catch (Throwable t) {
                skip("superflat worldgen (trying the seeded default)", t);
            }
        }
        try {
            SimpleRegistry<DimensionOptions> base =
                    DimensionType.createDefaultDimensionOptions(dimTypes, biomes, chunkGenSettings, seed);
            ChunkGenerator overworld =
                    GeneratorOptions.createOverworldGenerator(biomes, chunkGenSettings, seed);
            SimpleRegistry<DimensionOptions> dims =
                    GeneratorOptions.getRegistryWithReplacedOverworldGenerator(dimTypes, base, overworld);
            return new GeneratorOptions(seed, true /* structures */, false /* bonus chest */, dims);
        } catch (Throwable t) {
            skip("seeded worldgen (using random-seed default)", t);
            return GeneratorOptions.getDefaultOptions(dimTypes, biomes, chunkGenSettings);
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
                // 60 s Speed I, icon on but no particles (keeps the reference frame clean).
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
                // Screens sweep (F): several effects (beneficial / harmful / ambient) so the ONE glass effect
                // strip shows its multiple entries and separators. No particles -> clean, deterministic frame.
                if (isScreensMode()) {
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 1800, 1, false, false, true));
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 0, false, false, true));
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 1200, 0, true, false, true));
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 600, 0, false, false, true));
                }
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the plain).
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

    /** {@link #capture} for {@link DevShotLegacy} (same folder, same writer). */
    static void captureShot(MinecraftClient client, String name) { capture(client, name); }

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
