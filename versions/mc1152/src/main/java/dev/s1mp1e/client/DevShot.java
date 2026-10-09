package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/**
 * DevShot v2 — deterministic screenshot harness for cross-version visual comparison, with an
 * optional per-scene BURST mode for flicker diagnosis.
 *
 * <p><b>Completely inert unless the environment variable {@code S1MP1E_SHOT} names an output
 * folder</b> (the shot pipeline) or {@code S1MP1E_AUDIT} is set (the one-shot mixin audit). A normal
 * game run never touches any of this. Environment variables reach the forked game JVM of
 * {@code runClient}; a {@code -D} system property does not, which is why this reads
 * {@link System#getenv}.
 *
 * <h3>Burst mode (flicker diagnosis)</h3>
 * <ul>
 *   <li>{@code S1MP1E_SHOT_BURST=N} — every scene, besides its single reference PNG, also saves N
 *       captured frames as {@code <scene>_burst_000.png ...}. That is what lets a metric compare a
 *       glass region frame-to-frame on a held-static scene and tell real flicker apart from
 *       legitimate animation (title panorama, held-item bob).</li>
 *   <li>{@code S1MP1E_SHOT_BURST_GAP=G} (default 2) — number of native (un-captured) frames left to
 *       run between two captured frames. The mc1152 glass flicker is a high-frame-rate effect (the
 *       {@code SceneCapture} 3&nbsp;ms grab de-dup only starts folding grabs once frames are
 *       &lt;~3&nbsp;ms apart); a {@code glReadPixels} readback stalls the pipeline and, if done every
 *       frame, inflates the gap so the next frame's grab is NOT folded — hiding the flicker. Running
 *       G native frames between captures keeps each captured frame's grab-gap at native timing, so
 *       each captured frame is a faithful sample of the native-rate flicker state. G≥1 with an odd
 *       stride (G+1) also avoids period-2 aliasing.</li>
 * </ul>
 * Only the readback runs per captured frame; the PNG encode is deferred to the end of the burst. The
 * per-capture delta and the pre-burst native frame time (median of a small ring) are both logged.
 *
 * <p>Settle waits are WALL-CLOCK, not frame counts, so a scene is equally settled at 60&nbsp;fps and
 * at 500&nbsp;fps (a frame count would under-settle a fast scene — e.g. capture mid open-fade).
 *
 * <p>Scenes (in order): {@code title}, {@code config} (settings over title), {@code world},
 * {@code config-world}, {@code pause} (GameMenuScreen over world), {@code inventory} (survival, no
 * hover), {@code inventory-tooltip} (survival, cursor over the diamond-sword slot so the glass
 * tooltip is up), {@code creative} (creative inventory, cursor over the first item so a tooltip is
 * up), {@code options} (video settings), {@code social} (Social Interactions glass panel — feature A),
 * {@code effects-wide} (survival inventory with 5 effects: the one continuous glass strip + separators —
 * feature F) and {@code effects-tooltip} (a far-right item whose long tooltip flips left and overlaps the
 * effect strip AND the item grid — R1: glass card on the very top, refracting the GUI beneath). Every step
 * is guarded so a failure only skips that step, and a global watchdog quits the game so a stuck wait can
 * never hang the run.
 */
public final class DevShot {
    private DevShot() {}

    // Wall-clock settle durations (ms) before each capture.
    private static final long SETTLE_MS       = 1200L;  // screens: open-fade + spring settle
    private static final long WORLD_SETTLE_MS = 2500L;  // chunks render + loadout appears
    private static final long TIP_SETTLE_MS   = 1300L;  // tooltip fade-in + panel morph

    /** Per-wait frame caps so one stuck wait skips rather than eating the whole run. */
    private static final int WAIT_WORLD_CAP  = 4000;
    private static final int WAIT_SCREEN_CAP = 600;
    private static final int CREATIVE_PREP_CAP = 600;

    /** Global watchdog: quit no matter what after this long. */
    private static final long WATCHDOG_MS = 900_000L;

    /** Target reference resolution forced onto the framebuffer so shots are 1280x720. */
    private static final int SHOT_W = 1280, SHOT_H = 720;

    // Slot offsets (GUI px) RELATIVE to the screen's own this.x/this.y, so a status-effect side
    // panel shifting the screen right does not throw the cursor off the slot.
    private static final int INV_SLOT_DX = 16, INV_SLOT_DY = 150;  // survival hotbar slot 0 (sword)
    private static final int CRE_SLOT_DX = 17, CRE_SLOT_DY = 26;   // creative grid item (0,0)
    private static final int EFF_SLOT_DX = 160, EFF_SLOT_DY = 150; // survival hotbar slot 8 (far right)

    // State machine phases.
    private static final int P_INIT = 0, P_WAIT_TITLE = 1, P_TITLE = 2,
                             P_WAIT_CONFIG = 3, P_CONFIG = 4,
                             P_WAIT_WORLD = 5, P_WORLD_SETTLE = 6, P_WORLD = 7,
                             P_WAIT_CONFIG2 = 8, P_CONFIG_WORLD = 9,
                             P_WAIT_PAUSE = 10, P_PAUSE = 11,
                             P_WAIT_INV = 12, P_INV = 13,
                             P_INV_TIP_SETTLE = 14, P_INV_TIP = 15,
                             P_CREATIVE_PREP = 16, P_WAIT_CREATIVE = 17, P_CREATIVE_SETTLE = 18, P_CREATIVE = 19,
                             P_WAIT_OPTIONS = 20, P_OPTIONS = 21,
                             P_WAIT_SOCIAL = 22, P_SOCIAL = 23,
                             P_EFFECTS_PREP = 24, P_WAIT_EFFECTS = 25, P_EFFECTS_SETTLE = 26, P_EFFECTS = 27,
                             P_EFFECTS_TIP_SETTLE = 28, P_EFFECTS_TIP = 29,
                             // BATCH-A stage 2: fused-tab / scrollbar / glide verification (B/C/D).
                             P_CRE_SCROLL_PREP = 30, P_CRE_SCROLL_WAIT = 31, P_CRE_SCROLL = 32,
                             P_STONE_PREP = 33, P_STONE = 34,
                             P_LOOM_PREP = 35, P_LOOM = 36,
                             P_MERCH_PREP = 37, P_MERCH = 38,
                             // BATCH-B stage 3: HUD overlays (G) + modules (H).
                             P_HUD_PREP = 39, P_HUD = 40,
                             P_HUD_CHATIN_PREP = 41, P_HUD_CHATIN = 42,
                             P_HUD_TAB_PREP = 43, P_HUD_TAB = 44,
                             P_MOD_PREP = 45, P_MOD_OUTLINE8 = 46, P_MOD_OUTLINE1 = 47,
                             P_MOD_CHROMA_A = 48, P_MOD_CHROMA_B = 49, P_MOD_FILL = 50,
                             P_MOD_HUDCHROMA_PREP = 51, P_MOD_HUDCHROMA_A = 52, P_MOD_HUDCHROMA_B = 53,
                             P_MOD_HUDCHROMA_FLAT = 54,
                             P_MOD_CONFIG_BO = 55, P_MOD_CONFIG_CH = 56, P_MOD_RESTORE = 57,
                             P_COMBAT = 9090, P_STOP = 58, P_DONE = 59,
                             // Verifier round: clicks (D), tab motion (B), held lens (C), tooltip overlaps (E),
                             // advancements / stats / book / lectern / death / anvil (A).
                             P_CLICKS = 60, P_TABS = 61, P_LENS = 62, P_TIP_SCROLL = 63, P_TIP_STRIP = 64,
                             P_SCREENS = 65,
                             // VERIFY sweeps of the 2026-10 port round (DevShotVerify, the neighbour lines' scene
                             // framework: settings / sodium / trans / gap / newmenu / newanim / vcombat, or any of its
                             // sweeps behind a "v:" prefix).
                             P_VERIFY2 = 120,
                             // settings / sodium modes: the page over the TITLE background before the world exists
                             P_TITLE_PAGE = 121,
                             // INTRO mode (S1MP1E_SHOT_MODE=intro): boot brand-intro frames -> after-title -> world-entry
                             // loop preview -> real world entry loop -> after-world. Inert otherwise.
                             P_LOOPPREV = 130,
                             // all-glass #1-#26 verification modes (DevAudit / DevAllGlass / DevMenus steppers)
                             P_AUDIT = 210, P_ALLGLASS = 211, P_MENUS = 212;

    /** S1MP1E_SHOT_MODE, lower-cased ("" = this line's own full pipeline). */
    private static String mode = "";

    // ---- intro mode state (S1MP1E_SHOT_MODE=intro) ----
    private static boolean introMode;     // boot brand-intro capture path active
    private static int     introCount;    // boot overlay frame index
    private static long    introLastMs;   // last boot-overlay capture time (ms spacing)
    private static boolean introSeen;     // the boot SplashScreen was seen at least once
    private static boolean introWorld;    // after boot capture: continue title -> loop -> world -> after-world
    private static int     wlCount;       // world-entry loop (real LevelLoadingScreen) frame index
    private static long    wlLastMs;
    private static long    lpLastMs;      // loop-preview frame spacing
    private static int     lpCount;       // loop-preview frame index
    /** The framebuffer size currently forced (dev sweeps shrink it for the small-window shots, then restore). */
    private static int curW = SHOT_W, curH = SHOT_H;

    /** Change the forced framebuffer size (dev sweeps only). */
    static void setTarget(int w, int h) { curW = w; curH = h; }

    private static boolean resolved;
    private static File    outDir;
    private static boolean auditPending;
    private static long    startMs;
    private static int     phase = P_INIT;
    private static int     frames;      // for wait caps only
    private static long    tPhase;      // wall-clock time the current settle phase was entered
    private static boolean busy;
    private static boolean worldTried;

    // ---- burst state -------------------------------------------------------
    private static int          burstN;       // 0 => no burst
    private static int          burstGap = 2; // native frames between captures
    private static boolean      burstRunning;
    private static int          burstIdx;
    private static int          burstGapLeft;
    private static NativeImage[] burstImgs;
    private static String[]     burstNames;
    private static long[]       burstNanos;

