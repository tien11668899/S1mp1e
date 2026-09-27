package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
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
    private static void capture(MinecraftClient client, String name) {
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
