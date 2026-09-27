package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.client.module.BlockOutlineModule;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
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
                             // BATCH-A screen sweep (S1MP1E_SHOT_MODE=screens)
                             P_SCR_OPEN = 20, P_SCR_WAIT = 21, P_SCR_SHOT = 22,
                             // BATCH-A tooltip-on-top sweep (S1MP1E_SHOT_MODE=tooltips) and effect-strip sweep (=effects)
                             P_TT = 30, P_FX = 40,
                             // BATCH-A(stage2) creative fused tabs (B) / glass scrollbar (C) / silky glide (D) / clicks (D)
                             P_TABS = 50, P_SCROLL = 60, P_GLIDE = 70, P_CLICKS = 80,
                             // BATCH-B HUD overlays (G, S1MP1E_SHOT_MODE=hud) and modules (H, =modules)
                             P_HUD = 90, P_MODULES = 100,
                             // R4 flicker sweep (S1MP1E_SHOT_MODE=flicker): consecutive-frame burst of a
                             // frame-primary container glass surface (inventory + effect strip) — the exact
                             // surface family that self-sampled/flickered in 1.16.5. No state changes between
                             // captures, so any frame-to-frame delta beyond world drift == a grab-dedup flicker.
                             P_FLICKER = 110;
    /** flicker sweep sub-stage. */
    private static int flkStage;

    /** Screens captured by the "screens" mode, in order. */
    private static final String[] SCR_NAMES = {"creative", "advancements", "social", "stats", "book"};
    private static int scrIdx;

    /** Tooltip-on-top (E) + effect-strip (F) sweeps: their own stage index + a virtual cursor written into MC's mouse. */
    private static int ttStage, fxStage;
    /** Stage-2 sweeps (tabs B / scroll C / glide D / clicks D): their own sub-stage index. */
    private static int s2Stage;
    /** BATCH-B sweeps (hud G / modules H): their own sub-stage index. */
    private static int hudStage, modStage;
    /** Wall-clock gate + one-shot flag for scenes whose animation is time-driven, not frame-driven
     *  (the toast slide-in): frame counts are unreliable because the shot loop's fps is unbounded. */
    private static long hudGateMs;
    private static boolean hudCaptured;
    /** BATCH-B modules sweep: saved module state, restored at the end so nothing persists (spec: snapshot+restore). */
    private static boolean modSnapTaken;
    private static boolean snBoOn, snChOn, snFpsOn, snCoordsOn, snBoChroma, snBoFill;
    private static int snBoColor, snBoFillCol;
    private static double snBoWidth, snBoSpeed, snChSpeed, snChSat, snChWave;
    private static boolean snChAccents;
    /** Lazily-resolved CreativeInventoryScreen private members used by the stage-2 sweeps. */
    private static java.lang.reflect.Field crScrollPos;
    private static java.lang.reflect.Method crSetSelectedTab;
    private static double ttHoverX = -1, ttHoverY = -1;              // GUI px, re-applied every frame while >= 0
    private static java.lang.reflect.Field ttMouseX, ttMouseY;      // net.minecraft.client.Mouse x/y (lazy)

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static String  mode;          // S1MP1E_SHOT_MODE ("screens" adds the BATCH-A screen sweep)
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
    /** The framebuffer size the shots are forced to. Mutable so the effects COMPACT scene can narrow the window (so the
     *  space right of the inventory drops below 120 px → the effect strip falls into its icon-only layout), then restore. */
    private static int curW = SHOT_W, curH = SHOT_H;

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
                String m = System.getenv("S1MP1E_SHOT_MODE");
                mode = m == null ? "" : m.trim();
            } catch (Throwable t) {
                mode = "";
            }
            try {
                String a = System.getenv("S1MP1E_AUDIT");
                auditPending = a != null && !a.trim().isEmpty();
            } catch (Throwable t) {
                auditPending = false;
            }
        }

        // One-shot mixin audit, independent of the shot pipeline.
        if (auditPending) {
            auditPending = false;
            runAudit();
        }

        if (outDir == null || client == null) return;

        // Global watchdog — never hang.
        if (phase != P_DONE && startMs > 0 && System.currentTimeMillis() - startMs > WATCHDOG_MS) {
            System.out.println("[S1mp1e][DevShot] watchdog fired (" + (WATCHDOG_MS / 1000)
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
                case P_STOP:               stepStop(client);                     break;
                case P_SCR_OPEN:           stepScrOpen(client);                  break;
                case P_SCR_WAIT:           stepScrWait(client);                  break;
                case P_SCR_SHOT:           stepScrShot(client);                  break;
                case P_TT:                 stepTooltips(client);                 break;
                case P_FX:                 stepEffects(client);                  break;
                case P_COMBAT:             if (CombatShot.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_TABS:               stepTabs(client);                     break;
                case P_SCROLL:             stepScroll(client);                   break;
                case P_GLIDE:              stepGlide(client);                    break;
                case P_CLICKS:             stepClicks(client);                   break;
                case P_HUD:                stepHud(client);                      break;
                case P_MODULES:            stepModules(client);                  break;
                case P_FLICKER:            stepFlicker(client);                  break;
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
            skip("force framebuffer " + SHOT_W + "x" + SHOT_H, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(MinecraftClient client) {
        try {
            client.getWindow().setWindowedSize(1280, 720);
            client.options.getGuiScale().setValue(2);
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

    private static void stepTitle(MinecraftClient client) {
        if (++frames >= TITLE_FRAMES) {
            capture(client, "title.png");
            open(client, new S1mp1eConfigScreen(), "open settings (over title)");
            frames = 0; phase = P_WAIT_CONFIG;
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
        capture(client, "config.png");
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
        if (client.world != null && client.player != null && client.getServer() != null
                && client.currentScreen == null) {
            frames = 0; phase = P_WORLD_SETTLE;
        } else if (++frames > WAIT_WORLD_CAP) {
            skip("wait world load", new IllegalStateException("world never became ready"));
            phase = P_STOP;
        }
    }

    private static void stepWorldSettle(MinecraftClient client) {
        if (++frames < WORLD_SETTLE) return;
        applyWorldSetup(client);            // time/weather/position/loadout (own try/catch inside)
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
            if ("screens".equalsIgnoreCase(mode)) {
                scrIdx = 0; frames = 0; phase = P_SCR_OPEN;   // BATCH-A screen sweep instead of the base tail
                return;
            }
            if ("tooltips".equalsIgnoreCase(mode)) {
                ttStage = 0; frames = 0; phase = P_TT;        // BATCH-A tooltip-on-top (E) sweep
                return;
            }
            if ("effects".equalsIgnoreCase(mode)) {
                fxStage = 0; frames = 0; phase = P_FX;        // BATCH-A effect-strip (F) sweep
                return;
            }
            if ("combat".equalsIgnoreCase(mode)) { frames = 0; phase = P_COMBAT; return; }   // 26.2 combat trio
            if ("tabs".equalsIgnoreCase(mode))   { s2Stage = 0; frames = 0; phase = P_TABS;   return; }
            if ("scroll".equalsIgnoreCase(mode)) { s2Stage = 0; frames = 0; phase = P_SCROLL; return; }
            if ("glide".equalsIgnoreCase(mode))  { s2Stage = 0; frames = 0; phase = P_GLIDE;  return; }
            if ("clicks".equalsIgnoreCase(mode)) { s2Stage = 0; frames = 0; phase = P_CLICKS; return; }
            if ("hud".equalsIgnoreCase(mode))    { hudStage = 0; frames = 0; phase = P_HUD;     return; }  // BATCH-B G
            if ("modules".equalsIgnoreCase(mode)){ modStage = 0; frames = 0; phase = P_MODULES; return; }  // BATCH-B H
            if ("flicker".equalsIgnoreCase(mode)){ flkStage = 0; frames = 0; phase = P_FLICKER; return; }  // R4 flicker
            open(client, new S1mp1eConfigScreen(), "open settings (over world)");
            frames = 0; phase = P_WAIT_CONFIG2;
        }
    }

    // ---- BATCH-A screen sweep: creative (B tabs) / advancements / social / stats (A) ----

    private static void stepScrOpen(MinecraftClient client) {
        if (scrIdx >= SCR_NAMES.length) { phase = P_STOP; return; }
        String name = SCR_NAMES[scrIdx];
        try {
            net.minecraft.client.gui.screen.Screen screen = buildScreen(client, name);
            if (screen == null) { skip("build " + name, new IllegalStateException("null screen")); scrIdx++; return; }
            client.setScreen(screen);
        } catch (Throwable t) {
            skip("open " + name, t);
            scrIdx++;
            return;
        }
        frames = 0; phase = P_SCR_WAIT;
    }

    private static void stepScrWait(MinecraftClient client) {
        // The screen is current (or a wait cap elapsed) -> settle a few frames then shoot.
        if (client.currentScreen != null || ++frames > WAIT_SCREEN_CAP) {
            frames = 0; phase = P_SCR_SHOT;
        }
    }

    private static void stepScrShot(MinecraftClient client) {
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        if (++frames < 40) return;
        capture(client, SCR_NAMES[scrIdx] + ".png");
        close(client);
        scrIdx++;
        frames = 0; phase = P_SCR_OPEN;
    }

    /** Build one BATCH-A screen by name (each guarded by the caller). */
    private static net.minecraft.client.gui.screen.Screen buildScreen(MinecraftClient client, String name) {
        switch (name) {
            case "creative":
                return new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(
                        client.player, client.player.getWorld().getEnabledFeatures(), false);
            case "advancements":
                return new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                        client.player.networkHandler.getAdvancementHandler());
            case "social":
                return new net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen();
            case "stats":
                return new net.minecraft.client.gui.screen.StatsScreen(
                        client.currentScreen, client.player.getStatHandler());
            case "book": {
                // A 1-page written book so the shot shows dark ink over the light warm parchment scrim (A / BookGlassMixin).
                net.minecraft.client.gui.screen.ingame.BookScreen.Contents bc =
                        new net.minecraft.client.gui.screen.ingame.BookScreen.Contents() {
                            public int getPageCount() { return 1; }
                            public net.minecraft.text.StringVisitable getPageUnchecked(int i) {
                                return net.minecraft.text.StringVisitable.plain(
                                        "液態玻璃書頁\n深色墨水必須\n清晰可讀。\n\nDark ink stays\nreadable on cream.");
                            }
                        };
                return new net.minecraft.client.gui.screen.ingame.BookScreen(bc);
            }
            default:
                return null;
        }
    }

    // ==== BATCH-A tooltip-on-top (E) sweep — S1MP1E_SHOT_MODE=tooltips =================================
    //
    // The glass hover-tooltip card must be the very TOP layer (R1 / spec E): it must refract the GUI drawn beneath it
    // (panel + items + count digits + effect strip) and NOTHING may draw over it. Each scene parks a virtual cursor
    // (writing net.minecraft.client.Mouse x/y the way 26.2's ttApplyHover writes the MouseHandler, so the real OS cursor
    // is not moved) on an item whose card overlaps other items / glass, then captures. The last scene catches the 150 ms
    // fade-out ghost, which must stay on top too. Fully guarded; inert unless the mode is set.

    private static final int TT_SETTLE = 46;   // frames a scene renders before the probed capture (hover held from ~3)
    private static final int TT_FLUSH  = 8;    // extra frames after it so the async read-back grabs the probed frame
    private static final int TT_WARM   = 12;   // creative scenes: let the game-mode / tab change reach the client first

    private static void stepTooltips(MinecraftClient client) {
        final ClientPlayerEntity player = client.player;
        if (player == null) { ttHoverX = ttHoverY = -1; frames = 0; phase = P_STOP; return; }
        try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        boolean done;
        switch (ttStage) {
            case 0:   // survival inventory: hover a main-row slot so the tall card covers the item rows + counts below
                done = ttScene(client, "tt-survival",
                        () -> { camDown(client); fillInventory(client); open(client, new InventoryScreen(player), "tt inv"); },
                        null, s -> slotCenter(s, 12));
                break;
            case 1:   // survival inventory WITH effects: hover a right-column slot so the card overlaps the effect strip
                done = ttScene(client, "tt-effects",
                        () -> { camDown(client); effectsGive(client); fillInventory(client);
                                close(client); open(client, new InventoryScreen(player), "tt effects inv"); },
                        null, s -> slotCenter(s, 17));
                break;
            case 2:   // creative grid (search tab): hover a mid-grid item so the card covers items + the glass scrollbar
                done = ttScene(client, "tt-creative",
                        () -> { camDown(client); gamemodeCreative(client); rebuildCreative(client); close(client);
                                open(client, new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(
                                        player, player.networkHandler.getEnabledFeatures(), true), "tt creative"); },
                        f -> { if (f == TT_WARM) selectCreativeSearch(client); },
                        // slot 16 = grid row 1, col 7 (near the right edge): the card extends right OVER the glass
                        // scrollbar and toward the effect strip, so R1 case (b) card-over-scrollbar is captured.
                        s -> frames > TT_WARM ? slotCenter(s, 16) : null);
                break;
            case 3:   // fade-out ghost: hover an item, then move the cursor off; capture 4 frames into the 150 ms fade
                done = ttGhostScene(client, player);
                break;
            default:
                ttHoverX = ttHoverY = -1; close(client); frames = 0; phase = P_STOP; return;
        }
        if (done) { ttStage++; frames = 0; }
    }

    /** One hover scene; returns true when finished. {@code tick} (optional) sees every frame; {@code hover} maps the
     *  current screen to a GUI-px cursor (null = keep the previous). Settle, hold the hover, capture, flush. */
    private static boolean ttScene(MinecraftClient client, String name, Runnable setup,
                                   java.util.function.IntConsumer tick,
                                   java.util.function.Function<net.minecraft.client.gui.screen.Screen, double[]> hover) {
        frames++;
        if (frames == 1) { ttHoverX = ttHoverY = -1; try { setup.run(); } catch (Throwable t) { skip("tt setup " + name, t); } }
        if (tick != null) { try { tick.accept(frames); } catch (Throwable t) { skip("tt tick " + name, t); } }
        if (frames >= 3 && hover != null) {
            double[] h = null;
            try { h = hover.apply(client.currentScreen); } catch (Throwable t) { if (frames == TT_SETTLE - 2) skip("tt hover " + name, t); }
            if (h != null) { ttHoverX = h[0]; ttHoverY = h[1]; }
        }
        ttApplyHover(client);
        if (frames == TT_SETTLE) {
            capture(client, name + ".png");
            System.out.println("[S1mp1e][DevShot][TOOLTIP] " + name + " hover " + Math.round(ttHoverX) + "," + Math.round(ttHoverY)
                    + " (" + (client.currentScreen == null ? "no screen" : client.currentScreen.getClass().getSimpleName()) + ")");
        } else if (frames >= TT_SETTLE + TT_FLUSH) {
            return true;
        }
        return false;
    }

    /** Creative-search grid: hold a hover until the card is up, then move the cursor off so the panel fades as a ghost;
     *  capture 4 consecutive frames of the 150 ms fade (the ghost must stay on the top layer too). */
    private static boolean ttGhostScene(MinecraftClient client) { return ttGhostScene(client, client.player); }
    private static boolean ttGhostScene(MinecraftClient client, ClientPlayerEntity player) {
        frames++;
        if (frames == 1) {
            ttHoverX = ttHoverY = -1;
            gamemodeSurvival(client); fillInventory(client);
            close(client); open(client, new InventoryScreen(player), "tt ghost inv");
        }
        net.minecraft.client.gui.screen.Screen s = client.currentScreen;
        if (frames >= 3 && frames < TT_SETTLE) {           // hold a hover so the card is fully up
            double[] h = slotCenter(s, 12);
            if (h != null) { ttHoverX = h[0]; ttHoverY = h[1]; }
        } else if (frames >= TT_SETTLE) {                  // move the cursor to an empty corner -> card fades out
            ttHoverX = 8; ttHoverY = 8;
        }
        ttApplyHover(client);
        if (frames >= TT_SETTLE + 2 && frames <= TT_SETTLE + 5) {
            capture(client, String.format("tt-ghost-%02d.png", frames - (TT_SETTLE + 2)));
        }
        return frames >= TT_SETTLE + 5 + TT_FLUSH;
    }

    /** Park the GUI cursor at (ttHoverX, ttHoverY) GUI px by writing the {@code Mouse} position the screens read (the
     *  render loop maps mouse.getX()*scaledW/fbW → mouseX). The OS cursor is NOT moved (no glfwSetCursorPos). */
    private static void ttApplyHover(MinecraftClient client) {
        if (ttHoverX < 0 || ttHoverY < 0) return;
        try {
            net.minecraft.client.util.Window w = client.getWindow();
            double px = ttHoverX * w.getWidth() / (double) w.getScaledWidth();
            double py = ttHoverY * w.getHeight() / (double) w.getScaledHeight();
            if (ttMouseX == null) {
                ttMouseX = net.minecraft.client.Mouse.class.getDeclaredField("x");
                ttMouseY = net.minecraft.client.Mouse.class.getDeclaredField("y");
                ttMouseX.setAccessible(true); ttMouseY.setAccessible(true);
            }
            ttMouseX.setDouble(client.mouse, px);
            ttMouseY.setDouble(client.mouse, py);
        } catch (Throwable t) { skip("tt park cursor", t); ttHoverX = ttHoverY = -1; }
    }

    /** Centre (GUI px) of screen-handler slot {@code index}, or null if unavailable. Works for any HandledScreen. */
    private static double[] slotCenter(net.minecraft.client.gui.screen.Screen s, int index) {
        if (!(s instanceof net.minecraft.client.gui.screen.ingame.HandledScreen)) return null;
        try {
            net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs = (net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s;
            net.minecraft.screen.ScreenHandler h = hs.getScreenHandler();
            if (h == null || index < 0 || index >= h.slots.size()) return null;
            net.minecraft.screen.slot.Slot slot = h.slots.get(index);
            int ox = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$x();
            int oy = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$y();
            return new double[]{ox + slot.x + 8, oy + slot.y + 8};
        } catch (Throwable t) { return null; }
    }

    // ==== BATCH-A effect-strip (F) sweep — S1MP1E_SHOT_MODE=effects ====================================
    //
    // Give the player a representative spread of effects and shoot the now-glass effect strip:
    //   effects-survival-wide.png  — survival inventory, WIDE (icon + name + time), ONE continuous glass strip;
    //   effects-creative-wide.png  — creative inventory, WIDE;
    //   effects-chest.png          — a chest (GenericContainerScreen): plain containers have NO effect panel (correct);
    //   effects-compact.png        — narrow window so the strip falls into its COMPACT icon-only column.

    private static final int FX_WARM   = 12;
    private static final int FX_SETTLE = 46;
    private static final int FX_FLUSH  = 8;

    private static void stepEffects(MinecraftClient client) {
        final ClientPlayerEntity player = client.player;
        if (player == null) { curW = SHOT_W; curH = SHOT_H; frames = 0; phase = P_STOP; return; }
        try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        switch (fxStage) {
            case 0: {   // WIDE survival inventory
                frames++;
                if (frames == 1) { camDown(client); effectsGive(client); }
                if (frames == FX_WARM) open(client, new InventoryScreen(player), "fx survival");
                if (frames == FX_WARM + FX_SETTLE) capture(client, "effects-survival-wide.png");
                else if (frames >= FX_WARM + FX_SETTLE + FX_FLUSH) { close(client); fxStage = 1; frames = 0; }
                return;
            }
            case 1: {   // WIDE creative inventory
                frames++;
                if (frames == 1) { camDown(client); gamemodeCreative(client); effectsGive(client); rebuildCreative(client); }
                if (frames == FX_WARM) open(client, new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(
                        player, player.networkHandler.getEnabledFeatures(), true), "fx creative");
                if (frames == FX_WARM + 6) { effectsGive(client); selectCreativeSearch(client); }
                if (frames == FX_WARM + FX_SETTLE) capture(client, "effects-creative-wide.png");
                else if (frames >= FX_WARM + FX_SETTLE + FX_FLUSH) { close(client); fxStage = 2; frames = 0; }
                return;
            }
            case 2: {   // chest — plain containers draw NO effect panel (documents that; shot is the chest)
                frames++;
                if (frames == 1) { camDown(client); gamemodeSurvival(client); effectsGive(client); openChest(client); }
                if (frames == FX_SETTLE) capture(client, "effects-chest.png");
                else if (frames >= FX_SETTLE + FX_FLUSH) { close(client); fxStage = 3; frames = 0; }
                return;
            }
            case 3: {   // COMPACT survival inventory: narrow the window so the room right of the panel drops below 120 px
                frames++;
                if (frames == 1) { curW = 760; curH = 720; camDown(client); effectsGive(client); }
                if (frames == FX_WARM) open(client, new InventoryScreen(player), "fx compact");
                if (frames == FX_WARM + FX_SETTLE) capture(client, "effects-compact.png");
                else if (frames >= FX_WARM + FX_SETTLE + FX_FLUSH) {
                    close(client); curW = SHOT_W; curH = SHOT_H; fxStage = 4; frames = 0;
                }
                return;
            }
            default:
                close(client); curW = SHOT_W; curH = SHOT_H; frames = 0; phase = P_STOP;
        }
    }

    // ==== R4 flicker sweep — S1MP1E_SHOT_MODE=flicker ================================================
    //
    // Open a frame-primary container glass surface (survival inventory + a status-effect strip) over the
    // settled flat world and capture a burst of CONSECUTIVE frames with NOTHING changed between them.
    // The effects are all INFINITE-duration so the strip shows no ticking countdown text (which would be a
    // legitimate per-second change, not flicker). If the glass grab were the 3 ms time-deduped grab() (the
    // 1.16.5 self-sampling bug) instead of grabNow(), the panel/strip would oscillate frame-to-frame; with
    // grabNow() every frame owns a fresh pre-glass backdrop, so the burst is stable to ~world drift only.
    private static final int FLK_COUNT = 12;

    private static void stepFlicker(MinecraftClient client) {
        final ClientPlayerEntity player = client.player;
        if (player == null) { frames = 0; phase = P_STOP; return; }
        try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        switch (flkStage) {
            case 0: {   // set up + settle
                frames++;
                if (frames == 1) { camDown(client); flickerEffects(client); }
                if (frames == FX_WARM) open(client, new InventoryScreen(player), "flicker inv");
                if (frames >= FX_WARM + FX_SETTLE) { flkStage = 1; frames = 0; }
                return;
            }
            case 1: {   // consecutive-frame burst — no state touched between captures
                capture(client, String.format("flicker-%02d.png", frames));
                if (++frames >= FLK_COUNT) { close(client); frames = 0; phase = P_STOP; }
                return;
            }
            default:
                close(client); frames = 0; phase = P_STOP;
        }
    }

    /** Infinite (no-countdown) effects for the flicker burst: Speed, Haste, Night Vision — the strip renders
     *  without ticking time text so any frame-to-frame delta is glass, not a legitimate per-second change. */
    private static void flickerEffects(MinecraftClient client) {
        try {
            ServerPlayerEntity sp = client.getServer() == null || client.getServer().getPlayerManager().getPlayerList().isEmpty()
                    ? null : client.getServer().getPlayerManager().getPlayerList().get(0);
            if (sp != null) {
                sp.clearStatusEffects();
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, -1, 0, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, -1, 0, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, -1, 0, false, false, true));
            }
        } catch (Throwable t) { skip("flicker effects (server)", t); }
        try {
            ClientPlayerEntity cp = client.player;
            if (cp != null) {
                cp.clearStatusEffects();
                cp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, -1, 0, false, false, true));
                cp.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, -1, 0, false, false, true));
                cp.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, -1, 0, false, false, true));
            }
        } catch (Throwable t) { skip("flicker effects (client)", t); }
    }

    /** A representative spread of 6 effects: beneficial (Speed, Strength II, Haste), harmful (Poison), ambient
     *  (Regeneration), infinite (Night Vision) — new instances each call (they tick; server/client must not share one). */
    private static void effectsGive(MinecraftClient client) {
        try {
            ServerPlayerEntity sp = client.getServer() == null || client.getServer().getPlayerManager().getPlayerList().isEmpty()
                    ? null : client.getServer().getPlayerManager().getPlayerList().get(0);
            if (sp != null) { sp.clearStatusEffects(); for (StatusEffectInstance e : effectsList()) sp.addStatusEffect(e); }
        } catch (Throwable t) { skip("effects give (server)", t); }
        try {
            ClientPlayerEntity cp = client.player;
            if (cp != null) { cp.clearStatusEffects(); for (StatusEffectInstance e : effectsList()) cp.addStatusEffect(e); }
        } catch (Throwable t) { skip("effects give (client)", t); }
    }

    private static java.util.List<StatusEffectInstance> effectsList() {
        java.util.List<StatusEffectInstance> l = new java.util.ArrayList<StatusEffectInstance>();
        l.add(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.STRENGTH, 3600, 1, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.HASTE, 900, 0, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.POISON, 600, 0, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.REGENERATION, 1800, 0, true, false, true));
        l.add(new StatusEffectInstance(StatusEffects.NIGHT_VISION, -1, 0, false, false, true));
        return l;
    }

    /** Fill the main inventory (slots 9..35) with an assortment so hover cards overlap real items + count digits. */
    private static void fillInventory(MinecraftClient client) {
        net.minecraft.item.Item[] pool = {
                Items.GOLDEN_APPLE, Items.DIAMOND_SWORD, Items.BOW, Items.IRON_PICKAXE, Items.TORCH, Items.OAK_LOG,
                Items.BREAD, Items.ARROW, Items.REDSTONE, Items.ENDER_PEARL, Items.BOOK, Items.COMPASS, Items.CLOCK,
                Items.EMERALD, Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COAL, Items.GLASS, Items.BRICKS,
                Items.SAND, Items.GRAVEL, Items.CACTUS, Items.PUMPKIN, Items.MELON_SLICE, Items.CARROT, Items.POTATO };
        try {
            ServerPlayerEntity sp = client.getServer() == null || client.getServer().getPlayerManager().getPlayerList().isEmpty()
                    ? null : client.getServer().getPlayerManager().getPlayerList().get(0);
            for (int i = 9; i < 36; i++) {
                net.minecraft.item.Item it = pool[(i - 9) % pool.length];
                ItemStack st = new ItemStack(it, 1 + (i * 7) % 24);
                if (sp != null) sp.getInventory().setStack(i, st.copy());
                if (client.player != null) client.player.getInventory().setStack(i, st.copy());
            }
        } catch (Throwable t) { skip("fill inventory", t); }
    }

    /** Open a client-side 9x3 chest full of items — a GenericContainerScreen (NOT AbstractInventoryScreen → no effect
     *  panel, which is exactly what the shot documents). */
    private static void openChest(MinecraftClient client) {
        try {
            net.minecraft.inventory.SimpleInventory inv = new net.minecraft.inventory.SimpleInventory(27);
            for (int i = 0; i < 27; i++) inv.setStack(i, new ItemStack(Items.STONE, 1 + i));
            net.minecraft.screen.GenericContainerScreenHandler h =
                    net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(1, client.player.getInventory(), inv);
            client.player.currentScreenHandler = h;
            close(client);
            client.setScreen(new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(
                    h, client.player.getInventory(), net.minecraft.text.Text.literal("Chest")));
        } catch (Throwable t) { skip("open chest", t); }
    }

    // ---- shared world-state helpers for the tooltip / effects sweeps -------

    private static void camDown(MinecraftClient client) {
        try { ClientPlayerEntity cp = client.player; cp.setYaw(0f); cp.prevYaw = 0f; cp.headYaw = 0f; cp.setPitch(28f); cp.prevPitch = 28f; }
        catch (Throwable t) { skip("camera down", t); }
    }

    private static void gamemodeCreative(MinecraftClient client) {
        try { if (client.getServer() != null) client.getServer().getCommandManager().executeWithPrefix(
                client.getServer().getCommandSource(), "gamemode creative @a"); } catch (Throwable t) { skip("gamemode creative (server)", t); }
        try { if (client.interactionManager != null) client.interactionManager.setGameMode(GameMode.CREATIVE); } catch (Throwable t) { skip("gamemode creative (client)", t); }
    }

    private static void gamemodeSurvival(MinecraftClient client) {
        try { if (client.getServer() != null) client.getServer().getCommandManager().executeWithPrefix(
                client.getServer().getCommandSource(), "gamemode survival @a"); } catch (Throwable t) { skip("gamemode survival (server)", t); }
        try { if (client.interactionManager != null) client.interactionManager.setGameMode(GameMode.SURVIVAL); } catch (Throwable t) { skip("gamemode survival (client)", t); }
    }

    /** Build the creative tab contents so the search/category grids are populated (else creative opens on a bare tab). */
    private static void rebuildCreative(MinecraftClient client) {
        try {
            net.minecraft.item.ItemGroups.updateDisplayContext(
                    client.player.networkHandler.getEnabledFeatures(), true, client.player.networkHandler.getRegistryManager());
        } catch (Throwable t) { skip("rebuild creative tabs", t); }
    }

    /** Select the creative SEARCH tab (populated with everything → a scrollbar shows) via the private setSelectedTab. */
    private static void selectCreativeSearch(MinecraftClient client) {
        try {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            net.minecraft.item.ItemGroup search = net.minecraft.item.ItemGroups.getSearchGroup();
            java.lang.reflect.Method m = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                    .getDeclaredMethod("setSelectedTab", net.minecraft.item.ItemGroup.class);
            m.setAccessible(true);
            m.invoke(client.currentScreen, search);
        } catch (Throwable t) { skip("select creative search tab", t); }
    }

    // ==== stage-2 sweeps: fused tabs (B) / glass scrollbar (C) / silky glide (D) / clicks (D) ==========
    //
    // All four drive the CREATIVE inventory (the biggest scroll list + the fused category tabs). Screens with
    // server-populated lists (stonecutter / loom / merchant) cannot be opened deterministically in the flat DevShot
    // world without a live block-entity + recipe/trade sync, so their C/D injections are covered by the mixin AUDIT
    // (S1MP1E_AUDIT=1) instead; the code path is identical (same GlassScrollbar + suppress-and-redraw pattern).

    private static final int S2_WARM = 14;     // let the creative-mode + tab change reach the client
    private static final int S2_SETTLE = 34;
    private static final int S2_FLUSH = 8;

    /** Open the creative inventory on the SEARCH tab (populated → the grid scrolls and the scrollbar is active). */
    private static void s2$openCreativeSearch(MinecraftClient client) {
        camDown(client); gamemodeCreative(client); rebuildCreative(client);
        close(client);
        open(client, new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(
                client.player, client.player.networkHandler.getEnabledFeatures(), true), "s2 creative");
        selectCreativeSearch(client);
    }

    private static float s2$scrollPos(net.minecraft.client.gui.screen.Screen s) {
        try {
            if (crScrollPos == null) {
                crScrollPos = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                        .getDeclaredField("scrollPosition");
                crScrollPos.setAccessible(true);
            }
            return crScrollPos.getFloat(s);
        } catch (Throwable t) { return 0f; }
    }

    private static void s2$setScrollPos(net.minecraft.client.gui.screen.Screen s, float v) {
        try {
            if (crScrollPos == null) {
                crScrollPos = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                        .getDeclaredField("scrollPosition");
                crScrollPos.setAccessible(true);
            }
            crScrollPos.setFloat(s, v);
            // The creative HANDLER repopulates the 45 visible slots from the scroll position (scrollItems), so call it on
            // the handler after setting the screen field — that keeps the logical rows (clicks/hit-test) in sync.
            net.minecraft.screen.ScreenHandler h =
                    ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s).getScreenHandler();
            if (h instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.CreativeScreenHandler csh)
                csh.scrollItems(v);
        } catch (Throwable t) { skip("set creative scroll", t); }
    }

    /** True while the creative grid is mid-glide (our mixin's flag), read via the GlassGlideHost duck the screen impls. */
    private static boolean s2$gliding(net.minecraft.client.gui.screen.Screen s) {
        try { return s instanceof dev.s1mp1e.client.gui.GlassGlideHost g && g.s1mp1e$gliding(); }
        catch (Throwable t) { return false; }
    }

    private static void s2$setScrolling(net.minecraft.client.gui.screen.Screen s, boolean v) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                    .getDeclaredField("scrolling");
            f.setAccessible(true);
            f.setBoolean(s, v);
        } catch (Throwable t) { skip("set creative scrolling", t); }
    }

    /** The creative handler's live item list (public field, same one CreativeGlassMixin reads). */
    private static java.util.List<ItemStack> s2$itemList(net.minecraft.client.gui.screen.Screen s) {
        try {
            net.minecraft.screen.ScreenHandler h =
                    ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s).getScreenHandler();
            if (h instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.CreativeScreenHandler csh)
                return csh.itemList;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Select a creative category tab (private setSelectedTab). */
    private static void s2$selectTab(MinecraftClient client, net.minecraft.item.ItemGroup group) {
        try {
            if (group == null || !(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            if (crSetSelectedTab == null) {
                crSetSelectedTab = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                        .getDeclaredMethod("setSelectedTab", net.minecraft.item.ItemGroup.class);
                crSetSelectedTab.setAccessible(true);
            }
            crSetSelectedTab.invoke(client.currentScreen, group);
        } catch (Throwable t) { skip("select creative tab", t); }
    }

    /** The nth CATEGORY item group in the given tab row (TOP/BOTTOM), or null. */
    private static net.minecraft.item.ItemGroup s2$tabGroup(boolean top, int skip) {
        try {
            int seen = 0;
            for (net.minecraft.item.ItemGroup g : net.minecraft.registry.Registries.ITEM_GROUP) {
                if (g.getType() != net.minecraft.item.ItemGroup.Type.CATEGORY) continue;
                boolean isTop = g.getRow() == net.minecraft.item.ItemGroup.Row.TOP;
                if (isTop != top) continue;
                if (seen++ == skip) return g;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Centre (GUI px) of a fused-band tab cell — mirrors GlassTabs.cellW so the virtual cursor lands on the drawn pill. */
    private static double[] s2$tabCellCenter(net.minecraft.client.gui.screen.Screen s, int col, boolean top) {
        try {
            net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs =
                    (net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s;
            int px = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$x();
            int py = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$y();
            int pw = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$backgroundWidth();
            int ph = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$backgroundHeight();
            double c = pw / 7.0;
            double cx = px + (col + 0.5) * c;
            double cy = top ? py - 14 : py + ph + 14;
            return new double[]{cx, cy};
        } catch (Throwable t) { return null; }
    }

    // ---- (B) fused creative tabs: slide within a row, cross-fade across rows, hover pill glide ----

    private static void stepTabs(MinecraftClient client) {
        if (client.player == null) { ttHoverX = ttHoverY = -1; frames = 0; phase = P_STOP; return; }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        frames++;
        switch (s2Stage) {
            case 0:   // open on the search tab, then select TOP-row tab 0 (building blocks) as the slide start
                if (frames == 1) s2$openCreativeSearch(client);
                if (frames == S2_WARM) s2$selectTab(client, s2$tabGroup(true, 0));
                if (frames == S2_WARM + S2_SETTLE) capture(client, "tabs-a.png");
                else if (frames >= S2_WARM + S2_SETTLE + S2_FLUSH) { s2Stage = 1; frames = 0; }
                return;
            case 1:   // slide within the TOP row: jump to TOP tab 4 → the selected pill springs across the row
                if (frames == 1) s2$selectTab(client, s2$tabGroup(true, 4));
                if (frames >= 2 && frames <= 5) capture(client, String.format("tabs-slide-%02d.png", frames - 2));
                else if (frames >= 6 + S2_FLUSH) { s2Stage = 2; frames = 0; }
                return;
            case 2:   // cross-row: jump to a BOTTOM-row tab → old pill fades out (150 ms), new pill fades in (100 ms)
                if (frames == 1) s2$selectTab(client, s2$tabGroup(false, 0));
                if (frames >= 2 && frames <= 4) capture(client, String.format("tabs-cross-%02d.png", frames - 2));
                else if (frames >= 5 + S2_FLUSH) { s2Stage = 3; frames = 0; }
                return;
            case 3: {  // hover an UNSELECTED top-row tab → the fainter hover pill glides in
                if (frames == 1) ttHoverX = ttHoverY = -1;
                double[] h = s2$tabCellCenter(client.currentScreen, 2, true);   // top cell 2 (not the selected bottom tab)
                if (h != null) { ttHoverX = h[0]; ttHoverY = h[1]; }
                ttApplyHover(client);
                if (frames == S2_SETTLE) capture(client, "tabs-hover.png");
                else if (frames >= S2_SETTLE + S2_FLUSH) { ttHoverX = ttHoverY = -1; s2Stage = 4; frames = 0; }
                return;
            }
            case 4: {  // #4 — hover an item SLOT so the creative slot lattice + gliding hover pill show
                if (frames == 1) s2$selectTab(client, s2$tabGroup(true, 0));   // building blocks: a full item grid
                try {
                    dev.s1mp1e.glass.mixin.HandledScreenAccessor a =
                            (dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) client.currentScreen;
                    ttHoverX = a.s1mp1e$x() + 9 + 8;    // first grid slot (x+9, y+18) centre
                    ttHoverY = a.s1mp1e$y() + 18 + 8;
                } catch (Throwable ignored) {}
                ttApplyHover(client);
                if (frames == S2_SETTLE) capture(client, "creative-hover.png");
                else if (frames >= S2_SETTLE + S2_FLUSH) { ttHoverX = ttHoverY = -1; s2Stage = 5; frames = 0; }
                return;
            }
            default:
                ttHoverX = ttHoverY = -1; close(client); frames = 0; phase = P_STOP;
        }
    }

    // ---- (C) vertical glass scrollbar: rest / mid / held-lens ----

    private static void stepScroll(MinecraftClient client) {
        if (client.player == null) { frames = 0; phase = P_STOP; return; }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        frames++;
        net.minecraft.client.gui.screen.Screen s = client.currentScreen;
        switch (s2Stage) {
            case 0:   // open + settle at the top → thumb at rest
                if (frames == 1) s2$openCreativeSearch(client);
                if (frames == S2_WARM + S2_SETTLE) capture(client, "scroll-top.png");
                else if (frames >= S2_WARM + S2_SETTLE + S2_FLUSH) { s2Stage = 1; frames = 0; }
                return;
            case 1:   // jump to the middle and let the thumb ease there → thumb mid
                if (frames == 1) s2$setScrollPos(s, 0.5f);
                if (frames == S2_SETTLE) capture(client, "scroll-mid.png");
                else if (frames >= S2_SETTLE + S2_FLUSH) { s2Stage = 2; frames = 0; }
                return;
            case 2:   // held: force the drag flag + park the cursor on the thumb → the white pill morphs to a glass lens
                if (frames == 1) { s2$setScrollPos(s, 0.5f); s2$setScrolling(s, true); }
                {
                    double[] th = s2$scrollThumb(s);
                    if (th != null) { ttHoverX = th[0]; ttHoverY = th[1]; }
                    ttApplyHover(client);
                }
                if (frames == S2_SETTLE) capture(client, "scroll-held.png");
                else if (frames >= S2_SETTLE + S2_FLUSH) { s2$setScrolling(s, false); ttHoverX = ttHoverY = -1; s2Stage = 3; frames = 0; }
                return;
            default:
                ttHoverX = ttHoverY = -1; close(client); frames = 0; phase = P_STOP;
        }
    }

    /** Approx thumb centre for the creative bar (track top = topPos+18, travel 97, thumb 15). */
    private static double[] s2$scrollThumb(net.minecraft.client.gui.screen.Screen s) {
        try {
            net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs =
                    (net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s;
            int px = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$x();
            int py = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$y();
            int pw = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$backgroundWidth();
            double ratio = s2$scrollPos(s);
            return new double[]{px + pw - 19, py + 18 + ratio * 97 + 7.5};
        } catch (Throwable t) { return null; }
    }

    // ---- (D) silky sub-pixel glide: one scroll jump, N consecutive frames of the decelerating settle ----

    private static void stepGlide(MinecraftClient client) {
        if (client.player == null) { frames = 0; phase = P_STOP; return; }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        frames++;
        net.minecraft.client.gui.screen.Screen s = client.currentScreen;
        if (s2Stage == 0) {
            if (frames == 1) s2$openCreativeSearch(client);
            if (frames == S2_WARM + S2_SETTLE) { s2$setScrollPos(s, 0.0f); }   // ensure settled at the top
            if (frames == S2_WARM + S2_SETTLE + 2) { s2$setScrollPos(s, 0.28f); s2Stage = 1; frames = 0; }  // ONE jump
            return;
        }
        // capture the decelerating sub-pixel glide over consecutive frames (τ = 90 ms → settles in ~6 frames)
        if (frames >= 1 && frames <= 10) {
            capture(client, String.format("glide-%02d.png", frames - 1));
            System.out.println("[S1mp1e][DevShot][GLIDE] frame " + (frames - 1) + " gliding=" + s2$gliding(s)
                    + " scroll=" + String.format("%.3f", s2$scrollPos(s)));
        } else if (frames >= 10 + S2_FLUSH) {
            close(client); frames = 0; phase = P_STOP;
        }
    }

    // ---- (D) click correctness: at rest AND mid-glide (mid-glide snaps to target first) ----

    private static int clkPass, clkFail;

    private static void stepClicks(MinecraftClient client) {
        if (client.player == null) { frames = 0; phase = P_STOP; return; }
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        frames++;
        net.minecraft.client.gui.screen.Screen s = client.currentScreen;
        switch (s2Stage) {
            case 0:
                if (frames == 1) { s2$openCreativeSearch(client); clkPass = clkFail = 0; }
                if (frames >= S2_WARM + S2_SETTLE) { s2$setScrollPos(s, 0.0f); s2Stage = 1; frames = 0; }
                return;
            case 1:   // click at REST on a mid-grid slot; the picked stack must equal what is drawn there
                if (frames == 2) s2$clickAssert(client, s, 22, "rest");
                if (frames >= 4) { s2$clearCursor(s); s2$setScrollPos(s, 0.0f); s2Stage = 2; frames = 0; }
                return;
            case 2:   // start a glide, then click MID-GLIDE: the HEAD snap ends the glide so the click hits the drawn item
                if (frames == 1) s2$setScrollPos(s, 0.30f);       // jump → glide begins
                if (frames == 2) {
                    System.out.println("[S1mp1e][DevShot][CLICKS] mid-glide? gliding=" + s2$gliding(s));
                    s2$clickAssert(client, s, 22, "mid-glide");
                }
                if (frames >= 4) { s2$clearCursor(s); s2Stage = 3; frames = 0; }
                return;
            case 3:   // edge cell (first grid slot) at rest
                if (frames == 1) s2$setScrollPos(s, 0.0f);
                if (frames == 3) s2$clickAssert(client, s, 0, "edge");
                if (frames >= 6) {
                    System.out.println("[S1mp1e][DevShot][CLICKS] " + clkPass + " PASS / " + clkFail + " FAIL");
                    capture(client, "clicks-done.png");
                    close(client); frames = 0; phase = P_STOP;
                }
                return;
            default:
                close(client); frames = 0; phase = P_STOP;
        }
    }

    /** Click slot {@code index} at its drawn centre; PASS if the resulting cursor stack matches the slot's item. */
    private static void s2$clickAssert(MinecraftClient client, net.minecraft.client.gui.screen.Screen s, int index, String label) {
        try {
            net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs =
                    (net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s;
            net.minecraft.screen.ScreenHandler h = hs.getScreenHandler();
            if (index >= h.slots.size()) { clkFail++; System.out.println("[S1mp1e][DevShot][CLICKS] " + label + " FAIL: no slot"); return; }
            net.minecraft.screen.slot.Slot slot = h.slots.get(index);
            net.minecraft.item.Item expected = slot.getStack().getItem();
            int ox = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$x();
            int oy = ((dev.s1mp1e.glass.mixin.HandledScreenAccessor) (Object) hs).s1mp1e$y();
            double mx = ox + slot.x + 8, my = oy + slot.y + 8;
            ttHoverX = mx; ttHoverY = my; ttApplyHover(client);
            hs.mouseClicked(mx, my, 0);
            net.minecraft.item.Item got = h.getCursorStack().getItem();
            boolean ok = !h.getCursorStack().isEmpty() && got == expected;
            if (ok) clkPass++; else clkFail++;
            System.out.println("[S1mp1e][DevShot][CLICKS] " + label + " slot " + index + " expected="
                    + net.minecraft.registry.Registries.ITEM.getId(expected) + " got="
                    + net.minecraft.registry.Registries.ITEM.getId(got) + " -> " + (ok ? "PASS" : "FAIL"));
        } catch (Throwable t) { clkFail++; skip("click assert " + label, t); }
    }

    private static void s2$clearCursor(net.minecraft.client.gui.screen.Screen s) {
        try {
            ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) s).getScreenHandler()
                    .setCursorStack(ItemStack.EMPTY);
        } catch (Throwable ignored) {}
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
            client.createIntegratedServerLoader().createAndStart(
                    "devshot", info, gen,
                    drm -> drm.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                              .createDimensionsRegistryHolder());
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
        // The screens / tabs / scroll / glide / clicks sweeps need CREATIVE so the creative inventory shows its category
        // tabs (fused-tab shot B) and a populated grid (scrollbar + glide); otherwise a survival world opens the plain
        // inventory tab with no category tabs built.
        if ("screens".equalsIgnoreCase(mode) || "tabs".equalsIgnoreCase(mode) || "scroll".equalsIgnoreCase(mode)
                || "glide".equalsIgnoreCase(mode) || "clicks".equalsIgnoreCase(mode)) {
            try {
                ServerPlayerEntity sp0 = server.getPlayerManager().getPlayerList().isEmpty()
                        ? null : server.getPlayerManager().getPlayerList().get(0);
                if (sp0 != null) sp0.changeGameMode(GameMode.CREATIVE);
            } catch (Throwable t) {
                skip("set creative for screens sweep", t);
            }
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

    // ==== BATCH-B HUD overlays (G) sweep — S1MP1E_SHOT_MODE=hud ========================================
    //
    // Drives each vanilla HUD component the mod glasses and captures it in the flat world (currentScreen
    // null so the HUD renders), one scene per stage:
    //   hud-actionbar.png   — the overlay-message glass pill (G5), fading with its own timer;
    //   hud-bossbar.png     — blue capsule fill + concentric true-capsule glass under-layer (G3);
    //   hud-chat.png        — the unfocused chat glass panel + dropped per-line rects (G1);
    //   hud-chat-input.png  — the open chat input glass bar (G1) with the panel at full opacity;
    //   hud-tablist.png     — header/list/footer glass plates + per-row scrims (G2);
    //   hud-toast.png       — a system toast as a glass card, slid in (G4);
    //   hud-nametag.png     — an entity name plate re-tinted frosted charcoal (G6).

    private static final int HUD_SETTLE = 14;
    private static final int HUD_FLUSH  = 8;

    private static void stepHud(MinecraftClient client) {
        if (client.player == null) { frames = 0; phase = P_STOP; return; }
        switch (hudStage) {
            case 0: {   // action bar / overlay message
                frames++;
                if (frames == 1) {
                    try { client.getToastManager().clear(); } catch (Throwable ignored) {}
                    try { client.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {}
                    camLevel(client);
                    try { client.inGameHud.setOverlayMessage(Text.literal("拾取 鑽石 x3"), false); }
                    catch (Throwable t) { skip("hud actionbar set", t); }
                }
                if (frames == HUD_SETTLE) capture(client, "hud-actionbar.png");
                else if (frames >= HUD_SETTLE + HUD_FLUSH) { hudStage = 1; frames = 0; }
                return;
            }
            case 1: {   // boss bar (blue capsule fill + concentric glass capsule)
                frames++;
                if (frames == 1) {
                    serverCmd(client, "bossbar add s1mp1e:demo \"S1mp1e\"");
                    serverCmd(client, "bossbar set s1mp1e:demo max 100");
                    serverCmd(client, "bossbar set s1mp1e:demo value 70");
                    serverCmd(client, "bossbar set s1mp1e:demo color blue");
                    serverCmd(client, "bossbar set s1mp1e:demo players @a");
                    serverCmd(client, "bossbar set s1mp1e:demo visible true");
                }
                if (frames == HUD_SETTLE + 6) capture(client, "hud-bossbar.png");
                else if (frames >= HUD_SETTLE + 6 + HUD_FLUSH) {
                    serverCmd(client, "bossbar remove s1mp1e:demo");
                    hudStage = 2; frames = 0;
                }
                return;
            }
            case 2: {   // chat glass panel (unfocused)
                frames++;
                if (frames == 1) addChatLines(client);
                if (frames == HUD_SETTLE) capture(client, "hud-chat.png");
                else if (frames >= HUD_SETTLE + HUD_FLUSH) { hudStage = 3; frames = 0; }
                return;
            }
            case 3: {   // chat input glass bar (focused -> panel at full opacity)
                frames++;
                if (frames == 1) { addChatLines(client); open(client, new ChatScreen(""), "hud chat input"); }
                if (frames == HUD_SETTLE) capture(client, "hud-chat-input.png");
                else if (frames >= HUD_SETTLE + HUD_FLUSH) { close(client); hudStage = 4; frames = 0; }
                return;
            }
            case 4: {   // player tab list (needs the list key pressed + a list-slot scoreboard objective in singleplayer)
                frames++;
                if (frames == 1) {
                    serverCmd(client, "scoreboard objectives add s1list dummy \"分數\"");
                    serverCmd(client, "scoreboard objectives setdisplay list s1list");
                    serverCmd(client, "scoreboard players set @a s1list 42");
                    try {
                        client.inGameHud.getPlayerListHud().setHeader(Text.literal("S1mp1e 伺服器"));
                        client.inGameHud.getPlayerListHud().setFooter(Text.literal("liquid glass"));
                    } catch (Throwable t) { skip("tablist header/footer", t); }
                }
                try { client.options.playerListKey.setPressed(true); } catch (Throwable ignored) {}
                if (frames == HUD_SETTLE + 4) capture(client, "hud-tablist.png");
                else if (frames >= HUD_SETTLE + 4 + HUD_FLUSH) {
                    try { client.options.playerListKey.setPressed(false); } catch (Throwable ignored) {}
                    serverCmd(client, "scoreboard objectives remove s1list");
                    hudStage = 5; frames = 0;
                }
                return;
            }
            case 5: {   // toast (glass card, fully slid in — wall-clock gated so the title/desc are on screen)
                frames++;
                if (frames == 1) {
                    try { client.getToastManager().clear(); } catch (Throwable ignored) {}
                    try { SystemToast.show(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                            Text.literal("S1mp1e"), Text.literal("液態玻璃 Toast")); }
                    catch (Throwable t) { skip("hud toast show", t); }
                    hudGateMs = System.currentTimeMillis(); hudCaptured = false;
                }
                // Vanilla toast slide-in is ~600 ms; the toast then rests fully in for ~5 s before sliding
                // out. The shot loop's fps is unbounded so a frame count is unreliable — gate on wall clock:
                // wait 1300 ms (well past slide-in) so "S1mp1e"/"液態玻璃 Toast" are on screen, capture ONCE.
                // NOTE: never reset `frames` here — a reset would make the next frame hit the
                // `frames == 1` init again and re-show the toast forever. Gate the post-capture flush
                // on wall clock too (reusing hudGateMs as the capture timestamp).
                if (!hudCaptured) {
                    if (System.currentTimeMillis() - hudGateMs >= 1300L) {
                        capture(client, "hud-toast.png"); hudCaptured = true; hudGateMs = System.currentTimeMillis();
                    }
                } else if (System.currentTimeMillis() - hudGateMs >= 200L) {
                    try { client.getToastManager().clear(); } catch (Throwable ignored) {}
                    hudStage = 6; frames = 0;
                }
                return;
            }
            case 6: {   // name tag (frosted plate, world space) — stand set back + camera tilted up so the plate centres
                frames++;
                if (frames == 1) {
                    serverCmd(client, "kill @e[type=armor_stand]");
                    serverCmd(client, "execute at @p run summon minecraft:armor_stand ~ ~ ~3.4 "
                            + "{CustomName:'{\"text\":\"S1mp1e\"}',CustomNameVisible:1b,NoGravity:1b,Marker:0b}");
                    camNametag(client);
                }
                if (frames == HUD_SETTLE + 8) capture(client, "hud-nametag.png");
                else if (frames >= HUD_SETTLE + 8 + HUD_FLUSH) {
                    serverCmd(client, "kill @e[type=armor_stand]");
                    hudStage = 7; frames = 0;
                }
                return;
            }
            default:
                frames = 0; phase = P_STOP;
        }
    }

    /** Add a handful of chat lines (cleared first) so the unfocused chat glass panel has content to back. */
    private static void addChatLines(MinecraftClient client) {
        try {
            net.minecraft.client.gui.hud.ChatHud c = client.inGameHud.getChatHud();
            c.clear(false);
            c.addMessage(Text.literal("<S1mp1e> 液態玻璃聊天"));
            c.addMessage(Text.literal("[系統] 歡迎回到伺服器"));
            c.addMessage(Text.literal("<friend> gg wp 這個面板很讚"));
            c.addMessage(Text.literal("<S1mp1e> 這面板會折射下方畫面"));
        } catch (Throwable t) { skip("add chat lines", t); }
    }

    /** Level the camera (yaw 0 / pitch 0) so a summoned name tag sits near screen centre. */
    private static void camLevel(MinecraftClient client) {
        try {
            ClientPlayerEntity cp = client.player;
            cp.setYaw(0f); cp.prevYaw = 0f; cp.headYaw = 0f;
            cp.setPitch(0f); cp.prevPitch = 0f;
        } catch (Throwable t) { skip("camera level", t); }
    }

    /** Tilt the camera up ~7 deg (yaw 0) so an armor stand's name plate — which sits above eye level —
     *  drops from the top edge to the upper-middle of the frame, fully on screen with headroom. */
    private static void camNametag(MinecraftClient client) {
        try {
            ClientPlayerEntity cp = client.player;
            cp.setYaw(0f); cp.prevYaw = 0f; cp.headYaw = 0f;
            cp.setPitch(-7f); cp.prevPitch = -7f;
        } catch (Throwable t) { skip("camera nametag", t); }
    }

    /** Run a server command as the console (cheats on in the DevShot world). */
    private static void serverCmd(MinecraftClient client, String cmd) {
        try {
            MinecraftServer sv = client.getServer();
            if (sv != null) sv.getCommandManager().executeWithPrefix(sv.getCommandSource(), cmd);
        } catch (Throwable t) { skip("server cmd: " + cmd, t); }
    }

    // ==== BATCH-B modules (H) sweep — S1MP1E_SHOT_MODE=modules =========================================
    //
    // Snapshots the module state, exercises Block Outline (H1) and Chroma HUD (H2), captures, then
    // RESTORES the snapshot so nothing persists (never saved to disk). Scenes:
    //   outline.png / outline-chroma-00..01.png / outline-fill.png — the recoloured / wide / chroma /
    //     filled selection outline of the ONE targeted block (fair play);
    //   hud-chroma.png / hud-chroma-flat.png — S1mp1e HUD text through the chroma seam (per-char / uniform);
    //   config-blockoutline.png / config-chromahud.png — the two new modules' zh-TW settings pages.

    private static void stepModules(MinecraftClient client) {
        if (client.player == null) { frames = 0; phase = P_STOP; return; }
        BlockOutlineModule bo = (BlockOutlineModule) ModuleManager.byName("BlockOutline");
        ChromaHudModule ch = (ChromaHudModule) ModuleManager.byName("ChromaHud");
        if (bo == null || ch == null) { restoreModules(); frames = 0; phase = P_STOP; return; }
        if (!modSnapTaken) snapshotModules(bo, ch);

        switch (modStage) {
            case 0: {   // block outline: custom colour + line-width PROOF (width 1 vs width 8)
                frames++;
                if (frames == 1) {
                    lookAtGround(client);
                    setModuleEnabled("FpsHUD", false); setModuleEnabled("CoordsHUD", false);
                    ch.enabled = false;
                    bo.enabled = true;
                    bo.color.colorValue = 0xCCFF3020;
                    bo.chroma.boolValue = false; bo.fill.boolValue = false;
                    bo.width.doubleValue = 1.0;     // thin
                }
                lookAtGround(client);
                if (frames == 12) capture(client, "outline-w1.png");   // width 1 -> hairline
                if (frames == 14) bo.width.doubleValue = 8.0;          // thick
                if (frames == 26) { capture(client, "outline-w8.png"); capture(client, "outline.png"); } // width 8 -> visibly fat
                else if (frames >= 34) { modStage = 1; frames = 0; }
                return;
            }
            case 1: {   // block outline chroma (two frames apart -> the hue has visibly shifted)
                frames++;
                if (frames == 1) { bo.chroma.boolValue = true; bo.chromaSpeed.doubleValue = 4.0; }
                lookAtGround(client);
                if (frames == 8)  capture(client, "outline-chroma-00.png");
                if (frames == 18) capture(client, "outline-chroma-01.png");
                else if (frames >= 26) { modStage = 2; frames = 0; }
                return;
            }
            case 2: {   // block outline translucent fill
                frames++;
                if (frames == 1) { bo.chroma.boolValue = false; bo.fill.boolValue = true; bo.fillColour.colorValue = 0x5530A0FF; }
                lookAtGround(client);
                if (frames == 12) capture(client, "outline-fill.png");
                else if (frames >= 20) { bo.enabled = false; modStage = 3; frames = 0; }
                return;
            }
            case 3: {   // Chroma HUD, per-character wave
                frames++;
                if (frames == 1) {
                    camLevel(client);
                    bo.enabled = false;
                    setModuleEnabled("FpsHUD", true); setModuleEnabled("CoordsHUD", true);
                    ch.enabled = true; ch.speed.doubleValue = 1.0; ch.saturation.doubleValue = 0.9;
                    ch.wave.doubleValue = 0.5; ch.accents.boolValue = false;
                }
                if (frames == 12) capture(client, "hud-chroma.png");
                else if (frames >= 20) { modStage = 4; frames = 0; }
                return;
            }
            case 4: {   // Chroma HUD, uniform hue (wave 0)
                frames++;
                if (frames == 1) ch.wave.doubleValue = 0.0;
                if (frames == 12) capture(client, "hud-chroma-flat.png");
                else if (frames >= 20) { ch.enabled = false; modStage = 5; frames = 0; }
                return;
            }
            case 5: {   // config page: Block Outline settings (zh-TW) — Visual tab (index 2)
                frames++;
                if (frames == 1) open(client, new S1mp1eConfigScreen(), "config blockoutline");
                if (frames == 3) stageConfigModule(client, 2, bo);
                if (frames == 16) capture(client, "config-blockoutline.png");
                else if (frames >= 24) { modStage = 6; frames = 0; }
                return;
            }
            case 6: {   // config page: Chroma HUD settings (zh-TW) — HUD tab (index 1)
                frames++;
                if (frames == 1) stageConfigModule(client, 1, ch);
                if (frames == 16) capture(client, "config-chromahud.png");
                else if (frames >= 24) { close(client); modStage = 7; frames = 0; }
                return;
            }
            default:
                restoreModules();
                close(client);
                frames = 0; phase = P_STOP;
        }
    }

    /** Aim steeply down (pitch 75, yaw 0) so the crosshair targets a ground block and the outline renders. */
    private static void lookAtGround(MinecraftClient client) {
        try {
            ClientPlayerEntity cp = client.player;
            cp.setYaw(0f); cp.prevYaw = 0f; cp.headYaw = 0f;
            cp.setPitch(75f); cp.prevPitch = 75f;
        } catch (Throwable t) { skip("look at ground", t); }
    }

    private static void setModuleEnabled(String name, boolean on) {
        try { Module m = ModuleManager.byName(name); if (m != null) m.enabled = on; } catch (Throwable ignored) {}
    }

    /** Reflectively drive the config screen's private module selection so the shot shows that module's page. */
    private static void selectConfigModule(MinecraftClient client, Module m) {
        try {
            if (!(client.currentScreen instanceof S1mp1eConfigScreen)) return;
            java.lang.reflect.Method sel = S1mp1eConfigScreen.class.getDeclaredMethod("selectModule", Module.class);
            sel.setAccessible(true);
            sel.invoke(client.currentScreen, m);
        } catch (Throwable t) { skip("select config module " + (m == null ? "?" : m.name), t); }
    }

    /**
     * Stage the config screen on module {@code m}'s OWN category tab, then select it with no cross-fade
     * so the captured frame shows that module's full zh-TW settings page. BlockOutline lives on the
     * Visual tab (index 2) and ChromaHud on the HUD tab (index 1); {@code selectModule} alone never
     * switches tabs, so a Visual/HUD module selected from the default Combat tab renders detached and
     * label-less (the earlier broken config shots). We set the private {@code tab} field, rebuild the
     * left list for that tab, null {@code selected} so {@code selectModule} takes its no-fade
     * first-pick branch, then select — the detail (name + zh-TW setting rows) is fully laid out on the
     * very next frame.
     */
    private static void stageConfigModule(MinecraftClient client, int tabIdx, Module m) {
        try {
            if (!(client.currentScreen instanceof S1mp1eConfigScreen)) return;
            S1mp1eConfigScreen s = (S1mp1eConfigScreen) client.currentScreen;
            java.lang.reflect.Field tf = S1mp1eConfigScreen.class.getDeclaredField("tab");
            tf.setAccessible(true); tf.setInt(s, tabIdx);
            // Snap the sliding tab-highlight pill onto the same tab (it is driven by tabSlide, not `tab`),
            // so the pill and the left list agree in the still shot.
            try {
                java.lang.reflect.Field tsf = S1mp1eConfigScreen.class.getDeclaredField("tabSlide");
                tsf.setAccessible(true);
                Object anim = tsf.get(s);
                anim.getClass().getMethod("snap", float.class).invoke(anim, (float) tabIdx);
            } catch (Throwable ignored) {}
            java.lang.reflect.Method rb = S1mp1eConfigScreen.class.getDeclaredMethod("rebuildTab");
            rb.setAccessible(true); rb.invoke(s);
            java.lang.reflect.Field sf = S1mp1eConfigScreen.class.getDeclaredField("selected");
            sf.setAccessible(true); sf.set(s, null);
            selectConfigModule(client, m);   // selected==null -> applySelect + detailFade.snap(1), no fade
        } catch (Throwable t) { skip("stage config module " + (m == null ? "?" : m.name) + " tab " + tabIdx, t); }
    }

    private static void snapshotModules(BlockOutlineModule bo, ChromaHudModule ch) {
        modSnapTaken = true;
        snBoOn = bo.enabled; snBoColor = bo.color.colorValue; snBoWidth = bo.width.doubleValue;
        snBoChroma = bo.chroma.boolValue; snBoSpeed = bo.chromaSpeed.doubleValue;
        snBoFill = bo.fill.boolValue; snBoFillCol = bo.fillColour.colorValue;
        snChOn = ch.enabled; snChSpeed = ch.speed.doubleValue; snChSat = ch.saturation.doubleValue;
        snChWave = ch.wave.doubleValue; snChAccents = ch.accents.boolValue;
        Module fps = ModuleManager.byName("FpsHUD");    snFpsOn    = fps != null && fps.enabled;
        Module co  = ModuleManager.byName("CoordsHUD"); snCoordsOn = co  != null && co.enabled;
    }

    private static void restoreModules() {
        if (!modSnapTaken) return;
        try {
            BlockOutlineModule bo = (BlockOutlineModule) ModuleManager.byName("BlockOutline");
            ChromaHudModule ch = (ChromaHudModule) ModuleManager.byName("ChromaHud");
            if (bo != null) {
                bo.enabled = snBoOn; bo.color.colorValue = snBoColor; bo.width.doubleValue = snBoWidth;
                bo.chroma.boolValue = snBoChroma; bo.chromaSpeed.doubleValue = snBoSpeed;
                bo.fill.boolValue = snBoFill; bo.fillColour.colorValue = snBoFillCol;
            }
            if (ch != null) {
                ch.enabled = snChOn; ch.speed.doubleValue = snChSpeed; ch.saturation.doubleValue = snChSat;
                ch.wave.doubleValue = snChWave; ch.accents.boolValue = snChAccents;
            }
            setModuleEnabled("FpsHUD", snFpsOn); setModuleEnabled("CoordsHUD", snCoordsOn);
        } catch (Throwable t) { skip("restore modules", t); }
    }

    // ---- mixin audit -------------------------------------------------------

    private static void runAudit() {
        // Force-load the stage-2 target screens FIRST so their mixins are transformed + applied (Fabric applies a mixin
        // when its target class loads; a screen never opened in this run would otherwise leave its @Redirect/@Inject
        // unvalidated). A missing injection point throws here, surfacing loom/merchant/stonecutter glide errors even
        // though those server-synced screens cannot be opened deterministically in the flat DevShot world.
        String[] targets = {
                "net.minecraft.client.gui.screen.ingame.LoomScreen",
                "net.minecraft.client.gui.screen.ingame.MerchantScreen",
                "net.minecraft.client.gui.screen.ingame.StonecutterScreen",
                "net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen",
                // BATCH-B (G/H) targets: some (ChatScreen, toasts) never load in a bare audit run, so
                // force them here to transform + validate their mixins (a missing injection point throws).
                "net.minecraft.client.gui.hud.ChatHud",
                "net.minecraft.client.gui.hud.PlayerListHud",
                "net.minecraft.client.gui.hud.BossBarHud",
                "net.minecraft.client.gui.screen.ChatScreen",
                "net.minecraft.client.toast.AdvancementToast",
                "net.minecraft.client.toast.RecipeToast",
                "net.minecraft.client.toast.SystemToast",
                "net.minecraft.client.toast.TutorialToast",
                "net.minecraft.client.render.entity.EntityRenderer",
                "net.minecraft.client.render.WorldRenderer",
                "net.minecraft.client.render.RenderPhase$LineWidth",
        };
        int ok = 0;
        for (String cn : targets) {
            try { Class.forName(cn); ok++; }
            catch (Throwable t) { System.out.println("[S1mp1e] MIXIN AUDIT preload FAILED for " + cn + ": " + t); }
        }
        System.out.println("[S1mp1e] MIXIN AUDIT preload " + ok + "/" + targets.length + " OK");
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
