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
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.GeneratorOptions;
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
 * <p><b>1.18.2 API notes (vs the 1.20.1 reference):</b> the language manager works in
 * {@link LanguageDefinition} (looked up by code) rather than raw {@code String}; GUI scale is the
 * plain {@code options.guiScale} int field (no {@code SimpleOption} wrapper yet); world creation
 * goes through the older {@code MinecraftClient.createWorld(name, info, drm, gen)} — 1.18.2 predates
 * the {@code IntegratedServerLoader} (1.19) and {@code WorldPresets} (1.19) — with the built-in
 * registry manager ({@link DynamicRegistryManager#BUILTIN}) and a seeded default (noise) overworld
 * built via {@link DimensionType#createDefaultDimensionOptions}. Superflat needs a fragile
 * {@code FlatChunkGenerator} build here and terrain is a never-judged cross-version difference, so
 * default terrain is used (same choice the 1.16.5 line makes); and {@link LevelInfo} takes
 * {@link DataPackSettings#SAFE_MODE} (not {@code DataConfiguration}).
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
    private static final int WAIT_WORLD_CAP  = 2400;  // default gen is a touch slower; generous anyway
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
                             // BATCH-2 glide sweep (mode "glide"): drive the creative screen for B/C/D.
                             P_GLIDE = 19,
                             // BATCH-2 container sweep (mode "containers"): stonecutter + loom lists (C/D).
                             P_CONTAINERS = 20,
                             // BATCH-B (G) HUD overlays sweep (mode "hud"): chat/input/actionbar/toast/bossbar/nametag/tablist.
                             P_HUD_SEQ = 21,
                             // BATCH-B (H) modules sweep (mode "modules"): block outline (width 1/8/chroma/fill) + chroma HUD.
                             P_MODULES_SEQ = 22;

    /** S1MP1E_SHOT_MODE: null/"base" = the six base shots; "screens" = the BATCH-A screen sweep after the world. */
    private static String mode;
    /** Index into the screens sweep (see {@link #openSweepScreen}). */
    private static int sweepIdx;
    /** Frames waited in the current sweep sub-step. */
    private static int sweepFrames;
    /** Names each sweep shot; a null name from {@link #openSweepScreen} ends the sweep. */
    private static String sweepName;
    /** True while the current sweep step is the hovered-item tooltip scene (rule E/R1). */
    private static boolean sweepTooltip;
    /** BATCH-2 glide sweep (mode "glide"): sub-step index + frame counter within the step. */
    private static int gStep, gFrame;
    /** Frames a screen renders before the sweep captures it. */
    private static final int SWEEP_SETTLE = 45;

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions (createWorld, reloadResources, disconnect) pump the
     *  render loop synchronously, which re-fires this render-TAIL hook. Without this, the current
     *  step would run again mid-action (e.g. re-creating the world every pumped frame → recursion
     *  → StackOverflow/OOM). Nested frames pumped inside a step do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** Language application is retried across frames until it takes; then latched true. */
    private static boolean langDone;
    /** The future of the language-triggered resource reload; the title wait blocks on it so a shot
     *  is never taken over the reload's splash overlay. Null when no reload was needed. */
    private static java.util.concurrent.CompletableFuture<Void> reloadFuture;
    /** Target reference resolution — forced onto the framebuffer so shots are 1280x720 even when
     *  the desktop is smaller than that and the OS clamps the on-screen window. */
    private static final int SHOT_W = 1280, SHOT_H = 720;

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
                case P_SCREENS_OPEN:       stepSweepOpen(client);                break;
                case P_SCREENS_WAIT:       stepSweepWait(client);                break;
                case P_SCREENS_SHOT:       stepSweepShot(client);                break;
                case P_COMBAT:             if (CombatShot.step(client)) { frames = 0; phase = P_STOP; } break;
                case P_GLIDE:              stepGlide(client);                    break;
                case P_CONTAINERS:         stepContainers(client);               break;
                case P_HUD_SEQ:            stepHudSeq(client);                   break;
                case P_MODULES_SEQ:        stepModulesSeq(client);               break;
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
            if (win.getFramebufferWidth() == SHOT_W && win.getFramebufferHeight() == SHOT_H) return;
            java.lang.reflect.Field fw = net.minecraft.client.util.Window.class.getDeclaredField("framebufferWidth");
            java.lang.reflect.Field fh = net.minecraft.client.util.Window.class.getDeclaredField("framebufferHeight");
            fw.setAccessible(true); fh.setAccessible(true);
            fw.setInt(win, SHOT_W); fh.setInt(win, SHOT_H);
            client.onResolutionChanged();   // resizes the framebuffer + GUI scale to the forced size
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + SHOT_W + "x" + SHOT_H);
        } catch (Throwable t) {
            skip("force framebuffer " + SHOT_W + "x" + SHOT_H, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(MinecraftClient client) {
        // Window + GUI scale — idempotent, so safe to re-run on every language-retry frame below.
        try {
            client.getWindow().setWindowedSize(1280, 720);
            // 1.18.2: GUI scale is the plain options.guiScale int field (no SimpleOption wrapper).
            client.options.guiScale = 2;
            // The harness window runs unfocused; with the default pauseOnLostFocus, singleplayer
            // pops the pause menu the instant focus is lost, so currentScreen never returns to null
            // and the world-load / settle waits (which require currentScreen == null) time out and
            // every world-dependent shot is skipped. Turn it off (same guard the 1.16.5 line uses).
            client.options.pauseOnLostFocus = false;
        } catch (Throwable t) {
            skip("init (window/scale)", t);
        }
        // Language -> zh_tw, RETRIED across frames. 1.18.2's LanguageManager works in
        // LanguageDefinition (looked up by code), not raw String codes as in 1.20.1, and its
        // definition map is only populated by the INITIAL resource reload — which is still running
        // on this first rendered frame (P_INIT fires at render TAIL while the SplashOverlay drives
        // that reload). An early getLanguage("zh_tw") therefore returns null and the switch would be
        // silently skipped, leaving the whole run in en_us. So keep re-checking each frame until the
        // definition is available, then set it + reload exactly once. (1.16.5 happens to reach its
        // first frame after the list is populated, so its one-shot form works there; 1.18.2 needs
        // the retry.) Capped so a version that never populates the list still proceeds.
        if (!langDone) {
            try {
                LanguageDefinition cur = client.getLanguageManager().getLanguage();
                if (cur != null && "zh_tw".equals(cur.getCode())) {
                    langDone = true;                    // already applied (e.g. after our reload)
                } else {
                    LanguageDefinition def = client.getLanguageManager().getLanguage("zh_tw");
                    if (def != null) {
                        client.getLanguageManager().setLanguage(def);
                        client.options.language = "zh_tw";
                        // Keep the future: the reload runs behind a SplashOverlay and finishes a
                        // second or two later; stepWaitTitle blocks on isDone() so the title shot is
                        // never captured over that splash. (Re-entrant frames guarded by `busy`.)
                        reloadFuture = client.reloadResources();
                        System.out.println("[S1mp1e][DevShot] language set to zh_tw");
                        langDone = true;
                    }
                }
            } catch (Throwable t) {
                skip("init (language)", t);
                langDone = true;                        // hard failure: don't spin forever
            }
            if (!langDone) {
                if (++frames > 1800) {                   // list never populated — proceed anyway
                    skip("set language zh_tw",
                            new IllegalStateException("zh_tw definition not available after wait"));
                    langDone = true;
                } else {
                    return;                             // stay in P_INIT, retry next frame
                }
            }
        }
        try { client.onResolutionChanged(); } catch (Throwable ignored) {}
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static void stepWaitTitle(MinecraftClient client) {
        // Wait past the Mojang splash / any resource reload before shooting the title. The language
        // reload keeps currentScreen == the (stale) TitleScreen the whole time it runs, and the
        // overlay==null gate alone proved unreliable across the reload's splash — so also require the
        // reload future to have completed. reloadFuture is null when no reload was needed.
        boolean reloadDone = reloadFuture == null || reloadFuture.isDone();
        if (reloadDone && client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
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
            if ("screens".equals(mode)) {
                // BATCH-A sweep: open each glass screen in turn and capture it, then stop.
                sweepIdx = 0; sweepFrames = 0; phase = P_SCREENS_OPEN;
                return;
            }
            if ("combat".equalsIgnoreCase(mode)) { frames = 0; phase = P_COMBAT; return; }   // 26.2 combat trio
            if ("glide".equals(mode)) {
                // BATCH-2 (B/C/D) sweep: drive the creative screen — fused tabs, tab cross-fade burst, sub-pixel
                // scroll-glide burst, mid-glide click. Runs entirely on the creative screen (opens standalone).
                gStep = 0; gFrame = 0; phase = P_GLIDE;
                return;
            }
            if ("containers".equals(mode)) {
                // BATCH-2 (C/D) container sweep: stage the stonecutter + loom with populated handlers (they draw their
                // list INSIDE drawBackground — the KNOWN BLOCKER screens), confirm the list survives + the glass panel
                // + glass scrollbar, and burst-capture a scroll glide on each (loom uses the version-specific rewrite).
                gStep = 0; gFrame = 0; phase = P_CONTAINERS;
                return;
            }
            if ("hud".equals(mode)) {
                // BATCH-B (G) HUD overlays: chat panel + input, action bar pill, toast card, boss bar capsule,
                // name-tag plate, tab list — each staged in-world and captured. Runs on the in-world HUD.
                sweepFrames = 0; phase = P_HUD_SEQ;
                return;
            }
            if ("modules".equals(mode)) {
                // BATCH-B (H) modules: Block Outline (width 1/8, custom colour, chroma, fill) + Chroma HUD
                // (per-char / wave-0) + the two zh-TW config pages. Module state snapshotted + restored.
                sweepFrames = 0; phase = P_MODULES_SEQ;
                return;
            }
            open(client, new S1mp1eConfigScreen(), "open settings (over world)");
            frames = 0; phase = P_WAIT_CONFIG2;
        }
    }

    // ---- BATCH-A screens sweep (mode "screens") ---------------------------
    //
    // Opens each glass screen in turn, waits for it to settle, captures it, moves on; a tooltip step warps the
    // GLFW cursor over an inventory item so the top-layer glass tooltip (rule E/R1) is captured. Guarded per step
    // so a failure only skips that shot. Ends at P_STOP (leave world + quit).

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
                case 7:
                    sweepName = "book-edit.png";
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.BookEditScreen(
                            client.player, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WRITABLE_BOOK),
                            net.minecraft.util.Hand.MAIN_HAND));
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
            return idx + 1 <= 7;
        }
    }

    /** A one-page written book for the book-glass shot. */
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

    /** Warp the GLFW cursor over a creative item-grid slot so the hovered-item glass tooltip shows. The screens sweep
     *  runs in creative game mode, so the open inventory renders as the creative screen (bg 195x136, centred). A
     *  right-column grid slot puts the card over neighbouring items + their stack counts AND reaches toward the effect
     *  strip on the right — the rule E / R1 overlap scene. */
    private static void hoverInventoryItem(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            int sw = win.getScaledWidth(), sh = win.getScaledHeight();
            int left = (sw - 195) / 2, top = (sh - 136) / 2;
            double scx = left + 9 + 6 * 18 + 8;   // grid col 6 centre (card opens right over cols 7-8 + the strip edge)
            double scy = top + 18 + 2 * 18 + 8;    // grid row 2 centre
            double sxr = (double) win.getWidth() / sw, syr = (double) win.getHeight() / sh;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(win.getHandle(), scx * sxr, scy * syr);
        } catch (Throwable t) {
            skip("warp cursor for tooltip", t);
        }
    }

    // ---- BATCH-2 glide sweep (mode "glide") -------------------------------
    //
    // Drives the creative screen to verify B/C/D with consecutive-frame bursts (filmstrips), all on a screen that
    // opens standalone. Written as a linear frame script (gStep / gFrame). Every reflection/step is guarded so a
    // failure skips that piece rather than the run. Ends at P_STOP.
    //   tabs.png          — the fused tab band at rest (B): body+tabs one sheet, sliding pill, centred icons.
    //   tabswitch-0..5    — a row-switch tab select: the selection pill cross-fades between rows (B motion).
    //   glide-0..5        — a scroll jump: the item grid glides sub-pixel + the glass thumb moves as one (C+D).
    //   click-midglide    — a click while mid-glide (D correctness: snap-to-target-first path exercised).

    private static void stepGlide(MinecraftClient client) {
        switch (gStep) {
            case 0:   // open the creative screen
                try {
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen(client.player));
                } catch (Throwable t) { skip("glide open creative", t); phase = P_STOP; return; }
                gStep = 1; gFrame = 0; return;
            case 1:   // settle, then select a scrollable top-row tab and snap the scroll to the top
                if (++gFrame < 12) return;
                selectTab(client, net.minecraft.item.ItemGroup.BUILDING_BLOCKS);
                setCreativeScroll(client, 0f);
                gStep = 2; gFrame = 0; return;
            case 2:   // rest, capture the fused band, then the #5 automated tab-click self-test
                if (++gFrame < 20) return;
                capture(client, "tabs.png");
                s1mp1e$tabClickTest(client);
                gStep = 3; gFrame = 0; return;
            case 3:   // tab cross-fade: switch to a tab in the OTHER row, burst-capture the pill motion
                if (gFrame == 0) selectTab(client, s1mp1e$otherRowGroup());
                capture(client, "tabswitch-" + gFrame + ".png");
                if (++gFrame >= 6) { gStep = 4; gFrame = 0; }
                return;
            case 4:   // back to the scrollable tab, snap to the top, settle the thumb
                if (gFrame == 0) { selectTab(client, net.minecraft.item.ItemGroup.BUILDING_BLOCKS); setCreativeScroll(client, 0f); }
                if (++gFrame < 16) return;
                gStep = 5; gFrame = 0; return;
            case 5:   // scroll glide: jump the logical scroll; the thumb + grid ease toward it — burst-capture
                if (gFrame == 0) setCreativeScroll(client, 0.5f);
                capture(client, "glide-" + gFrame + ".png");
                if (++gFrame >= 6) { gStep = 6; gFrame = 0; }
                return;
            case 6:   // mid-glide click: snap to top, settle, jump, then click a grid slot one frame into the glide
                if (gFrame == 0) setCreativeScroll(client, 0f);
                else if (gFrame == 16) setCreativeScroll(client, 0.7f);
                else if (gFrame == 17) { clickGridSlot(client); capture(client, "click-midglide.png"); }
                if (++gFrame >= 22) { gStep = 7; gFrame = 0; }
                return;
            case 7:   // #4 — park the cursor on the first grid slot: the creative slot lattice + glass hover pill
                if (gFrame == 0) { setCreativeScroll(client, 0f); s1mp1e$warpSlotCursor(client); }
                if (gFrame == 18) capture(client, "creative-hover.png");
                if (++gFrame >= 20) { gStep = 8; gFrame = 0; }
                return;
            default:
                phase = P_STOP; return;
        }
    }

    /**
     * #5 self-test — for EVERY visible creative tab, click the CENTRE of its drawn fused cell (absolute screen
     * coords, exactly as a real click) and assert {@code getSelectedTab()} became that tab. Vanilla mouseClicked
     * subtracts this.x/this.y before isClickInTab, so a PASS proves the fused-cell hit box (GlassTabs.hit, relative
     * space) lines up with the drawn cell. Logs a PASS/FAIL line per tab and a TOTAL. Dev-only; guarded.
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
                int col = g.getColumn();
                boolean top = g.isTopRow();
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
            selectTab(client, net.minecraft.item.ItemGroup.BUILDING_BLOCKS);   // restore a stable tab for later shots
        } catch (Throwable t) { skip("tab click test", t); }
    }

    /** Warp the GLFW cursor over the FIRST item grid slot so the creative slot hover pill shows (#4). */
    private static void s1mp1e$warpSlotCursor(MinecraftClient client) {
        try {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            dev.s1mp1e.glass.mixin.HandledScreenAccessor a =
                    (dev.s1mp1e.glass.mixin.HandledScreenAccessor) client.currentScreen;
            net.minecraft.client.util.Window win = client.getWindow();
            double cx = a.s1mp1e$x() + 9 + 8, cy = a.s1mp1e$y() + 18 + 8;   // grid slot (x+9, y+18) centre
            double sxr = (double) win.getWidth() / win.getScaledWidth();
            double syr = (double) win.getHeight() / win.getScaledHeight();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(win.getHandle(), cx * sxr, cy * syr);
        } catch (Throwable t) { skip("warp slot cursor", t); }
    }

    /** A creative tab in the row opposite BUILDING_BLOCKS (so a select triggers the pill cross-fade). */
    private static net.minecraft.item.ItemGroup s1mp1e$otherRowGroup() {
        try {
            boolean topWanted = !net.minecraft.item.ItemGroup.BUILDING_BLOCKS.isTopRow();
            for (net.minecraft.item.ItemGroup g : net.minecraft.item.ItemGroup.GROUPS) {
                if (g != null && g.isTopRow() == topWanted) return g;
            }
        } catch (Throwable ignored) {}
        return net.minecraft.item.ItemGroup.INVENTORY;
    }

    /** Invoke the private {@code CreativeInventoryScreen.setSelectedTab(ItemGroup)} so the grid repopulates + the
     *  static selected-tab index (which GlassTabs animates from) updates. Dev-only reflection. */
    private static void selectTab(MinecraftClient client, net.minecraft.item.ItemGroup group) {
        try {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen)) return;
            java.lang.reflect.Method m = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                    .getDeclaredMethod("setSelectedTab", net.minecraft.item.ItemGroup.class);
            m.setAccessible(true);
            m.invoke(client.currentScreen, group);
        } catch (Throwable t) {
            skip("glide select tab", t);
        }
    }

    /** Set the creative logical scroll ratio (private {@code scrollPosition} field) AND remap the item rows
     *  ({@code CreativeScreenHandler.scrollItems}) so the vanilla row-aligned scroll jumps while the glass thumb eases
     *  toward it — the exact state that arms the sub-pixel glide. */
    private static void setCreativeScroll(MinecraftClient client, float ratio) {
        try {
            if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen s)) return;
            java.lang.reflect.Field f = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                    .getDeclaredField("scrollPosition");
            f.setAccessible(true);
            f.setFloat(s, ratio);
            ((net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.CreativeScreenHandler)
                    s.getScreenHandler()).scrollItems(ratio);
        } catch (Throwable t) {
            skip("glide set scroll", t);
        }
    }

    /** Click a creative item-grid slot (col 4, row 2) via {@code mouseClicked} — exercises the mid-glide snap path. */
    private static void clickGridSlot(MinecraftClient client) {
        try {
            net.minecraft.client.util.Window win = client.getWindow();
            int sw = win.getScaledWidth(), sh = win.getScaledHeight();
            int left = (sw - 195) / 2, top = (sh - 136) / 2;
            double mx = left + 9 + 4 * 18 + 8;
            double my = top + 18 + 2 * 18 + 8;
            client.currentScreen.mouseClicked(mx, my, 0);
            client.currentScreen.mouseReleased(mx, my, 0);
        } catch (Throwable t) {
            skip("glide click grid slot", t);
        }
    }

    // ---- BATCH-2 container sweep (mode "containers") ----------------------
    //
    // Stages the stonecutter + loom with populated handlers (these draw their scrollable list INSIDE drawBackground —
    // the KNOWN BLOCKER screens), captures them at rest (list survives + glass panel + glass scrollbar) and burst-
    // captures a scroll glide on each (loom exercises the version-specific rewrite). Linear frame script; guarded.

    private static void stepContainers(MinecraftClient client) {
        switch (gStep) {
            case 0:   // stage the stonecutter with a stone input (many stonecutting recipes -> scrollbar active)
                try {
                    net.minecraft.screen.StonecutterScreenHandler h =
                            new net.minecraft.screen.StonecutterScreenHandler(1, client.player.getInventory(),
                                    net.minecraft.screen.ScreenHandlerContext.EMPTY);
                    h.input.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE, 64));
                    h.onContentChanged(h.input);   // -> updateInput -> availableRecipes + canCraft
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.StonecutterScreen(
                            h, client.player.getInventory(), net.minecraft.text.Text.of("Stonecutter")));
                } catch (Throwable t) { skip("stage stonecutter", t); gStep = 3; return; }
                gStep = 1; gFrame = 0; return;
            case 1:   // settle at rest, capture, then jump the logical scroll to arm the glide
                if (++gFrame < 16) return;
                capture(client, "stonecutter.png");
                setPrivateFloat(client.currentScreen,
                        net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollAmount", 0.6f);
                gStep = 2; gFrame = 0; return;
            case 2:   // burst-capture the stonecutter list glide (thumb eases + recipes glide as one)
                capture(client, "stonecutter-glide-" + gFrame + ".png");
                if (++gFrame >= 4) { gStep = 3; gFrame = 0; }
                return;
            case 3:   // stage the loom with a banner + dye (canApplyDyePattern -> the 4x4 pattern grid + scrollbar)
                try {
                    net.minecraft.screen.LoomScreenHandler h =
                            new net.minecraft.screen.LoomScreenHandler(1, client.player.getInventory(),
                                    net.minecraft.screen.ScreenHandlerContext.EMPTY);
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.LoomScreen(
                            h, client.player.getInventory(), net.minecraft.text.Text.of("Loom")));
                    // init() (run synchronously by setScreen) registered the inventory listener; setting the slots now
                    // fires it -> canApplyDyePattern + bannerPatterns populate.
                    h.getBannerSlot().setStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHITE_BANNER));
                    h.getDyeSlot().setStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.RED_DYE));
                    h.onContentChanged(h.getBannerSlot().inventory);
                } catch (Throwable t) { skip("stage loom", t); gStep = 6; return; }
                gStep = 4; gFrame = 0; return;
            case 4:   // settle at rest, capture, then jump the logical top row (firstPatternButtonId = 1 + row*4)
                if (++gFrame < 16) return;
                capture(client, "loom.png");
                setPrivateInt(client.currentScreen,
                        net.minecraft.client.gui.screen.ingame.LoomScreen.class, "firstPatternButtonId", 1 + 3 * 4);
                gStep = 5; gFrame = 0; return;
            case 5:   // burst-capture the loom pattern glide (frame sprite + banner model share the sub-pixel offset)
                capture(client, "loom-glide-" + gFrame + ".png");
                if (++gFrame >= 4) { gStep = 6; gFrame = 0; }
                return;
            default:
                phase = P_STOP; return;
        }
    }

    private static void setPrivateFloat(Object target, Class<?> decl, String name, float v) {
        try {
            java.lang.reflect.Field f = decl.getDeclaredField(name);
            f.setAccessible(true);
            f.setFloat(target, v);
        } catch (Throwable t) { skip("set " + name, t); }
    }

    private static void setPrivateInt(Object target, Class<?> decl, String name, int v) {
        try {
            java.lang.reflect.Field f = decl.getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, v);
        } catch (Throwable t) { skip("set " + name, t); }
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

    // ---- BATCH-B (G): HUD overlays (mode "hud") ---------------------------
    //
    // In-world, frame-scripted: add chat lines (glass panel), open the chat screen (glass input bar), show an action
    // bar message (glass pill), pop a system toast (glass card + slide), inject a client boss bar (blue capsule + glass
    // capsule) and spawn a named entity (frosted name-tag plate), then force the tab list. Each captured with the
    // in-world HUD; guarded per step. Text uses 1.18.2's LiteralText (no Text.literal until 1.19).

    private static void stepHudSeq(MinecraftClient client) {
        int f = sweepFrames++;
        try {
            switch (f) {
                case 1:
                    net.minecraft.client.gui.hud.ChatHud chat = client.inGameHud.getChatHud();
                    chat.addMessage(new net.minecraft.text.LiteralText("<S1mp1e> liquid glass chat panel"));
                    chat.addMessage(new net.minecraft.text.LiteralText("the widest line sets the panel width"));
                    chat.addMessage(new net.minecraft.text.LiteralText("per-line dark rects are dropped"));
                    break;
                case 40: capture(client, "chat.png"); break;                     // in-world: glass panel behind lines
                case 44:
                    client.setScreen(new net.minecraft.client.gui.screen.ChatScreen("liquid glass input bar"));
                    break;
                case 64: capture(client, "chat-input.png"); break;               // glass input bar + chat panel
                case 66: try { client.setScreen(null); } catch (Throwable ignored) {} break;
                case 70:
                    client.inGameHud.setOverlayMessage(new net.minecraft.text.LiteralText("Action Bar Pill"), false);
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
                    try { client.options.playerListKey.setPressed(false); } catch (Throwable ignored) {}
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
                    new net.minecraft.text.LiteralText("S1mp1e"),
                    new net.minecraft.text.LiteralText("liquid glass toast"));
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
                    java.util.UUID.randomUUID(), new net.minecraft.text.LiteralText("Liquid Glass Boss"), 0.65F,
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
            e.setCustomName(new net.minecraft.text.LiteralText("Frosted Name Tag"));
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
                        new net.minecraft.text.LiteralText("Players"),
                        net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER);
            }
            sb.setObjectiveSlot(0, obj);   // slot 0 = LIST
            client.options.playerListKey.setPressed(true);
        } catch (Throwable t) { skip("tab list visible", t); }
    }

    // ---- BATCH-B (H): modules (mode "modules") ----------------------------
    //
    // Enable Block Outline (recolour / chroma / width 1 & 8 / fill) while looking at the ground, and Chroma HUD
    // (per-char rainbow + uniform wave-0) over the FPS/Coords HUD, then open the config screen (zh-TW module labels).
    // All module state is snapshotted and restored at the end (nothing is saved to disk - the run quits after).

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
            DynamicRegistryManager.Immutable drm = DynamicRegistryManager.BUILTIN.get();
            GeneratorOptions gen = buildGeneratorOptions(drm, 12345L);
            // 1.18.2: the older MinecraftClient.createWorld path (no IntegratedServerLoader yet).
            client.createWorld("devshot", info, drm, gen);
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /**
     * Seeded default (noise) overworld for seed {@code seed}, built the way vanilla's default
     * "create world" does on 1.18.2: {@link DimensionType#createDefaultDimensionOptions} yields the
     * dimension registry from the built-in manager, wrapped in a {@link GeneratorOptions}. Falls
     * back to MC's random-seed default if the seeded assembly throws. Superflat needs a fragile
     * {@code FlatChunkGenerator} build on 1.18.2 and terrain is a never-judged cross-version
     * difference, so default terrain is used here (same choice the 1.16.5 line makes).
     */
    private static GeneratorOptions buildGeneratorOptions(DynamicRegistryManager drm, long seed) {
        try {
            Registry<DimensionOptions> dims = DimensionType.createDefaultDimensionOptions(drm, seed);
            return new GeneratorOptions(seed, true /* structures */, false /* bonus chest */, dims);
        } catch (Throwable t) {
            skip("seeded worldgen (using random-seed default)", t);
            return GeneratorOptions.getDefaultOptions(drm);
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
                // Creative game mode so the real CreativeInventoryScreen (fused tabs + item grid) renders instead of
                // falling back to the survival inventory (PORT_SPEC §5 DevShot limitation). Needed by the "screens"
                // (A) sweep and the "glide" (B/C/D) sweep.
                if ("screens".equals(mode) || "glide".equals(mode)) {
                    try { sp.changeGameMode(GameMode.CREATIVE); } catch (Throwable ignored) {}
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
