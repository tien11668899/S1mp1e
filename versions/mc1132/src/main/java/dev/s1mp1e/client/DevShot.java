package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.resource.language.LanguageManager;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.List;

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
 * conditions, on the first rendered frame it forces the framebuffer to 1280x720, GUI scale 2 and the
 * language to {@code zh_tw}, then walks a fixed script and writes six PNGs with the game's own
 * framebuffer writer ({@link ScreenshotUtils#method_18269} + {@code class_4277.method_19471}),
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
 * <p>Driven from a single render-frame hook ({@code DevShotMixin} at
 * {@code MinecraftClient.method_18228(Z)V} TAIL — the per-frame render method, unmapped in this
 * yarn; the frame's fbo is fully drawn there, the same buffer vanilla's F2 screenshot reads).
 *
 * <p><b>1.13.2 port of the 1.20.1 (mc1201) harness.</b> API deltas from 1.14.4/1.16.5:
 * <ul>
 *   <li>{@code MinecraftClient} has no {@code Window} object and no {@code onResolutionChanged()};
 *       the window is {@code net.minecraft.class_4117} reached via {@link Mc1132#window()}, and the
 *       reference resolution is forced by reflecting its {@code framebufferWidth/Height} fields
 *       ({@code field_20043}/{@code field_20044}) and calling its public resolution-update method
 *       {@code method_18314()} (which recomputes the scaled size and resizes the GL framebuffer);</li>
 *   <li>1.13.2 has no Mojang {@code SplashOverlay}/{@code getOverlay()}, so the title gate is just
 *       {@code currentScreen instanceof TitleScreen};</li>
 *   <li>{@code setScreen} (not {@code openScreen}); {@code options.guiScale} is an {@code int} field;
 *       the language is a {@link LanguageDefinition} looked up via the unmapped {@code method_14698};</li>
 *   <li>world creation is {@code startIntegratedServer(save, name, LevelInfo)} with the legacy
 *       {@link LevelInfo}/{@link LevelGeneratorType} API (cheats via {@code enableCommands()}, flat
 *       via {@code LevelGeneratorType.FLAT}); the overworld is {@code server.method_20312(OVERWORLD)},
 *       its properties {@code world.method_3588()}; {@code setDifficulty} takes ONE arg;
 *       {@code setTimeOfDay} lives on the world; {@code addStatusEffect} is the unmapped
 *       {@code method_2654};</li>
 *   <li>{@link ServerPlayerEntity} and {@link ClientPlayerEntity} live in
 *       {@code net.minecraft.entity.player}; the player list is {@code getPlayerManager().getPlayers()};
 *       inventory writes use {@code PlayerInventory.setInvStack};</li>
 *   <li>the survival inventory is {@link SurvivalInventoryScreen} ({@link InventoryScreen} is the
 *       abstract base); {@link VideoOptionsScreen} takes {@code (Screen, GameOptions)}; 1.13.2 has no
 *       {@code ToastManager}, so only the chat line is cleared;</li>
 *   <li>the framebuffer→image helper is {@code ScreenshotUtils.method_18269(w, h, fb)} returning
 *       {@code net.minecraft.class_4277} (NativeImage is unmapped), written with {@code method_19471}.</li>
 * </ul>
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

    /** Target reference resolution — forced onto the framebuffer so shots are 1280x720 even when the
     *  desktop is smaller than that and the OS clamps the on-screen window. */
    private static final int SHOT_W = 1280, SHOT_H = 720;

    // State machine phases.
    private static final int P_INIT = 0, P_WAIT_TITLE = 1, P_TITLE = 2,
                             P_WAIT_CONFIG = 3, P_CONFIG = 4,
                             P_WAIT_WORLD = 5, P_WORLD_SETTLE = 6, P_WORLD = 7,
                             P_WAIT_CONFIG2 = 8, P_CONFIG_WORLD = 9,
                             P_WAIT_INV = 10, P_INV = 11,
                             P_WAIT_OPTIONS = 12, P_OPTIONS = 13,
                             P_STOP = 14, P_DONE = 15;

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions (startIntegratedServer, reloadResources) pump the render
     *  loop synchronously, which re-fires this render-TAIL hook. Without this, the current step would
     *  run again mid-action (e.g. re-creating the world every pumped frame → recursion →
     *  StackOverflow/OOM). Nested frames pumped inside a step do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;

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
            // Suppress the vanilla advancement/recipe toasts the scripted loadout triggers, so the
            // over-world reference frames stay clean and deterministic. 1.13.2 has no getToastManager,
            // so the manager (class_3264, field_15868) is reached by reflection and cleared each frame
            // while in the world (matches the ToastManager.clear() the mc1201/mc1144 references call).
            if (client.world != null) clearToasts(client);
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
     * Force MC's framebuffer to {@link #SHOT_W}x{@link #SHOT_H} so every reference shot is 1280x720
     * regardless of the desktop size. On a desktop smaller than 1280x720 the OS clamps the on-screen
     * window, but the off-screen framebuffer we screenshot can still be full size — the frame renders
     * into it at 1280x720 and only the (unwatched) on-screen blit is scaled.
     *
     * <p>1.13.2 has no {@code Window}/{@code onResolutionChanged()}; we reflect the window
     * ({@code class_4117}) {@code framebufferWidth/Height} fields ({@code field_20043}/
     * {@code field_20044}) and then call its public {@code method_18314()}, which recomputes the
     * scaled GUI size from {@code options.guiScale} and resizes the GL framebuffer to match. Dev-only
     * (DevShot runs solely under {@code S1MP1E_SHOT}), so the reflection is safe here. No-op once the
     * size already matches, so it is cheap to call every frame and self-heals after any stray GLFW
     * framebuffer-resize callback.
     */
    private static void forceFramebuffer(MinecraftClient client) {
        try {
            net.minecraft.class_4117 win = Mc1132.window();
            if (win == null) return;
            if (win.method_18317() == SHOT_W && win.method_18318() == SHOT_H) return;
            java.lang.reflect.Field fw = net.minecraft.class_4117.class.getDeclaredField("field_20043");
            java.lang.reflect.Field fh = net.minecraft.class_4117.class.getDeclaredField("field_20044");
            fw.setAccessible(true); fh.setAccessible(true);
            fw.setInt(win, SHOT_W); fh.setInt(win, SHOT_H);
            win.method_18314();   // recompute scaled size + resize the GL framebuffer to the forced size
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + SHOT_W + "x" + SHOT_H);
        } catch (Throwable t) {
            skip("force framebuffer " + SHOT_W + "x" + SHOT_H, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(MinecraftClient client) {
        try {
            // Identical rendering conditions for every version. 1.13.2 exposes no Window helper;
            // resize the GLFW window directly through its handle. Restore first in case the dev
            // window came up maximized. forceFramebuffer() (run every frame) then pins the off-screen
            // framebuffer to 1280x720 even if the OS clamps this on-screen size.
            long handle = Mc1132.handle();
            if (handle != 0L) {
                GLFW.glfwRestoreWindow(handle);
                GLFW.glfwSetWindowSize(handle, SHOT_W, SHOT_H);
            }
            if (client.options != null) client.options.guiScale = 2;
            // Only pay the resource-reload cost when the language actually differs (options.txt
            // normally already selects zh_tw). getLanguage(String) is unmapped -> method_14698.
            LanguageManager lm = client.getLanguageManager();
            LanguageDefinition cur = lm == null ? null : lm.getLanguage();
            if (lm != null && (cur == null || !"zh_tw".equals(cur.getCode()))) {
                LanguageDefinition def = lm.method_14698("zh_tw");
                if (def != null) {
                    lm.setLanguage(def);
                    if (client.options != null) client.options.language = "zh_tw";
                    client.reloadResources();
                }
            }
        } catch (Throwable t) {
            skip("init (window/scale/language)", t);
        }
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static void stepWaitTitle(MinecraftClient client) {
        // Wait past the Mojang splash / any resource reload. 1.13.2 has no SplashOverlay, so the
        // TitleScreen becoming currentScreen is the whole gate.
        if (client.currentScreen instanceof TitleScreen) {
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
        // Suppress the advancement chat line the scripted loadout can trigger, so the over-world
        // reference frames (world / config-world / inventory) are clean and deterministic. 1.13.2 has
        // no ToastManager to clear. Cleared every frame; no new lines appear after the loadout.
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
        open(client, new SurvivalInventoryScreen(client.player), "open inventory");
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
        // leave+quit. We deliberately do NOT disconnect here: disconnect runs its own nested
        // world-unload/"saving" render loop, and invoking that from inside this render-TAIL hook
        // wedges the render thread (the run then only ends when the watchdog force-quits).
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
        // difficulty (server-authoritative; 1.13.2 setDifficulty takes one arg)
        try {
            server.setDifficulty(Difficulty.PEACEFUL);
        } catch (Throwable t) {
            skip("set difficulty", t);
        }
        // time + weather (server-authoritative, via the overworld + its level properties)
        try {
            ServerWorld ow = server.method_20312(DimensionType.OVERWORLD);   // getWorld(OVERWORLD)
            ow.setTimeOfDay(6000L);
            LevelProperties props = ow.method_3588();                        // getLevelProperties()
            props.setRaining(false);
            props.setThundering(false);
            props.setClearWeatherTime(1_000_000);
        } catch (Throwable t) {
            skip("set time/weather", t);
        }
        // loadout on the server player (auto-syncs to the client within a couple of ticks)
        try {
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayers();
            ServerPlayerEntity sp = players.isEmpty() ? null : players.get(0);
            if (sp != null) {
                sp.inventory.setInvStack(0, new ItemStack(Items.DIAMOND_SWORD));
                sp.inventory.setInvStack(1, new ItemStack(Items.COOKED_BEEF, 32));
                sp.inventory.setInvStack(2, new ItemStack(Blocks.STONE, 64));
                sp.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                sp.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
                sp.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                sp.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
                sp.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
                // 60 s Speed I, icon on but no particles (keeps the reference frame clean).
                // addStatusEffect is the unmapped method_2654 on this yarn.
                sp.method_2654(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the flat plain).
                sp.refreshPositionAndAngles(sp.x, sp.y, sp.z, 0f, 15f);
                sp.networkHandler.requestTeleport(sp.x, sp.y, sp.z, 0f, 15f);
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
        // mirror the loadout + camera on the client player so the very next frame already shows it
        try {
            ClientPlayerEntity cp = client.player;
            cp.inventory.setInvStack(0, new ItemStack(Items.DIAMOND_SWORD));
            cp.inventory.setInvStack(1, new ItemStack(Items.COOKED_BEEF, 32));
            cp.inventory.setInvStack(2, new ItemStack(Blocks.STONE, 64));
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

    // ---- toast suppression -------------------------------------------------

    /** Cached reflective handle to {@code MinecraftClient.field_15868} (the ToastManager). */
    private static java.lang.reflect.Field toastField;

    /**
     * Clear the queued + visible vanilla toasts. 1.13.2 has no {@code getToastManager()}; the manager
     * is the private final {@code class_3264 field_15868}, and its {@code method_14489()} empties both
     * the display slots and the pending Deque. Dev-only (DevShot runs solely under {@code S1MP1E_SHOT}),
     * so the reflection is safe here; fully guarded so a mappings surprise only skips the suppression.
     */
    private static void clearToasts(MinecraftClient client) {
        try {
            if (toastField == null) {
                toastField = MinecraftClient.class.getDeclaredField("field_15868");
                toastField.setAccessible(true);
            }
            Object tm = toastField.get(client);
            if (tm instanceof net.minecraft.class_3264) {
                ((net.minecraft.class_3264) tm).method_14489();
            }
        } catch (Throwable ignored) {}
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
    private static void capture(MinecraftClient client, String name) {
        net.minecraft.class_4277 img = null;
        try {
            Framebuffer fb = client.getFramebuffer();
            // ScreenshotUtils.method_18269(width, height, fb): framebuffer -> class_4277 (NativeImage,
            // unmapped on legacy-yarn 1.13.2+build.604), written with method_19471(File).
            img = ScreenshotUtils.method_18269(fb.textureWidth, fb.textureHeight, fb);
            File out = new File(outDir, name);
            img.method_19471(out);
            System.out.println("[S1mp1e][DevShot] wrote " + out.getAbsolutePath()
                    + " (" + fb.textureWidth + "x" + fb.textureHeight + ")");
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] capture failed for " + name + ": " + t);
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
