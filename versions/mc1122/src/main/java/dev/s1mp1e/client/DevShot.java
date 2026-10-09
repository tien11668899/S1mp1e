package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Deque;

/**
 * DevShot v2 — deterministic screenshot harness for cross-version visual comparison (1.12.2 line).
 *
 * <p><b>Completely inert unless the environment variable {@code S1MP1E_SHOT} names an output
 * folder</b> (the shot pipeline) or {@code S1MP1E_AUDIT} is set (the one-shot coremod audit). It
 * ships in the jar but does nothing at all when both are unset — a normal game run only pays a
 * cheap early return per frame. Environment variables reach the forked game JVM of {@code runClient};
 * a {@code -D} system property on the gradle command line does not, which is why this reads
 * {@link System#getenv}.
 *
 * <p>When the shot pipeline is active, so that every S1mp1e version renders under identical
 * conditions, on the first rendered frame it forces the framebuffer to 1280x720, GUI scale 2 and the
 * language to {@code zh_tw}, then walks a fixed script and writes six PNGs with the game's own
 * framebuffer reader ({@link net.minecraft.util.ScreenShotHelper#createScreenshot}) straight into the
 * folder {@code S1MP1E_SHOT} names, overwriting:
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
 * then leaves the world and quits cleanly ({@link Minecraft#shutdown()}).
 *
 * <p>Every step is wrapped so a failure only skips that step ("{@code [S1mp1e] DevShot skipped
 * &lt;step&gt;: &lt;reason&gt;}") and the script moves on, and a global 240 s watchdog quits the
 * game so a stuck wait can never hang the run.
 *
 * <p><b>1.12.2 deltas vs the 1.20.1 reference.</b> No Mixin: the per-frame driver is a Forge
 * {@link TickEvent.RenderTickEvent} at {@code Phase.END}, which in 1.12.2's game loop fires while
 * {@code framebufferMc} is still bound and holds the fully-drawn frame (world + GUI + toasts), so the
 * capture reads a complete frame. World creation is {@link Minecraft#launchIntegratedServer} (which
 * blocks until the integrated server's run loop starts, then the client joins over the next ticks —
 * no nested render loop, unlike modern {@code createAndStart}). The audit is not a Mixin audit: on a
 * coremod build it force-loads every transformer target and logs each patch site's PASS/FAIL.
 */
public final class DevShot {

    // ---- frame budgets (identical to every other version) ------------------
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
                             // BATCH A feature (F): the status-effect glass strip. Runs AFTER every
                             // base shot so it never changes the regression references above.
                             P_WAIT_EFF_INV = 14, P_EFFECTS = 15,
                             // BATCH A feature (E): the tooltip on the very top layer (R1) over the
                             // effect strip + items, via a probe screen that draws a glass tooltip.
                             P_WAIT_TOOLTIP = 16, P_TOOLTIP = 17,
                             // BATCH A features (C): the creative screen + glass scrollbar. Needs the
                             // player switched to creative mode (a survival world bounces the screen).
                             P_CREATIVE_SETUP = 18, P_CREATIVE_WAIT_MODE = 19,
                             P_CREATIVE_WAIT_SCREEN = 20, P_CREATIVE = 21,
                             // BATCH A features (B) fused tabs + (D) sub-pixel glide + click test.
                             P_CREATIVE_EXTRA = 24,
                             // BATCH A feature (A): the advancements window framed in glass.
                             P_WAIT_ADV = 25, P_ADV = 26,
                             // BATCH B (G/H): HUD chat panel/input + chroma HUD module.
                             P_HUD = 29, P_HUD_INPUT = 30, P_HUD_CHROMA = 31,
                             P_STOP = 27, P_DONE = 28,
                             // mode-driven scene queues (DevShotScenes): after title.png / after world.png
                             P_SCENES_TITLE = 40, P_SCENES = 41;
    /** Sub-step index inside {@link #P_CREATIVE_EXTRA}. */
    private static int csub;

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions may pump the loop; nested frames do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** The size the just-rendered frame actually used, sampled each frame BEFORE forceFramebuffer
     *  overwrites {@code displayWidth/Height}. MC's runGameLoop re-reads the live window size into
     *  those fields (Display.wasResized) before each render, so while the OS window is still settling
     *  the frame renders into only part of the forced 1280x720 buffer; this is the signal that a frame
     *  actually filled the buffer, used to hold the title shot until the window has settled. */
    private static int lastRenderW, lastRenderH;

    /** Target reference resolution — forced onto the (off-screen) framebuffer so shots are 1280x720
     *  regardless of the on-screen window size the OS grants. */
    private static final int SHOT_W = 1280, SHOT_H = 720;
    /** The size forceFramebuffer holds (a scene may switch it, e.g. the small-window settings page). */
    static int shotW = SHOT_W, shotH = SHOT_H;

    /** Public no-arg ctor so it can be registered on the Forge event bus. */
    public DevShot() {}

    /** Per-frame Forge driver. Runs at the end of every rendered frame; inert unless a var is set. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        try {
            onRenderEnd(Minecraft.getMinecraft());
        } catch (Throwable ignored) {
            // A screenshot-harness failure must never disturb the frame loop.
        }
    }

    /** The state machine, called once per rendered frame. No-op when both vars are unset. */
    private static void onRenderEnd(Minecraft mc) {
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

        // One-shot coremod audit, independent of the shot pipeline.
        if (auditPending) {
            auditPending = false;
            runAudit();
        }

        if (outDir == null || mc == null) return;

        // Global watchdog — never hang.
        if (phase != P_DONE && startMs > 0 && System.currentTimeMillis() - startMs > WATCHDOG_MS) {
            System.out.println("[S1mp1e][DevShot] watchdog fired (" + (WATCHDOG_MS / 1000)
                    + "s) at phase " + phase + " — quitting.");
            try { mc.shutdown(); } catch (Throwable ignored) {}
            phase = P_DONE;
            return;
        }

        // A heavy step may re-fire this hook (nested render); ignore those re-entrant frames.
        if (busy) return;
        busy = true;
        try {
            // Sample the size the frame just rendered at BEFORE forcing the buffer for the next frame.
            lastRenderW = mc.displayWidth;
            lastRenderH = mc.displayHeight;
            resizedThisFrame = false;
            forceFramebuffer(mc);
            // A frame in which the framebuffer was just rebuilt holds nothing (a captured shot would be black, and the
            // screen was re-initialised): let it go by without stepping the script.
            if (resizedThisFrame && phase != P_INIT && phase != P_WAIT_TITLE && phase != P_TITLE) return;
            switch (phase) {
                case P_INIT:         stepInit(mc);                       break;
                case P_WAIT_TITLE:   stepWaitTitle(mc);                  break;
                case P_TITLE:        stepTitle(mc);                      break;
                case P_WAIT_CONFIG:  stepWaitConfig(mc, P_CONFIG);       break;
                case P_CONFIG:       stepConfig(mc);                     break;
                case P_WAIT_WORLD:   stepWaitWorld(mc);                  break;
                case P_WORLD_SETTLE: stepWorldSettle(mc);               break;
                case P_WORLD:        stepWorld(mc);                      break;
                case P_WAIT_CONFIG2: stepWaitConfig(mc, P_CONFIG_WORLD); break;
                case P_CONFIG_WORLD: stepConfigWorld(mc);               break;
                case P_WAIT_INV:     stepWaitInventory(mc);              break;
                case P_INV:          stepInventory(mc);                  break;
                case P_WAIT_OPTIONS: stepWaitOptions(mc);               break;
                case P_OPTIONS:      stepOptions(mc);                    break;
                case P_WAIT_EFF_INV: stepWaitEffInv(mc);                break;
                case P_EFFECTS:      stepEffects(mc);                    break;
                case P_WAIT_TOOLTIP: stepWaitTooltip(mc);              break;
                case P_TOOLTIP:      stepTooltip(mc);                   break;
                case P_CREATIVE_SETUP:       stepCreativeSetup(mc);     break;
                case P_CREATIVE_WAIT_MODE:   stepCreativeWaitMode(mc);  break;
                case P_CREATIVE_WAIT_SCREEN: stepCreativeWaitScreen(mc);break;
                case P_CREATIVE:             stepCreative(mc);          break;
                case P_CREATIVE_EXTRA:       stepCreativeExtra(mc);     break;
                case P_WAIT_ADV:             stepWaitAdvancements(mc);  break;
                case P_ADV:                  stepAdvancements(mc);      break;
                case P_HUD:                  stepHudChat(mc);           break;
                case P_HUD_INPUT:            stepHudInput(mc);          break;
                case P_HUD_CHROMA:           stepHudChroma(mc);         break;
                case P_SCENES_TITLE:
                    if (DevShotScenes.tick(mc)) {
                        if (createWorld(mc)) { frames = 0; phase = P_WAIT_WORLD; } else phase = P_STOP;
                    }
                    break;
                case P_SCENES:
                    clearToasts(mc);                  // not the chat: the HUD scenes push chat on purpose
                    if (DevShotScenes.tick(mc)) phase = P_STOP;
                    break;
                case P_STOP:         stepStop(mc);                       break;
                default:                                                 break;
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] fatal error at phase " + phase + ": " + t);
            t.printStackTrace();
            phase = P_STOP;
        } finally {
            busy = false;
        }
    }