    // ---- frame-timing ring (native fps probe) ------------------------------
    private static final int   DT_RING = 16;
    private static final long[] dtRing = new long[DT_RING];
    private static int          dtCount;
    private static long         lastFrameNanos;

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
                    tPhase = startMs;
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
                String b = System.getenv("S1MP1E_SHOT_BURST");
                if (b != null && !b.trim().isEmpty()) burstN = Math.max(0, Math.min(200, Integer.parseInt(b.trim())));
                String g = System.getenv("S1MP1E_SHOT_BURST_GAP");
                if (g != null && !g.trim().isEmpty()) burstGap = Math.max(0, Math.min(30, Integer.parseInt(g.trim())));
                if (burstN > 0) System.out.println("[S1mp1e][DevShot] burst mode N=" + burstN + " gap=" + burstGap);
            } catch (Throwable t) {
                burstN = 0;
            }
            try {
                String m = System.getenv("S1MP1E_SHOT_MODE");
                mode = m == null ? "" : m.trim().toLowerCase(java.util.Locale.ROOT);
                introMode = outDir != null && "intro".equals(mode);
            } catch (Throwable t) {
                mode = "";
            }
        }

        if (auditPending) {
            auditPending = false;
            runAudit();
        }

        if (outDir == null || client == null) return;

        // Intro mode runs before the normal state machine: it shoots the boot SplashScreen (brand intro) frame by
        // frame, then hands off (introWorld) to the title -> loop-preview -> world-entry path below.
        if (introMode) {
            if (busy) return;
            busy = true;
            try {
                stepIntro(client);
            } catch (Throwable t) {
                System.out.println("[S1mp1e][DevShot] intro capture error: " + t);
                introMode = false; introWorld = true; goPhase(P_INIT);
            } finally {
                busy = false;
            }
            return;
        }

        // Frame-timing ring — updated every frame BEFORE the busy guard.
        long fnow = System.nanoTime();
        if (lastFrameNanos != 0L) { dtRing[dtCount % DT_RING] = fnow - lastFrameNanos; dtCount++; }
        lastFrameNanos = fnow;

        boolean longSweep = DevShotVerify.handles(mode) || "audit".equals(mode) || "allglass".equals(mode)
                || "menus".equals(mode);
        long watchdog = longSweep ? 1_800_000L : WATCHDOG_MS;   // the verify / all-glass sweeps run long
        if (phase != P_DONE && startMs > 0 && System.currentTimeMillis() - startMs > watchdog) {
            System.out.println("[S1mp1e][DevShot] watchdog fired (" + (watchdog / 1000)
                    + "s) at phase " + phase + " — quitting.");
            try { client.scheduleStop(); } catch (Throwable ignored) {}
            phase = P_DONE;
            return;
        }

        if (busy) return;   // ignore re-entrant frames pumped inside a heavy step
        busy = true;
        try {
            forceFramebuffer(client);
            switch (phase) {
                case P_INIT:            stepInit(client);                        break;
                case P_WAIT_TITLE:      stepWaitTitle(client);                   break;
                case P_TITLE:           stepTitle(client);                       break;
                case P_WAIT_CONFIG:     stepWaitConfig(client, P_CONFIG);        break;
                case P_CONFIG:          stepConfig(client);                      break;
                case P_WAIT_WORLD:      stepWaitWorld(client);                   break;
                case P_WORLD_SETTLE:    stepWorldSettle(client);                 break;
                case P_COMBAT:          if (CombatShot.step(client)) goPhase(P_STOP);  break;
                case P_WORLD:           stepWorld(client);                       break;
                case P_WAIT_CONFIG2:    stepWaitConfig(client, P_CONFIG_WORLD);  break;
                case P_CONFIG_WORLD:    stepConfigWorld(client);                 break;
                case P_WAIT_PAUSE:      stepWaitPause(client);                   break;
                case P_PAUSE:           stepPause(client);                       break;
                case P_WAIT_INV:        stepWaitInventory(client);               break;
                case P_INV:             stepInventory(client);                   break;
                case P_INV_TIP_SETTLE:  stepInvTipSettle(client);                break;
                case P_INV_TIP:         stepInvTip(client);                      break;
                case P_CREATIVE_PREP:   stepCreativePrep(client);                break;
                case P_WAIT_CREATIVE:   stepWaitCreative(client);                break;
                case P_CREATIVE_SETTLE: stepCreativeSettle(client);              break;
                case P_CREATIVE:        stepCreative(client);                    break;
                case P_WAIT_OPTIONS:    stepWaitOptions(client);                 break;
                case P_OPTIONS:         stepOptions(client);                     break;
                case P_WAIT_SOCIAL:     stepWaitSocial(client);                  break;
                case P_SOCIAL:          stepSocial(client);                      break;
                case P_EFFECTS_PREP:    stepEffectsPrep(client);                 break;
                case P_WAIT_EFFECTS:    stepWaitEffects(client);                 break;
                case P_EFFECTS_SETTLE:  stepEffectsSettle(client);               break;
                case P_EFFECTS:         stepEffects(client);                     break;
                case P_EFFECTS_TIP_SETTLE: stepEffectsTipSettle(client);         break;
                case P_EFFECTS_TIP:     stepEffectsTip(client);                  break;
                case P_CRE_SCROLL_PREP: stepCreScrollPrep(client);              break;
                case P_CRE_SCROLL_WAIT: stepCreScrollWait(client);              break;
                case P_CRE_SCROLL:      stepCreScroll(client);                  break;
                case P_STONE_PREP:      stepStonePrep(client);                  break;
                case P_STONE:           stepStone(client);                      break;
                case P_LOOM_PREP:       stepLoomPrep(client);                   break;
                case P_LOOM:            stepLoom(client);                       break;
                case P_MERCH_PREP:      stepMerchPrep(client);                  break;
                case P_MERCH:           stepMerch(client);                      break;
                case P_HUD_PREP:        stepHudPrep(client);                    break;
                case P_HUD:             stepHud(client);                        break;
                case P_HUD_CHATIN_PREP: stepHudChatInPrep(client);             break;
                case P_HUD_CHATIN:      stepHudChatIn(client);                  break;
                case P_HUD_TAB_PREP:    stepHudTabPrep(client);                 break;
                case P_HUD_TAB:         stepHudTab(client);                     break;
                case P_MOD_PREP:        stepModPrep(client);                    break;
                case P_MOD_OUTLINE8:    stepModOutline8(client);                break;
                case P_MOD_OUTLINE1:    stepModOutline1(client);                break;
                case P_MOD_CHROMA_A:    stepModChromaA(client);                 break;
                case P_MOD_CHROMA_B:    stepModChromaB(client);                 break;
                case P_MOD_FILL:        stepModFill(client);                    break;
                case P_MOD_HUDCHROMA_PREP: stepModHudChromaPrep(client);        break;
                case P_MOD_HUDCHROMA_A: stepModHudChromaA(client);              break;
                case P_MOD_HUDCHROMA_B: stepModHudChromaB(client);              break;
                case P_MOD_HUDCHROMA_FLAT: stepModHudChromaFlat(client);        break;
                case P_MOD_CONFIG_BO:   stepModConfigBO(client);                break;
                case P_MOD_CONFIG_CH:   stepModConfigCH(client);                break;
                case P_MOD_RESTORE:     stepModRestore(client);                 break;
                case P_CLICKS:          stepClicks(client);                     break;
                case P_TABS:            stepTabs(client);                       break;
                case P_LENS:            stepLens(client);                       break;
                case P_TIP_SCROLL:      stepTipScroll(client);                  break;
                case P_TIP_STRIP:       stepTipStrip(client);                   break;
                case P_SCREENS:         stepScreens(client);                    break;
                case P_VERIFY2:         if (DevShotVerify.step(client)) goPhase(P_STOP); break;
                case P_AUDIT:           if (DevAudit.step(client)) goPhase(P_STOP);     break;
                case P_ALLGLASS:        if (DevAllGlass.step(client)) goPhase(P_STOP);  break;
                case P_MENUS:           if (DevMenus.step(client)) goPhase(P_STOP);     break;
                case P_TITLE_PAGE:      stepTitlePage(client);                  break;
                case P_LOOPPREV:        stepLoopPreview(client);                break;
                case P_STOP:            stepStop(client);                        break;
                default:                break;
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] fatal error at phase " + phase + ": " + t);
            t.printStackTrace();
            goPhase(P_STOP);
        } finally {
            busy = false;
        }
    }

    // ---- phase / settle helpers -------------------------------------------

    private static void goPhase(int p) { phase = p; frames = 0; tPhase = System.currentTimeMillis(); }
    private static boolean settled(long ms) { return System.currentTimeMillis() - tPhase >= ms; }

    private static void forceFramebuffer(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            if (win.getFramebufferWidth() == curW && win.getFramebufferHeight() == curH) return;
            java.lang.reflect.Field fw = net.minecraft.client.util.Window.class.getDeclaredField("framebufferWidth");
            java.lang.reflect.Field fh = net.minecraft.client.util.Window.class.getDeclaredField("framebufferHeight");
            fw.setAccessible(true); fh.setAccessible(true);
            fw.setInt(win, curW); fh.setInt(win, curH);
            client.onResolutionChanged();
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + curW + "x" + curH);
        } catch (Throwable t) {
            skip("force framebuffer " + curW + "x" + curH, t);
        }
    }

    // ---- title -------------------------------------------------------------

    private static void stepInit(MinecraftClient client) {
        try {
            long handle = client.getWindow().getHandle();
            GLFW.glfwRestoreWindow(handle);
            GLFW.glfwSetWindowSize(handle, 1280, 720);
            client.options.guiScale = 2;
            client.options.pauseOnLostFocus = false;
            // Harness only: swap without vsync. With the display asleep (unattended night runs) a vsynced swap is
            // throttled to ~4 frames a second, which turns every motion burst into a slide show and every frame-counted
            // wait into minutes. Only the window's swap interval is changed - the enableVsync OPTION is left alone, so
            // nothing is written to options.txt; the frame limiter (maxFps) still paces the loop.
            try { client.getWindow().setVsync(false); } catch (Throwable ignored) {}
            // No tutorial hints over the shots (a fresh dev world starts the movement tutorial and its toast sits on
            // top of every screen). Dev run directory only.
            try { client.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE); } catch (Throwable ignored) {}
            LanguageDefinition cur = client.getLanguageManager().getLanguage();
            if (cur == null || !"zh_tw".equals(cur.getCode())) {
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
        goPhase(P_WAIT_TITLE);
    }

    private static void stepWaitTitle(MinecraftClient client) {
        if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
            goPhase(P_TITLE);
        } else if (++frames > WAIT_SCREEN_CAP * 4) {
            skip("wait title screen", new IllegalStateException("title never shown"));
            goPhase(P_TITLE);
        }
    }

    private static void stepTitle(MinecraftClient client) {
        // menus mode (#26 all-glass): shoot the world-less menus from the title, never entering a world.
        if ("menus".equals(mode)) {
            if (!settled(SETTLE_MS)) return;
            if (DevMenus.step(client)) goPhase(P_STOP);
            return;
        }
        if (introWorld) {
            if (!settled(SETTLE_MS)) return;
            // Glass buttons must still render after the boot intro ran during the first resource reload.
            capture(client, "after-title.png");
            // A dev world can load too fast to see a whole loop cycle: preview the world-entry loop on its own
            // screen first, then do the real world entry (its LevelLoadingScreen carries the same loop).
            open(client, new LoopPreview(), "open world-entry loop preview");
            lpLastMs = 0L; lpCount = 0; goPhase(P_LOOPPREV);
            return;
        }
        if (burstRunning) { if (!afterMain(client, "title")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "title.png");
            if (!afterMain(client, "title")) return;
        }
        open(client, new S1mp1eConfigScreen(), "open settings (over title)");
        goPhase(P_WAIT_CONFIG);
    }

    private static void stepWaitConfig(MinecraftClient client, int next) {
        if (client.currentScreen instanceof S1mp1eConfigScreen) {
            goPhase(next);
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait settings screen", new IllegalStateException("settings screen never opened"));
            goPhase(next);
        }
    }

    private static void stepConfig(MinecraftClient client) {
        if (burstRunning) { if (!afterMain(client, "config")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "config.png");
            if (!afterMain(client, "config")) return;
        }
        close(client);
        if (mode.contains("settings")) {          // the settings shell over the title background
            open(client, new net.minecraft.client.gui.screen.SettingsScreen(new TitleScreen(), client.options),
                    "open options (over title)");
            titlePageShot = "st-title.png";
            goPhase(P_TITLE_PAGE);
            return;
        }
        startWorld(client);
    }

    private static String titlePageShot;

    private static void startWorld(MinecraftClient client) {
        if (createWorld(client)) {
            goPhase(P_WAIT_WORLD);
        } else {
            skip("create world", new IllegalStateException("world creation did not start"));
            goPhase(P_STOP);
        }
    }

    private static void stepTitlePage(MinecraftClient client) {
        if (!settled(1500L)) return;
        capture(client, titlePageShot);
        if ("sd-title.png".equals(titlePageShot) && mode.contains("settings")) {   // both asked for: the shell page too
            close(client);
            open(client, new net.minecraft.client.gui.screen.SettingsScreen(new TitleScreen(), client.options),
                    "open options (over title)");
            titlePageShot = "st-title.png";
            goPhase(P_TITLE_PAGE);
            return;
        }
        if ("st-title.png".equals(titlePageShot)) {   // 1.15.2: the resource pack screen (two lists) over the title
            close(client);
            open(client, new net.minecraft.client.gui.screen.resourcepack.ResourcePackOptionsScreen(new TitleScreen(),
                    client.options), "open resource packs (over title)");
            titlePageShot = "st-title-packs.png";
            goPhase(P_TITLE_PAGE);
            return;
        }
        close(client);
        startWorld(client);
    }

    /** Dev-only screen that plays the world-entry loop ({@code BrandIntro.MODE_LOOP}) on pure black, exactly as
     *  {@code LevelLoadingScreen} does under the glass mixin, for as long as it is open. */
    private static final class LoopPreview extends Screen {
        private final long t0 = System.nanoTime();
        LoopPreview() { super(new LiteralText("loop preview")); }
        @Override public void render(int mx, int my, float d) {
            net.minecraft.client.gui.DrawableHelper.fill(0, 0, this.width, this.height, 0xFF000000);
            DevShotVerify.loopPreview((System.nanoTime() - t0) / 1.0E9F);
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
        startWorld(client);
    }

    /**
     * Boot brand-intro capture: while the {@code SplashScreen} is up, shoot the framebuffer ~33 fps into
     * {@code intro_NNN.png}; once it is gone (or it never appeared within 20 s) continue through the title (glass must
     * still be intact after the intro ran during the reload) into the world-entry loop, shooting on the way.
     */
    private static void stepIntro(MinecraftClient client) {
        forceFramebuffer(client);
        Object ov = client.getOverlay();
        long now = System.currentTimeMillis();
        if (ov instanceof net.minecraft.client.gui.screen.SplashScreen) {
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
            introMode = false;
            introWorld = true;
            goPhase(P_INIT);
        }
    }

    // ---- world -------------------------------------------------------------

    private static void stepWaitWorld(MinecraftClient client) {
        // Intro mode: while the real world-entry LevelLoadingScreen is up (it carries the MODE_LOOP loop under the
        // glass mixin), grab a ~20 fps strip so the seamless loop can be judged frame by frame.
        if (introWorld && client.currentScreen instanceof net.minecraft.client.gui.screen.LevelLoadingScreen) {
            long ms = System.currentTimeMillis();
            if (ms - wlLastMs >= 50 && wlCount < 400) { wlLastMs = ms; capture(client, String.format("wl_%03d.png", wlCount++)); }
        }
        if (client.world != null && client.player != null && client.getServer() != null
                && client.currentScreen == null) {
            goPhase(P_WORLD_SETTLE);
        } else if (++frames > WAIT_WORLD_CAP) {
            skip("wait world load", new IllegalStateException("world never became ready"));
            goPhase(P_STOP);
        }
    }

    private static void stepWorldSettle(MinecraftClient client) {
        if (!settled(WORLD_SETTLE_MS)) return;
        applyWorldSetup(client);
        if (introWorld) {                   // glass HUD must be intact in game too after the intro; then finish
            System.out.println("[S1mp1e][DevShot] world-entry loop frames: " + wlCount);
            introWorld = false;
            introAfterWorld = true;
            goPhase(P_WORLD);
            return;
        }
        if ("combat".equals(mode)) { goPhase(P_COMBAT); return; }   // 26.2 combat trio
        if ("allglass".equals(mode)) { goPhase(P_ALLGLASS); return; }   // all-glass in-world elements (#12-#23)
        if ("audit".equals(mode)) { goPhase(P_AUDIT); return; }         // sweep every vanilla screen
        if (DevShotVerify.handles(mode)) {  // the 1.21.1 feature-set sweeps (2026-10 port round)
            DevShotVerify.init(outDir, mode);
            goPhase(P_VERIFY2);
            return;
        }
        goPhase(P_WORLD);
    }

    private static boolean introAfterWorld;

    private static void stepWorld(MinecraftClient client) {
        if (introAfterWorld) {
            if (!settled(SETTLE_MS)) return;
            capture(client, "after-world.png");
            goPhase(P_STOP);
            return;
        }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        try {
            ChatHud chat = client.inGameHud.getChatHud();
            if (chat != null) chat.clear(false);
        } catch (Throwable ignored) {}
        if (burstRunning) { if (!afterMain(client, "world")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "world.png");
            if (!afterMain(client, "world")) return;
        }
        // S1MP1E_SHOT_FROM=<clicks|tabs|lens|tips|screens|stone|hud|mod>: dev iteration shortcut — jump straight from the
        // world scene to that sweep (every sweep stages its own game mode / screen). Unset = the full run.
        int from = fromPhase();
        if (from >= 0) { goPhase(from); return; }
        open(client, new S1mp1eConfigScreen(), "open settings (over world)");
        goPhase(P_WAIT_CONFIG2);
    }

    private static void stepConfigWorld(MinecraftClient client) {
        if (burstRunning) { if (!afterMain(client, "config-world")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "config-world.png");
            if (!afterMain(client, "config-world")) return;
        }
        close(client);
        open(client, new GameMenuScreen(true), "open pause menu");
        goPhase(P_WAIT_PAUSE);
    }

    // ---- pause -------------------------------------------------------------

    private static void stepWaitPause(MinecraftClient client) {
        if (client.currentScreen instanceof GameMenuScreen) {
            goPhase(P_PAUSE);
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait pause screen", new IllegalStateException("pause menu never opened"));
            goPhase(P_PAUSE);
        }
    }

    private static void stepPause(MinecraftClient client) {
        if (burstRunning) { if (!afterMain(client, "pause")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "pause.png");
            if (!afterMain(client, "pause")) return;
        }
        close(client);
        // S1MP1E_SHOT_RECIPE=1: open the inventory WITH the recipe book showing (flicker diagnosis of
        // the recipe-book glass, which renders before the container panel in InventoryScreen.render).
        if (System.getenv("S1MP1E_SHOT_RECIPE") != null) {
            try {
                client.player.getRecipeBook().setGuiOpen(true);
            } catch (Throwable t) { skip("open recipe book", t); }
        }
        open(client, new InventoryScreen(client.player), "open inventory");
        goPhase(P_WAIT_INV);
    }

    // ---- inventory (no hover) ---------------------------------------------

    private static void stepWaitInventory(MinecraftClient client) {
        if (client.currentScreen instanceof InventoryScreen) {
            goPhase(P_INV);
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait inventory screen", new IllegalStateException("inventory never opened"));
            goPhase(P_INV);
        }
    }

    private static void stepInventory(MinecraftClient client) {
        setMouseGui(client, 4, 4);   // park OFF any slot: no tooltip (single grab site)
        if (burstRunning) { if (!afterMain(client, "inventory")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "inventory.png");
            if (!afterMain(client, "inventory")) return;
        }
        goPhase(P_INV_TIP_SETTLE);
    }

    // ---- inventory WITH tooltip -------------------------------------------

    private static void stepInvTipSettle(MinecraftClient client) {
        hoverSlot(client, INV_SLOT_DX, INV_SLOT_DY);
        if (settled(TIP_SETTLE_MS)) goPhase(P_INV_TIP);
    }

    private static void stepInvTip(MinecraftClient client) {
        hoverSlot(client, INV_SLOT_DX, INV_SLOT_DY);
        if (burstRunning) { if (!afterMain(client, "inventory-tooltip")) return; }
        else {
            if (!settled(0)) return;
            capture(client, "inventory-tooltip.png");
            if (!afterMain(client, "inventory-tooltip")) return;
        }
        close(client);
        // Switch to creative so the creative inventory screen stays open.
        try { client.interactionManager.setGameMode(GameMode.CREATIVE); } catch (Throwable t) { skip("set creative mode", t); }
        goPhase(P_CREATIVE_PREP);
    }

    // ---- creative WITH tooltip --------------------------------------------

    private static void stepCreativePrep(MinecraftClient client) {
        try { client.interactionManager.setGameMode(GameMode.CREATIVE); } catch (Throwable ignored) {}
        boolean creative = false;
        try { creative = client.interactionManager.getCurrentGameMode() == GameMode.CREATIVE; } catch (Throwable ignored) {}
        if ((creative && settled(300)) || ++frames > CREATIVE_PREP_CAP) {
            try {
                client.openScreen(new CreativeInventoryScreen(client.player));
            } catch (Throwable t) {
                skip("open creative inventory", t);
            }
            goPhase(P_WAIT_CREATIVE);
        }
    }

    private static void stepWaitCreative(MinecraftClient client) {
        if (client.currentScreen instanceof CreativeInventoryScreen) {
            goPhase(P_CREATIVE_SETTLE);
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait creative screen", new IllegalStateException("creative inventory never opened"));
            goPhase(P_CREATIVE_SETTLE);
        }
    }

    private static void stepCreativeSettle(MinecraftClient client) {
        hoverSlot(client, CRE_SLOT_DX, CRE_SLOT_DY);
        if (settled(TIP_SETTLE_MS)) goPhase(P_CREATIVE);
    }

    private static void stepCreative(MinecraftClient client) {
        hoverSlot(client, CRE_SLOT_DX, CRE_SLOT_DY);
        if (burstRunning) { if (!afterMain(client, "creative")) return; }
        else {
            if (!settled(0)) return;
            capture(client, "creative.png");
            if (!afterMain(client, "creative")) return;
        }
        try {
            Screen parent = client.currentScreen;
            client.openScreen(new VideoOptionsScreen(parent, client.options));
        } catch (Throwable t) {
            skip("open video settings", t);
        }
        goPhase(P_WAIT_OPTIONS);
    }

    // ---- video settings ----------------------------------------------------

    private static void stepWaitOptions(MinecraftClient client) {
        if (client.currentScreen instanceof VideoOptionsScreen) {
            goPhase(P_OPTIONS);
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait video settings screen", new IllegalStateException("video settings never opened"));
            goPhase(P_OPTIONS);
        }
    }

    private static void stepOptions(MinecraftClient client) {
        if (burstRunning) { if (!afterMain(client, "options")) return; }
        else {
            if (!settled(SETTLE_MS)) return;
            capture(client, "options.png");
            if (!afterMain(client, "options")) return;
        }
        close(client);
        goPhase(P_EFFECTS_PREP);   // 1.15.2: no social interactions screen (1.16+) - straight on
    }

    // ---- social interactions (A) ------------------------------------------

    private static void stepWaitSocial(MinecraftClient client) { goPhase(P_EFFECTS_PREP); }

    private static void stepSocial(MinecraftClient client) { goPhase(P_EFFECTS_PREP); }

    // ---- status-effect strip (F) + tooltip over it (E / R1) ---------------

    private static void stepEffectsPrep(MinecraftClient client) {
        // Force SURVIVAL so the survival InventoryScreen (with the wide effect strip on its left) opens —
        // the previous scene left the player in CREATIVE, where a survival-inventory open is redirected.
        try { client.interactionManager.setGameMode(GameMode.SURVIVAL); } catch (Throwable ignored) {}
        boolean survival = false;
        try { survival = client.interactionManager.getCurrentGameMode() == GameMode.SURVIVAL; } catch (Throwable ignored) {}
        if (!((survival && settled(300)) || ++frames > CREATIVE_PREP_CAP)) return;

        giveEffects(client);
        // A far-right hotbar item with a very long name: its tooltip flips left and clamps to x=4,
        // spanning most of the width, so a single still shows the glass tooltip overlapping BOTH the
        // effect strip (R1c) AND the item grid + stack counts (R1a) at once.
        try {
            ItemStack longName = new ItemStack(Items.NETHER_STAR);
            longName.setCount(5);
            longName.setCustomName(new LiteralText(
                    "左右都要一樣 Liquid Glass Tooltip Over The Effect Strip And Items"));
            client.player.inventory.setInvStack(8, longName);   // hotbar slot 8 (far right)
        } catch (Throwable t) { skip("give long-name item", t); }
        open(client, new InventoryScreen(client.player), "open inventory (effects)");
        goPhase(P_WAIT_EFFECTS);
    }

    private static void stepWaitEffects(MinecraftClient client) {
        if (client.currentScreen instanceof InventoryScreen) {
            goPhase(P_EFFECTS_SETTLE);
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait effects inventory", new IllegalStateException("inventory never opened"));
            goPhase(P_EFFECTS_SETTLE);
        }
    }

    private static void stepEffectsSettle(MinecraftClient client) {
        setMouseGui(client, 4, 4);           // park off any slot: clean strip, no tooltip
        if (settled(SETTLE_MS)) goPhase(P_EFFECTS);
    }

    private static void stepEffects(MinecraftClient client) {
        setMouseGui(client, 4, 4);
        if (burstRunning) { if (!afterMain(client, "effects-wide")) return; }
        else {
            if (!settled(0)) return;
            capture(client, "effects-wide.png");   // F: one continuous strip + separators, no hover
            if (!afterMain(client, "effects-wide")) return;
        }
        goPhase(P_EFFECTS_TIP_SETTLE);
    }

    private static void stepEffectsTipSettle(MinecraftClient client) {
        hoverSlot(client, EFF_SLOT_DX, EFF_SLOT_DY);
        if (settled(TIP_SETTLE_MS)) goPhase(P_EFFECTS_TIP);
    }

    private static void stepEffectsTip(MinecraftClient client) {
        hoverSlot(client, EFF_SLOT_DX, EFF_SLOT_DY);
        if (burstRunning) { if (!afterMain(client, "effects-tooltip")) return; }
        else {
            if (!settled(0)) return;
            capture(client, "effects-tooltip.png");  // E/R1: card on top, refracts strip + items beneath
            if (!afterMain(client, "effects-tooltip")) return;
        }
        close(client);
        goPhase(P_CRE_SCROLL_PREP);
    }

    /** Give the (server + client) player a spread of effects so the strip shows several entries + separators. */
    private static void giveEffects(MinecraftClient client) {
        ServerPlayerEntity sp = null;
        try {
            MinecraftServer server = client.getServer();
            if (server != null && !server.getPlayerManager().getPlayerList().isEmpty())
                sp = server.getPlayerManager().getPlayerList().get(0);
        } catch (Throwable ignored) {}
        StatusEffectInstance[] fx = {
                new StatusEffectInstance(StatusEffects.SPEED,        6000,   0, false, true,  true),  // beneficial
                new StatusEffectInstance(StatusEffects.HASTE,        6000,   1, false, true,  true),  // beneficial
                new StatusEffectInstance(StatusEffects.STRENGTH,     6000,   0, false, true,  true),  // beneficial
                new StatusEffectInstance(StatusEffects.POISON,       6000,   0, false, true,  true),  // harmful
                new StatusEffectInstance(StatusEffects.NIGHT_VISION, 999999, 0, true,  false, true),  // ambient / long
        };
        for (StatusEffectInstance e : fx) {
            try { if (sp != null) sp.addStatusEffect(new StatusEffectInstance(e)); } catch (Throwable ignored) {}
            try { client.player.addStatusEffect(e); } catch (Throwable ignored) {}
        }
    }

    // ---- (B/C/D) fused tabs · glass scrollbar · sub-pixel glide verification -----------------------

    private static boolean s1mp1e$sceneStill;   // per-scene: the reference still has been captured
    private static int     s1mp1e$strip;        // filmstrip frame index

    /** Open the creative screen for the glide filmstrip (its item grid is populated + scrollable). */
    private static void stepCreScrollPrep(MinecraftClient client) {
        try { client.interactionManager.setGameMode(GameMode.CREATIVE); } catch (Throwable ignored) {}
        boolean creative = false;
        try { creative = client.interactionManager.getCurrentGameMode() == GameMode.CREATIVE; } catch (Throwable ignored) {}
        if (!((creative && settled(300)) || ++frames > CREATIVE_PREP_CAP)) return;
        try { client.openScreen(new CreativeInventoryScreen(client.player)); } catch (Throwable t) { skip("open creative (scroll)", t); }
        s1mp1e$sceneStill = false; s1mp1e$strip = 0;
        goPhase(P_CRE_SCROLL_WAIT);
    }

    private static void stepCreScrollWait(MinecraftClient client) {
        if (client.currentScreen instanceof CreativeInventoryScreen) goPhase(P_CRE_SCROLL);
        else if (++frames > WAIT_SCREEN_CAP) { skip("wait creative (scroll)", new IllegalStateException("no creative")); goPhase(P_STONE_PREP); }
    }

    /** D: one wheel step over the creative grid, then N consecutive frames showing the decelerating sub-pixel glide,
     *  then a settled scrolled still. */
    private static void stepCreScroll(MinecraftClient client) {
        if (!(client.currentScreen instanceof CreativeInventoryScreen)) { goPhase(P_STONE_PREP); return; }
        if (!s1mp1e$sceneStill) {
            if (!settled(400)) return;
            if (!glideStrip(client, "creative", 6, -8.0)) return;
            s1mp1e$sceneStill = true;
            return;
        }
        if (!settled(500)) return;                       // let the glide settle
        capture(client, "creative-scrolled.png");        // C/D at rest: thumb moved, grid row-aligned
        goPhase(P_STONE_PREP);
    }

    /** Stage a stonecutter with a stone input so the recipe list (and scrollbar) populate. */
    private static void stepStonePrep(MinecraftClient client) {
        close(client);
        if (!openStonecutter(client)) { goPhase(P_LOOM_PREP); return; }
        s1mp1e$sceneStill = false; s1mp1e$strip = 0;
        goPhase(P_STONE);
    }

    private static void stepStone(MinecraftClient client) {
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.StonecutterScreen)) { goPhase(P_LOOM_PREP); return; }
        if (!s1mp1e$sceneStill) {
            if (!settled(600)) return;
            capture(client, "stonecutter.png");          // A/C: glass panel + recipe list + glass scrollbar
            s1mp1e$sceneStill = true;
            return;
        }
        if (!glideStrip(client, "stonecutter", 6, -6.0)) return;
        goPhase(P_LOOM_PREP);
    }

    /** Stage a loom with a banner + dye so the pattern grid (and scrollbar) can populate. */
    private static void stepLoomPrep(MinecraftClient client) {
        close(client);
        if (!openLoom(client)) { goPhase(P_MERCH_PREP); return; }
        s1mp1e$sceneStill = false; s1mp1e$strip = 0;
        goPhase(P_LOOM);
    }

    private static void stepLoom(MinecraftClient client) {
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.LoomScreen)) { goPhase(P_MERCH_PREP); return; }
        if (!s1mp1e$sceneStill) {
            if (!settled(600)) return;
            capture(client, "loom.png");                 // A/C: glass panel + pattern grid + glass scrollbar
            s1mp1e$sceneStill = true;
            return;
        }
        if (!glideStrip(client, "loom", 6, -6.0)) return;
        goPhase(P_MERCH_PREP);
    }

    /** Stage a villager merchant with 12 synthetic trades so the trade list scrolls. */
    private static void stepMerchPrep(MinecraftClient client) {
        close(client);
        if (!openMerchant(client)) { goPhase(P_CLICKS); return; }
        s1mp1e$sceneStill = false; s1mp1e$strip = 0;
        goPhase(P_MERCH);
    }

    private static void stepMerch(MinecraftClient client) {
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen)) { s1mp1e$sub = 0; goPhase(P_CLICKS); return; }
        if (!s1mp1e$sceneStill) {
            if (!settled(600)) return;
            capture(client, "merchant.png");             // C: trade list + glass scrollbar
            s1mp1e$sceneStill = true;
            return;
        }
        if (!glideStrip(client, "merchant", 6, -4.0)) return;
        close(client);
        s1mp1e$sub = 0;
        goPhase(P_CLICKS);
    }

    /** One wheel step over the list centre on the first frame, then {@code count} consecutive captured frames
     *  {@code <base>-glide-N.png}. Returns true when the filmstrip is complete. */
    private static boolean glideStrip(MinecraftClient client, String base, int count, double scrollAmt) {
        if (s1mp1e$strip == 0) {
            setMouseGui(client, 640, 360);
            try { if (client.currentScreen != null) client.currentScreen.mouseScrolled(640, 360, scrollAmt); }
            catch (Throwable ignored) {}
        }
        if (s1mp1e$strip < count) {
            capture(client, base + "-glide-" + s1mp1e$strip + ".png");
            s1mp1e$strip++;
            return false;
        }
        s1mp1e$strip = 0;
        return true;
    }

    // ==== verifier round: staged list screens · clicks (D) · tab motion (B) · held lens (C) · tooltip overlaps (E) ·
    //      advancements / statistics / book / lectern / death / anvil (A).  All inert without S1MP1E_SHOT. ==========

    private static int s1mp1e$sub;          // sub-stage within the current sweep
    private static int s1mp1e$subF;         // frames spent in the current sub-stage
    private static int clkPass, clkFail;
    private static double s1mp1e$mx, s1mp1e$my;   // virtual cursor of the lens drag

    private static void subGo(int s) { s1mp1e$sub = s; s1mp1e$subF = 0; tPhase = System.currentTimeMillis(); }

    /** Reference still + (S1MP1E_SHOT_BURST) the static-scene flicker burst. False while burst frames are pending. */
    private static boolean shot(MinecraftClient client, String base) {
        if (!burstRunning) capture(client, base + ".png");
        return afterMain(client, base);
    }

    /** S1MP1E_SHOT_FROM → the sweep to jump to after the world scene (-1 = full run). */
    private static int fromPhase() {
        try {
            String f = System.getenv("S1MP1E_SHOT_FROM");
            if (f == null || f.trim().isEmpty()) return -1;
            s1mp1e$sub = 0; s1mp1e$subF = 0;
            switch (f.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "clicks":  return P_CLICKS;
                case "tabs":    return P_TABS;
                case "lens":    return P_LENS;
                case "tips":    return P_TIP_SCROLL;
                case "screens": return P_SCREENS;
                case "stone":   return P_STONE_PREP;
                case "hud":     return P_HUD_PREP;
                case "mod":     return P_MOD_PREP;
                default:        return -1;
            }
        } catch (Throwable t) { return -1; }
    }

    // ---- reflection helpers (dev runtime is yarn-named) ----

    private static java.lang.reflect.Field fld(Class<?> c, String name) throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static int getI(Object o, Class<?> c, String name) {
        try { return fld(c, name).getInt(o); } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    private static boolean getZ(Object o, Class<?> c, String name) {
        try { return fld(c, name).getBoolean(o); } catch (Throwable t) { return false; }
    }

    /** {x, y, backgroundWidth, backgroundHeight} of the open HandledScreen. */
    private static int[] origin(Screen s) {
        try {
            Class<?> hs = net.minecraft.client.gui.screen.ingame.ContainerScreen.class;
            return new int[] { fld(hs, "x").getInt(s), fld(hs, "y").getInt(s),
                    fld(hs, "containerWidth").getInt(s), fld(hs, "containerHeight").getInt(s) };   // 1.15.2 yarn names
        } catch (Throwable t) { return new int[] { 232, 97, 176, 166 }; }
    }

    private static void gameMode(MinecraftClient client, GameMode mode) {
        try {
            if (client.interactionManager.getCurrentGameMode() != mode) {
                runCmd(client, "gamerule sendCommandFeedback false");   // keep "game mode changed" out of the chat
                runCmd(client, "gamemode " + (mode == GameMode.CREATIVE ? "creative" : "survival") + " @p");
                client.interactionManager.setGameMode(mode);
                try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { skip("game mode " + mode, t); }
    }

    private static int creativeTab() {
        return getI(null, CreativeInventoryScreen.class, "selectedTab");
    }

    private static void selectCreativeTab(MinecraftClient client, int index) {
        try {
            java.lang.reflect.Method m = CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab",
                    net.minecraft.item.ItemGroup.class);
            m.setAccessible(true);
            m.invoke(client.currentScreen, net.minecraft.item.ItemGroup.GROUPS[index]);
        } catch (Throwable t) { skip("select creative tab " + index, t); }
    }

    /** Creative inventory open on tab {@code tabIndex}; true once it is the current screen on that tab. */
    private static boolean creativeOn(MinecraftClient client, int tabIndex) {
        gameMode(client, GameMode.CREATIVE);
        try { if (client.interactionManager.getCurrentGameMode() != GameMode.CREATIVE) return false; }
        catch (Throwable ignored) {}
        if (!(client.currentScreen instanceof CreativeInventoryScreen)) {
            open(client, new CreativeInventoryScreen(client.player), "open creative");
            return false;
        }
        if (creativeTab() != tabIndex) { selectCreativeTab(client, tabIndex); return false; }
        return true;
    }

    /** Creative scroll position -> vanilla field + the handler's visible rows (keeps clicks/hit-test in sync). */
    private static void setCreativeScroll(Screen s, float v) {
        try {
            fld(CreativeInventoryScreen.class, "scrollPosition").setFloat(s, v);
            ((CreativeInventoryScreen.CreativeContainer)
                    ((CreativeInventoryScreen) s).getContainer()).scrollItems(v);
        } catch (Throwable t) { skip("set creative scroll", t); }
    }

    /** Centre of the creative glass thumb (row-aligned ratio, like CreativeGlassMixin: x+175+6, top y+18, travel 97). */
    private static double[] creativeThumb(Screen s) {
        try {
            int[] o = origin(s);
            java.util.List<ItemStack> items = ((CreativeInventoryScreen.CreativeContainer)
                    ((CreativeInventoryScreen) s).getContainer()).itemList;
            int rc = Math.max(0, (items.size() + 8) / 9 - 5);
            float sp = fld(CreativeInventoryScreen.class, "scrollPosition").getFloat(s);
            int row = rc <= 0 ? 0 : Math.max(0, Math.min(rc, (int) (sp * rc + 0.5f)));
            float ratio = rc <= 0 ? 0f : (float) row / rc;
            return new double[] { o[0] + 181, o[1] + 18 + ratio * 97f + 7.5 };
        } catch (Throwable t) { return new double[] { 413, 115 }; }
    }

    /** Centre (GUI px) of a fused-band tab cell (GlassTabs: 6 equal cells, band GlassTabs.BAND above / below the panel). */
    private static double[] tabCell(Screen s, int col, boolean top) {
        int[] o = origin(s);
        double c = o[2] / 6.0;
        double hb = dev.s1mp1e.glass.render.GlassTabs.BAND / 2.0;
        return new double[] { o[0] + (col + 0.5) * c, top ? o[1] - hb : o[1] + o[3] + hb };
    }

    // ---- staged list screens (client-only handlers; the server ignores their syncId) ----

    /**
     * Stonecutter with a stone input. The screen is built FIRST so its content listener is registered when the stack
     * lands (canCraft). No vanilla 1.15.2 stonecutter input has more than 12 recipes (stone 7, blackstone 12), so
     * shouldScroll() (count &gt; 12) would never turn true with vanilla data; the client-side recipe list is padded to
     * 21 by repeating it — harness staging only — so the glass scrollbar, the sub-pixel glide and the click sweep run.
     */
    private static boolean openStonecutter(MinecraftClient client) {
        try {
            net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
            net.minecraft.container.StonecutterContainer h = new net.minecraft.container.StonecutterContainer(1, inv);
            net.minecraft.client.gui.screen.ingame.StonecutterScreen screen =
                    new net.minecraft.client.gui.screen.ingame.StonecutterScreen(h, inv, new LiteralText("Stonecutter"));
            h.getSlot(0).setStack(new ItemStack(Items.STONE, 64));
            java.util.List<net.minecraft.recipe.StonecuttingRecipe> list = h.getAvailableRecipes();
            java.util.List<net.minecraft.recipe.StonecuttingRecipe> base = new java.util.ArrayList<>(list);
            while (!base.isEmpty() && list.size() < 21)
                list.addAll(base.subList(0, Math.min(base.size(), 21 - list.size())));
            client.openScreen(screen);
            System.out.println("[S1mp1e][DevShot] stonecutter recipes = " + h.getAvailableRecipeCount()
                    + " (vanilla " + base.size() + ", padded client-side)");
            return true;
        } catch (Throwable t) { skip("open stonecutter", t); return false; }
    }

    /** Loom with banner + dye. Built FIRST so LoomScreen.onInventoryChanged (its handler listener) sees the stacks land
     *  and sets canApplyDyePattern — the earlier staging set them before the listener existed, so the grid stayed empty. */
    private static boolean openLoom(MinecraftClient client) {
        try {
            net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
            net.minecraft.container.LoomContainer h = new net.minecraft.container.LoomContainer(1, inv);
            net.minecraft.client.gui.screen.ingame.LoomScreen screen =
                    new net.minecraft.client.gui.screen.ingame.LoomScreen(h, inv, new LiteralText("Loom"));
            h.getSlot(0).setStack(new ItemStack(Items.WHITE_BANNER));
            h.getSlot(1).setStack(new ItemStack(Items.BLUE_DYE, 8));
            client.openScreen(screen);
            System.out.println("[S1mp1e][DevShot] loom canApplyDyePattern="
                    + getZ(screen, net.minecraft.client.gui.screen.ingame.LoomScreen.class, "canApplyDyePattern"));
            return true;
        } catch (Throwable t) { skip("open loom", t); return false; }
    }

    /** Villager merchant with 12 synthetic trades so the trade list scrolls. */
    private static boolean openMerchant(MinecraftClient client) {
        try {
            net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
            net.minecraft.container.MerchantContainer h = new net.minecraft.container.MerchantContainer(1, inv);
            net.minecraft.village.TraderOfferList offers = new net.minecraft.village.TraderOfferList();
            for (int i = 0; i < 12; i++) {
                offers.add(new net.minecraft.village.TradeOffer(
                        new ItemStack(Items.EMERALD, 1 + i % 9),
                        new ItemStack(i % 2 == 0 ? Items.WHEAT : Items.BREAD, 4 + i),
                        12, 3, 0.05f));
            }
            h.setOffers(offers);
            h.setExperienceFromServer(45);
            h.setLevelProgress(2);
            client.openScreen(new net.minecraft.client.gui.screen.ingame.MerchantScreen(
                    h, inv, new LiteralText("Villager")));
            return true;
        } catch (Throwable t) { skip("open merchant", t); return false; }
    }

    // ---- (D) clicks: at rest AND mid-glide on creative / stonecutter / loom / merchant ----

    private static void clickLog(String label, boolean ok, String detail) {
        if (ok) clkPass++; else clkFail++;
        System.out.println("[S1mp1e][DevShot][CLICKS] " + label + " " + detail + " -> " + (ok ? "PASS" : "FAIL"));
    }

    private static boolean glideFlag(Screen s) {
        if (s instanceof dev.s1mp1e.client.gui.GlassGlideHost) return ((dev.s1mp1e.client.gui.GlassGlideHost) s).s1mp1e$gliding();
        return s != null && getZ(s, s.getClass(), "s1mp1e$sliding");
    }

    /** Creative grid slot {@code index}: the picked cursor stack must be the item in that (row-aligned) slot; mid-glide
     *  the glide must have been active and the click must have snapped it to rest first. */
    private static void clickCreative(MinecraftClient client, Screen s, int index, String label, boolean midGlide) {
        try {
            net.minecraft.client.gui.screen.ingame.ContainerScreen<?> hs = (net.minecraft.client.gui.screen.ingame.ContainerScreen<?>) s;
            net.minecraft.container.Slot slot = hs.getContainer().slots.get(index);
            net.minecraft.item.Item expected = slot.getStack().getItem();
            int[] o = origin(s);
            double mx = o[0] + slot.xPosition + 8, my = o[1] + slot.yPosition + 8;
            boolean before = glideFlag(s);
            setMouseGui(client, mx, my);
            hs.mouseClicked(mx, my, 0);
            boolean after = glideFlag(s);
            ItemStack cur = client.player.inventory.getCursorStack();
            boolean ok = !cur.isEmpty() && cur.getItem() == expected && expected != Items.AIR
                    && (!midGlide || (before && !after));
            clickLog("creative/" + label, ok, "slot " + index + " expected=" + Registry.ITEM.getId(expected)
                    + " got=" + Registry.ITEM.getId(cur.getItem()) + " glideBefore=" + before + " glideAfter=" + after);
            client.player.inventory.setCursorStack(ItemStack.EMPTY);
        } catch (Throwable t) { clickLog("creative/" + label, false, "error " + t); }
    }

    private static void clickStone(MinecraftClient client, Screen s, int cell, String label, boolean midGlide) {
        try {
            Class<?> c = net.minecraft.client.gui.screen.ingame.StonecutterScreen.class;
            int[] o = origin(s);
            double mx = o[0] + 52 + (cell % 4) * 16 + 8, my = o[1] + 14 + (cell / 4) * 18 + 9;
            boolean before = glideFlag(s);
            setMouseGui(client, mx, my);
            s.mouseClicked(mx, my, 0);
            boolean after = glideFlag(s);
            int off = getI(s, c, "scrollOffset");
            int sel = ((net.minecraft.container.StonecutterContainer)
                    ((net.minecraft.client.gui.screen.ingame.ContainerScreen<?>) s).getContainer()).getSelectedRecipe();
            boolean ok = sel == off + cell && (!midGlide || (before && !after));
            clickLog("stonecutter/" + label, ok, "cell " + cell + " scrollOffset=" + off + " expected=" + (off + cell)
                    + " selected=" + sel + " glideBefore=" + before + " glideAfter=" + after);
        } catch (Throwable t) { clickLog("stonecutter/" + label, false, "error " + t); }
    }

    private static void clickLoom(MinecraftClient client, Screen s, int cell, String label, boolean midGlide) {
        try {
            Class<?> c = net.minecraft.client.gui.screen.ingame.LoomScreen.class;
            int[] o = origin(s);
            double mx = o[0] + 60 + (cell % 4) * 14 + 7, my = o[1] + 13 + (cell / 4) * 14 + 7;
            boolean before = glideFlag(s);
            setMouseGui(client, mx, my);
            s.mouseClicked(mx, my, 0);
            boolean after = glideFlag(s);
            int first = getI(s, c, "firstPatternButtonId");
            int sel = ((net.minecraft.container.LoomContainer)
                    ((net.minecraft.client.gui.screen.ingame.ContainerScreen<?>) s).getContainer()).getSelectedPattern();
            boolean ok = sel == first + cell && (!midGlide || (before && !after));
            clickLog("loom/" + label, ok, "cell " + cell + " firstPattern=" + first + " expected=" + (first + cell)
                    + " selected=" + sel + " glideBefore=" + before + " glideAfter=" + after);
        } catch (Throwable t) { clickLog("loom/" + label, false, "error " + t); }
    }

    private static void clickMerchant(MinecraftClient client, Screen s, int row, String label, boolean midGlide) {
        try {
            Class<?> c = net.minecraft.client.gui.screen.ingame.MerchantScreen.class;
            int[] o = origin(s);
            double mx = o[0] + 5 + 44, my = o[1] + 18 + row * 20 + 10;
            boolean before = glideFlag(s);
            setMouseGui(client, mx, my);
            s.mouseClicked(mx, my, 0);
            boolean after = glideFlag(s);
            int start = getI(s, c, "indexStartOffset");                   // indexStartOffset (unnamed in this yarn)
            int sel = getI(s, c, "selectedIndex");                     // selectedIndex (unnamed in this yarn)
            boolean ok = sel == start + row && (!midGlide || (before && !after));
            clickLog("merchant/" + label, ok, "row " + row + " indexStartOffset=" + start + " expected=" + (start + row)
                    + " selected=" + sel + " glideBefore=" + before + " glideAfter=" + after);
        } catch (Throwable t) { clickLog("merchant/" + label, false, "error " + t); }
    }

    /** Sub-stages: 0-3 creative (rest / mid-glide / edge), 4-6 stonecutter, 7-9 loom, 10-12 merchant, 13 report. */
    private static void stepClicks(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        Screen s = client.currentScreen;
        s1mp1e$subF++;
        switch (s1mp1e$sub) {
            case 0:
                if (s1mp1e$subF == 1) { clkPass = 0; clkFail = 0; close(client); }
                if (creativeOn(client, 0)) subGo(1);                       // BUILDING_BLOCKS (scrolls)
                else if (s1mp1e$subF > WAIT_SCREEN_CAP) { skip("clicks: creative", new IllegalStateException("no creative")); subGo(4); }
                return;
            case 1:                                                        // creative at REST
                if (s1mp1e$subF == 1) setCreativeScroll(s, 0f);
                if (!settled(700)) return;
                clickCreative(client, s, 22, "rest", false);
                subGo(2);
                return;
            case 2:                                                        // creative MID-GLIDE (one wheel step)
                if (s1mp1e$subF == 8) {
                    int[] o = origin(s);
                    setMouseGui(client, o[0] + 90, o[1] + 60);
                    s.mouseScrolled(o[0] + 90, o[1] + 60, -1.0);
                }
                if (s1mp1e$subF == 10) clickCreative(client, s, 22, "mid-glide", true);
                if (s1mp1e$subF == 11) capture(client, "clicks-creative.png");
                if (s1mp1e$subF > 30) { setCreativeScroll(s, 0f); subGo(3); }
                return;
            case 3:                                                        // creative EDGE cell (first grid slot) at rest
                if (!settled(600)) return;
                clickCreative(client, s, 0, "edge", false);
                close(client);
                subGo(4);
                return;
            case 4:
                if (s1mp1e$subF == 1) { close(client); if (!openStonecutter(client)) { subGo(7); } return; }
                if (settled(700)) {
                    clickStone(client, s, 5, "rest", false);
                    subGo(5);
                }
                return;
            case 5:
                if (s1mp1e$subF == 4) {
                    int[] o = origin(s);
                    s.mouseScrolled(o[0] + 70, o[1] + 30, -1.0);
                }
                if (s1mp1e$subF == 6) clickStone(client, s, 5, "mid-glide", true);
                if (s1mp1e$subF == 7) capture(client, "clicks-stonecutter.png");
                if (s1mp1e$subF > 30) subGo(6);
                return;
            case 6:
                if (!settled(300)) return;
                clickStone(client, s, 11, "edge", false);
                subGo(7);
                return;
            case 7:
                if (s1mp1e$subF == 1) { close(client); if (!openLoom(client)) { subGo(10); } return; }
                if (settled(700)) {
                    clickLoom(client, s, 6, "rest", false);
                    subGo(8);
                }
                return;
            case 8:
                if (s1mp1e$subF == 4) {
                    int[] o = origin(s);
                    s.mouseScrolled(o[0] + 80, o[1] + 30, -1.0);
                }
                if (s1mp1e$subF == 6) clickLoom(client, s, 6, "mid-glide", true);
                if (s1mp1e$subF == 7) capture(client, "clicks-loom.png");
                if (s1mp1e$subF > 30) subGo(9);
                return;
            case 9:
                if (!settled(300)) return;
                clickLoom(client, s, 15, "edge", false);
                subGo(10);
                return;
            case 10:
                if (s1mp1e$subF == 1) { close(client); if (!openMerchant(client)) { subGo(13); } return; }
                if (settled(700)) {
                    clickMerchant(client, s, 2, "rest", false);
                    subGo(11);
                }
                return;
            case 11:
                if (s1mp1e$subF == 4) {
                    int[] o = origin(s);
                    s.mouseScrolled(o[0] + 40, o[1] + 60, -2.0);
                }
                if (s1mp1e$subF == 6) clickMerchant(client, s, 2, "mid-glide", true);
                if (s1mp1e$subF == 7) capture(client, "clicks-merchant.png");
                if (s1mp1e$subF > 30) subGo(12);
                return;
            case 12:
                if (!settled(300)) return;
                clickMerchant(client, s, 6, "edge", false);
                subGo(13);
                return;
            default:
                System.out.println("[S1mp1e][DevShot][CLICKS] " + clkPass + " PASS / " + clkFail + " FAIL");
                close(client);
                subGo(0);
                goPhase(P_TABS);
        }
    }

    // ---- (B) fused creative tabs: slide within a row, cross-fade across rows, hover pill glide ----

    private static void stepTabs(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        Screen s = client.currentScreen;
        s1mp1e$subF++;
        switch (s1mp1e$sub) {
            case 0:
                setMouseGui(client, 4, 4);
                if (creativeOn(client, 0)) subGo(1);                       // BUILDING_BLOCKS = top row, cell 0
                else if (s1mp1e$subF > WAIT_SCREEN_CAP) { skip("tabs: creative", new IllegalStateException("no creative")); subGo(9); }
                return;
            case 1:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "tabs-a.png");
                selectCreativeTab(client, 3);                              // TRANSPORTATION = top row, cell 3
                subGo(2);
                return;
            case 2:                                                        // SLIDE within the top row
                setMouseGui(client, 4, 4);
                if (s1mp1e$subF <= 6) { capture(client, String.format("tabs-slide-%02d.png", s1mp1e$subF - 1)); return; }
                if (!settled(600)) return;
                capture(client, "tabs-slide-rest.png");
                selectCreativeTab(client, 7);                              // FOOD = bottom row, cell 1 -> row switch
                subGo(3);
                return;
            case 3:                                                        // CROSS-FADE across rows
                setMouseGui(client, 4, 4);
                if (s1mp1e$subF <= 6) { capture(client, String.format("tabs-cross-%02d.png", s1mp1e$subF - 1)); return; }
                if (!settled(600)) return;
                capture(client, "tabs-cross-rest.png");
                subGo(4);
                return;
            case 4: {                                                      // HOVER an unselected top cell -> pill fades in
                double[] c = tabCell(s, 1, true);
                setMouseGui(client, c[0], c[1]);
                if (s1mp1e$subF >= 2 && s1mp1e$subF <= 5) { capture(client, String.format("tabs-hover-%02d.png", s1mp1e$subF - 2)); return; }
                if (!settled(600)) return;
                capture(client, "tabs-hover.png");
                subGo(5);
                return;
            }
            case 5: {                                                      // hover GLIDES to another cell
                double[] c = tabCell(s, 4, true);
                setMouseGui(client, c[0], c[1]);
                if (s1mp1e$subF >= 2 && s1mp1e$subF <= 6) { capture(client, String.format("tabs-hoverglide-%02d.png", s1mp1e$subF - 2)); return; }
                if (!settled(600)) return;
                subGo(6);
                return;
            }
            case 6:                                                        // hover OUT -> pill fades out (150 ms)
                setMouseGui(client, 4, 4);
                if (s1mp1e$subF < 2) return;
                if (s1mp1e$subF >= 2 && s1mp1e$subF <= 4) { capture(client, String.format("tabs-hoverout-%02d.png", s1mp1e$subF - 2)); return; }
                subGo(9);
                return;
            default:
                selectCreativeTab(client, 0);
                close(client);
                subGo(0);
                goPhase(P_LENS);
        }
    }

    // ---- (C) vertical glass scrollbar: rest / mid / held lens / 1:1 drag / rubber band / release ----

    private static void stepLens(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        Screen s = client.currentScreen;
        s1mp1e$subF++;
        switch (s1mp1e$sub) {
            case 0:
                setMouseGui(client, 4, 4);
                if (creativeOn(client, 0)) { setCreativeScroll(client.currentScreen, 0.35f); subGo(1); }
                else if (s1mp1e$subF > WAIT_SCREEN_CAP) { skip("lens: creative", new IllegalStateException("no creative")); subGo(9); }
                return;
            case 1:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "scroll-mid.png");                         // white capsule thumb at rest, mid-track
                {
                    double[] th = creativeThumb(s);
                    s1mp1e$mx = th[0]; s1mp1e$my = th[1];
                    setMouseGui(client, s1mp1e$mx, s1mp1e$my);
                    s.mouseClicked(s1mp1e$mx, s1mp1e$my, 0);               // press the thumb -> scrolling = true
                }
                subGo(2);
                return;
            case 2:                                                        // PRESS: pill morphs into the glass lens
                setMouseGui(client, s1mp1e$mx, s1mp1e$my);
                if (s1mp1e$subF <= 4) { capture(client, String.format("scroll-press-%02d.png", s1mp1e$subF - 1)); return; }
                if (!settled(500)) return;
                capture(client, "scroll-held.png");
                subGo(3);
                return;
            case 3:                                                        // DRAG 1:1 (lens stretches with speed)
                if (s1mp1e$subF <= 6) {
                    s1mp1e$my += 6;
                    setMouseGui(client, s1mp1e$mx, s1mp1e$my);
                    s.mouseDragged(s1mp1e$mx, s1mp1e$my, 0, 0, 6);
                    capture(client, String.format("scroll-drag-%02d.png", s1mp1e$subF - 1));
                    return;
                }
                subGo(4);
                return;
            case 4: {                                                      // RUBBER BAND past the end of the track
                int[] o = origin(s);
                // Pointer below the track end; x moved just right of the panel so neither the drag nor the release
                // lands on a fused tab cell (a release over a tab selects it in vanilla). The vertical bar ignores x.
                s1mp1e$mx = o[0] + o[2] + 12;
                s1mp1e$my = o[1] + 18 + 112 + 28;
                setMouseGui(client, s1mp1e$mx, s1mp1e$my);
                if (s1mp1e$subF == 1) s.mouseDragged(s1mp1e$mx, s1mp1e$my, 0, 0, 20);
                if (!settled(400)) return;
                capture(client, "scroll-rubber.png");
                s.mouseReleased(s1mp1e$mx, s1mp1e$my, 0);                  // RELEASE -> lens back to pill, settle
                subGo(5);
                return;
            }
            case 5:
                setMouseGui(client, s1mp1e$mx, s1mp1e$my);
                if (s1mp1e$subF <= 4) { capture(client, String.format("scroll-release-%02d.png", s1mp1e$subF - 1)); return; }
                if (!settled(700)) return;
                capture(client, "scroll-settled.png");
                subGo(9);
                return;
            default:
                if (s instanceof CreativeInventoryScreen) setCreativeScroll(s, 0f);
                close(client);
                subGo(0);
                goPhase(P_TIP_SCROLL);
        }
    }

    // ---- (E / R1) tooltip card over the glass scrollbar, and across the middle of the effect strip ----

    private static void stepTipScroll(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        s1mp1e$subF++;
        if (s1mp1e$sub == 0) {
            setMouseGui(client, 4, 4);
            if (creativeOn(client, 0)) { setCreativeScroll(client.currentScreen, 0f); subGo(1); }
            else if (s1mp1e$subF > WAIT_SCREEN_CAP) { skip("tip scroll: creative", new IllegalStateException("no creative")); subGo(2); }
            return;
        }
        if (s1mp1e$sub == 1) {
            // grid row 0, col 8 (right edge): the card opens to the right, straight over the glass scrollbar thumb.
            hoverSlot(client, 9 + 8 * 18 + 8, 18 + 8);
            if (!settled(TIP_SETTLE_MS)) return;
            capture(client, "tooltip-scrollbar.png");
            subGo(2);
            return;
        }
        close(client);
        subGo(0);
        goPhase(P_TIP_STRIP);
    }

    private static void stepTipStrip(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        s1mp1e$subF++;
        if (s1mp1e$sub == 0) {
            gameMode(client, GameMode.SURVIVAL);
            boolean survival = false;
            try { survival = client.interactionManager.getCurrentGameMode() == GameMode.SURVIVAL; } catch (Throwable ignored) {}
            if (!(survival && settled(300)) && s1mp1e$subF < CREATIVE_PREP_CAP) return;
            giveEffects(client);
            try {
                // main-inventory rows 0-1 full of counted stacks (count digits under the card) + a long-named item at
                // the right end of row 0 whose card flips LEFT across the items AND the middle of the effect strip.
                for (int i = 9; i < 27; i++) {
                    if (i == 17) continue;
                    client.player.inventory.setInvStack(i, new ItemStack(i % 3 == 0 ? Items.COBBLESTONE
                            : (i % 3 == 1 ? Items.OAK_PLANKS : Items.TORCH), 5 + (i * 7) % 59));
                }
                ItemStack longName = new ItemStack(Items.NETHER_STAR, 7);
                longName.setCustomName(new LiteralText("左右都要一樣 Liquid Glass Tooltip Across The Middle Of The Effect Strip"));
                client.player.inventory.setInvStack(17, longName);
            } catch (Throwable t) { skip("tip strip items", t); }
            open(client, new InventoryScreen(client.player), "open inventory (tip strip)");
            subGo(1);
            return;
        }
        if (s1mp1e$sub == 1) {
            if (!(client.currentScreen instanceof InventoryScreen)) {
                if (s1mp1e$subF > WAIT_SCREEN_CAP) { skip("tip strip inventory", new IllegalStateException("no inventory")); subGo(2); }
                return;
            }
            hoverSlot(client, 8 + 8 * 18 + 8, 84 + 8);                   // main inventory row 0, col 8
            if (!settled(TIP_SETTLE_MS)) return;
            capture(client, "tooltip-strip.png");
            subGo(2);
            return;
        }
        close(client);
        try { for (int i = 9; i < 27; i++) client.player.inventory.setInvStack(i, ItemStack.EMPTY); } catch (Throwable ignored) {}
        subGo(0);
        goPhase(P_SCREENS);
    }

    // ---- (A) advancements / statistics / book / book-edit / lectern / death / anvil ----

    private static ItemStack writtenBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        net.minecraft.nbt.CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", "Liquid Glass");
        tag.putString("author", "S1mp1e");
        tag.putBoolean("resolved", true);
        net.minecraft.nbt.ListTag pages = new net.minecraft.nbt.ListTag();
        pages.add(net.minecraft.nbt.StringTag.of("{\"text\":\"The page is a frosted glass plate under a light "
                + "parchment scrim, so this dark ink stays perfectly readable.\\n\\nThe world still refracts through "
                + "the glass frame around the page.\"}"));
        pages.add(net.minecraft.nbt.StringTag.of("{\"text\":\"Page two.\"}"));
        tag.put("pages", pages);
        return book;
    }

    private static ItemStack writableBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        net.minecraft.nbt.ListTag pages = new net.minecraft.nbt.ListTag();
        pages.add(net.minecraft.nbt.StringTag.of("Book & quill on liquid glass: the parchment scrim keeps the "
                + "editable text dark and readable."));
        book.getOrCreateTag().put("pages", pages);
        return book;
    }

    private static void stepScreens(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        Screen s = client.currentScreen;
        s1mp1e$subF++;
        switch (s1mp1e$sub) {
            case 0:
                close(client);
                gameMode(client, GameMode.SURVIVAL);
                setMouseGui(client, 4, 4);
                runCmd(client, "gamerule sendCommandFeedback false");
                runCmd(client, "gamerule announceAdvancements false");
                runCmd(client, "advancement grant @p only minecraft:story/root");
                runCmd(client, "advancement grant @p only minecraft:story/mine_stone");
                runCmd(client, "advancement grant @p only minecraft:story/upgrade_tools");
                try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
                subGo(1);
                return;
            case 1:
                if (!settled(600)) return;
                open(client, new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                        client.player.networkHandler.getAdvancementHandler()), "open advancements");
                subGo(2);
                return;
            case 2:
                setMouseGui(client, 4, 4);
                if (!burstRunning && !settled(1300)) return;
                if (!shot(client, "advancements")) return;                      // A: tree framed in glass, wooden frame gone
                open(client, new net.minecraft.client.gui.screen.StatsScreen(null, client.player.getStatHandler()),
                        "open statistics");
                subGo(3);
                return;
            case 3:
                setMouseGui(client, 4, 4);
                if (!burstRunning && !settled(1600)) return;
                if (!shot(client, "stats")) return;                             // A: full glass plate + grey scrim
                try {
                    net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget<?> list =
                            ((net.minecraft.client.gui.screen.StatsScreen) s).getSelectedStatList();
                    if (list != null) list.setScrollAmount(list.getScrollAmount() + 57);
                } catch (Throwable t) { skip("scroll stats", t); }
                subGo(4);
                return;
            case 4:
                setMouseGui(client, 4, 4);
                if (!settled(500)) return;
                capture(client, "stats-scrolled.png");                    // rows masked cleanly at the header band
                try {
                    Object items = fld(net.minecraft.client.gui.screen.StatsScreen.class, "itemStats").get(s);
                    ((net.minecraft.client.gui.screen.StatsScreen) s).selectStatList(
                            (net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget<?>) items);
                } catch (Throwable t) { skip("stats item list", t); }
                subGo(5);
                return;
            case 5:
                setMouseGui(client, 4, 4);
                if (!settled(700)) return;
                capture(client, "stats-items.png");
                open(client, new net.minecraft.client.gui.screen.ingame.BookScreen(
                        new net.minecraft.client.gui.screen.ingame.BookScreen.WrittenBookContents(writtenBook())),
                        "open book");
                subGo(6);
                return;
            case 6:
                setMouseGui(client, 4, 4);
                if (!burstRunning && !settled(900)) return;
                if (!shot(client, "book")) return;                              // A: glass page + light parchment scrim
                open(client, new net.minecraft.client.gui.screen.ingame.BookEditScreen(client.player, writableBook(),
                        net.minecraft.util.Hand.MAIN_HAND), "open book edit");
                subGo(7);
                return;
            case 7:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "book-edit.png");
                try {
                    net.minecraft.container.LecternContainer lh = new net.minecraft.container.LecternContainer(1);
                    lh.getSlot(0).setStack(writtenBook());
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.LecternScreen(lh,
                            client.player.inventory, new LiteralText("Lectern")));
                } catch (Throwable t) { skip("open lectern", t); }
                subGo(8);
                return;
            case 8:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "lectern.png");                           // inherits BookScreen.render -> same glass page
                open(client, new net.minecraft.client.gui.screen.DeathScreen(new LiteralText("S1mp1e fell"), false),
                        "open death screen");
                subGo(9);
                return;
            case 9:
                setMouseGui(client, 4, 4);
                if (!settled(1500)) return;
                capture(client, "death.png");                             // no new panel: red vignette + glass buttons
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.AnvilContainer ah = new net.minecraft.container.AnvilContainer(1, inv);
                    ah.getSlot(0).setStack(new ItemStack(Items.STONE));   // input, no output -> vanilla error X
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.AnvilScreen(
                            ah, inv, new LiteralText("Repair & Name")));
                } catch (Throwable t) { skip("open anvil", t); }
                subGo(10);
                return;
            case 10:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "anvil.png");                             // shared body-blit glass: name field + error X kept
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.FurnaceContainer fh = new net.minecraft.container.FurnaceContainer(1, inv);
                    fh.setProperty(0, 800); fh.setProperty(1, 1600);    // burning: flame half full
                    fh.setProperty(2, 110); fh.setProperty(3, 200);     // cooking: arrow ~half
                    fh.getSlot(0).setStack(new ItemStack(Items.IRON_ORE, 12));
                    fh.getSlot(1).setStack(new ItemStack(Items.COAL, 5));
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.FurnaceScreen(fh, inv, new LiteralText("Furnace")));
                } catch (Throwable t) { skip("open furnace", t); }
                subGo(11);
                return;
            case 11:
                setMouseGui(client, 4, 4);
                if (!burstRunning && !settled(900)) return;
                if (!shot(client, "furnace")) return;                           // flame + progress arrow survive on the glass
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.BrewingStandContainer bh = new net.minecraft.container.BrewingStandContainer(1, inv);
                    bh.setProperty(0, 150); bh.setProperty(1, 14);       // brewing progress + fuel
                    bh.getSlot(3).setStack(new ItemStack(Items.NETHER_WART));
                    bh.getSlot(4).setStack(new ItemStack(Items.BLAZE_POWDER, 3));
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.BrewingStandScreen(bh, inv, new LiteralText("Brewing Stand")));
                } catch (Throwable t) { skip("open brewing", t); }
                subGo(12);
                return;
            case 12:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "brewing.png");                           // fuel bar, bubbles, progress kept
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.EnchantingTableContainer eh = new net.minecraft.container.EnchantingTableContainer(1, inv);
                    eh.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
                    eh.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 9));
                    int sharp = Registry.ENCHANTMENT.getRawId(net.minecraft.enchantment.Enchantments.SHARPNESS);
                    eh.setProperty(0, 5); eh.setProperty(1, 15); eh.setProperty(2, 30);
                    eh.setProperty(4, sharp); eh.setProperty(5, sharp); eh.setProperty(6, sharp);
                    eh.setProperty(7, 1); eh.setProperty(8, 2); eh.setProperty(9, 4);
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.EnchantingScreen(eh, inv, new LiteralText("Enchant")));
                } catch (Throwable t) { skip("open enchanting", t); }
                subGo(13);
                return;
            case 13:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "enchanting.png");                        // book model + 3 option buttons kept
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.GenericContainer ch =
                            net.minecraft.container.GenericContainer.createGeneric9x6(1, inv);
                    for (int i = 0; i < 54; i += 5) ch.getSlot(i).setStack(new ItemStack(i % 2 == 0 ? Items.GOLD_INGOT : Items.OAK_LOG, 1 + i));
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(ch, inv, new LiteralText("Large Chest")));
                } catch (Throwable t) { skip("open chest", t); }
                subGo(14);
                return;
            case 14:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "chest6.png");                            // two-strip body -> ONE glass panel
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.GrindstoneContainer gh = new net.minecraft.container.GrindstoneContainer(1, inv);
                    gh.getSlot(0).setStack(new ItemStack(Items.DIRT));      // not grindable -> vanilla error X
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.GrindstoneScreen(gh, inv, new LiteralText("Grindstone")));
                } catch (Throwable t) { skip("open grindstone", t); }
                subGo(15);
                return;
            case 15:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "grindstone.png");
                try {
                    net.minecraft.entity.player.PlayerInventory inv = client.player.inventory;
                    net.minecraft.container.BeaconContainer bh = new net.minecraft.container.BeaconContainer(1, inv);
                    bh.setProperty(0, 4);                                  // pyramid levels -> power buttons active
                    client.openScreen(new net.minecraft.client.gui.screen.ingame.BeaconScreen(bh, inv, new LiteralText("Beacon")));
                } catch (Throwable t) { skip("open beacon", t); }
                subGo(16);
                return;
            case 16:
                setMouseGui(client, 4, 4);
                if (!settled(900)) return;
                capture(client, "beacon.png");                            // payment item icons + power buttons kept
                subGo(17);
                return;
            default:
                close(client);
                subGo(0);
                goPhase(P_HUD_PREP);
        }
    }

    // ---- BATCH-B stage 3: HUD overlays (G) + modules (H) ------------------

    // Module state snapshot so the H sweep restores everything (nothing is ever written to disk).
    private static boolean s1mp1e$modSnapTaken;
    private static boolean s1mp1e$boEnabled, s1mp1e$chEnabled, s1mp1e$fpsEnabled, s1mp1e$coordEnabled, s1mp1e$ksEnabled;
    private static double  s1mp1e$boWidth, s1mp1e$chWave;
    private static int     s1mp1e$boColour, s1mp1e$boFillColour;
    private static boolean s1mp1e$boChroma, s1mp1e$boFill, s1mp1e$chAccents;

    /** Run a server command as the integrated-server source (permission 4). No-op if there is no server. */
    private static void runCmd(MinecraftClient client, String cmd) {
        try {
            net.minecraft.server.MinecraftServer server = client.getServer();
            if (server == null) return;
            server.getCommandManager().execute(server.getCommandSource(), cmd);
        } catch (Throwable t) {
            skip("command: " + cmd, t);
        }
    }

    private static dev.s1mp1e.client.Module mod(String name) {
        return dev.s1mp1e.client.ModuleManager.byName(name);
    }

    private static void setModEnabled(String name, boolean on) {
        try { dev.s1mp1e.client.Module m = mod(name); if (m != null) m.setEnabled(on); }
        catch (Throwable t) { skip("enable " + name, t); }
    }

    private static void setD(String modName, String settingName, double v) {
        try {
            dev.s1mp1e.client.Module m = mod(modName);
            if (m != null) { dev.s1mp1e.client.Setting s = m.setting(settingName); if (s != null) s.doubleValue = v; }
        } catch (Throwable t) { skip("set " + modName + "." + settingName, t); }
    }

    private static void setB(String modName, String settingName, boolean v) {
        try {
            dev.s1mp1e.client.Module m = mod(modName);
            if (m != null) { dev.s1mp1e.client.Setting s = m.setting(settingName); if (s != null) s.boolValue = v; }
        } catch (Throwable t) { skip("set " + modName + "." + settingName, t); }
    }

    private static void setC(String modName, String settingName, int argb) {
        try {
            dev.s1mp1e.client.Module m = mod(modName);
            if (m != null) { dev.s1mp1e.client.Setting s = m.setting(settingName); if (s != null) s.colorValue = argb; }
        } catch (Throwable t) { skip("set " + modName + "." + settingName, t); }
    }

    /** Face the player level (yaw 0 = south / +Z) so a summoned name tag and a targeted block sit ahead. */
    private static void faceLevel(MinecraftClient client, float pitch) {
        try {
            ClientPlayerEntity cp = client.player;
            cp.yaw = 0f; cp.prevYaw = 0f; cp.setHeadYaw(0f); cp.setYaw(0f);
            cp.pitch = pitch; cp.prevPitch = pitch;
        } catch (Throwable t) { skip("face level", t); }
    }

    // ---- G: HUD overlays (chat + bossbar + action bar + name tag + toast in one world frame) ----

    private static void stepHudPrep(MinecraftClient client) {
        close(client);
        // Clear any leftover container close-ghost (from the just-closed merchant) so the world HUD scenes are clean.
        try { dev.s1mp1e.glass.render.PanelGhost.cancel(); } catch (Throwable ignored) {}
        faceLevel(client, 0f);
        // Keep command feedback OUT of chat so the chat panel shows only our own lines (not a flood of
        // "displayed action bar" server-feedback messages).
        runCmd(client, "gamerule sendCommandFeedback false");
        // Chat panel (G1): several lines of varying width so the panel hugs the widest.
        try {
            ChatHud chat = client.inGameHud.getChatHud();
            chat.addMessage(new LiteralText("[S1mp1e] Liquid glass HUD online."));
            chat.addMessage(new LiteralText("<Steve> the chat panel hugs the widest line"));
            chat.addMessage(new LiteralText("<Alex> nice frosted glass"));
            chat.addMessage(new LiteralText("[Server] boss bar + action bar + toasts too"));
            chat.addMessage(new LiteralText("gg"));
        } catch (Throwable t) { skip("add chat messages", t); }
        // Boss bar (G3): a purple bar at ~62%.
        runCmd(client, "bossbar add s1mp1e:demo {\"text\":\"Ender Dragon\"}");
        runCmd(client, "bossbar set s1mp1e:demo color purple");
        runCmd(client, "bossbar set s1mp1e:demo max 100");
        runCmd(client, "bossbar set s1mp1e:demo value 62");
        runCmd(client, "bossbar set s1mp1e:demo players @a");
        runCmd(client, "bossbar set s1mp1e:demo visible true");
        // Name tag (G6): an armor stand ahead of the player.
        runCmd(client, "execute at @p run summon armor_stand ~ ~ ~4 "
                + "{CustomName:'{\"text\":\"S1mp1e Player\"}',CustomNameVisible:1b,NoGravity:1b}");
        // Toast (G4): a system toast card (SystemToastGlassMixin path).
        try {
            SystemToast.show(client.getToastManager(), SystemToast.Type.NARRATOR_TOGGLE,
                    new LiteralText("Advancement Made!"), new LiteralText("Liquid Glass"));
        } catch (Throwable t) { skip("system toast", t); }
        // Action bar (G5): issued once — it stays for ~3 s (60 ticks), well past the settle+capture.
        runCmd(client, "title @a actionbar {\"text\":\"Picked up: Diamond x8\",\"color\":\"aqua\"}");
        goPhase(P_HUD);
    }

    private static void stepHud(MinecraftClient client) {
        // Keep the just-closed merchant's fading close-ghost cleared every frame (harness switches screens fast).
        try { dev.s1mp1e.glass.render.PanelGhost.cancel(); } catch (Throwable ignored) {}
        if (burstRunning) { if (!afterMain(client, "hud")) return; }   // R4: HUD-glass flicker burst
        else {
            if (!settled(700)) return;
            capture(client, "hud.png");   // G: chat panel + boss bar + action bar + name tag + toast, all glass
            if (!afterMain(client, "hud")) return;
        }
        goPhase(P_HUD_CHATIN_PREP);
    }

    private static void stepHudChatInPrep(MinecraftClient client) {
        try { client.openScreen(new ChatScreen("")); } catch (Throwable t) { skip("open chat input", t); }
        goPhase(P_HUD_CHATIN);
    }

    private static void stepHudChatIn(MinecraftClient client) {
        try { dev.s1mp1e.glass.render.PanelGhost.cancel(); } catch (Throwable ignored) {}
        if (!(client.currentScreen instanceof ChatScreen)) { goPhase(P_HUD_TAB_PREP); return; }
        if (!settled(500)) return;
        capture(client, "hud-chatinput.png");   // G1: glass input bar + focused chat panel
        close(client);
        goPhase(P_HUD_TAB_PREP);
    }

    private static void stepHudTabPrep(MinecraftClient client) {
        close(client);
        // A list-slot objective makes the tab list render even in a 1-player singleplayer world.
        runCmd(client, "scoreboard objectives add s1mp1e_tl dummy \"Score\"");
        runCmd(client, "scoreboard objectives setdisplay list s1mp1e_tl");
        runCmd(client, "scoreboard players set @p s1mp1e_tl 42");
        goPhase(P_HUD_TAB);
    }

    private static void stepHudTab(MinecraftClient client) {
        try { dev.s1mp1e.glass.render.PanelGhost.cancel(); } catch (Throwable ignored) {}
        try { pressKey(client.options.keyPlayerList, true); } catch (Throwable ignored) {}
        if (!settled(500)) return;
        capture(client, "hud-tablist.png");   // G2: glass header/list plates + softened row stripes
        try { pressKey(client.options.keyPlayerList, false); } catch (Throwable ignored) {}
        runCmd(client, "scoreboard objectives remove s1mp1e_tl");
        runCmd(client, "bossbar remove s1mp1e:demo");
        goPhase(P_MOD_PREP);
    }

    // ---- H: modules (Block Outline width/chroma/fill + Chroma HUD per-char/flat + config) ----

    private static void snapshotModules() {
        if (s1mp1e$modSnapTaken) return;
        s1mp1e$modSnapTaken = true;
        try {
            dev.s1mp1e.client.Module bo = mod("BlockOutline"), ch = mod("ChromaHud");
            dev.s1mp1e.client.Module fps = mod("FpsHUD"), co = mod("CoordsHUD"), ks = mod("Keystrokes");
            s1mp1e$boEnabled = bo != null && bo.enabled;
            s1mp1e$chEnabled = ch != null && ch.enabled;
            s1mp1e$fpsEnabled = fps != null && fps.enabled;
            s1mp1e$coordEnabled = co != null && co.enabled;
            s1mp1e$ksEnabled = ks != null && ks.enabled;
            if (bo != null) {
                s1mp1e$boWidth = bo.setting("Line width").doubleValue;
                s1mp1e$boColour = bo.setting("Colour").colorValue;
                s1mp1e$boFillColour = bo.setting("Fill colour").colorValue;
                s1mp1e$boChroma = bo.setting("Chroma").boolValue;
                s1mp1e$boFill = bo.setting("Fill").boolValue;
            }
            if (ch != null) {
                s1mp1e$chWave = ch.setting("Wave").doubleValue;
                s1mp1e$chAccents = ch.setting("Accents").boolValue;
            }
        } catch (Throwable t) { skip("module snapshot", t); }
    }

    private static void stepModPrep(MinecraftClient client) {
        close(client);
        snapshotModules();
        // A flat WHITE quartz floor ahead (air above it) so the targeted block contrasts with every outline colour and
        // the translucent fill reads unambiguously (the forest grass made a green fill on green grass hard to confirm).
        runCmd(client, "gamerule sendCommandFeedback false");
        runCmd(client, "execute at @p run fill ~-3 ~ ~-1 ~3 ~3 ~5 minecraft:air");
        runCmd(client, "execute at @p run fill ~-3 ~-1 ~-1 ~3 ~-1 ~5 minecraft:quartz_block");
        faceLevel(client, 72f);   // look down-forward at a ground block so the outline always has a target
        setModEnabled("BlockOutline", true);
        setD("BlockOutline", "Line width", 8.0);
        setC("BlockOutline", "Colour", 0xFF32FF96);   // bright green, custom (not vanilla black)
        setB("BlockOutline", "Chroma", false);
        setB("BlockOutline", "Fill", false);
        goPhase(P_MOD_OUTLINE8);
    }

    private static void stepModOutline8(MinecraftClient client) {
        if (!settled(600)) return;
        capture(client, "outline-w8.png");            // H1: thick (8 px) custom-colour outline
        setD("BlockOutline", "Line width", 1.0);
        goPhase(P_MOD_OUTLINE1);
    }

    private static void stepModOutline1(MinecraftClient client) {
        if (!settled(400)) return;
        capture(client, "outline-w1.png");            // H1: thin (1 px) outline — proves the width setting
        setB("BlockOutline", "Chroma", true);
        goPhase(P_MOD_CHROMA_A);
    }

    private static void stepModChromaA(MinecraftClient client) {
        if (!settled(400)) return;
        capture(client, "outline-chroma-a.png");      // H1: chroma outline frame A
        goPhase(P_MOD_CHROMA_B);
    }

    private static void stepModChromaB(MinecraftClient client) {
        if (!settled(300)) return;
        capture(client, "outline-chroma-b.png");      // H1: chroma outline frame B (hue advanced vs A)
        setB("BlockOutline", "Chroma", false);
        setD("BlockOutline", "Line width", 2.5);
        setB("BlockOutline", "Fill", true);
        setC("BlockOutline", "Fill colour", 0x99FF30C0);   // magenta on the white quartz block: unmistakable
        goPhase(P_MOD_FILL);
    }

    private static void stepModFill(MinecraftClient client) {
        if (!settled(500)) return;
        capture(client, "outline-fill.png");          // H1: translucent depth-tested fill of the targeted block
        setModEnabled("BlockOutline", false);
        goPhase(P_MOD_HUDCHROMA_PREP);
    }

    private static void stepModHudChromaPrep(MinecraftClient client) {
        close(client);
        faceLevel(client, 0f);
        setModEnabled("FpsHUD", true);
        setModEnabled("CoordsHUD", true);
        setModEnabled("Keystrokes", true);
        setModEnabled("ChromaHud", true);
        setD("ChromaHud", "Speed", 1.0);
        setD("ChromaHud", "Saturation", 0.85);
        setD("ChromaHud", "Wave", 0.5);        // per-character diagonal sweep
        setB("ChromaHud", "Accents", true);    // keystroke press highlight follows the chroma too
        goPhase(P_MOD_HUDCHROMA_A);
    }

    private static void stepModHudChromaA(MinecraftClient client) {
        if (!settled(500)) return;
        capture(client, "hud-chroma-a.png");          // H2: per-char rainbow HUD text, frame A
        goPhase(P_MOD_HUDCHROMA_B);
    }

    private static void stepModHudChromaB(MinecraftClient client) {
        if (!settled(300)) return;
        capture(client, "hud-chroma-b.png");          // H2: frame B (hues advanced vs A)
        setD("ChromaHud", "Wave", 0.0);               // uniform hue per string
        goPhase(P_MOD_HUDCHROMA_FLAT);
    }

    private static void stepModHudChromaFlat(MinecraftClient client) {
        if (!settled(400)) return;
        capture(client, "hud-chroma-flat.png");       // H2: wave 0 — one uniform cycling hue
        goPhase(P_MOD_CONFIG_BO);
    }

    private static boolean s1mp1e$cfgOpened;

    private static void stepModConfigBO(MinecraftClient client) {
        // Open the config screen ONCE (re-opening every frame would reset its open-fade and leave the panel invisible),
        // then let the fade settle before capturing.
        if (!s1mp1e$cfgOpened) { openConfigOn(client, "Visual", "BlockOutline"); s1mp1e$cfgOpened = true; return; }
        if (!settled(600)) return;
        capture(client, "config-blockoutline.png");   // H: zh-TW labels (方塊外框 / 線寬 / 彩虹色 / 填充 ...)
        s1mp1e$cfgOpened = false;
        goPhase(P_MOD_CONFIG_CH);
    }

    private static void stepModConfigCH(MinecraftClient client) {
        if (!s1mp1e$cfgOpened) { openConfigOn(client, "HUD", "ChromaHud"); s1mp1e$cfgOpened = true; return; }
        if (!settled(600)) return;
        capture(client, "config-chromahud.png");      // H: zh-TW labels (彩虹 HUD / 速度 / 飽和度 / 字元波動 ...)
        s1mp1e$cfgOpened = false;
        goPhase(P_MOD_RESTORE);
    }

    /** Open the config screen on a category tab with a module selected (reflection into the private screen state). */
    private static void openConfigOn(MinecraftClient client, String category, String moduleName) {
        try {
            S1mp1eConfigScreen screen = new S1mp1eConfigScreen();
            client.openScreen(screen);
            String[] tabs = { "Combat", "HUD", "Visual" };
            int idx = 0; for (int i = 0; i < tabs.length; i++) if (tabs[i].equals(category)) idx = i;
            java.lang.reflect.Field tabF = S1mp1eConfigScreen.class.getDeclaredField("tab");
            tabF.setAccessible(true); tabF.setInt(screen, idx);
            java.lang.reflect.Method rebuild = S1mp1eConfigScreen.class.getDeclaredMethod("rebuildTab");
            rebuild.setAccessible(true); rebuild.invoke(screen);
            dev.s1mp1e.client.Module m = mod(moduleName);
            if (m != null) {
                java.lang.reflect.Method sel = S1mp1eConfigScreen.class.getDeclaredMethod("selectModule", dev.s1mp1e.client.Module.class);
                sel.setAccessible(true); sel.invoke(screen, m);
            }
        } catch (Throwable t) { skip("open config on " + category + "/" + moduleName, t); }
    }

    private static void stepModRestore(MinecraftClient client) {
        close(client);
        // Restore every module value the H sweep changed (nothing was ever written to disk).
        try {
            if (mod("BlockOutline") != null) {
                setD("BlockOutline", "Line width", s1mp1e$boWidth);
                setC("BlockOutline", "Colour", s1mp1e$boColour);
                setC("BlockOutline", "Fill colour", s1mp1e$boFillColour);
                setB("BlockOutline", "Chroma", s1mp1e$boChroma);
                setB("BlockOutline", "Fill", s1mp1e$boFill);
                setModEnabled("BlockOutline", s1mp1e$boEnabled);
            }
            if (mod("ChromaHud") != null) {
                setD("ChromaHud", "Wave", s1mp1e$chWave);
                setB("ChromaHud", "Accents", s1mp1e$chAccents);
                setModEnabled("ChromaHud", s1mp1e$chEnabled);
            }
            setModEnabled("FpsHUD", s1mp1e$fpsEnabled);
            setModEnabled("CoordsHUD", s1mp1e$coordEnabled);
            setModEnabled("Keystrokes", s1mp1e$ksEnabled);
        } catch (Throwable t) { skip("module restore", t); }
        goPhase(P_STOP);
    }

    private static void stepStop(MinecraftClient client) {
        System.out.println("[S1mp1e][DevShot] done, quitting.");
        try { client.scheduleStop(); } catch (Throwable ignored) {}
        phase = P_DONE;
    }

    // ---- burst -------------------------------------------------------------

    /**
     * Called right after a scene's reference PNG. Returns {@code true} when the step may proceed to
     * its transition (no burst, or the burst just finished); {@code false} while burst frames are
     * still being captured (the caller must return and wait for the next rendered frame). Between two
     * captured frames it lets {@link #burstGap} native frames run un-captured so the readback does
     * not bias the flicker state. The scene must be held STATIC across the whole burst.
     */
    private static boolean afterMain(MinecraftClient client, String base) {
        if (burstN <= 0) return true;
        if (!burstRunning) {
            burstRunning = true;
            burstIdx = 0;
            burstImgs  = new NativeImage[burstN];
            burstNames = new String[burstN];
            burstNanos = new long[burstN];
            System.out.println("[S1mp1e][DevShot] burst start '" + base + "' nativeFrame="
                    + String.format("%.2f", medianDtMs()) + "ms (~" + fps(medianDtMs()) + " fps)");
            grabBurst(client, base);
            burstIdx = 1;
            burstGapLeft = burstGap;
            return burstIdx < burstN ? false : finishBurst(base);
        }
        if (burstGapLeft > 0) { burstGapLeft--; return false; }   // native (un-captured) frame
        grabBurst(client, base);
        burstIdx++;
        burstGapLeft = burstGap;
        return burstIdx < burstN ? false : finishBurst(base);
    }

    private static void grabBurst(MinecraftClient client, String base) {
        try {
            Framebuffer fb = client.getFramebuffer();
            burstImgs[burstIdx] = ScreenshotUtils.takeScreenshot(fb.textureWidth, fb.textureHeight, fb);
            burstNames[burstIdx] = base + "_burst_" + String.format("%03d", burstIdx) + ".png";
            burstNanos[burstIdx] = System.nanoTime();
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] burst grab failed " + base + " #" + burstIdx + ": " + t);
            burstNames[burstIdx] = null;
        }
    }

    private static boolean finishBurst(String base) {
        StringBuilder sb = new StringBuilder("[S1mp1e][DevShot] burst '" + base + "' capture dt(ms):");
        for (int i = 0; i < burstN; i++) {
            NativeImage img = burstImgs[i];
            if (img != null && burstNames[i] != null) {
                try { img.writeFile(new File(outDir, burstNames[i])); }
                catch (Throwable t) { System.out.println("[S1mp1e][DevShot] burst write failed " + burstNames[i] + ": " + t); }
                finally { try { img.close(); } catch (Throwable ignored) {} }
            }
            if (i > 0 && burstNanos[i] > 0 && burstNanos[i - 1] > 0)
                sb.append(' ').append(String.format("%.2f", (burstNanos[i] - burstNanos[i - 1]) / 1e6));
        }
        System.out.println(sb.toString());
        System.out.println("[S1mp1e][DevShot] wrote " + burstN + " burst frames for '" + base + "' -> "
                + outDir.getAbsolutePath());
        burstImgs = null; burstNames = null; burstNanos = null;
        burstRunning = false;
        return true;
    }

    private static double medianDtMs() {
        int n = Math.min(dtCount, DT_RING);
        if (n == 0) return 0.0;
        long[] a = new long[n];
        System.arraycopy(dtRing, 0, a, 0, n);
        java.util.Arrays.sort(a);
        return a[n / 2] / 1e6;
    }

    private static int fps(double ms) { return ms <= 0 ? 0 : (int) Math.round(1000.0 / ms); }

    // ---- mouse (dev-only reflection) --------------------------------------

    /** Park the cursor over a slot whose centre is ({@code dxGui},{@code dyGui}) GUI px from the open
     *  {@code HandledScreen}'s own {@code x}/{@code y} origin — robust to the status-effect side panel
     *  shifting the screen right. Falls back to a screen-centre guess if the origin can't be read. */
    private static void hoverSlot(MinecraftClient client, int dxGui, int dyGui) {
        int ox = 232, oy = 97;   // default centred origin (1280x720 gui-scale 2, 176x166 bg)
        try {
            Screen s = client.currentScreen;
            if (s instanceof net.minecraft.client.gui.screen.ingame.ContainerScreen) {
                java.lang.reflect.Field fx = net.minecraft.client.gui.screen.ingame.ContainerScreen.class.getDeclaredField("x");
                java.lang.reflect.Field fy = net.minecraft.client.gui.screen.ingame.ContainerScreen.class.getDeclaredField("y");
                fx.setAccessible(true); fy.setAccessible(true);
                ox = fx.getInt(s); oy = fy.getInt(s);
            }
        } catch (Throwable ignored) {}
        setMouseGui(client, ox + dxGui, oy + dyGui);
    }

    private static void setMouseGui(MinecraftClient client, double guiX, double guiY) {
        try {
            java.lang.reflect.Field mf = MinecraftClient.class.getDeclaredField("mouse");
            mf.setAccessible(true);
            Object mouse = mf.get(client);
            if (mouse == null) return;
            net.minecraft.client.util.Window win = client.getWindow();
            double ww = win.getWidth(),  sw = win.getScaledWidth();
            double wh = win.getHeight(), sh = win.getScaledHeight();
            double mx = (sw <= 0) ? guiX : guiX * ww / sw;
            double my = (sh <= 0) ? guiY : guiY * wh / sh;
            setDouble(mouse, "x", mx);
            setDouble(mouse, "y", my);
        } catch (Throwable t) {
            skip("set mouse", t);
        }
    }

    private static void setDouble(Object o, String name, double v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(o, v);
    }

    // ---- world creation + setup -------------------------------------------

    private static boolean createWorld(MinecraftClient client) {
        if (worldTried) return false;
        worldTried = true;
        try {
            File saves = new File(client.runDirectory, "saves");
            deleteRecursively(new File(saves, "devshot"));
        } catch (Throwable t) {
            skip("delete previous devshot save", t);
        }
        try {
            // seed 12345, survival, no structures, not hardcore, SUPERFLAT (a bright, level, deterministic backdrop
            // for the glass - what this line's harness always used), cheats on.
            LevelInfo info = new LevelInfo(12345L, GameMode.SURVIVAL, false, false, LevelGeneratorType.FLAT)
                    .enableCommands();
            client.startIntegratedServer("devshot", "devshot", info);
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /** 1.15.2: a key binding has no setPressed; the static setter goes through the bound key code. */
    static void pressKey(net.minecraft.client.options.KeyBinding key, boolean down) {
        try {
            net.minecraft.client.options.KeyBinding.setKeyPressed(
                    net.minecraft.client.util.InputUtil.fromName(key.getName()), down);
        } catch (Throwable t) {
            skip("press key " + key.getId(), t);
        }
    }

    private static void applyWorldSetup(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        try {
            server.setDifficulty(Difficulty.PEACEFUL, true);
            ServerWorld ow = server.getWorld(DimensionType.OVERWORLD);
            LevelProperties props = ow.getLevelProperties();
            props.setTimeOfDay(6000L);
            props.setRaining(false);
            props.setThundering(false);
            props.setClearWeatherTime(1_000_000);
        } catch (Throwable t) {
            skip("set time/weather", t);
        }
        try {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayerList().isEmpty()
                    ? null : server.getPlayerManager().getPlayerList().get(0);
            if (sp != null) {
                sp.inventory.setInvStack(0, new ItemStack(Items.DIAMOND_SWORD));
                sp.inventory.setInvStack(1, new ItemStack(Items.COOKED_BEEF, 32));
                sp.inventory.setInvStack(2, new ItemStack(Items.STONE, 64));
                sp.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                sp.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
                sp.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                sp.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
                sp.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
                sp.refreshPositionAndAngles(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
                sp.networkHandler.requestTeleport(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
        try {
            ClientPlayerEntity cp = client.player;
            cp.inventory.setInvStack(0, new ItemStack(Items.DIAMOND_SWORD));
            cp.inventory.setInvStack(1, new ItemStack(Items.COOKED_BEEF, 32));
            cp.inventory.setInvStack(2, new ItemStack(Items.STONE, 64));
            cp.inventory.selectedSlot = 0;
            cp.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            cp.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
            cp.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            cp.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
            cp.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
            cp.yaw = 0f;  cp.prevYaw = 0f;  cp.setHeadYaw(0f);
            cp.pitch = 15f; cp.prevPitch = 15f;
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

    private static void open(MinecraftClient client, Screen screen, String what) {
        try { client.openScreen(screen); } catch (Throwable t) { skip(what, t); }
    }

    private static void close(MinecraftClient client) {
        try { client.openScreen(null); } catch (Throwable ignored) {}
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

    static void capture(MinecraftClient client, String name) {
        NativeImage img = null;
        try {
            Framebuffer fb = client.getFramebuffer();
            img = ScreenshotUtils.takeScreenshot(fb.textureWidth, fb.textureHeight, fb);
            File out = new File(outDir, name);
            img.writeFile(out);
            System.out.println("[S1mp1e][DevShot] wrote " + out.getAbsolutePath()
                    + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] capture failed for " + name + ": " + t);
        } finally {
            if (img != null) { try { img.close(); } catch (Throwable ignored) {} }
        }
    }
}
