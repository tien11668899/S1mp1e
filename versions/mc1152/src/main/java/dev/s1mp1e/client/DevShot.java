package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.container.MerchantContainer;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TraderOfferList;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemGroup;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Hand;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import org.lwjgl.glfw.GLFW;

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
 * language to {@code zh_tw}, then walks a fixed script and writes six PNGs with the game's own
 * framebuffer writer ({@link ScreenshotUtils#takeScreenshot} + {@link NativeImage#writeFile}),
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
 * <p>1.15.2 port of the 1.14.4 (mc1144) harness, which is the closest API sibling (both are the
 * no-{@code MatrixStack} GUI, {@code LevelInfo}/{@code LevelGeneratorType} worldgen, Java-8 line).
 * The three deltas from 1.14.4: the {@code window} field is private on {@code MinecraftClient} in
 * 1.15.2, so the GLFW handle comes from {@code client.getWindow()}; the entity position fields
 * {@code x/y/z} became private, so the teleport reads {@code getX()/getY()/getZ()}; and the
 * framebuffer→{@link NativeImage} helper is now the mapped {@code ScreenshotUtils.takeScreenshot}
 * (unmapped {@code method_1663} in yarn 1.14.4). Everything else — {@code options.guiScale} int
 * field, {@link LanguageDefinition} compared via {@code getCode}, {@code openScreen} (not
 * {@code setScreen}), {@code startIntegratedServer(save, name, LevelInfo)} with cheats via
 * {@code enableCommands()} and flat via {@code LevelGeneratorType.FLAT}, time/weather through
 * {@link LevelProperties}, difficulty via {@code server.setDifficulty}, {@code PlayerInventory
 * .setInvStack}, {@link VideoOptionsScreen} in {@code ...gui.screen} — is identical to 1.14.4.
 */
public final class DevShot {
    private DevShot() {}

    private static final int TITLE_FRAMES   = 60;
    private static final int CONFIG_FRAMES  = 90;
    private static final int WORLD_SETTLE   = 100;  // frames the world renders before the loadout
    private static final int WORLD_FRAMES   = 60;
    private static final int INV_FRAMES     = 60;
    private static final int OPTIONS_FRAMES = 60;

    /** Per-wait frame caps so one stuck wait skips rather than eating the whole run. */
    private static final int WAIT_WORLD_CAP  = 2400;  // flat-world gen is quick; generous anyway
    private static final int WAIT_SCREEN_CAP = 300;

    /** Global watchdog: quit no matter what after this long. */
    private static final long WATCHDOG_MS = 240_000L;

    /** Target reference resolution — forced onto the framebuffer so shots are 1280x720 even when
     *  the desktop is smaller than that and the OS clamps the on-screen window. On this box the
     *  1.15.2 window comes up clamped to ~1028 wide (DPI/work-area), so unlike 1.14.4 the plain
     *  {@code glfwSetWindowSize} is not enough on its own; the framebuffer is forced every frame. */
    private static final int SHOT_W = 1280, SHOT_H = 720;

    // State machine phases.
    private static final int P_INIT = 0, P_WAIT_TITLE = 1, P_TITLE = 2,
                             P_WAIT_CONFIG = 3, P_CONFIG = 4,
                             P_WAIT_WORLD = 5, P_WORLD_SETTLE = 6, P_WORLD = 7,
                             P_WAIT_CONFIG2 = 8, P_CONFIG_WORLD = 9,
                             P_WAIT_INV = 10, P_INV = 11,
                             P_WAIT_OPTIONS = 12, P_OPTIONS = 13,
                             P_STOP = 14, P_DONE = 15,
                             P_SCENES = 16;   // BATCH-A/E/F mode scenes (S1MP1E_SHOT_MODE)

    /** Frames to hold a mode scene before capturing, so fades / tooltip springs settle. */
    private static final int SCENE_SETTLE = 48;

    /** DEV-ONLY: when set, {@code InGameHudMixin}'s render-HEAD hook forces the player-list key pressed for that one
     *  frame, so the vanilla tab list renders in the headless DevShot window (where {@code updatePressedStates} reads
     *  the physical GLFW key each frame and would otherwise clear a plain {@code setPressed}). Defaults false — a
     *  normal game run never sets it, so production behaviour is unchanged. */
    public static volatile boolean forceTabListKey;

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions (startIntegratedServer, reloadResources) pump the render
     *  loop synchronously, which re-fires this render-TAIL hook. Without this, the current step
     *  would run again mid-action (e.g. re-creating the world every pumped frame → recursion →
     *  StackOverflow/OOM). Nested frames pumped inside a step do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** The one-shot zh_tw language switch has been attempted (applied on the first fully-shown title
     *  frame, not in stepInit — see {@link #tryApplyLanguage}). */
    private static boolean langApplied;

    /** {@code S1MP1E_SHOT_MODE}: {@code null}/"base" = the original six-shot base sweep; otherwise a
     *  BATCH-A/E/F scene mode ("screens" / "effects" / "tooltips"). Resolved once with the env vars. */
    private static String  mode;
    /** Built once on entering {@link #P_SCENES}: the ordered scene list for {@link #mode}. */
    private static Scene[] scenes;
    private static int     sceneIdx;
    private static int     sceneTimer;
    /** Scaled-GUI hover point for the current scene, or -1 when the scene needs no pinned cursor. */
    private static int     sceneMouseX = -1, sceneMouseY = -1;

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
                mode = (m == null || m.trim().isEmpty()) ? "base" : m.trim().toLowerCase();
            } catch (Throwable t) {
                mode = "base";
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
                case P_SCENES:             stepScenes(client);                     break;
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
     * 1280x720 regardless of the desktop size. On a desktop smaller than 1280x720 (or under DPI
     * scaling) the OS clamps the on-screen window, but the off-screen framebuffer we screenshot can
     * still be full size — the frame renders into it at 1280x720 and only the (unwatched) on-screen
     * blit is scaled.
     *
     * <p>Dev-only: DevShot runs solely under {@code S1MP1E_SHOT} (never in a launcher build), so
     * reflecting the yarn-named private {@code framebufferWidth/framebufferHeight} fields of
     * {@code Window} is safe here. No-op once the size already matches, so this is cheap to call
     * every frame and self-heals after any stray GLFW framebuffer-resize callback.
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
        try {
            // Identical rendering conditions for every version. 1.15.2's Window exposes no
            // setWindowedSize; resize the GLFW window directly. Restore first in case the dev
            // window came up maximized, then size + recompute. The window field is private in
            // 1.15.2 (public in 1.14.4), so the GLFW handle comes from getWindow().
            long handle = client.getWindow().getHandle();
            GLFW.glfwRestoreWindow(handle);
            GLFW.glfwSetWindowSize(handle, 1280, 720);
            client.options.guiScale = 2;
            // The harness runs the window in the background (no OS focus). In singleplayer 1.15.2
            // pauseOnLostFocus opens the pause menu the instant focus is lost, which would keep
            // currentScreen non-null forever and wedge the world-load wait — so turn it off.
            client.options.pauseOnLostFocus = false;
            client.onResolutionChanged();
            // NOTE: the language switch is deliberately NOT done here. stepInit runs on the very
            // first rendered frame, while the game's initial resource reload may still be in flight
            // (the TitleScreen is already currentScreen behind the fading splash overlay), so the
            // LanguageManager's language list can be empty and getLanguage("zh_tw") returns null —
            // the switch silently no-ops and every vanilla string stays English. It is applied in
            // stepWaitTitle instead, once the title is fully shown and resources are loaded.
        } catch (Throwable t) {
            skip("init (window/scale)", t);
        }
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static void stepWaitTitle(MinecraftClient client) {
        // Wait past the Mojang splash / any resource reload; the TitleScreen becomes currentScreen
        // behind the SplashOverlay while it is still fading, so also require the overlay to be gone.
        boolean titleShown = client.currentScreen instanceof TitleScreen && client.getOverlay() == null;
        if (titleShown && !langApplied) {
            // Resources are loaded now, so the language list is populated. Apply zh_tw (which fires
            // its own async resource reload); then fall through and wait for the title to settle
            // again before shooting, so title.png is captured in the target language.
            tryApplyLanguage(client);
            langApplied = true;
            frames = 0;
            return;
        }
        if (titleShown) {   // langApplied — the post-switch title is back up
            frames = 0; phase = P_TITLE;
        } else if (++frames > WAIT_SCREEN_CAP * 4) {
            skip("wait title screen", new IllegalStateException("title never shown"));
            langApplied = true;            // don't retry the switch after giving up
            frames = 0; phase = P_TITLE;   // try to shoot whatever is on screen, then continue
        }
    }

    /**
     * Switch the game language to {@code zh_tw} (so every version's shots are in the same language as
     * the 1.20.1 reference) and trigger the resource reload that applies it. Called once, only after
     * the title screen is fully shown so the language list is guaranteed populated. If the {@code
     * zh_tw} definition is somehow absent, the available codes are logged and the game is left in its
     * current language rather than hanging.
     */
    private static void tryApplyLanguage(MinecraftClient client) {
        try {
            LanguageDefinition cur = client.getLanguageManager().getLanguage();
            if (cur != null && "zh_tw".equals(cur.getCode())) return;   // already set
            LanguageDefinition def = client.getLanguageManager().getLanguage("zh_tw");
            if (def == null) {
                StringBuilder codes = new StringBuilder();
                try {
                    for (LanguageDefinition d : client.getLanguageManager().getAllLanguages()) {
                        codes.append(d.getCode()).append(' ');
                    }
                } catch (Throwable ignored) {}
                skip("apply language zh_tw", new IllegalStateException(
                        "zh_tw not in language list; available: " + codes));
                return;
            }
            client.getLanguageManager().setLanguage(def);
            client.options.language = "zh_tw";
            client.reloadResources();
            client.onResolutionChanged();
            System.out.println("[S1mp1e][DevShot] language -> zh_tw (reload triggered)");
        } catch (Throwable t) {
            skip("apply language zh_tw", t);
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
        frames = 0;
        // BATCH-A/E/F: a non-base S1MP1E_SHOT_MODE diverts to the scene runner instead of the base
        // world/config-world/inventory/options tail. The base sweep is unchanged when mode is unset.
        phase = "base".equals(mode) ? P_WORLD : P_SCENES;
    }

    private static void stepWorld(MinecraftClient client) {
        // Suppress the transient advancement/recipe toasts and the advancement chat line that the
        // scripted loadout triggers, so the over-world reference frames (world / config-world /
        // inventory) are clean and deterministic. Cleared every frame here; no new toasts appear
        // after the loadout, so the later over-world shots stay clean too.
        try { client.getToastManager().clear(); } catch (Throwable ignored) {}
        try {
            ChatHud chat = client.inGameHud.getChatHud();
            if (chat != null) chat.clear(false);
        } catch (Throwable ignored) {}
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
        // VideoOptionsScreen's constructor dereferences parent state, so the parent must be an
        // already-shown screen. Reuse the inventory that is still current. Build inside try because
        // the construction runs as an argument — outside open()'s own try/catch — so any failure
        // would otherwise escape.
        try {
            Screen parent = client.currentScreen;
            client.openScreen(new VideoOptionsScreen(parent, client.options));
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
        // leave+quit. We deliberately do NOT disconnect here: disconnect runs its own nested
        // world-unload/"saving" render loop, and invoking that from inside this render-TAIL hook
        // wedges the render thread (the run then only ends when the watchdog force-quits).
        phase = P_STOP;
    }

    private static void stepStop(MinecraftClient client) {
        System.out.println("[S1mp1e][DevShot] done, quitting.");
        forceTabListKey = false;
        resetModules();   // in case a shutdown path saves config: leave the new modules OFF at their defaults
        try { client.scheduleStop(); } catch (Throwable ignored) {}
        phase = P_DONE;
    }

    /** Disable the two BATCH-B modules and reset their settings so a stray shutdown save never persists a scene's
     *  toggles. DevShot only ever mutates these in memory; nothing is written to disk by the scenes themselves. */
    private static void resetModules() {
        for (String name : new String[] { "BlockOutline", "ChromaHud" }) {
            try {
                Module m = ModuleManager.byName(name);
                if (m == null) continue;
                m.setEnabled(false);
                for (Setting s : m.settings) s.reset();
            } catch (Throwable ignored) {}
        }
    }

    // ---- BATCH-A/E/F scene modes (S1MP1E_SHOT_MODE) ------------------------
    //
    // These are ADDITIVE and inert without both S1MP1E_SHOT and S1MP1E_SHOT_MODE: the base sweep
    // (mode unset / "base") never enters P_SCENES. Each scene opens a screen and/or stages player
    // state once, holds it for SCENE_SETTLE frames (fades + tooltip springs settle), then captures.
    // Every action is guarded so one failed scene skips rather than wedging the run.

    /** One capture: a name + a one-shot staging action (open a screen, give effects, pin the cursor). */
    private interface Stage { void run(MinecraftClient c); }
    private static final class Scene {
        final String name; final Stage stage;
        /** Fired ONCE after the settle, just before the (first) capture — e.g. a wheel step to start a glide. */
        final Stage action;
        /** &gt;1 captures {@code burst} consecutive frames (name-00.png..) after the action, to film motion. */
        final int burst;
        /** Run EVERY held frame (HUD staging that must stay fresh: re-issue the action bar, keep the player
         *  looking at the outline block, keep the tab-list key pressed). Null = nothing per frame. */
        final Stage hold;
        Scene(String name, Stage stage) { this(name, stage, null, 1, null); }
        Scene(String name, Stage stage, Stage action, int burst) { this(name, stage, action, burst, null); }
        Scene(String name, Stage stage, Stage action, int burst, Stage hold) {
            this.name = name; this.stage = stage; this.action = action; this.burst = Math.max(1, burst); this.hold = hold;
        }
    }

    private static Scene[] buildScenes(MinecraftClient client) {
        if ("screens".equals(mode)) {
            // Feature (A): the remaining screens framed in glass.
            return new Scene[] {
                new Scene("advancements.png", c ->
                        open(c, new AdvancementsScreen(c.player.networkHandler.getAdvancementHandler()),
                             "advancements")),
                new Scene("stats.png", c ->
                        open(c, new StatsScreen(null, c.player.getStatHandler()), "stats")),
                new Scene("book.png", DevShot::openBook),
                new Scene("death.png", c ->
                        open(c, new DeathScreen(new LiteralText("DevShot"), false), "death")),
            };
        }
        if ("effects".equals(mode)) {
            // Feature (F): the status-effect list as ONE continuous glass strip beside the inventory.
            return new Scene[] {
                new Scene("effects-wide.png", c -> {
                    giveDemoEffects(c);
                    open(c, new InventoryScreen(c.player), "inventory (effects)");
                }),
            };
        }
        if ("tooltips".equals(mode)) {
            // Feature (E, R1): the tooltip card on the very top layer, refracting the GUI below it —
            // hover the top-left main-inventory item so its multi-line card overlaps the next row of
            // items and their stack-count digits, with the effect strip present to the left.
            return new Scene[] {
                new Scene("tooltip-nextrow.png", c -> {
                    giveDemoEffects(c);
                    fillTooltipGrid(c);
                    open(c, new InventoryScreen(c.player), "inventory (tooltip)");
                    pinMouse(248, 189);   // scaled-GUI centre of main-inventory slot 9 (top-left)
                }),
            };
        }
        if ("creative".equals(mode)) {
            // Feature (B): the fused-band creative tabs + (C) the glass scrollbar. Each scene selects a tab so the
            // lifted glass pill sits on that cell (proving the slide TARGET geometry per row/column), and the last
            // scene pins the cursor over a different tab so the fainter hover pill shows beside the selected pill.
            return new Scene[] {
                new Scene("creative-building.png", c -> openCreative(c, ItemGroup.BUILDING_BLOCKS)),
                new Scene("creative-redstone.png", c -> openCreative(c, ItemGroup.REDSTONE)),
                new Scene("creative-combat.png",   c -> openCreative(c, ItemGroup.COMBAT)),
                new Scene("creative-search.png",   c -> openCreative(c, ItemGroup.SEARCH)),
                new Scene("creative-hover.png", c -> {
                    openCreative(c, ItemGroup.BUILDING_BLOCKS);
                    int[] g = creativeGeom(c.currentScreen);
                    int cell = cellOf(ItemGroup.REDSTONE, g[2]);
                    float cw = g[2] / 7f;
                    pinMouse(Math.round(g[0] + (cell + 0.5f) * cw), g[1] - 14);   // hover the REDSTONE top-band cell
                }),
                // #4 — the creative slot lattice + the gliding glass hover pill over an item slot
                new Scene("creative-slot-hover.png", c -> {
                    openCreative(c, ItemGroup.BUILDING_BLOCKS);
                    int[] g = creativeGeom(c.currentScreen);
                    pinMouse(g[0] + 9 + 8, g[1] + 18 + 8);                         // first grid slot centre
                }),
                // #4 — the creative INVENTORY tab (armor / off-hand / hotbar slots) with its lattice
                new Scene("creative-inventory.png", c -> openCreative(c, ItemGroup.INVENTORY)),
            };
        }
        if ("glide".equals(mode)) {
            // Feature (D): after ONE wheel step the item grid GLIDES sub-pixel; the burst films the decelerating settle
            // (thumb + content move as one; clean scissor edges; row-aligned settle at the end).
            return new Scene[] {
                new Scene("glide-creative.png",
                        c -> openCreative(c, ItemGroup.BUILDING_BLOCKS),
                        c -> wheelCreative(c, -4.0), 14),
            };
        }
        if ("merchant".equals(mode)) {
            // Feature (D) for the villager trade list: the trades already show on the shared glass panel; this proves the
            // C glass scrollbar at rest and the D sub-pixel content glide. merchant.png = at rest; merchant-glide burst =
            // after ONE wheel step the trade column GLIDES sub-pixel (thumb + content as one, scissor-clean, row-aligned
            // settle at the end). A synthetic client-side MerchantContainer is staged with 12 distinct trades so it scrolls.
            return new Scene[] {
                new Scene("merchant.png", DevShot::openMerchant),
                new Scene("merchant-glide.png",
                        DevShot::openMerchant,
                        c -> wheelMerchant(c, -4.0), 14),
            };
        }
        if ("clicks".equals(mode)) {
            // Feature (B/D) click correctness, automated: tab hit boxes follow the drawn cells, the empty gap cell
            // selects nothing, and a click while the grid is mid-glide does not crash (it snaps to the target first).
            return new Scene[] {
                new Scene("clicks.png",
                        c -> openCreative(c, ItemGroup.BUILDING_BLOCKS),
                        DevShot::runClickTests, 1),
            };
        }
        if ("hud".equals(mode)) {
            // Feature (G): the HUD overlays as liquid glass, staged in the world with no screen open (except the
            // chat-input scene). Each is verified by PIXELS: chat panel + boss bar + action bar + name tag + toast in
            // one frame, then the focused chat input bar, then the tab list.
            return new Scene[] {
                new Scene("hud.png",
                        DevShot::stageHud,           // chat lines + boss bar + name tag + toast + action bar
                        null, 1,
                        DevShot::holdHud),           // keep the action bar fresh (it fades after 60 ticks)
                new Scene("hud-chatinput.png", c -> {
                    stageChatLines(c);
                    try { c.openScreen(new ChatScreen("")); } catch (Throwable t) { skip("open chat input", t); }
                }),
                new Scene("hud-tablist.png",
                        DevShot::stageTabList,
                        null, 1,
                        c -> { forceTabListKey = true; }),
            };
        }
        if ("modules".equals(mode)) {
            // Feature (H): Block Outline (width 1 vs 8 proves the width setting works; chroma; translucent fill) and
            // Chroma HUD (per-char wave vs flat), plus the zh-TW config labels. Module values are set in each stage
            // and restored at the very end (nothing saved to disk).
            return new Scene[] {
                new Scene("outline-w8.png", c -> {
                    setEnabled("BlockOutline", true);
                    setColor("BlockOutline", "Colour", 0xFF33E0FF);   // opaque cyan
                    setNum("BlockOutline", "Line width", 8.0);
                    setBool("BlockOutline", "Chroma", false);
                    setBool("BlockOutline", "Fill", false);
                    faceOutlineBlock(c);
                }, null, 1, DevShot::faceOutlineBlock),
                new Scene("outline-w1.png", c -> {
                    setNum("BlockOutline", "Line width", 1.0);
                    faceOutlineBlock(c);
                }, null, 1, DevShot::faceOutlineBlock),
                new Scene("outline-chroma.png", c -> {
                    setNum("BlockOutline", "Line width", 6.0);
                    setBool("BlockOutline", "Chroma", true);
                    faceOutlineBlock(c);
                }, null, 1, DevShot::faceOutlineBlock),
                new Scene("outline-fill.png", c -> {
                    setBool("BlockOutline", "Chroma", false);
                    setColor("BlockOutline", "Colour", 0xFF33E0FF);
                    setBool("BlockOutline", "Fill", true);
                    setColor("BlockOutline", "Fill colour", 0x5533E0FF);
                    faceOutlineBlock(c);
                }, null, 1, DevShot::faceOutlineBlock),
                new Scene("hud-chroma-wave.png", c -> {
                    setEnabled("BlockOutline", false);
                    setEnabled("ChromaHud", true);
                    setNum("ChromaHud", "Wave", 0.5);
                    setNum("ChromaHud", "Speed", 1.0);
                }),
                new Scene("hud-chroma-flat.png", c -> setNum("ChromaHud", "Wave", 0.0)),
                new Scene("config-blockoutline.png", c -> {
                    setEnabled("ChromaHud", false);
                    openConfigDetail(c, "Visual", "BlockOutline");
                }),
                new Scene("config-chromahud.png", c -> openConfigDetail(c, "HUD", "ChromaHud")),
            };
        }
        if ("extras".equals(mode)) {
            // Contact-sheet extras (verification pass): (B) the selected pill SLIDING within a row filmed frame-by-frame,
            // and (C) the glass scrollbar thumb held as a refracting LENS. Both inert without the env vars, like every
            // other mode.
            return new Scene[] {
                // (B) pill slide: settle on BUILDING_BLOCKS (pill on cell 0), then select REDSTONE (cell 2, same top
                // row) so the lifted pill SLIDES cell 0 -> cell 2 over the 55/30 springs. The burst films the slide.
                new Scene("pillslide.png",
                        c -> openCreative(c, ItemGroup.BUILDING_BLOCKS),
                        c -> selectCreativeTab(c, ItemGroup.REDSTONE), 12),
                // (C) held lens: open BUILDING_BLOCKS (has a scrollbar), pin the cursor mid-track and force the drag
                // state every frame so the thumb renders as the refracting lens (knobLens) at the cursor.
                new Scene("heldlens.png",
                        c -> {
                            openCreative(c, ItemGroup.BUILDING_BLOCKS);
                            int[] g = creativeGeom(c.currentScreen);
                            pinMouse(g[0] + g[2] - 14, g[1] + 66);   // scrollbar thumb column, mid-track
                        },
                        null, 1,
                        DevShot::holdScrollbarDrag),
            };
        }
        skip("scene mode", new IllegalStateException("unknown S1MP1E_SHOT_MODE '" + mode + "'"));
        return new Scene[0];
    }

    /** Extras (B): reflectively select a creative tab on the open screen so the lifted pill animates toward it. */
    private static void selectCreativeTab(MinecraftClient c, ItemGroup group) {
        try {
            Screen s = c.currentScreen;
            if (!(s instanceof CreativeInventoryScreen)) return;
            java.lang.reflect.Method m = CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab", ItemGroup.class);
            m.setAccessible(true);
            m.invoke(s, group);
        } catch (Throwable t) { skip("select creative tab", t); }
    }

    /** Extras (C): force the creative screen's private {@code scrolling} flag true every frame so the glass scrollbar
     *  draws its thumb as the held refracting lens (the redirect passes {@code scrolling && active} as the drag flag). */
    private static void holdScrollbarDrag(MinecraftClient c) {
        try {
            Screen s = c.currentScreen;
            if (!(s instanceof CreativeInventoryScreen)) return;
            java.lang.reflect.Field f = net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen.class
                    .getDeclaredField("scrolling");
            f.setAccessible(true);
            f.setBoolean(s, true);
        } catch (Throwable t) { skip("hold scrollbar drag", t); }
    }

    // ---- BATCH-B stage 3: HUD overlays (G) + modules (H) staging ------------

    /** Run a command from the integrated server console (permission level 4). */
    private static void runCmd(MinecraftClient c, String command) {
        try {
            MinecraftServer s = c.getServer();
            if (s != null) s.getCommandManager().execute(s.getCommandSource(), command);
        } catch (Throwable t) {
            skip("cmd '" + command + "'", t);
        }
    }

    /** Add several chat lines so the frosted chat panel hugs the widest one. */
    private static void stageChatLines(MinecraftClient c) {
        try {
            net.minecraft.client.gui.hud.ChatHud chat = c.inGameHud.getChatHud();
            if (chat == null) return;
            chat.clear(false);
            chat.addMessage(new LiteralText("[S1mp1e] Liquid glass HUD online."));
            chat.addMessage(new LiteralText("<Steve> the chat panel hugs the widest line"));
            chat.addMessage(new LiteralText("<Alex> nice frosted glass"));
            chat.addMessage(new LiteralText("[Server] boss bar + action bar + toasts too"));
            chat.addMessage(new LiteralText("gg"));
        } catch (Throwable t) {
            skip("stage chat lines", t);
        }
    }

    /** G scene: chat + a purple boss bar (62%) + a name-tag armour stand + a system toast + the action bar. */
    private static void stageHud(MinecraftClient c) {
        stageChatLines(c);
        runCmd(c, "gamerule sendCommandFeedback false");
        runCmd(c, "bossbar add s1mp1e:demo {\"text\":\"Ender Dragon\"}");
        runCmd(c, "bossbar set s1mp1e:demo max 100");
        runCmd(c, "bossbar set s1mp1e:demo value 62");
        runCmd(c, "bossbar set s1mp1e:demo players @a");
        runCmd(c, "bossbar set s1mp1e:demo visible true");
        // Name tag (G6): an armour stand a few blocks ahead with a visible custom name.
        runCmd(c, "execute at @p run summon armor_stand ~ ~ ~4 "
                + "{NoGravity:1b,Marker:1b,CustomName:\"{\\\"text\\\":\\\"S1mp1e\\\"}\",CustomNameVisible:1b}");
        // Toast (G4): a system toast card (ToastGlassMixin path).
        try {
            net.minecraft.client.toast.SystemToast.show(c.getToastManager(),
                    net.minecraft.client.toast.SystemToast.Type.WORLD_BACKUP,
                    new LiteralText("Liquid Glass"), new LiteralText("Toast cards are glass now"));
        } catch (Throwable t) { skip("system toast", t); }
        holdHud(c);
    }

    /** G2 tab-list staging: in single-player 1.15.2 the tab list only renders when the key is held AND there is a
     *  {@code list}-slot scoreboard objective (or >1 player) — so add one and give the player a score, then hold the
     *  key. The list panel (a >8px-tall plate) then becomes a glass plate with the softened row stripe on top. */
    private static void stageTabList(MinecraftClient c) {
        // The previous scene left a ChatScreen open; close it so the world HUD shows. The tab list gate is
        // keyPlayerList.isPressed() AND a list-slot objective (or >1 player). isPressed() is reset from the physical
        // GLFW key each frame by updatePressedStates (Mouse.updateMouse), so a plain setPressed does not survive to the
        // in-render check in this headless window -> forceTabListKey makes InGameHudMixin re-press it at render HEAD
        // (after updateMouse, before the tab-list check) for the shot only.
        close(c);
        // Put a list-slot objective straight onto the CLIENT scoreboard the tab list reads (deterministic — no
        // server->client sync wait). The gate then needs only isPressed(), which forceTabListKey supplies.
        try {
            net.minecraft.scoreboard.Scoreboard sb = c.world.getScoreboard();
            net.minecraft.scoreboard.ScoreboardObjective obj = sb.getObjective("s1hp");
            if (obj == null) {
                obj = sb.addObjective("s1hp", net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                        new LiteralText("HP"), net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER);
            }
            sb.setObjectiveSlot(0, obj);   // slot 0 = the player-list (tab) slot
            sb.getPlayerScore(c.player.getEntityName(), obj).setScore(20);
        } catch (Throwable t) {
            skip("stage tab-list scoreboard", t);
        }
        forceTabListKey = true;
    }

    /** Keep the action bar (G5) fresh every held frame (vanilla fades it after ~60 ticks). */
    private static void holdHud(MinecraftClient c) {
        try {
            if (c.inGameHud != null) c.inGameHud.setOverlayMessage(new LiteralText("Picked up: Diamond x8"), false);
        } catch (Throwable ignored) {}
    }

    /** Point the player down at the flat ground so {@code crosshairTarget} is a block (H1 outline needs a target). */
    private static void faceOutlineBlock(MinecraftClient c) {
        try {
            ClientPlayerEntity cp = c.player;
            if (cp == null) return;
            cp.yaw = 0f; cp.prevYaw = 0f; cp.setHeadYaw(0f);
            cp.pitch = 78f; cp.prevPitch = 78f;
        } catch (Throwable ignored) {}
    }

    // ---- H module value setters (in-memory only; restored by resetModules() at the run's end) ----

    private static void setEnabled(String module, boolean on) {
        try { Module m = ModuleManager.byName(module); if (m != null) m.setEnabled(on); }
        catch (Throwable t) { skip("enable " + module, t); }
    }
    private static void setBool(String module, String setting, boolean v) {
        try { Setting s = settingOf(module, setting); if (s != null) s.boolValue = v; }
        catch (Throwable t) { skip("set " + module + "." + setting, t); }
    }
    private static void setNum(String module, String setting, double v) {
        try { Setting s = settingOf(module, setting); if (s != null) s.setDouble(v); }
        catch (Throwable t) { skip("set " + module + "." + setting, t); }
    }
    private static void setColor(String module, String setting, int argb) {
        try { Setting s = settingOf(module, setting); if (s != null) s.colorValue = argb; }
        catch (Throwable t) { skip("set " + module + "." + setting, t); }
    }
    private static Setting settingOf(String module, String setting) {
        Module m = ModuleManager.byName(module);
        return m == null ? null : m.setting(setting);
    }

    /** Open the config screen and reflect it to the given category tab + module detail (H zh-TW label shots). */
    private static void openConfigDetail(MinecraftClient c, String category, String module) {
        try {
            S1mp1eConfigScreen scr = new S1mp1eConfigScreen();
            open(c, scr, "config detail " + module);
            int tabIdx = "Combat".equals(category) ? 0 : ("HUD".equals(category) ? 1 : 2);
            java.lang.reflect.Field tf = S1mp1eConfigScreen.class.getDeclaredField("tab");
            tf.setAccessible(true); tf.setInt(scr, tabIdx);
            java.lang.reflect.Method rebuild = S1mp1eConfigScreen.class.getDeclaredMethod("rebuildTab");
            rebuild.setAccessible(true); rebuild.invoke(scr);
            // Snap the sliding tab-highlight pill to the same tab so the shot shows the pill on the right tab
            // (rebuildTab only refreshes the module list, not the tabSlide animation).
            try {
                java.lang.reflect.Field ts = S1mp1eConfigScreen.class.getDeclaredField("tabSlide");
                ts.setAccessible(true);
                Object anim = ts.get(scr);
                anim.getClass().getMethod("snap", float.class).invoke(anim, (float) tabIdx);
            } catch (Throwable ignored) {}
            Module m = ModuleManager.byName(module);
            if (m != null) {
                java.lang.reflect.Method sel = S1mp1eConfigScreen.class.getDeclaredMethod("selectModule", Module.class);
                sel.setAccessible(true); sel.invoke(scr, m);
            }
        } catch (Throwable t) {
            skip("open config detail " + module, t);
        }
    }

    // ---- BATCH-B/C/D creative helpers --------------------------------------

    /** Put the client into creative mode and open the creative screen on {@code group} so its item list is built. */
    private static void openCreative(MinecraftClient c, ItemGroup group) {
        try {
            if (c.interactionManager != null) c.interactionManager.setGameMode(GameMode.CREATIVE);
        } catch (Throwable ignored) {}
        try {
            CreativeInventoryScreen scr = new CreativeInventoryScreen(c.player);
            open(c, scr, "creative");
            // setSelectedTab is private; reflect it so the tab's item list is built and hasScrollbar() becomes true.
            java.lang.reflect.Method m = CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab", ItemGroup.class);
            m.setAccessible(true);
            m.invoke(scr, group);
        } catch (Throwable t) {
            skip("open creative " + group.getName(), t);
        }
    }

    /** Stage a synthetic client-side villager trade screen with 12 distinct trades (so it scrolls) and open it. The
     *  MerchantContainer(int, PlayerInventory) ctor makes a self-contained client-side inventory + placeholder trader, so
     *  no server round-trip is needed; {@code setOffers} populates the list the screen renders, and {@code switchTo(0)}
     *  fills the sell slot. Everything is client-only and torn down when the screen closes. */
    private static void openMerchant(MinecraftClient c) {
        try {
            PlayerInventory inv = c.player.inventory;
            MerchantContainer container = new MerchantContainer(0, inv);
            TraderOfferList offers = new TraderOfferList();
            ItemStack[] buys = {
                new ItemStack(Items.EMERALD, 1),  new ItemStack(Items.EMERALD, 2),
                new ItemStack(Items.EMERALD, 3),  new ItemStack(Items.EMERALD, 5),
                new ItemStack(Items.EMERALD, 8),  new ItemStack(Items.EMERALD, 13),
                new ItemStack(Items.EMERALD, 1),  new ItemStack(Items.EMERALD, 2),
                new ItemStack(Items.EMERALD, 4),  new ItemStack(Items.EMERALD, 6),
                new ItemStack(Items.EMERALD, 9),  new ItemStack(Items.EMERALD, 12),
            };
            ItemStack[] sells = {
                new ItemStack(Items.DIAMOND_SWORD),   new ItemStack(Items.GOLDEN_APPLE, 3),
                new ItemStack(Items.ENDER_PEARL, 4),  new ItemStack(Items.BOOK, 8),
                new ItemStack(Items.IRON_PICKAXE),    new ItemStack(Items.BREAD, 16),
                new ItemStack(Items.ARROW, 32),       new ItemStack(Items.COOKED_BEEF, 12),
                new ItemStack(Items.REDSTONE, 24),    new ItemStack(Items.LAPIS_LAZULI, 20),
                new ItemStack(Items.GLOWSTONE, 6),    new ItemStack(Items.DIAMOND, 2),
            };
            for (int i = 0; i < sells.length; i++) {
                offers.add(new TradeOffer(buys[i], sells[i], 16, 5, 0.05F));
            }
            container.setOffers(offers);
            try { container.switchTo(0); } catch (Throwable ignored) {}
            open(c, new MerchantScreen(container, inv, new LiteralText("Villager")), "merchant");
        } catch (Throwable t) {
            skip("open merchant", t);
        }
    }

    /** One wheel step on the current merchant screen (starts the trade-list glide toward the new row). */
    private static void wheelMerchant(MinecraftClient c, double amount) {
        try {
            Screen s = c.currentScreen;
            if (s instanceof MerchantScreen) s.mouseScrolled(300.0, 200.0, amount);
        } catch (Throwable t) {
            skip("wheel merchant", t);
        }
    }

    /** One wheel step on the current creative screen (starts a glide toward the new row). */
    private static void wheelCreative(MinecraftClient c, double amount) {
        try {
            Screen s = c.currentScreen;
            if (s != null) s.mouseScrolled(300.0, 200.0, amount);
        } catch (Throwable t) {
            skip("wheel creative", t);
        }
    }

    /** {@code {x, y, containerWidth, containerHeight}} of a ContainerScreen, read reflectively (init has run). */
    private static int[] creativeGeom(Screen scr) {
        try {
            Class<?> cs = Class.forName("net.minecraft.client.gui.screen.ingame.ContainerScreen");
            return new int[] { readInt(cs, scr, "x"), readInt(cs, scr, "y"),
                               readInt(cs, scr, "containerWidth"), readInt(cs, scr, "containerHeight") };
        } catch (Throwable t) {
            return new int[] { 0, 0, 195, 136 };
        }
    }

    private static int readInt(Class<?> owner, Object o, String field) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(o);
    }

    /** The fused cell 0..6 for a group (mirrors CreativeGlassMixin.s1mp1e$cell). */
    private static int cellOf(ItemGroup g, int pw) {
        int col = g.getColumn();
        float tabXRel = g.isSpecial() ? (pw - 28f * (6 - col)) : (28f * col + (col > 0 ? col : 0));
        float c = pw / 7f;
        int cell = Math.round((tabXRel + 14f) / c - 0.5f);
        return cell < 0 ? 0 : (cell > 6 ? 6 : cell);
    }

    /** Automated click assertions for (B) tab hit boxes and (D) mid-glide click safety; logs "N PASS / M FAIL". */
    private static void runClickTests(MinecraftClient c) {
        int pass = 0, fail = 0;
        StringBuilder sb = new StringBuilder();
        Screen s = c.currentScreen;
        if (!(s instanceof CreativeInventoryScreen)) {
            System.out.println("[S1mp1e][DevShot] clicks: 0 PASS / 1 FAIL (creative screen not open)");
            return;
        }
        CreativeInventoryScreen scr = (CreativeInventoryScreen) s;
        int[] g = creativeGeom(s);
        float cw = g[2] / 7f;
        // (B) clicking the drawn cell centre selects that tab (hit box follows what is drawn).
        ItemGroup[] tabs = { ItemGroup.BUILDING_BLOCKS, ItemGroup.REDSTONE, ItemGroup.COMBAT, ItemGroup.SEARCH };
        for (ItemGroup grp : tabs) {
            int cell = cellOf(grp, g[2]);
            boolean top = grp.isTopRow();
            double cx = g[0] + (cell + 0.5f) * cw;
            double cy = top ? g[1] - 14 : g[1] + g[3] + 14;
            // Vanilla selects the tab on RELEASE (mouseClicked only consumes the press), so drive both.
            try { scr.mouseClicked(cx, cy, 0); scr.mouseReleased(cx, cy, 0); } catch (Throwable ignored) {}
            int got = scr.getSelectedTab();
            if (got == grp.getIndex()) pass++;
            else { fail++; sb.append(grp.getName()).append("(want ").append(grp.getIndex()).append(" got ").append(got).append(") "); }
        }
        // (B, informational) the mid gap between the 5 left cells and the right cell: log what it hits (layout-specific).
        int before = scr.getSelectedTab();
        try { scr.mouseClicked(g[0] + 5.5f * cw, g[1] - 14, 0); scr.mouseReleased(g[0] + 5.5f * cw, g[1] - 14, 0); } catch (Throwable ignored) {}
        int afterGap = scr.getSelectedTab();
        sb.append("[gap cell5: ").append(before == afterGap ? "no-change" : ("->" + afterGap)).append("] ");
        // (D) a click while mid-glide must not throw (the snap-to-target runs first).
        try {
            java.lang.reflect.Method m = CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab", ItemGroup.class);
            m.setAccessible(true);
            m.invoke(scr, ItemGroup.BUILDING_BLOCKS);
            scr.mouseScrolled(300.0, 200.0, -5.0);            // start a glide
            scr.mouseClicked(g[0] + 20, g[1] + 30, 0);        // click a grid slot mid-glide
            pass++;
        } catch (Throwable t) { fail++; sb.append("mid-glide click threw ").append(t); }
        System.out.println("[S1mp1e][DevShot] clicks: " + pass + " PASS / " + fail + " FAIL " + sb);
    }

    private static void stepScenes(MinecraftClient client) {
        if (scenes == null) {
            scenes = buildScenes(client);
            sceneIdx = 0; sceneTimer = 0;
        }
        if (sceneIdx >= scenes.length) { phase = P_STOP; return; }
        Scene sc = scenes[sceneIdx];
        if (sceneTimer == 0) {
            sceneMouseX = -1; sceneMouseY = -1;     // reset; the stage re-pins if it needs a hover
            try { sc.stage.run(client); } catch (Throwable t) { skip("stage scene " + sc.name, t); }
        }
        // Hold the scene deterministic: drop the loadout's transient toasts (EXCEPT in the HUD modes, whose whole
        // point is to show a glass toast card + boss bar), and keep the cursor pinned every frame so the hovered slot
        // (and its tooltip) stays put through the settle.
        boolean hudMode = "hud".equals(mode) || "modules".equals(mode);
        if (!hudMode) { try { client.getToastManager().clear(); } catch (Throwable ignored) {} }
        if (sceneMouseX >= 0) applyPinnedMouse(client);
        if (sc.hold != null) { try { sc.hold.run(client); } catch (Throwable t) { skip("hold scene " + sc.name, t); } }
        sceneTimer++;
        if (sceneTimer == SCENE_SETTLE && sc.action != null) {
            try { sc.action.run(client); } catch (Throwable t) { skip("action scene " + sc.name, t); }
        }
        if (sc.burst <= 1) {
            if (sceneTimer >= SCENE_SETTLE) {
                capture(client, sc.name);
                sceneIdx++; sceneTimer = 0;
            }
        } else {
            // Burst: capture `burst` consecutive frames starting the frame AFTER the action fired, so the
            // motion (glide settle / pill slide) is filmed frame-by-frame with no extra settle between them.
            int shot = sceneTimer - SCENE_SETTLE;   // 1..burst
            if (shot >= 1 && shot <= sc.burst) {
                capture(client, sc.name.replace(".png", String.format("-%02d.png", shot - 1)));
            }
            if (shot >= sc.burst) { sceneIdx++; sceneTimer = 0; }
        }
    }

    /** Feature (A) book scene: a writable book in hand → the glass page + parchment scrim edit screen. */
    private static void openBook(MinecraftClient c) {
        try {
            ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
            c.player.inventory.setInvStack(c.player.inventory.selectedSlot, book);
            open(c, new BookEditScreen(c.player, book, Hand.MAIN_HAND), "book (edit)");
        } catch (Throwable t) {
            skip("open book", t);
        }
    }

    /** Give the player several long effects (beneficial + harmful) on both server and client so the
     *  effect strip shows a realistic multi-entry list immediately. */
    private static void giveDemoEffects(MinecraftClient c) {
        StatusEffect[] fx = { StatusEffects.SPEED, StatusEffects.REGENERATION,
                StatusEffects.NIGHT_VISION, StatusEffects.POISON, StatusEffects.WITHER };
        try {
            for (StatusEffect e : fx) {
                c.player.addStatusEffect(new StatusEffectInstance(e, 100000, 0, false, true, true));
            }
        } catch (Throwable t) {
            skip("give client effects", t);
        }
        try {
            MinecraftServer s = c.getServer();
            if (s != null && !s.getPlayerManager().getPlayerList().isEmpty()) {
                ServerPlayerEntity sp = s.getPlayerManager().getPlayerList().get(0);
                for (StatusEffect e : fx) {
                    sp.addStatusEffect(new StatusEffectInstance(e, 100000, 0, false, true, true));
                }
            }
        } catch (Throwable t) {
            skip("give server effects", t);
        }
    }

    /** Put a multi-line-tooltip item (diamond sword) in the hovered top-left slot and counted stacks
     *  in the slots below/right so the card visibly overlaps the next row + its count digits (R1). */
    private static void fillTooltipGrid(MinecraftClient c) {
        try {
            PlayerInventory inv = c.player.inventory;
            inv.setInvStack(9,  new ItemStack(Items.DIAMOND_SWORD));    // hovered: top-left main row
            inv.setInvStack(10, new ItemStack(Items.GOLDEN_APPLE, 17));
            inv.setInvStack(11, new ItemStack(Items.EMERALD, 42));
            inv.setInvStack(18, new ItemStack(Items.DIAMOND, 64));      // next row down
            inv.setInvStack(19, new ItemStack(Items.REDSTONE, 63));
            inv.setInvStack(20, new ItemStack(Items.GOLD_INGOT, 12));
        } catch (Throwable t) {
            skip("fill tooltip grid", t);
        }
    }

    /** Remember a scaled-GUI hover point; {@link #applyPinnedMouse} converts it to window pixels. */
    private static void pinMouse(int scaledX, int scaledY) {
        sceneMouseX = scaledX; sceneMouseY = scaledY;
    }

    /** Force MC's cursor to the pinned scaled-GUI point so the screen hit-tests the intended slot.
     *  {@code mouseX_scaled = Mouse.x / scaleFactor}, so {@code Mouse.x = scaledX * scaleFactor}. The
     *  window has no OS focus in the harness, so nothing moves the cursor back between frames. */
    private static void applyPinnedMouse(MinecraftClient c) {
        try {
            double sf = c.getWindow().getScaleFactor();
            java.lang.reflect.Field fx = net.minecraft.client.Mouse.class.getDeclaredField("x");
            java.lang.reflect.Field fy = net.minecraft.client.Mouse.class.getDeclaredField("y");
            fx.setAccessible(true); fy.setAccessible(true);
            fx.setDouble(c.mouse, sceneMouseX * sf);
            fy.setDouble(c.mouse, sceneMouseY * sf);
        } catch (Throwable t) {
            skip("pin mouse", t);
        }
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
            // seed 12345, survival, no structures, not hardcore, superflat; cheats on.
            LevelInfo info = new LevelInfo(12345L, GameMode.SURVIVAL, false, false, LevelGeneratorType.FLAT)
                    .enableCommands();
            client.startIntegratedServer("devshot", "devshot", info);
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /** Time / weather / difficulty / camera angle / scripted loadout. Each sub-part is guarded. */
    private static void applyWorldSetup(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        // difficulty (server-authoritative)
        try {
            server.setDifficulty(Difficulty.PEACEFUL, true);
        } catch (Throwable t) {
            skip("set difficulty", t);
        }
        // time + weather (server-authoritative, via the overworld level properties)
        try {
            ServerWorld ow = server.getWorld(DimensionType.OVERWORLD);
            LevelProperties props = ow.getLevelProperties();
            props.setTimeOfDay(6000L);
            props.setRaining(false);
            props.setThundering(false);
            props.setClearWeatherTime(1_000_000);
        } catch (Throwable t) {
            skip("set time/weather", t);
        }
        // loadout on the server player (auto-syncs to the client within a couple of ticks)
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
                // 60 s Speed I, icon on but no particles (keeps the reference frame clean).
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the flat plain).
                // 1.15.2: entity position fields x/y/z are private -> getX()/getY()/getZ().
                sp.refreshPositionAndAngles(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
                sp.networkHandler.requestTeleport(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
        // mirror the loadout + camera on the client player so the very next frame already shows it
        try {
            ClientPlayerEntity cp = client.player;
            cp.inventory.setInvStack(0, new ItemStack(Items.DIAMOND_SWORD));
            cp.inventory.setInvStack(1, new ItemStack(Items.COOKED_BEEF, 32));
            cp.inventory.setInvStack(2, new ItemStack(Items.STONE, 64));
            cp.inventory.selectedSlot = 0;                    // hold the sword
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
        try {
            client.openScreen(screen);
        } catch (Throwable t) {
            skip(what, t);
        }
    }

    private static void close(MinecraftClient client) {
        try {
            client.openScreen(null);
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
    private static void capture(MinecraftClient client, String name) {
        NativeImage img = null;
        try {
            Framebuffer fb = client.getFramebuffer();
            // ScreenshotUtils.takeScreenshot(width, height, fb): framebuffer -> NativeImage. Mapped
            // in yarn 1.15.2 (the unmapped method_1663 of 1.14.4).
            img = ScreenshotUtils.takeScreenshot(fb.textureWidth, fb.textureHeight, fb);
            File out = new File(outDir, name);
            img.writeFile(out);
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
