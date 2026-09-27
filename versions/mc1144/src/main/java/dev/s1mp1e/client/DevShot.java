package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
 * framebuffer writer ({@link ScreenshotUtils#method_1663} + {@link NativeImage#writeFile}), straight
 * into the folder {@code S1MP1E_SHOT} names, overwriting:
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
 * <p>1.14.4 port of the 1.20.1 (mc1201) harness. API deltas: {@code MinecraftClient} exposes the
 * {@code window}/{@code options}/{@code currentScreen}/{@code world}/{@code player} FIELDS (no
 * {@code getX()} accessors) and {@code openScreen} (not {@code setScreen}); {@code options.guiScale}
 * is an {@code int} field; the language is a {@link LanguageDefinition} (compared via {@code getCode})
 * not a String; world creation is {@code startIntegratedServer(save, name, LevelInfo)} with the
 * legacy {@link LevelInfo}/{@link LevelGeneratorType} API (cheats via {@code enableCommands()}, flat
 * via {@code LevelGeneratorType.FLAT}); time/weather are set through {@link LevelProperties}
 * ({@code setTimeOfDay}/{@code setRaining}/{@code setThundering}/{@code setClearWeatherTime}) with
 * difficulty via {@code server.setDifficulty}; the framebuffer→{@link NativeImage} helper is the
 * unmapped {@code ScreenshotUtils.method_1663(w, h, fb)}; {@link VideoOptionsScreen} lives in
 * {@code ...gui.screen} (not {@code ...gui.screen.option}); the inventory loadout uses
 * {@code PlayerInventory.setInvStack}.
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

    // State machine phases.
    private static final int P_INIT = 0, P_WAIT_TITLE = 1, P_TITLE = 2,
                             P_WAIT_CONFIG = 3, P_CONFIG = 4,
                             P_WAIT_WORLD = 5, P_WORLD_SETTLE = 6, P_WORLD = 7,
                             P_WAIT_CONFIG2 = 8, P_CONFIG_WORLD = 9,
                             P_WAIT_INV = 10, P_INV = 11,
                             P_WAIT_OPTIONS = 12, P_OPTIONS = 13,
                             P_STOP = 14, P_DONE = 15, P_COMBAT = 9090,
                             // Batch-A / E / F verification scenes (inserted between inventory and options).
                             P_INV_TIP_SETTLE = 16, P_INV_TIP = 17,
                             P_WAIT_ADV = 18, P_ADV = 19,
                             P_WAIT_STATS = 20, P_STATS = 21,
                             P_WAIT_BOOK = 22, P_BOOK = 23;

    /** Survival hotbar slot 0 (the diamond sword), GUI px relative to the screen's own x/y. */
    private static final int INV_SLOT_DX = 16, INV_SLOT_DY = 150;
    /** Frames to let the glass tooltip fade in + morph before the tooltip shot. */
    private static final int TIP_SETTLE_FRAMES = 45;
    private static final int SCREEN_SHOT_FRAMES = 40;   // settle a freshly opened A-screen

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
                case P_INV_TIP_SETTLE:     stepInvTipSettle(client);               break;
                case P_INV_TIP:            stepInvTip(client);                     break;
                case P_WAIT_ADV:           stepWaitAdv(client);                    break;
                case P_ADV:                stepAdv(client);                        break;
                case P_WAIT_STATS:         stepWaitStats(client);                  break;
                case P_STATS:              stepStats(client);                      break;
                case P_WAIT_BOOK:          stepWaitBook(client);                   break;
                case P_BOOK:               stepBook(client);                       break;
                case P_WAIT_OPTIONS:       stepWaitOptions(client);                break;
                case P_OPTIONS:            stepOptions(client);                    break;
                case P_COMBAT:             if (CombatShot.step(client)) { phase = P_STOP; frames = 0; }  break;
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

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(MinecraftClient client) {
        try {
            // Identical rendering conditions for every version. 1.14.4's Window exposes no
            // setWindowedSize; resize the GLFW window directly. Restore first in case the dev
            // window came up maximized, then size + recompute.
            long handle = client.window.getHandle();
            GLFW.glfwRestoreWindow(handle);
            GLFW.glfwSetWindowSize(handle, 1280, 720);
            client.options.guiScale = 2;
            // Only pay the resource-reload cost when the language actually differs.
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
        if ("combat".equalsIgnoreCase(System.getenv("S1MP1E_SHOT_MODE"))) { frames = 0; phase = P_COMBAT; return; }   // 26.2 combat trio
        frames = 0; phase = P_WORLD;
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
        // Park the cursor OFF any slot so inventory.png has no tooltip. The status-effect glass
        // strip (F) shows to the LEFT of the shifted inventory (SPEED effect is active).
        setMouseGui(client, 4, 4);
        if (++frames < INV_FRAMES) return;
        capture(client, "inventory.png");
        frames = 0; phase = P_INV_TIP_SETTLE;
    }

    // ---- E + F verification: inventory WITH a glass tooltip on the very top layer ------------

    private static void stepInvTipSettle(MinecraftClient client) {
        // Hover the sword slot every frame so MC keeps resolving it as the hovered slot; the glass
        // tooltip card then rises up-right over the counted main-inventory stacks (R1).
        hoverSlot(client, INV_SLOT_DX, INV_SLOT_DY);
        if (++frames >= TIP_SETTLE_FRAMES) { frames = 0; phase = P_INV_TIP; }
    }

    private static void stepInvTip(MinecraftClient client) {
        hoverSlot(client, INV_SLOT_DX, INV_SLOT_DY);
        if (++frames < 4) return;                     // a few frames at rest before the shot
        capture(client, "inventory-tooltip.png");     // R1: card + text on top, refracting items below
        close(client);
        try {
            open(client, new AdvancementsScreen(client.player.networkHandler.getAdvancementHandler()),
                 "open advancements");
        } catch (Throwable t) {
            skip("build advancements screen", t);
        }
        frames = 0; phase = P_WAIT_ADV;
    }

    // ---- A: advancements ---------------------------------------------------

    private static void stepWaitAdv(MinecraftClient client) {
        if (client.currentScreen instanceof AdvancementsScreen) {
            frames = 0; phase = P_ADV;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait advancements screen", new IllegalStateException("advancements never opened"));
            frames = 0; phase = P_ADV;
        }
    }

    private static void stepAdv(MinecraftClient client) {
        if (++frames < SCREEN_SHOT_FRAMES) return;
        capture(client, "advancements.png");
        close(client);
        try {
            open(client, new StatsScreen(null, client.player.getStatHandler()), "open statistics");
        } catch (Throwable t) {
            skip("build statistics screen", t);
        }
        frames = 0; phase = P_WAIT_STATS;
    }

    // ---- A: statistics -----------------------------------------------------

    private static void stepWaitStats(MinecraftClient client) {
        if (client.currentScreen instanceof StatsScreen) {
            frames = 0; phase = P_STATS;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait statistics screen", new IllegalStateException("statistics never opened"));
            frames = 0; phase = P_STATS;
        }
    }

    private static void stepStats(MinecraftClient client) {
        // Stats may show a brief "downloading" phase; the extra settle frames cover it.
        if (++frames < SCREEN_SHOT_FRAMES * 2) return;
        capture(client, "stats.png");
        close(client);
        try {
            open(client, new BookScreen(BookScreen.EMPTY_PROVIDER), "open book");
        } catch (Throwable t) {
            skip("build book screen", t);
        }
        frames = 0; phase = P_WAIT_BOOK;
    }

    // ---- A: book (view) ----------------------------------------------------

    private static void stepWaitBook(MinecraftClient client) {
        if (client.currentScreen instanceof BookScreen) {
            frames = 0; phase = P_BOOK;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait book screen", new IllegalStateException("book never opened"));
            frames = 0; phase = P_BOOK;
        }
    }

    private static void stepBook(MinecraftClient client) {
        if (++frames < SCREEN_SHOT_FRAMES) return;
        capture(client, "book.png");
        close(client);
        // Continue to the original video-settings shot, then quit.
        try {
            open(client, new InventoryScreen(client.player), "reopen inventory (video parent)");
            client.openScreen(new VideoOptionsScreen(client.currentScreen, client.options));
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
                // First main-inventory row: counted stacks so the hover-tooltip card (which rises
                // up-right from a hotbar slot) overlaps other items + count digits — the R1 test.
                sp.inventory.setInvStack(9,  new ItemStack(Items.COBBLESTONE, 64));
                sp.inventory.setInvStack(10, new ItemStack(Items.OAK_PLANKS, 48));
                sp.inventory.setInvStack(11, new ItemStack(Items.TORCH, 16));
                sp.inventory.setInvStack(12, new ItemStack(Items.APPLE, 5));
                sp.inventory.setInvStack(13, new ItemStack(Items.ARROW, 12));
                sp.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                sp.equipStack(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
                sp.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                sp.equipStack(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
                sp.equipStack(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
                // 60 s Speed I, icon on but no particles (keeps the reference frame clean).
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 1200, 0, false, false, true));
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
            cp.inventory.setInvStack(2, new ItemStack(Items.STONE, 64));
            cp.inventory.setInvStack(9,  new ItemStack(Items.COBBLESTONE, 64));
            cp.inventory.setInvStack(10, new ItemStack(Items.OAK_PLANKS, 48));
            cp.inventory.setInvStack(11, new ItemStack(Items.TORCH, 16));
            cp.inventory.setInvStack(12, new ItemStack(Items.APPLE, 5));
            cp.inventory.setInvStack(13, new ItemStack(Items.ARROW, 12));
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

    /** Place the virtual cursor at a scaled-GUI coordinate by writing the raw framebuffer-px mouse
     *  position MC reads each frame (Mouse.x/y are private doubles). This is how 26.2's DevShot
     *  ttApplyHover drives a hover with no real input device. */
    private static void setMouseGui(MinecraftClient client, double guiX, double guiY) {
        try {
            java.lang.reflect.Field mf = MinecraftClient.class.getDeclaredField("mouse");
            mf.setAccessible(true);
            Object mouse = mf.get(client);
            if (mouse == null) return;
            double ww = client.window.getWidth(),  sw = client.window.getScaledWidth();
            double wh = client.window.getHeight(), sh = client.window.getScaledHeight();
            double mx = (sw <= 0) ? guiX : guiX * ww / sw;
            double my = (sh <= 0) ? guiY : guiY * wh / sh;
            setDouble(mouse, "x", mx);
            setDouble(mouse, "y", my);
        } catch (Throwable t) {
            skip("set mouse", t);
        }
    }

    /** Hover a slot at {@code (dxGui, dyGui)} relative to the open container screen's own x/y, so a
     *  status-effect side panel shifting the screen does not throw the cursor off the slot. */
    private static void hoverSlot(MinecraftClient client, int dxGui, int dyGui) {
        int ox = 232, oy = 97;   // centred 176x166 bg at 1280x720 / gui-scale 2 (fallback)
        try {
            Screen s = client.currentScreen;
            if (s instanceof ContainerScreen) {
                java.lang.reflect.Field fx = ContainerScreen.class.getDeclaredField("x");
                java.lang.reflect.Field fy = ContainerScreen.class.getDeclaredField("y");
                fx.setAccessible(true); fy.setAccessible(true);
                ox = fx.getInt(s); oy = fy.getInt(s);
            }
        } catch (Throwable ignored) {}
        setMouseGui(client, ox + dxGui, oy + dyGui);
    }

    private static void setDouble(Object obj, String field, double val) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setDouble(obj, val);
        } catch (Throwable ignored) {}
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
            Framebuffer fb = client.getFramebuffer();
            // ScreenshotUtils.method_1663(width, height, fb): framebuffer -> NativeImage. Unmapped in
            // yarn 1.14.4+build.18 (1.16.5 names the same method takeScreenshot).
            img = ScreenshotUtils.method_1663(fb.textureWidth, fb.textureHeight, fb);
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
