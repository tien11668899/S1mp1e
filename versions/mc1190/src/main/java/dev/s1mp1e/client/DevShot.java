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
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPreset;
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
 * <p><b>1.19.2 API notes (vs the 1.20.1 reference):</b> the language manager works in
 * {@link LanguageDefinition} (looked up by code) rather than raw {@code String}; world creation goes
 * through {@code createIntegratedServerLoader().createAndStart(name, info, drm, gen)} where 1.19.2
 * takes a concrete {@link DynamicRegistryManager} + {@link GeneratorOptions} (1.20.1 takes a
 * dimensions-holder lambda) — the built-in registry manager and the FLAT {@link WorldPreset} supply
 * both; and {@link LevelInfo} takes {@link DataPackSettings#SAFE_MODE} (not {@code DataConfiguration}).
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
                             P_COMBAT = 9090, P_STOP = 14, P_DONE = 15,
                             // BATCH-A screens sweep (mode "screens"): open each glass screen, settle, capture.
                             P_SCREENS_OPEN = 16, P_SCREENS_WAIT = 17, P_SCREENS_SHOT = 18,
                             // BATCH-A tab/scroll sweeps (modes "tabs"/"scroll"): a single frame-scripted sequence.
                             P_TABS_SEQ = 19, P_SCROLL_SEQ = 20,
                             // BATCH-B HUD/module sweeps (modes "hud"/"modules"): frame-scripted sequences.
                             P_HUD_SEQ = 21, P_MODULES_SEQ = 22,
                             // VERIFY sweeps ported from the 1.20.1 / 1.21.1 lines (DevShotVerify: settings / sodium /
                             // trans / gap / newmenu / newanim / lists / tooltips / effects / flicker / vcombat, or
                             // any of its sweeps behind a "v:" prefix).
                             P_VERIFY = 120,
                             // INTRO mode (S1MP1E_SHOT_MODE=intro): boot brand-intro frames -> after-title (glass intact
                             // after the intro ran during the first reload) -> world-entry loop preview -> real world
                             // entry loop -> after-world. Inert otherwise.
                             P_LOOPPREV = 130,
                             // AUDIT mode: open every vanilla Screen in turn and shoot it (DevAudit); after world load.
                             P_AUDIT = 140,
                             // ALLGLASS mode: state-dependent all-glass evidence shots (DevAllGlass); after world load.
                             P_ALLGLASS = 150,
                             // MENUS mode (#26): world-less menus from the title (DevMenus). Never creates a world.
                             P_MENUS = 160;
    // NAMETAGS / WINGS / MINIMAP modes (S1MP1E_SHOT_MODE=nametags | wings | minimap), ported from 1.21.1: the NameTags
    // Background looks, the DragonWings cosmetic (+ Freelook and a fine crosshair here), and the glass minimap +
    // full-screen map (+ the ToggleSprint pill). The minimap mode uses a normal-terrain world. PORT1201 mode
    // (S1MP1E_SHOT_MODE=port1201) runs all three in one launch on that world: name tags -> wings -> minimap; ALL1201
    // (S1MP1E_SHOT_MODE=all1201) runs the font probe and the Essential sweep at the title screen first.
    private static final int P_NAMETAGS = 170, P_WINGS = 180, P_MINIMAP = 190;
    // ESSENTIAL mode (S1MP1E_SHOT_MODE=essential), ported from 1.21.1: Essential's own screens under the glass restyle.
    private static final int P_ESSENTIAL = 200;
    // FONT mode (S1MP1E_SHOT_MODE=font, also first in all1201): TTF probe strings drawn large over their advance cells.
    private static final int P_FONT = 210;
    private static boolean allMode, chainMode, nameTagsMode, wingsMode, minimapMode;   // all1201 = font, essential, then port1201
    private static int ntStage, wgStage, mmStage;
    private static long wgT0, mmT0;
    private static final String[] NT_MODES = { "Glass", "Vanilla", "Off" };
    private static net.minecraft.client.network.OtherClientPlayerEntity[] mmFakes;

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

    /** S1MP1E_SHOT_MODE: null/"base" = the six base shots; "screens" = the BATCH-A screen sweep after the world. */
    private static String mode;
    /** Index into the screens sweep (see {@link #openSweepScreen}). */
    private static int sweepIdx;
    /** Frames waited in the current sweep sub-step. */
    private static int sweepFrames;

    private static boolean resolved;      // env vars checked exactly once
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
    /** The framebuffer size currently forced (dev sweeps shrink it for the small-window shots, then restore). */
    private static int curW = SHOT_W, curH = SHOT_H;

    /** Change the forced framebuffer size (dev sweeps only). */
    static void setTarget(int w, int h) { curW = w; curH = h; }

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
                allMode = "all1201".equals(mode);
                chainMode = allMode || "port1201".equals(mode);
                nameTagsMode = chainMode || "nametags".equals(mode);
                wingsMode = chainMode || "wings".equals(mode);
                minimapMode = chainMode || "minimap".equals(mode);
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
        long watchdog = (DevShotVerify.handles(mode) || "audit".equalsIgnoreCase(mode) || "allglass".equalsIgnoreCase(mode)
                         || "menus".equalsIgnoreCase(mode))
                ? 1_500_000L : minimapMode ? 420_000L : WATCHDOG_MS;
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
                case P_SCREENS_OPEN:       stepSweepOpen(client);                break;
                case P_SCREENS_WAIT:       stepSweepWait(client);                break;
                case P_SCREENS_SHOT:       stepSweepShot(client);                break;
                case P_COMBAT:             if (CombatShot.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_TABS_SEQ:           stepTabsSeq(client);                  break;
                case P_SCROLL_SEQ:         stepScrollSeq(client);                break;
                case P_HUD_SEQ:            stepHudSeq(client);                   break;
                case P_MODULES_SEQ:        stepModulesSeq(client);               break;
                case P_VERIFY:             if (DevShotVerify.step(client)) phase = P_STOP; break;
                case P_LOOPPREV:           stepLoopPreview(client);              break;
                case P_AUDIT:              if (DevAudit.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_ALLGLASS:           if (DevAllGlass.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_MENUS:              if (DevMenus.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_STOP:               stepStop(client);                     break;
                case P_ESSENTIAL:          stepEssential(client);                break;
                case P_FONT:               stepFontProbe(client);                break;
                case P_NAMETAGS:           stepNameTags(client);                 break;
                case P_WINGS:              stepWings(client);                    break;
                case P_MINIMAP:            stepMinimap(client);                  break;
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
            if (win.getFramebufferWidth() == curW && win.getFramebufferHeight() == curH) return;
            java.lang.reflect.Field fw = net.minecraft.client.util.Window.class.getDeclaredField("framebufferWidth");
            java.lang.reflect.Field fh = net.minecraft.client.util.Window.class.getDeclaredField("framebufferHeight");
            fw.setAccessible(true); fh.setAccessible(true);
            fw.setInt(win, curW); fh.setInt(win, curH);
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
            client.options.getGuiScale().setValue(2);
            // Other windows may steal focus; never let the world auto-pause behind a shot.
            client.options.pauseOnLostFocus = false;
            // Only pay the resource-reload cost when the language actually differs.
            // 1.19.2: the language manager works in LanguageDefinition (looked up by code),
            // not raw String codes as in 1.20.1.
            if (!"zh_tw".equals(client.getLanguageManager().getLanguage().getCode())) {
                LanguageDefinition def = client.getLanguageManager().getLanguage("zh_tw");
                if (def != null) {
                    client.getLanguageManager().setLanguage(def);
                    client.options.language = "zh_tw";
                    client.reloadResources();
                }
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
                // A dev flat world loads in ~2 s, too short to see a whole loop cycle: preview the world-entry loop on
                // its own screen first, then do the real world entry (its LevelLoadingScreen carries the same loop).
                open(client, new LoopPreview(), "open world-entry loop preview");
                frames = 0; lpLastMs = 0L; lpCount = 0; phase = P_LOOPPREV;
                return;
            }
            capture(client, "title.png");
            if ("font".equalsIgnoreCase(mode) || allMode) { open(client, new FontProbe(), "open font probe"); frames = 0; phase = P_FONT; return; }
            if ("essential".equalsIgnoreCase(mode)) { frames = 0; phase = P_ESSENTIAL; return; }   // menus only, no world
            open(client, new S1mp1eConfigScreen(), "open settings (over title)");
            frames = 0; phase = P_WAIT_CONFIG;
        }
    }

    /** Dev-only screen that plays the world-entry loop ({@code BrandIntro.MODE_LOOP}) on pure black, exactly as
     *  {@code LevelLoadingScreen} does under the glass mixin, for as long as it is open. */
    private static final class LoopPreview extends net.minecraft.client.gui.screen.Screen {
        private final long t0 = System.nanoTime();
        LoopPreview() { super(net.minecraft.text.Text.literal("loop preview")); }
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
        if (nameTagsMode) { ntStage = 0; frames = 0; phase = P_NAMETAGS; return; }
        if (wingsMode) { frames = 0; phase = P_WINGS; return; }
        if (minimapMode) { frames = 0; phase = P_MINIMAP; return; }
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
            if ("screens".equals(mode)) {
                // BATCH-A sweep: open each glass screen in turn and capture it, then stop.
                sweepIdx = 0; sweepFrames = 0; phase = P_SCREENS_OPEN;
                return;
            }
            if ("combat".equalsIgnoreCase(mode)) { frames = 0; phase = P_COMBAT; return; }   // 26.2 combat trio
            if ("tabs".equals(mode)) {          // B — fused creative tabs, motion bursts.
                sweepFrames = 0; phase = P_TABS_SEQ;
                return;
            }
            if ("scroll".equals(mode)) {        // C/D — container scrollbars + stonecutter glide.
                sweepFrames = 0; phase = P_SCROLL_SEQ;
                return;
            }
            if ("hud".equals(mode)) {           // G — chat/input/actionbar/toast/bossbar/nametag.
                sweepFrames = 0; phase = P_HUD_SEQ;
                return;
            }
            if ("modules".equals(mode)) {       // H — block outline + chroma HUD + config pages.
                sweepFrames = 0; phase = P_MODULES_SEQ;
                return;
            }
            if ("audit".equalsIgnoreCase(mode)) { frames = 0; phase = P_AUDIT; return; }           // full-screen audit
            if ("allglass".equalsIgnoreCase(mode)) { frames = 0; phase = P_ALLGLASS; return; }     // state shots
            if (DevShotVerify.handles(mode)) {  // the 1.21.1 feature-set sweeps
                DevShotVerify.init(outDir, mode);
                frames = 0; phase = P_VERIFY;
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
    // Opens each glass screen in turn, waits for it to settle, captures it, moves on; a tooltip step warps the
    // GLFW cursor over an inventory item so the top-layer glass tooltip (rule E/R1) is captured. Guarded per step
    // so a failure only skips that shot. Ends at P_STOP (leave world + quit).

    private static final int SWEEP_SETTLE = 45;   // frames a screen renders before capture
    /** Names each sweep shot; a null screen from {@link #openSweepScreen} ends the sweep. */
    private static String sweepName;
    private static boolean sweepTooltip;

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
        // For the tooltip scene, warp the cursor over an inventory item a few frames before the capture so the
        // hovered-item glass tooltip is on screen when we shoot.
        if (sweepTooltip && sweepFrames == 10) hoverInventoryItem(client);
        if (++sweepFrames < SWEEP_SETTLE) return;
        capture(client, sweepName);
        try { client.setScreen(null); } catch (Throwable ignored) {}
        sweepIdx++;
        phase = P_SCREENS_OPEN;
    }

    /** Open the sweep screen for {@code idx}; sets {@link #sweepName}. Returns false (with name null) at the end. */
    private static boolean openSweepScreen(MinecraftClient client, int idx) {
        try {
            switch (idx) {
                case 0:
                    sweepName = "creative.png";
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(client.player));
                    return true;
                case 1:
                    sweepName = "stats.png";
                    client.setScreen(new net.minecraft.client.gui.screen.StatsScreen(
                            new net.minecraft.client.gui.screen.TitleScreen(), client.player.getStatHandler()));
                    return true;
                case 2:
                    sweepName = "advancements.png";
                    client.setScreen(new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                            client.getNetworkHandler().getAdvancementHandler()));
                    return true;
                case 3:
                    sweepName = "book.png";
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.BookScreen(makeBookContents()));
                    return true;
                case 4:
                    sweepName = "effects-survival.png";
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player));
                    return true;
                case 5:
                    sweepName = "tooltip.png";
                    sweepTooltip = true;
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player));
                    return true;
                case 6:
                    sweepName = "social.png";
                    client.setScreen(new net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen());
                    return true;
                default:
                    sweepName = null;
                    return false;
            }
        } catch (Throwable t) {
            skip("sweep open idx " + idx, t);
            sweepName = null;
            // Do not end the sweep on one bad screen: advance past it.
            sweepIdx = idx + 1;
            return idx + 1 <= 6;
        }
    }

    /** A two-page written book for the book-glass shot. */
    private static net.minecraft.client.gui.screen.ingame.BookScreen.Contents makeBookContents() {
        try {
            net.minecraft.item.ItemStack book = new net.minecraft.item.ItemStack(net.minecraft.item.Items.WRITTEN_BOOK);
            net.minecraft.nbt.NbtCompound nbt = book.getOrCreateNbt();
            nbt.putString("title", "S1mp1e");
            nbt.putString("author", "DevShot");
            net.minecraft.nbt.NbtList pages = new net.minecraft.nbt.NbtList();
            pages.add(net.minecraft.nbt.NbtString.of(
                    "{\"text\":\"Liquid glass book page.\\n\\nDark ink stays readable over the light parchment scrim.\"}"));
            nbt.put("pages", pages);
            return net.minecraft.client.gui.screen.ingame.BookScreen.Contents.create(book);
        } catch (Throwable t) {
            return net.minecraft.client.gui.screen.ingame.BookScreen.EMPTY_PROVIDER;
        }
    }

    /** Warp the GLFW cursor over the survival-inventory hotbar slot 0 (the diamond sword) so its glass tooltip shows. */
    private static void hoverInventoryItem(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            int sw = win.getScaledWidth(), sh = win.getScaledHeight();
            // Survival inventory panel geometry (176x166, centred). Hotbar row slot 0 centre.
            int left = (sw - 176) / 2, top = (sh - 166) / 2;
            double scx = left + 8 + 4;      // slot 0 x + half
            double scy = top + 142 + 4;     // hotbar row y + half
            double sxr = (double) win.getWidth() / sw, syr = (double) win.getHeight() / sh;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(win.getHandle(), scx * sxr, scy * syr);
        } catch (Throwable t) {
            skip("warp cursor for tooltip", t);
        }
    }

    // ---- B: fused creative tabs, motion bursts (mode "tabs") --------------
    //
    // Opens the creative screen once, then over a single frame-scripted sequence: captures the fused sheet at rest,
    // slides the selection pill WITHIN the top row (consecutive-frame burst), CROSS-FADES it to the bottom row
    // (burst), and warps a virtual cursor over a non-selected cell for the hover-pill glide (burst). The pill motion
    // is real wall-clock spring integration, so the burst frames catch the intermediate positions.

    private static void stepTabsSeq(MinecraftClient client) {
        int f = sweepFrames++;
        try {
            switch (f) {
                case 1:
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(client.player));
                    s1mp1e$selectTab(true, 0);   // top row, column 0
                    break;
                case 40: capture(client, "tabs-rest.png");        break;   // fused sheet, pill at top col0
                case 45: s1mp1e$selectTab(true, 4);               break;   // slide within the top row
                case 47: capture(client, "tabs-slide-1.png");     break;
                case 50: capture(client, "tabs-slide-2.png");     break;
                case 54: capture(client, "tabs-slide-3.png");     break;
                case 74: capture(client, "tabs-slide-settled.png"); break;
                case 79: s1mp1e$selectTab(false, 2);              break;   // cross-fade to the bottom row
                case 81: capture(client, "tabs-cross-1.png");     break;
                case 84: capture(client, "tabs-cross-2.png");     break;
                case 88: capture(client, "tabs-cross-3.png");     break;
                case 108: capture(client, "tabs-cross-settled.png"); break;
                case 110: s1mp1e$warpTabCursor(client, true, 1);  break;   // hover a top-row cell
                case 114: capture(client, "tabs-hover-1.png");    break;
                case 119: capture(client, "tabs-hover-2.png");    break;
                case 125: capture(client, "tabs-hover-3.png");    break;
                case 128: s1mp1e$tabClickTest(client);            break;   // #5 automated tab-click self-test
                case 130: s1mp1e$selectTab(true, 0); s1mp1e$warpSlotCursor(client); break;  // #4 hover an item slot
                case 138: capture(client, "creative-hover.png");  break;   // #4 slot lattice + glass hover pill
                case 142:
                    try { client.setScreen(null); } catch (Throwable ignored) {}
                    phase = P_STOP;
                    break;
                default: break;
            }
        } catch (Throwable t) {
            skip("tabs seq frame " + f, t);
            if (f > 140) phase = P_STOP;
        }
    }

    /**
     * #5 self-test — click the CENTRE of every visible tab's drawn fused cell (absolute coords, as a real click) and
     * assert {@code getSelectedTab()} became that tab. Vanilla mouseClicked subtracts this.x/this.y before isClickInTab,
     * so a PASS proves GlassTabs.hit (relative space) matches the drawn cell. Logs PASS/FAIL per tab + a TOTAL.
     */
    private static void s1mp1e$tabClickTest(MinecraftClient client) {
        try {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen s =
                    (net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen) client.currentScreen;
            dev.s1mp1e.glass.mixin.HandledScreenAccessor a = (dev.s1mp1e.glass.mixin.HandledScreenAccessor) s;
            float cw = a.s1mp1e$backgroundWidth() / (float) dev.s1mp1e.glass.render.GlassTabs.COLUMNS;
            int pass = 0, fail = 0;
            for (net.minecraft.item.ItemGroup g : net.minecraft.item.ItemGroup.GROUPS) {
                if (g == null) continue;
                int col = g.getColumn(); boolean top = g.isTopRow();
                double x = a.s1mp1e$x() + (col + 0.5) * cw;
                double y = top ? a.s1mp1e$y() - dev.s1mp1e.glass.render.GlassTabs.BAND / 2.0
                               : a.s1mp1e$y() + a.s1mp1e$backgroundHeight() + dev.s1mp1e.glass.render.GlassTabs.BAND / 2.0;
                try { s.mouseClicked(x, y, 0); s.mouseReleased(x, y, 0); } catch (Throwable ignored) {}
                int sel = s.getSelectedTab();
                boolean ok = sel == g.getIndex();
                if (ok) pass++; else fail++;
                System.out.println("[S1mp1e][DevShot][CLICKS] tab idx=" + g.getIndex() + " col=" + col
                        + " top=" + top + " selected=" + sel + " -> " + (ok ? "PASS" : "FAIL"));
            }
            System.out.println("[S1mp1e][DevShot][CLICKS] TOTAL " + pass + " PASS / " + fail + " FAIL");
        } catch (Throwable t) { skip("tab click test", t); }
    }

    /** Warp the GLFW cursor over the FIRST item grid slot so the creative slot hover pill shows (#4). */
    private static void s1mp1e$warpSlotCursor(MinecraftClient client) {
        try {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            dev.s1mp1e.glass.mixin.HandledScreenAccessor a =
                    (dev.s1mp1e.glass.mixin.HandledScreenAccessor) client.currentScreen;
            net.minecraft.client.util.Window win = client.getWindow();
            double cx = a.s1mp1e$x() + 9 + 8;    // grid slot (x+9, y+18) centre
            double cy = a.s1mp1e$y() + 18 + 8;
            double sxr = (double) win.getWidth() / win.getScaledWidth();
            double syr = (double) win.getHeight() / win.getScaledHeight();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(win.getHandle(), cx * sxr, cy * syr);
        } catch (Throwable t) { skip("warp slot cursor", t); }
    }

    /** Select the creative tab in ({@code topRow}, {@code column}) via the private {@code setSelectedTab}. */
    private static void s1mp1e$selectTab(boolean topRow, int column) throws Exception {
        net.minecraft.item.ItemGroup target = null;
        for (net.minecraft.item.ItemGroup g : net.minecraft.item.ItemGroup.GROUPS) {
            if (g != null && g.isTopRow() == topRow && g.getColumn() == column) { target = g; break; }
        }
        if (target == null) return;
        java.lang.reflect.Method m = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                .getDeclaredMethod("setSelectedTab", net.minecraft.item.ItemGroup.class);
        m.setAccessible(true);
        m.invoke(client().currentScreen, target);
    }

    private static MinecraftClient client() { return MinecraftClient.getInstance(); }

    /** Warp the GLFW cursor over the fused cell of the ({@code topRow},{@code column}) tab so the hover pill shows. */
    private static void s1mp1e$warpTabCursor(MinecraftClient client, boolean topRow, int column) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            int sw = win.getScaledWidth(), sh = win.getScaledHeight();
            int bgW = 195, bgH = 136;
            int left = (sw - bgW) / 2, top = (sh - bgH) / 2;
            double cx = left + (column + 0.5) * (bgW / 6.0);
            double cy = topRow ? top - dev.s1mp1e.glass.render.GlassTabs.BAND / 2.0 : top + bgH + dev.s1mp1e.glass.render.GlassTabs.BAND / 2.0;
            double sxr = (double) win.getWidth() / sw, syr = (double) win.getHeight() / sh;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(win.getHandle(), cx * sxr, cy * syr);
        } catch (Throwable t) {
            skip("warp tab cursor", t);
        }
    }

    // ---- C/D: container scrollbars + stonecutter glide (mode "scroll") -----
    //
    // Opens a stonecutter with a stone input (enough recipes to need the scrollbar), captures the glass slider, then
    // drags the logical scroll one row and captures a consecutive-frame burst of the sub-pixel content glide. Also
    // captures the creative grid scrollbar. Guarded so a missing screen only skips that shot.

    private static void stepScrollSeq(MinecraftClient client) {
        int f = sweepFrames++;
        try {
            switch (f) {
                case 1:  s1mp1e$openStonecutter(client);          break;
                case 40: capture(client, "sc-rest.png");          break;   // glass slider at top
                case 45: s1mp1e$stonecutterScroll(client, 3);     break;   // jump the logical scroll 3 rows
                case 47: capture(client, "sc-glide-1.png");       break;
                case 50: capture(client, "sc-glide-2.png");       break;
                case 54: capture(client, "sc-glide-3.png");       break;
                case 74: capture(client, "sc-settled.png");       break;
                case 79:
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(client.player));
                    break;
                case 118: capture(client, "creative-scroll.png");  break;   // rest
                case 120: s1mp1e$creativeScroll(client);           break;   // jump to bottom -> content glides
                case 122: capture(client, "creative-glide-1.png"); break;
                case 125: capture(client, "creative-glide-2.png"); break;
                case 129: capture(client, "creative-glide-3.png"); break;
                case 150: capture(client, "creative-glide-settled.png"); break;
                case 156: s1mp1e$openLoom(client);                 break;
                case 196: capture(client, "loom.png");             break;   // C: loom panel + filled grid + slider
                case 198: s1mp1e$loomClickProbe(client, false);    break;   // click a rest cell, print selected
                case 200: s1mp1e$loomScroll(client, 4);            break;   // jump visibleTopRow -> content+thumb glide
                case 202: capture(client, "loom-glide-1.png");     break;   // D: sub-pixel banner-grid glide burst
                case 205: capture(client, "loom-glide-2.png");     break;
                case 209: capture(client, "loom-glide-3.png");     break;
                case 214: s1mp1e$loomClickProbe(client, true);     break;   // mid-glide click (snaps first)
                case 232: capture(client, "loom-settled.png");     break;   // row-aligned, clean vanilla handoff
                case 238: s1mp1e$openMerchant(client);             break;   // 12 trades -> scrollbar active
                case 278: capture(client, "merchant-rest.png");    break;   // C: glass slider + trades at rest
                case 280: s1mp1e$merchantClickProbe(client, false); break;  // click a rest trade, print selectedIndex
                case 282: s1mp1e$merchantScroll(client, 5);        break;   // jump indexStartOffset -> trades glide
                case 284: capture(client, "merchant-glide-1.png"); break;   // D: sub-pixel trade glide burst
                case 287: capture(client, "merchant-glide-2.png"); break;
                case 291: capture(client, "merchant-glide-3.png"); break;
                case 296: s1mp1e$merchantClickProbe(client, true); break;   // mid-glide click (snaps first)
                case 314: capture(client, "merchant-settled.png"); break;   // row-aligned, clean vanilla handoff
                case 320:
                    try { client.setScreen(null); } catch (Throwable ignored) {}
                    phase = P_STOP;
                    break;
                default: break;
            }
        } catch (Throwable t) {
            skip("scroll seq frame " + f, t);
            if (f > 340) phase = P_STOP;
        }
    }

    /** Open a stonecutter with a cobbled-deepslate input (>12 stonecutter recipes → the scrollbar is genuinely
     *  active, so the sub-pixel row glide can be exercised). */
    private static void s1mp1e$openStonecutter(MinecraftClient client) {
        try {
            net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
            net.minecraft.screen.StonecutterScreenHandler h =
                    new net.minecraft.screen.StonecutterScreenHandler(1, inv);
            h.getSlot(0).setStack(new ItemStack(Items.COBBLED_DEEPSLATE, 64));
            h.onContentChanged(h.getSlot(0).inventory);
            net.minecraft.client.gui.screen.ingame.StonecutterScreen s =
                    new net.minecraft.client.gui.screen.ingame.StonecutterScreen(
                            h, inv, net.minecraft.text.Text.of("Stonecutter"));
            client.setScreen(s);
        } catch (Throwable t) {
            skip("open stonecutter", t);
        }
    }

    /** Jump the stonecutter logical scroll to the LAST valid row so the glass thumb + content glide toward it. The
     *  offset is kept in range (row-aligned) so clicks/hit-tests stay correct; the eased draw glides there. */
    private static void s1mp1e$stonecutterScroll(MinecraftClient client, int unused) {
        try {
            Object screen = client.currentScreen;
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.StonecutterScreen)) return;
            Class<?> c = net.minecraft.client.gui.screen.ingame.StonecutterScreen.class;
            java.lang.reflect.Method getMax = c.getDeclaredMethod("getMaxScroll");
            getMax.setAccessible(true);
            int rc = ((Integer) getMax.invoke(screen)).intValue();
            if (rc <= 0) return;   // not enough recipes to scroll
            java.lang.reflect.Field off = c.getDeclaredField("scrollOffset");
            java.lang.reflect.Field amt = c.getDeclaredField("scrollAmount");
            off.setAccessible(true); amt.setAccessible(true);
            off.setInt(screen, rc * 4);   // last valid row start (row-aligned)
            amt.setFloat(screen, 1.0f);   // amount 1 = fully scrolled; the glass thumb + content ease toward it
        } catch (Throwable t) {
            skip("stonecutter scroll", t);
        }
    }

    /** Jump the creative logical scroll to the bottom (reflection on scrollPosition + scrollItems) so the glass thumb
     *  and item grid glide toward it — exercises the sub-pixel grid glide (D). */
    private static void s1mp1e$creativeScroll(MinecraftClient client) {
        try {
            Object screen = client.currentScreen;
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            Class<?> c = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class;
            java.lang.reflect.Field sp = c.getDeclaredField("scrollPosition");
            sp.setAccessible(true);
            sp.setFloat(screen, 1.0f);   // logical bottom; the glass thumb + content ease toward it
            // Snap the logical slot contents to the bottom row so hit-testing stays correct while the draw glides.
            net.minecraft.screen.ScreenHandler h =
                    ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler();
            java.lang.reflect.Method scroll = h.getClass().getDeclaredMethod("scrollItems", float.class);
            scroll.setAccessible(true);
            scroll.invoke(h, 1.0f);
        } catch (Throwable t) {
            skip("creative scroll", t);
        }
    }

    /** Open a loom with a banner + dye so the pattern grid + glass scrollbar populate (C on the loom delegate path).
     *  A banner+dye alone yields only the ~16 no-item patterns (exactly 4 rows → not scrollable), so — purely for the
     *  still-shot harness — the client handler's pattern list is padded past 4 rows (by repeating the real synced
     *  entries) and {@code canApplyDyePattern} is forced on, so the D content glide (D) can actually be exercised. This
     *  changes nothing in real play; it only gives the DevShot a scrollable list the way a server would sync one. */
    private static void s1mp1e$openLoom(MinecraftClient client) {
        try {
            net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
            net.minecraft.screen.LoomScreenHandler h = new net.minecraft.screen.LoomScreenHandler(1, inv);
            h.getBannerSlot().setStack(new ItemStack(Items.WHITE_BANNER));
            h.getDyeSlot().setStack(new ItemStack(Items.BLUE_DYE, 8));
            h.onContentChanged(h.getBannerSlot().inventory);
            net.minecraft.client.gui.screen.ingame.LoomScreen s =
                    new net.minecraft.client.gui.screen.ingame.LoomScreen(h, inv, net.minecraft.text.Text.of("Loom"));
            client.setScreen(s);
            s1mp1e$loomPadPatterns(h, s, 30);   // pad to >16 (>4 rows) so the list scrolls in the harness
        } catch (Throwable t) {
            skip("open loom", t);
        }
    }

    /** Pad the loom handler's client pattern list past {@code target} entries (repeating the real synced ones) and
     *  force {@code canApplyDyePattern} so the DevShot loom is genuinely scrollable. Harness-only. */
    private static void s1mp1e$loomPadPatterns(net.minecraft.screen.LoomScreenHandler h, Object screen, int target) {
        try {
            java.util.List<?> real = h.getBannerPatterns();
            if (real == null || real.isEmpty()) return;
            java.util.List<Object> padded = new java.util.ArrayList<>();
            while (padded.size() < target) for (Object o : real) { padded.add(o); if (padded.size() >= target) break; }
            java.lang.reflect.Field bp = net.minecraft.screen.LoomScreenHandler.class.getDeclaredField("bannerPatterns");
            bp.setAccessible(true);
            bp.set(h, padded);
            java.lang.reflect.Field ca = net.minecraft.client.gui.screen.ingame.LoomScreen.class
                    .getDeclaredField("canApplyDyePattern");
            ca.setAccessible(true);
            ca.setBoolean(screen, true);
        } catch (Throwable t) {
            skip("loom pad patterns", t);
        }
    }

    /** Jump the loom logical scroll to {@code row} (row-aligned {@code visibleTopRow}); the glass thumb + banner grid
     *  ease toward it. Clicks/hit-tests keep using the logical row so they stay correct while the draw glides. */
    private static void s1mp1e$loomScroll(MinecraftClient client, int row) {
        try {
            Object screen = client.currentScreen;
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.LoomScreen)) return;
            Class<?> c = net.minecraft.client.gui.screen.ingame.LoomScreen.class;
            java.lang.reflect.Method getRows = c.getDeclaredMethod("getRows");
            getRows.setAccessible(true);
            int rows = ((Integer) getRows.invoke(screen)).intValue();
            int offscreen = Math.max(0, rows - 4);
            int r = Math.max(0, Math.min(row, offscreen));
            java.lang.reflect.Field vtr = c.getDeclaredField("visibleTopRow");
            java.lang.reflect.Field sp = c.getDeclaredField("scrollPosition");
            vtr.setAccessible(true); sp.setAccessible(true);
            vtr.setInt(screen, r);
            sp.setFloat(screen, offscreen <= 0 ? 0f : (float) r / offscreen);
        } catch (Throwable t) {
            skip("loom scroll", t);
        }
    }

    /** Click a loom pattern cell (rest: row 2 col 1; mid-glide: same screen point) and print the resulting selection.
     *  Proves {@code mouseClicked} selects the pattern under the cursor at rest and, mid-glide, after the snap. */
    private static void s1mp1e$loomClickProbe(MinecraftClient client, boolean midGlide) {
        try {
            Object screen = client.currentScreen;
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.LoomScreen)) return;
            int[] xy = s1mp1e$containerOrigin(screen);
            int px = xy[0], py = xy[1];
            double mx = px + 60 + 1 * 14 + 7;   // col 1
            double my = py + 13 + 2 * 14 + 7;   // visible row 2
            ((net.minecraft.client.gui.screen.Screen) screen).mouseClicked(mx, my, 0);
            net.minecraft.screen.LoomScreenHandler h =
                    (net.minecraft.screen.LoomScreenHandler) ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler();
            System.out.println("[S1mp1e][DevShot] loom click " + (midGlide ? "mid-glide" : "rest")
                    + " -> selectedPattern=" + h.getSelectedPattern() + " (patterns=" + h.getBannerPatterns().size() + ")");
        } catch (Throwable t) {
            skip("loom click probe", t);
        }
    }

    /** Open a merchant with 12 fabricated trades (via a client {@code SimpleMerchant}) so its trade list overflows the
     *  7 visible rows and the glass scrollbar + trade glide (C/D) are exercised. */
    private static void s1mp1e$openMerchant(MinecraftClient client) {
        try {
            net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
            net.minecraft.village.SimpleMerchant merchant = new net.minecraft.village.SimpleMerchant(client.player);
            net.minecraft.village.TradeOfferList offers = new net.minecraft.village.TradeOfferList();
            net.minecraft.item.Item[] buys = {
                    Items.EMERALD, Items.EMERALD, Items.WHEAT, Items.COAL, Items.IRON_INGOT, Items.EMERALD,
                    Items.PAPER, Items.GOLD_INGOT, Items.EMERALD, Items.ROTTEN_FLESH, Items.EMERALD, Items.STRING };
            net.minecraft.item.Item[] sells = {
                    Items.BREAD, Items.DIAMOND, Items.EMERALD, Items.TORCH, Items.SHIELD, Items.MAP,
                    Items.BOOK, Items.CLOCK, Items.ENDER_PEARL, Items.EMERALD, Items.GLASS, Items.ARROW };
            for (int i = 0; i < 12; i++) {
                offers.add(new net.minecraft.village.TradeOffer(
                        new ItemStack(buys[i], 1 + (i % 3)), new ItemStack(sells[i], 1 + (i % 2)), 12, 5, 0.05f));
            }
            net.minecraft.screen.MerchantScreenHandler h =
                    new net.minecraft.screen.MerchantScreenHandler(1, inv, merchant);
            h.setOffers(offers);
            h.setExperienceFromServer(20);
            h.setLevelProgress(2);
            net.minecraft.client.gui.screen.ingame.MerchantScreen s =
                    new net.minecraft.client.gui.screen.ingame.MerchantScreen(h, inv, net.minecraft.text.Text.of("Villager"));
            client.setScreen(s);
        } catch (Throwable t) {
            skip("open merchant", t);
        }
    }

    /** Jump the merchant logical scroll to trade {@code offset} (row-aligned {@code indexStartOffset}); the glass thumb
     *  + trade content ease toward it while clicks/hit-tests keep using the logical offset. */
    private static void s1mp1e$merchantScroll(MinecraftClient client, int offset) {
        try {
            Object screen = client.currentScreen;
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen)) return;
            net.minecraft.screen.MerchantScreenHandler h =
                    (net.minecraft.screen.MerchantScreenHandler) ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler();
            int maxRows = Math.max(0, h.getRecipes().size() - 7);
            int off = Math.max(0, Math.min(offset, maxRows));
            java.lang.reflect.Field iso = net.minecraft.client.gui.screen.ingame.MerchantScreen.class
                    .getDeclaredField("indexStartOffset");
            iso.setAccessible(true);
            iso.setInt(screen, off);
        } catch (Throwable t) {
            skip("merchant scroll", t);
        }
    }

    /** Click a merchant trade row (rest: row 2; mid-glide: same screen point) and print the resulting selectedIndex.
     *  Proves {@code mouseClicked} selects the trade under the cursor at rest and, mid-glide, after the snap. */
    private static void s1mp1e$merchantClickProbe(MinecraftClient client, boolean midGlide) {
        try {
            Object screen = client.currentScreen;
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen)) return;
            int[] xy = s1mp1e$containerOrigin(screen);
            int px = xy[0], py = xy[1];
            double mx = px + 45;                 // over the trade button
            double my = py + 18 + 2 * 20 + 10;   // visible trade row 2
            ((net.minecraft.client.gui.screen.Screen) screen).mouseClicked(mx, my, 0);
            java.lang.reflect.Field si = net.minecraft.client.gui.screen.ingame.MerchantScreen.class
                    .getDeclaredField("selectedIndex");
            si.setAccessible(true);
            System.out.println("[S1mp1e][DevShot] merchant click " + (midGlide ? "mid-glide" : "rest")
                    + " -> selectedIndex=" + si.getInt(screen));
        } catch (Throwable t) {
            skip("merchant click probe", t);
        }
    }

    /** Read a {@code HandledScreen}'s container origin (protected {@code x}/{@code y}) for click-probe geometry. */
    private static int[] s1mp1e$containerOrigin(Object screen) throws Exception {
        java.lang.reflect.Field fx = net.minecraft.client.gui.screen.ingame.HandledScreen.class.getDeclaredField("x");
        java.lang.reflect.Field fy = net.minecraft.client.gui.screen.ingame.HandledScreen.class.getDeclaredField("y");
        fx.setAccessible(true); fy.setAccessible(true);
        return new int[] { fx.getInt(screen), fy.getInt(screen) };
    }

    // ---- G: HUD overlays (mode "hud") -------------------------------------
    //
    // In-world, frame-scripted: add chat lines (glass panel), open the chat screen (glass input bar), show an action
    // bar message (glass pill), pop a system toast (glass card + slide), inject a client boss bar (blue capsule + glass
    // capsule) and spawn a named entity (frosted name-tag plate). Each captured with the in-world HUD; guarded per step.

    private static void stepHudSeq(MinecraftClient client) {
        int f = sweepFrames++;
        try {
            switch (f) {
                case 1:
                    net.minecraft.client.gui.hud.ChatHud chat = client.inGameHud.getChatHud();
                    chat.addMessage(net.minecraft.text.Text.literal("<S1mp1e> liquid glass chat panel"));
                    chat.addMessage(net.minecraft.text.Text.literal("the widest line sets the panel width"));
                    chat.addMessage(net.minecraft.text.Text.literal("per-line dark rects are dropped"));
                    break;
                case 40: capture(client, "chat.png"); break;                     // in-world: glass panel behind lines
                case 44:
                    client.setScreen(new net.minecraft.client.gui.screen.ChatScreen("liquid glass input bar"));
                    break;
                case 64: capture(client, "chat-input.png"); break;               // glass input bar + chat panel
                case 66: try { client.setScreen(null); } catch (Throwable ignored) {} break;
                case 70:
                    client.inGameHud.setOverlayMessage(net.minecraft.text.Text.literal("Action Bar Pill"), false);
                    break;
                case 80: capture(client, "actionbar.png"); break;                // glass pill, fading with the timer
                case 84: s1mp1e$showToast(client); break;
                case 92: capture(client, "toast.png"); break;                    // glass toast card, mid slide-in
                case 100: s1mp1e$addBossBar(client); break;
                case 120: capture(client, "bossbar.png"); break;                 // blue capsule + concentric glass
                case 124: s1mp1e$spawnNameTag(client); break;
                case 150: capture(client, "nametag.png"); break;                 // frosted name-tag plate
                case 154: s1mp1e$tabListVisible(client); break;                  // best-effort (scoreboard + key)
                case 168: capture(client, "tablist.png"); break;
                case 172:
                    try { s1mp1e$clearBossBars(client); } catch (Throwable ignored) {}
                    try { client.getToastManager().clear(); } catch (Throwable ignored) {}
                    phase = P_STOP;
                    break;
                default: break;
            }
        } catch (Throwable t) {
            skip("hud seq frame " + f, t);
            if (f > 175) phase = P_STOP;
        }
    }

    /** Pop a system toast so the glass toast card (and its slide-in) can be captured. */
    private static void s1mp1e$showToast(MinecraftClient client) {
        try {
            net.minecraft.client.toast.SystemToast.Type type = net.minecraft.client.toast.SystemToast.Type.values()[0];
            net.minecraft.client.toast.SystemToast.show(client.getToastManager(), type,
                    net.minecraft.text.Text.literal("S1mp1e"),
                    net.minecraft.text.Text.literal("liquid glass toast"));
        } catch (Throwable t) { skip("show toast", t); }
    }

    /** Inject a client boss bar directly into the BossBarHud map (reflection) so the glass boss bar can be captured. */
    private static void s1mp1e$addBossBar(MinecraftClient client) {
        try {
            net.minecraft.client.gui.hud.BossBarHud hud = client.inGameHud.getBossBarHud();
            java.lang.reflect.Field f = net.minecraft.client.gui.hud.BossBarHud.class.getDeclaredField("bossBars");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<java.util.UUID, net.minecraft.client.gui.hud.ClientBossBar> map =
                    (java.util.Map<java.util.UUID, net.minecraft.client.gui.hud.ClientBossBar>) f.get(hud);
            net.minecraft.client.gui.hud.ClientBossBar bar = new net.minecraft.client.gui.hud.ClientBossBar(
                    java.util.UUID.randomUUID(), net.minecraft.text.Text.literal("Liquid Glass Boss"), 0.65F,
                    net.minecraft.entity.boss.BossBar.Color.PURPLE, net.minecraft.entity.boss.BossBar.Style.PROGRESS,
                    false, false, false);
            map.put(java.util.UUID.randomUUID(), bar);
        } catch (Throwable t) { skip("add boss bar", t); }
    }

    private static void s1mp1e$clearBossBars(MinecraftClient client) {
        try { client.inGameHud.getBossBarHud().clear(); } catch (Throwable ignored) {}
    }

    /** Spawn a named armor stand a few blocks in front of the player (facing it) so its name-tag plate renders. */
    private static void s1mp1e$spawnNameTag(MinecraftClient client) {
        try {
            net.minecraft.client.network.ClientPlayerEntity p = client.player;
            p.setPitch(0f); p.prevPitch = 0f;            // look level so the tag is centred
            double x = p.getX(), y = p.getY(), z = p.getZ();
            net.minecraft.server.MinecraftServer server = client.getServer();
            net.minecraft.server.world.ServerWorld ow = server.getOverworld();
            net.minecraft.entity.decoration.ArmorStandEntity e =
                    new net.minecraft.entity.decoration.ArmorStandEntity(ow, x, y, z + 3.0);
            e.setCustomName(net.minecraft.text.Text.literal("Frosted Name Tag"));
            e.setCustomNameVisible(true);
            ow.spawnEntity(e);
        } catch (Throwable t) { skip("spawn name tag", t); }
    }

    /** Best-effort tab list: add a LIST-slot scoreboard objective on the server + hold the player-list key. */
    private static void s1mp1e$tabListVisible(MinecraftClient client) {
        try {
            net.minecraft.server.world.ServerWorld ow = client.getServer().getOverworld();
            net.minecraft.scoreboard.Scoreboard sb = ow.getScoreboard();
            net.minecraft.scoreboard.ScoreboardObjective obj = sb.getNullableObjective("s1mp1e_tab");
            if (obj == null) {
                obj = sb.addObjective("s1mp1e_tab", net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                        net.minecraft.text.Text.literal("Players"),
                        net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER);
            }
            sb.setObjectiveSlot(0, obj);   // slot 0 = LIST
            client.options.playerListKey.setPressed(true);
        } catch (Throwable t) { skip("tab list visible", t); }
    }

    // ---- H: modules (mode "modules") --------------------------------------
    //
    // Enable Block Outline (recolour / chroma / width / fill) while looking at the ground, and Chroma HUD (per-char
    // rainbow + uniform wave-0) over the FPS/Coords HUD, then open the config screen (zh-TW module labels). All module
    // state is snapshotted and restored at the end (nothing is saved to disk - the run quits after).

    private static void stepModulesSeq(MinecraftClient client) {
        int f = sweepFrames++;
        try {
            switch (f) {
                case 1:
                    s1mp1e$snapshotModules();
                    s1mp1e$lookAtGround(client);
                    s1mp1e$outline(0xCCFFFFFF, 1.0, false, false, 0);    // width 1 (thin end of the 1..8 range)
                    break;
                case 24: capture(client, "outline-width1.png"); break;  // width 1 — compare with outline-width8.png
                case 28: s1mp1e$outline(0xCCFF3060, 6.0, false, false, 0); break;   // custom colour + width 6, no chroma/fill
                case 40: capture(client, "outline.png"); break;
                case 44: s1mp1e$outline(0xCCFF3060, 6.0, true, false, 0); break;   // chroma on
                case 48: capture(client, "outline-chroma-1.png"); break;
                case 60: capture(client, "outline-chroma-2.png"); break;          // hue advanced
                case 64: s1mp1e$outline(0xCCFFFFFF, 8.0, false, false, 0); break;  // width 8 (core-profile clamp check)
                case 84: capture(client, "outline-width8.png"); break;
                case 88: s1mp1e$outline(0xCCFFFFFF, 2.5, false, true, 0x5533C0FF); break;   // translucent fill
                case 108: capture(client, "outline-fill.png"); break;
                case 112: s1mp1e$outlineOff(); s1mp1e$chroma(true, 1.0, 0.75, 0.5); break;  // per-char rainbow HUD
                case 132: capture(client, "hud-chroma.png"); break;
                case 136: s1mp1e$chroma(true, 1.0, 0.75, 0.0); break;             // wave 0 = one uniform hue
                case 156: capture(client, "hud-chroma-flat.png"); break;
                case 160: s1mp1e$openConfigModule(client, "BlockOutline"); break;  // Visual tab, 方塊外框 page
                case 190: capture(client, "config-blockoutline.png"); break;      // zh-TW: 方塊外框 + its settings
                case 194: s1mp1e$selectConfigModule("ChromaHud"); break;           // HUD tab, 彩虹 HUD page
                case 224: capture(client, "config-chromahud.png"); break;         // zh-TW: 彩虹 HUD + its settings
                case 228:
                    try { client.setScreen(null); } catch (Throwable ignored) {}
                    s1mp1e$restoreModules();
                    phase = P_STOP;
                    break;
                default: break;
            }
        } catch (Throwable t) {
            skip("modules seq frame " + f, t);
            if (f > 232) { s1mp1e$restoreModules(); phase = P_STOP; }
        }
    }

    /** Pitch the player down so the crosshair targets a ground block (the outline recolour needs a targeted block). */
    private static void s1mp1e$lookAtGround(MinecraftClient client) {
        try {
            net.minecraft.client.network.ClientPlayerEntity p = client.player;
            p.setPitch(72f); p.prevPitch = 72f;
            p.setYaw(0f); p.prevYaw = 0f; p.headYaw = 0f;
        } catch (Throwable t) { skip("look at ground", t); }
    }

    private static void s1mp1e$outline(int colour, double width, boolean chroma, boolean fill, int fillColour) {
        Module m = ModuleManager.byName("BlockOutline");
        if (!(m instanceof dev.s1mp1e.client.module.BlockOutlineModule)) return;
        dev.s1mp1e.client.module.BlockOutlineModule bo = (dev.s1mp1e.client.module.BlockOutlineModule) m;
        bo.setEnabled(true);
        bo.color.colorValue = colour;
        bo.width.doubleValue = width;
        bo.chroma.boolValue = chroma;
        bo.fill.boolValue = fill;
        if (fill) bo.fillColour.colorValue = fillColour;
    }

    private static void s1mp1e$outlineOff() {
        Module m = ModuleManager.byName("BlockOutline");
        if (m != null) m.setEnabled(false);
    }

    /** The config screen kept open across the two config-page captures (reused for both modules). */
    private static dev.s1mp1e.client.gui.S1mp1eConfigScreen cfgScreen;

    /** Open the settings screen and drive it to {@code moduleName}'s category tab + row (zh-TW page). */
    private static void s1mp1e$openConfigModule(MinecraftClient client, String moduleName) {
        cfgScreen = new dev.s1mp1e.client.gui.S1mp1eConfigScreen();
        open(client, cfgScreen, "open settings (modules)");
        s1mp1e$selectConfigModule(moduleName);
    }

    /**
     * Reflectively switch the (already open) config screen to the tab holding {@code moduleName} and select that row,
     * so its zh-TW label + settings render. Harness-only: the config screen has no public tab/select API, so DevShot
     * reaches its private {@code tab}/{@code rebuildTab()}/{@code selectModule(..)} rather than change that file (R3).
     */
    private static void s1mp1e$selectConfigModule(String moduleName) {
        try {
            if (cfgScreen == null) return;
            Module m = ModuleManager.byName(moduleName);
            if (m == null) { skip("select config module " + moduleName, new IllegalStateException("no such module")); return; }
            String[] tabs = {"Combat", "HUD", "Visual"};
            int ti = 0;
            for (int i = 0; i < tabs.length; i++) if (tabs[i].equalsIgnoreCase(m.category)) ti = i;
            Class<?> c = cfgScreen.getClass();
            java.lang.reflect.Field tabF = c.getDeclaredField("tab");
            tabF.setAccessible(true); tabF.setInt(cfgScreen, ti);
            // Keep the top tab-highlight pill in sync with the switched tab (it rides the tabSlide Anim).
            try {
                java.lang.reflect.Field tsF = c.getDeclaredField("tabSlide");
                tsF.setAccessible(true);
                Object ts = tsF.get(cfgScreen);
                ts.getClass().getMethod("snap", float.class).invoke(ts, (float) ti);
            } catch (Throwable ignored) {}
            java.lang.reflect.Method rebuild = c.getDeclaredMethod("rebuildTab");
            rebuild.setAccessible(true); rebuild.invoke(cfgScreen);
            java.lang.reflect.Method sel = c.getDeclaredMethod("selectModule", Module.class);
            sel.setAccessible(true); sel.invoke(cfgScreen, m);
        } catch (Throwable t) { skip("select config module " + moduleName, t); }
    }

    private static void s1mp1e$chroma(boolean on, double speed, double sat, double wave) {
        Module m = ModuleManager.byName("ChromaHud");
        if (!(m instanceof dev.s1mp1e.client.module.ChromaHudModule)) return;
        dev.s1mp1e.client.module.ChromaHudModule ch = (dev.s1mp1e.client.module.ChromaHudModule) m;
        ch.setEnabled(on);
        ch.speed.doubleValue = speed;
        ch.saturation.doubleValue = sat;
        ch.wave.doubleValue = wave;
        // Make sure there is HUD text to colour: FPS is on by default; also show coordinates.
        Module coords = ModuleManager.byName("CoordsHUD");
        if (coords != null) coords.setEnabled(true);
    }

    // Snapshot/restore so the modules sweep leaves no lasting change (nothing is written to disk).
    private static boolean[] snapEnabled;
    private static void s1mp1e$snapshotModules() {
        String[] names = {"BlockOutline", "ChromaHud", "CoordsHUD", "FpsHUD"};
        snapEnabled = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            Module m = ModuleManager.byName(names[i]);
            snapEnabled[i] = m != null && m.enabled;
        }
    }
    private static void s1mp1e$restoreModules() {
        try {
            String[] names = {"BlockOutline", "ChromaHud", "CoordsHUD", "FpsHUD"};
            for (int i = 0; snapEnabled != null && i < names.length; i++) {
                Module m = ModuleManager.byName(names[i]);
                if (m == null) continue;
                m.setEnabled(snapEnabled[i]);
                for (int s = 0; s < m.settings.size(); s++) m.settings.get(s).reset();
            }
        } catch (Throwable ignored) {}
    }

    // ---- font probe (S1MP1E_SHOT_MODE=font, first in all1201) ----------------------------------------------

    /** Dev-only screen: TTF probe strings at 3x, each character over its own advance cell (alternating shades), so a glyph
     *  drawn outside its cell shows (vanilla's double-counted bearing put "（" inside the next one). The red line is the
     *  bottom of the 9-unit vanilla box that Essential's text boxes are sized by; descenders cross it. 1.19.2: drawn
     *  through the MatrixStack (no DrawContext). */
    private static final class FontProbe extends net.minecraft.client.gui.screen.Screen {
        private static final String[] LINES = { "疾跑（切換）", "（A）A（B「（」『』【】", "直向長度（0＝同大小）",
                                                "Create new world · ago · just now" };
        FontProbe() { super(net.minecraft.text.Text.literal("font probe")); }
        @Override public void render(net.minecraft.client.util.math.MatrixStack m, int mx, int my, float d) {
            net.minecraft.client.gui.DrawableHelper.fill(m, 0, 0, this.width, this.height, 0xFF18223A);
            net.minecraft.client.font.TextHandler th = textRenderer.getTextHandler();
            m.push();
            m.scale(3.0F, 3.0F, 1.0F);
            int y = 4;
            for (String s : LINES) {
                float x = 4;
                for (int i = 0, k = 0; i < s.length(); k++) {
                    int n = Character.charCount(s.codePointAt(i));
                    float adv = th.getWidth(s.substring(i, i + n));
                    m.push();
                    m.translate(x, y, 0);
                    m.scale(adv, 1.0F, 1.0F);
                    net.minecraft.client.gui.DrawableHelper.fill(m, 0, 0, 1, 9, (k & 1) == 0 ? 0x40FFFFFF : 0x20FFFFFF);
                    m.pop();
                    x += adv;
                    i += n;
                }
                net.minecraft.client.gui.DrawableHelper.fill(m, 4, y + 9, (int) Math.ceil(x), y + 10, 0x60FF5050);
                textRenderer.draw(m, s, 4, y, 0xFFFFFFFF);
                y += 15;
            }
            m.pop();
        }
    }

    /** Font probe: a moment to bake the glyphs, one shot, then back to the title (all1201 goes on to the Essential sweep). */
    private static void stepFontProbe(MinecraftClient client) {
        if (++frames == 30) { capture(client, "font-probe.png"); return; }
        if (frames >= 32) {
            open(client, new TitleScreen(), "back to title");
            frames = 0;
            phase = allMode ? P_ESSENTIAL : P_STOP;
        }
    }

    // ---- Essential sweep (S1MP1E_SHOT_MODE=essential), ported from 1.21.1: menus only, no world ----------

    /** Essential's settings (scrolled), its host-world modal over the title screen and its screenshot browser, each shot with
     *  the screen class noted in ess-screens.txt. */
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
        if (frames >= 540) {
            frames = 0;
            if (allMode) { open(client, new S1mp1eConfigScreen(), "open settings (over title)"); phase = P_WAIT_CONFIG; }   // then the world sweeps
            else phase = P_STOP;
        }
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
            s.mouseScrolled(w * fx, h * 0.5, amount);   // 1.20.1: (x, y, amount)
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

        if (ntStage >= NT_MODES.length) {
            nameTagSetMode("Glass");
            if (chainMode) { frames = 0; phase = P_WINGS; } else phase = P_STOP;
            return;
        }

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
            ServerWorld level = sp.getWorld();   // 1.19.2: getServerWorld() came in 1.20
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
            net.minecraft.entity.decoration.ArmorStandEntity stand = new net.minecraft.entity.decoration.ArmorStandEntity(level, x, y, z);
            stand.setCustomName(net.minecraft.text.Text.literal(name));
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
                var state = (dx % 2 == 0 ? net.minecraft.block.Blocks.SNOW_BLOCK : net.minecraft.block.Blocks.OBSIDIAN).getDefaultState();
                for (int dy = 0; dy <= 7; dy++) {
                    level.setBlockState(new net.minecraft.util.math.BlockPos(cx + dx, floorY + dy, cz), state);
                }
            }
        } catch (Throwable t) { skip("name tag wall", t); }
    }

    private static void nameTagSetMode(String m) {
        try {
            Module mod = ModuleManager.byName("NameTags");
            if (mod != null) {
                Setting s = mod.setting("Background");
                if (s != null) s.setMode(m);
            }
        } catch (Throwable t) { skip("nametag set mode", t); }
    }

    // ---- wings + minimap sweeps (S1MP1E_SHOT_MODE=wings | minimap), ported from 1.21.1 ---------------

    /** Server command run as the player (so "~" is relative to them); the result or parse error goes to the log. */
    private static void devCmd(MinecraftClient client, String cmd) {
        try {
            MinecraftServer server = client.getServer();
            if (server == null) return;
            server.execute(() -> {
                if (server.getPlayerManager().getPlayerList().isEmpty()) return;
                ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().get(0);
                try {
                    int r = server.getCommandManager().getDispatcher().execute(cmd, sp.getCommandSource().withSilent());
                    System.out.println("[S1mp1e][DevShot] cmd '" + cmd + "' -> " + r);
                } catch (Throwable t) {
                    System.out.println("[S1mp1e][DevShot] cmd '" + cmd + "' failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) { skip("cmd " + cmd, t); }
    }

    /**
     * Third-person shots of the DragonWings module: standing (back, front, three-quarter), sneaking (folded), falling
     * and creative flight, wearing an elytra (wings must hide) and with the module off. Same stages as 1.21.1's sweep.
     */
    private static void stepWings(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Module m = ModuleManager.byName("DragonWings");
        if (player == null || m == null) { phase = P_STOP; return; }
        try { if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) client.setScreen(null); } catch (Throwable ignored) {}
        long now = System.nanoTime();
        if (frames++ == 0) {
            wgStage = 0;
            wgT0 = now;
            try { client.options.pauseOnLostFocus = false; } catch (Throwable ignored) {}
            m.setEnabled(true);
            devCmd(client, "gamemode creative");
            devCmd(client, "clear @s");   // the throwaway devshot world: no armor or shield hiding the back
            devCmd(client, "time set noon");
            devCmd(client, "tp @s ~ ~ ~ 0 15");
            client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
            return;
        }
        double secs = (now - wgT0) / 1e9;
        // three-quarter stage: the body turned 50 degrees (the most a player's head allows) under the back camera
        if (wgStage == 10) { player.bodyYaw = player.getYaw() - 50f; player.prevBodyYaw = player.bodyYaw; }
        switch (wgStage) {
            case 0 -> { if (secs > 4.0) { capture(client, "wings-01-back-idle.png"); wgNext(now, 1);
                        client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT); } }
            case 1 -> { if (secs > 1.0) { capture(client, "wings-02-front-idle.png"); wgNext(now, 10);
                        client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK); } }
            case 10 -> { if (secs > 1.0) { capture(client, "wings-03-back-three-quarter.png"); wgNext(now, 2);
                        player.bodyYaw = player.getYaw(); player.prevBodyYaw = player.bodyYaw;
                        client.options.sneakKey.setPressed(true); } }
            case 2 -> { if (secs > 1.5) { capture(client, "wings-04-sneak-folded.png"); wgNext(now, 3);
                        client.options.sneakKey.setPressed(false); devCmd(client, "tp @s ~ ~8 ~"); } }
            case 3 -> { if (secs > 0.6) { capture(client, "wings-05-falling.png"); wgNext(now, 4); } }
            case 4 -> { if (secs > 2.5) { wgNext(now, 5);
                        player.getAbilities().flying = true; player.sendAbilitiesUpdate(); devCmd(client, "tp @s ~ ~3 ~"); } }
            case 5 -> { if (secs > 1.5) { capture(client, "wings-06-flying.png"); wgNext(now, 6);
                        player.getAbilities().flying = false; player.sendAbilitiesUpdate();
                        devCmd(client, "item replace entity @s armor.chest with minecraft:elytra"); } }
            case 6 -> { if (secs > 2.5) { capture(client, "wings-08-elytra-hides-wings.png"); wgNext(now, 7);
                        devCmd(client, "item replace entity @s armor.chest with minecraft:air"); m.setEnabled(false); } }
            case 7 -> { if (secs > 1.0) { capture(client, "wings-09-module-off.png"); wgNext(now, 30);
                        client.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
                        devCmd(client, "tp @s ~ ~ ~ 0 15");
                        Module fl = ModuleManager.byName("Freelook");
                        if (fl != null) fl.setEnabled(true); } }
            // Freelook: hold (devHold), turn the camera 90 degrees right and 15 up, release; the player must not turn
            case 30 -> { if (secs > 1.0) { dev.s1mp1e.client.module.FreelookModule.devHold = true; wgNext(now, 31); } }
            case 31 -> { if (secs > 0.3) { dev.s1mp1e.client.module.FreelookModule.turn(600, -100); wgNext(now, 32); } }
            case 32 -> { if (secs > 0.8) {
                        // the rendered Camera's own angles too: Freelook's numbers alone passed while 1.21.2's camera ignored them
                        net.minecraft.client.render.Camera cam = client.gameRenderer.getCamera();
                        System.out.println(String.format("[S1mp1e][DevShot] freelook held: camera %.1f/%.1f (rendered %.1f/%.1f), player %.1f/%.1f, view %s",
                                dev.s1mp1e.client.module.FreelookModule.yaw(), dev.s1mp1e.client.module.FreelookModule.pitch(),
                                cam.getYaw(), cam.getPitch(), player.getYaw(), player.getPitch(), client.options.getPerspective()));
                        capture(client, "fl-01-freelook-turned.png");
                        dev.s1mp1e.client.module.FreelookModule.devHold = false; wgNext(now, 33); } }
            case 33 -> { if (secs > 2.0) {
                        System.out.println("[S1mp1e][DevShot] freelook released: active=" + dev.s1mp1e.client.module.FreelookModule.active()
                                + ", view " + client.options.getPerspective());
                        capture(client, "fl-02-freelook-returned.png");
                        // fine crosshair: fractional size / thickness / gap, rotated (the double settings of 79f5d00)
                        Module chm = ModuleManager.byName("Crosshair");
                        if (chm instanceof dev.s1mp1e.client.module.CrosshairModule ch) {
                            ch.setEnabled(true);
                            ch.shape.modeValue = "Cross";
                            ch.size.doubleValue = 6.5; ch.thick.doubleValue = 0.5; ch.gap.doubleValue = 2.5; ch.rotation.doubleValue = 45;
                        }
                        wgNext(now, 34); } }
            case 34 -> { if (secs > 0.8) {
                        capture(client, "ch-01-fine-crosshair.png");
                        Module chm = ModuleManager.byName("Crosshair");
                        if (chm instanceof dev.s1mp1e.client.module.CrosshairModule ch) {
                            ch.size.doubleValue = 4; ch.thick.doubleValue = 1; ch.gap.doubleValue = 2; ch.rotation.doubleValue = 0;
                        }
                        wgNext(now, 8); } }
            default -> { if (secs > 0.5) { if (chainMode) { frames = 0; phase = P_MINIMAP; } else phase = P_STOP; } }
        }
    }

    private static void wgNext(long now, int stage) { wgStage = stage; wgT0 = now; }

    /**
     * docs/MINIMAP_SPEC.md acceptance sweep on a normal-terrain world: orientation check with coloured wool (red east,
     * blue north, then lime south placed AFTER the map is drawn = the block-update path), both shapes and orientations,
     * all zoom steps, Tab radar (still mobs + items), other players (client-side test players, one inside and one far
     * outside the map), a 640-block teleport (ring window shift: no wrapped terrain), the Nether placeholder, the
     * full-screen map, module-off releasing the texture, and the ToggleSprint pill. Each shot waits for the work queue.
     */
    private static void stepMinimap(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        dev.s1mp1e.client.module.MinimapModule m = dev.s1mp1e.client.module.MinimapModule.get();
        if (player == null || m == null) { phase = P_STOP; return; }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        try { if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) client.setScreen(null); } catch (Throwable ignored) {}
        long now = System.nanoTime();
        if (frames++ == 0) {
            mmStage = 0;
            mmT0 = now;
            try { client.options.pauseOnLostFocus = false; } catch (Throwable ignored) {}
            m.setEnabled(true);
            m.shape.modeValue = dev.s1mp1e.client.module.MinimapModule.ROUND;
            m.orientation.modeValue = dev.s1mp1e.client.module.MinimapModule.NORTH_UP;
            m.zoom.modeValue = dev.s1mp1e.client.module.MinimapModule.MEDIUM;
            m.size.intValue = 96;
            m.names.boolValue = false;
            devCmd(client, "gamemode creative");
            devCmd(client, "tp @s ~ ~ ~ -90 30");   // face east
            devCmd(client, "fill ~4 ~15 ~-1 ~26 ~15 ~1 minecraft:red_wool");
            devCmd(client, "fill ~-1 ~15 ~-26 ~1 ~15 ~-4 minecraft:blue_wool");
            return;
        }
        double secs = (now - mmT0) / 1e9;
        boolean settled = dev.s1mp1e.client.minimap.MinimapData.pendingWork() == 0;
        String R = dev.s1mp1e.client.module.MinimapModule.ROUND, SQ = dev.s1mp1e.client.module.MinimapModule.SQUARE;
        String N = dev.s1mp1e.client.module.MinimapModule.NORTH_UP, F = dev.s1mp1e.client.module.MinimapModule.FOLLOW;
        switch (mmStage) {
            case 0 -> { if (secs > 4.0 && settled || secs > 20.0) { capture(client, "mm-01-round-north-medium.png"); mmNext(now, 1); m.orientation.modeValue = F; } }
            case 1 -> { if (secs > 1.0) { capture(client, "mm-02-round-follow-facing-east.png"); mmNext(now, 2); m.shape.modeValue = SQ; m.orientation.modeValue = N; } }
            case 2 -> { if (secs > 1.0) { capture(client, "mm-03-square-north-medium.png"); mmNext(now, 3); m.orientation.modeValue = F; m.zoom.modeValue = dev.s1mp1e.client.module.MinimapModule.CLOSE; } }
            case 3 -> { if (secs > 1.5) { capture(client, "mm-04-square-follow-close.png"); mmNext(now, 4); m.shape.modeValue = R; m.orientation.modeValue = N; m.zoom.modeValue = dev.s1mp1e.client.module.MinimapModule.FAR; } }
            case 4 -> { if (secs > 1.5 && settled || secs > 10.0) { capture(client, "mm-05-round-north-far.png"); mmNext(now, 5); m.zoom.modeValue = dev.s1mp1e.client.module.MinimapModule.MEDIUM;
                        devCmd(client, "fill ~-1 ~15 ~4 ~1 ~15 ~26 minecraft:lime_wool"); } }
            case 5 -> { if (secs > 1.5 && settled) { capture(client, "mm-06-block-update-lime-south.png"); mmNext(now, 6);
                        for (int i = 0; i < 4; i++) {
                            devCmd(client, "summon minecraft:husk ~" + (8 + i * 3) + " ~ ~" + (6 - i * 4) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b}");
                            devCmd(client, "summon minecraft:cow ~" + (-6 - i * 3) + " ~ ~" + (8 + i * 2) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b}");
                            // 1.20.1 item NBT: Count as a byte (the lower-case "count" is 1.20.5's component format)
                            devCmd(client, "summon minecraft:item ~" + (-4 - i * 2) + " ~1 ~" + (-6 - i * 3) + " {Item:{id:\"minecraft:diamond\",Count:1b},PickupDelay:32767}");
                        }
                        mmFakes = new net.minecraft.client.network.OtherClientPlayerEntity[] {
                                mmFake(client, "Alex", 14, -18), mmFake(client, "Steve", -20, 6), mmFake(client, "Far", 400, -250) };
                        client.options.playerListKey.setPressed(true); } }
            case 6 -> { if (secs > 2.0) { capture(client, "mm-07-tab-radar-players.png"); mmNext(now, 7);
                        m.names.boolValue = true; m.orientation.modeValue = F; } }
            case 7 -> { if (secs > 1.0) { capture(client, "mm-08-names-follow.png"); mmNext(now, 8);
                        client.options.playerListKey.setPressed(false); m.names.boolValue = false; m.orientation.modeValue = N;
                        if (mmFakes != null) for (var f : mmFakes) if (f != null) client.world.removeEntity(f.getId(), net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                        devCmd(client, "tp @s ~640 ~40 ~"); } }
            case 8 -> { if (secs > 6.0 && settled || secs > 30.0) { capture(client, "mm-09-after-640-teleport.png"); mmNext(now, 9);
                        devCmd(client, "execute in minecraft:the_nether run tp @s 0 80 0"); } }
            case 9 -> { if (secs > 5.0) { capture(client, "mm-10-nether-placeholder.png"); mmNext(now, 10);
                        devCmd(client, "execute in minecraft:overworld run tp @s 0 140 0"); } }
            case 10 -> { if (secs > 6.0 && settled || secs > 30.0) { capture(client, "mm-11-back-overworld.png"); mmNext(now, 20);
                        client.setScreen(new dev.s1mp1e.client.gui.MinimapScreen()); } }
            // full-screen map (M): as opened, then zoomed out with the wheel, then closed
            case 20 -> { if (secs > 1.5) { capture(client, "mm-11b-fullscreen.png"); mmNext(now, 21);
                        if (client.currentScreen instanceof dev.s1mp1e.client.gui.MinimapScreen ms)
                            ms.mouseScrolled(client.getWindow().getScaledWidth() / 2.0, client.getWindow().getScaledHeight() / 2.0, -6); } }
            case 21 -> { if (secs > 1.5) { capture(client, "mm-11c-fullscreen-zoomed-out.png"); mmNext(now, 22);
                        if (client.currentScreen instanceof dev.s1mp1e.client.gui.MinimapScreen ms) ms.close(); } }
            case 22 -> { if (secs > 0.6) {
                        System.out.println("[S1mp1e][DevShot] fullscreen map closed: screen=" + client.currentScreen);
                        mmNext(now, 11); m.setEnabled(false); } }
            case 11 -> { if (secs > 2.5) {
                        System.out.println("[S1mp1e][DevShot] minimap after module off: texture allocated=" + dev.s1mp1e.client.minimap.MinimapData.hasTexture()
                                + " active=" + dev.s1mp1e.client.minimap.MinimapData.active);
                        capture(client, "mm-12-module-off.png");
                        // ToggleSprint: one tap of the sprint key latches it; the glass pill says so
                        Module ts = ModuleManager.byName("ToggleSprint");
                        if (ts != null) ts.setEnabled(true);
                        client.options.sprintKey.setPressed(true);
                        client.options.sprintKey.setPressed(false);
                        mmNext(now, 12); } }
            case 12 -> { if (secs > 1.0) {
                        System.out.println("[S1mp1e][DevShot] toggle sprint latched=" + client.options.sprintKey.isPressed());
                        capture(client, "ts-01-sprint-toggled-pill.png");
                        phase = P_STOP; } }
            default -> phase = P_STOP;
        }
    }

    private static void mmNext(long now, int stage) { mmStage = stage; mmT0 = now; }

    /** A client-only player at (dx, dz) from the player, on the same height, for the minimap marker check. */
    private static net.minecraft.client.network.OtherClientPlayerEntity mmFake(MinecraftClient client, String name, double dx, double dz) {
        try {
            net.minecraft.client.network.OtherClientPlayerEntity rp = new net.minecraft.client.network.OtherClientPlayerEntity(client.world,
                    new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(("mm-" + name).getBytes()), name), null);   // 1.19.2: + public key
            rp.setId(-0x5EED0000 - Math.floorMod(name.hashCode(), 0xFFFF));   // client-only id, far from server ids
            rp.refreshPositionAndAngles(client.player.getX() + dx, client.player.getY(), client.player.getZ() + dz, 0f, 0f);
            rp.resetPosition();
            client.world.addEntity(rp.getId(), rp);   // 1.20.1: addEntity(id, entity)
            return rp;
        } catch (Throwable t) { skip("minimap fake player " + name, t); return null; }
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
            // 1.19.2 world creation: pass a concrete DynamicRegistryManager + GeneratorOptions.
            // The built-in registry manager carries the vanilla dimension types / biomes / world
            // presets, and the FLAT preset builds a superflat GeneratorOptions from the same manager
            // (both must come from the one instance so the dimensions stay consistent).
            DynamicRegistryManager.Immutable drm = DynamicRegistryManager.BUILTIN.get();
            WorldPreset flat = drm.get(Registry.WORLD_PRESET_KEY).getOrThrow(minimapMode ? WorldPresets.DEFAULT : WorldPresets.FLAT);
            GeneratorOptions gen = flat.createGeneratorOptions(12345L, false /* structures */, false /* bonus chest */);
            LevelInfo info = new LevelInfo("devshot", GameMode.SURVIVAL, false, Difficulty.PEACEFUL,
                    true /* cheats */, new GameRules(), DataPackSettings.SAFE_MODE);
            client.createIntegratedServerLoader().createAndStart("devshot", info, drm, gen);
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
                // 60 s Speed I, icon on but no particles (keeps the reference frame clean).
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
                // Screens sweep: several effects (beneficial/harmful/ambient) so the ONE glass strip shows its
                // faint separators between entries (5 total keeps the vanilla 33 px spacing, four separators).
                if ("screens".equals(mode)) {
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 1800, 1, false, false, true));
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 0, false, false, true));
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 1200, 0, true, false, true));
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 600, 0, false, false, true));
                }
                // Creative game mode so the real CreativeInventoryScreen (fused tabs + item grid + glass scrollbar)
                // renders instead of falling back to the survival inventory (PORT_SPEC §5 DevShot limitation).
                if ("screens".equals(mode) || "tabs".equals(mode) || "scroll".equals(mode)) {
                    try { sp.changeGameMode(GameMode.CREATIVE); } catch (Throwable ignored) {}
                }
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
