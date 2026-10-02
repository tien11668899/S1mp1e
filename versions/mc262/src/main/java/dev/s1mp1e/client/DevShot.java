package dev.s1mp1e.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.client.module.BlockOutlineModule;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.ClientSideMerchant;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * DevShot v2 — deterministic screenshot harness for cross-version visual comparison (26.2 / Fabric / mojmap port
 * of {@code versions/mc1201}'s DevShot).
 *
 * <p><b>Completely inert unless the environment variable {@code S1MP1E_SHOT} names an output folder</b>
 * (the shot pipeline) or {@code S1MP1E_AUDIT} is set (the one-shot mixin audit). It ships in the jar but does
 * nothing at all when both are unset — a normal game run never touches any of this. Environment variables reach
 * the forked game JVM of {@code runClient}; a {@code -D} system property on the gradle command line does not,
 * which is why this reads {@link System#getenv}.
 *
 * <p>When the shot pipeline is active, so that every S1mp1e version renders under identical conditions, on the
 * first rendered frame it forces the window to 1280x720, GUI scale 2 and the language to {@code zh_tw}, then
 * walks a fixed script and writes seven PNGs with the game's own framebuffer writer
 * ({@link Screenshot#takeScreenshot} + {@link NativeImage#writeToFile}) straight into the folder
 * {@code S1MP1E_SHOT} names, overwriting: {@code title.png}, {@code config.png}, {@code world.png},
 * {@code config-world.png}, {@code inventory.png}, {@code options.png}; then leaves the world and quits.
 *
 * <p>Identical script and framing to every other S1mp1e version so the shots are directly comparable. Every step
 * is wrapped so a failure only skips that step ("{@code [S1mp1e] DevShot skipped <step>: <reason>}") and the
 * script moves on, and a global 240 s watchdog quits the game so a stuck wait can never hang the run.
 *
 * <p><b>26.2 specifics.</b> (1) The screenshot writer is now an async GPU read-back
 * ({@code takeScreenshot(RenderTarget, Consumer)}), so its file is written a frame or two after the capture is
 * requested; a drain phase renders extra frames after the last shot so every pending read-back flushes before
 * quit. (2) There is no {@code scheduleStop()}; a clean leave+quit is a volatile {@code running=false} on
 * {@link Minecraft} (the run loop then exits and MC's normal shutdown stops the integrated server and saves the
 * world) — set by reflection because the field is private (dev-only; DevShot never runs in a launcher build).
 * (3) Time moved to the world-clock system: {@code server.clockManager().setTotalTicks(overworldClock, 6000)}.
 *
 * <p>Driven from a single render-frame hook ({@code DevShotMixin} at {@code Minecraft.renderFrame} TAIL).
 */
public final class DevShot {
    private DevShot() {}

    private static final int TITLE_FRAMES   = 60;
    private static final int CONFIG_FRAMES  = 90;
    private static final int WORLD_SETTLE   = 100;  // frames the world renders before the loadout
    private static final int WORLD_FRAMES   = 60;
    private static final int INV_FRAMES     = 60;
    private static final int OPTIONS_FRAMES = 60;
    private static final int DRAIN_FRAMES   = 40;   // flush the async screenshot read-backs before quit

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
                             P_DRAIN = 14, P_STOP = 15, P_DONE = 16,
                             P_SCREENS = 17, P_VERIFY = 18, P_HUD = 19, P_MODULES = 20,
                             P_FLICKER = 21, P_TABS = 22, P_SCROLL = 23, P_CLICKS = 24, P_GLIDE = 25,
                             P_TOOLTIPS = 26, P_EFFECTS = 27, P_COMBAT = 28, P_TRANS = 29, P_INGAME = 30, P_LISTS = 31,
                             P_LOOPPREV = 32;

    /** Per-screen shot timing for the {@code screens} sweep. */
    private static final int SCREEN_SETTLE = 45;   // frames rendered before the capture request
    private static final int SCREEN_FLUSH  = 6;    // extra frames after it, so the async read-back grabs the right frame

    private static boolean resolved;      // env vars checked exactly once
    private static File    outDir;        // null => shot pipeline inert
    /** When set (env {@code S1MP1E_SHOT_MODE=screens}) the run skips the default sweep and shoots each new glass screen. */
    private static boolean screensMode;
    /** When set (env {@code S1MP1E_SHOT_MODE=verify}) the run does the independent verifier sweep: consecutive frames of a
     *  full-screen glass surface (flicker/self-ghost check) plus a forced creative item-tab capture. Inert otherwise. */
    private static boolean verifyMode;
    private static int     verifyStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=hud}) the run drives the in-world HUD overlays (chat, tab list, boss bar,
     *  name tag, toast, action bar) and shoots each. Inert otherwise. */
    private static boolean hudMode;
    private static int     hudStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=modules}) the run drives the BlockOutline + ChromaHud modules (custom-colour /
     *  wide / chroma / filled outline on a placed block, chroma HUD text, and both config pages) and shoots each. The
     *  module state it changes is snapshotted first and restored afterwards (never saved). Inert otherwise. */
    private static boolean modulesMode;
    private static int     modulesStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=flicker}) the run captures {@link #FLK_FRAMES} consecutive frames of three
     *  representative new glass surfaces (creative inventory, chat, tab list) over a frozen backdrop, so any frame-to-frame
     *  delta inside a settled glass panel would be the glass sampling itself. Inert otherwise. */
    private static boolean flickerMode;
    private static int     flickerStage;
    private static int     flickerShot;    // index (0..FLK_FRAMES-1) of the consecutive capture in progress
    private static boolean flickerFrozen;  // backdrop (clouds/shadows) frozen exactly once
    /** When set (env {@code S1MP1E_SHOT_MODE=tabs}) the run opens the creative inventory with tab contents built, forces a
     *  normal item tab and then the inventory tab, and shoots each so the fused glass tab row can be judged. Inert otherwise. */
    private static boolean tabsMode;
    private static int     tabsStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=scroll}) the run opens the creative item grid (populated search tab) and
     *  captures the vertical glass scrollbar scrolled to a middle position, a short glide burst after a wheel step, and
     *  a frame with the thumb held (glass lens). Inert otherwise. */
    private static boolean scrollMode;
    private static int     scrollStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=clicks}) the run drives AUTOMATED click tests: it calls each glide screen's
     *  {@code mouseClicked}/{@code mouseReleased} at the pixel centre of a specific drawn item / pattern / trade, both at
     *  rest and mid-glide, and asserts the resulting action picked exactly that item / pattern / trade. PASS/FAIL lines
     *  go to stdout (the run log). No real mouse needed. Inert otherwise. */
    private static boolean clicksMode;
    private static int     clicksStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=glide}) the run captures {@link #GLIDE_FRAMES} consecutive frames after ONE
     *  wheel step (one row / one trade) on each of the creative grid, loom pattern grid and villager trade list, so the
     *  sub-pixel content glide can be measured settling row-aligned. Inert otherwise. */
    private static boolean glideMode;
    private static int     glideStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=tooltips}) the run hovers an item / widget in every screen that shows a
     *  liquid-glass hover tooltip, so the card overlaps other items / glass, arms {@link GuiLayerProbe} for the captured
     *  frame and logs a TOOLTIP-LAYER PASS/FAIL line per scene (is anything drawn over the card?). Inert otherwise. */
    private static boolean tooltipsMode;
    private static int     ttStage;
    /** When set (env {@code S1MP1E_SHOT_MODE=effects}) the run gives the player several potion effects (beneficial,
     *  harmful, ambient and infinite) and shoots the now-glass status-effect panel beside the survival inventory (wide),
     *  the creative inventory (wide), a chest (documents that vanilla shows no effect panel on plain containers), and a
     *  narrow GUI-scale survival inventory where the COMPACT icon-only layout shows plus its top-layer hover tooltip
     *  (armed through {@link GuiLayerProbe}). Inert otherwise. */
    private static boolean effectsMode;
    /** When set (env {@code S1MP1E_SHOT_MODE=trans}) the run stays in the main menu and walks menu-to-menu screen
     *  switches, capturing the last frame before each switch and every frame of the first 12 after it. */
    private static boolean transMode;

    /** When set (env {@code S1MP1E_SHOT_MODE=ingame}) the run drives the in-gameplay screen switches that vanilla cuts hard:
     *  creative category-tab switch, survival recipe-book open/close and its category-tab switch, and the advancements tab —
     *  each now snapshot-cross-dissolved ({@link com.seagull.liquidglass.client.render.ScreenTransition#onTabSwitch}). Captures
     *  a {@code -pre} frame then early frames so the dissolve is visible frame by frame. Also validates the four new @Inject
     *  targets resolve (require:1 fails the load if a target method is missing when its screen class loads). */
    private static boolean ingameMode;
    /** {@code S1MP1E_SHOT_MODE=lists}: menu-only sweep of the selection-list animations (smooth wheel scroll, gliding
     *  selection box) on the language list; logs the list's scroll amount at every capture. */
    private static boolean listsMode;
    /** {@code S1MP1E_SHOT_MODE=intro}: capture the real boot {@code LoadingOverlay} frame by frame (the brand intro,
     *  held up ~3.9 s by {@link com.seagull.liquidglass.client.mixin.LoadingOverlayIntroMixin}) into {@code intro_NNN.png},
     *  time-sampled ~33 fps, then quit. Lets the ported intro shader be compared against the prototype. */
    private static boolean introMode;
    private static int     introCount;
    private static long    introLastMs;
    private static long    introFirstMs;
    private static boolean introSeen;
    private static int ingStage;
    private static int     effectsStage;
    private static java.util.Map<Setting, Object> modulesSnapshot;
    private static java.util.Map<Module, Boolean> modulesEnabledSnapshot;
    private static boolean auditPending;  // S1MP1E_AUDIT set and not yet run
    /** The {@code screens} sweep scenes ({name, screen supplier}), built lazily once the world/player exist. */
    private static List<Object[]> screenScenes;
    private static int screenIdx;
    private static long    startMs;       // watchdog origin
    private static int     phase = P_INIT;
    private static int     frames;
    /** Re-entrancy guard: heavy actions (createFreshLevel, reloadResourcePacks) pump the render loop
     *  synchronously, which re-fires this render-TAIL hook. Nested frames pumped inside a step do nothing. */
    private static boolean busy;
    /** World creation is attempted exactly once. */
    private static boolean worldTried;
    /** The language resource reload triggered in {@link #stepInit}; the title is "fully shown" once it is done
     *  (26.2 has no {@code Minecraft.getOverlay()} to poll, so we track the reload's own future). */
    private static java.util.concurrent.CompletableFuture<Void> reloadFuture;
    /** Target reference resolution. */
    private static final int SHOT_W = 1280, SHOT_H = 720;
    /** Window size + GUI scale {@link #forceSize} pins every frame. Normally {@link #SHOT_W}x{@link #SHOT_H} at scale 2;
     *  the effects sweep's compact stage narrows the window (so the space right of the inventory drops below 120 px and
     *  {@code EffectsInInventory} falls into its COMPACT icon-only layout), then restores them. */
    private static int forcedScale = 2;
    static {
        // Dev-only: S1MP1E_SHOT_SCALE overrides the pinned GUI scale so font sharpness can be checked
        // at scales other than 2 (the font atlas bakes at launch from options.txt's guiScale, set to match).
        try {
            String s = System.getenv("S1MP1E_SHOT_SCALE");
            if (s != null && !s.trim().isEmpty()) forcedScale = Integer.parseInt(s.trim());
        } catch (Throwable ignored) {}
    }
    private static int forcedW = SHOT_W;
    private static int forcedH = SHOT_H;

    /** Called at the end of every rendered frame (render thread). No-op when both vars are unset. */
    public static void onRenderEnd(Minecraft client) {
        if (!resolved) {
            resolved = true;
            try {
                String dir = System.getenv("S1MP1E_SHOT");
                if (dir != null && !dir.trim().isEmpty()) {
                    outDir = new File(dir.trim());
                    outDir.mkdirs();
                    startMs = System.currentTimeMillis();
                    String mode = System.getenv("S1MP1E_SHOT_MODE");
                    screensMode = mode != null && mode.trim().equalsIgnoreCase("screens");
                    verifyMode = mode != null && mode.trim().equalsIgnoreCase("verify");
                    hudMode = mode != null && mode.trim().equalsIgnoreCase("hud");
                    modulesMode = mode != null && mode.trim().equalsIgnoreCase("modules");
                    flickerMode = mode != null && mode.trim().equalsIgnoreCase("flicker");
                    tabsMode = mode != null && mode.trim().equalsIgnoreCase("tabs");
                    scrollMode = mode != null && mode.trim().equalsIgnoreCase("scroll");
                    clicksMode = mode != null && mode.trim().equalsIgnoreCase("clicks");
                    glideMode = mode != null && mode.trim().equalsIgnoreCase("glide");
                    tooltipsMode = mode != null && mode.trim().equalsIgnoreCase("tooltips");
                    effectsMode = mode != null && mode.trim().equalsIgnoreCase("effects");
                    combatMode = mode != null && mode.trim().equalsIgnoreCase("combat");
                    transMode = mode != null && mode.trim().equalsIgnoreCase("trans");
                    ingameMode = mode != null && mode.trim().equalsIgnoreCase("ingame");
                    listsMode = mode != null && mode.trim().equalsIgnoreCase("lists");
                    introMode = mode != null && mode.trim().equalsIgnoreCase("intro");
                    try {   // S1MP1E_SHOT_INGAME_FROM=<stage>: start the ingame sweep at that stage (e.g. 7 = typing only)
                        String from = System.getenv("S1MP1E_SHOT_INGAME_FROM");
                        if (from != null && !from.isBlank()) ingStage = Integer.parseInt(from.trim());
                    } catch (NumberFormatException ignored) {}
                    System.out.println("[S1mp1e][DevShot] active -> " + outDir.getAbsolutePath()
                            + (screensMode ? " (screens sweep)" : verifyMode ? " (verify sweep)"
                            : hudMode ? " (hud sweep)" : modulesMode ? " (modules sweep)"
                            : flickerMode ? " (flicker sweep)" : tabsMode ? " (tabs sweep)"
                            : scrollMode ? " (scroll sweep)" : clicksMode ? " (click tests)" : glideMode ? " (glide capture)"
                            : tooltipsMode ? " (tooltip layer sweep)" : effectsMode ? " (effects sweep)" : ""));
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

        // Intro mode runs before the normal state machine: it just shoots the boot LoadingOverlay frame by frame.
        if (introMode) {
            if (busy) return;
            busy = true;
            try {
                stepIntro(client);
            } catch (Throwable t) {
                System.out.println("[S1mp1e][DevShot] intro capture error: " + t);
                introMode = false;
                frames = 0;
                phase = P_DRAIN;
            } finally {
                busy = false;
            }
            return;
        }

        // Global watchdog — never hang.
        if (phase != P_DONE && startMs > 0 && System.currentTimeMillis() - startMs > WATCHDOG_MS) {
            System.out.println("[S1mp1e][DevShot] watchdog fired (" + (WATCHDOG_MS / 1000)
                    + "s) at phase " + phase + " — quitting.");
            quit(client);
            phase = P_DONE;
            return;
        }

        // A heavy step may re-fire this hook (nested render); ignore those re-entrant frames.
        if (busy) return;
        busy = true;
        try {
            // Keep the window at the reference resolution + GUI scale every frame (cheap no-op once set).
            forceSize(client);
            switch (phase) {
                case P_INIT:         stepInit(client);                       break;
                case P_WAIT_TITLE:   stepWaitTitle(client);                  break;
                case P_TITLE:        stepTitle(client);                      break;
                case P_WAIT_CONFIG:  stepWaitConfig(client, P_CONFIG);       break;
                case P_CONFIG:       stepConfig(client);                     break;
                case P_WAIT_WORLD:   stepWaitWorld(client);                  break;
                case P_WORLD_SETTLE: stepWorldSettle(client);               break;
                case P_WORLD:        stepWorld(client);                      break;
                case P_WAIT_CONFIG2: stepWaitConfig(client, P_CONFIG_WORLD); break;
                case P_CONFIG_WORLD: stepConfigWorld(client);               break;
                case P_WAIT_INV:     stepWaitInventory(client);             break;
                case P_INV:          stepInventory(client);                 break;
                case P_WAIT_OPTIONS: stepWaitOptions(client);              break;
                case P_OPTIONS:      stepOptions(client);                  break;
                case P_SCREENS:      stepScreens(client);                  break;
                case P_VERIFY:       stepVerify(client);                   break;
                case P_HUD:          stepHud(client);                      break;
                case P_MODULES:      stepModules(client);                  break;
                case P_FLICKER:      stepFlicker(client);                  break;
                case P_TABS:         stepTabs(client);                     break;
                case P_SCROLL:       stepScroll(client);                   break;
                case P_CLICKS:       stepClicks(client);                   break;
                case P_GLIDE:        stepGlide(client);                    break;
                case P_TOOLTIPS:     stepTooltips(client);                 break;
                case P_EFFECTS:      stepEffects(client);                  break;
                case P_COMBAT:       stepCombat(client);                   break;
                case P_TRANS:        stepTrans(client);                    break;
                case P_INGAME:       stepIngame(client);                   break;
                case P_LISTS:        stepLists(client);                    break;
                case P_LOOPPREV:     stepLoopPreview(client);              break;
                case P_DRAIN:        stepDrain(client);                    break;
                case P_STOP:         stepStop(client);                     break;
                default:             break;
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

    /** Screen currently shown (26.2 keeps it on {@code Minecraft.gui}). */
    private static Screen currentScreen(Minecraft client) {
        return client.gui == null ? null : client.gui.screen();
    }

    /**
     * Keep the window at {@link #SHOT_W}x{@link #SHOT_H} and GUI scale 2 so every reference shot matches. The
     * screenshot reads the main render target, which follows the window's framebuffer size, so a real 1280x720
     * client area (this box's desktop is large enough — it already produced the 1.20.1 reference at that size)
     * gives a 1280x720 shot. Self-heals after any stray GLFW resize; a cheap no-op once the size already matches.
     */
    private static void forceSize(Minecraft client) {
        try {
            Window win = client.getWindow();
            if (win == null) return;
            boolean changed = false;
            if (win.getWidth() != forcedW || win.getHeight() != forcedH) {
                win.setWindowed(forcedW, forcedH);
                changed = true;
            }
            if (win.getGuiScale() != forcedScale) {
                client.options.guiScale().set(forcedScale);
                changed = true;
            }
            if (changed) client.framebufferSizeChanged();
        } catch (Throwable t) {
            skip("force size " + forcedW + "x" + forcedH, t);
        }
    }

    // ---- steps 1-2: window + title -----------------------------------------

    /**
     * Boot-intro capture: while the {@code LoadingOverlay} is up, shoot the framebuffer ~33 fps into {@code intro_NNN.png};
     * once it is gone (or it never appeared) flush the async writes and quit. The overlay is held ~3.9 s by the intro
     * mixin, so this samples the whole animation regardless of frame rate.
     */
    private static void stepIntro(Minecraft client) {
        forceSize(client);
        Object ov = client.gui == null ? null : client.gui.overlay();
        long now = System.currentTimeMillis();
        if (ov instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
            if (introFirstMs == 0) introFirstMs = now;
            introSeen = true;
            if (now - introLastMs >= 30 && introCount < 220) {
                introLastMs = now;
                capture(client, String.format("intro_%03d.png", introCount++));
            }
            return;
        }
        // Overlay gone, or never showed within 20 s — boot part done. Continue through the title (glass must still be
        // intact after the intro ran during the reload) into a fresh world, shooting the world-entry loop on the way.
        if (introSeen || now - startMs > 20000) {
            System.out.println("[S1mp1e][DevShot] intro capture done: " + introCount + " frames");
            try { client.options.pauseOnLostFocus = false; } catch (Throwable ignored) {}   // an unfocused dev window must not pause the world
            introMode = false;
            introWorld = true;
            frames = 0;
            phase = P_WAIT_TITLE;
        }
    }

    /** Dev-only screen that plays the world-entry loop ({@link dev.s1mp1e.client.gui.BrandIntro#MODE_LOOP}) on black,
     *  exactly as {@code LevelLoadingScreen} does, for as long as it is open. */
    private static final class LoopPreview extends Screen {
        private final long t0 = System.nanoTime();
        LoopPreview() { super(net.minecraft.network.chat.Component.literal("loop preview")); }
        @Override public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float d) {}
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float d) {
            g.fill(0, 0, this.width, this.height, 0xFF000000);
            dev.s1mp1e.client.gui.BrandIntro.draw(g, (System.nanoTime() - t0) / 1.0E9F, dev.s1mp1e.client.gui.BrandIntro.MODE_LOOP, 1.0F);
        }
    }
    private static long lpLastMs;
    private static int  lpCount;

    /** Loop preview: 8 s at 10 fps into {@code lp_NNN.png}, then on into the real world entry. */
    private static void stepLoopPreview(Minecraft client) {
        if (!(currentScreen(client) instanceof LoopPreview)) {
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
        else { skip("create world", new IllegalStateException("world creation did not start")); phase = P_DRAIN; }
    }

    /** Set after the boot part of the {@code intro} mode: title shot, then the world-entry loop, then an in-world shot. */
    private static boolean introWorld;
    private static int     wlCount;
    private static long    wlLastMs;

    private static void stepInit(Minecraft client) {
        try {
            client.getWindow().setWindowed(SHOT_W, SHOT_H);
            client.options.guiScale().set(forcedScale);
            // Only pay the resource-reload cost when the language actually differs.
            if (!"zh_tw".equals(client.getLanguageManager().getSelected())) {
                client.getLanguageManager().setSelected("zh_tw");
                client.options.languageCode = "zh_tw";
                reloadFuture = client.reloadResourcePacks();
            }
            client.framebufferSizeChanged();
        } catch (Throwable t) {
            skip("init (window/scale/language)", t);
        }
        frames = 0;
        phase = P_WAIT_TITLE;
    }

    private static void stepWaitTitle(Minecraft client) {
        // Wait past the Mojang splash / any resource reload. The TitleScreen becomes the shown screen behind the
        // LoadingOverlay while it is still up/fading, so require the loading overlay to be GONE (26.2 keeps it on
        // Minecraft.gui — client.gui.overlay()), plus the initial game load and the language reload we triggered
        // both finished. Without the overlay==null gate the Mojang splash is captured instead of the title.
        boolean loaded = client.isGameLoadFinished()
                && client.gui != null && client.gui.overlay() == null
                && (reloadFuture == null || reloadFuture.isDone());
        if (currentScreen(client) instanceof TitleScreen && loaded) {
            frames = 0; phase = P_TITLE;
        } else if (++frames > WAIT_SCREEN_CAP * 4) {
            skip("wait title screen", new IllegalStateException("title never shown"));
            frames = 0; phase = P_TITLE;   // try to shoot whatever is on screen, then continue
        }
    }

    private static void stepTitle(Minecraft client) {
        if (++frames >= TITLE_FRAMES) {
            if (introWorld) {
                capture(client, "after-title.png");   // glass buttons must render after the boot intro
                // a dev world loads in ~2 s, too short to see a whole cycle: preview the world-entry loop on its own first
                open(client, new LoopPreview(), "open world-entry loop preview");
                frames = 0; lpLastMs = 0L; lpCount = 0; phase = P_LOOPPREV;
                return;
            }
            if (transMode) { frames = 0; transStep = 0; phase = P_TRANS; return; }   // menus only, no world
            if (listsMode) { frames = 0; phase = P_LISTS; return; }
            if (screensMode || hudMode || modulesMode || flickerMode || tabsMode || scrollMode || clicksMode || glideMode || tooltipsMode || effectsMode || combatMode || ingameMode) {
                // Screens / HUD / modules / flicker / tabs / scroll / clicks / effects sweep: skip the title/config baseline shots and head straight for the world.
                if (createWorld(client)) {
                    frames = 0; phase = P_WAIT_WORLD;
                } else {
                    skip("create world", new IllegalStateException("world creation did not start"));
                    phase = P_DRAIN;
                }
                return;
            }
            capture(client, "title.png");
            open(client, new S1mp1eConfigScreen(), "open settings (over title)");
            frames = 0; phase = P_WAIT_CONFIG;
        }
    }

    // ---- shared config-screen wait -----------------------------------------

    private static void stepWaitConfig(Minecraft client, int next) {
        if (currentScreen(client) instanceof S1mp1eConfigScreen) {
            frames = 0; phase = next;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait settings screen", new IllegalStateException("settings screen never opened"));
            frames = 0; phase = next;
        }
    }

    // ---- step 3: config over title, then create the world ------------------

    private static void stepConfig(Minecraft client) {
        if (++frames < CONFIG_FRAMES) return;
        capture(client, "config.png");
        close(client);                      // back to the title
        if (createWorld(client)) {
            frames = 0; phase = P_WAIT_WORLD;
        } else {
            skip("create world", new IllegalStateException("world creation did not start"));
            phase = P_DRAIN;                // no world -> skip every world-dependent shot, still drain+quit
        }
    }

    // ---- step 4: world load, settle, loadout, shot ------------------------

    private static long rlT0;
    private static int rlIdx;

    private static void stepWaitWorld(Minecraft client) {
        // the real world-load screen (chunk grid + liquid loader): a time-spaced strip while it is up
        if (introWorld && currentScreen(client) instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) {
            long ms = System.currentTimeMillis();   // world-entry loop: ~20 fps strip for as long as it is up
            if (ms - wlLastMs >= 50 && wlCount < 400) { wlLastMs = ms; capture(client, String.format("wl_%03d.png", wlCount++)); }
        } else if (currentScreen(client) instanceof net.minecraft.client.gui.screens.LevelLoadingScreen && rlIdx < 20) {
            long now = System.nanoTime();
            if (rlT0 == 0L) rlT0 = now;
            if ((now - rlT0) / 1_000_000L >= rlIdx * 100L) capture(client, String.format("ld-realload-%02d.png", rlIdx++));
        }
        if (client.level != null && client.player != null && client.getSingleplayerServer() != null
                && currentScreen(client) == null) {
            frames = 0; phase = P_WORLD_SETTLE;
        } else if (++frames > WAIT_WORLD_CAP) {
            skip("wait world load", new IllegalStateException("world never became ready"));
            phase = P_DRAIN;
        }
    }

    private static void stepWorldSettle(Minecraft client) {
        if (++frames < WORLD_SETTLE) return;
        if (introWorld) {                   // glass HUD must be intact in game too; then finish
            System.out.println("[S1mp1e][DevShot] world-entry loop frames: " + wlCount);
            capture(client, "after-world.png");
            frames = 0; phase = P_DRAIN;
            return;
        }
        applyWorldSetup(client);            // time/weather/position/loadout (own try/catch inside)
        frames = 0; phase = transMode ? P_TRANS : verifyMode ? P_VERIFY : screensMode ? P_SCREENS : hudMode ? P_HUD
                : modulesMode ? P_MODULES : flickerMode ? P_FLICKER : tabsMode ? P_TABS
                : scrollMode ? P_SCROLL : clicksMode ? P_CLICKS : glideMode ? P_GLIDE : tooltipsMode ? P_TOOLTIPS
                : effectsMode ? P_EFFECTS : combatMode ? P_COMBAT : ingameMode ? P_INGAME : P_WORLD;
    }

    private static void stepWorld(Minecraft client) {
        // Suppress the transient advancement/recipe toasts and the advancement chat line that the scripted
        // loadout triggers, so the over-world reference frames stay clean and deterministic.
        try { if (client.gui != null) client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { if (client.gui != null) client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}
        if (++frames >= WORLD_FRAMES) {
            capture(client, "world.png");
            open(client, new S1mp1eConfigScreen(), "open settings (over world)");
            frames = 0; phase = P_WAIT_CONFIG2;
        }
    }

    // ---- step 5: config over world ----------------------------------------

    private static void stepConfigWorld(Minecraft client) {
        if (++frames < CONFIG_FRAMES) return;
        capture(client, "config-world.png");
        close(client);
        open(client, new InventoryScreen(client.player), "open inventory");
        frames = 0; phase = P_WAIT_INV;
    }

    // ---- step 6: inventory -------------------------------------------------

    private static void stepWaitInventory(Minecraft client) {
        if (currentScreen(client) instanceof InventoryScreen) {
            frames = 0; phase = P_INV;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait inventory screen", new IllegalStateException("inventory never opened"));
            frames = 0; phase = P_INV;
        }
    }

    private static void stepInventory(Minecraft client) {
        if (++frames < INV_FRAMES) return;
        capture(client, "inventory.png");
        // The video-settings parent must be an already-shown screen; reuse the inventory that is still current.
        try {
            Screen parent = currentScreen(client);
            client.gui.setScreen(new VideoSettingsScreen(parent, client, client.options));
        } catch (Throwable t) {
            skip("open video settings", t);
        }
        frames = 0; phase = P_WAIT_OPTIONS;
    }

    // ---- step 7: video settings -------------------------------------------

    private static void stepWaitOptions(Minecraft client) {
        if (currentScreen(client) instanceof VideoSettingsScreen) {
            frames = 0; phase = P_OPTIONS;
        } else if (++frames > WAIT_SCREEN_CAP) {
            skip("wait video settings screen", new IllegalStateException("video settings never opened"));
            frames = 0; phase = P_OPTIONS;
        }
    }

    private static void stepOptions(Minecraft client) {
        if (++frames < OPTIONS_FRAMES) return;
        capture(client, "options.png");
        close(client);
        frames = 0; phase = P_DRAIN;        // Step 8: drain pending read-backs, then leave the world + quit.
    }

    // ---- screens sweep (S1MP1E_SHOT_MODE=screens): one glass screen per scene -------

    /**
     * Walk the list of "screens" scenes, opening each new liquid-glass screen, letting it settle, and capturing it into the
     * shot folder. Each scene is fully guarded: a scene whose screen can't be constructed is skipped without wedging the run,
     * and a screen that opens is given {@link #SCREEN_SETTLE} frames to render before the async capture and
     * {@link #SCREEN_FLUSH} more before the next scene replaces it.
     */
    private static void stepScreens(Minecraft client) {
        if (screenScenes == null) buildScreenScenes(client);
        if (screenScenes == null || screenIdx >= screenScenes.size()) {
            close(client);
            frames = 0; phase = P_DRAIN;
            return;
        }
        Object[] scene = screenScenes.get(screenIdx);
        String name = (String) scene[0];
        if (frames == 0) {
            Screen s = null;
            try {
                @SuppressWarnings("unchecked")
                Supplier<Screen> sup = (Supplier<Screen>) scene[1];
                s = sup.get();
            } catch (Throwable t) {
                skip("build screen " + name, t);
            }
            if (s == null) {
                screenIdx++;      // frames stays 0 -> next tick builds the next scene
                return;
            }
            open(client, s, "open " + name);
        }
        frames++;
        if (frames == SCREEN_SETTLE) {
            capture(client, name + ".png");
        } else if (frames >= SCREEN_SETTLE + SCREEN_FLUSH) {
            screenIdx++;
            frames = 0;
        }
    }

    /** Build the ordered list of new-glass-screen scenes. Needs the world/player, so it runs after the world settles. */
    private static void buildScreenScenes(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null) return;   // leave screenScenes null -> stepScreens drains cleanly
        List<Object[]> list = new ArrayList<>();
        list.add(new Object[]{"creative", (Supplier<Screen>) () ->
                new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true)});
        list.add(new Object[]{"anvil", (Supplier<Screen>) () ->
                new AnvilScreen(new AnvilMenu(1, player.getInventory()), player.getInventory(), Component.literal("Anvil"))});
        list.add(new Object[]{"crafter", (Supplier<Screen>) () ->
                new CrafterScreen(new CrafterMenu(2, player.getInventory()), player.getInventory(), Component.literal("Crafter"))});
        list.add(new Object[]{"advancements", (Supplier<Screen>) () ->
                new AdvancementsScreen(player.connection.getAdvancements())});
        list.add(new Object[]{"stats", (Supplier<Screen>) () ->
                new StatsScreen(null, player.getStats())});
        list.add(new Object[]{"social", (Supplier<Screen>) () ->
                new SocialInteractionsScreen()});
        list.add(new Object[]{"book", (Supplier<Screen>) () ->
                new BookViewScreen(new BookViewScreen.BookAccess(List.of(
                        Component.literal("S1mp1e\n\n液態玻璃書頁\n\nLiquid glass book page.\nDark ink on a\nglass-framed parchment."))))});
        list.add(new Object[]{"death", (Supplier<Screen>) () ->
                new DeathScreen(Component.literal("You Died"), true, player)});
        screenScenes = list;
        screenIdx = 0;
    }

    // ---- verify sweep (S1MP1E_SHOT_MODE=verify): independent flicker + creative-tab checks --------

    /**
     * Independent verifier sweep (not part of the implementer's screens sweep):
     * <ol>
     *   <li><b>Flicker / self-ghost</b> — opens the full-screen glass {@link StatsScreen} and captures three
     *       <em>consecutive</em> frames ({@code v-stats-f0/f1/f2}); if the glass ever sampled itself the frames would
     *       differ frame-to-frame.</li>
     *   <li><b>Creative item tab</b> — force-builds the creative tab contents and presets the search tab, so the
     *       creative item panel, tabs, scrollbar and search field actually draw (the implementer's sweep fell back to
     *       the survival tab). Two consecutive frames ({@code v-creative-f0/f1}).</li>
     * </ol>
     * Fully guarded; every failure is skipped, never wedges the run. Inert unless {@code S1MP1E_SHOT_MODE=verify}.
     */
    private static void stepVerify(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null) { frames = 0; phase = P_DRAIN; return; }

        if (verifyStage == 0) {                      // stats: three consecutive frames
            if (frames == 0) open(client, new StatsScreen(null, player.getStats()), "verify stats");
            frames++;
            if (frames == SCREEN_SETTLE)          capture(client, "v-stats-f0.png");
            else if (frames == SCREEN_SETTLE + 1) capture(client, "v-stats-f1.png");
            else if (frames == SCREEN_SETTLE + 2) capture(client, "v-stats-f2.png");
            else if (frames >= SCREEN_SETTLE + 12) { verifyStage = 1; frames = 0; }
            return;
        }

        if (verifyStage == 1) {                      // creative forced onto the item/search tab
            if (frames == 0) {
                try {
                    net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                            player.connection.enabledFeatures(), true, client.level.registryAccess());
                } catch (Throwable t) { skip("verify rebuild creative tabs", t); }
                try {
                    java.lang.reflect.Field f = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                    f.setAccessible(true);
                    f.set(null, net.minecraft.world.item.CreativeModeTabs.searchTab());
                } catch (Throwable t) { skip("verify preset creative tab", t); }
                open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "verify creative");
            }
            frames++;
            if (frames == SCREEN_SETTLE - 6) {        // re-assert the tab after init() ran
                try {
                    Screen s = currentScreen(client);
                    if (s instanceof CreativeModeInventoryScreen) {
                        java.lang.reflect.Method m = CreativeModeInventoryScreen.class
                                .getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                        m.setAccessible(true);
                        m.invoke(s, net.minecraft.world.item.CreativeModeTabs.searchTab());
                    }
                } catch (Throwable t) { skip("verify select creative tab", t); }
            }
            if (frames == SCREEN_SETTLE)          capture(client, "v-creative-f0.png");
            else if (frames == SCREEN_SETTLE + 1) capture(client, "v-creative-f1.png");
            else if (frames >= SCREEN_SETTLE + 12) { verifyStage = 2; frames = 0; }
            return;
        }

        close(client);
        frames = 0; phase = P_DRAIN;
    }

    // ---- hud sweep (S1MP1E_SHOT_MODE=hud): drive each in-world HUD overlay and shoot it -------

    private static final int HUD_SETTLE = 55;   // frames rendered before the capture (also lets server->client packets sync)
    private static final int HUD_FLUSH  = 8;    // extra frames after it for the async read-back

    /**
     * Walk the HUD overlay scenes over the live world with no screen open (except the chat-input variant): chat lines, the
     * open chat input, the tab list, a boss bar, a named entity, a toast and the action bar. Each stage sets its scene up
     * once (guarded), renders {@link #HUD_SETTLE} frames so server-driven state (boss bar, spawned name tag) reaches the
     * client HUD, captures, then tears down and advances. Nothing here clears chat/toasts every frame (unlike the clean
     * world shot), so the overlays actually show.
     */
    private static void stepHud(Minecraft client) {
        if (client.player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }

        switch (hudStage) {
            case 0: {   // chat: several lines, HUD only
                if (frames == 0) {
                    try {
                        client.gui.hud.getChat().clearMessages(true);
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("[S1mp1e] 液態玻璃聊天視窗"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("<Steve> hey, check out this glass chat"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("<Alex> the backdrop refracts nicely"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("玩家加入了遊戲"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("/gamemode creative"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("<Steve> readable over any terrain"));
                    } catch (Throwable t) { skip("hud chat lines", t); }
                }
                if (advanceHud(client, "chat.png")) hudStage = 1;
                return;
            }
            case 1: {   // chat with the input field open
                if (frames == 0) open(client, new ChatScreen("液態玻璃 /", false), "open chat input");
                if (advanceHud(client, "chat-input.png")) { close(client); hudStage = 2; }
                return;
            }
            case 2: {   // player tab list held open
                if (frames == 0) {
                    try {
                        client.gui.hud.getTabList().setHeader(Component.literal("S1mp1e Liquid Glass"));
                        client.gui.hud.getTabList().setFooter(Component.literal("play.s1mp1e.dev  -  26.2"));
                    } catch (Throwable t) { skip("hud tab list header/footer", t); }
                    // Hud.extractTabList only shows the list while the player-list key is DOWN and (in singleplayer) a
                    // LIST-slot scoreboard objective exists, so add one and hold the key.
                    try {
                        MinecraftServer server = client.getSingleplayerServer();
                        if (server != null) {
                            Commands cmd = server.getCommands();
                            CommandSourceStack src = server.createCommandSourceStack();
                            cmd.performPrefixedCommand(src, "scoreboard objectives add s1mp1e_hp dummy \"血量\"");
                            cmd.performPrefixedCommand(src, "scoreboard objectives setdisplay list s1mp1e_hp");
                            cmd.performPrefixedCommand(src, "scoreboard players set @a s1mp1e_hp 20");
                        }
                    } catch (Throwable t) { skip("hud tab list objective", t); }
                }
                try { client.options.keyPlayerList.setDown(true); } catch (Throwable ignored) {}
                if (advanceHud(client, "tablist.png")) {
                    try { client.options.keyPlayerList.setDown(false); } catch (Throwable ignored) {}
                    try {
                        MinecraftServer server = client.getSingleplayerServer();
                        if (server != null) {
                            server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(), "scoreboard objectives remove s1mp1e_hp");
                        }
                    } catch (Throwable ignored) {}
                    hudStage = 3;
                }
                return;
            }
            case 3: {   // boss bar via /bossbar
                if (frames == 0) hudBossBar(client, true);
                if (advanceHud(client, "bossbar.png")) { hudBossBar(client, false); hudStage = 4; }
                return;
            }
            case 4: {   // named entity (armor stand) in front of the camera
                if (frames == 0) hudNameTag(client);
                if (advanceHud(client, "nametag.png")) hudStage = 5;
                return;
            }
            case 5: {   // a system toast
                if (frames == 0) {
                    try {
                        SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                                Component.literal("S1mp1e"), Component.literal("液態玻璃通知 - glass toast card"));
                    } catch (Throwable t) { skip("hud toast", t); }
                }
                if (advanceHud(client, "toast.png")) hudStage = 6;
                return;
            }
            case 6: {   // action-bar / overlay message
                if (frames == 0) {
                    try { client.gui.hud.setOverlayMessage(Component.literal("動作列 - Action Bar"), false); }
                    catch (Throwable t) { skip("hud action bar", t); }
                }
                // The overlay message counts down each tick; re-assert it right up to the capture.
                if (frames < HUD_SETTLE) {
                    try { client.gui.hud.setOverlayMessage(Component.literal("動作列 - Action Bar"), false); }
                    catch (Throwable ignored) {}
                }
                if (advanceHud(client, "actionbar.png")) hudStage = HUD_EARLY ? 7 : 99;
                return;
            }
            case 7: {   // (early-capture runs only) HUD modules switched OFF -> their fade-out
                if (frames == 0) {
                    hudFadeWas = new boolean[]{ hudFadeSet(false, 0), hudFadeSet(false, 1) };
                    hudFadeLogBounds();
                }
                if (advanceHud(client, "hudout.png")) hudStage = 8;
                return;
            }
            case 8: {   // ... and switched back ON -> their fade-in (and the items' scale-in)
                if (frames == 0) { hudFadeSet(true, 0); hudFadeSet(true, 1); }
                if (advanceHud(client, "hudin.png")) {
                    if (hudFadeWas != null) { hudFadeSet(hudFadeWas[0], 0); hudFadeSet(hudFadeWas[1], 1); }
                    hudStage = 9;
                }
                return;
            }
            // Potion HUD rows (zh_tw names: 力量 Strength sorts before 加速 Speed): a new row fades in, the others glide.
            case 9: {   // Speed alone -> row 0 fades in
                if (frames == 0) {
                    hudCmd(client, "effect clear @p");
                    hudCmd(client, "effect give @p minecraft:speed 120 0 true");
                }
                if (advanceHud(client, "potin.png")) hudStage = 10;
                return;
            }
            case 10: {  // + Strength -> it fades in at row 0 and Speed glides down to row 1
                if (frames == 0) hudCmd(client, "effect give @p minecraft:strength 120 0 true");
                if (advanceHud(client, "potglide.png")) hudStage = 11;
                return;
            }
            case 11: {  // - Strength -> it fades out and Speed glides back up to row 0
                if (frames == 0) hudCmd(client, "effect clear @p minecraft:strength");
                if (advanceHud(client, "potout.png")) hudStage = 14;
                return;
            }
            case 14: {  // HUD editor opens with the shared screen-open fade
                if (frames == 0) open(client, new dev.s1mp1e.client.gui.S1mp1eHudEditScreen(), "open HUD editor");
                if (advanceHud(client, "hudedit.png")) {
                    close(client);
                    hudCmd(client, "effect clear @p");
                    hudStage = 99;
                }
                return;
            }
            default:
                frames = 0; phase = P_DRAIN;
        }
    }

    /** Run one command through the integrated server as the server source. */
    private static void hudCmd(Minecraft client, String command) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        } catch (Throwable t) {
            skip("hud cmd " + command, t);
        }
    }

    /** Original enabled state of the two modules the HUD-fade stages switch (restored afterwards). */
    private static boolean[] hudFadeWas;

    /** The HUD-fade stages' two modules: FPS (glass + text) and the inventory HUD (item icons). */
    private static dev.s1mp1e.client.Module hudFadeModule(int which) {
        for (dev.s1mp1e.client.Module m : dev.s1mp1e.client.ModuleManager.all()) {
            if (which == 0 && m instanceof dev.s1mp1e.client.module.FpsHudModule) return m;
            if (which == 1 && m instanceof dev.s1mp1e.client.module.InventoryHudModule) return m;
        }
        return null;
    }

    /** Set a HUD-fade module's enabled flag directly (render-side only); returns its previous state. */
    private static boolean hudFadeSet(boolean on, int which) {
        dev.s1mp1e.client.Module m = hudFadeModule(which);
        if (m == null) { skip("hud fade module " + which, new IllegalStateException("not found")); return false; }
        boolean was = m.enabled;
        m.enabled = on;
        return was;
    }

    private static void hudFadeLogBounds() {
        for (int w = 0; w < 2; w++) {
            if (hudFadeModule(w) instanceof dev.s1mp1e.client.HudBounds hb) {
                System.out.println("[S1mp1e][DevShot] hudfade bounds " + w + ": " + hb.hudX() + "," + hb.hudY() + " "
                        + hb.hudW() + "x" + hb.hudH());
            }
        }
    }

    /** Dev-only: {@code S1MP1E_SHOT_EARLY=1} also captures the first frames after each HUD stage opens, so an
     *  appear animation (fade / slide) is visible as a curve instead of only its settled end state. */
    private static final boolean HUD_EARLY = "1".equals(System.getenv("S1MP1E_SHOT_EARLY"));

    /** Render HUD_SETTLE frames, capture {@code name}, then HUD_FLUSH more; returns true once the stage is done. */
    private static boolean advanceHud(Minecraft client, String name) {
        frames++;
        if (HUD_EARLY && (frames == 1 || frames == 3 || frames == 6 || frames == 10)) {
            capture(client, name.replace(".png", String.format("-f%02d.png", frames)));
        }
        if (frames == HUD_SETTLE) {
            capture(client, name);
        } else if (frames >= HUD_SETTLE + HUD_FLUSH) {
            frames = 0;
            return true;
        }
        return false;
    }

    /** Add (or remove) a scripted boss bar through the integrated server's command dispatcher. */
    private static void hudBossBar(Minecraft client, boolean add) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) return;
            Commands cmd = server.getCommands();
            CommandSourceStack src = server.createCommandSourceStack();
            if (add) {
                cmd.performPrefixedCommand(src, "bossbar add s1mp1e:test \"Test Boss\"");
                cmd.performPrefixedCommand(src, "bossbar set s1mp1e:test color purple");
                cmd.performPrefixedCommand(src, "bossbar set s1mp1e:test max 100");
                cmd.performPrefixedCommand(src, "bossbar set s1mp1e:test value 68");
                cmd.performPrefixedCommand(src, "bossbar set s1mp1e:test players @a");
                cmd.performPrefixedCommand(src, "bossbar set s1mp1e:test visible true");
            } else {
                cmd.performPrefixedCommand(src, "bossbar remove s1mp1e:test");
            }
        } catch (Throwable t) {
            skip("hud bossbar " + (add ? "add" : "remove"), t);
        }
    }

    /** Spawn a named armor stand a couple blocks in front of the camera so its glass name-tag plate is visible. */
    private static void hudNameTag(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) return;
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp == null) return;
            ServerLevel level = sp.level();
            Vec3 look = sp.getLookAngle();
            double dist = 2.8;
            ArmorStand stand = new ArmorStand(level,
                    sp.getX() + look.x * dist, sp.getEyeY() - 1.85, sp.getZ() + look.z * dist);
            stand.setCustomName(Component.literal("S1mp1e"));
            stand.setCustomNameVisible(true);
            stand.setNoGravity(true);
            level.addFreshEntity(stand);
        } catch (Throwable t) {
            skip("hud name tag spawn", t);
        }
    }

    // ---- flicker sweep (S1MP1E_SHOT_MODE=flicker): consecutive-frame self-ghost check ---------------

    private static final int FLK_SETTLE = 55;   // frames to let the open/fade settle before the consecutive burst
    private static final int FLK_FRAMES = 30;   // consecutive frames captured per surface (flk-<name>-00..29.png)
    private static final int FLK_FLUSH  = 14;   // extra frames after the last capture so every async read-back flushes

    /**
     * Final integrated flicker / self-ghost sweep. For each of three representative new glass surfaces
     * (creative inventory, chat panel, tab list) the scene is opened, given {@link #FLK_SETTLE} frames to render past its
     * ~150-200 ms open fade, then captured on {@link #FLK_FRAMES} <em>consecutive</em> frames. The backdrop is frozen first
     * ({@link #flickerFreezeBackdrop}: clouds and entity shadows off; the camera is already fixed and the world is peaceful),
     * so a settled glass surface has a static thing behind it — any frame-to-frame delta inside the panel would be the glass
     * sampling itself (a self-ghost). The offline diff over these frames is what the contact-sheet report measures. Fully
     * guarded; inert unless {@code S1MP1E_SHOT_MODE=flicker}.
     */
    private static void stepFlicker(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        if (!flickerFrozen) { flickerFreezeBackdrop(client); flickerFrozen = true; }
        // Keep the frame free of the scripted advancement/recipe toasts the world setup triggers.
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}

        switch (flickerStage) {
            case 0: {   // creative inventory forced onto the search tab so items/tabs/scrollbar/search all draw
                if (frames == 0) {
                    try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("flicker rebuild creative tabs", t); }
                    try {
                        java.lang.reflect.Field f = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                        f.setAccessible(true);
                        f.set(null, net.minecraft.world.item.CreativeModeTabs.searchTab());
                    } catch (Throwable t) { skip("flicker preset creative tab", t); }
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "flicker creative");
                }
                if (frames == FLK_SETTLE - 6) {   // re-assert the tab after init() ran
                    try {
                        Screen s = currentScreen(client);
                        if (s instanceof CreativeModeInventoryScreen) {
                            java.lang.reflect.Method m = CreativeModeInventoryScreen.class
                                    .getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                            m.setAccessible(true);
                            m.invoke(s, net.minecraft.world.item.CreativeModeTabs.searchTab());
                        }
                    } catch (Throwable t) { skip("flicker select creative tab", t); }
                }
                if (flickerBurst(client, "creative")) { close(client); flickerStage = 1; }
                return;
            }
            case 1: {   // chat panel, HUD only
                if (frames == 0) {
                    try {
                        client.gui.hud.getChat().clearMessages(true);
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("[S1mp1e] 液態玻璃聊天視窗"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("<Steve> flicker check: this line stays put"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("<Alex> the backdrop refracts, the glass must not"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("玩家加入了遊戲"));
                        client.gui.hud.getChat().addClientSystemMessage(Component.literal("<Steve> readable over any terrain"));
                    } catch (Throwable t) { skip("flicker chat lines", t); }
                }
                if (flickerBurst(client, "chat")) flickerStage = 2;
                return;
            }
            case 2: {   // tab list held open
                if (frames == 0) {
                    try {
                        client.gui.hud.getTabList().setHeader(Component.literal("S1mp1e Liquid Glass"));
                        client.gui.hud.getTabList().setFooter(Component.literal("play.s1mp1e.dev  -  26.2"));
                    } catch (Throwable t) { skip("flicker tab list header/footer", t); }
                    try {
                        MinecraftServer server = client.getSingleplayerServer();
                        if (server != null) {
                            Commands cmd = server.getCommands();
                            CommandSourceStack src = server.createCommandSourceStack();
                            cmd.performPrefixedCommand(src, "scoreboard objectives add s1mp1e_hp dummy \"血量\"");
                            cmd.performPrefixedCommand(src, "scoreboard objectives setdisplay list s1mp1e_hp");
                            cmd.performPrefixedCommand(src, "scoreboard players set @a s1mp1e_hp 20");
                        }
                    } catch (Throwable t) { skip("flicker tab list objective", t); }
                }
                try { client.options.keyPlayerList.setDown(true); } catch (Throwable ignored) {}
                if (flickerBurst(client, "tablist")) {
                    try { client.options.keyPlayerList.setDown(false); } catch (Throwable ignored) {}
                    try {
                        MinecraftServer server = client.getSingleplayerServer();
                        if (server != null) server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack(), "scoreboard objectives remove s1mp1e_hp");
                    } catch (Throwable ignored) {}
                    flickerStage = 3;
                }
                return;
            }
            default:
                close(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    /**
     * Let the surface settle for {@link #FLK_SETTLE} frames, then capture {@link #FLK_FRAMES} consecutive frames
     * ({@code flk-<name>-NN.png}), then hold {@link #FLK_FLUSH} more so the async read-backs flush. Returns true once done.
     */
    private static boolean flickerBurst(Minecraft client, String name) {
        frames++;
        if (frames > FLK_SETTLE && flickerShot < FLK_FRAMES) {
            capture(client, String.format("flk-%s-%02d.png", name, flickerShot));
            flickerShot++;
            return false;
        }
        if (flickerShot >= FLK_FRAMES && frames >= FLK_SETTLE + FLK_FRAMES + FLK_FLUSH) {
            flickerShot = 0;
            frames = 0;
            return true;
        }
        return false;
    }

    /** Freeze the world backdrop (clouds + entity shadows off) so a steady glass surface has a static thing behind it. */
    private static void flickerFreezeBackdrop(Minecraft client) {
        try { client.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF); }
        catch (Throwable t) { skip("flicker clouds off", t); }
        try { client.options.entityShadows().set(Boolean.FALSE); } catch (Throwable ignored) {}
    }

    // ---- modules sweep (S1MP1E_SHOT_MODE=modules): BlockOutline + ChromaHud -------------------------

    private static final int MOD_SECOND_SHOT = 30;   // frames between the two shots of an animated (chroma) scene

    /**
     * Drives the two 26.2-first modules and shoots each look:
     * <ol>
     *   <li>{@code outline.png} — a block placed in front of the camera, crosshair on it, outline in a custom colour at
     *       width 8;</li>
     *   <li>{@code outline-chroma.png} / {@code outline-chroma-2.png} — chroma outline, two shots
     *       {@link #MOD_SECOND_SHOT} frames apart so the hue visibly moved;</li>
     *   <li>{@code outline-fill.png} / {@code outline-fill-f1.png} — custom colour plus the translucent fill, two
     *       consecutive frames (flicker check: the fill is static, so the two must match);</li>
     *   <li>{@code hud-chroma.png} / {@code hud-chroma-2.png} — FPS / coords / CPS / keystrokes (+accents) with the
     *       per-character chroma wave; {@code hud-chroma-flat.png} — wave 0 (one hue for the whole HUD);</li>
     *   <li>{@code config-blockoutline.png} / {@code config-chromahud.png} — each module's settings page.</li>
     * </ol>
     * Every module value touched is snapshotted up front and restored at the end (nothing is saved to disk).
     */
    private static void stepModules(Minecraft client) {
        if (client.player == null || client.gui == null) { modulesRestore(client); frames = 0; phase = P_DRAIN; return; }
        BlockOutlineModule outline = (BlockOutlineModule) ModuleManager.byName("BlockOutline");
        ChromaHudModule chroma = (ChromaHudModule) ModuleManager.byName("ChromaHud");
        if (outline == null || chroma == null) {
            skip("modules sweep", new IllegalStateException("BlockOutline/ChromaHud not registered"));
            frames = 0; phase = P_DRAIN; return;
        }
        // Clean frames: no advancement/recipe toasts or chat lines from the scripted setup.
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}

        switch (modulesStage) {
            case 0: {   // custom colour, width 8
                if (frames == 0) {
                    modulesSnapshot();
                    modulesPlaceTarget(client);
                    outline.setEnabled(true);
                    outline.color.colorValue = 0xFF0A84FF;   // S1mp1e accent blue, opaque
                    outline.width.setDouble(8.0);
                    outline.chroma.boolValue = false;
                    outline.fill.boolValue = false;
                }
                modulesAimAtTarget(client);
                if (advanceHud(client, "outline.png")) modulesStage = 1;
                return;
            }
            case 1: {   // chroma outline, two shots so the hue change is visible
                if (frames == 0) {
                    outline.chroma.boolValue = true;
                    outline.chromaSpeed.setDouble(2.0);
                    outline.width.setDouble(6.0);
                }
                modulesAimAtTarget(client);
                if (modulesTwoShots(client, "outline-chroma.png", "outline-chroma-2.png", MOD_SECOND_SHOT)) modulesStage = 2;
                return;
            }
            case 2: {   // custom colour + translucent fill
                if (frames == 0) {
                    outline.chroma.boolValue = false;
                    outline.color.colorValue = 0xFFFFD60A;   // warm yellow
                    outline.width.setDouble(4.0);
                    outline.fill.boolValue = true;
                    outline.fillColour.colorValue = 0x55FFD60A;
                }
                modulesAimAtTarget(client);
                // two CONSECUTIVE frames: the fill is static, so any difference would be flicker
                if (modulesTwoShots(client, "outline-fill.png", "outline-fill-f1.png", 1)) modulesStage = 3;
                return;
            }
            case 3: {   // chroma HUD, per-character wave + accents
                if (frames == 0) {
                    outline.setEnabled(false);
                    modulesEnableHud(client, true);
                    chroma.setEnabled(true);
                    chroma.speed.setDouble(1.0);
                    chroma.saturation.setDouble(0.75);
                    chroma.wave.setDouble(0.6);
                    chroma.accents.boolValue = true;
                }
                try { client.options.keyShift.setDown(true); } catch (Throwable ignored) {}   // lights a keystroke cap
                if (modulesTwoShots(client, "hud-chroma.png", "hud-chroma-2.png", MOD_SECOND_SHOT)) {
                    try { client.options.keyShift.setDown(false); } catch (Throwable ignored) {}
                    modulesStage = 4;
                }
                return;
            }
            case 4: {   // chroma HUD, wave 0: one hue for everything
                if (frames == 0) chroma.wave.setDouble(0.0);
                if (advanceHud(client, "hud-chroma-flat.png")) modulesStage = 5;
                return;
            }
            case 5: {   // BlockOutline settings page
                if (frames == 0) open(client, new S1mp1eConfigScreen(outline), "open settings on BlockOutline");
                if (modulesConfigShot(client, "config-blockoutline.png")) modulesStage = 6;
                return;
            }
            case 6: {   // ChromaHud settings page
                if (frames == 0) open(client, new S1mp1eConfigScreen(chroma), "open settings on ChromaHud");
                if (modulesConfigShot(client, "config-chromahud.png")) modulesStage = 7;
                return;
            }
            default:
                close(client);
                modulesRestore(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    /** Capture {@code a} at HUD_SETTLE and {@code b} {@code gap} frames later; true once done. */
    private static boolean modulesTwoShots(Minecraft client, String a, String b, int gap) {
        frames++;
        if (frames == HUD_SETTLE) capture(client, a);
        else if (frames == HUD_SETTLE + gap) capture(client, b);
        else if (frames >= HUD_SETTLE + gap + HUD_FLUSH) { frames = 0; return true; }
        return false;
    }

    /** Config pages open with a 150 ms fade; give them CONFIG_FRAMES like the baseline config shots. */
    private static boolean modulesConfigShot(Minecraft client, String name) {
        frames++;
        if (frames == CONFIG_FRAMES) capture(client, name);
        else if (frames >= CONFIG_FRAMES + HUD_FLUSH) { close(client); frames = 0; return true; }
        return false;
    }

    /** Block the outline scenes look at: three blocks south (+Z, yaw 0) of the player's feet. */
    private static int[] modulesTarget;

    private static void modulesPlaceTarget(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) return;
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp == null) return;
            int bx = (int) Math.floor(sp.getX()), by = (int) Math.floor(sp.getY()), bz = (int) Math.floor(sp.getZ()) + 3;
            modulesTarget = new int[] { bx, by, bz };
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "setblock " + bx + " " + by + " " + bz + " minecraft:smooth_stone");
            float[] rot = modulesLookRot(sp.getX(), sp.getEyeY(), sp.getZ());
            sp.setYRot(rot[0]); sp.setXRot(rot[1]); sp.setYHeadRot(rot[0]);
            sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), rot[0], rot[1]);
        } catch (Throwable t) {
            skip("modules place target block", t);
        }
    }

    /** Keep the client camera on the target block's centre (the client re-picks the hit result every frame). */
    private static void modulesAimAtTarget(Minecraft client) {
        if (modulesTarget == null) return;
        try {
            LocalPlayer cp = client.player;
            float[] rot = modulesLookRot(cp.getX(), cp.getEyeY(), cp.getZ());
            cp.setYRot(rot[0]); cp.yRotO = rot[0]; cp.setYHeadRot(rot[0]);
            cp.setXRot(rot[1]); cp.xRotO = rot[1];
        } catch (Throwable t) {
            skip("modules aim at target", t);
        }
    }

    /** {yaw, pitch} in degrees from an eye position to the target block's centre (MC: yaw 0 = +Z, pitch + = down). */
    private static float[] modulesLookRot(double ex, double ey, double ez) {
        double dx = modulesTarget[0] + 0.5 - ex, dy = modulesTarget[1] + 0.5 - ey, dz = modulesTarget[2] + 0.5 - ez;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, h));
        return new float[] { yaw, pitch };
    }

    /** Turn on (or leave as snapshotted) the text HUD modules the chroma scene recolours, laid out so none overlap. */
    private static void modulesEnableHud(Minecraft client, boolean on) {
        if (!on) return;
        String[] names = { "FpsHUD", "CoordsHUD", "CPS", "Keystrokes", "HungerHUD", "XpFlow" };
        for (String n : names) {
            Module m = ModuleManager.byName(n);
            if (m != null) m.setEnabled(true);
        }
        Module cps = ModuleManager.byName("CPS");
        if (cps != null) {
            Setting px = cps.setting("PosX"), py = cps.setting("PosY");
            if (px != null) px.setInt(4);
            if (py != null) py.setInt(48);   // FPS sits at y 4 and coords at y 20; keep CPS clear of both
        }
        Module keys = ModuleManager.byName("Keystrokes");
        if (keys instanceof HudBounds) ((HudBounds) keys).hudSetPos(28, 64);   // clear of the ArmorHUD column
        try {   // a partial XP bar so the XpFlow sheen (an accent) has something to run over
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "experience add @a 10 points");   // level 1 + 3/9 of the bar
        } catch (Throwable t) { skip("modules xp", t); }
    }

    /** Snapshot every setting value + enabled flag of every module (restored by {@link #modulesRestore}). */
    private static void modulesSnapshot() {
        modulesSnapshot = new java.util.HashMap<Setting, Object>();
        modulesEnabledSnapshot = new java.util.HashMap<Module, Boolean>();
        for (Module m : ModuleManager.all()) {
            modulesEnabledSnapshot.put(m, Boolean.valueOf(m.enabled));
            for (Setting s : m.settings) {
                switch (s.type) {
                    case BOOL:   modulesSnapshot.put(s, Boolean.valueOf(s.boolValue));  break;
                    case INT:    modulesSnapshot.put(s, Integer.valueOf(s.intValue));   break;
                    case DOUBLE: modulesSnapshot.put(s, Double.valueOf(s.doubleValue)); break;
                    case COLOR:  modulesSnapshot.put(s, Integer.valueOf(s.colorValue)); break;
                    case MODE:   modulesSnapshot.put(s, s.modeValue);                   break;
                }
            }
        }
    }

    private static void modulesRestore(Minecraft client) {
        try { client.options.keyShift.setDown(false); } catch (Throwable ignored) {}
        if (modulesSnapshot == null) return;
        for (java.util.Map.Entry<Setting, Object> e : modulesSnapshot.entrySet()) {
            Setting s = e.getKey();
            Object v = e.getValue();
            switch (s.type) {
                case BOOL:   s.boolValue = (Boolean) v;   break;
                case INT:    s.intValue = (Integer) v;    break;
                case DOUBLE: s.doubleValue = (Double) v;  break;
                case COLOR:  s.colorValue = (Integer) v;  break;
                case MODE:   s.modeValue = (String) v;    break;
            }
        }
        for (java.util.Map.Entry<Module, Boolean> e : modulesEnabledSnapshot.entrySet())
            e.getKey().setEnabled(e.getValue().booleanValue());
        modulesSnapshot = null;
        modulesEnabledSnapshot = null;
    }

    // ---- tabs sweep (S1MP1E_SHOT_MODE=tabs): the fused glass creative tab row ----------------------

    private static final int TAB_WARM   = 15;   // frames to let the creative game-mode switch reach the client before opening
    private static final int TAB_SETTLE = 45;   // frames rendered before the capture request
    private static final int TAB_FLUSH  = 8;    // extra frames after it for the async read-back

    /**
     * Open the creative inventory with its tab contents actually built and shoot the fused glass tab row twice: once on a
     * normal item tab (Building Blocks — a top-row category tab, so the selected tab merges into the panel top and the whole
     * top+bottom rows are visible), then on the inventory tab (the "operator/inventory" look). Last round the creative shot
     * kept falling back to the survival-inventory look because (1) the tab item lists were never rebuilt in the fresh dev
     * world, so every category tab was empty, and (2) {@code CreativeModeInventoryScreen.init()} resets {@code selectedTab}
     * to {@code getDefaultTab()} and calls {@code selectTab} every time the screen initialises, so a tab set before the
     * screen opened was overwritten. Fixed here by rebuilding {@link net.minecraft.world.item.CreativeModeTabs} contents up
     * front and forcing {@code selectTab(...)} AFTER {@code init()} has run (and re-asserting once). Fully guarded; inert
     * unless {@code S1MP1E_SHOT_MODE=tabs}.
     */
    private static void stepTabs(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        // Keep the frames clean of the scripted advancement/recipe toasts + chat the world setup triggers.
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}

        switch (tabsStage) {
            case 0: {   // a normal item tab: Building Blocks
                if (frames == 0) {
                    // Look steeply down so the WHOLE menu backdrop is terrain (not the bright dusk sky the default
                    // pitch-15 frame puts above the panel). A glass tab refracts whatever is behind it; framing the top
                    // tab row against the same ground the body panel samples is the fair, non-adversarial backdrop for
                    // judging that the tabs are the SAME glass material as the inventory.
                    tabsCameraDown(client);
                    // The creative inventory only opens in the creative game mode; the dev world is survival, so opening
                    // CreativeModeInventoryScreen there falls straight back to the survival InventoryScreen (last round's
                    // symptom). Switch to creative first (server-authoritative + client-local) and build the tab contents.
                    tabsGamemodeCreative(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("tabs rebuild creative contents", t); }
                }
                frames++;
                if (frames == TAB_WARM) {   // creative mode has reached the client; the creative screen will now stay open
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "tabs creative");
                    tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.CATEGORY));   // right after init()
                }
                if (frames == TAB_WARM + TAB_SETTLE - 8) tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.CATEGORY));
                if (frames == TAB_WARM + TAB_SETTLE)          capture(client, "tabs-item.png");
                else if (frames >= TAB_WARM + TAB_SETTLE + TAB_FLUSH) { tabsStage = 1; frames = 0; }
                return;
            }
            case 1: {   // the inventory / operator tab
                if (frames == 0) tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.INVENTORY));
                frames++;
                if (frames == TAB_SETTLE)          capture(client, "tabs-inventory.png");
                else if (frames >= TAB_SETTLE + TAB_FLUSH) { tabsStage = 2; frames = 0; }
                return;
            }
            case 2: {   // selected pill SLIDE within the top row: column 0 -> column 4 (hotbar-style two-spring slide)
                if (frames == 0) { ttHoverX = ttHoverY = -1; tabsSelect(client, tabsAt(true, 0)); }
                frames++;
                if (frames == TAB_SETTLE) tabsSelect(client, tabsAt(true, 4));
                if (frames > TAB_SETTLE && frames <= TAB_SETTLE + TAB_MOTION)
                    capture(client, String.format("tabs-slide-%02d.png", frames - TAB_SETTLE - 1));
                else if (frames >= TAB_SETTLE + TAB_MOTION + TAB_FLUSH) { tabsStage = 3; frames = 0; }
                return;
            }
            case 3: {   // row switch top -> bottom: old pill fades out, new pill fades in (no drag across the body)
                frames++;
                if (frames == 1) tabsSelect(client, tabsAt(false, 1));
                if (frames >= 1 && frames <= TAB_MOTION) capture(client, String.format("tabs-cross-%02d.png", frames - 1));
                else if (frames >= TAB_MOTION + TAB_FLUSH) { tabsStage = 4; frames = 0; }
                return;
            }
            case 4: {   // hover pill: fade in on top col 1, glide to col 3, fade out off the band (slot-hover motion)
                Screen s = currentScreen(client);
                frames++;
                if (s instanceof CreativeModeInventoryScreen) {
                    com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                            (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
                    float cw = acc.liquidglass$imageWidth() / 7.0F;
                    double bandY = acc.liquidglass$topPos() - 14;
                    if (frames == 1)                 { ttHoverX = acc.liquidglass$leftPos() + 1.5 * cw; ttHoverY = bandY; }
                    if (frames == 1 + TAB_MOTION)    { ttHoverX = acc.liquidglass$leftPos() + 3.5 * cw; ttHoverY = bandY; }
                    if (frames == 1 + 2 * TAB_MOTION){ ttHoverX = acc.liquidglass$leftPos() + 3.5 * cw; ttHoverY = acc.liquidglass$topPos() + 60; }
                    ttApplyHover(client);
                }
                if (frames >= 1 && frames <= 3 * TAB_MOTION) {
                    int seg = (frames - 1) / TAB_MOTION, i = (frames - 1) % TAB_MOTION;
                    capture(client, String.format("tabs-hover-%s-%02d.png", seg == 0 ? "in" : seg == 1 ? "glide" : "out", i));
                } else if (frames >= 3 * TAB_MOTION + TAB_FLUSH) { ttHoverX = ttHoverY = -1; tabsStage = 5; frames = 0; }
                return;
            }
            default:
                close(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    /** Put the dev player into creative (server-authoritative via the command dispatcher, plus the client-local mode). */
    private static void tabsGamemodeCreative(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode creative @a");
            }
        } catch (Throwable t) { skip("tabs gamemode creative (server)", t); }
        try {
            client.gameMode.setLocalMode(GameType.CREATIVE);
        } catch (Throwable t) { skip("tabs gamemode creative (client)", t); }
    }

    private static void ingGamemodeSurvival(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode survival @a");
            }
        } catch (Throwable t) { skip("ingame gamemode survival (server)", t); }
        try {
            client.gameMode.setLocalMode(GameType.SURVIVAL);
        } catch (Throwable t) { skip("ingame gamemode survival (client)", t); }
    }

    /** Find the {@link net.minecraft.client.gui.screens.recipebook.RecipeBookComponent} the given screen holds (scanning the
     *  screen and its superclasses), so the sweep can drive its open/close without simulating the exact button geometry. */
    private static Object ingFindBook(Screen s) {
        Class<?> c = s.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.isAssignableFrom(f.getType())) {
                    try { f.setAccessible(true); Object v = f.get(s); if (v != null) return v; } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void ingInvoke(Object o, String method) {
        if (o == null) { skip("ingame invoke " + method, new IllegalStateException("null target")); return; }
        Class<?> c = o.getClass();
        while (c != null) {
            try { java.lang.reflect.Method m = c.getDeclaredMethod(method); m.setAccessible(true); m.invoke(o); return; }
            catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            catch (Throwable t) { skip("ingame invoke " + method, t); return; }
        }
        skip("ingame invoke " + method, new IllegalStateException("no such method"));
    }

    private static void ingInvoke1(Object o, String method, Class<?> type, Object arg) {
        if (o == null) { skip("ingame invoke " + method, new IllegalStateException("null target")); return; }
        Class<?> c = o.getClass();
        while (c != null) {
            try { java.lang.reflect.Method m = c.getDeclaredMethod(method, type); m.setAccessible(true); m.invoke(o, arg); return; }
            catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            catch (Throwable t) { skip("ingame invoke " + method, t); return; }
        }
        skip("ingame invoke " + method, new IllegalStateException("no such method"));
    }

    /** Find the {@link net.minecraft.client.gui.components.EditBox} a screen holds (the chat input, a rename field, …). */
    private static Object ingFindEditBox(Screen s) {
        Class<?> c = s.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (net.minecraft.client.gui.components.EditBox.class.isAssignableFrom(f.getType())) {
                    try { f.setAccessible(true); Object v = f.get(s); if (v != null) return v; } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * In-gameplay screen-switch cross-dissolves (env {@code S1MP1E_SHOT_MODE=ingame}). Three sub-stages: (0) creative
     * category-tab switch, (1) survival recipe-book open then close, (2) load the advancements screen to validate its tab
     * hook. Each captures a {@code -pre} frame then early frames. The very act of opening each screen makes mixin
     * {@code require:1} validate the four new @Inject targets resolved.
     */
    private static void stepIngame(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        if (ingStage != 8) { try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {} }   // stage 8 tests the chat itself

        final int OPEN = TAB_WARM, PRE = OPEN + 18, SWITCH = PRE + 1, POST = 14;

        switch (ingStage) {
            case 0: {   // CREATIVE category-tab switch: col 1 -> col 4
                if (frames == 0) {
                    tabsCameraDown(client);
                    tabsGamemodeCreative(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("ingame creative rebuild", t); }
                }
                frames++;
                if (frames == OPEN) {
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "ingame creative");
                    tabsSelect(client, tabsAt(true, 1));
                }
                if (frames == PRE)    capture(client, "cre-pre.png");
                if (frames == SWITCH) tabsSelect(client, tabsAt(true, 4));
                if (frames > SWITCH && frames <= SWITCH + POST) capture(client, String.format("cre-f%02d.png", frames - SWITCH));
                else if (frames >= SWITCH + POST + TAB_FLUSH) { close(client); ingStage = 1; frames = 0; }
                return;
            }
            case 1: {   // SURVIVAL recipe book: open (dissolve) then close (dissolve)
                if (frames == 0) ingGamemodeSurvival(client);
                frames++;
                if (frames == OPEN) {
                    open(client, new net.minecraft.client.gui.screens.inventory.InventoryScreen(player), "ingame survival inv");
                    Screen s = currentScreen(client);
                    ingBook = s == null ? null : ingFindBook(s);
                }
                if (frames == OPEN + 12) capture(client, "rb-pre.png");            // book closed
                if (frames == OPEN + 13) ingInvoke(ingBook, "toggleVisibility");    // OPEN book
                if (frames > OPEN + 13 && frames <= OPEN + 13 + POST) capture(client, String.format("rb-open-f%02d.png", frames - (OPEN + 13)));
                if (frames == OPEN + 13 + POST + 4) ingInvoke(ingBook, "toggleVisibility");  // CLOSE book
                if (frames > OPEN + 13 + POST + 4 && frames <= OPEN + 13 + POST + 4 + POST)
                    capture(client, String.format("rb-close-f%02d.png", frames - (OPEN + 13 + POST + 4)));
                else if (frames >= OPEN + 13 + POST + 4 + POST + TAB_FLUSH) { close(client); ingStage = 2; frames = 0; }
                return;
            }
            case 2: {   // ADVANCEMENTS: force class load so its new tab-switch @Inject is validated by require:1
                frames++;
                if (frames == 1) {
                    try {
                        Class.forName("net.minecraft.client.gui.screens.advancements.AdvancementsScreen", true,
                                DevShot.class.getClassLoader());
                        System.out.println("[S1mp1e][DevShot] AdvancementsScreen loaded — tab-switch inject applied OK");
                    } catch (Throwable t) { skip("ingame advancements class-load", t); }
                }
                if (frames >= 6) { ingStage = 3; frames = 0; }
                return;
            }
            case 3: {   // CHAT: Apple caret — type text (end caret), then jump the cursor to show the glide
                frames++;
                if (frames == 1) open(client, new net.minecraft.client.gui.screens.ChatScreen("", false), "ingame chat");
                if (frames == 5) {
                    Screen s = currentScreen(client);
                    ingChatBox = s == null ? null : ingFindEditBox(s);
                    if (ingChatBox != null) {
                        ingInvoke1(ingChatBox, "setValue", String.class, "s1mp1e liquid glass");
                        ingInvoke1(ingChatBox, "setFocused", boolean.class, Boolean.TRUE);
                        ingInvoke1(ingChatBox, "moveCursorToEnd", boolean.class, Boolean.FALSE);
                    }
                }
                if (frames == 8)  capture(client, "caret-end.png");                 // caret bar at end of text
                if (frames == 9 && ingChatBox != null)
                    ingInvoke1(ingChatBox, "setCursorPosition", int.class, Integer.valueOf(3));   // jump → glide
                if (frames >= 10 && frames <= 15) capture(client, String.format("caret-glide-f%02d.png", frames - 9));
                if (frames == 20) close(client);   // close chat → ChatCloseFade.begin() → HUD ghost fades the input bar
                if (frames >= 21 && frames <= 28) capture(client, String.format("chatclose-f%02d.png", frames - 20));
                else if (frames >= 32) { ingStage = 4; frames = 0; }
                return;
            }
            case 4: {   // TAB LIST: hold the player-list key (fade in), then release (fade OUT instead of pop)
                frames++;
                if (frames == 1) {
                    // The list only renders when held AND (multiplayer OR a list-slot objective exists). In the dev
                    // singleplayer world neither holds, so add a list objective to make it show — the real scenario the
                    // fade targets. (Nothing to fade in true 1-player singleplayer; vanilla never shows it there.)
                    try {
                        MinecraftServer server = client.getSingleplayerServer();
                        if (server != null) {
                            var src = server.createCommandSourceStack();
                            server.getCommands().performPrefixedCommand(src, "scoreboard objectives add s1tl dummy Players");
                            server.getCommands().performPrefixedCommand(src, "scoreboard objectives setdisplay list s1tl");
                        }
                    } catch (Throwable t) { skip("ingame tablist objective", t); }
                }
                if (frames == 3)  { try { client.options.keyPlayerList.setDown(true); } catch (Throwable t) { skip("ingame tablist down", t); } }
                if (frames == 10) capture(client, "tab-in.png");                    // faded in, holding
                if (frames == 11) { try { client.options.keyPlayerList.setDown(false); } catch (Throwable t) { skip("ingame tablist up", t); } }
                if (frames >= 12 && frames <= 20) capture(client, String.format("tab-out-f%02d.png", frames - 11));  // fade-out
                else if (frames >= 26) { ingStage = 5; frames = 0; }
                return;
            }
            case 5: {   // SCOREBOARD sidebar: set on the sidebar slot (fade in), then clear the slot (fade OUT)
                frames++;
                if (frames == 1) {
                    ingCmd(client, "scoreboard objectives add s1sb dummy Sidebar");
                    ingCmd(client, "scoreboard players set Alpha s1sb 7");
                    ingCmd(client, "scoreboard players set Bravo s1sb 4");
                    ingCmd(client, "scoreboard players set Charlie s1sb 2");
                    ingCmd(client, "scoreboard objectives setdisplay sidebar s1sb");
                }
                if (frames >= 3 && frames <= 9) capture(client, String.format("sb-in-f%02d.png", frames - 2));    // fade in
                if (frames == 12) ingCmd(client, "scoreboard objectives setdisplay sidebar");                      // clear slot -> fade out
                if (frames >= 13 && frames <= 21) capture(client, String.format("sb-out-f%02d.png", frames - 12)); // fade out
                else if (frames >= 27) { ingCmd(client, "scoreboard objectives remove s1sb"); ingStage = 6; frames = 0; }
                return;
            }
            case 6: {   // BOSS BAR: add + show (fade in), then remove (ghost fade OUT instead of pop)
                frames++;
                if (frames == 1) {
                    ingCmd(client, "bossbar add s1:boss \"Ender Dragon\"");
                    ingCmd(client, "bossbar set s1:boss players @a");
                    ingCmd(client, "bossbar set s1:boss color purple");
                    ingCmd(client, "bossbar set s1:boss max 100");
                    ingCmd(client, "bossbar set s1:boss value 70");
                    ingCmd(client, "bossbar set s1:boss visible true");
                }
                if (frames >= 4 && frames <= 10) capture(client, String.format("boss-in-f%02d.png", frames - 3));   // fade in
                if (frames == 13) ingCmd(client, "bossbar remove s1:boss");                                          // remove -> ghost fade out
                if (frames >= 14 && frames <= 22) capture(client, String.format("boss-out-f%02d.png", frames - 13)); // fade out
                else if (frames >= 28) { ingStage = 7; frames = 0; }
                return;
            }
            case 7: {   // TYPING: per-glyph entrance / burst / mid-insert glide / backspace exit / replace roll / select / scroll
                frames++;
                if (frames == 1) open(client, new net.minecraft.client.gui.screens.ChatScreen("", false), "ingame typing");
                if (frames == 4) {
                    Screen s = currentScreen(client);
                    ingChatBox = s == null ? null : ingFindEditBox(s);
                    if (ingChatBox != null) ((net.minecraft.client.gui.components.EditBox) ingChatBox).setFocused(true);
                }
                net.minecraft.client.gui.components.EditBox box =
                        ingChatBox instanceof net.minecraft.client.gui.components.EditBox eb ? eb : null;
                if (box == null) { if (frames >= 6) { close(client); ingStage = 8; frames = 0; } return; }
                // Everything below is scheduled in MILLISECONDS from the stage start (frame-rate independent). Each
                // capture is of the frame just rendered; with TypingAnim.debugLog the log pairs capture times with the
                // animation's own progress for that frame.
                if (tyEvents == null) { tyEvents = tySchedule(client, box); tyNext = 0; tyT0 = System.currentTimeMillis(); }
                long t = System.currentTimeMillis() - tyT0;
                boolean shot = false;   // at most ONE capture per rendered frame (a hitch must not stack several on one frame)
                while (tyNext < tyEvents.size() && tyEvents.get(tyNext).t() <= t) {
                    TyEv e = tyEvents.get(tyNext);
                    if (e.shot() && shot) break;
                    e.run().run();
                    tyNext++;
                    shot |= e.shot();
                }
                if (tyNext >= tyEvents.size()) {
                    com.seagull.liquidglass.client.render.TypingAnim.timeScale = 1F;
                    com.seagull.liquidglass.client.render.TypingAnim.debugLog = false;
                    tyEvents = null;
                    close(client); ingStage = 8; frames = 0;
                }
                return;
            }
            case 8: {   // CHAT: arrivals (rise + panel grows), suggestion popup (fade in / glide / fade out), close text lift
                frames++;
                if (frames == 1) { close(client); return; }
                if (frames < 4) return;
                if (tyEvents == null) { tyEvents = chSchedule(client); tyNext = 0; tyT0 = System.currentTimeMillis(); }
                long t = System.currentTimeMillis() - tyT0;
                boolean shot = false;
                while (tyNext < tyEvents.size() && tyEvents.get(tyNext).t() <= t) {
                    TyEv e = tyEvents.get(tyNext);
                    if (e.shot() && shot) break;
                    e.run().run();
                    tyNext++;
                    shot |= e.shot();
                }
                if (tyNext >= tyEvents.size()) { tyEvents = null; close(client); ingStage = 9; frames = 0; }
                return;
            }
            case 9: {   // SIGN + BOOK typing
                frames++;
                if (frames < 3) return;
                if (tyEvents == null) { tyEvents = sbSchedule(client); tyNext = 0; tyT0 = System.currentTimeMillis(); }
                long t = System.currentTimeMillis() - tyT0;
                boolean shot = false;
                while (tyNext < tyEvents.size() && tyEvents.get(tyNext).t() <= t) {
                    TyEv e = tyEvents.get(tyNext);
                    if (e.shot() && shot) break;
                    e.run().run();
                    tyNext++;
                    shot |= e.shot();
                }
                if (tyNext >= tyEvents.size()) { tyEvents = null; close(client); ingStage = 10; frames = 0; }
                return;
            }
            case 10: {   // HEALTH trail + ITEM flights
                frames++;
                if (frames < 3) return;
                if (tyEvents == null) { tyEvents = hfSchedule(client); tyNext = 0; tyT0 = System.currentTimeMillis(); }
                long t = System.currentTimeMillis() - tyT0;
                boolean shot = false;
                while (tyNext < tyEvents.size() && tyEvents.get(tyNext).t() <= t) {
                    TyEv e = tyEvents.get(tyNext);
                    if (e.shot() && shot) break;
                    e.run().run();
                    tyNext++;
                    shot |= e.shot();
                }
                if (tyNext >= tyEvents.size()) { tyEvents = null; close(client); ingStage = 11; frames = 0; }
                return;
            }
            case 11: {   // RECIPE BOOK: slide out from behind the inventory, per-item cascade on open / page / tab, slide back
                frames++;
                if (frames < 3) return;
                if (tyEvents == null) { tyEvents = rbSchedule(client); tyNext = 0; tyT0 = System.currentTimeMillis(); }
                long t = System.currentTimeMillis() - tyT0;
                boolean shot = false;
                while (tyNext < tyEvents.size() && tyEvents.get(tyNext).t() <= t) {
                    TyEv e = tyEvents.get(tyNext);
                    if (e.shot() && shot) break;
                    e.run().run();
                    tyNext++;
                    shot |= e.shot();
                }
                if (tyNext >= tyEvents.size()) { tyEvents = null; close(client); ingStage = 12; frames = 0; }
                return;
            }
            case 12: {   // SF SYMBOLS: every replaced glyph sprite, vanilla vs Apple, normal + highlighted
                frames++;
                if (frames == 2) open(client, new IconGallery(), "sf icon gallery");
                if (frames == 45) capture(client, "icons.png");
                if (frames >= 48) { close(client); ingStage = 13; frames = 0; }
                return;
            }
            case 13: {   // SODIUM / REESE'S SODIUM OPTIONS video settings (only when those mods are present)
                frames++;
                if (frames == 2) {
                    try {
                        Class<?> c = Class.forName("me.flashyreese.mods.reeses_sodium_options.client.gui.SodiumVideoOptionsScreen");
                        Screen s = (Screen) c.getConstructor(Screen.class).newInstance((Screen) null);
                        open(client, s, "sodium options");
                    } catch (Throwable t) { skip("sodium options screen", t); ingStage = 14; frames = 0; return; }
                }
                // GUI coords at the dev window's scale 2 (640x360): slider row "最大 FPS", boolean row "自動儲存指示器",
                // Sodium Extra page "動畫" in the rail.
                if (frames < 46) sodPark(client, 320, 352);
                else if (frames < 82) sodPark(client, 400, 184);
                else if (frames < 141) sodPark(client, 400, 224);
                else sodPark(client, 300, 77);                       // Sodium Extra 動畫 → "水" row (tooltip)
                if (frames == 45) capture(client, "sodium-open.png");
                if (frames == 80) capture(client, "sodium-hover-slider.png");
                if (frames == 110) capture(client, "sodium-hover-bool.png");
                if (frames == 111) rbClickAt(client, 400, 224, "sodium bool row");
                if (frames == 113 || frames == 116 || frames == 120 || frames == 130) capture(client, "sodium-switch-" + frames + ".png");
                if (frames == 140) rbClickAt(client, 35, 156, "sodium extra page");
                if (frames == 142 || frames == 146 || frames == 152 || frames == 175) capture(client, "sodium-page-" + frames + ".png");
                if (frames == 300) capture(client, "sodium-tooltip.png");
                // smooth scroll + scrollbar: Quality has enough options to overflow at 720p; wheel down, capture the ease
                if (frames == 305) rbClickAt(client, 60, 95, "sodium quality tab");
                if (frames >= 330) sodPark(client, 360, 200);
                if (frames == 335) { Screen s = currentScreen(client); if (s != null) s.mouseScrolled(360, 200, 0, -4); }
                if (frames >= 336 && frames <= 352) capture(client, String.format("sodium-scroll-%02d.png", frames - 335));
                if (frames >= 356) { close(client); ingStage = 14; frames = 0; }
                return;
            }
            case 14: {   // SETTINGS SHELL: every vanilla settings page in the Video Settings layout (SettingsShell)
                frames++;
                if (frames == 2) open(client, shellRoot(client), "settings shell");
                // grid: sidebar entries at x 60, y 47 + 4 + i*18 + 9; first content row at y 47 + 9
                if (frames < 50) sodPark(client, 400, 56);
                if (frames == 20 || frames == 24 || frames == 28 || frames == 34) capture(client, "st-main-slider-" + frames + ".png");
                if (frames == 45) capture(client, "st-main.png");
                int k = (frames - 50) / 40, ph = (frames - 50) % 40;
                if (frames >= 50 && k < 11) {
                    if (ph == 0) { rbClickAt(client, 60, 47 + 4 + (k + 1) * 18 + 9, "settings tab " + (k + 1)); sodPark(client, 400, 56); }
                    if (ph == 2 || ph == 5 || ph == 9) capture(client, "st-tab" + (k + 1) + "-in" + ph + ".png");
                    if (ph == 34) capture(client, "st-tab" + (k + 1) + ".png");
                    if (ph == 36) {                              // a category that leaves the settings (packs, credits…): come back
                        Screen cur = currentScreen(client);
                        if (!(cur instanceof net.minecraft.client.gui.screens.options.OptionsScreen)
                                && !(cur instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen)) {
                            open(client, shellRoot(client), "settings shell again");
                        }
                    }
                    return;
                }
                int f = frames - 490;
                if (f == 1) {
                    Screen root = shellRoot(client);
                    open(client, new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(root, client.options), "settings keys");
                }
                if (f == 36) capture(client, "st-keys.png");
                if (f == 38) sodPark(client, 400, 150);
                if (f == 40) dev.s1mp1e.client.gui.SettingsShell.wheel(currentScreen(client), 400, 150, -4);
                if (f == 70) capture(client, "st-keys-scrolled.png");
                if (f == 72) {
                    Screen root = shellRoot(client);
                    open(client, new net.minecraft.client.gui.screens.options.LanguageSelectScreen(root, client.options, client.getLanguageManager()), "settings language");
                }
                if (f == 110) capture(client, "st-language.png");
                if (f == 111) {                                  // the search box refills the list: the rows must follow
                    Screen cur = currentScreen(client);
                    if (cur != null) for (Object c : cur.children()) {
                        if (c instanceof net.minecraft.client.gui.components.EditBox eb) { cur.setFocused(eb); eb.setValue("English"); }
                    }
                }
                if (f == 126) capture(client, "st-language-search.png");
                f -= 20;
                if (f == 112) {
                    Screen root = shellRoot(client);
                    open(client, new net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen(root, client.options), "settings accessibility");
                    sodPark(client, 400, 47 + 18 + 9);
                }
                if (f == 150) capture(client, "st-access.png");
                if (f == 151) rbClickAt(client, 400, 47 + 18 + 9, "settings row click");
                if (f == 153 || f == 156 || f == 160 || f == 170) capture(client, "st-access-click-" + f + ".png");
                if (f == 172) rbClickAt(client, 400, 47 + 18 + 9, "settings row click back");
                if (f == 172 + 10) open(client, new net.minecraft.client.gui.screens.options.SoundOptionsScreen(shellRoot(client), client.options), "settings sound");
                // drag the master-volume pill (right end of its track) to the left, frame by frame, then let go
                int df = f - 215;
                if (df == 0) { shellDragX = shellPillX(client); sodPark(client, shellDragX, 56); }
                if (df == 2) { dev.s1mp1e.client.gui.VanillaSliderSkin.devMouseDown = true; shellMouse(client, 0, shellDragX, 56); }
                if (df >= 3 && df < 17) {
                    double x = shellDragX - 6.0 * (df - 2);
                    sodPark(client, x, 56);
                    shellMouse(client, 1, x, 56);
                    capture(client, String.format("st-drag-%02d.png", df - 3));
                }
                if (df == 17) { shellMouse(client, 2, shellDragX - 84, 56); dev.s1mp1e.client.gui.VanillaSliderSkin.devMouseDown = false; }
                if (df > 17 && df <= 25) capture(client, String.format("st-drag-r%02d.png", df - 17));
                // a cycle row's value rolls: Skin Customization, last row = main hand
                if (df == 27) open(client, new net.minecraft.client.gui.screens.options.SkinCustomizationScreen(shellRoot(client), client.options), "settings skin");
                if (df == 60) rbClickAt(client, 400, 47 + 7 * 18 + 9, "settings cycle row");
                if (df > 60 && df <= 72) capture(client, String.format("st-roll-%02d.png", df - 61));
                if (df == 74) rbClickAt(client, 400, 47 + 7 * 18 + 9, "settings cycle row back");
                if (df >= 90) { close(client); ingStage = 15; frames = 0; }
                return;
            }
            default:
                close(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    private static double shellDragX;

    /** Screen x of the first slider row's pill at its current value (grid: right edge 16 + 6, value column, 14 gap). */
    private static double shellPillX(Minecraft client) {
        Screen s = currentScreen(client);
        if (s != null) for (Object c : s.children()) {
            if (c instanceof net.minecraft.client.gui.components.AbstractSliderButton sl) return sl.getX() + sl.getWidth() - 9;
        }
        return client.getWindow().getGuiScaledWidth() - 16 - 6 - 34 - 14;
    }

    /** kind 0 press / 1 drag / 2 release on the current screen at GUI (x, y). */
    private static void shellMouse(Minecraft client, int kind, double x, double y) {
        try {
            Screen s = currentScreen(client);
            if (s == null) return;
            net.minecraft.client.input.MouseButtonEvent ev = new net.minecraft.client.input.MouseButtonEvent(x, y,
                    new net.minecraft.client.input.MouseButtonInfo(0, 0));
            if (kind == 0) s.mouseClicked(ev, false);
            else if (kind == 1) s.mouseDragged(ev, 0, 0);
            else s.mouseReleased(ev);
        } catch (Throwable t) { skip("settings mouse", t); }
    }

    private static Screen shellRoot(Minecraft client) {
        return new net.minecraft.client.gui.screens.options.OptionsScreen(
                new net.minecraft.client.gui.screens.PauseScreen(true), client.options, true);
    }

    private static void ingCmd(Minecraft client, String cmd) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
        } catch (Throwable t) { skip("ingame cmd: " + cmd, t); }
    }

    // ---- lists sweep --------------------------------------------------------------------------------------------
    private static net.minecraft.client.gui.components.AbstractSelectionList<?> lsList;

    private static void stepLists(Minecraft client) {
        if (lsPhase > 0) { stepListsLoad(client); return; }
        frames++;
        if (frames == 1) {
            open(client, new net.minecraft.client.gui.screens.options.LanguageSelectScreen(
                    currentScreen(client), client.options, client.getLanguageManager()), "lists language");
            return;
        }
        if (frames < 6) return;
        if (tyEvents == null) {
            Screen s = currentScreen(client);
            lsList = null;
            if (s != null) for (Object c : s.children())
                if (c instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> l) { lsList = l; break; }
            if (lsList == null) { skip("lists: no selection list", new IllegalStateException()); phase = P_DRAIN; return; }
            tyEvents = lsSchedule(client, lsList);
            tyNext = 0;
            tyT0 = System.currentTimeMillis();
        }
        long t = System.currentTimeMillis() - tyT0;
        boolean shot = false;
        while (tyNext < tyEvents.size() && tyEvents.get(tyNext).t() <= t) {
            TyEv e = tyEvents.get(tyNext);
            if (e.shot() && shot) break;
            e.run().run();
            tyNext++;
            shot |= e.shot();
        }
        if (tyNext >= tyEvents.size()) { tyEvents = null; lsPhase = 1; lsFrames = 0; }
    }

    // ---- lists sweep, part 2/3: the world list's async load and a server ping result, one capture per frame ----
    private static int lsPhase, lsFrames;

    private static void stepListsLoad(Minecraft client) {
        lsFrames++;
        if (lsPhase == 1) {                                   // worlds: entries arriving after the async load
            if (lsFrames == 1) open(client, new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(
                    new net.minecraft.client.gui.screens.TitleScreen()), "lists worlds");
            if (lsFrames >= 2 && lsFrames <= 16) capture(client, String.format("ls-wl-%02d.png", lsFrames - 1));
            if (lsFrames >= 22) { lsList = lsFindList(currentScreen(client)); }   // hover the first world row's icon
            if (lsFrames >= 22 && lsList != null) {
                java.util.List<?> ch = lsList.children();
                if (!ch.isEmpty() && ch.get(0) instanceof net.minecraft.client.gui.layouts.LayoutElement e) {
                    sodPark(client, e.getX() + 16, e.getY() + 18);
                }
            }
            if (lsFrames == 30) capture(client, "ls-wl-hover.png");
            if (lsFrames >= 34) { lsPhase = 2; lsFrames = 0; }
            return;
        }
        if (lsPhase == 2) {                                   // servers: an unreachable localhost entry's ping result
            if (lsFrames == 1) {
                try {
                    net.minecraft.client.multiplayer.ServerList sl = new net.minecraft.client.multiplayer.ServerList(client);
                    sl.load();
                    if (sl.size() == 0) {
                        sl.add(new net.minecraft.client.multiplayer.ServerData("S1mp1e Test", "127.0.0.1:1",
                                net.minecraft.client.multiplayer.ServerData.Type.OTHER), false);
                        sl.save();
                    }
                } catch (Throwable t) { skip("lists add test server", t); }
                open(client, new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(
                        new net.minecraft.client.gui.screens.TitleScreen()), "lists servers");
            }
            if (lsFrames >= 2 && lsFrames <= 60) capture(client, String.format("ls-sv-%02d.png", lsFrames - 1));
            if (lsFrames >= 64) { lsPhase = 3; lsFrames = 0; }
            return;
        }
        if (lsPhase == 3) {                                   // controls: a real click on a cycle button (view bobbing)
            if (lsFrames == 1) open(client, new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                    new net.minecraft.client.gui.screens.TitleScreen(), client, client.options), "lists video");
            if (lsFrames == 8) { lsCycle = lsFindCycle(currentScreen(client)); if (lsCycle == null) skip("lists: no cycle button", new IllegalStateException()); }
            if (lsFrames == 10) lsClick(client, lsCycle);                                   // toggle -> pulse + roll
            if (lsFrames >= 10 && lsFrames <= 26) { lsLogCycle(); capture(client, String.format("ls-cb-%02d.png", lsFrames - 9)); }
            if (lsFrames == 34) lsClick(client, lsCycle);                                   // toggle back (restore the setting)
            if (lsFrames >= 34 && lsFrames <= 50) { lsLogCycle(); capture(client, String.format("ls-cc-%02d.png", lsFrames - 33)); }
            if (lsFrames >= 56) { lsPhase = 4; lsFrames = 0; }
            return;
        }
        if (lsPhase == 4) {                                   // glass over NO world: Sodium options + our config screen
            if (lsFrames == 1) {
                try {
                    Class<?> c = Class.forName("me.flashyreese.mods.reeses_sodium_options.client.gui.SodiumVideoOptionsScreen");
                    open(client, (Screen) c.getConstructor(Screen.class).newInstance((Screen) null), "lists sodium (no world)");
                } catch (Throwable t) { skip("lists sodium", t); }
            }
            if (lsFrames == 20) capture(client, "ls-noworld-sodium.png");
            if (lsFrames == 24) open(client, new dev.s1mp1e.client.gui.S1mp1eConfigScreen(), "lists config (no world)");
            if (lsFrames == 40) capture(client, "ls-noworld-config.png");
            if (lsFrames >= 44) { lsPhase = 5; lsFrames = 0; ldIdx = 0; ldScenes = null; }
            return;
        }
        if (lsPhase == 5) {                                   // loading screens: liquid loader, time-spaced strips
            if (ldScenes == null) ldScenes = ldBuildScenes();
            if (ldIdx >= ldScenes.size()) { lsPhase = 0; close(client); frames = 0; phase = P_DRAIN; return; }
            Object[] sc = ldScenes.get(ldIdx);
            String name = (String) sc[0];
            if (lsFrames == 1) {
                Screen s = null;
                try {
                    @SuppressWarnings("unchecked") Supplier<Screen> sup = (Supplier<Screen>) sc[1];
                    s = sup.get();
                } catch (Throwable t) { skip("loader scene " + name, t); }
                if (s == null) { ldIdx++; lsFrames = 0; return; }
                open(client, s, "loader " + name);
                ldT0 = 0L; ldShot = 0; ldMidDone = false;
            }
            if (lsFrames < 30) return;                        // let the screen cross-dissolve finish
            long now = System.nanoTime();
            if (ldT0 == 0L) ldT0 = now;
            if (!ldMidDone && sc[2] != null && ldShot >= 12) { ((Runnable) sc[2]).run(); ldMidDone = true; }
            if (ldShot < 24 && (now - ldT0) / 1_000_000L >= ldShot * 70L) {
                capture(client, String.format("ld-%s-%02d.png", name, ldShot++));
            }
            if (ldShot >= 24) { ldIdx++; lsFrames = 0; }
        }
    }

    private static java.util.List<Object[]> ldScenes;
    private static int ldIdx, ldShot;
    private static long ldT0;
    private static boolean ldMidDone;

    /** {name, Supplier<Screen>, Runnable run at strip frame 12 (or null)} — every text loading screen we restyled. */
    private static java.util.List<Object[]> ldBuildScenes() {
        java.util.List<Object[]> l = new java.util.ArrayList<>();
        net.minecraft.network.chat.Component T = net.minecraft.network.chat.Component.literal("正在載入世界");
        l.add(new Object[]{"prog0", (Supplier<Screen>) () -> {
            net.minecraft.client.gui.screens.ProgressScreen ps = new net.minecraft.client.gui.screens.ProgressScreen(false);
            ps.progressStartNoAbort(T);
            return ps;
        }, null});
        final net.minecraft.client.gui.screens.ProgressScreen[] pRef = new net.minecraft.client.gui.screens.ProgressScreen[1];
        l.add(new Object[]{"prog", (Supplier<Screen>) () -> {
            net.minecraft.client.gui.screens.ProgressScreen ps = new net.minecraft.client.gui.screens.ProgressScreen(false);
            ps.progressStartNoAbort(T);
            ps.progressStage(net.minecraft.network.chat.Component.literal("建立地形"));
            ps.progressStagePercentage(30);
            pRef[0] = ps;
            return ps;
        }, (Runnable) () -> { if (pRef[0] != null) pRef[0].progressStagePercentage(75); }});
        l.add(new Object[]{"wait", (Supplier<Screen>) () ->
                net.minecraft.client.gui.screens.GenericWaitingScreen.createWaitingWithoutButton(
                        net.minecraft.network.chat.Component.literal("請稍候"),
                        net.minecraft.network.chat.Component.literal("正在把你的世界傳送到伺服器，這可能需要一點時間。")), null});
        l.add(new Object[]{"conn", (Supplier<Screen>) () -> {
            try {
                java.lang.reflect.Constructor<net.minecraft.client.gui.screens.ConnectScreen> c =
                        net.minecraft.client.gui.screens.ConnectScreen.class.getDeclaredConstructor(Screen.class, net.minecraft.network.chat.Component.class);
                c.setAccessible(true);
                return c.newInstance(new net.minecraft.client.gui.screens.TitleScreen(), net.minecraft.network.chat.Component.literal("連線到伺服器"));
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        }, null});
        l.add(new Object[]{"level", (Supplier<Screen>) () -> new net.minecraft.client.gui.screens.LevelLoadingScreen(
                new net.minecraft.client.multiplayer.LevelLoadTracker(), net.minecraft.client.gui.screens.LevelLoadingScreen.Reason.OTHER), null});
        return l;
    }

    private static net.minecraft.client.gui.components.AbstractSelectionList<?> lsFindList(Screen s) {
        if (s != null) for (Object c : s.children())
            if (c instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> l) return l;
        return null;
    }

    private static net.minecraft.client.gui.components.CycleButton<?> lsCycle;

    private static net.minecraft.client.gui.components.CycleButton<?> lsFindCycle(Object node) {
        String want = net.minecraft.network.chat.Component.translatable("options.viewBobbing").getString();
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        if (node != null) q.add(node);
        net.minecraft.client.gui.components.CycleButton<?> anyBool = null;
        while (!q.isEmpty()) {
            Object o = q.poll();
            if (o instanceof net.minecraft.client.gui.components.CycleButton<?> cb) {
                if (cb.getMessage().getString().startsWith(want)) return cb;
                if (anyBool == null && cb.getValue() instanceof Boolean) anyBool = cb;
            }
            if (o instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) q.addAll(c.children());
        }
        return anyBool;
    }

    private static void lsClick(Minecraft client, net.minecraft.client.gui.components.AbstractWidget w) {
        Screen s = currentScreen(client);
        if (s == null || w == null) return;
        double x = w.getX() + w.getWidth() / 2.0, y = w.getY() + w.getHeight() / 2.0;
        net.minecraft.client.input.MouseButtonEvent ev = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0));
        s.mouseClicked(ev, false);
        s.mouseReleased(ev);
        System.out.println("[DevShot] lists clicked '" + w.getMessage().getString() + "' at " + (int) x + "," + (int) y);
    }

    private static void lsLogCycle() {
        if (lsCycle != null) System.out.println("[DevShot] cycle label now '" + lsCycle.getMessage().getString() + "' ns=" + System.nanoTime()
                + " box=" + lsCycle.getX() + "," + lsCycle.getY() + "," + lsCycle.getWidth() + "," + lsCycle.getHeight());
    }

    private static java.util.List<TyEv> lsSchedule(Minecraft client, net.minecraft.client.gui.components.AbstractSelectionList<?> list) {
        java.util.List<TyEv> ev = new java.util.ArrayList<>();
        double cx = list.getX() + list.getWidth() / 2.0, cy = list.getY() + list.getHeight() / 2.0;
        // W: two wheel notches down
        lsShots(ev, client, list, "w", 300, 40, 1);
        ev.add(new TyEv(360, () -> list.mouseScrolled(cx, cy, 0, 3)));   // up: the language list opens at the bottom
        lsShots(ev, client, list, "w", 380, 40, 14, 2);
        // S: select a row a few below the first visible one, then one far below
        ev.add(new TyEv(1400, () -> lsSelect(list, 2)));
        lsShots(ev, client, list, "s", 1420, 40, 10);
        ev.add(new TyEv(2300, () -> lsSelect(list, 7)));
        lsShots(ev, client, list, "t", 2320, 40, 10);
        // F: a quick flick of five notches
        for (int i = 0; i < 6; i++) ev.add(new TyEv(3200 + 45L * i, () -> list.mouseScrolled(cx, cy, 0, 1)));
        lsShots(ev, client, list, "f", 3210, 40, 20);
        ev.add(new TyEv(4600, () -> {}));
        ev.sort(java.util.Comparator.comparingLong(TyEv::t));
        return ev;
    }

    /** Select the {@code k}-th row below the first row currently visible (Entry is protected: LayoutElement + reflection). */
    private static void lsSelect(net.minecraft.client.gui.components.AbstractSelectionList<?> list, int k) {
        try {
            java.util.List<?> ch = list.children();
            int first = 0;
            for (int i = 0; i < ch.size(); i++) {
                if (((net.minecraft.client.gui.layouts.LayoutElement) ch.get(i)).getY() >= list.getY()) { first = i; break; }
            }
            int idx = Math.min(ch.size() - 1, first + k);
            Class<?> entryCls = Class.forName("net.minecraft.client.gui.components.AbstractSelectionList$Entry");
            net.minecraft.client.gui.components.AbstractSelectionList.class.getMethod("setSelected", entryCls).invoke(list, ch.get(idx));
            System.out.println("[DevShot] lists select row " + idx);
        } catch (Throwable t) { skip("lists select", t); }
    }

    private static void lsShots(java.util.List<TyEv> ev, Minecraft client, net.minecraft.client.gui.components.AbstractSelectionList<?> list,
                                String tag, long start, long step, int n) { lsShots(ev, client, list, tag, start, step, n, 1); }

    private static void lsShots(java.util.List<TyEv> ev, Minecraft client, net.minecraft.client.gui.components.AbstractSelectionList<?> list,
                                String tag, long start, long step, int n, int firstIndex) {
        for (int k = 0; k < n; k++) {
            final String name = String.format("ls-%s-%02d.png", tag, firstIndex + k);
            ev.add(new TyEv(start + step * k, () -> {
                System.out.printf("[DevShot] capture %s scroll=%.2f ns=%d%n", name, list.scrollAmount(), System.nanoTime());
                capture(client, name);
            }, true));
        }
    }

    private static Object ingBook;
    private static Object ingChatBox;

    // ---- typing stage: a millisecond schedule of actions and captures ----
    private record TyEv(long t, Runnable run, boolean shot) {
        TyEv(long t, Runnable run) { this(t, run, false); }
    }
    private static java.util.List<TyEv> tyEvents;
    private static int tyNext;
    private static long tyT0;

    private static java.util.List<TyEv> tySchedule(Minecraft client, net.minecraft.client.gui.components.EditBox box) {
        java.util.List<TyEv> ev = new java.util.ArrayList<>();
        // A: one glyph in 10x slow motion, captured every 120 ms real (= 12 ms of animation time)
        ev.add(new TyEv(400, () -> {
            com.seagull.liquidglass.client.render.TypingAnim.timeScale = 0.1F;
            com.seagull.liquidglass.client.render.TypingAnim.debugLog = true;
            box.insertText("S");
        }));
        tyShots(ev, client, "a", 460, 120, 20);
        ev.add(new TyEv(2900, () -> com.seagull.liquidglass.client.render.TypingAnim.timeScale = 1F));
        // B: real-speed burst, one glyph every 70 ms (incl. CJK)
        String burst = "imp1e 你好";
        for (int i = 0; i < burst.length(); i++) {
            final String ch = String.valueOf(burst.charAt(i));
            ev.add(new TyEv(3100 + 70L * i, () -> box.insertText(ch)));
        }
        tyShots(ev, client, "b", 3115, 30, 26);
        // C: insert in the middle -> the rest glides right   (C..G in 4x slow motion so the frames show the motion)
        ev.add(new TyEv(4050, () -> com.seagull.liquidglass.client.render.TypingAnim.timeScale = 0.25F));
        ev.add(new TyEv(4100, () -> box.moveCursorTo(1, false)));
        ev.add(new TyEv(4200, () -> box.insertText("X")));
        tyShots(ev, client, "c", 4215, 60, 12);
        // D: backspace at the end -> exit (float up / blur / fade)
        ev.add(new TyEv(5600, () -> box.moveCursorToEnd(false)));
        ev.add(new TyEv(5700, () -> box.deleteChars(-1)));
        tyShots(ev, client, "d", 5715, 60, 12);
        // E: whole-string replacement (history / tab-complete) -> odometer roll
        ev.add(new TyEv(6600, () -> box.setValue("/gamemode creative")));
        tyShots(ev, client, "e", 6615, 60, 14);
        // F: select all -> highlight grows from the anchor
        ev.add(new TyEv(7700, () -> { box.moveCursorToEnd(false); box.setHighlightPos(0); }));
        tyShots(ev, client, "f", 7715, 60, 12);
        // G: wider than the box, keep typing at the end -> eased horizontal scroll
        ev.add(new TyEv(8700, () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append("liquid glass ");
            box.setValue(sb.toString());
            box.moveCursorToEnd(false);
        }));
        for (int i = 0; i < 4; i++) ev.add(new TyEv(9300 + 300L * i, () -> box.insertText("W")));
        tyShots(ev, client, "g", 9300, 60, 24);
        ev.add(new TyEv(11000, () -> {
            com.seagull.liquidglass.client.render.TypingAnim.debugLog = false;
            com.seagull.liquidglass.client.render.TypingAnim.timeScale = 1F;
        }));
        ev.sort(java.util.Comparator.comparingLong(TyEv::t));
        return ev;
    }

    private static java.util.List<TyEv> chSchedule(Minecraft client) {
        java.util.List<TyEv> ev = new java.util.ArrayList<>();
        java.util.function.Consumer<String> say = m -> client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(m));
        ev.add(new TyEv(200, () -> say.accept("S1mp1e 聊天動畫測試:第一則")));
        tyShots(ev, client, "cha", 215, 30, 12);
        ev.add(new TyEv(1500, () -> { say.accept("第二則:舊訊息往上滑"); say.accept("第三則:新訊息從底部升起淡入"); }));
        tyShots(ev, client, "chb", 1515, 30, 12);
        // suggestion popup
        ev.add(new TyEv(2600, () -> open(client, new net.minecraft.client.gui.screens.ChatScreen("", false), "ingame chat suggest")));
        ev.add(new TyEv(2800, () -> { Object b = chInput(client); if (b instanceof net.minecraft.client.gui.components.EditBox eb) { eb.setValue("/game"); } }));
        tyShots(ev, client, "csi", 2815, 30, 14);
        ev.add(new TyEv(3800, () -> chCycle(client, 1)));
        ev.add(new TyEv(3950, () -> chCycle(client, 1)));
        tyShots(ev, client, "css", 3805, 30, 14);
        ev.add(new TyEv(4900, () -> { Object b = chInput(client); if (b instanceof net.minecraft.client.gui.components.EditBox eb) eb.setValue(""); }));
        tyShots(ev, client, "cso", 4905, 30, 12);
        // close with text -> the words lift off
        ev.add(new TyEv(5800, () -> { Object b = chInput(client); if (b instanceof net.minecraft.client.gui.components.EditBox eb) eb.setValue("hello glass 你好"); }));
        ev.add(new TyEv(6200, () -> close(client)));
        tyShots(ev, client, "ccl", 6150, 30, 12);
        ev.add(new TyEv(7200, () -> {}));
        ev.sort(java.util.Comparator.comparingLong(TyEv::t));
        return ev;
    }

    private static net.minecraft.core.BlockPos sbSignPos;

    private static java.util.List<TyEv> sbSchedule(Minecraft client) {
        java.util.List<TyEv> ev = new java.util.ArrayList<>();
        ev.add(new TyEv(100, () -> {
            sbSignPos = client.player.blockPosition().offset(0, 0, 2);
            ingCmd(client, "execute as @p at @s run setblock ~ ~ ~2 minecraft:oak_sign");
        }));
        ev.add(new TyEv(900, () -> {
            if (client.level != null && client.level.getBlockEntity(sbSignPos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sbe)
                open(client, new net.minecraft.client.gui.screens.inventory.SignEditScreen(sbe, true, false), "sign edit");
            else System.out.println("[DevShot] sign: no sign block entity at " + sbSignPos);
        }));
        ev.add(new TyEv(1300, () -> sbSignInsert(client, "S")));
        tyShots(ev, client, "sga", 1315, 30, 10);
        String more = "imp1e";
        for (int i = 0; i < more.length(); i++) { final String c = String.valueOf(more.charAt(i)); ev.add(new TyEv(1800 + 90L * i, () -> sbSignInsert(client, c))); }
        tyShots(ev, client, "sgb", 1800, 30, 18);
        ev.add(new TyEv(2700, () -> close(client)));
        // book & quill
        ev.add(new TyEv(3000, () -> open(client, new net.minecraft.client.gui.screens.inventory.BookEditScreen(client.player,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK), net.minecraft.world.InteractionHand.MAIN_HAND,
                net.minecraft.world.item.component.WritableBookContent.EMPTY), "book edit")));
        ev.add(new TyEv(3400, () -> sbBookInsert(client, "Liquid glass ")));
        tyShots(ev, client, "bka", 3415, 30, 10);
        String wrap = "typewriter";   // long enough to push the word to the next line
        for (int i = 0; i < wrap.length(); i++) { final String c = String.valueOf(wrap.charAt(i)); ev.add(new TyEv(4000 + 80L * i, () -> sbBookInsert(client, c))); }
        tyShots(ev, client, "bkb", 4000, 30, 30);
        ev.add(new TyEv(5600, () -> {}));
        ev.sort(java.util.Comparator.comparingLong(TyEv::t));
        return ev;
    }

    private static void sbSignInsert(Minecraft client, String text) {
        try {
            Screen s = currentScreen(client);
            if (s == null) return;
            for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass())
                for (java.lang.reflect.Field f : c.getDeclaredFields())
                    if (f.getType() == net.minecraft.client.gui.font.TextFieldHelper.class) {
                        f.setAccessible(true);
                        ((net.minecraft.client.gui.font.TextFieldHelper) f.get(s)).insertText(text);
                        return;
                    }
            System.out.println("[DevShot] sign: no TextFieldHelper");
        } catch (Throwable t) { skip("sign insert", t); }
    }

    private static void sbBookInsert(Minecraft client, String text) {
        try {
            Screen s = currentScreen(client);
            if (s == null) return;
            for (Object o : s.children()) if (o instanceof net.minecraft.client.gui.components.MultiLineEditBox box) {
                java.lang.reflect.Field f = net.minecraft.client.gui.components.MultiLineEditBox.class.getDeclaredField("textField");
                f.setAccessible(true);
                ((net.minecraft.client.gui.components.MultilineTextField) f.get(box)).insertText(text);
                box.setFocused(true);
                return;
            }
            System.out.println("[DevShot] book: no MultiLineEditBox");
        } catch (Throwable t) { skip("book insert", t); }
    }

    private static int hfMoved = -1;

    private static java.util.List<TyEv> hfSchedule(Minecraft client) {
        java.util.List<TyEv> ev = new java.util.ArrayList<>();
        ev.add(new TyEv(50, () -> { ingCmd(client, "gamemode survival @a"); ingCmd(client, "effect clear @a"); }));
        ev.add(new TyEv(600, () -> ingCmd(client, "damage @p 6")));
        tyShots(ev, client, "hp", 600, 40, 26);
        ev.add(new TyEv(2000, () -> ingCmd(client, "effect give @p minecraft:instant_health 1 1")));
        tyShots(ev, client, "hh", 2000, 40, 10);
        ev.add(new TyEv(2690, () -> {
            com.seagull.liquidglass.client.render.TypingAnim.debugLog = true;          // captures log their ns
            com.seagull.liquidglass.client.render.ItemFlights.debugLog = true;
            com.seagull.liquidglass.client.render.ItemFlights.timeScale = 0.25F;       // 4x slow motion to see the path
        }));
        ev.add(new TyEv(2700, () -> open(client, new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player), "inventory flights")));
        ev.add(new TyEv(3100, () -> hfQuickMove(client, true)));
        tyShots(ev, client, "fla", 3100, 40, 20);
        ev.add(new TyEv(4000, () -> hfQuickMove(client, false)));
        tyShots(ev, client, "flb", 4000, 40, 16);
        ev.add(new TyEv(4800, () -> {
            com.seagull.liquidglass.client.render.TypingAnim.debugLog = false;
            com.seagull.liquidglass.client.render.ItemFlights.debugLog = false;
            com.seagull.liquidglass.client.render.ItemFlights.timeScale = 1F;
        }));
        ev.sort(java.util.Comparator.comparingLong(TyEv::t));
        return ev;
    }

    /** Shift-click (QUICK_MOVE) a hotbar stack into the inventory, or the moved stack back — through vanilla's own click path. */
    private static void hfQuickMove(Minecraft client, boolean fromHotbar) {
        try {
            if (!(currentScreen(client) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> scr)) return;
            java.util.List<net.minecraft.world.inventory.Slot> slots = scr.getMenu().slots;
            int idx = -1;
            if (fromHotbar) {
                for (int i = 37; i <= 44 && i < slots.size(); i++) if (!slots.get(i).getItem().isEmpty()) { idx = i; break; }
            } else {
                for (int i = 9; i <= 35 && i < slots.size(); i++) if (!slots.get(i).getItem().isEmpty()) { idx = i; break; }
            }
            if (idx < 0) { System.out.println("[DevShot] flights: nothing to move"); return; }
            java.lang.reflect.Method m = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredMethod("slotClicked",
                    net.minecraft.world.inventory.Slot.class, int.class, int.class, net.minecraft.world.inventory.ContainerInput.class);
            m.setAccessible(true);
            net.minecraft.world.inventory.Slot sl = slots.get(idx);
            System.out.println("[DevShot] flights: quick-move slot " + idx + " (" + sl.getItem() + ")");
            m.invoke(scr, sl, sl.index, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
        } catch (Throwable t) { skip("flights quick-move", t); }
    }

    private static java.util.List<TyEv> rbSchedule(Minecraft client) {
        java.util.List<TyEv> ev = new java.util.ArrayList<>();
        ev.add(new TyEv(50, () -> {
            ingCmd(client, "gamemode survival @a");
            // `recipe give` mutates the advancement/recipe maps the server thread iterates each tick, so it must run
            // on the server thread (else CME in PlayerAdvancements.flushDirty). Defer it like scrollOpenRecipeBook.
            MinecraftServer sv = client.getSingleplayerServer();
            if (sv != null) sv.execute(() -> {
                try { sv.getCommands().performPrefixedCommand(sv.createCommandSourceStack(), "recipe give @a *"); }
                catch (Throwable t) { skip("recipe give (server thread)", t); }
            });
        }));
        ev.add(new TyEv(600, () -> {
            com.seagull.liquidglass.client.render.RecipeBookSlide.timeScale = 0.25F;
            com.seagull.liquidglass.client.render.RecipeCascade.timeScale = 0.25F;
            open(client, new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player), "recipe book slide");
        }));
        ev.add(new TyEv(1300, () -> rbClickBookButton(client)));                          // OPEN
        tyShots(ev, client, "rbo", 1300, 40, 40);
        ev.add(new TyEv(4200, () -> rbClickPage(client, true)));                          // PAGE ->
        tyShots(ev, client, "rbp", 4200, 40, 30);
        ev.add(new TyEv(6600, () -> rbClickTab(client, 2)));                              // TAB
        tyShots(ev, client, "rbt", 6600, 40, 30);
        ev.add(new TyEv(9000, () -> rbClickBookButton(client)));                          // CLOSE
        tyShots(ev, client, "rbc", 9000, 40, 30);
        ev.add(new TyEv(11500, () -> {
            com.seagull.liquidglass.client.render.RecipeBookSlide.timeScale = 1F;
            com.seagull.liquidglass.client.render.RecipeCascade.timeScale = 1F;
        }));
        ev.sort(java.util.Comparator.comparingLong(TyEv::t));
        return ev;
    }

    /** Park the GUI cursor at GUI px (gx, gy) for the Sodium sweep (writes MouseHandler, like ttApplyHover). */
    private static void sodPark(Minecraft client, double gx, double gy) {
        try {
            Window w = client.getWindow();
            if (ttMouseX == null) {
                java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                fx.setAccessible(true);
                fy.setAccessible(true);
                ttMouseX = fx;
                ttMouseY = fy;
            }
            ttMouseX.setDouble(client.mouseHandler, gx * w.getScreenWidth() / (double) w.getGuiScaledWidth());
            ttMouseY.setDouble(client.mouseHandler, gy * w.getScreenHeight() / (double) w.getGuiScaledHeight());
        } catch (Throwable t) { skip("sodium park cursor", t); }
    }

    /** Dev-only screen: each replaced glyph sprite drawn vanilla (bypass) and replaced, normal + highlighted. */
    private static final class IconGallery extends Screen {
        private static final Object[][] ROWS = {
            {"recipe_book/page_forward", 12, 17, true}, {"recipe_book/page_backward", 12, 17, true},
            {"recipe_book/filter_enabled", 26, 16, true}, {"recipe_book/filter_disabled", 26, 16, true},
            {"widget/page_forward", 23, 13, true}, {"widget/page_backward", 23, 13, true},
            {"container/beacon/confirm", 18, 18, false}, {"container/beacon/cancel", 18, 18, false},
            {"icon/checkmark", 9, 8, false}, {"widget/cross_button", 14, 14, true},
            {"server_list/join", 32, 32, true}, {"server_list/move_up", 32, 32, true},
            {"server_list/move_down", 32, 32, true}, {"world_list/join", 32, 32, true},
            {"transferable_list/move_up", 32, 32, true}, {"statistics/sort_up", 18, 18, false},
            {"spectator/scroll_right", 16, 16, false}, {"spectator/close", 16, 16, false},
            {"widget/checkbox", 17, 17, true}, {"widget/checkbox_selected", 17, 17, true},
            {"world_list/join", 32, 32, true}, {"world_list/marked_join", 32, 32, true},
            {"world_list/warning", 32, 32, true}, {"world_list/error", 32, 32, true},
        };

        IconGallery() { super(Component.literal("icons")); }

        @Override
        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            int half = ROWS.length / 2;
            for (int i = 0; i < ROWS.length; i++) {
                String sprite = (String) ROWS[i][0];
                int w = (Integer) ROWS[i][1], h = (Integer) ROWS[i][2];
                boolean hl = (Boolean) ROWS[i][3];
                int gx = i < half ? 8 : 324;
                int y = 6;
                for (int k = (i < half ? 0 : half); k < i; k++) y += (Integer) ROWS[k][2] + 5;
                String label = sprite.substring(sprite.lastIndexOf('/') + 1);
                g.text(this.font, label, gx, y + h / 2 - 4, 0xFFFFFFFF, true);
                int[] cx = {gx + 110, gx + 146, gx + 200, gx + 236};
                for (int c = 0; c < 4; c++) {
                    boolean isHl = c % 2 == 1;
                    if (isHl && !hl) continue;
                    com.seagull.liquidglass.client.render.SfIcons.devBypass = c < 2;
                    g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                            net.minecraft.resources.Identifier.withDefaultNamespace(sprite + (isHl ? "_highlighted" : "")),
                            cx[c], y, w, h);
                    com.seagull.liquidglass.client.render.SfIcons.devBypass = false;
                }
            }
        }
    }

    private static void rbClickAt(Minecraft client, double x, double y, String what) {
        Screen s = currentScreen(client);
        if (s == null) return;
        net.minecraft.client.input.MouseButtonEvent ev = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0));
        s.mouseClicked(ev, false);
        s.mouseReleased(ev);
        System.out.println("[DevShot] recipe: clicked " + what + " at " + (int) x + "," + (int) y);
    }

    private static void rbClickBookButton(Minecraft client) {
        Screen s = currentScreen(client);
        if (s == null) return;
        for (Object o : s.children()) if (o instanceof net.minecraft.client.gui.components.ImageButton b) {
            rbClickAt(client, b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, "book button");
            return;
        }
        System.out.println("[DevShot] recipe: no book button");
    }

    private static Object rbField(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static void rbClickPage(Minecraft client, boolean forward) {
        try {
            Object comp = rbField(currentScreen(client), "recipeBookComponent");
            Object page = rbField(comp, "recipeBookPage");
            Object btn = rbField(page, forward ? "forwardButton" : "backButton");
            if (btn instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
                rbClickAt(client, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, forward ? "next page" : "prev page");
            else System.out.println("[DevShot] recipe: page button not visible");
        } catch (Throwable t) { skip("recipe page", t); }
    }

    private static void rbClickTab(Minecraft client, int index) {
        try {
            Object comp = rbField(currentScreen(client), "recipeBookComponent");
            java.util.List<?> tabs = (java.util.List<?>) rbField(comp, "tabButtons");
            int seen = 0;
            for (Object o : tabs) if (o instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible) {
                if (seen++ == index) { rbClickAt(client, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0, "tab " + index); return; }
            }
            System.out.println("[DevShot] recipe: tab " + index + " not found");
        } catch (Throwable t) { skip("recipe tab", t); }
    }

    private static Object chInput(Minecraft client) {
        Screen s = currentScreen(client);
        return s == null ? null : ingFindEditBox(s);
    }

    /** Move the command-suggestion selection like Tab would (reflection: ChatScreen.commandSuggestions -> suggestions). */
    private static void chCycle(Minecraft client, int dir) {
        try {
            Screen s = currentScreen(client);
            if (s == null) return;
            Object cs = null;
            for (Class<?> c = s.getClass(); c != null && cs == null; c = c.getSuperclass())
                for (java.lang.reflect.Field f : c.getDeclaredFields())
                    if (f.getType() == net.minecraft.client.gui.components.CommandSuggestions.class) { f.setAccessible(true); cs = f.get(s); break; }
            if (cs == null) { System.out.println("[DevShot] chat: no CommandSuggestions"); return; }
            java.lang.reflect.Field lf = net.minecraft.client.gui.components.CommandSuggestions.class.getDeclaredField("suggestions");
            lf.setAccessible(true);
            Object list = lf.get(cs);
            if (list == null) { System.out.println("[DevShot] chat: suggestions not shown"); return; }
            list.getClass().getMethod("cycle", int.class).invoke(list, dir);
            System.out.println("[DevShot] chat: cycled suggestion " + dir);
        } catch (Throwable t) { skip("chat cycle", t); }
    }

    private static void tyShots(java.util.List<TyEv> ev, Minecraft client, String tag, long start, long step, int n) {
        for (int k = 0; k < n; k++) {
            final String name = String.format("ty-%s-%02d.png", tag, k + 1);
            ev.add(new TyEv(start + step * k, () -> {
                if (com.seagull.liquidglass.client.render.TypingAnim.debugLog)
                    System.out.println("[DevShot] capture " + name + " ns=" + System.nanoTime());
                capture(client, name);
            }, true));
        }
    }

    /** Point the camera steeply down (pitch 52, yaw 0) so terrain fills the whole viewport behind the open menu — a
     *  representative backdrop for judging the glass tab material, instead of the default frame's bright horizon sky. */
    private static void tabsCameraDown(Minecraft client) {
        try {
            LocalPlayer cp = client.player;
            if (cp != null) {
                cp.setYRot(0f);  cp.yRotO = 0f;  cp.setYHeadRot(0f);
                cp.setXRot(52f); cp.xRotO = 52f;
            }
        } catch (Throwable t) { skip("tabs camera down", t); }
    }

    /** Consecutive frames captured per tab-motion case (slide / row switch / hover in, glide, out). */
    private static final int TAB_MOTION = 14;

    /** The creative tab shown in the given row/column, or null. */
    private static net.minecraft.world.item.CreativeModeTab tabsAt(boolean top, int column) {
        try {
            for (net.minecraft.world.item.CreativeModeTab t : net.minecraft.world.item.CreativeModeTabs.tabs()) {
                boolean isTop = t.row() == net.minecraft.world.item.CreativeModeTab.Row.TOP;
                if (isTop == top && t.column() == column && t.shouldDisplay()) return t;
            }
        } catch (Throwable t) { skip("tabs at " + (top ? "top " : "bottom ") + column, t); }
        return null;
    }

    /** First visible creative tab of the given type (CATEGORY = Building Blocks, INVENTORY = survival inventory), or null. */
    private static net.minecraft.world.item.CreativeModeTab tabsPick(net.minecraft.world.item.CreativeModeTab.Type type) {
        try {
            for (net.minecraft.world.item.CreativeModeTab t : net.minecraft.world.item.CreativeModeTabs.tabs()) {
                if (t.getType() == type) return t;
            }
        } catch (Throwable t) { skip("tabs pick " + type, t); }
        return null;
    }

    /** Force the open creative screen onto {@code tab} via its private {@code selectTab} (which also rebuilds the grid). */
    private static void tabsSelect(Minecraft client, net.minecraft.world.item.CreativeModeTab tab) {
        if (tab == null) return;
        try {
            Screen s = currentScreen(client);
            if (s instanceof CreativeModeInventoryScreen) {
                java.lang.reflect.Method m = CreativeModeInventoryScreen.class
                        .getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                m.setAccessible(true);
                m.invoke(s, tab);
            } else {
                skip("tabs select (screen is " + (s == null ? "null" : s.getClass().getSimpleName()) + ")",
                        new IllegalStateException("not a CreativeModeInventoryScreen"));
            }
        } catch (Throwable t) { skip("tabs select tab", t); }
    }

    // ---- scroll sweep (S1MP1E_SHOT_MODE=scroll): the vertical glass scrollbar on the creative grid ----

    private static final int SCROLL_MOTION_FRAMES = 12;   // consecutive frames after a wheel step, to show the glide

    /**
     * Open the creative item grid (search tab, so it is full and the scrollbar is active) and shoot the shared vertical
     * glass scrollbar in three states: (1) scrolled to a middle position; (2) a short glide burst of consecutive frames
     * right after one big wheel step, so the eased thumb is caught mid-flight; (3) the thumb held, which morphs it into
     * the refracting glass lens. State is forced through the vanilla fields ({@code scrollOffs}/{@code scrolling}) and
     * {@code ItemPickerMenu.scrollTo}, so the real render path (the glass mixin) draws every frame. Fully guarded; inert
     * unless {@code S1MP1E_SHOT_MODE=scroll}.
     */
    private static void stepScroll(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}

        switch (scrollStage) {
            case 0: {   // open the populated creative grid, scroll to the middle
                if (frames == 0) {
                    tabsCameraDown(client);
                    tabsGamemodeCreative(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("scroll rebuild creative contents", t); }
                    try {
                        java.lang.reflect.Field f = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                        f.setAccessible(true);
                        f.set(null, net.minecraft.world.item.CreativeModeTabs.searchTab());
                    } catch (Throwable t) { skip("scroll preset creative tab", t); }
                }
                frames++;
                if (frames == TAB_WARM) {
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "scroll creative");
                    scrollSelectSearch(client);
                }
                if (frames == TAB_WARM + 8) scrollSelectSearch(client);
                if (frames == TAB_WARM + 14) scrollSet(client, 0.5f);
                if (frames == TAB_WARM + SCREEN_SETTLE)          capture(client, "scroll-creative-mid.png");
                else if (frames >= TAB_WARM + SCREEN_SETTLE + SCREEN_FLUSH) { scrollStage = 1; frames = 0; }
                return;
            }
            case 1: {   // glide burst: settle low, one big wheel step, then consecutive frames of the ease
                if (frames == 0) scrollSet(client, 0.12f);
                frames++;
                if (frames == 24) scrollSet(client, 0.78f);   // the "wheel step" the thumb now glides toward
                if (frames > 24 && frames <= 24 + SCROLL_MOTION_FRAMES) {
                    capture(client, String.format("scroll-creative-motion-%02d.png", frames - 25));
                } else if (frames >= 24 + SCROLL_MOTION_FRAMES + SCREEN_FLUSH) {
                    scrollStage = 2; frames = 0;
                }
                return;
            }
            case 2: {   // thumb held -> glass lens
                if (frames == 0) scrollSet(client, 0.5f);
                if (frames >= 4) scrollHold(client, true);   // (re-assert cursor + scrolling each frame until the shot)
                frames++;
                if (frames == 4 + 44)          capture(client, "scroll-creative-held.png");
                else if (frames >= 4 + 44 + SCREEN_FLUSH) { scrollHold(client, false); scrollStage = 3; frames = 0; }
                return;
            }
            case 3: {   // stonecutter recipe list: mid position, then a glide burst
                if (frames == 0) scrollOpenStonecutter(client);
                frames++;
                if (frames == 40) scrollSetStonecutter(client, 0.5f);
                if (frames == 40 + SCREEN_SETTLE)          capture(client, "scroll-stonecutter-mid.png");
                else if (frames == 40 + SCREEN_SETTLE + 10) scrollSetStonecutter(client, 0.08f);
                else if (frames == 40 + SCREEN_SETTLE + 34) scrollSetStonecutter(client, 0.82f);   // wheel step the content glides toward
                else if (frames > 40 + SCREEN_SETTLE + 34 && frames <= 40 + SCREEN_SETTLE + 34 + SCROLL_MOTION_FRAMES) {
                    capture(client, String.format("scroll-stonecutter-motion-%02d.png", frames - (40 + SCREEN_SETTLE + 35)));
                } else if (frames >= 40 + SCREEN_SETTLE + 34 + SCROLL_MOTION_FRAMES + SCREEN_FLUSH) {
                    scrollStage = 4; frames = 0;
                }
                return;
            }
            case 4: {   // stonecutter scrollbar thumb held -> glass lens
                if (frames == 0) scrollSetStonecutter(client, 0.5f);
                if (frames >= 4) scrollHoldStonecutter(client, true);
                frames++;
                if (frames == 4 + 44)          capture(client, "scroll-stonecutter-held.png");
                else if (frames >= 4 + 44 + SCREEN_FLUSH) { scrollHoldStonecutter(client, false); scrollStage = 5; frames = 0; }
                return;
            }
            case 5: {   // merchant / villager trade list: mid position, then a glide burst
                if (frames == 0) scrollOpenMerchant(client);
                frames++;
                if (frames == 20) scrollSetMerchant(client, 3);
                if (frames == 20 + SCREEN_SETTLE)          capture(client, "scroll-merchant-mid.png");
                else if (frames == 20 + SCREEN_SETTLE + 10) scrollSetMerchant(client, 0);
                else if (frames == 20 + SCREEN_SETTLE + 34) scrollSetMerchant(client, 7);
                else if (frames > 20 + SCREEN_SETTLE + 34 && frames <= 20 + SCREEN_SETTLE + 34 + SCROLL_MOTION_FRAMES) {
                    capture(client, String.format("scroll-merchant-motion-%02d.png", frames - (20 + SCREEN_SETTLE + 35)));
                } else if (frames >= 20 + SCREEN_SETTLE + 34 + SCROLL_MOTION_FRAMES + SCREEN_FLUSH) {
                    scrollStage = 6; frames = 0;
                }
                return;
            }
            case 6: {   // merchant scrollbar thumb held -> glass lens
                if (frames == 0) scrollSetMerchant(client, 3);
                if (frames >= 4) scrollHoldMerchant(client, true);
                frames++;
                if (frames == 4 + 44)          capture(client, "scroll-merchant-held.png");
                else if (frames >= 4 + 44 + SCREEN_FLUSH) { scrollHoldMerchant(client, false); scrollStage = 7; frames = 0; }
                return;
            }
            case 7: {   // loom pattern list: mid position, then a glide burst
                if (frames == 0) scrollOpenLoom(client);
                frames++;
                if (frames == 40) scrollSetLoom(client, 0.5f);
                if (frames == 40 + SCREEN_SETTLE)          capture(client, "scroll-loom-mid.png");
                else if (frames == 40 + SCREEN_SETTLE + 10) scrollSetLoom(client, 0.06f);
                else if (frames == 40 + SCREEN_SETTLE + 34) scrollSetLoom(client, 0.9f);
                else if (frames > 40 + SCREEN_SETTLE + 34 && frames <= 40 + SCREEN_SETTLE + 34 + SCROLL_MOTION_FRAMES) {
                    capture(client, String.format("scroll-loom-motion-%02d.png", frames - (40 + SCREEN_SETTLE + 35)));
                } else if (frames >= 40 + SCREEN_SETTLE + 34 + SCROLL_MOTION_FRAMES + SCREEN_FLUSH) {
                    scrollStage = 8; frames = 0;
                }
                return;
            }
            case 8: {   // loom scrollbar thumb held -> glass lens
                if (frames == 0) scrollSetLoom(client, 0.5f);
                if (frames >= 4) scrollHoldLoom(client, true);
                frames++;
                if (frames == 4 + 44)          capture(client, "scroll-loom-held.png");
                else if (frames >= 4 + 44 + SCREEN_FLUSH) { scrollHoldLoom(client, false); scrollStage = 9; frames = 0; }
                return;
            }
            case 9: {   // recipe book: page 0 static, then a smooth page-turn cross-slide
                if (frames == 0) scrollOpenRecipeBook(client);
                frames++;
                if (frames == 20) recipeBookShow(client);
                if (frames == 30) recipeBookShow(client);      // re-assert after init/updateCollections
                if (frames == 55)          capture(client, "scroll-recipebook-page0.png");
                else if (frames == 64) recipeBookTurnPage(client, 1);   // forward -> slide in from the right
                else if (frames > 64 && frames <= 64 + SCROLL_MOTION_FRAMES) {
                    capture(client, String.format("scroll-recipebook-motion-%02d.png", frames - 65));
                } else if (frames >= 64 + SCROLL_MOTION_FRAMES + SCREEN_FLUSH) {
                    close(client); scrollStage = 10; frames = 0;
                }
                return;
            }
            default:
                close(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    // ---- stonecutter population + scroll control -------------------------------------------------

    /** Open a stonecutter menu through the integrated server and put stone in the input so the recipe list fills. */
    private static void scrollOpenStonecutter(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) { skip("scroll stonecutter (no server)", new IllegalStateException("no server")); return; }
            java.util.List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp == null) return;
            sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new StonecutterMenu(id, inv), Component.literal("石切機")));
            AbstractContainerMenu m = sp.containerMenu;
            if (m instanceof StonecutterMenu sm) {
                // Pick whichever common stonecuttable block yields the MOST recipes (> 12 so the scrollbar activates).
                net.minecraft.world.item.Item[] candidates = {
                        Items.COBBLED_DEEPSLATE, Items.DEEPSLATE, Items.BLACKSTONE, Items.SANDSTONE,
                        Items.RED_SANDSTONE, Items.PRISMARINE, Items.QUARTZ_BLOCK, Items.COBBLESTONE,
                        Items.GRANITE, Items.ANDESITE, Items.DIORITE, Items.STONE };
                net.minecraft.world.item.Item best = Items.STONE;
                int bestN = 0;
                for (net.minecraft.world.item.Item it : candidates) {
                    sm.container.setItem(0, new ItemStack(it, 64));
                    sm.slotsChanged(sm.container);
                    int n = sm.getNumberOfVisibleRecipes();
                    if (n > bestN) { bestN = n; best = it; }
                }
                sm.container.setItem(0, new ItemStack(best, 64));
                sm.slotsChanged(sm.container);
            }
        } catch (Throwable t) { skip("scroll open stonecutter", t); }
    }

    /** Set the stonecutter recipe scroll to fraction {@code f} (0..1) on the client screen. */
    private static void scrollSetStonecutter(Minecraft client, float f) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof StonecutterScreen)) return;
            int n = ((StonecutterMenu) ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu()).getNumberOfVisibleRecipes();
            int offscreen = Math.max(0, (n + 3) / 4 - 3);
            java.lang.reflect.Field so = StonecutterScreen.class.getDeclaredField("scrollOffs"); so.setAccessible(true); so.setFloat(s, f);
            java.lang.reflect.Field si = StonecutterScreen.class.getDeclaredField("startIndex"); si.setAccessible(true);
            si.setInt(s, Math.round(f * offscreen) * 4);
        } catch (Throwable t) { skip("scroll set stonecutter " + f, t); }
    }

    /** Hold the stonecutter scrollbar thumb (set {@code scrolling} + park the cursor over the mid-track). */
    private static void scrollHoldStonecutter(Minecraft client, boolean on) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof StonecutterScreen)) return;
            java.lang.reflect.Field sc = StonecutterScreen.class.getDeclaredField("scrolling"); sc.setAccessible(true); sc.setBoolean(s, on);
            if (on) {
                com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                        (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
                double gx = acc.liquidglass$leftPos() + 119 + 6;
                double gy = acc.liquidglass$topPos() + 15 + 27;   // mid of the 54px track
                int scale = client.getWindow().getGuiScale();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), gx * scale, gy * scale);
            }
        } catch (Throwable t) { skip("scroll hold stonecutter " + on, t); }
    }

    // ---- merchant population + scroll control ---------------------------------------------------

    /** Open a fully client-side villager trade screen with 14 offers so the scrollbar is active. */
    private static void scrollOpenMerchant(Minecraft client) {
        try {
            LocalPlayer player = client.player;
            if (player == null) return;
            MerchantMenu menu = new MerchantMenu(1, player.getInventory(), new ClientSideMerchant(player));
            MerchantOffers offers = new MerchantOffers();
            net.minecraft.world.item.Item[] results = {
                    Items.DIAMOND, Items.GOLD_INGOT, Items.IRON_INGOT, Items.BREAD, Items.BOOK,
                    Items.ARROW, Items.COMPASS, Items.CLOCK, Items.SADDLE, Items.NAME_TAG,
                    Items.LEAD, Items.SHIELD, Items.APPLE, Items.MAP };
            for (int i = 0; i < results.length; i++) {
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1 + i % 6),
                        new ItemStack(results[i], 1 + i % 3), 16, 5, 0.05f));
            }
            menu.setOffers(offers);
            open(client, new MerchantScreen(menu, player.getInventory(), Component.literal("村民交易")), "scroll merchant");
        } catch (Throwable t) { skip("scroll open merchant", t); }
    }

    /** Set the merchant trade scroll to row {@code off}. */
    private static void scrollSetMerchant(Minecraft client, int off) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof MerchantScreen)) return;
            java.lang.reflect.Field f = MerchantScreen.class.getDeclaredField("scrollOff"); f.setAccessible(true); f.setInt(s, off);
        } catch (Throwable t) { skip("scroll set merchant " + off, t); }
    }

    // ---- loom population + scroll control ------------------------------------------------------

    /** Open a loom through the integrated server with a banner + dye so the full pattern list (scrollbar) fills. */
    private static void scrollOpenLoom(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) return;
            java.util.List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp == null) return;
            sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new LoomMenu(id, inv), Component.literal("織布機")));
            AbstractContainerMenu m = sp.containerMenu;
            if (m instanceof LoomMenu lm) {
                lm.getBannerSlot().set(new ItemStack(Items.BANNER.pick(net.minecraft.world.item.DyeColor.WHITE)));
                lm.getDyeSlot().set(new ItemStack(Items.DYE.pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE)));
                lm.slotsChanged(lm.getBannerSlot().container);
                System.out.println("[S1mp1e][DevShot] loom patterns = " + lm.getSelectablePatterns().size());
            }
        } catch (Throwable t) { skip("scroll open loom", t); }
    }

    /** Loom offscreen rows (totalRowCount - 4) from the client menu's selectable-pattern count. */
    private static int scrollLoomOffscreen(Screen s) {
        try {
            int size = ((LoomMenu) ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu()).getSelectablePatterns().size();
            return Math.max(0, (size + 3) / 4 - 4);
        } catch (Throwable t) { return 0; }
    }

    /** Set the loom pattern scroll to fraction {@code f} (0..1). */
    private static void scrollSetLoom(Minecraft client, float f) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof LoomScreen)) return;
            int offscreen = scrollLoomOffscreen(s);
            java.lang.reflect.Field so = LoomScreen.class.getDeclaredField("scrollOffs"); so.setAccessible(true); so.setFloat(s, f);
            java.lang.reflect.Field sr = LoomScreen.class.getDeclaredField("startRow"); sr.setAccessible(true);
            sr.setInt(s, Math.max(0, Math.round(f * offscreen)));
        } catch (Throwable t) { skip("scroll set loom " + f, t); }
    }

    /** Hold the loom scrollbar thumb (set {@code scrolling} + park the cursor over the mid-track). */
    private static void scrollHoldLoom(Minecraft client, boolean on) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof LoomScreen)) return;
            java.lang.reflect.Field sc = LoomScreen.class.getDeclaredField("scrolling"); sc.setAccessible(true); sc.setBoolean(s, on);
            if (on) {
                com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                        (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
                double gx = acc.liquidglass$leftPos() + 119 + 6;
                double gy = acc.liquidglass$topPos() + 13 + 27;
                int scale = client.getWindow().getGuiScale();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), gx * scale, gy * scale);
            }
        } catch (Throwable t) { skip("scroll hold loom " + on, t); }
    }

    // ---- recipe book population + page turn ----------------------------------------------------

    /** Unlock every recipe (server) and open a crafting-table screen so its recipe book has many pages. */
    private static void scrollOpenRecipeBook(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                // Must run on the SERVER thread: `recipe give @a *` mutates the player's advancement/recipe maps that
                // the server thread iterates every tick, so issuing it from the render thread races (CME in
                // PlayerAdvancements.flushDirty). server.execute() defers it onto the server thread.
                server.execute(() -> {
                    try {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "recipe give @a *");
                    } catch (Throwable t) { skip("recipe give (server thread)", t); }
                });
            }
            LocalPlayer player = client.player;
            if (player == null) return;
            open(client, new CraftingScreen(new net.minecraft.world.inventory.CraftingMenu(1, player.getInventory()),
                    player.getInventory(), Component.literal("工作台")), "recipe book crafting");
        } catch (Throwable t) { skip("scroll open recipebook", t); }
    }

    private static Object lg$recipeBookPage(Screen s) throws Exception {
        java.lang.reflect.Field cf = AbstractRecipeBookScreen.class.getDeclaredField("recipeBookComponent");
        cf.setAccessible(true);
        Object comp = cf.get(s);
        java.lang.reflect.Field pf = RecipeBookComponent.class.getDeclaredField("recipeBookPage");
        pf.setAccessible(true);
        return pf.get(comp);
    }

    /** Force the recipe book panel open (both the {@code visible} field and {@code setVisible}, plus a filter reset). */
    private static void recipeBookShow(Minecraft client) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof CraftingScreen)) return;
            java.lang.reflect.Field cf = AbstractRecipeBookScreen.class.getDeclaredField("recipeBookComponent");
            cf.setAccessible(true);
            Object comp = cf.get(s);
            try {
                java.lang.reflect.Method m = RecipeBookComponent.class.getDeclaredMethod("setVisible", boolean.class);
                m.setAccessible(true); m.invoke(comp, true);
            } catch (Throwable t) {
                java.lang.reflect.Field vf = RecipeBookComponent.class.getDeclaredField("visible");
                vf.setAccessible(true); vf.setBoolean(comp, true);
            }
        } catch (Throwable t) { skip("recipe book show", t); }
    }

    /** Turn the recipe book to {@code page} (sets currentPage + repopulates), which triggers the glass cross-slide. */
    private static void recipeBookTurnPage(Minecraft client, int page) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof CraftingScreen)) return;
            Object pg = lg$recipeBookPage(s);
            java.lang.reflect.Field cp = RecipeBookPage.class.getDeclaredField("currentPage");
            cp.setAccessible(true); cp.setInt(pg, page);
            java.lang.reflect.Method upd = RecipeBookPage.class.getDeclaredMethod("updateButtonsForPage");
            upd.setAccessible(true); upd.invoke(pg);
        } catch (Throwable t) { skip("recipe book turn page " + page, t); }
    }

    /** Hold the merchant scrollbar thumb (set {@code isDragging} + park the cursor over the mid-track). */
    private static void scrollHoldMerchant(Minecraft client, boolean on) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof MerchantScreen)) return;
            java.lang.reflect.Field d = MerchantScreen.class.getDeclaredField("isDragging"); d.setAccessible(true); d.setBoolean(s, on);
            if (on) {
                com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                        (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
                double gx = acc.liquidglass$leftPos() + 94 + 3;
                double gy = acc.liquidglass$topPos() + 18 + 70;   // mid of the 139px track
                int scale = client.getWindow().getGuiScale();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), gx * scale, gy * scale);
            }
        } catch (Throwable t) { skip("scroll hold merchant " + on, t); }
    }

    /** Force the open creative screen onto the search tab (full item list) after init() has run. */
    private static void scrollSelectSearch(Minecraft client) {
        try {
            Screen s = currentScreen(client);
            if (s instanceof CreativeModeInventoryScreen) {
                java.lang.reflect.Method m = CreativeModeInventoryScreen.class
                        .getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                m.setAccessible(true);
                m.invoke(s, net.minecraft.world.item.CreativeModeTabs.searchTab());
            }
        } catch (Throwable t) { skip("scroll select search tab", t); }
    }

    /** Set the vanilla scroll position (thumb target + item mapping) so the shot shows a real scrolled state. */
    private static void scrollSet(Minecraft client, float f) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof CreativeModeInventoryScreen)) return;
            java.lang.reflect.Field so = CreativeModeInventoryScreen.class.getDeclaredField("scrollOffs");
            so.setAccessible(true);
            so.setFloat(s, f);
            Object menu = ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu();
            java.lang.reflect.Method m = menu.getClass().getDeclaredMethod("scrollTo", float.class);
            m.setAccessible(true);
            m.invoke(menu, f);
        } catch (Throwable t) { skip("scroll set " + f, t); }
    }

    /** Hold (or release) the scrollbar thumb: set {@code scrolling} and park the cursor over the mid-track so the glass
     *  mixin's redirect presses/drags the bar into its lens state at a predictable position. */
    private static void scrollHold(Minecraft client, boolean on) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof CreativeModeInventoryScreen)) return;
            java.lang.reflect.Field sc = CreativeModeInventoryScreen.class.getDeclaredField("scrolling");
            sc.setAccessible(true);
            sc.setBoolean(s, on);
            if (on) {
                com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                        (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
                double gx = acc.liquidglass$leftPos() + 181;         // scrollbar centre x (GUI px)
                double gy = acc.liquidglass$topPos() + 18 + 48.5;    // mid-track y (GUI px)
                int scale = client.getWindow().getGuiScale();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), gx * scale, gy * scale);
            }
        } catch (Throwable t) { skip("scroll hold " + on, t); }
    }

    // ---- click tests (S1MP1E_SHOT_MODE=clicks): assert clicks hit the drawn item, at rest AND mid-glide ----

    private static int clicksPass, clicksFail;

    /**
     * Automated click tests for the three sub-pixel glide screens. For each of the creative item grid, the loom pattern
     * grid and the villager trade list, the harness (a) settles the list on a row and clicks a specific drawn cell, then
     * (b) starts a big glide and, a couple of frames in (mid-glide), clicks the SAME screen pixel — and each time asserts
     * the resulting action (carried creative stack / selected loom pattern index / selected trade index) is exactly the
     * item/pattern/trade drawn under the cursor. PASS/FAIL lines go to stdout; a shot is written at every click. No real
     * mouse. Fully guarded; inert unless {@code S1MP1E_SHOT_MODE=clicks}.
     */
    private static void stepClicks(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}

        switch (clicksStage) {
            case 0: {   // open the populated creative grid on the search tab
                if (frames == 0) {
                    tabsCameraDown(client);
                    tabsGamemodeCreative(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("clicks rebuild creative contents", t); }
                    try {
                        java.lang.reflect.Field f = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                        f.setAccessible(true);
                        f.set(null, net.minecraft.world.item.CreativeModeTabs.searchTab());
                    } catch (Throwable t) { skip("clicks preset creative tab", t); }
                }
                frames++;
                if (frames == TAB_WARM) {
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "clicks creative");
                    scrollSelectSearch(client);
                }
                if (frames == TAB_WARM + 8) scrollSelectSearch(client);
                if (frames >= TAB_WARM + 20) { clicksStage = 1; frames = 0; }
                return;
            }
            case 1: {   // CREATIVE at rest: settle on a row, click a slot, assert the carried stack is that item
                if (frames == 0) scrollSet(client, 0.5f);
                frames++;
                if (frames == 34) { clicksCreativeClick(client, "rest", 0.5f, 2, 4); clicksStage = 2; frames = 0; }
                return;
            }
            case 2: {   // CREATIVE mid-glide: settle low, jump high, click 2 frames in (mid-glide), assert
                if (frames == 0) scrollSet(client, 0.10f);
                frames++;
                if (frames == 34) scrollSet(client, 0.85f);   // start the glide toward row round(0.85*rc)
                if (frames == 36) { clicksCreativeClick(client, "glide", 0.85f, 2, 4); clicksStage = 3; frames = 0; }
                return;
            }
            case 3: {   // CREATIVE mid-glide, TOP-edge visible row (sr=0): click the row entering/leaving the top edge
                if (frames == 0) scrollSet(client, 0.85f);
                frames++;
                if (frames == 34) scrollSet(client, 0.55f);          // up-glide toward round(0.55*rc)
                if (frames == 36) { clicksCreativeClick(client, "glide-topedge", 0.55f, 0, 0); clicksStage = 4; frames = 0; }
                return;
            }
            case 4: {   // CREATIVE mid-glide, BOTTOM-edge visible row (sr=4): click the row entering at the bottom edge
                if (frames == 0) scrollSet(client, 0.20f);
                frames++;
                if (frames == 34) scrollSet(client, 0.70f);          // down-glide toward round(0.70*rc)
                if (frames == 36) { clicksCreativeClick(client, "glide-botedge", 0.70f, 4, 8); clicksStage = 5; frames = 0; }
                return;
            }
            case 5: {   // LOOM at rest
                if (frames == 0) scrollOpenLoom(client);
                frames++;
                if (frames == 40) scrollSetLoom(client, 0.5f);
                if (frames == 74) { clicksLoomClick(client, "rest", 0.5f, 1, 2); clicksStage = 6; frames = 0; }
                return;
            }
            case 6: {   // LOOM mid-glide
                if (frames == 0) scrollSetLoom(client, 0.08f);
                frames++;
                if (frames == 34) scrollSetLoom(client, 0.9f);
                if (frames == 36) { clicksLoomClick(client, "glide", 0.9f, 1, 2); clicksStage = 7; frames = 0; }
                return;
            }
            case 7: {   // MERCHANT at rest
                if (frames == 0) scrollOpenMerchant(client);
                frames++;
                if (frames == 20) scrollSetMerchant(client, 3);
                if (frames == 54) { clicksMerchantClick(client, "rest", 3, 2); clicksStage = 8; frames = 0; }
                return;
            }
            case 8: {   // MERCHANT mid-glide (mid row)
                if (frames == 0) scrollSetMerchant(client, 0);
                frames++;
                if (frames == 34) scrollSetMerchant(client, 7);
                if (frames == 36) { clicksMerchantClick(client, "glide", 7, 2); clicksStage = 9; frames = 0; }
                return;
            }
            case 9: {   // MERCHANT mid-glide, LAST visible row (i=6): bottom-edge trade
                if (frames == 0) scrollSetMerchant(client, 1);
                frames++;
                if (frames == 34) scrollSetMerchant(client, 6);
                if (frames == 36) { clicksMerchantClick(client, "glide-lastrow", 6, 6); clicksStage = 10; frames = 0; }
                return;
            }
            default:
                System.out.println("[S1mp1e][DevShot][CLICKTEST] SUMMARY " + clicksPass + " PASS / " + clicksFail + " FAIL");
                close(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    /** Synthesize a left click at GUI pixel (gx,gy): park the cursor there, then mouseClicked+mouseReleased. */
    private static void clickAt(Minecraft client, Screen s, double gx, double gy) {
        try {
            int scale = client.getWindow().getGuiScale();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), gx * scale, gy * scale);
        } catch (Throwable ignored) {}
        MouseButtonEvent ev = new MouseButtonEvent(gx, gy, new MouseButtonInfo(0, 0));
        try { s.mouseClicked(ev, false); } catch (Throwable t) { skip("mouseClicked", t); }
        try { s.mouseReleased(ev); } catch (Throwable t) { /* release optional */ }
    }

    private static void passFail(String name, boolean ok, String detail) {
        if (ok) { clicksPass++; System.out.println("[S1mp1e][DevShot][CLICKTEST] PASS " + name + " -> " + detail); }
        else    { clicksFail++; System.out.println("[S1mp1e][DevShot][CLICKTEST] FAIL " + name + " -> " + detail); }
    }

    /** Click creative grid slot (row {@code sr}, col {@code sc}); assert the carried stack is that visible item. */
    private static void clicksCreativeClick(Minecraft client, String what, float target, int sr, int sc) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof CreativeModeInventoryScreen)) { passFail("creative-" + what, false, "no creative screen"); return; }
            com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                    (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
            AbstractContainerMenu menu = ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu();
            java.lang.reflect.Field itemsF = menu.getClass().getField("items");
            @SuppressWarnings("unchecked")
            java.util.List<ItemStack> items = (java.util.List<ItemStack>) itemsF.get(menu);
            java.lang.reflect.Method rc = menu.getClass().getDeclaredMethod("calculateRowCount");
            rc.setAccessible(true);
            int rowCount = (int) rc.invoke(menu);
            int row = Math.max(0, Math.min(rowCount, Math.round(target * rowCount)));
            int idx = (row + sr) * 9 + sc;
            ItemStack expected = idx < items.size() ? items.get(idx) : ItemStack.EMPTY;
            menu.setCarried(ItemStack.EMPTY);
            double gx = acc.liquidglass$leftPos() + 9 + sc * 18 + 8;
            double gy = acc.liquidglass$topPos() + 18 + sr * 18 + 8;
            capture(client, "click-creative-" + what + ".png");
            clickAt(client, s, gx, gy);
            ItemStack carried = menu.getCarried();
            boolean ok = !expected.isEmpty() && !carried.isEmpty() && carried.getItem() == expected.getItem();
            passFail("creative-" + what, ok, "row=" + row + " slot(" + sr + "," + sc + ") expected="
                    + itemName(expected) + " carried=" + itemName(carried));
        } catch (Throwable t) { passFail("creative-" + what, false, "exc " + t); }
    }

    /** Click loom pattern cell (row {@code r}, col {@code c}); assert the selected pattern index is that visible one. */
    private static void clicksLoomClick(Minecraft client, String what, float target, int r, int c) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof LoomScreen)) { passFail("loom-" + what, false, "no loom screen"); return; }
            com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                    (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
            LoomMenu menu = (LoomMenu) ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu();
            int offscreen = scrollLoomOffscreen(s);
            int startRow = Math.max(0, Math.round(target * offscreen));
            int idx = (startRow + r) * 4 + c;
            if (idx >= menu.getSelectablePatterns().size()) { passFail("loom-" + what, false, "idx OOB " + idx); return; }
            double gx = acc.liquidglass$leftPos() + 60 + c * 14 + 7;
            double gy = acc.liquidglass$topPos() + 13 + r * 14 + 7;
            capture(client, "click-loom-" + what + ".png");
            clickAt(client, s, gx, gy);
            int selected = menu.getSelectedBannerPatternIndex();
            boolean ok = selected == idx;
            passFail("loom-" + what, ok, "startRow=" + startRow + " cell(" + r + "," + c + ") expectedIdx=" + idx + " selected=" + selected);
        } catch (Throwable t) { passFail("loom-" + what, false, "exc " + t); }
    }

    /** Click merchant trade button row {@code i}; assert the selected trade (shopItem) is scrollOff+i. */
    private static void clicksMerchantClick(Minecraft client, String what, int scrollOff, int i) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof MerchantScreen)) { passFail("merchant-" + what, false, "no merchant screen"); return; }
            com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                    (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
            java.lang.reflect.Field shopF = MerchantScreen.class.getDeclaredField("shopItem");
            shopF.setAccessible(true);
            shopF.setInt(s, -1);
            double gx = acc.liquidglass$leftPos() + 5 + 44;
            double gy = acc.liquidglass$topPos() + 16 + i * 20 + 10;
            capture(client, "click-merchant-" + what + ".png");
            clickAt(client, s, gx, gy);
            int shopItem = shopF.getInt(s);
            int expected = scrollOff + i;
            boolean ok = shopItem == expected;
            passFail("merchant-" + what, ok, "scrollOff=" + scrollOff + " row=" + i + " expected=" + expected + " shopItem=" + shopItem);
        } catch (Throwable t) { passFail("merchant-" + what, false, "exc " + t); }
    }

    private static String itemName(ItemStack st) {
        try { return st.isEmpty() ? "empty" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString(); }
        catch (Throwable t) { return String.valueOf(st); }
    }

    // ---- glide capture (S1MP1E_SHOT_MODE=glide): ONE wheel step, then the eased sub-pixel settle ----

    private static final int GLIDE_FRAMES = 16;

    /**
     * Capture the sub-pixel content glide after ONE wheel step (one row / one trade) on each of the three glide screens,
     * so the eased settle can be measured frame-by-frame (decelerating, row-aligned at rest). The logical scroll is
     * stepped by exactly one row via the vanilla fields; the glass mixin eases the drawn content toward it. Fully
     * guarded; inert unless {@code S1MP1E_SHOT_MODE=glide}.
     */
    private static void stepGlide(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}

        switch (glideStage) {
            case 0: {   // creative: open, settle on row 6, step to row 7, capture the glide
                if (frames == 0) {
                    tabsCameraDown(client);
                    tabsGamemodeCreative(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("glide rebuild creative", t); }
                    try {
                        java.lang.reflect.Field f = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                        f.setAccessible(true); f.set(null, net.minecraft.world.item.CreativeModeTabs.searchTab());
                    } catch (Throwable t) { skip("glide preset creative tab", t); }
                }
                frames++;
                if (frames == TAB_WARM) {
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "glide creative");
                    scrollSelectSearch(client);
                }
                if (frames == TAB_WARM + 8) { scrollSelectSearch(client); scrollSetCreativeRow(client, 6); }
                if (frames == TAB_WARM + 30) scrollSetCreativeRow(client, 7);       // ONE wheel step
                if (frames > TAB_WARM + 30 && frames <= TAB_WARM + 30 + GLIDE_FRAMES)
                    capture(client, String.format("glide-creative-%02d.png", frames - (TAB_WARM + 31)));
                else if (frames >= TAB_WARM + 30 + GLIDE_FRAMES + SCREEN_FLUSH) { glideStage = 1; frames = 0; }
                return;
            }
            case 1: {   // loom: open, settle startRow 1, step to startRow 2, capture
                if (frames == 0) scrollOpenLoom(client);
                frames++;
                if (frames == 40) scrollSetLoomRow(client, 1);
                if (frames == 70) scrollSetLoomRow(client, 2);                      // ONE wheel step
                if (frames > 70 && frames <= 70 + GLIDE_FRAMES)
                    capture(client, String.format("glide-loom-%02d.png", frames - 71));
                else if (frames >= 70 + GLIDE_FRAMES + SCREEN_FLUSH) { close(client); glideStage = 2; frames = 0; }
                return;
            }
            case 2: {   // merchant: open, settle trade 2, step to trade 3, capture
                if (frames == 0) scrollOpenMerchant(client);
                frames++;
                if (frames == 20) scrollSetMerchant(client, 2);
                if (frames == 50) scrollSetMerchant(client, 3);                     // ONE wheel step
                if (frames > 50 && frames <= 50 + GLIDE_FRAMES)
                    capture(client, String.format("glide-merchant-%02d.png", frames - 51));
                else if (frames >= 50 + GLIDE_FRAMES + SCREEN_FLUSH) { close(client); glideStage = 3; frames = 0; }
                return;
            }
            default:
                close(client);
                frames = 0; phase = P_DRAIN;
        }
    }

    /** Set the creative scroll to exactly row {@code row} (row-snapped fraction + scrollTo). */
    private static void scrollSetCreativeRow(Minecraft client, int row) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof CreativeModeInventoryScreen)) return;
            AbstractContainerMenu menu = ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s).getMenu();
            java.lang.reflect.Method rc = menu.getClass().getDeclaredMethod("calculateRowCount");
            rc.setAccessible(true);
            int rowCount = Math.max(1, (int) rc.invoke(menu));
            float f = Math.min(1f, row / (float) rowCount);
            java.lang.reflect.Field so = CreativeModeInventoryScreen.class.getDeclaredField("scrollOffs");
            so.setAccessible(true); so.setFloat(s, f);
            java.lang.reflect.Method m = menu.getClass().getDeclaredMethod("scrollTo", float.class);
            m.setAccessible(true); m.invoke(menu, f);
        } catch (Throwable t) { skip("glide set creative row " + row, t); }
    }

    /** Set the loom scroll to exactly {@code startRow} (fraction + startRow field). */
    private static void scrollSetLoomRow(Minecraft client, int startRow) {
        try {
            Screen s = currentScreen(client);
            if (!(s instanceof LoomScreen)) return;
            int offscreen = Math.max(1, scrollLoomOffscreen(s));
            float f = Math.min(1f, startRow / (float) offscreen);
            java.lang.reflect.Field so = LoomScreen.class.getDeclaredField("scrollOffs"); so.setAccessible(true); so.setFloat(s, f);
            java.lang.reflect.Field sr = LoomScreen.class.getDeclaredField("startRow"); sr.setAccessible(true); sr.setInt(s, startRow);
        } catch (Throwable t) { skip("glide set loom row " + startRow, t); }
    }

    // ---- tooltip layer sweep (S1MP1E_SHOT_MODE=tooltips): the glass hover card must be the very TOP layer ----

    private static final int TT_SETTLE = 50;   // frames of a scene before the probed capture (hover held from ~frame 3)
    private static final int TT_FLUSH  = 6;    // extra frames after it, so the async read-back grabs the probed frame
    private static final int TT_WARM   = 15;   // creative scenes: let the game-mode switch reach the client first
    private static double ttHoverX = -1, ttHoverY = -1;   // GUI px, re-applied every frame while >= 0
    private static int ttPass, ttFail, ttNone;
    private static boolean ttFromResolved;
    private static java.lang.reflect.Field ttMouseX, ttMouseY;
    private static final net.minecraft.world.item.Item[] TT_POOL = {
            Items.GOLDEN_APPLE, Items.DIAMOND_SWORD, Items.BOW, Items.IRON_PICKAXE, Items.TORCH, Items.OAK_LOG,
            Items.BREAD, Items.ARROW, Items.REDSTONE, Items.ENDER_PEARL, Items.BOOK, Items.COMPASS, Items.CLOCK,
            Items.EMERALD, Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COAL, Items.GLASS, Items.BRICKS,
            Items.SAND, Items.GRAVEL, Items.CACTUS, Items.PUMPKIN, Items.MELON_SLICE, Items.CARROT, Items.POTATO };

    /**
     * Hover sweep for the "glass tooltip is the very top layer" rule. Every scene parks the cursor (by setting the
     * {@code MouseHandler} position the GUI reads, re-applied each frame) on an item / tab / widget whose liquid-glass
     * hover card overlaps OTHER items and glass (slot items + counts, creative tabs, the glass scrollbars, the effect
     * panel, recipe buttons, trade rows...), arms {@link GuiLayerProbe} for the captured frame and prints one
     * {@code [TOOLTIP-LAYER] PASS|FAIL|NO-CARD} line per scene with where the card landed and what (if anything) is drawn
     * over it. The last scene catches the card's 150 ms fade-out ghost, which must stay on top too. Fully guarded;
     * inert unless {@code S1MP1E_SHOT_MODE=tooltips}.
     */
    private static void stepTooltips(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { GuiLayerProbe.armed = false; frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}
        if (!ttFromResolved) {   // optional S1MP1E_TT_FROM=<stage> skips ahead (11 = the creative scenes)
            ttFromResolved = true;
            try { String f = System.getenv("S1MP1E_TT_FROM"); if (f != null && !f.isBlank()) ttStage = Integer.parseInt(f.trim()); }
            catch (Throwable ignored) {}
        }
        boolean done;
        switch (ttStage) {
            case 0:   // survival inventory: hover the sword in main row 0 so its tall card covers the rows below
                done = ttScene(client, "tt-survival-inventory", () -> {
                    ttFillInventory(client);
                    ttServerCommand(client, "advancement grant @a everything");   // for the advancement scene later
                    open(client, new InventoryScreen(player), "tt inventory");
                }, null, s -> s instanceof InventoryScreen ? ttSlotCenter(s, 10) : null);
                break;
            case 1:   // chest: hover the sword in chest row 0; card spans the chest rows
                done = ttScene(client, "tt-chest", () -> { close(client); ttOpenChest(client); }, null,
                        s -> s instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen ? ttSlotCenter(s, 1) : null);
                break;
            case 2:   // crafting-table recipe book: hover the 2nd recipe button (card covers the other recipe buttons)
                done = ttScene(client, "tt-recipe-book", () -> { close(client); scrollOpenRecipeBook(client); },
                        f -> { if (f == 8 || f == 16) recipeBookShow(client); }, s -> ttRecipeButton(s, 1));
                break;
            case 3:   // same screen: the recipe-book filter toggle (a widget tooltip)
                done = ttScene(client, "tt-recipe-filter", () -> {}, null, DevShot::ttRecipeFilter);
                break;
            case 4:   // stonecutter: hover recipe #1, card covers the other recipes + the glass scrollbar
                done = ttScene(client, "tt-stonecutter", () -> { close(client); scrollOpenStonecutter(client); }, null,
                        s -> s instanceof StonecutterScreen ? ttScreenRel(s, 52 + 16 + 8, 14 + 9) : null);
                break;
            case 5:   // loom: hover pattern #1
                done = ttScene(client, "tt-loom", () -> { close(client); scrollOpenLoom(client); }, null,
                        s -> s instanceof LoomScreen ? ttScreenRel(s, 60 + 14 + 7, 13 + 7) : null);
                break;
            case 6:   // merchant: hover trade 0's result item; card covers the trade list + scrollbar
                done = ttScene(client, "tt-merchant", () -> { close(client); scrollOpenMerchant(client); }, null,
                        s -> s instanceof MerchantScreen ? ttScreenRel(s, 5 + 75, 18 + 10) : null);
                break;
            case 7:   // advancements: hover the tree root; its hover box extends over the neighbouring nodes
                done = ttScene(client, "tt-advancements", () -> {
                    close(client);
                    open(client, new AdvancementsScreen(player.connection.getAdvancements()), "tt advancements");
                }, null, DevShot::ttAdvancementRoot);
                break;
            case 8:   // video settings: a button with a tooltip
                done = ttScene(client, "tt-options-button", () -> {
                    close(client);
                    open(client, new VideoSettingsScreen(null, client, client.options), "tt video settings");
                }, null, s -> ttWidgetWithTooltip(s, false));
                break;
            case 9:   // same screen: a slider with a tooltip
                done = ttScene(client, "tt-options-slider", () -> {}, null, s -> ttWidgetWithTooltip(s, true));
                break;
            case 10:  // S1mp1e config screen (no hover tooltips of its own; shot documents that nothing covers content)
                done = ttScene(client, "tt-s1mp1e-config", () -> { close(client); open(client, new S1mp1eConfigScreen(), "tt config"); },
                        null, s -> s instanceof S1mp1eConfigScreen ? new double[]{s.width * 0.30, s.height * 0.35} : null);
                break;
            case 11:  // creative item tab: hover row 1 col 3, card covers neighbouring items
                done = ttScene(client, "tt-creative-grid", () -> {
                    close(client);
                    tabsCameraDown(client);
                    tabsGamemodeCreative(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("tt rebuild creative", t); }
                }, f -> {
                    if (f == TT_WARM) {
                        open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "tt creative");
                        tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.CATEGORY));
                    }
                    if (f == TT_WARM + 8) tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.CATEGORY));
                }, s -> s instanceof CreativeModeInventoryScreen && frames > TT_WARM + 8 ? ttScreenRel(s, 9 + 3 * 18 + 8, 18 + 18 + 8) : null);
                break;
            case 12:  // creative, right column: card over the glass scrollbar and the effect panel
                done = ttScene(client, "tt-creative-grid-right", () -> {}, null,
                        s -> s instanceof CreativeModeInventoryScreen ? ttScreenRel(s, 9 + 8 * 18 + 8, 18 + 2 * 18 + 8) : null);
                break;
            case 13:  // creative tab icon: the tab-name card over the other glass tabs
                done = ttScene(client, "tt-creative-tab", () -> {}, null, DevShot::ttCreativeTab);
                break;
            case 14:  // fade-out ghost: hover an item, then leave; capture 4 frames into the 150 ms fade
                done = ttScene(client, "tt-creative-ghost", () -> {}, null,
                        s -> !(s instanceof CreativeModeInventoryScreen) ? null
                                : frames < TT_SETTLE - 4 ? ttScreenRel(s, 9 + 4 * 18 + 8, 18 + 2 * 18 + 8)
                                : new double[]{8, s.height - 8});
                break;
            case 15:  // hover held THROUGH a one-row wheel step: probe every frame of the sub-pixel glide + tooltip return
                done = ttGlideScene(client);
                break;
            default:
                GuiLayerProbe.armed = false;
                ttHoverX = ttHoverY = -1;
                System.out.println("[S1mp1e][DevShot][TOOLTIP-LAYER] SUMMARY " + ttPass + " PASS / " + ttFail
                        + " FAIL / " + ttNone + " no-card");
                close(client);
                frames = 0; phase = P_DRAIN;
                return;
        }
        if (done) { ttStage++; frames = 0; }
    }

    /** Creative search tab, cursor parked on grid cell (col 4, row 2): settle on row 6, then ONE wheel step to row 7 and
     *  probe + capture 16 consecutive frames (the glide suppresses the tooltip, its card fades out as a ghost, then the
     *  live tooltip returns) - the exact sequence of the round-3 glide evidence. */
    private static boolean ttGlideScene(Minecraft client) {
        frames++;
        Screen s = currentScreen(client);
        if (frames == 1) scrollSelectSearch(client);
        if (frames == 8) { scrollSelectSearch(client); scrollSetCreativeRow(client, 6); }
        if (frames >= 3 && s instanceof CreativeModeInventoryScreen) {
            // the round-3 evidence cursor: bottom-right of grid cell (col 4, row 2), so the card overlaps row 3 by ~6 px
            double[] h = ttScreenRel(s, 9 + 4 * 18 + 17, 18 + 2 * 18 + 14);
            ttHoverX = h[0];
            ttHoverY = h[1];
        }
        ttApplyHover(client);
        final int step = 40;
        if (frames == step) scrollSetCreativeRow(client, 7);                 // ONE wheel step
        if (frames > step && frames <= step + GLIDE_FRAMES) {
            String name = String.format("tt-creative-glide-%02d", frames - step - 1);
            ttReport(client, name);
            capture(client, name + ".png");
        }
        GuiLayerProbe.armed = frames >= step && frames < step + GLIDE_FRAMES;
        return frames >= step + GLIDE_FRAMES + TT_FLUSH;
    }

    /** One hover scene; returns true when finished. {@code tick} (optional) sees every frame number; {@code hover} maps
     *  the current screen to a GUI-px cursor position (null = keep the previous one). */
    private static boolean ttScene(Minecraft client, String name, Runnable setup, java.util.function.IntConsumer tick,
                                   java.util.function.Function<Screen, double[]> hover) {
        frames++;
        if (frames == 1) {
            ttHoverX = ttHoverY = -1;
            try { setup.run(); } catch (Throwable t) { skip("tt setup " + name, t); }
        }
        if (tick != null) {
            try { tick.accept(frames); } catch (Throwable t) { skip("tt tick " + name, t); }
        }
        if (frames >= 3 && hover != null) {
            double[] h = null;
            try { h = hover.apply(currentScreen(client)); } catch (Throwable t) { if (frames == TT_SETTLE - 2) skip("tt hover " + name, t); }
            if (h != null) { ttHoverX = h[0]; ttHoverY = h[1]; }
        }
        ttApplyHover(client);
        if (frames == TT_SETTLE - 1) {
            GuiLayerProbe.armed = true;        // the NEXT frame (extraction + GuiRenderer.render) is analysed
        } else if (frames == TT_SETTLE) {
            GuiLayerProbe.armed = false;
            ttReport(client, name);
            capture(client, name + ".png");
        } else if (frames >= TT_SETTLE + TT_FLUSH) {
            return true;
        }
        return false;
    }

    private static void ttReport(Minecraft client, String name) {
        int off = GuiLayerProbe.lastOffenders;
        Screen s = currentScreen(client);
        String verdict = off < 0 ? "NO-CARD" : off == 0 ? "PASS" : "FAIL";
        if (off < 0) ttNone++; else if (off == 0) ttPass++; else ttFail++;
        System.out.println("[S1mp1e][DevShot][TOOLTIP-LAYER] " + verdict + " " + name
                + " (" + (s == null ? "no screen" : s.getClass().getSimpleName())
                + ", hover " + Math.round(ttHoverX) + "," + Math.round(ttHoverY) + ") " + GuiLayerProbe.lastReport
                + " [split draw " + com.seagull.liquidglass.client.render.TooltipLayer.lastSplitDrawIndex + "/"
                + com.seagull.liquidglass.client.render.TooltipLayer.lastDrawCount
                + (com.seagull.liquidglass.client.render.TooltipLayer.lastOverlayGrabbed ? ", GUI-below grabbed]" : ", no grab]"));
    }

    /** Park the GUI cursor at (ttHoverX, ttHoverY) GUI px by writing the MouseHandler position the screens read. The
     *  real OS cursor is not moved (no glfwSetCursorPos), so the sweep does not fight whoever uses the desktop. */
    private static void ttApplyHover(Minecraft client) {
        if (ttHoverX < 0 || ttHoverY < 0) return;
        try {
            Window w = client.getWindow();
            double px = ttHoverX * w.getScreenWidth() / (double) w.getGuiScaledWidth();
            double py = ttHoverY * w.getScreenHeight() / (double) w.getGuiScaledHeight();
            if (ttMouseX == null) {
                java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                fx.setAccessible(true);
                fy.setAccessible(true);
                ttMouseX = fx;
                ttMouseY = fy;
            }
            ttMouseX.setDouble(client.mouseHandler, px);
            ttMouseY.setDouble(client.mouseHandler, py);
        } catch (Throwable t) {
            skip("tt park cursor", t);
            ttHoverX = ttHoverY = -1;
        }
    }

    private static void ttServerCommand(Minecraft client, String cmd) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) return;
            server.execute(() -> {
                try { server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd); }
                catch (Throwable t) { skip("tt command " + cmd, t); }
            });
        } catch (Throwable t) { skip("tt command " + cmd, t); }
    }

    /** Fill main inventory slots 9..35 (server + client mirror) with an assortment; slot 10 holds a diamond sword. */
    private static void ttFillInventory(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            List<ServerPlayer> players = server == null ? List.of() : server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            for (int i = 9; i < 36; i++) {
                net.minecraft.world.item.Item it = TT_POOL[(i - 9) % TT_POOL.length];
                ItemStack st = it == Items.DIAMOND_SWORD ? new ItemStack(it) : new ItemStack(it, 1 + (i * 7) % 16);
                if (sp != null) sp.getInventory().setItem(i, st.copy());
                if (client.player != null) client.player.getInventory().setItem(i, st.copy());
            }
        } catch (Throwable t) { skip("tt fill inventory", t); }
    }

    /** Open a 3-row chest (server-side, synced to the client) full of items; chest slot 1 holds a diamond sword. */
    private static void ttOpenChest(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) return;
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp == null) return;
            net.minecraft.world.SimpleContainer c = new net.minecraft.world.SimpleContainer(27);
            for (int i = 0; i < 27; i++) c.setItem(i, new ItemStack(TT_POOL[(i + 5) % TT_POOL.length], 1 + (i * 5) % 16));
            c.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
            sp.openMenu(new SimpleMenuProvider((id, inv, p) -> net.minecraft.world.inventory.ChestMenu.threeRows(id, inv, c),
                    Component.literal("箱子")));
        } catch (Throwable t) { skip("tt open chest", t); }
    }

    /** Centre (GUI px) of menu slot {@code menuIndex} of a container screen. */
    private static double[] ttSlotCenter(Screen s, int menuIndex) {
        if (!(s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs)) return null;
        AbstractContainerMenu menu = cs.getMenu();
        if (menuIndex < 0 || menuIndex >= menu.slots.size()) return null;
        net.minecraft.world.inventory.Slot sl = menu.slots.get(menuIndex);
        return ttScreenRel(s, sl.x + 8, sl.y + 8);
    }

    /** A point given relative to a container screen's leftPos/topPos. */
    private static double[] ttScreenRel(Screen s, double rx, double ry) {
        com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
        return new double[]{acc.liquidglass$leftPos() + rx, acc.liquidglass$topPos() + ry};
    }

    private static double[] ttRecipeButton(Screen s, int idx) {
        if (!(s instanceof CraftingScreen)) return null;
        try {
            Object pg = lg$recipeBookPage(s);
            java.lang.reflect.Field bf = RecipeBookPage.class.getDeclaredField("buttons");
            bf.setAccessible(true);
            List<?> buttons = (List<?>) bf.get(pg);
            int seen = 0;
            for (Object o : buttons) {
                net.minecraft.client.gui.components.AbstractWidget w = (net.minecraft.client.gui.components.AbstractWidget) o;
                if (!w.visible) continue;
                if (seen++ == idx) return new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
            }
        } catch (Throwable t) { /* page not ready yet */ }
        return null;
    }

    private static double[] ttRecipeFilter(Screen s) {
        if (!(s instanceof CraftingScreen)) return null;
        try {
            java.lang.reflect.Field cf = AbstractRecipeBookScreen.class.getDeclaredField("recipeBookComponent");
            cf.setAccessible(true);
            Object comp = cf.get(s);
            java.lang.reflect.Field ff = RecipeBookComponent.class.getDeclaredField("filterButton");
            ff.setAccessible(true);
            net.minecraft.client.gui.components.AbstractWidget w = (net.minecraft.client.gui.components.AbstractWidget) ff.get(comp);
            if (w == null || !w.visible) return null;
            return new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
        } catch (Throwable t) { return null; }
    }

    /** Root (left-most) advancement node of the selected tab, in screen GUI px. */
    private static double[] ttAdvancementRoot(Screen s) {
        if (!(s instanceof AdvancementsScreen)) return null;
        try {
            java.lang.reflect.Field ft = AdvancementsScreen.class.getDeclaredField("selectedTab");
            ft.setAccessible(true);
            Object tab = ft.get(s);
            if (tab == null) return null;
            Class<?> tc = tab.getClass();
            java.lang.reflect.Field fw = tc.getDeclaredField("widgets");
            java.lang.reflect.Field fx = tc.getDeclaredField("scrollX");
            java.lang.reflect.Field fy = tc.getDeclaredField("scrollY");
            fw.setAccessible(true);
            fx.setAccessible(true);
            fy.setAccessible(true);
            java.lang.reflect.Field fl = AdvancementsScreen.class.getDeclaredField("leftPos");
            java.lang.reflect.Field fto = AdvancementsScreen.class.getDeclaredField("topPos");
            fl.setAccessible(true);
            fto.setAccessible(true);
            net.minecraft.client.gui.screens.advancements.AdvancementWidget best = null;
            for (Object o : ((java.util.Map<?, ?>) fw.get(tab)).values()) {
                net.minecraft.client.gui.screens.advancements.AdvancementWidget w =
                        (net.minecraft.client.gui.screens.advancements.AdvancementWidget) o;
                if (best == null || w.getX() < best.getX() || (w.getX() == best.getX() && w.getY() < best.getY())) best = w;
            }
            if (best == null) return null;
            int sx = (int) Math.floor(fx.getDouble(tab)), sy = (int) Math.floor(fy.getDouble(tab));
            return new double[]{fl.getInt(s) + 9 + sx + best.getX() + 13, fto.getInt(s) + 18 + sy + best.getY() + 13};
        } catch (Throwable t) { return null; }
    }

    /** Centre of the first on-screen widget that carries a tooltip (a slider when {@code slider}, else a non-slider). */
    private static double[] ttWidgetWithTooltip(Screen s, boolean slider) {
        if (s == null) return null;
        try {
            List<net.minecraft.client.gui.components.AbstractWidget> all = new ArrayList<>();
            ttCollectWidgets(s, all, 0);
            java.lang.reflect.Field tf = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
            tf.setAccessible(true);
            for (net.minecraft.client.gui.components.AbstractWidget w : all) {
                if (!w.visible || (w instanceof net.minecraft.client.gui.components.AbstractSliderButton) != slider) continue;
                Object holder = tf.get(w);
                if (!(holder instanceof net.minecraft.client.gui.components.WidgetTooltipHolder h) || h.get() == null) continue;
                double cx = w.getX() + w.getWidth() / 2.0, cy = w.getY() + w.getHeight() / 2.0;
                if (cy < 40 || cy > s.height - 50) continue;
                return new double[]{cx, cy};
            }
        } catch (Throwable t) { /* not ready */ }
        return null;
    }

    private static void ttCollectWidgets(Object node, List<net.minecraft.client.gui.components.AbstractWidget> out, int depth) {
        if (node == null || depth > 8) return;
        if (node instanceof net.minecraft.client.gui.components.AbstractWidget w) out.add(w);
        if (node instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
            for (Object ch : c.children()) ttCollectWidgets(ch, out, depth + 1);
        }
    }

    /** Centre of the 2nd top-row creative tab (its name tooltip overlaps the neighbouring glass tabs). */
    private static double[] ttCreativeTab(Screen s) {
        if (!(s instanceof CreativeModeInventoryScreen)) return null;
        try {
            java.lang.reflect.Method gx = CreativeModeInventoryScreen.class.getDeclaredMethod("getTabX", net.minecraft.world.item.CreativeModeTab.class);
            java.lang.reflect.Method gy = CreativeModeInventoryScreen.class.getDeclaredMethod("getTabY", net.minecraft.world.item.CreativeModeTab.class);
            gx.setAccessible(true);
            gy.setAccessible(true);
            for (net.minecraft.world.item.CreativeModeTab t : net.minecraft.world.item.CreativeModeTabs.tabs()) {
                if (t.row() == net.minecraft.world.item.CreativeModeTab.Row.TOP && t.column() == 1 && t.shouldDisplay()) {
                    return ttScreenRel(s, (int) gx.invoke(s, t) + 13, (int) gy.invoke(s, t) + 16);
                }
            }
        } catch (Throwable t) { /* not ready */ }
        return null;
    }

    // ---- effects sweep (S1MP1E_SHOT_MODE=effects): the now-glass status-effect panel beside the inventory ----

    private static final int FX_WARM   = 14;   // frames for the effects / gamemode change to reach the client before opening
    private static final int FX_SETTLE = 48;   // frames rendered before the capture request (open fade fully done)
    private static final int FX_FLUSH  = 8;    // extra frames after it for the async read-back

    /**
     * Give the player a representative spread of effects and shoot the now-glass {@code EffectsInInventory} panel:
     * <ol>
     *   <li>{@code effects-survival-wide.png} — survival inventory, WIDE layout (icon + name + remaining time);</li>
     *   <li>{@code effects-creative-wide.png} — creative inventory, WIDE layout;</li>
     *   <li>{@code effects-chest.png} — a chest, which documents that vanilla shows NO effect panel on plain containers
     *       (only the player inventory + creative own an {@code EffectsInInventory}); the shot is the chest itself;</li>
     *   <li>{@code effects-compact.png} — survival inventory at GUI scale 4 (narrow), so the panel falls into its COMPACT
     *       icon-only layout; the first box is hovered and {@link GuiLayerProbe} verifies the compact hover tooltip is on
     *       the very top layer (a {@code [TOOLTIP-LAYER]} PASS/FAIL line is logged).</li>
     * </ol>
     * The spread is 6 effects (&gt;5, so vanilla packs them tighter — the "stacked" case): beneficial (Speed, Strength II,
     * Haste), harmful (Poison), ambient (Regeneration), and infinite (Night Vision). Fully guarded; inert unless
     * {@code S1MP1E_SHOT_MODE=effects}.
     */
    private static void stepEffects(Minecraft client) {
        final LocalPlayer player = client.player;
        if (player == null || client.gui == null) { forcedScale = 2; GuiLayerProbe.armed = false; frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}

        switch (effectsStage) {
            case 0: {   // WIDE survival inventory
                if (frames == 0) {
                    tabsCameraDown(client);          // terrain fills the backdrop the glass refracts (not the bright sky)
                    effectsGive(client);
                }
                frames++;
                if (frames == FX_WARM) open(client, new InventoryScreen(player), "effects survival");
                if (frames == FX_WARM + FX_SETTLE)               capture(client, "effects-survival-wide.png");
                else if (frames >= FX_WARM + FX_SETTLE + FX_FLUSH) { close(client); effectsStage = 1; frames = 0; }
                return;
            }
            case 1: {   // WIDE creative inventory
                if (frames == 0) {
                    tabsCameraDown(client);
                    tabsGamemodeCreative(client);
                    effectsGive(client);
                    try {
                        net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(
                                player.connection.enabledFeatures(), true, client.level.registryAccess());
                    } catch (Throwable t) { skip("effects rebuild creative", t); }
                }
                frames++;
                if (frames == FX_WARM) {
                    open(client, new CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), true), "effects creative");
                    tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.CATEGORY));
                }
                if (frames == FX_WARM + 8) { effectsGive(client); tabsSelect(client, tabsPick(net.minecraft.world.item.CreativeModeTab.Type.CATEGORY)); }
                if (frames == FX_WARM + FX_SETTLE)               capture(client, "effects-creative-wide.png");
                else if (frames >= FX_WARM + FX_SETTLE + FX_FLUSH) { close(client); effectsStage = 2; frames = 0; }
                return;
            }
            case 2: {   // chest — vanilla draws NO effect panel on plain containers; documents that (shot is the chest)
                if (frames == 0) {
                    tabsGamemodeSurvival(client);
                    effectsGive(client);
                    ttOpenChest(client);
                }
                frames++;
                if (frames == FX_SETTLE)               capture(client, "effects-chest.png");
                else if (frames >= FX_SETTLE + FX_FLUSH) { close(client); effectsStage = 3; frames = 0; }
                return;
            }
            case 3: {   // COMPACT survival inventory (narrow window -> icon-only) + top-layer hover tooltip
                if (frames == 0) {
                    // Narrow the window so the space right of the 176 px inventory drops below 120 px: at 760x720, scale 2,
                    // guiScaledWidth=380, i=leftPos+178=280, j=380-280=100 (<120) -> COMPACT icon-only, and j>=32 so the
                    // panel still shows. (GUI scale 4 is not usable at 720 p height -- MC clamps it to 3, which stays wide.)
                    forcedW = 760; forcedH = 720; forcedScale = 2;
                    effectsGive(client);
                    ttHoverX = ttHoverY = -1;
                }
                frames++;
                if (frames == FX_WARM) open(client, new InventoryScreen(player), "effects compact");
                Screen s = currentScreen(client);
                if (frames >= FX_WARM + 4 && s instanceof InventoryScreen) {
                    double[] h = effectsFirstBoxHover(s);
                    if (h != null) { ttHoverX = h[0]; ttHoverY = h[1]; }
                }
                ttApplyHover(client);
                if (frames == FX_WARM + FX_SETTLE - 1) {
                    GuiLayerProbe.armed = true;      // the NEXT frame (extraction + GuiRenderer.render) is analysed
                } else if (frames == FX_WARM + FX_SETTLE) {
                    GuiLayerProbe.armed = false;
                    ttReport(client, "effects-compact");
                    capture(client, "effects-compact.png");
                } else if (frames >= FX_WARM + FX_SETTLE + FX_FLUSH) {
                    close(client); forcedW = SHOT_W; forcedH = SHOT_H; forcedScale = 2; ttHoverX = ttHoverY = -1; effectsStage = 4; frames = 0;
                }
                return;
            }
            default:
                close(client); forcedW = SHOT_W; forcedH = SHOT_H; forcedScale = 2; GuiLayerProbe.armed = false;
                frames = 0; phase = P_DRAIN;
        }
    }

    /** A fresh spread of six effects: beneficial (Speed, Strength II, Haste), harmful (Poison), ambient (Regeneration),
     *  infinite (Night Vision). New instances each call (they are ticked, so server and client must not share one). */
    private static List<MobEffectInstance> effectsList() {
        List<MobEffectInstance> l = new ArrayList<>();
        l.add(new MobEffectInstance(MobEffects.SPEED, 1200, 0, false, false, true));        // beneficial, 60 s
        l.add(new MobEffectInstance(MobEffects.STRENGTH, 3600, 1, false, false, true));     // beneficial, "Strength II"
        l.add(new MobEffectInstance(MobEffects.HASTE, 900, 0, false, false, true));         // beneficial
        l.add(new MobEffectInstance(MobEffects.POISON, 600, 0, false, false, true));        // harmful
        l.add(new MobEffectInstance(MobEffects.REGENERATION, 1800, 0, true, false, true));  // ambient (beacon-style border)
        l.add(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false, true));   // infinite -> shows the infinity label
        l.add(new MobEffectInstance(MobEffects.JUMP_BOOST, 2400, 1, false, false, true));   // 7th/8th only with S1MP1E_SHOT_FX_COUNT
        l.add(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 4800, 0, false, false, true));
        // S1MP1E_SHOT_FX_COUNT=N (1..8, default 6) picks how many effects the sweep gives (strip layout at 1/3/6/8 entries).
        int n = 6;
        try { String c = System.getenv("S1MP1E_SHOT_FX_COUNT"); if (c != null) n = Math.max(1, Math.min(l.size(), Integer.parseInt(c.trim()))); } catch (Throwable ignored) {}
        return new ArrayList<MobEffectInstance>(l.subList(0, n));
    }

    // ---- combat sweep (S1MP1E_SHOT_MODE=combat): LowFire / AttackRing / HitMarker ----

    private static boolean combatMode;
    private static int     combatStage;
    private static long    combatT0;
    private static int     combatShot;
    private static int     combatFlush;
    private static boolean combatSwung;
    /** Attack-cooldown ring: shots at these wall-clock offsets after resetting the attack ticker (sword ~625 ms). */
    private static final long[] COMBAT_RING_MS = { 0, 80, 160, 260, 360, 480, 620, 800 };
    /** Hit marker: shots after the swing (the server's damage event lands ~1 tick later). */
    private static final long[] COMBAT_HIT_MS = { 40, 80, 130, 200, 280, 360 };

    /** Shoot {@code prefix-NNNms.png} at each scheduled wall-clock offset from {@link #combatT0}; true once all are taken. */
    private static boolean combatTimed(Minecraft client, String prefix, long[] schedule) {
        if (combatShot >= schedule.length) {
            if (++combatFlush > HUD_FLUSH) { combatFlush = 0; combatShot = 0; frames = 0; return true; }
            return false;
        }
        long el = (System.nanoTime() - combatT0) / 1_000_000L;
        if (el >= schedule[combatShot]) {
            capture(client, String.format("%s-%03dms.png", prefix, schedule[combatShot]));
            combatShot++;
        }
        return false;
    }

    private static long combatElapsedMs() {
        return (System.nanoTime() - combatT0) / 1_000_000L;
    }

    /** The nearest pig (the scripted target). */
    private static net.minecraft.world.entity.Entity combatTarget(Minecraft client) {
        try {
            LocalPlayer p = client.player;
            net.minecraft.world.entity.Entity best = null;
            double bd = Double.MAX_VALUE;
            for (net.minecraft.world.entity.Entity e : client.level.entitiesForRendering()) {
                if (e.getType() == net.minecraft.world.entity.EntityTypes.PIG) {
                    double d = e.distanceToSqr(p);
                    if (d < bd) { bd = d; best = e; }
                }
            }
            return best;
        } catch (Throwable t) {
            skip("combat target", t);
            return null;
        }
    }

    /** Look at the target's chest. */
    private static void combatAim(Minecraft client, net.minecraft.world.entity.Entity e) {
        if (e == null) return;
        try {
            LocalPlayer cp = client.player;
            double dx = e.getX() - cp.getX(), dy = (e.getY() + e.getBbHeight() * 0.6) - cp.getEyeY(), dz = e.getZ() - cp.getZ();
            double h = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, h));
            cp.setYRot(yaw); cp.yRotO = yaw; cp.setYHeadRot(yaw);
            cp.setXRot(pitch); cp.xRotO = pitch;
        } catch (Throwable t) {
            skip("combat aim", t);
        }
    }

    /** Set the player on fire (server-authoritative + client mirror) or put it out. */
    private static void combatFire(Minecraft client, boolean on) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        if (on) sp.igniteForSeconds(30f); else sp.clearFire();
                    }
                });
            }
        } catch (Throwable t) { skip("combat fire (server)", t); }
        try {
            if (on) client.player.igniteForSeconds(30f); else client.player.clearFire();
        } catch (Throwable t) { skip("combat fire (client)", t); }
    }

    private static void combatSwing(Minecraft client, boolean crit) {
        net.minecraft.world.entity.Entity z = combatTarget(client);
        if (z == null) { skip("combat swing", new IllegalStateException("no pig target")); return; }
        try {
            LocalPlayer cp = client.player;
            combatAim(client, z);
            cp.setSprinting(false);
            if (crit) {   // Player.canCriticalAttack: falling, not on ground, not sprinting, strength > 0.9
                cp.setOnGround(false);
                cp.fallDistance = 1.0;
                cp.setDeltaMovement(new net.minecraft.world.phys.Vec3(0.0, -0.3, 0.0));
            }
            client.gameMode.attack(cp, z);
            cp.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (Throwable t) {
            skip("combat swing", t);
        }
    }

    private static void stepCombat(Minecraft client) {
        if (client.player == null || client.gui == null) { frames = 0; phase = P_DRAIN; return; }
        try { client.gui.toastManager().clear(); } catch (Throwable ignored) {}
        try { client.gui.hud.getChat().clearMessages(false); } catch (Throwable ignored) {}
        dev.s1mp1e.client.Module lowFire = ModuleManager.byName("LowFire");

        switch (combatStage) {
            case 0: {   // burning, LowFire OFF = vanilla reference
                if (frames == 0) {
                    client.player.setXRot(0f); client.player.xRotO = 0f;
                    if (lowFire != null) lowFire.setEnabled(false);
                    combatFire(client, true);
                }
                if (advanceHud(client, "fire-off.png")) combatStage = 1;
                return;
            }
            case 1: {   // burning, LowFire ON (defaults: lower 0.3, opacity 0.6)
                if (frames == 0 && lowFire != null) lowFire.setEnabled(true);
                if (advanceHud(client, "fire-on.png")) { combatFire(client, false); combatStage = 2; }
                return;
            }
            case 2: {   // a still pig 3 blocks ahead
                if (frames == 0) {
                    ttServerCommand(client, "kill @e[type=minecraft:pig]");
                    // the DevShot world is PEACEFUL (hostile mobs cannot exist) -> a still pig is the target
                    ttServerCommand(client, "execute as @p at @s run summon minecraft:pig ~ ~ ~3 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:100f,"
                        + "attributes:[{id:\"minecraft:max_health\",base:100d}]}");
                }
                frames++;
                combatAim(client, combatTarget(client));
                if (frames >= 60) {
                    if (combatTarget(client) == null) skip("combat target", new IllegalStateException("summoned pig did not reach the client"));
                    frames = 0; combatStage = 3;
                }
                return;
            }
            case 3: {   // attack-cooldown ring filling after a reset
                if (combatShot == 0 && combatFlush == 0 && frames == 0) {
                    client.player.resetAttackStrengthTicker();
                    combatT0 = System.nanoTime();
                    frames = 1;
                }
                combatAim(client, combatTarget(client));
                if (combatTimed(client, "ring", COMBAT_RING_MS)) combatStage = 4;
                return;
            }
            case 4: {   // charged + aiming at a living target with a sword: the ring stays full
                combatAim(client, combatTarget(client));
                if (frames == 0) combatT0 = System.nanoTime();
                frames++;
                if (combatElapsedMs() >= 900 && frames < 100000) { capture(client, "ring-ready.png"); frames = 100000; }
                else if (frames >= 100000 + HUD_FLUSH) { frames = 0; combatStage = 5; }
                return;
            }
            case 5: {   // normal hit -> hit marker (hit colour)
                if (combatShot == 0 && combatFlush == 0 && frames == 0) {
                    combatSwing(client, false);
                    combatT0 = System.nanoTime();
                    frames = 1;
                }
                if (combatTimed(client, "hit", COMBAT_HIT_MS)) combatStage = 6;
                return;
            }
            case 6: {   // let the target's hurt-invulnerability and the attack cooldown run out
                combatAim(client, combatTarget(client));
                if (frames == 0) combatT0 = System.nanoTime();
                frames++;
                if (combatElapsedMs() >= 1100) { frames = 0; combatStage = 7; }
                return;
            }
            case 7: {   // critical hit: a REAL server-side crit. 26.2 decides crits on the server, so the player is
                        // teleported up and swings while actually falling (the server sees onGround=false + fallDistance).
                if (!combatSwung) {
                    LocalPlayer cp = client.player;
                    if (frames == 0) {
                        ttServerCommand(client, "execute as @p at @s run tp @s ~ ~1.25 ~");
                        combatT0 = System.nanoTime();
                        frames = 1;
                        return;
                    }
                    combatAim(client, combatTarget(client));
                    boolean falling = !cp.onGround() && cp.getDeltaMovement().y < -0.08 && cp.fallDistance > 0.15;
                    if (falling || combatElapsedMs() > 3000) {
                        if (!falling) skip("combat crit", new IllegalStateException("player never fell; swinging anyway"));
                        System.out.println("[S1mp1e][DevShot] crit swing: onGround=" + cp.onGround() + " vy=" + cp.getDeltaMovement().y + " fall=" + cp.fallDistance);
                        combatSwing(client, false);
                        combatT0 = System.nanoTime();
                        combatShot = 0;
                        combatFlush = 0;
                        combatSwung = true;
                    }
                    return;
                }
                if (combatTimed(client, "crit", COMBAT_HIT_MS)) { combatSwung = false; frames = 0; combatStage = 8; }
                return;
            }
            case 8: {   // new options: counter-clockwise + rainbow ring (charging)
                dev.s1mp1e.client.module.AttackRingModule ring =
                        (dev.s1mp1e.client.module.AttackRingModule) ModuleManager.byName("AttackRing");
                if (frames == 0) {
                    if (ring != null) { ring.clockwise.boolValue = false; ring.chroma.boolValue = true; }
                    client.player.resetAttackStrengthTicker();
                    combatT0 = System.nanoTime();
                }
                frames++;
                combatAim(client, combatTarget(client));
                if (combatElapsedMs() >= 300 && frames < 100000) { capture(client, "ring-ccw-chroma.png"); frames = 100000; }
                else if (frames >= 100000 + HUD_FLUSH) {
                    if (ring != null) { ring.clockwise.boolValue = true; ring.chroma.boolValue = false; }
                    frames = 0; combatStage = 9;
                }
                return;
            }
            case 9: {   // new options: "+" shape, a KILL (a 1-HP pig) -> kill colour
                dev.s1mp1e.client.module.HitMarkerModule hm =
                        (dev.s1mp1e.client.module.HitMarkerModule) ModuleManager.byName("HitMarker");
                if (frames == 0) {
                    if (hm != null) hm.shape.modeValue = "Cross";
                    ttServerCommand(client, "kill @e[type=minecraft:pig]");
                    ttServerCommand(client, "execute as @p at @s run summon minecraft:pig ~ ~ ~3 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:1f}");
                    combatT0 = System.nanoTime();
                    frames = 1;
                    return;
                }
                if (!combatSwung) {
                    combatAim(client, combatTarget(client));
                    if (combatElapsedMs() >= 1100) {             // pig synced + cooldown full
                        combatSwing(client, false);
                        combatT0 = System.nanoTime();
                        combatShot = 0; combatFlush = 0;
                        combatSwung = true;
                    }
                    return;
                }
                if (combatTimed(client, "kill-cross", COMBAT_HIT_MS)) {
                    combatSwung = false;
                    if (hm != null) hm.shape.modeValue = "X";
                    frames = 0; combatStage = 10;
                }
                return;
            }
            case 10:    // shapes while charging: rounded square, then the crosshair wrap
            case 11: {
                dev.s1mp1e.client.module.AttackRingModule ring =
                        (dev.s1mp1e.client.module.AttackRingModule) ModuleManager.byName("AttackRing");
                if (frames == 0) {
                    if (ring != null) ring.shape.modeValue = combatStage == 10 ? "Square" : "Wrap";
                    client.player.resetAttackStrengthTicker();
                    combatT0 = System.nanoTime();
                }
                frames++;
                if (combatElapsedMs() >= 330 && frames < 100000) {
                    capture(client, combatStage == 10 ? "ring-square.png" : "ring-wrap.png");
                    frames = 100000;
                } else if (frames >= 100000 + HUD_FLUSH) {
                    if (ring != null) ring.shape.modeValue = "Circle";
                    frames = 0; combatStage++;
                }
                return;
            }
            case 12: {  // ready shape = Wrap: charging circle, then the full indicator wraps the crosshair on a pig
                dev.s1mp1e.client.module.AttackRingModule ring =
                        (dev.s1mp1e.client.module.AttackRingModule) ModuleManager.byName("AttackRing");
                if (frames == 0) {
                    if (ring != null) { ring.readyShape.modeValue = "Wrap"; ring.readyRadius.doubleValue = 9.0; }
                    ttServerCommand(client, "execute as @p at @s run summon minecraft:pig ~ ~ ~3 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:100f,"
                            + "attributes:[{id:\"minecraft:max_health\",base:100d}]}");
                    combatT0 = System.nanoTime();
                }
                frames++;
                combatAim(client, combatTarget(client));
                if (combatElapsedMs() >= 1300 && frames < 100000) { capture(client, "ring-ready-wrap.png"); frames = 100000; }
                else if (frames >= 100000 + HUD_FLUSH) {
                    if (ring != null) { ring.readyShape.modeValue = "Same"; ring.readyRadius.doubleValue = 0.0; }
                    frames = 0; combatStage = 13;
                }
                return;
            }
            case 13:    // Fit crosshair: the S1mp1e crosshair (big, thick, rotated 45) -> the wrap follows it
            case 14: {  // ... and a Circle crosshair -> a round wrap
                dev.s1mp1e.client.module.AttackRingModule ring =
                        (dev.s1mp1e.client.module.AttackRingModule) ModuleManager.byName("AttackRing");
                dev.s1mp1e.client.module.CrosshairModule ch =
                        (dev.s1mp1e.client.module.CrosshairModule) ModuleManager.byName("Crosshair");
                if (frames == 0) {
                    if (ch != null) {
                        ch.enabled = true;
                        ch.shape.modeValue = combatStage == 13 ? "Cross" : "Circle";
                        ch.size.intValue = 7; ch.gap.intValue = 3; ch.thick.intValue = 2;
                        ch.rotation.intValue = combatStage == 13 ? 45 : 0;
                    }
                    if (ring != null) ring.shape.modeValue = "Wrap";
                    client.player.resetAttackStrengthTicker();
                    combatT0 = System.nanoTime();
                }
                frames++;
                if (combatElapsedMs() >= 330 && frames < 100000) {
                    capture(client, combatStage == 13 ? "fit-cross45.png" : "fit-circle.png");
                    frames = 100000;
                } else if (frames >= 100000 + HUD_FLUSH) {
                    if (ch != null) {
                        ch.enabled = false; ch.shape.modeValue = "Cross";
                        ch.size.intValue = 4; ch.gap.intValue = 2; ch.thick.intValue = 1; ch.rotation.intValue = 0;
                    }
                    if (ring != null) ring.shape.modeValue = "Circle";
                    frames = 0; combatStage++;
                }
                return;
            }
            default: {
                ttServerCommand(client, "kill @e[type=minecraft:pig]");
                frames = 0; phase = P_DRAIN;
            }
        }
    }

    /** Replace the player's effects (server + client mirror) with {@link #effectsList()}. */
    private static void effectsGive(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            List<ServerPlayer> players = server == null ? List.of() : server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp != null) { sp.removeAllEffects(); for (MobEffectInstance e : effectsList()) sp.addEffect(e); }
        } catch (Throwable t) { skip("effects give (server)", t); }
        try {
            LocalPlayer cp = client.player;
            if (cp != null) { cp.removeAllEffects(); for (MobEffectInstance e : effectsList()) cp.addEffect(e); }
        } catch (Throwable t) { skip("effects give (client)", t); }
    }

    /** Put the dev player back into survival (server-authoritative + client-local). */
    private static void tabsGamemodeSurvival(Minecraft client) {
        try {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode survival @a");
        } catch (Throwable t) { skip("effects gamemode survival (server)", t); }
        try { client.gameMode.setLocalMode(GameType.SURVIVAL); } catch (Throwable t) { skip("effects gamemode survival (client)", t); }
    }

    /** Centre (GUI px) of the first (top) compact effect box: it starts at (leftPos+imageWidth+2, topPos), 32x32. */
    private static double[] effectsFirstBoxHover(Screen s) {
        if (!(s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return null;
        com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor acc =
                (com.seagull.liquidglass.client.mixin.AbstractContainerScreenAccessor) (Object) s;
        return ttScreenRel(s, acc.liquidglass$imageWidth() + 2 + 16, 13);
    }

    // ---- step 8: drain async screenshots, then quit -----------------------

    private static void stepDrain(Minecraft client) {
        // The async GPU read-backs complete during rendering; render a few extra frames so every pending PNG
        // write flushes to disk before the run loop exits.
        if (++frames < DRAIN_FRAMES) return;
        phase = P_STOP;
    }

    private static void stepStop(Minecraft client) {
        System.out.println("[S1mp1e][DevShot] done, quitting.");
        quit(client);
        phase = P_DONE;
    }

    /**
     * Clean leave+quit: set {@link Minecraft}'s volatile {@code running} field false. The run loop then exits and
     * MC's normal shutdown stops the integrated server and saves/unloads the world. We deliberately do NOT call
     * {@code disconnect()} from this render-TAIL hook — disconnect runs its own nested world-unload render loop,
     * which would wedge the render thread. Dev-only reflection (DevShot runs solely under {@code S1MP1E_SHOT}).
     */
    private static void quit(Minecraft client) {
        try {
            java.lang.reflect.Field running = Minecraft.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(client, false);
        } catch (Throwable t) {
            skip("schedule quit (running=false)", t);
        }
    }

    // ---- world creation + setup -------------------------------------------

    /** Delete any previous {@code devshot} save and start a fresh flat world. Attempted once only. */
    private static boolean createWorld(Minecraft client) {
        if (worldTried) return false;   // never start world creation twice
        worldTried = true;
        try {
            File saves = new File(client.gameDirectory, "saves");
            deleteRecursively(new File(saves, "devshot"));
        } catch (Throwable t) {
            skip("delete previous devshot save", t);
        }
        try {
            LevelSettings settings = new LevelSettings(
                    "devshot", GameType.SURVIVAL,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true /* allowCommands / cheats */, WorldDataConfiguration.DEFAULT);
            WorldOptions opts = new WorldOptions(12345L, false /* structures */, false /* bonus chest */);
            Function<HolderLookup.Provider, WorldDimensions> dims = provider ->
                    provider.lookupOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
            WorldOpenFlows flows = client.createWorldOpenFlows();
            flows.createFreshLevel("devshot", settings, opts, dims, new TitleScreen());
            return true;
        } catch (Throwable t) {
            skip("create world", t);
            return false;
        }
    }

    /** Time / weather / camera angle / scripted loadout. Each sub-part is independently guarded. */
    private static void applyWorldSetup(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        // time (26.2 world-clock system)
        try {
            Holder<WorldClock> clock = server.registryAccess()
                    .lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
            server.clockManager().setTotalTicks(clock, 6000L);
        } catch (Throwable t) {
            skip("set time", t);
        }
        // clear weather (server-authoritative)
        try {
            server.setWeatherParameters(1_000_000, 0, false, false);
        } catch (Throwable t) {
            skip("set weather", t);
        }
        // loadout on the server player (auto-syncs to the client within a couple of ticks)
        try {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            ServerPlayer sp = players.isEmpty() ? null : players.get(0);
            if (sp != null) {
                sp.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                sp.getInventory().setItem(1, new ItemStack(Items.COOKED_BEEF, 32));
                sp.getInventory().setItem(2, new ItemStack(Items.STONE, 64));
                sp.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                sp.setItemSlot(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
                sp.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                sp.setItemSlot(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
                sp.setItemSlot(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
                // 60 s Speed I, icon on but no particles (keeps the reference frame clean).
                sp.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200, 0, false, false, true));
                // Fixed spot, facing yaw 0 / pitch 15 (looking slightly down at the flat plain).
                sp.snapTo(sp.getX(), sp.getY(), sp.getZ());
                sp.setYRot(0f);
                sp.setXRot(15f);
                sp.setYHeadRot(0f);
                sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), 0f, 15f);
            }
        } catch (Throwable t) {
            skip("give loadout", t);
        }
        // mirror the loadout + camera on the client player so the very next frame already shows it
        try {
            LocalPlayer cp = client.player;
            cp.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            cp.getInventory().setItem(1, new ItemStack(Items.COOKED_BEEF, 32));
            cp.getInventory().setItem(2, new ItemStack(Items.STONE, 64));
            cp.getInventory().setSelectedSlot(0);             // hold the sword
            cp.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            cp.setItemSlot(EquipmentSlot.HEAD,  new ItemStack(Items.IRON_HELMET));
            cp.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            cp.setItemSlot(EquipmentSlot.LEGS,  new ItemStack(Items.IRON_LEGGINGS));
            cp.setItemSlot(EquipmentSlot.FEET,  new ItemStack(Items.IRON_BOOTS));
            cp.setYRot(0f);  cp.yRotO = 0f;  cp.setYHeadRot(0f);
            cp.setXRot(15f); cp.xRotO = 15f;
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

    // ---- trans sweep (S1MP1E_SHOT_MODE=trans): menu-to-menu screen switches, frame by frame -----------------

    private static final int TRANS_SETTLE = 40;   // frames on each screen first, so its own open fade is long done
    private static final int TRANS_FRAMES = 16;   // frames captured after each switch (t<N>-f01..f16)
    private static int transStep;
    private static Screen transTitle, transOptions, transPause;

    /**
     * Full out-of-gameplay audit. Menu part (no world): t0 Title->Options, t1 ->Video, t2 ->Options, t3 ->Title,
     * t4 ->Singleplayer, t5 ->Title, t6 ->S1mp1e settings, t7 settings closed (its own close), t8 ->Multiplayer,
     * t9 ->Title, t10 ->Create World, t11 its "World" tab, t12 its "More" tab, t13 ->Title. Then a world is created:
     * t14 game->Pause, t15 ->Options, t16 ->Pause, t17 ->game, t18 game->S1mp1e settings, t19 settings closed.
     */
    private static void stepTrans(Minecraft client) {
        frames++;
        if (frames < TRANS_SETTLE) return;
        int k = frames - TRANS_SETTLE;   // 0 = the switch frame
        String tag = "t" + transStep;
        if (k == 0) {
            if (transStep == 14 && client.level == null) {   // menu part done: into a world
                frames = 0;
                if (createWorld(client)) {
                    phase = P_WAIT_WORLD;
                } else {
                    skip("trans create world", new IllegalStateException("world creation did not start"));
                    phase = P_DRAIN;
                }
                return;
            }
            if (transStep > 19) { frames = 0; phase = P_DRAIN; return; }
            capture(client, tag + "-pre.png");   // the last frame before the switch
            Screen before = client.gui.screen();
            transAct(client, transStep);
            System.out.println("[S1mp1e][DevShot] trans " + transStep + ": " + describe(before) + " -> (act) ");
        } else if (k <= TRANS_FRAMES) {
            capture(client, String.format("%s-f%02d.png", tag, k));
            if (k == TRANS_FRAMES) System.out.println("[S1mp1e][DevShot] trans " + transStep + " now on " + describe(client.gui.screen()));
        } else if (k >= TRANS_FRAMES + 10) {   // let the async read-backs flush, then the next switch
            transStep++;
            frames = 0;
        }
    }

    private static void transAct(Minecraft client, int step) {
        Screen cur = client.gui.screen();
        switch (step) {
            case 0: transTitle = cur; transOptions = new OptionsScreen(cur, client.options, false); open(client, transOptions, "t0"); break;
            case 1: open(client, new VideoSettingsScreen(transOptions, client, client.options), "t1"); break;
            case 2: open(client, transOptions, "t2"); break;
            case 3: open(client, transTitle, "t3"); break;
            case 4: open(client, new SelectWorldScreen(transTitle), "t4"); break;
            case 5: open(client, transTitle, "t5"); break;
            case 6: open(client, new S1mp1eConfigScreen(), "t6"); break;
            case 7: if (cur != null) cur.onClose(); break;                    // the settings screen's own close
            case 8: open(client, new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(transTitle), "t8"); break;
            case 9: open(client, transTitle, "t9"); break;
            case 10:
                try {
                    net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(transTitle));
                } catch (Throwable t) { skip("t10 create world screen", t); }
                break;
            case 11: transSelectTab(cur, 1); break;
            case 12: transSelectTab(cur, 2); break;
            case 13: open(client, transTitle, "t13"); break;
            case 14: transPause = new PauseScreen(true); open(client, transPause, "t14"); break;
            case 15: transOptions = new OptionsScreen(transPause, client.options, true); open(client, transOptions, "t15"); break;
            case 16: open(client, transPause, "t16"); break;
            case 17: close(client); break;
            case 18: open(client, new S1mp1eConfigScreen(), "t18"); break;
            case 19: if (cur != null) cur.onClose(); break;
            default: break;
        }
    }

    private static void transSelectTab(Screen screen, int index) {
        if (screen == null) { skip("trans tab " + index, new IllegalStateException("no screen")); return; }
        for (Object child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.tabs.TabNavigationBar bar) {
                bar.selectTab(index, false);
                return;
            }
        }
        skip("trans tab " + index, new IllegalStateException("no TabNavigationBar on " + describe(screen)));
    }

    private static String describe(Screen s) { return s == null ? "(game)" : s.getClass().getSimpleName(); }

    private static void open(Minecraft client, Screen screen, String what) {
        try {
            client.gui.setScreen(screen);
        } catch (Throwable t) {
            skip(what, t);
        }
    }

    private static void close(Minecraft client) {
        try {
            client.gui.setScreen(null);
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

    /**
     * Capture the current framebuffer to {@code outDir/name}, overwriting. 26.2's writer is an async GPU
     * read-back: {@link Screenshot#takeScreenshot} enqueues a copy of the main render target now and invokes the
     * consumer (which writes the PNG and closes the image) a frame or two later. The drain phase renders extra
     * frames afterwards so the write completes before quit.
     */
    private static void capture(Minecraft client, String name) {
        try {
            RenderTarget target = client.gameRenderer.mainRenderTarget();
            final File out = new File(outDir, name);
            Screenshot.takeScreenshot(target, image -> {
                try (NativeImage img = image) {
                    img.writeToFile(out);
                    System.out.println("[S1mp1e][DevShot] wrote " + out.getAbsolutePath()
                            + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                } catch (Throwable t) {
                    System.out.println("[S1mp1e][DevShot] capture write failed for " + name + ": " + t);
                }
            });
        } catch (Throwable t) {
            System.out.println("[S1mp1e][DevShot] capture failed for " + name + ": " + t);
        }
    }
}