    /**
     * Force MC's main framebuffer to {@link #SHOT_W}x{@link #SHOT_H} so every reference shot is
     * 1280x720 regardless of the on-screen window size. {@link Minecraft#resize} rebuilds
     * {@code framebufferMc} to that size and re-lays-out the current screen; the (unwatched) on-screen
     * blit is what gets clamped on a smaller desktop. No-op once the size already matches, so this is
     * cheap to call every frame and self-heals after any stray resize.
     */
    /** Set when forceFramebuffer rebuilt the framebuffer this frame: its content is a cleared buffer, not a frame. */
    private static boolean resizedThisFrame;

    private static void forceFramebuffer(Minecraft mc) {
        try {
            if (mc.displayWidth == shotW && mc.displayHeight == shotH) return;
            mc.resize(shotW, shotH);
            resizedThisFrame = true;
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + shotW + "x" + shotH);
        } catch (Throwable t) {
            skip("force framebuffer " + SHOT_W + "x" + SHOT_H, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(Minecraft mc) {
        try {
            mc.gameSettings.guiScale = 2;
            // the dev window may lose focus (other desktop activity): never let the game pause itself mid-scene
            mc.gameSettings.pauseOnLostFocus = false;
            // Only pay the resource-reload cost when the language actually differs.
            LanguageManager lm = mc.getLanguageManager();
            String cur = lm.getCurrentLanguage() != null ? lm.getCurrentLanguage().getLanguageCode() : null;
            if (!"zh_tw".equals(cur)) {
                Language target = lm.getLanguage("zh_tw");
                if (target != null) {
                    lm.setCurrentLanguage(target);
                    mc.gameSettings.language = "zh_tw";
                    mc.refreshResources();
                } else {
                    skip("set language zh_tw", new IllegalStateException("zh_tw not available"));
                }
            }
        } catch (Throwable t) {
            skip("init (scale/language)", t);
        }
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static int introFrame;

    private static void stepWaitTitle(Minecraft mc) {
        // mode "intro": shoot the boot intro (every 3rd frame) while it plays before the title
        if (dev.s1mp1e.client.gui.BrandIntro.showing() && DevShotScenes.has("intro")) {
            if (introFrame % 3 == 0) capture(mc, String.format("intro_%03d.png", introFrame / 3));
            introFrame++;
        }
        // Just wait for the title screen to exist; full-size stability is enforced in stepTitle.
        if (mc.currentScreen instanceof GuiMainMenu) {
            frames = 0; phase = P_TITLE;
        } else if (++frames > WAIT_SCREEN_CAP * 8) {
            skip("wait title screen", new IllegalStateException("title never shown"));
            frames = 0; phase = P_TITLE;   // shoot whatever is on screen, then continue
        }
    }

    private static void stepTitle(Minecraft mc) {
        // The title (GuiMainMenu) is created by MC's own boot while the OS window is still at its small
        // startup size, so its initGui laid the buttons out — and its panorama draws — for that small
        // ScaledResolution. forceFramebuffer then grows displayWidth/the FBO to 1280x720, but its guard
        // stops re-calling mc.resize once the size already matches, so the title is never re-laid and
        // renders into only the top-left of the buffer (screens WE open later are built at 1280x720, so
        // they are unaffected). Force one explicit re-layout: mc.resize() calls currentScreen.onResize
        // unconditionally, rebuilding the title for the full buffer; the next frames then render full.
        if (mc.currentScreen instanceof GuiMainMenu && frames == 0) {
            try { mc.resize(SHOT_W, SHOT_H); } catch (Throwable ignored) {}
        }
        if (++frames >= TITLE_FRAMES) {
            capture(mc, "title.png");
            if (!DevShotScenes.mode().isEmpty()) {
                System.out.println("[S1mp1e][DevShot] mode run: " + DevShotScenes.mode());
                DevShotScenes.queueTitle();
                frames = 0; phase = P_SCENES_TITLE;
                return;
            }
            open(mc, new S1mp1eConfigScreen(), "open settings (over title)");
            frames = 0; phase = P_WAIT_CONFIG;
        }
    }

    // ---- shared config-screen wait -----------------------------------------

    private static void stepWaitConfig(Minecraft mc, int next) {
        if (mc.currentScreen instanceof S1mp1eConfigScreen) {
            frames = 0; phase = next;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait settings screen", new IllegalStateException("settings screen never opened"));
            frames = 0; phase = next;
        }
    }

    // ---- step 3: config over title, then create the world ------------------

    private static void stepConfig(Minecraft mc) {
        if (++frames < CONFIG_FRAMES) return;
        capture(mc, "config.png");
        close(mc);                          // back to the title
        if (createWorld(mc)) {
            frames = 0; phase = P_WAIT_WORLD;
        } else {
            skip("create world", new IllegalStateException("world creation did not start"));
            phase = P_STOP;                 // no world -> skip every world-dependent shot
        }
    }

    // ---- step 4: world load, settle, loadout, shot ------------------------

    private static void stepWaitWorld(Minecraft mc) {
        if (mc.world != null && mc.player != null && mc.getIntegratedServer() != null
                && mc.currentScreen == null) {
            frames = 0; phase = P_WORLD_SETTLE;
        } else if (++frames > WAIT_WORLD_CAP) {
            skip("wait world load", new IllegalStateException("world never became ready"));
            phase = P_STOP;
        }
    }

    private static void stepWorldSettle(Minecraft mc) {
        // Keep the frame clean while the world settles (loadout below triggers recipe/advancement toasts).
        clearNotifications(mc);
        if (++frames < WORLD_SETTLE) return;
        applyWorldSetup(mc);                // time/weather/position/loadout (own try/catch inside)
        applyClientMirror(mc);
        frames = 0; phase = P_WORLD;
    }

    private static void stepWorld(Minecraft mc) {
        // Suppress the transient recipe/advancement toasts and any chat the loadout triggered, so the
        // over-world reference frames stay clean and deterministic. Cleared every frame; the toast is
        // drawn one frame ahead of this hook, so clearing here keeps the NEXT frame (and the capture)
        // clean.
        clearNotifications(mc);
        if (++frames >= WORLD_FRAMES) {
            capture(mc, "world.png");
            if (!DevShotScenes.mode().isEmpty()) {
                DevShotScenes.queueWorld();
                frames = 0; phase = P_SCENES;
                return;
            }
            open(mc, new S1mp1eConfigScreen(), "open settings (over world)");
            frames = 0; phase = P_WAIT_CONFIG2;
        }
    }

    // ---- step 5: config over world ----------------------------------------

    private static void stepConfigWorld(Minecraft mc) {
        clearNotifications(mc);
        if (++frames < CONFIG_FRAMES) return;
        capture(mc, "config-world.png");
        close(mc);
        open(mc, new GuiInventory(mc.player), "open inventory");
        frames = 0; phase = P_WAIT_INV;
    }

    // ---- step 6: inventory -------------------------------------------------

    private static void stepWaitInventory(Minecraft mc) {
        if (mc.currentScreen instanceof GuiInventory) {
            frames = 0; phase = P_INV;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait inventory screen", new IllegalStateException("inventory never opened"));
            frames = 0; phase = P_INV;
        }
    }

    private static void stepInventory(Minecraft mc) {
        clearNotifications(mc);
        if (++frames < INV_FRAMES) return;
        capture(mc, "inventory.png");
        try {
            GuiScreen parent = mc.currentScreen;   // the inventory, already shown
            mc.displayGuiScreen(new GuiVideoSettings(parent, mc.gameSettings));
        } catch (Throwable t) {
            skip("open video settings", t);
        }
        frames = 0; phase = P_WAIT_OPTIONS;
    }

    // ---- step 7: video settings -------------------------------------------

    private static void stepWaitOptions(Minecraft mc) {
        if (mc.currentScreen instanceof GuiVideoSettings) {
            frames = 0; phase = P_OPTIONS;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait video settings screen", new IllegalStateException("video settings never opened"));
            frames = 0; phase = P_OPTIONS;
        }
    }

    private static void stepOptions(Minecraft mc) {
        if (++frames < OPTIONS_FRAMES) return;
        capture(mc, "options.png");
        close(mc);
        // BATCH A (F): give the player several effects, reopen the inventory and shoot the
        // status-effect glass strip. Done here, after every base shot, so the base references
        // are unchanged. If the world/player is gone, skip straight to quit.
        if (mc.world != null && mc.player != null && giveEffects(mc)) {
            open(mc, new GuiInventory(mc.player), "open inventory (effects)");
            frames = 0; phase = P_WAIT_EFF_INV;
        } else {
            phase = P_STOP;
        }
    }

    // ---- BATCH A feature (F): status-effect glass strip ---------------------

    private static void stepWaitEffInv(Minecraft mc) {
        if (mc.currentScreen instanceof GuiInventory) {
            frames = 0; phase = P_EFFECTS;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait effects inventory", new IllegalStateException("inventory never opened"));
            frames = 0; phase = P_EFFECTS;
        }
    }

    private static void stepEffects(Minecraft mc) {
        clearNotifications(mc);
        if (++frames < INV_FRAMES) return;
        // effects-wide.png: the survival inventory with a multi-entry effect strip (F).
        capture(mc, "effects-wide.png");
        close(mc);
        // R1 tooltip scene: reopen the inventory via a probe that draws a glass
        // tooltip over the effect strip + items (player still has the effects).
        open(mc, new TooltipProbe(mc.player), "open tooltip probe");
        frames = 0; phase = P_WAIT_TOOLTIP;
    }

    // ---- BATCH A feature (E): tooltip on the very top layer (R1) ------------

    private static void stepWaitTooltip(Minecraft mc) {
        if (mc.currentScreen instanceof TooltipProbe) {
            frames = 0; phase = P_TOOLTIP;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait tooltip probe", new IllegalStateException("probe never opened"));
            frames = 0; phase = P_CREATIVE_SETUP;
        }
    }

    private static void stepTooltip(Minecraft mc) {
        clearNotifications(mc);
        if (++frames < INV_FRAMES) return;
        // tooltip.png: the glass tooltip card on the absolute top layer, refracting
        // the GUI-below (effect strip + items), grey scrim between glass and text.
        capture(mc, "tooltip.png");
        close(mc);
        frames = 0; phase = P_CREATIVE_SETUP;
    }

    // ---- BATCH A feature (C): creative screen + glass scrollbar -------------

    private static void stepCreativeSetup(Minecraft mc) {
        // Switch the player to creative so GuiContainerCreative doesn't bounce back to
        // the inventory (updateScreen kicks non-creative players out). Set it on the
        // server player; the client mode syncs over the next ticks.
        try {
            MinecraftServer server = mc.getIntegratedServer();
            EntityPlayerMP sp = server != null && !server.getPlayerList().getPlayers().isEmpty()
                    ? server.getPlayerList().getPlayers().get(0) : null;
            if (sp != null) {
                final EntityPlayerMP fsp = sp;    // server thread (see applyWorldSetup)
                server.addScheduledTask(new Runnable() { public void run() { fsp.setGameType(GameType.CREATIVE); } });
            }
        } catch (Throwable t) {
            skip("set creative gametype", t);
        }
        frames = 0; phase = P_CREATIVE_WAIT_MODE;
    }

    private static void stepCreativeWaitMode(Minecraft mc) {
        boolean creative = false;
        try { creative = mc.playerController != null && mc.playerController.isInCreativeMode(); }
        catch (Throwable ignored) {}
        if (creative) {
            open(mc, new GuiContainerCreative(mc.player), "open creative");
            frames = 0; phase = P_CREATIVE_WAIT_SCREEN;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait creative mode", new IllegalStateException("gametype never synced"));
            phase = P_STOP;
        }
    }

    private static void stepCreativeWaitScreen(Minecraft mc) {
        if (mc.currentScreen instanceof GuiContainerCreative) {
            // Force a scrollable item tab so the fused band, the glass scrollbar and the glide are all
            // exercised (a flat SURVIVAL world can open creative on the inventory tab — spec section 5).
            selectTab(mc, net.minecraft.creativetab.CreativeTabs.BUILDING_BLOCKS);
            frames = 0; phase = P_CREATIVE;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait creative screen", new IllegalStateException("creative never opened"));
            phase = P_STOP;
        }
    }

    private static void stepCreative(Minecraft mc) {
        clearNotifications(mc);
        // frame INV_FRAMES: resting scrollbar (white capsule thumb). Then force a
        // mid-track dragged state and shoot the held lens two dozen frames later.
        if (frames == INV_FRAMES) {
            capture(mc, "creative.png");
            forceCreativeDragged(mc);
        }
        if (++frames < INV_FRAMES + 24) return;
        capture(mc, "creative-scroll.png");
        dev.s1mp1e.client.gui.GlassScrollbar.DEV_FORCE_HELD = false;   // dev lens force is for this shot only
        // Features (B) fused tabs + (D) sub-pixel glide + click correctness, all on this same screen.
        csub = 0; frames = 0; phase = P_CREATIVE_EXTRA;
    }

    // ---- BATCH A features (B) fused tabs, (D) sub-pixel glide, click test -------------------------

    /**
     * Sub-state machine on the open creative screen. Drives the animations DevShot can control and
     * shoots consecutive-frame bursts so the motion is visible in stills: the selected pill sliding
     * within a row (tab switch on the same row), cross-fading across rows, the hover pill glide (a
     * virtual cursor), the sub-pixel content glide after a scroll jump, and a click-correctness log.
     */
    private static void stepCreativeExtra(Minecraft mc) {
        clearNotifications(mc);
        switch (csub) {
            case 0:   // fused band + selected pill at rest (feature B)
                if (frames == 0) {
                    selectTab(mc, net.minecraft.creativetab.CreativeTabs.BUILDING_BLOCKS);
                    setScroll(mc, 0f, false);
                    dev.s1mp1e.glass.render.GlassCreativeTabs.devClearHover();
                }
                if (++frames < 6) return;
                capture(mc, "tabs-rest.png");
                csub = 1; frames = 0; return;

            case 1:   // selected pill SLIDES within the top row (BUILDING_BLOCKS col0 -> DECORATIONS col1)
                if (frames == 0) selectTab(mc, net.minecraft.creativetab.CreativeTabs.DECORATIONS);
                capture(mc, "tabs-slide-" + frames + ".png");
                if (++frames <= 5) return;
                csub = 2; frames = 0; return;

            case 2:   // selection jumps to the OTHER row (DECORATIONS top -> COMBAT bottom) -> cross-fade
                if (frames == 0) selectTab(mc, net.minecraft.creativetab.CreativeTabs.COMBAT);
                capture(mc, "tabs-cross-" + frames + ".png");
                if (++frames <= 5) return;
                csub = 3; frames = 0; return;

            case 3:   // hover pill glide (virtual cursor over the DECORATIONS top-row cell)
                if (frames == 0) {
                    selectTab(mc, net.minecraft.creativetab.CreativeTabs.BUILDING_BLOCKS);
                    int[] gl = guiLeftTop(mc);
                    if (gl != null) dev.s1mp1e.glass.render.GlassCreativeTabs.devHover(gl[0] + 43, gl[1] - 12);
                }
                capture(mc, "tabs-hover-" + frames + ".png");
                if (++frames <= 3) return;
                dev.s1mp1e.glass.render.GlassCreativeTabs.devClearHover();
                csub = 4; frames = 0; return;

            case 4:   // silky sub-pixel content glide after one scroll jump (feature D)
                if (frames == 0) { selectTab(mc, net.minecraft.creativetab.CreativeTabs.BUILDING_BLOCKS); setScroll(mc, 0f, false); }
                if (frames == 2) setScroll(mc, 0.35f, false);   // logical jump; the eased content glides to it
                if (frames >= 2) capture(mc, "glide-" + (frames - 2) + ".png");
                if (++frames <= 10) return;
                csub = 5; frames = 0; return;

            case 5:   // click-correctness log (rest + mid-glide-then-snap)
                clicksTest(mc);
                close(mc);
                try {
                    if (mc.player != null && mc.player.connection != null) {
                        open(mc, new net.minecraft.client.gui.advancements.GuiScreenAdvancements(
                                mc.player.connection.getAdvancementManager()), "open advancements");
                        frames = 0; phase = P_WAIT_ADV;
                        return;
                    }
                } catch (Throwable t) {
                    skip("open advancements", t);
                }
                phase = P_STOP;
                return;

            default:
                phase = P_STOP;
        }
    }

    /** Reflectively select a creative tab (drives the selected-pill slide / cross-fade). */
    private static void selectTab(Minecraft mc, net.minecraft.creativetab.CreativeTabs tab) {
        try {
            if (!(mc.currentScreen instanceof GuiContainerCreative)) return;
            java.lang.reflect.Method m = null;
            String[] names = { "func_147050_b", "setCurrentCreativeTab" };
            for (int i = 0; i < names.length && m == null; i++) {
                try { m = GuiContainerCreative.class.getDeclaredMethod(names[i], net.minecraft.creativetab.CreativeTabs.class); }
                catch (NoSuchMethodException ignored) {}
            }
            if (m != null) { m.setAccessible(true); m.invoke(mc.currentScreen, tab); }
        } catch (Throwable t) {
            skip("select creative tab", t);
        }
    }

    /** Reflectively set the vanilla scroll state and snap the grid content to it (the logical row). */
    private static void setScroll(Minecraft mc, float scroll, boolean scrolling) {
        try {
            GuiContainerCreative screen = (GuiContainerCreative) mc.currentScreen;
            java.lang.reflect.Field sc = declared(GuiContainerCreative.class, "field_147067_x", "currentScroll");
            java.lang.reflect.Field is = declared(GuiContainerCreative.class, "field_147066_y", "isScrolling");
            if (sc != null) sc.setFloat(screen, scroll);
            if (is != null) is.setBoolean(screen, scrolling);
            // snap the container's 45 grid slots to the logical row for this scroll (ContainerCreative.scrollTo)
            Object menu = screen.inventorySlots;
            java.lang.reflect.Method st = null;
            String[] names = { "scrollTo", "func_148329_a" };
            for (int i = 0; i < names.length && st == null; i++) {
                try { st = menu.getClass().getDeclaredMethod(names[i], float.class); } catch (NoSuchMethodException ignored) {}
            }
            if (st != null) { st.setAccessible(true); st.invoke(menu, scroll); }
        } catch (Throwable t) {
            skip("set creative scroll", t);
        }
    }

    /** The open container's {guiLeft, guiTop}, or null. */
    private static int[] guiLeftTop(Minecraft mc) {
        try {
            java.lang.reflect.Field gl = declared(net.minecraft.client.gui.inventory.GuiContainer.class, "field_147003_i", "guiLeft");
            java.lang.reflect.Field gt = declared(net.minecraft.client.gui.inventory.GuiContainer.class, "field_147009_r", "guiTop");
            if (gl == null || gt == null) return null;
            return new int[] { gl.getInt(mc.currentScreen), gt.getInt(mc.currentScreen) };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Click-correctness assertions (feature D): the grid always acts on the item drawn under the cursor.
     * Vanilla keeps the logical scroll row-aligned; the overlay row must equal that logical row (a) at
     * rest and (b) after a mid-glide click snaps the bar to the target. Logs {@code N PASS / M FAIL} and
     * also exercises the real {@code mouseClicked} snap splice with a programmatic click on a tab cell.
     */
    private static void clicksTest(Minecraft mc) {
        int pass = 0, fail = 0;
        try {
            GuiContainerCreative screen = (GuiContainerCreative) mc.currentScreen;
            selectTab(mc, net.minecraft.creativetab.CreativeTabs.BUILDING_BLOCKS);

            // (a) at rest: settle on row 0, then a click must see overlay-row == logical-row (both 0).
            setScroll(mc, 0f, false);
            dev.s1mp1e.glass.hook.CreativeGlideHook.snapOnClick(screen);
            boolean restOk = !dev.s1mp1e.glass.hook.CreativeGlideHook.gliding(screen);
            if (restOk) pass++; else fail++;
            System.out.println("[S1mp1e][DevShot] click@rest: gliding=" + !restOk + " -> " + (restOk ? "PASS" : "FAIL"));

            // (b) mid-glide: jump the logical scroll, leave the bar mid-glide, then snap on click. After
            // the snap the bar sits on the target row, so the drawn overlay == the row vanilla hit-tests.
            setScroll(mc, 0.5f, false);
            dev.s1mp1e.glass.hook.CreativeGlideHook.snapOnClick(screen);
            boolean midOk = !dev.s1mp1e.glass.hook.CreativeGlideHook.gliding(screen);
            if (midOk) pass++; else fail++;
            System.out.println("[S1mp1e][DevShot] click@mid-glide-snap: gliding=" + !midOk + " -> " + (midOk ? "PASS" : "FAIL"));

            // exercise the real mouseClicked snap splice with a click on a tab cell (harmless re-select).
            int[] gl = guiLeftTop(mc);
            if (gl != null) {
                try {
                    java.lang.reflect.Method mcm = GuiContainerCreative.class.getDeclaredMethod(
                            "func_73864_a", int.class, int.class, int.class);
                    mcm.setAccessible(true);
                    mcm.invoke(screen, gl[0] + 14, gl[1] - 12, 0);   // top-left tab cell
                    pass++;
                    System.out.println("[S1mp1e][DevShot] programmatic mouseClicked on tab: PASS");
                } catch (Throwable t) {
                    // fall back to the MCP name
                    try {
                        java.lang.reflect.Method mcm = GuiContainerCreative.class.getDeclaredMethod(
                                "mouseClicked", int.class, int.class, int.class);
                        mcm.setAccessible(true);
                        mcm.invoke(screen, gl[0] + 14, gl[1] - 12, 0);
                        pass++;
                        System.out.println("[S1mp1e][DevShot] programmatic mouseClicked on tab: PASS");
                    } catch (Throwable t2) {
                        fail++;
                        System.out.println("[S1mp1e][DevShot] programmatic mouseClicked: FAIL " + t2);
                    }
                }
            }
        } catch (Throwable t) {
            fail++;
            skip("clicks test", t);
        }
        System.out.println("[S1mp1e][DevShot] clicks: " + pass + " PASS / " + fail + " FAIL");
    }

    // ---- BATCH A feature (A): advancements window framed in glass -----------

    private static void stepWaitAdvancements(Minecraft mc) {
        if (mc.currentScreen instanceof net.minecraft.client.gui.advancements.GuiScreenAdvancements) {
            frames = 0; phase = P_ADV;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait advancements screen", new IllegalStateException("advancements never opened"));
            frames = 0; phase = P_HUD;   // still run the BATCH B HUD scenes
        }
    }

    private static void stepAdvancements(Minecraft mc) {
        clearNotifications(mc);
        if (++frames < INV_FRAMES) return;
        capture(mc, "advancements.png");
        close(mc);
        frames = 0; phase = P_HUD;
    }

    // ---- BATCH B (G/H) HUD scenes ------------------------------------------

    /** G1 — chat panel: push a few messages (incl. CJK) into the in-world chat and shoot it. */
    private static void stepHudChat(Minecraft mc) {
        if (frames == 0) {
            close(mc);   // back to the world; chat renders in the HUD pass
            try {
                if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                    net.minecraft.client.gui.GuiNewChat chat = mc.ingameGUI.getChatGUI();
                    chat.clearChatMessages(false);
                    chat.printChatMessage(new net.minecraft.util.text.TextComponentString("§bS1mp1e§r liquid glass chat"));
                    chat.printChatMessage(new net.minecraft.util.text.TextComponentString("玻璃聊天面板：最寬的一行"));
                    chat.printChatMessage(new net.minecraft.util.text.TextComponentString("<Steve> gg wp"));
                    chat.printChatMessage(new net.minecraft.util.text.TextComponentString("折射下方的世界"));
                }
            } catch (Throwable t) {
                skip("push chat messages", t);
            }
        }
        if (++frames < INV_FRAMES) return;
        capture(mc, "hud-chat.png");
        frames = 0; phase = P_HUD_INPUT;
    }

    /** G1 — the open chat input becomes a glass bar. */
    private static void stepHudInput(Minecraft mc) {
        if (frames == 0) {
            open(mc, new net.minecraft.client.gui.GuiChat(), "open chat input");
        }
        if (++frames < INV_FRAMES) return;
        capture(mc, "hud-chatinput.png");
        close(mc);
        frames = 0; phase = P_HUD_CHROMA;
    }

    /** H2 — Chroma HUD: enable it + the FPS HUD so the FPS text renders as a moving rainbow. */
    private static void stepHudChroma(Minecraft mc) {
        if (frames == 0) {
            close(mc);
            savedFps = setModuleEnabled("FpsHUD", true);
            savedChroma = setModuleEnabled("ChromaHud", true);
        }
        if (++frames < INV_FRAMES) return;
        capture(mc, "hud-chroma.png");
        // Restore the module flags (nothing is ever saved to disk).
        setModuleEnabled("FpsHUD", savedFps);
        setModuleEnabled("ChromaHud", savedChroma);
        frames = 0; phase = P_STOP;
    }

    private static boolean savedFps, savedChroma;

    /** Set a module's enabled flag by name; returns the PREVIOUS value (false if not found). */
    private static boolean setModuleEnabled(String name, boolean on) {
        try {
            dev.s1mp1e.client.Module m = dev.s1mp1e.client.ModuleManager.byName(name);
            if (m == null) return false;
            boolean prev = m.enabled;
            m.enabled = on;
            return prev;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reflect {@code currentScroll = 0.5}, {@code isScrolling = true} so the shot
     *  shows the scrollbar thumb morphed into the refracting glass lens. */
    private static void forceCreativeDragged(Minecraft mc) {
        try {
            GuiContainerCreative screen = (GuiContainerCreative) mc.currentScreen;
            java.lang.reflect.Field sc = declared(GuiContainerCreative.class, "field_147067_x", "currentScroll");
            java.lang.reflect.Field is = declared(GuiContainerCreative.class, "field_147066_y", "isScrolling");
            if (sc != null) sc.setFloat(screen, 0.5F);
            if (is != null) is.setBoolean(screen, true);
            // Vanilla clears isScrolling on the next input frame, so the morph never engages in a scripted
            // still. Force the lens visual directly (dev-only) so the held refracting lens is captured.
            dev.s1mp1e.client.gui.GlassScrollbar.DEV_FORCE_HELD = true;
        } catch (Throwable t) {
            skip("force creative drag state", t);
        }
    }

    private static java.lang.reflect.Field declared(Class<?> cls, String srg, String mcp) {
        String[] names = { srg, mcp };
        for (int i = 0; i < names.length; i++) {
            try { java.lang.reflect.Field f = cls.getDeclaredField(names[i]); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Give the player four visible effects (beneficial + harmful, with icons, no
     * particles so the frame stays clean) so the effect strip shows several entries
     * with separators. Applied to both the server player (authoritative, syncs) and
     * the client player (visible next frame). Returns false only if neither exists.
     */
    private static boolean giveEffects(Minecraft mc) {
        boolean any = false;
        try {
            EntityPlayerSP cp = mc.player;
            if (cp != null) {
                cp.addPotionEffect(new PotionEffect(MobEffects.SPEED,        6000, 0, false, false));
                cp.addPotionEffect(new PotionEffect(MobEffects.STRENGTH,     6000, 1, false, false));
                cp.addPotionEffect(new PotionEffect(MobEffects.POISON,       6000, 0, false, false));
                cp.addPotionEffect(new PotionEffect(MobEffects.NIGHT_VISION, 6000, 0, false, false));
                any = true;
            }
        } catch (Throwable t) {
            skip("give client effects", t);
        }
        try {
            MinecraftServer server = mc.getIntegratedServer();
            EntityPlayerMP sp = server != null && !server.getPlayerList().getPlayers().isEmpty()
                    ? server.getPlayerList().getPlayers().get(0) : null;
            if (sp != null) {
                final EntityPlayerMP fsp = sp;    // server thread (see applyWorldSetup)
                server.addScheduledTask(new Runnable() { public void run() {
                    fsp.addPotionEffect(new PotionEffect(MobEffects.SPEED,        6000, 0, false, false));
                    fsp.addPotionEffect(new PotionEffect(MobEffects.STRENGTH,     6000, 1, false, false));
                    fsp.addPotionEffect(new PotionEffect(MobEffects.POISON,       6000, 0, false, false));
                    fsp.addPotionEffect(new PotionEffect(MobEffects.NIGHT_VISION, 6000, 0, false, false));
                } });
                any = true;
            }
        } catch (Throwable t) {
            skip("give server effects", t);
        }
        return any;
    }

    private static int stopFrames;

    private static void stepStop(Minecraft mc) {
        // 絕不在世界裡直接關遊戲：先照暫停選單「儲存並離開」的做法離開世界（Forge 的 loadWorld(null) 會等整合伺服器
        // 存完檔、停下來才返回），回到標題畫面，等幾幀之後才關（2026-10-04，同 1.8.9）。
        if (mc.world != null) {
            System.out.println("[S1mp1e][DevShot] done, leaving the world (save + quit to title) before quitting.");
            try {
                mc.world.sendQuittingDisconnectingPacket();
                mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
                mc.displayGuiScreen(new GuiMainMenu());
            } catch (Throwable t) {
                skip("leave world", t);
            }
            stopFrames = 0;
            return;
        }
        if (++stopFrames < 20) return;
        System.out.println("[S1mp1e][DevShot] done, quitting.");
        try { mc.shutdown(); } catch (Throwable ignored) {}
        phase = P_DONE;
    }

    // ---- world creation + setup -------------------------------------------

    /** Delete any previous {@code devshot} save and start a fresh flat world. Attempted once only. */
    private static boolean createWorld(Minecraft mc) {
        if (worldTried) return false;   // never start world creation twice
        worldTried = true;
        try {
            File saves = new File(mc.gameDir, "saves");
            deleteRecursively(new File(saves, "devshot"));
        } catch (Throwable t) {
            skip("delete previous devshot save", t);
        }
        try {
            WorldSettings settings = new WorldSettings(
                    12345L, GameType.SURVIVAL, false /* structures */, false /* hardcore */, WorldType.FLAT);
            settings.enableCommands();   // cheats
            mc.launchIntegratedServer("devshot", "devshot", settings);
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /** Time / weather / difficulty / camera angle / scripted loadout. Each sub-part is independently guarded. */
    private static void applyWorldSetup(Minecraft mc) {
        MinecraftServer server = mc.getIntegratedServer();
        // Server-side half on the SERVER thread: touching the server player's inventory / advancements from the client
        // render thread raced the server tick (ConcurrentModificationException in InventoryChangeTrigger, seen once).
        if (server != null) {
            final MinecraftServer srv = server;
            srv.addScheduledTask(new Runnable() { public void run() { applyServerSetup(srv); } });
        }
    }

    private static void applyServerSetup(MinecraftServer server) {
        // difficulty + time + weather (server-authoritative)
        try {
            if (server != null) {
                server.setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL);
                WorldServer ow = server.worlds != null && server.worlds.length > 0 ? server.worlds[0] : null;
                if (ow != null) {
                    ow.setWorldTime(6000L);
                    WorldInfo wi = ow.getWorldInfo();
                    wi.setRaining(false);
                    wi.setThundering(false);
                    wi.setRainTime(1_000_000);
                    wi.setThunderTime(1_000_000);
                    wi.setCleanWeatherTime(1_000_000);
                    try { ow.getGameRules().setOrCreateGameRule("announceAdvancements", "false"); } catch (Throwable ignored) {}
                    try { ow.getGameRules().setOrCreateGameRule("doDaylightCycle", "false"); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            skip("set difficulty/time/weather", t);
        }
        // loadout on the server player (auto-syncs to the client within a couple of ticks)
        try {
            EntityPlayerMP sp = server != null && !server.getPlayerList().getPlayers().isEmpty()
                    ? server.getPlayerList().getPlayers().get(0) : null;
            if (sp != null) {
                sp.inventory.setInventorySlotContents(0, new ItemStack(Items.DIAMOND_SWORD));
                sp.inventory.setInventorySlotContents(1, new ItemStack(Items.COOKED_BEEF, 32));
                sp.inventory.setInventorySlotContents(2, new ItemStack(Blocks.STONE, 64));
                sp.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                sp.setItemStackToSlot(EntityEquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
                sp.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                sp.setItemStackToSlot(EntityEquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
                sp.setItemStackToSlot(EntityEquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
                // 60 s Speed I, icon on but no ambient particles (keeps the reference frame clean).
                sp.addPotionEffect(new PotionEffect(MobEffects.SPEED, 1200, 0, false, false));
                sp.inventory.currentItem = 0;                        // hold the sword
                sp.inventoryContainer.detectAndSendChanges();
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the flat plain).
                sp.connection.setPlayerLocation(sp.posX, sp.posY, sp.posZ, 0f, 15f);
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
    }

    private static void applyClientMirror(Minecraft mc) {
        // mirror the loadout + camera on the client player so the very next frame already shows it
        try {
            EntityPlayerSP cp = mc.player;
            cp.inventory.setInventorySlotContents(0, new ItemStack(Items.DIAMOND_SWORD));
            cp.inventory.setInventorySlotContents(1, new ItemStack(Items.COOKED_BEEF, 32));
            cp.inventory.setInventorySlotContents(2, new ItemStack(Blocks.STONE, 64));
            cp.inventory.currentItem = 0;                            // hold the sword
            cp.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            cp.setItemStackToSlot(EntityEquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
            cp.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            cp.setItemStackToSlot(EntityEquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
            cp.setItemStackToSlot(EntityEquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
            cp.rotationYaw = 0f;  cp.prevRotationYaw = 0f;  cp.rotationYawHead = 0f;  cp.prevRotationYawHead = 0f;
            cp.rotationPitch = 15f; cp.prevRotationPitch = 15f;
        } catch (Throwable t) {
            skip("mirror loadout on client", t);
        }
    }

    /** Drop queued/visible toasts only (the scene queues keep the chat). */
    private static void clearToasts(Minecraft mc) {
        try {
            net.minecraft.client.gui.toasts.GuiToast gt = mc.getToastGui();
            if (gt == null) return;
            java.lang.reflect.Field qf = net.minecraft.client.gui.toasts.GuiToast.class.getDeclaredField("toastsQueue");
            qf.setAccessible(true);
            Object q = qf.get(gt);
            if (q instanceof Deque) ((Deque<?>) q).clear();
            java.lang.reflect.Field vf = net.minecraft.client.gui.toasts.GuiToast.class.getDeclaredField("visible");
            vf.setAccessible(true);
            Object[] vis = (Object[]) vf.get(gt);
            if (vis != null) for (int i = 0; i < vis.length; i++) vis[i] = null;
        } catch (Throwable ignored) {}
    }

    /** Drop any queued/visible advancement + recipe toasts and clear chat, so world shots stay clean. */
    private static void clearNotifications(Minecraft mc) {
        try {
            net.minecraft.client.gui.toasts.GuiToast gt = mc.getToastGui();
            if (gt != null) {
                // DEV-ONLY reflection (DevShot runs solely under S1MP1E_SHOT, never in a launcher
                // build): the toast queue/visible array have no public clear. MCP names in dev.
                java.lang.reflect.Field qf = net.minecraft.client.gui.toasts.GuiToast.class.getDeclaredField("toastsQueue");
                qf.setAccessible(true);
                Object q = qf.get(gt);
                if (q instanceof Deque) ((Deque<?>) q).clear();
                java.lang.reflect.Field vf = net.minecraft.client.gui.toasts.GuiToast.class.getDeclaredField("visible");
                vf.setAccessible(true);
                Object[] vis = (Object[]) vf.get(gt);
                if (vis != null) for (int i = 0; i < vis.length; i++) vis[i] = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().clearChatMessages(false);
            }
        } catch (Throwable ignored) {}
    }

    // ---- coremod audit -----------------------------------------------------

    /**
     * Log every coremod patch site's PASS/FAIL (this is a Forge coremod build, so there is no Mixin
     * environment to audit). Every transformer target is force-loaded first so its transformer has
     * definitely run — GuiButton/GuiScreen/GuiMainMenu are already up by the first frame, but
     * GuiContainer, GuiSlot, RenderLivingBase, the recipe-book classes and GuiButtonExt may still be
     * lazy — then each transformer's own success flag is read. A single "not applied" site is a
     * failed patch and the summary line reports it.
     */
    private static void runAudit() {
        try {
            ClassLoader cl = Minecraft.class.getClassLoader();
            String[] targets = {
                "net.minecraft.client.gui.GuiButton",
                "net.minecraftforge.fml.client.config.GuiButtonExt",
                "net.minecraft.client.gui.inventory.GuiContainer",
                "net.minecraft.client.gui.inventory.GuiContainerCreative",
                "net.minecraft.client.gui.advancements.GuiScreenAdvancements",
                "net.minecraft.client.renderer.InventoryEffectRenderer",
                "net.minecraft.client.gui.Gui",
                "net.minecraft.client.gui.GuiScreen",
                "net.minecraft.client.gui.GuiMainMenu",
                "net.minecraft.client.gui.GuiSlot",
                "net.minecraft.client.gui.recipebook.GuiRecipeBook",
                "net.minecraft.client.gui.recipebook.GuiButtonRecipeTab",
                "net.minecraft.client.gui.recipebook.GuiButtonRecipe",
                "net.minecraft.client.gui.GuiButtonToggle",
                "net.minecraft.client.renderer.EntityRenderer",
                "net.minecraft.client.renderer.entity.RenderLivingBase",
                "net.minecraft.client.renderer.ItemRenderer",
                "net.minecraft.client.settings.KeyBinding",
                "net.minecraft.client.settings.GameSettings",
                "net.minecraft.client.gui.FontRenderer",
                "net.minecraft.client.gui.GuiOptions",
                "net.minecraft.client.gui.GuiVideoSettings",
                "net.minecraft.client.gui.GuiControls",
                "net.minecraft.client.gui.GuiLanguage",
                "net.minecraft.client.gui.ScreenChatOptions",
                "net.minecraft.client.gui.GuiScreenOptionsSounds",
                "net.minecraft.client.gui.GuiCustomizeSkin",
                "net.minecraft.client.gui.GuiScreenResourcePacks",
                "net.minecraft.client.gui.GuiSnooper",
                "net.minecraft.client.gui.GuiCreateWorld",
                "net.minecraft.client.gui.GuiTextField",
                "net.minecraft.client.multiplayer.GuiConnecting",
                "net.minecraft.client.gui.GuiDownloadTerrain",
                "net.minecraft.client.gui.GuiScreenWorking",
                // BATCH B (G) HUD-overlay targets
                "net.minecraft.client.gui.GuiNewChat",
                "net.minecraft.client.gui.GuiChat",
                "net.minecraft.client.gui.GuiPlayerTabOverlay",
                "net.minecraftforge.client.GuiIngameForge",
                "net.minecraft.client.gui.toasts.AdvancementToast",
                "net.minecraft.client.gui.toasts.RecipeToast",
                "net.minecraft.client.gui.toasts.SystemToast",
                "net.minecraft.client.gui.toasts.TutorialToast",
                // ALLGLASS round (#1-#26) new coremod targets (#1/#4 are in GuiSlot, #17 in GuiIngameForge, already above)
                "net.minecraft.client.gui.GuiOverlayDebug",
                "net.minecraft.client.gui.GuiSubtitleOverlay",
                "net.minecraft.client.gui.GuiLockIconButton",
                "net.minecraft.client.gui.advancements.AdvancementTabType",
                "net.minecraft.client.gui.GuiRepair",
            };
            for (String t : targets) {
                try { Class.forName(t, false, cl); }
                catch (Throwable e) { System.out.println("[S1mp1e][audit] could not load " + t + ": " + e); }
            }

            StringBuilder fail = new StringBuilder();
            System.out.println("[S1mp1e] ===== COREMOD PATCH AUDIT =====");
            // glass UI transformer
            auditSite("GuiButton.drawButton",            dev.s1mp1e.glass.asm.S1mp1eTransformer.buttonPatched,      fail);
            auditSite("GuiButtonExt.drawButton",         dev.s1mp1e.glass.asm.S1mp1eTransformer.buttonExtPatched,   fail);
            auditSite("GuiContainer.drawScreen",         dev.s1mp1e.glass.asm.S1mp1eTransformer.containerPatched,   fail);
            auditSite("InventoryEffectRenderer.drawActivePotionEffects", dev.s1mp1e.glass.asm.S1mp1eTransformer.effectStripPatched, fail);
            auditSite("GuiContainerCreative scrollbar",  dev.s1mp1e.glass.asm.S1mp1eTransformer.creativeScrollPatched,  fail);
            auditSite("GuiContainer.drawSlot glide",     dev.s1mp1e.glass.asm.S1mp1eTransformer.creativeGlidePatched,  fail);
            auditSite("GuiContainerCreative.mouseClicked snap", dev.s1mp1e.glass.asm.S1mp1eTransformer.creativeClickPatched, fail);
            auditSite("GuiScreenAdvancements glass",     dev.s1mp1e.glass.asm.S1mp1eTransformer.advancementsPatched,   fail);
            auditSite("FontRenderer.renderString no-shadow", dev.s1mp1e.glass.asm.S1mp1eTransformer.textShadowPatched, fail);
            auditSite("SettingsShell drawScreen x" + dev.s1mp1e.glass.asm.S1mp1eTransformer.settingsDrawCount + " (group 1)",
                      dev.s1mp1e.glass.asm.S1mp1eTransformer.settingsDrawCount == 9, fail);
            auditSite("Tab-switch dissolves x" + dev.s1mp1e.glass.asm.S1mp1eTransformer.tabSwitchCount + " (group 5)", dev.s1mp1e.glass.asm.S1mp1eTransformer.tabSwitchCount == 3, fail);
            auditSite("Loading cards x" + dev.s1mp1e.glass.asm.S1mp1eTransformer.loadingCardCount + " (group 10)", dev.s1mp1e.glass.asm.S1mp1eTransformer.loadingCardCount == 3, fail);
            auditSite("GuiButton press pulse (group 9)", dev.s1mp1e.glass.asm.S1mp1eTransformer.pressPulsePatched, fail);
            auditSite("Tab list fade (group 7)", dev.s1mp1e.glass.asm.S1mp1eTransformer.tabFadePatched && dev.s1mp1e.glass.asm.S1mp1eTransformer.tabGatePatched, fail);
            auditSite("Health damage trail (group 7)", dev.s1mp1e.glass.asm.S1mp1eTransformer.healthTrailPatched, fail);
            auditSite("Scoreboard sidebar fade (group 7)", dev.s1mp1e.glass.asm.S1mp1eTransformer.scoreboardLookupPatched && dev.s1mp1e.glass.asm.S1mp1eTransformer.scoreboardRecordPatched, fail);
            auditSite("GuiChat close fade (group 7)", dev.s1mp1e.glass.asm.S1mp1eTransformer.chatClosePatched, fail);
            auditSite("GuiNewChat arrival (group 7)", dev.s1mp1e.glass.asm.S1mp1eTransformer.chatArrivalPatched, fail);
            auditSite("GuiSlot smooth wheel (group 6)", dev.s1mp1e.glass.asm.S1mp1eTransformer.listMotionPatched, fail);
            auditSite("GuiTextField typing (group 6)", dev.s1mp1e.glass.asm.S1mp1eTransformer.editBoxPatched, fail);
            auditSite("GuiContainer item flights (observe/draw/hide)", dev.s1mp1e.glass.asm.S1mp1eTransformer.itemFlightPatched, fail);
            auditSite("GuiScreen.mouseClicked settings gate", dev.s1mp1e.glass.asm.S1mp1eTransformer.settingsClickPatched, fail);
            auditSite("GuiScreen.handleMouseInput settings wheel gate", dev.s1mp1e.glass.asm.S1mp1eTransformer.settingsWheelPatched, fail);
            auditSite("Gui.drawTexturedModalRect",       dev.s1mp1e.glass.asm.S1mp1eTransformer.guiBlitPatched,     fail);
            auditSite("GuiScreen.drawBackground",        dev.s1mp1e.glass.asm.S1mp1eTransformer.screenBgPatched,    fail);
            auditSite("GuiScreen.drawHoveringText",      dev.s1mp1e.glass.asm.S1mp1eTransformer.tooltipPatched,     fail);
            auditSite("GuiMainMenu.drawScreen",          dev.s1mp1e.glass.asm.S1mp1eTransformer.mainMenuPatched,    fail);
            auditSite("GuiSlot list background",         dev.s1mp1e.glass.asm.S1mp1eTransformer.guiSlotPatched,     fail);
            auditSite("GuiRecipeBook.render",            dev.s1mp1e.glass.asm.S1mp1eTransformer.recipePanelPatched, fail);
            auditSite("GuiButtonRecipeTab.drawButton",   dev.s1mp1e.glass.asm.S1mp1eTransformer.recipeTabPatched,   fail);
            auditSite("GuiButtonRecipe.drawButton",      dev.s1mp1e.glass.asm.S1mp1eTransformer.recipeCellPatched,  fail);
            auditSite("GuiButtonToggle.drawButton",      dev.s1mp1e.glass.asm.S1mp1eTransformer.recipeTogglePatched, fail);
            // BATCH B (G) HUD overlays
            auditSite("GuiNewChat.drawChat (G1)",        dev.s1mp1e.glass.asm.S1mp1eTransformer.chatPatched,       fail);
            auditSite("GuiChat.drawScreen input (G1)",   dev.s1mp1e.glass.asm.S1mp1eTransformer.chatInputPatched,  fail);
            auditSite("GuiPlayerTabOverlay renderPlayerlist (G2)", dev.s1mp1e.glass.asm.S1mp1eTransformer.tabListPatched, fail);
            auditSite("GuiIngameForge.renderRecordOverlay (G5)",   dev.s1mp1e.glass.asm.S1mp1eTransformer.actionBarPatched, fail);
            auditSite("EntityRenderer.drawNameplate (G6)", dev.s1mp1e.glass.asm.S1mp1eTransformer.nameTagPatched,  fail);
            auditSite("Toasts glass card x" + dev.s1mp1e.glass.asm.S1mp1eTransformer.toastCount + " (G4)",
                      dev.s1mp1e.glass.asm.S1mp1eTransformer.toastCount == 4, fail);
            // ALLGLASS round (#1-#26)
            auditSite("GuiSlot Apple scroller (#1)",     dev.s1mp1e.glass.asm.S1mp1eTransformer.listScrollerPatched,  fail);
            auditSite("GuiSlot selection capsule (#4)",  dev.s1mp1e.glass.asm.S1mp1eTransformer.selectionGlassPatched, fail);
            auditSite("GuiOverlayDebug card (#15)",      dev.s1mp1e.glass.asm.S1mp1eTransformer.debugCardPatched,      fail);
            auditSite("GuiIngameForge.renderHUDText (#15)", dev.s1mp1e.glass.asm.S1mp1eTransformer.debugHudTextPatched, fail);
            auditSite("GuiRepair anvil field (#12)",     dev.s1mp1e.glass.asm.S1mp1eTransformer.anvilFieldPatched,     fail);
            auditSite("GuiSubtitleOverlay scrim (#16)",  dev.s1mp1e.glass.asm.S1mp1eTransformer.subtitlePatched,       fail);
            auditSite("Experience/jump bars (#17)",      dev.s1mp1e.glass.asm.S1mp1eTransformer.contextualBarPatched,  fail);
            auditSite("GuiLockIconButton glass (#21)",   dev.s1mp1e.glass.asm.S1mp1eTransformer.lockButtonPatched,     fail);
            auditSite("AdvancementTabType pill (#23)",   dev.s1mp1e.glass.asm.S1mp1eTransformer.advTabPatched,         fail);
            // render-only combat transformer
            auditSite("EntityRenderer.hurtCameraEffect", dev.s1mp1e.client.asm.CombatTransformer.hurtCamPatched,    fail);
            auditSite("RenderLivingBase.setBrightness",  dev.s1mp1e.client.asm.CombatTransformer.hurtFlashPatched,  fail);
            auditSite("ItemRenderer swing-while-using",  dev.s1mp1e.client.asm.CombatTransformer.swingPatched,      fail);
            // camera/view transformer
            auditSite("EntityRenderer.updateLightmap",   dev.s1mp1e.client.asm.CameraTransformer.gammaPatched,      fail);
            auditSite("ItemRenderer hand offset",        dev.s1mp1e.client.asm.CameraTransformer.handPatched,       fail);
            auditSite("EntityRenderer look-scale",       dev.s1mp1e.client.asm.CameraTransformer.lookScalePatched,  fail);
            auditSite("KeyBinding/GameSettings zoom filter", dev.s1mp1e.client.asm.CameraTransformer.keyFilterPatched, fail);
            System.out.println("[S1mp1e] ================================");

            if (fail.length() == 0) {
                System.out.println("[S1mp1e] COREMOD AUDIT COMPLETE");
            } else {
                System.out.println("[S1mp1e] COREMOD AUDIT FAILED:" + fail);
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e] COREMOD AUDIT FAILED: " + t);
            t.printStackTrace();
        }
    }

    private static void auditSite(String name, boolean ok, StringBuilder fail) {
        System.out.println("[S1mp1e][audit] " + (ok ? "PASS " : "FAIL ") + name);
        if (!ok) fail.append(' ').append(name).append(';');
    }

    // ---- helpers -----------------------------------------------------------

    private static void open(Minecraft mc, GuiScreen screen, String what) {
        try { mc.displayGuiScreen(screen); }
        catch (Throwable t) { skip(what, t); }
    }

    private static void close(Minecraft mc) {
        try { mc.displayGuiScreen(null); } catch (Throwable ignored) {}
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

    /**
     * DEV-only probe screen (feature E / R1): the survival inventory that, after its
     * own {@code drawScreen}, draws a glass tooltip card over the effect strip and the
     * item grid — so a shot shows the card on the very top layer, refracting the GUI
     * below it with the grey readability scrim. Drawn inside {@code drawScreen}, so the
     * GL state and layer order are exactly the real tooltip path.
     */
    static final class TooltipProbe extends GuiInventory {
        TooltipProbe(net.minecraft.entity.player.EntityPlayer p) { super(p); }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            try {
                int tx = this.guiLeft - 34;   // cursor over the effect strip (left)
                int ty = this.guiTop + 26;
                java.util.List<String> lines = new java.util.ArrayList<String>();
                lines.add("鐘石劍");                        // 鑽石劍
                lines.add("耐久度：1561 / 1561");       // 耐久度：1561 / 1561
                lines.add("在最上層折射下方"); // 在最上層折射下方
                dev.s1mp1e.glass.ui.GlassTooltip.draw(lines, tx, ty, this.width, this.height, this.fontRenderer);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Capture the current framebuffer to {@code outDir/name}, overwriting, with the game's own reader. */
    /** Capture entry for {@link DevShotScenes}. */
    static void captureExternal(Minecraft mc, String name) { capture(mc, name); }

    private static void capture(Minecraft mc, String name) {
        try {
            BufferedImage img = net.minecraft.util.ScreenShotHelper.createScreenshot(
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File out = new File(outDir, name);
            ImageIO.write(img, "png", out);
            System.out.println("[S1mp1e][DevShot] wrote " + out.getAbsolutePath()
                    + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] capture failed for " + name + ": " + t);
        }
    }
}
