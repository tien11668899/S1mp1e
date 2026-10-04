package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.client.module.BlockOutlineModule;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.resources.Language;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;

/**
 * DevShot v2 — deterministic screenshot harness for cross-version visual comparison.
 * 1.8.9 (Forge, LWJGL2, immediate-mode GL) port of {@code versions/mc1201}'s harness.
 *
 * <p><b>Completely inert unless the environment variable {@code S1MP1E_SHOT} names an output
 * folder</b> (the shot pipeline) or {@code S1MP1E_AUDIT} is set (the one-shot coremod audit). It
 * ships in the jar but does nothing at all when both are unset — a normal game run never touches
 * any of this. Environment variables reach the forked game JVM of {@code runClient}; a {@code -D}
 * system property on the gradle command line does not, which is why this reads {@link System#getenv}.
 *
 * <p>When the shot pipeline is active, so that every S1mp1e version renders under identical
 * conditions, on the first rendered frame it forces the framebuffer to 1280x720, GUI scale 2 and the
 * language to {@code zh_tw}, then walks a fixed script and writes seven PNGs with the game's own
 * framebuffer writer ({@link net.minecraft.util.ScreenShotHelper#saveScreenshot}) into the folder
 * {@code S1MP1E_SHOT} names, overwriting:
 * <ol>
 *   <li>{@code title.png}   — the title screen, 60 frames after it is fully shown;</li>
 *   <li>{@code config.png}  — the S1mp1e settings page over the title, +90 frames;</li>
 *   <li>{@code world.png}   — a fresh flat {@code devshot} world (seed 12345, survival, peaceful,
 *       cheats) with a scripted loadout (sword / cooked beef / stone / full iron armor / Speed;
 *       1.8.9 has no off hand, so the shield/off-hand step is skipped), +60 frames after settle;</li>
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
 * <p>Driven from a single render-frame hook: {@link DevShotDriver} on Forge's
 * {@code TickEvent.RenderTickEvent} END, which fires right after the frame is fully drawn into
 * {@code framebufferMc} and while it is still bound (the same complete frame vanilla's F2 reads).
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
                             // BATCH-A surfaces: effect strip / creative fused tabs+scrollbar / book.
                             P_WAIT_CREATIVE = 16, P_CREATIVE = 17,
                             P_WAIT_BOOK = 18, P_BOOK = 19,
                             // Feature D: sub-pixel glide filmstrip + click-correctness sweep.
                             P_CREATIVE_GLIDE = 20,
                             // Batch B (G/H): HUD overlays, chat input, world modules, name tag.
                             P_HUD = 21, P_HUD_INPUT = 22, P_MODULES = 23, P_NAMETAG = 24,
                             // Feature B: creative tab pill slide (same row) + cross-fade (row switch).
                             P_TABS = 26,
                             // 模式跑（S1MP1E_SHOT_MODE）：title.png 後的標題佇列、world.png 後的世界佇列。
                             P_SCENES_TITLE = 27, P_SCENES = 28,
                             P_STOP = 14, P_DONE = 15;

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    private static boolean auditFinalPending; // re-log the full patch list once the run is done
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: any heavy action that could re-fire this hook does nothing nested. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** Target reference resolution — forced onto the framebuffer so shots are 1280x720 even when
     *  the desktop is smaller than that and the OS clamps the on-screen window. */
    /** 參考截圖解析度；場景可以暫時改小（小視窗那張），所以不是常數。 */
    static int shotW = 1280, shotH = 720;

    /** Called at the end of every rendered frame (render thread). No-op when both vars are unset. */
    public static void onRenderEnd(Minecraft mc) {
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
        // 模式跑會多跑很多場景，看門狗放寬到 600 s；固定腳本維持 240 s。
        long watchdog = DevShotScenes.mode().isEmpty() ? WATCHDOG_MS : 600_000L;
        if (phase != P_DONE && startMs > 0 && System.currentTimeMillis() - startMs > watchdog) {
            System.out.println("[S1mp1e][DevShot] watchdog fired (" + (watchdog / 1000)
                    + "s) at phase " + phase + " — quitting.");
            try { mc.shutdown(); } catch (Throwable ignored) {}
            phase = P_DONE;
            return;
        }

        // A heavy step may re-fire this hook (nested render); ignore those re-entrant frames.
        if (busy) return;
        busy = true;
        try {
            // Keep the framebuffer at the reference resolution every frame (cheap no-op once set).
            forceFramebuffer(mc);
            switch (phase) {
                case P_INIT:               stepInit(mc);                     break;
                case P_WAIT_TITLE:         stepWaitTitle(mc);                break;
                case P_TITLE:              stepTitle(mc);                    break;
                case P_WAIT_CONFIG:        stepWaitConfig(mc, P_CONFIG);     break;
                case P_CONFIG:             stepConfig(mc);                   break;
                case P_WAIT_WORLD:         stepWaitWorld(mc);                break;
                case P_WORLD_SETTLE:       stepWorldSettle(mc);              break;
                case P_WORLD:              stepWorld(mc);                    break;
                case P_WAIT_CONFIG2:       stepWaitConfig(mc, P_CONFIG_WORLD); break;
                case P_CONFIG_WORLD:       stepConfigWorld(mc);             break;
                case P_WAIT_INV:           stepWaitInventory(mc);           break;
                case P_INV:                stepInventory(mc);               break;
                case P_WAIT_OPTIONS:       stepWaitOptions(mc);             break;
                case P_OPTIONS:            stepOptions(mc);                 break;
                case P_WAIT_CREATIVE:      stepWaitCreative(mc);            break;
                case P_CREATIVE:           stepCreative(mc);               break;
                case P_WAIT_BOOK:          stepWaitBook(mc);               break;
                case P_BOOK:               stepBook(mc);                   break;
                case P_CREATIVE_GLIDE:     stepCreativeGlide(mc);          break;
                case P_TABS:               stepTabs(mc);                   break;
                case P_HUD:                stepHud(mc);                    break;
                case P_HUD_INPUT:          stepHudInput(mc);               break;
                case P_MODULES:            stepModules(mc);                break;
                case P_NAMETAG:            stepNameTag(mc);                break;
                case P_SCENES_TITLE:
                    if (DevShotScenes.tick(mc)) {
                        if (createWorld(mc)) { frames = 0; phase = P_WAIT_WORLD; } else phase = P_STOP;
                    }
                    break;
                case P_SCENES:
                    if (DevShotScenes.tick(mc)) phase = P_STOP;
                    break;
                case P_STOP:               stepStop(mc);                    break;
                default:                   break;
            }
        } catch (Throwable t) {
            // Last-resort guard: never wedge the frame loop.
            System.out.println("[S1mp1e][DevShot] fatal error at phase " + phase + ": " + t);
            t.printStackTrace();
            phase = P_STOP;
        } finally {
            busy = false;
        }
    }

    /**
     * Force MC's main framebuffer to {@link #shotW}x{@link #shotH} so every reference shot is
     * 1280x720 regardless of the desktop size. {@link Minecraft#resize} sets displayWidth/Height and
     * reallocates {@code framebufferMc} to that size; on a desktop smaller than 1280x720 the OS
     * clamps the on-screen window, but the off-screen framebuffer we screenshot is still full size.
     *
     * <p>No-op once the size already matches, so this is cheap to call every frame and self-heals
     * after {@code checkWindowResize()} clamps displayWidth back to the real window on a resize.
     */
    private static void forceFramebuffer(Minecraft mc) {
        try {
            if (mc.displayWidth == shotW && mc.displayHeight == shotH) return;
            mc.resize(shotW, shotH);   // sets displayWidth/Height + reallocates framebufferMc + relays currentScreen
            System.out.println("[S1mp1e][DevShot] framebuffer forced to " + shotW + "x" + shotH);
        } catch (Throwable t) {
            skip("force framebuffer " + shotW + "x" + shotH, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    private static void stepInit(Minecraft mc) {
        try {
            // Cosmetic on-screen window size; the shot itself comes from the forced framebuffer.
            try { org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(shotW, shotH)); }
            catch (Throwable ignored) {}
            mc.gameSettings.guiScale = 2;
            // Only pay the resource-reload cost when the language actually differs. 1.8.9 registers
            // language codes with an uppercase region ("zh_TW", "en_US"), newer versions use "zh_tw",
            // so match case-insensitively and adopt whatever the game actually calls it.
            Language cur = mc.getLanguageManager().getCurrentLanguage();
            if (cur == null || !"zh_tw".equalsIgnoreCase(cur.getLanguageCode())) {
                Language target = null;
                StringBuilder avail = new StringBuilder();
                for (Language l : mc.getLanguageManager().getLanguages()) {
                    avail.append(l.getLanguageCode()).append(' ');
                    if ("zh_tw".equalsIgnoreCase(l.getLanguageCode())) { target = l; break; }
                }
                if (target != null) {
                    mc.getLanguageManager().setCurrentLanguage(target);
                    mc.gameSettings.language = target.getLanguageCode();
                    mc.refreshResources();
                    System.out.println("[S1mp1e][DevShot] language set to " + target.getLanguageCode());
                } else {
                    skip("set language zh_tw", new IllegalStateException(
                            "zh_tw not in language list; available = " + avail));
                }
            }
        } catch (Throwable t) {
            skip("init (window/scale/language)", t);
        }
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static int introFrame;

    private static void stepWaitTitle(Minecraft mc) {
        // 模式 intro：開機的品牌開場在標題畫面之前播放，每 3 幀拍一張
        if (dev.s1mp1e.client.gui.BrandIntro.showing() && DevShotScenes.has("intro")) {
            if (introFrame % 3 == 0) capture(mc, String.format("intro_%03d.png", introFrame / 3));
            introFrame++;
        }
        // Wait past the Mojang splash / any resource reload until the title is the current screen.
        if (mc.currentScreen instanceof GuiMainMenu) {
            frames = 0; phase = P_TITLE;
        } else if (++frames > WAIT_SCREEN_CAP * 8) {   // 開場會延後標題出現
            skip("wait title screen", new IllegalStateException("title never shown"));
            frames = 0; phase = P_TITLE;   // try to shoot whatever is on screen, then continue
        }
    }

    private static void stepTitle(Minecraft mc) {
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
        // Do NOT require currentScreen==null: after spawn a transient GuiDownloadTerrain (or a menu
        // opened by a focus change while the user's desktop is busy) can linger. Once the world and
        // player exist, dismiss any such screen ourselves and proceed.
        if (mc.theWorld != null && mc.thePlayer != null && mc.getIntegratedServer() != null) {
            if (mc.currentScreen != null) mc.displayGuiScreen(null);
            frames = 0; phase = P_WORLD_SETTLE;
        } else if (++frames > WAIT_WORLD_CAP) {
            skip("wait world load", new IllegalStateException("world never became ready"
                    + " [world=" + (mc.theWorld != null) + " player=" + (mc.thePlayer != null)
                    + " server=" + (mc.getIntegratedServer() != null)
                    + " screen=" + (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getName())
                    + " active=" + org.lwjgl.opengl.Display.isActive() + "]"));
            phase = P_STOP;
        }
    }

    private static void stepWorldSettle(Minecraft mc) {
        if (++frames < WORLD_SETTLE) return;
        applyWorldSetup(mc);                // time/weather/position/loadout (own try/catch inside)
        frames = 0; phase = P_WORLD;
    }

    private static void stepWorld(Minecraft mc) {
        // Keep the world unobstructed: if a screen slipped in (focus change), dismiss it and give the
        // clean frame one more tick before counting toward the shot.
        if (mc.currentScreen != null) { mc.displayGuiScreen(null); frames = 0; return; }
        // Suppress the transient chat/toasts the scripted loadout may trigger so the over-world
        // reference frames stay clean and deterministic.
        try { mc.ingameGUI.getChatGUI().clearChatMessages(); } catch (Throwable ignored) {}
        // The held-item name is a ~2 s popup that vanilla's remainingHighlightTicks counts down; the
        // reference shot catches it fresh, but on a lower/variable framerate it can fade before this
        // shot. Keep it pinned so the over-world reference frame deterministically shows "鑽石劍",
        // exactly like the 1.20.1 reference. Set here (render-tail) so it survives into the next
        // frame's HUD pass (GlassItemNameHandler reads it there).
        keepItemName(mc);
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
        if (++frames < CONFIG_FRAMES) return;
        capture(mc, "config-world.png");
        close(mc);
        open(mc, new GuiInventory(mc.thePlayer), "open inventory");
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
        if (++frames < INV_FRAMES) return;
        capture(mc, "inventory.png");
        try {
            GuiScreen parent = mc.currentScreen;   // reuse the still-shown inventory as parent
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
        // BATCH-A extra scenes: creative (fused tabs + glass scrollbar), then book.
        if (openCreative(mc)) {
            frames = 0; phase = P_WAIT_CREATIVE;
        } else {
            skip("open creative", new IllegalStateException("creative did not open"));
            phase = P_STOP;
        }
    }

    // ---- BATCH-A scene: creative inventory (B fused tabs + C glass scrollbar) ----

    /** Put the player in creative (server + client), then open the creative screen. */
    private static boolean openCreative(Minecraft mc) {
        try {
            IntegratedServer server = mc.getIntegratedServer();
            if (server != null && !server.getConfigurationManager().playerEntityList.isEmpty()) {
                EntityPlayerMP sp = server.getConfigurationManager().playerEntityList.get(0);
                sp.setGameType(WorldSettings.GameType.CREATIVE);
            }
            // Mirror on the client controller so GuiContainerCreative.updateScreen does not bounce
            // us back to the survival inventory before the server sync arrives (dev-only reflection).
            setClientCreative(mc);
            mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiContainerCreative(mc.thePlayer));
            return true;
        } catch (Throwable t) {
            skip("open creative", t);
            return false;
        }
    }

    private static boolean gtResolved;
    private static java.lang.reflect.Field F_GAMETYPE;
    private static void setClientCreative(Minecraft mc) {
        try {
            if (!gtResolved) {
                gtResolved = true;
                F_GAMETYPE = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(
                        net.minecraft.client.multiplayer.PlayerControllerMP.class,
                        "currentGameType", "field_78779_k");
            }
            if (F_GAMETYPE != null && mc.playerController != null) {
                F_GAMETYPE.set(mc.playerController, WorldSettings.GameType.CREATIVE);
            }
        } catch (Throwable ignored) {}
    }

    private static void stepWaitCreative(Minecraft mc) {
        if (mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainerCreative) {
            frames = 0; phase = P_CREATIVE;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait creative screen", new IllegalStateException("creative never opened"));
            frames = 0; phase = P_CREATIVE;
        }
    }

    private static void stepCreative(Minecraft mc) {
        // Keep re-asserting creative so updateScreen does not bounce us to the survival inventory.
        setClientCreative(mc);
        if (++frames < INV_FRAMES) return;
        capture(mc, "creative.png");
        // Feature D: run the sub-pixel glide filmstrip + click-correctness sweep on
        // the same creative screen before moving on to the book.
        frames = 0; phase = P_CREATIVE_GLIDE;
    }

    // ---- Feature D scene: silky sub-pixel grid glide (filmstrip) + click sweep ----

    private static void stepCreativeGlide(Minecraft mc) {
        setClientCreative(mc);
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainerCreative)) {
            // creative closed unexpectedly; move on to the book.
            gotoBook(mc); return;
        }
        net.minecraft.client.gui.inventory.GuiContainerCreative sc =
                (net.minecraft.client.gui.inventory.GuiContainerCreative) mc.currentScreen;
        java.util.List<net.minecraft.item.ItemStack> list =
                dev.s1mp1e.glass.hook.GlassCreativeGlide.items(sc);
        int rc = dev.s1mp1e.glass.hook.GlassCreativeGlide.rowCount(list);
        if (rc <= 0) {
            System.out.println("[S1mp1e][DevShot][D] current tab is not scrollable (rc=" + rc
                    + "); skipping glide filmstrip");
            frames = 0; phase = P_TABS; return;
        }

        int f = frames++;
        // Timeline (one action per rendered frame):
        //   0..7   settle the eased thumb at scroll 0
        //   8      at-rest click test, then JUMP scroll to 0.85 (starts a long glide)
        //   9..16  capture glide_0..glide_7 (the sub-pixel filmstrip)
        //   17     re-settle to scroll 0
        //   18..25 settle
        //   26     JUMP to 0.85 again
        //   28     mid-glide click test (verifies snap-to-target)
        //   >=31   open the book
        if (f <= 7) {
            setCreativeScroll(sc, 0f);
        } else if (f == 8) {
            clickGridAndVerify(mc, sc, "at-rest");
            setCreativeScroll(sc, 0.85f);
        } else if (f >= 9 && f <= 16) {
            capture(mc, "glide_" + (f - 9) + ".png");
        } else if (f == 17) {
            setCreativeScroll(sc, 0f);
        } else if (f >= 18 && f <= 25) {
            setCreativeScroll(sc, 0f);
        } else if (f == 26) {
            setCreativeScroll(sc, 0.85f);
        } else if (f == 28) {
            System.out.println("[S1mp1e][DevShot][D] gliding="
                    + dev.s1mp1e.glass.hook.GlassCreativeGlide.sliding()
                    + " base=" + dev.s1mp1e.glass.hook.GlassCreativeGlide.glideBase()
                    + " frac=" + dev.s1mp1e.glass.hook.GlassCreativeGlide.glideFracPx());
            capture(mc, "glide_midclick_before.png");
            clickGridAndVerify(mc, sc, "mid-glide");
        } else if (f == 29) {
            System.out.println("[S1mp1e][DevShot][D] after mid-glide click gliding="
                    + dev.s1mp1e.glass.hook.GlassCreativeGlide.sliding() + " (expect false = snapped)");
            capture(mc, "glide_midclick_after.png");
        } else if (f >= 31) {
            frames = 0; phase = P_TABS;
        }
    }

    // ---- Feature B scene: creative tab pill slide (same row) + cross-fade (row switch) ----
    private static boolean tabsResolved;
    private static java.lang.reflect.Method M_SETTAB;
    private static int s_tabOrigIdx = -1;

    /** Reflect GuiContainerCreative.setCurrentCreativeTab(CreativeTabs) (MCP/SRG tolerant). */
    private static void selectTab(net.minecraft.client.gui.inventory.GuiContainerCreative sc,
                                  net.minecraft.creativetab.CreativeTabs tab) {
        if (tab == null) return;
        try {
            if (!tabsResolved) {
                tabsResolved = true;
                M_SETTAB = net.minecraftforge.fml.relauncher.ReflectionHelper.findMethod(
                        net.minecraft.client.gui.inventory.GuiContainerCreative.class, sc,
                        new String[]{"setCurrentCreativeTab", "func_147050_b"},
                        net.minecraft.creativetab.CreativeTabs.class);
            }
            if (M_SETTAB != null) M_SETTAB.invoke(sc, tab);
        } catch (Throwable t) { skip("selectTab", t); }
    }

    /** Non-search tab in the requested row (first row = top), skipping the search tab. */
    private static net.minecraft.creativetab.CreativeTabs pickTab(boolean firstRow, int nth) {
        int seen = 0;
        for (net.minecraft.creativetab.CreativeTabs t : net.minecraft.creativetab.CreativeTabs.creativeTabArray) {
            if (t == null) continue;
            try { if (t.hasSearchBar()) continue; } catch (Throwable ignored) {}
            if (t.isTabInFirstRow() != firstRow) continue;
            if (seen++ == nth) return t;
        }
        // fallback: last matching row tab
        net.minecraft.creativetab.CreativeTabs last = null;
        for (net.minecraft.creativetab.CreativeTabs t : net.minecraft.creativetab.CreativeTabs.creativeTabArray) {
            if (t == null) continue;
            try { if (t.hasSearchBar()) continue; } catch (Throwable ignored) {}
            if (t.isTabInFirstRow() == firstRow) last = t;
        }
        return last;
    }

    private static void stepTabs(Minecraft mc) {
        setClientCreative(mc);
        if (!(mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainerCreative)) {
            gotoBook(mc); return;
        }
        net.minecraft.client.gui.inventory.GuiContainerCreative sc =
                (net.minecraft.client.gui.inventory.GuiContainerCreative) mc.currentScreen;

        net.minecraft.creativetab.CreativeTabs topA = pickTab(true, 0);   // top row, first cell
        net.minecraft.creativetab.CreativeTabs topB = pickTab(true, 3);   // top row, 4th cell (clear slide)
        net.minecraft.creativetab.CreativeTabs bottom = pickTab(false, 2);// bottom row, 3rd cell
        if (topA == null || topB == null || bottom == null) {
            skip("tabs scene", new IllegalStateException("could not pick two-row tabs"));
            gotoBook(mc); return;
        }

        int f = frames++;
        // Timeline (one action per rendered frame):
        //   0     snapshot original tab, select topA (settle the pill there)
        //   1..4  settle
        //   5     select topB  -> SAME-ROW slide begins (springs 55/30)
        //   6..12 capture tabs_slide_0..6 (pill sliding across the row)
        //   13    select bottom -> ROW-SWITCH cross-fade (ghost out 150 / new in 100)
        //   14..20 capture tabs_cross_0..6 (old pill fades where it stood, new fades in)
        //   >=22  restore original tab, go to book
        if (f == 0) {
            try { s_tabOrigIdx = sc.getSelectedTabIndex(); } catch (Throwable ignored) { s_tabOrigIdx = -1; }
            selectTab(sc, topA);
        } else if (f == 5) {
            capture(mc, "tabs_slide_start.png");     // pill parked on topA (pre-slide)
            selectTab(sc, topB);
        } else if (f >= 6 && f <= 12) {
            capture(mc, "tabs_slide_" + (f - 6) + ".png");
        } else if (f == 13) {
            selectTab(sc, bottom);
        } else if (f >= 14 && f <= 20) {
            capture(mc, "tabs_cross_" + (f - 14) + ".png");
        } else if (f >= 22) {
            if (s_tabOrigIdx >= 0 && s_tabOrigIdx < net.minecraft.creativetab.CreativeTabs.creativeTabArray.length) {
                selectTab(sc, net.minecraft.creativetab.CreativeTabs.creativeTabArray[s_tabOrigIdx]);
            }
            gotoBook(mc);
        }
    }

    private static void gotoBook(Minecraft mc) {
        if (openBook(mc)) {
            frames = 0; phase = P_WAIT_BOOK;
        } else {
            skip("open book", new IllegalStateException("book did not open"));
            phase = P_STOP;
        }
    }

    // Reflection into GuiContainerCreative.currentScroll + ContainerCreative.scrollTo.
    private static boolean csResolved;
    private static java.lang.reflect.Field F_CURSCROLL;
    private static java.lang.reflect.Method M_SCROLLTO;

    /** Set the vanilla logical scroll and re-fill the grid slots (like the wheel). */
    private static void setCreativeScroll(net.minecraft.client.gui.inventory.GuiContainerCreative sc, float v) {
        try {
            if (!csResolved) {
                csResolved = true;
                F_CURSCROLL = findField(sc.getClass(), "field_147067_x", "currentScroll");
                Object container = sc.inventorySlots;
                M_SCROLLTO = findMethod(container.getClass(),
                        new String[] { "func_148329_a", "scrollTo" }, float.class);
            }
            if (F_CURSCROLL != null) F_CURSCROLL.setFloat(sc, v);
            if (M_SCROLLTO != null) M_SCROLLTO.invoke(sc.inventorySlots, Float.valueOf(v));
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot][D] setCreativeScroll failed: " + t);
        }
    }

    /**
     * Click the item at grid (col 3, window row 2) and check the picked-up stack
     * matches the item vanilla's ROW-ALIGNED logical slot holds there — the item
     * the user sees drawn under the cursor once the glide snaps. Logs PASS/FAIL.
     */
    private static void clickGridAndVerify(Minecraft mc,
            net.minecraft.client.gui.inventory.GuiContainerCreative sc, String label) {
        try {
            int[] rect = dev.s1mp1e.glass.hook.GlassContainerHandler.panelRect(sc);
            if (rect == null) { System.out.println("[S1mp1e][DevShot][D] " + label + " click: no geometry"); return; }
            int gl = rect[0], gt = rect[1];
            int col = 3, winRow = 2;
            int mx = gl + 9 + col * 18 + 8;
            int my = gt + 18 + winRow * 18 + 8;

            java.util.List<net.minecraft.item.ItemStack> list =
                    dev.s1mp1e.glass.hook.GlassCreativeGlide.items(sc);
            int rc = dev.s1mp1e.glass.hook.GlassCreativeGlide.rowCount(list);
            float cur = 0f;
            try { if (F_CURSCROLL == null) F_CURSCROLL = findField(sc.getClass(), "field_147067_x", "currentScroll");
                  if (F_CURSCROLL != null) cur = F_CURSCROLL.getFloat(sc); } catch (Throwable ignored) {}
            int logicalRow = rc <= 0 ? 0 : Math.round(cur * rc);
            int idx = (logicalRow + winRow) * 9 + col;
            net.minecraft.item.ItemStack expect =
                    (list != null && idx >= 0 && idx < list.size()) ? list.get(idx) : null;

            // clear the cursor, then click.
            mc.thePlayer.inventory.setItemStack(null);
            if (M_CLICK == null) {
                M_CLICK = findMethod(net.minecraft.client.gui.inventory.GuiContainer.class,
                        new String[] { "func_73864_a", "mouseClicked" }, int.class, int.class, int.class);
            }
            if (M_CLICK != null) M_CLICK.invoke(sc, Integer.valueOf(mx), Integer.valueOf(my), Integer.valueOf(0));
            net.minecraft.item.ItemStack held = mc.thePlayer.inventory.getItemStack();

            String en = expect == null ? "null" : expect.getUnlocalizedName();
            String hn = held == null ? "null" : held.getUnlocalizedName();
            boolean ok = (expect == null && held == null)
                    || (expect != null && held != null && held.getItem() == expect.getItem());
            System.out.println("[S1mp1e][DevShot][D] click " + label + " @row=" + logicalRow
                    + " expect=" + en + " held=" + hn + " -> " + (ok ? "PASS" : "FAIL"));
            mc.thePlayer.inventory.setItemStack(null);
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot][D] click " + label + " failed: " + t);
        }
    }

    private static java.lang.reflect.Method M_CLICK;

    private static java.lang.reflect.Method findMethod(Class<?> cls, String[] names, Class<?>... params) {
        for (int i = 0; i < names.length; i++) {
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(names[i], params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String srg, String mcp) {
        String[] names = { srg, mcp };
        for (int i = 0; i < names.length; i++) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    // ---- BATCH-A scene: book (A glass page + light parchment scrim) ----

    private static boolean openBook(Minecraft mc) {
        try {
            net.minecraft.item.ItemStack book = new ItemStack(Items.written_book);
            net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
            net.minecraft.nbt.NBTTagList pages = new net.minecraft.nbt.NBTTagList();
            pages.appendTag(new net.minecraft.nbt.NBTTagString(
                    "S1mp1e liquid glass\n\nThe page is a frosted glass plate; the ink stays dark on a light parchment scrim."));
            tag.setTag("pages", pages);
            tag.setString("title", "S1mp1e");
            tag.setString("author", "DevShot");
            book.setTagCompound(tag);
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenBook(mc.thePlayer, book, false));
            return true;
        } catch (Throwable t) {
            skip("open book", t);
            return false;
        }
    }

    private static void stepWaitBook(Minecraft mc) {
        if (mc.currentScreen instanceof net.minecraft.client.gui.GuiScreenBook) {
            frames = 0; phase = P_BOOK;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait book screen", new IllegalStateException("book never opened"));
            frames = 0; phase = P_BOOK;
        }
    }

    private static void stepBook(Minecraft mc) {
        if (++frames < INV_FRAMES) return;
        capture(mc, "book.png");
        close(mc);
        // Batch B (G/H): continue with the in-world HUD overlays and world modules before quitting.
        frames = 0; phase = P_HUD;
    }

    // ================= Batch B (G) HUD overlays =================

    /** Frames of settle after injecting the transient HUD state, then the hold before capture. */
    private static final int HUD_SETTLE = 6;
    private static final int HUD_HOLD   = 30;
    private static boolean hudInjected;

    /**
     * G1 chat panel + G2 tab list + G3 boss bar + G5 action bar, all in one over-world frame. The
     * state (chat lines / boss / overlay message / tab-list key) is (re)asserted every frame so its
     * vanilla timers do not expire before the capture; everything is torn down before the next scene.
     */
    private static void stepHud(Minecraft mc) {
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        try {
            if (!hudInjected) {
                hudInjected = true;
                net.minecraft.client.gui.GuiNewChat chat = mc.ingameGUI.getChatGUI();
                chat.clearChatMessages();
                chat.printChatMessage(new ChatComponentText("§bS1mp1e §f液態玻璃 HUD"));
                chat.printChatMessage(new ChatComponentText("§7[系統] 聊天面板是一塊磨砂玻璃"));
                chat.printChatMessage(new ChatComponentText("§a<Steve> 這個玻璃效果不錯"));
                chat.printChatMessage(new ChatComponentText("§e<Alex> 嗯，邊緣折射很漂亮"));
                chat.printChatMessage(new ChatComponentText("§f<You> 每行深色底已換成面板"));
                setupTabObjective(mc);
            }
            // 1.8.9 has a single boss bar (no multi-bar / colour system).
            BossStatus.bossName = "§d下界之龍 Ender Dragon";
            BossStatus.healthScale = 0.62f;
            BossStatus.statusBarTime = 120;
            // Action bar / overlay message — the record path is 1.8.9's only overlay-message channel.
            mc.ingameGUI.setRecordPlayingMessage("§bC418 - Sweden");
            // Tab list: hold the player-list key (the display-slot objective set above satisfies the
            // single-player render gate).
            net.minecraft.client.settings.KeyBinding.setKeyBindState(
                    mc.gameSettings.keyBindPlayerList.getKeyCode(), true);
        } catch (Throwable t) {
            skip("hud scene setup", t);
        }
        if (++frames >= HUD_SETTLE + HUD_HOLD) {
            capture(mc, "hud.png");
            try {
                net.minecraft.client.settings.KeyBinding.setKeyBindState(
                        mc.gameSettings.keyBindPlayerList.getKeyCode(), false);
            } catch (Throwable ignored) {}
            open(mc, new GuiChat(), "open chat input");
            frames = 0; phase = P_HUD_INPUT;
        }
    }

    /** G1 chat input: the same chat panel with the open glass input bar (chat is focused -> full opacity). */
    private static void stepHudInput(Minecraft mc) {
        // Keep the boss + overlay message alive so the input shot still shows the other overlays.
        try {
            BossStatus.statusBarTime = 120;
            mc.ingameGUI.setRecordPlayingMessage("§bC418 - Sweden");
        } catch (Throwable ignored) {}
        if (++frames >= HUD_SETTLE + HUD_HOLD) {
            capture(mc, "hud-input.png");
            close(mc);
            // Tear down every transient HUD state before the world-module scene.
            try { mc.ingameGUI.getChatGUI().clearChatMessages(); } catch (Throwable ignored) {}
            BossStatus.bossName = null; BossStatus.statusBarTime = 0;
            clearTabObjective(mc);
            frames = 0; phase = P_MODULES;
        }
    }

    /** Add a client-side display-slot-0 objective so the single-player tab list renders. */
    private static void setupTabObjective(Minecraft mc) {
        try {
            Scoreboard sb = mc.theWorld.getScoreboard();
            ScoreObjective obj = sb.getObjective("s1mp1e");
            if (obj == null) obj = sb.addScoreObjective("s1mp1e", IScoreObjectiveCriteria.DUMMY);
            sb.setObjectiveInDisplaySlot(0, obj);   // slot 0 = the tab list
        } catch (Throwable t) {
            skip("tab list objective", t);
        }
    }

    private static void clearTabObjective(Minecraft mc) {
        try {
            Scoreboard sb = mc.theWorld.getScoreboard();
            sb.setObjectiveInDisplaySlot(0, null);
            ScoreObjective obj = sb.getObjective("s1mp1e");
            if (obj != null) sb.removeObjective(obj);
        } catch (Throwable ignored) {}
    }

    // ================= Batch B (H) world modules =================

    // Snapshots so the sweep restores every value it touches (nothing is written to disk).
    private static boolean s_olEnabled, s_olChroma, s_olFill, s_chromaEnabled, s_coordsEnabled;
    private static int     s_olColor, s_olFillC;
    private static double  s_olWidth, s_chromaSpeed, s_chromaSat, s_chromaWave;
    private static boolean s_chromaAcc;

    /**
     * H1 Block Outline (custom colour + width 8, then chroma, then translucent fill) and H2 Chroma HUD
     * (the coords HUD text cycling a diagonal rainbow). Every module value is snapshotted on entry and
     * restored on exit.
     */
    private static void stepModules(Minecraft mc) {
        Module olM = ModuleManager.byName("BlockOutline");
        Module chM = ModuleManager.byName("ChromaHud");
        if (!(olM instanceof BlockOutlineModule) || !(chM instanceof ChromaHudModule)) {
            skip("modules scene", new IllegalStateException("H modules not registered"));
            frames = 0; phase = P_NAMETAG; return;
        }
        BlockOutlineModule ol = (BlockOutlineModule) olM;
        ChromaHudModule ch = (ChromaHudModule) chM;
        Module coords = ModuleManager.byName("CoordsHUD");

        int f = frames++;
        if (f == 0) {
            s_olEnabled = ol.enabled; s_olColor = ol.color.colorValue; s_olWidth = ol.width.doubleValue;
            s_olChroma = ol.chroma.boolValue; s_olFill = ol.fill.boolValue; s_olFillC = ol.fillColour.colorValue;
            s_chromaEnabled = ch.enabled; s_chromaSpeed = ch.speed.doubleValue; s_chromaSat = ch.saturation.doubleValue;
            s_chromaWave = ch.wave.doubleValue; s_chromaAcc = ch.accents.boolValue;
            s_coordsEnabled = coords != null && coords.enabled;
            // Clear the leftover HUD overlays from the chat scene so the module shots are clean.
            try {
                BossStatus.bossName = null; BossStatus.statusBarTime = 0;
                mc.ingameGUI.getChatGUI().clearChatMessages();
                mc.ingameGUI.setRecordPlaying("", false);
            } catch (Throwable ignored) {}
            lookDownAtBlock(mc);
            ol.enabled = true; ol.color.colorValue = 0xCC22E0FF; ol.width.doubleValue = 8.0;
            ol.chroma.boolValue = false; ol.fill.boolValue = false;
        } else if (f < 8) {
            lookDownAtBlock(mc);                 // let objectMouseOver settle onto the block
        } else if (f == 8) {
            capture(mc, "outline.png");          // custom colour + width 8
            ol.chroma.boolValue = true;
        } else if (f == 12) {
            capture(mc, "outline-chroma-a.png");
        } else if (f == 18) {
            capture(mc, "outline-chroma-b.png"); // hue advanced two frames on
            ol.chroma.boolValue = false; ol.fill.boolValue = true; ol.fillColour.colorValue = 0x3322E0FF;
        } else if (f == 22) {
            capture(mc, "outline-fill.png");     // translucent depth-tested fill of the targeted block
            ol.enabled = false;
            ch.enabled = true; ch.speed.doubleValue = 3.0; ch.saturation.doubleValue = 0.85;
            ch.wave.doubleValue = 0.6; ch.accents.boolValue = true;
            if (coords != null) coords.enabled = true;
        } else if (f == 30) {
            capture(mc, "chroma-hud-a.png");
        } else if (f >= 34) {
            capture(mc, "chroma-hud-b.png");     // diagonal wave has advanced
            // restore
            ol.enabled = s_olEnabled; ol.color.colorValue = s_olColor; ol.width.doubleValue = s_olWidth;
            ol.chroma.boolValue = s_olChroma; ol.fill.boolValue = s_olFill; ol.fillColour.colorValue = s_olFillC;
            ch.enabled = s_chromaEnabled; ch.speed.doubleValue = s_chromaSpeed; ch.saturation.doubleValue = s_chromaSat;
            ch.wave.doubleValue = s_chromaWave; ch.accents.boolValue = s_chromaAcc;
            if (coords != null) coords.enabled = s_coordsEnabled;
            frames = 0; phase = P_NAMETAG;
        }
    }

    /** Aim the client player steeply down so {@code objectMouseOver} lands on a nearby ground block. */
    private static void lookDownAtBlock(Minecraft mc) {
        try {
            EntityPlayerSP cp = mc.thePlayer;
            if (cp == null) return;
            cp.rotationPitch = 78f; cp.prevRotationPitch = 78f;
            cp.rotationYaw = 0f; cp.prevRotationYaw = 0f; cp.rotationYawHead = 0f;
        } catch (Throwable ignored) {}
    }

    // ================= Batch B (G6) name tag =================

    private static EntityArmorStand nameStand;

    /** G6 name tag: spawn a named armor stand in front of the player and look at it (best-effort). */
    private static void stepNameTag(Minecraft mc) {
        int f = frames++;
        if (f == 0) {
            try {
                EntityPlayerSP cp = mc.thePlayer;
                IntegratedServer server = mc.getIntegratedServer();
                // yaw 0 faces +Z in Minecraft (look = -sin(yaw), cos(yaw)); aim slightly up so the
                // name-tag plate lands near the crosshair.
                cp.rotationPitch = -6f; cp.prevRotationPitch = -6f;
                cp.rotationYaw = 0f; cp.prevRotationYaw = 0f; cp.rotationYawHead = 0f;
                if (server != null) {
                    WorldServer ws = server.worldServers[0];
                    EntityArmorStand stand = new EntityArmorStand(ws, cp.posX, cp.posY + 0.4, cp.posZ + 2.5);
                    stand.setCustomNameTag("§bS1mp1e 名牌");
                    stand.setAlwaysRenderNameTag(true);
                    ws.spawnEntityInWorld(stand);
                    nameStand = stand;
                } else {
                    skip("nametag entity", new IllegalStateException("no integrated server"));
                }
            } catch (Throwable t) {
                skip("spawn nametag entity", t);
            }
        } else if (f >= 55) {
            capture(mc, "nametag.png");
            try { if (nameStand != null) { nameStand.setDead(); nameStand = null; } } catch (Throwable ignored) {}
            phase = P_STOP;
        }
    }

    private static void stepStop(Minecraft mc) {
        if (auditFinalPending) {
            auditFinalPending = false;
            logAudit("COREMOD AUDIT (final, all screens exercised)");
        }
        // 絕不在世界裡直接關遊戲：先照原版暫停選單「儲存並離開」的做法離開世界（Forge 的 loadWorld(null) 會等整合
        // 伺服器存完檔、停下來才返回），回到標題畫面，等幾幀之後才關。之前直接 shutdown，伺服器關閉流程偶爾在視窗
        // 已經關掉後才跑完，留下一個「Display not created」的伺服器執行緒例外。
        if (mc.theWorld != null) {
            System.out.println("[S1mp1e][DevShot] done, leaving the world (save + quit to title) before quitting.");
            try {
                mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
                mc.displayGuiScreen(new GuiMainMenu());
            } catch (Throwable t) {
                skip("leave world", t);
            }
            frames = 0;
            return;                       // 下一幀再回到這裡；那時 theWorld 已經是 null
        }
        if (++frames < 20) return;        // 在標題畫面停一下再關
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
            ISaveFormat sf = mc.getSaveLoader();
            sf.flushCache();
            sf.deleteWorldDirectory("devshot");
        } catch (Throwable t) {
            skip("delete previous devshot save", t);
        }
        try {
            // superflat, survival, seed 12345, no structures, not hardcore, cheats on.
            WorldSettings ws = new WorldSettings(
                    12345L, WorldSettings.GameType.SURVIVAL, false /*structures*/, false /*hardcore*/, WorldType.FLAT);
            ws.enableCommands();   // cheats
            mc.launchIntegratedServer("devshot", "devshot", ws);   // blocks until the server is up, then connects
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /** Time / weather / difficulty / camera angle / scripted loadout. Each sub-part is guarded. */
    private static void applyWorldSetup(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        // difficulty PEACEFUL (server-authoritative; also clears hostile mobs from the flat plain)
        try {
            server.setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL);
        } catch (Throwable t) {
            skip("set difficulty peaceful", t);
        }
        // time + weather (server-authoritative)
        try {
            WorldServer ow = server.worldServers[0];
            ow.setWorldTime(6000L);
            WorldInfo wi = ow.getWorldInfo();
            wi.setRaining(false);
            wi.setThundering(false);
            wi.setRainTime(0);
            wi.setThunderTime(0);
            wi.setCleanWeatherTime(1_000_000);
        } catch (Throwable t) {
            skip("set time/weather", t);
        }
        // loadout on the server player (auto-syncs to the client within a couple of ticks)
        try {
            EntityPlayerMP sp = server.getConfigurationManager().playerEntityList.isEmpty()
                    ? null : server.getConfigurationManager().playerEntityList.get(0);
            if (sp != null) {
                sp.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
                sp.inventory.setInventorySlotContents(1, new ItemStack(Items.cooked_beef, 32));
                sp.inventory.setInventorySlotContents(2, new ItemStack(Blocks.stone, 64));
                // armorInventory: 0=boots 1=leggings 2=chestplate 3=helmet
                sp.inventory.armorInventory[3] = new ItemStack(Items.iron_helmet);
                sp.inventory.armorInventory[2] = new ItemStack(Items.iron_chestplate);
                sp.inventory.armorInventory[1] = new ItemStack(Items.iron_leggings);
                sp.inventory.armorInventory[0] = new ItemStack(Items.iron_boots);
                sp.inventory.currentItem = 0;
                // A varied effect set so the inventory effect strip (F) shows several
                // rows (beneficial + one harmful), no world particles (clean frame).
                sp.addPotionEffect(new PotionEffect(Potion.moveSpeed.getId(),   1200, 0, false, false));
                sp.addPotionEffect(new PotionEffect(Potion.regeneration.getId(),1200, 1, false, false));
                sp.addPotionEffect(new PotionEffect(Potion.resistance.getId(),  1200, 0, false, false));
                sp.addPotionEffect(new PotionEffect(Potion.digSpeed.getId(),    1200, 2, false, false));
                sp.inventory.markDirty();
                sp.inventoryContainer.detectAndSendChanges();
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the flat plain).
                sp.setPositionAndRotation(sp.posX, sp.posY, sp.posZ, 0f, 15f);
                sp.playerNetServerHandler.setPlayerLocation(sp.posX, sp.posY, sp.posZ, 0f, 15f);
            } else {
                skip("give loadout", new IllegalStateException("no server player yet"));
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
        // 1.8.9 has no off hand: log the skipped off-hand/shield step explicitly.
        skip("off-hand shield (no off hand on 1.8.9)", new UnsupportedOperationException("version has no off hand"));
        // mirror the loadout + camera on the client player so the very next frame already shows it
        try {
            EntityPlayerSP cp = mc.thePlayer;
            cp.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
            cp.inventory.setInventorySlotContents(1, new ItemStack(Items.cooked_beef, 32));
            cp.inventory.setInventorySlotContents(2, new ItemStack(Blocks.stone, 64));
            cp.inventory.armorInventory[3] = new ItemStack(Items.iron_helmet);
            cp.inventory.armorInventory[2] = new ItemStack(Items.iron_chestplate);
            cp.inventory.armorInventory[1] = new ItemStack(Items.iron_leggings);
            cp.inventory.armorInventory[0] = new ItemStack(Items.iron_boots);
            cp.inventory.currentItem = 0;                     // hold the sword
            cp.addPotionEffect(new PotionEffect(Potion.moveSpeed.getId(),   1200, 0, false, false));
            cp.addPotionEffect(new PotionEffect(Potion.regeneration.getId(),1200, 1, false, false));
            cp.addPotionEffect(new PotionEffect(Potion.resistance.getId(),  1200, 0, false, false));
            cp.addPotionEffect(new PotionEffect(Potion.digSpeed.getId(),    1200, 2, false, false));
            cp.rotationYaw = 0f;  cp.prevRotationYaw = 0f;  cp.rotationYawHead = 0f;
            cp.rotationPitch = 15f; cp.prevRotationPitch = 15f;
        } catch (Throwable t) {
            skip("mirror loadout on client", t);
        }
    }

    /** Pin vanilla's held-item-name timer so the transient "鑽石劍" popup is present at capture,
     *  matching the reference. Dev-only (S1MP1E_SHOT), so reflecting the two protected GuiIngame
     *  fields is acceptable; both MCP (dev) and SRG (production) names are tried. */
    private static boolean hlResolved;
    private static java.lang.reflect.Field F_HL_TICKS, F_HL_STACK;
    private static void keepItemName(Minecraft mc) {
        try {
            if (!hlResolved) {
                hlResolved = true;
                F_HL_TICKS = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(
                        net.minecraft.client.gui.GuiIngame.class, "remainingHighlightTicks", "field_92017_k");
                F_HL_STACK = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(
                        net.minecraft.client.gui.GuiIngame.class, "highlightingItemStack", "field_92016_l");
            }
            if (F_HL_TICKS == null || F_HL_STACK == null || mc.thePlayer == null || mc.ingameGUI == null) return;
            ItemStack held = mc.thePlayer.inventory.getCurrentItem();
            if (held == null) return;
            F_HL_STACK.set(mc.ingameGUI, held);
            F_HL_TICKS.setInt(mc.ingameGUI, 40);
        } catch (Throwable ignored) {}
    }

    // ---- coremod audit -----------------------------------------------------

    /** 1.8.9 is a pure Forge ASM coremod (no Mixin) -> log every coremod patch site. */
    private static void runAudit() {
        logAudit("COREMOD AUDIT (first tick)");
        auditFinalPending = true;   // re-log after the run has loaded every target class
    }

    private static void logAudit(String header) {
        try {
            java.util.List<String> sites = dev.s1mp1e.glass.asm.S1mp1eTransformer.auditSnapshot();
            System.out.println("[S1mp1e] " + header + " — " + sites.size() + " patch site(s):");
            for (String s : sites) System.out.println("[S1mp1e]   " + s);
            if (dev.s1mp1e.glass.asm.S1mp1eTransformer.auditFailed()) {
                System.out.println("[S1mp1e] COREMOD AUDIT FAILED (a coremod patch site did not apply)");
            } else {
                System.out.println("[S1mp1e] MIXIN AUDIT COMPLETE");   // same success token the Mixin lines use
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e] COREMOD AUDIT ERROR: " + t);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static void open(Minecraft mc, GuiScreen screen, String what) {
        try {
            mc.displayGuiScreen(screen);
        } catch (Throwable t) {
            skip(what, t);
        }
    }

    private static void close(Minecraft mc) {
        try {
            mc.displayGuiScreen(null);
        } catch (Throwable ignored) {}
    }

    private static void skip(String step, Throwable t) {
        System.out.println("[S1mp1e] DevShot skipped " + step + ": " + t);
    }

    /**
     * Capture the current framebuffer to {@code outDir/name}, overwriting. Uses the game's own
     * framebuffer PNG writer ({@link net.minecraft.util.ScreenShotHelper}), which always writes into
     * {@code <gameDir>/screenshots}; the file is then relocated to the shot folder.
     */
    /** 給 {@link DevShotScenes} 用的截圖入口。 */
    static void captureExternal(Minecraft mc, String name) { capture(mc, name); }

    private static void capture(Minecraft mc, String name) {
        try {
            net.minecraft.util.ScreenShotHelper.saveScreenshot(
                    mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File produced = new File(new File(mc.mcDataDir, "screenshots"), name);
            File dest = new File(outDir, name);
            if (produced.exists()) {
                java.nio.file.Files.copy(produced.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                produced.delete();
                System.out.println("[S1mp1e][DevShot] wrote " + dest.getAbsolutePath());
            } else {
                System.out.println("[S1mp1e][DevShot] capture produced no file for " + name);
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] capture failed for " + name + ": " + t);
        }
    }
}
