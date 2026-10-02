package dev.s1mp1e.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.glass.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;
import net.minecraft.container.Container;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * DevShot VERIFY sweeps (1.15.2, ported from the 1.17.1 line, itself from 1.21.1) — the acceptance-checklist scenes
 * of the port spec §5, driven one frame at a time from {@link DevShot}. Completely inert unless {@code S1MP1E_SHOT} is set AND {@code S1MP1E_SHOT_MODE} names one or more of
 * these comma-separated modes (or {@code all}):
 * <ul>
 *   <li>{@code screens}  — (A) creative, crafter, horse, advancements, stats, social, book, book-edit, book-sign, lectern,
 *       death, anvil (error X), smithing, furnace (flame + arrow), brewing, enchanting, chest, survival inventory;</li>
 *   <li>({@code tabs} / {@code lists} of the newer lines are NOT here: they read the fused-tab and glide state
 *       through the GlideProbe / TabsProbe duck interfaces of those lines' container round, which this line does not
 *       have. The same screens — tab slide / cross / hover filmstrips, scroll press / drag / rubber / glide
 *       filmstrips and the click assertions — are covered by this line's own {@link DevShot} pipeline, run with no
 *       mode.)</li>
 *   <li>{@code tooltips} — (E / R1) cards over items + counts, the effect strip, the glass scrollbar, a list, stacked
 *       toasts, the compact effect column; the 150 ms ghost frames;</li>
 *   <li>{@code effects}  — (F) survival wide, creative wide, chest (no panel), compact column;</li>
 *   <li>{@code hud}      — (G) staged with integrated-server commands: chat, chat input, tab list, two boss bars, action
 *       bar (+ fading), toasts, name tag;</li>
 *   <li>{@code modules}  — (H) block outline width 1 / 8, chroma pair, fill; Chroma HUD wave / flat; zh-TW config pages
 *       (module flags + settings snapshotted and restored, nothing saved);</li>
 *   <li>{@code flicker}  — (R4) uncapped-fps frames (every 7th captured, so each shot follows a sub-ms frame) of
 *       creative / inventory / chat / tab list / merchant for an offline frame-to-frame delta.</li>
 * </ul>
 * Every step is guarded; a failure only skips that scene. PASS/FAIL lines are printed with {@code [S1mp1e][VERIFY]}.
 *
 * <p>1.15.2: this line's own {@link DevShot} is one long pipeline (no mode) plus {@code combat}; it stays as it is,
 * as the regression baseline. The modes here are selected exactly as on the 1.17.1 line, so the same command lines
 * work: {@code settings, sodium, trans, gap, newmenu, newanim, vcombat} by name, the others behind a {@code v:}
 * prefix on the mode list ({@code S1MP1E_SHOT_MODE=v:screens,tooltips,effects}); {@code intro} is DevShot's.
 * 1.15.2 API notes: creative tabs are the static {@code ItemGroup.GROUPS} array, there is no tabbed create-world
 * screen (its "More World Options" toggle is the content switch instead), no {@code /damage} command, the player list
 * is one {@code playerListEntries} map, and screens draw through a {@code MatrixStack}.
 */
final class DevShotVerify {
    private DevShotVerify() {}

    private interface Scene { boolean run(MinecraftClient c, int f, double ms); }

    private static File out;
    private static final List<String> modes = new ArrayList<String>();
    private static int mi;
    private static final List<Scene> scenes = new ArrayList<Scene>();
    private static int si, f;
    private static long t0;
    private static double hx = -1, hy = -1;          // virtual cursor (GUI px), re-applied every frame while >= 0
    private static int pass, fail;
    private static long prevStepNs;
    private static double lastFrameMs;

    static boolean handles(String mode) {
        if (mode == null) return false;
        String lower = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("v:")) return true;
        for (String m : lower.split(",")) {
            String t = m.trim();
            // 1.15.2: lists / tooltips / effects / flicker are ALSO old modes of this line (DevShot / DevShotLegacy,
            // kept as the regression baseline) -> those four need the "v:" prefix here.
            if (t.equals("vcombat") || t.equals("newmenu") || t.equals("newanim")
                    || t.equals("settings") || t.equals("trans") || t.equals("gap")) return true;
        }
        return false;
    }

    static void init(File dir, String modeCsv) {
        out = dir;
        modes.clear();
        modeCsv = modeCsv.trim().toLowerCase(java.util.Locale.ROOT);
        if (modeCsv.startsWith("v:")) modeCsv = modeCsv.substring(2);
        for (String m : modeCsv.split(",")) {
            String t = m.trim();
            if (t.equals("all")) {
                for (String a : new String[]{"screens", "tabs", "lists", "tooltips", "effects", "hud", "modules", "flicker"})
                    modes.add(a);
            } else if (!t.isEmpty()) modes.add(t);
        }
        mi = -1;
        scenes.clear();
        si = 0;
        say("modes " + modes);
    }

    /** @return true when every requested mode has finished. */
    static boolean step(MinecraftClient c) {
        if (mi < 0 || si >= scenes.size()) {
            mi++;
            if (mi >= modes.size()) {
                finish(c);
                return true;
            }
            scenes.clear();
            si = 0; f = 0; t0 = System.nanoTime();
            try { build(modes.get(mi)); } catch (Throwable t) { skip("build mode " + modes.get(mi), t); }
            say("---- mode " + modes.get(mi) + " (" + scenes.size() + " scenes)");
            return false;
        }
        f++;
        long nowNs = System.nanoTime();
        lastFrameMs = prevStepNs == 0 ? 0 : (nowNs - prevStepNs) / 1.0e6;
        prevStepNs = nowNs;
        double ms = (nowNs - t0) / 1.0e6;
        applyCursor(c);
        boolean done;
        try {
            done = scenes.get(si).run(c, f, ms);
        } catch (Throwable t) {
            skip("scene " + si + " of " + modes.get(mi), t);
            done = true;
        }
        applyCursor(c);
        if (done) { si++; f = 0; t0 = System.nanoTime(); }
        return false;
    }

    private static void finish(MinecraftClient c) {
        hx = hy = -1;
        say("CLICKS TOTAL " + pass + " PASS / " + fail + " FAIL");
        try { c.openScreen(null); } catch (Throwable ignored) {}
        restoreModules();
        DevShot.setTarget(1280, 720);
    }

    // ============================================================================================================
    //  mode builders
    // ============================================================================================================

    private static void build(String mode) {
        // common prologue: clean HUD, wait out any closing-panel ghost
        add(action(c -> {
            close(c); hx = hy = -1; clearChat(c); clearToasts(c); lookDown(c, 28f);
            // a STATIC world so frame-to-frame comparisons only see our glass: no daylight drift, no weather
            cmd(c, "gamerule doDaylightCycle false"); cmd(c, "gamerule doWeatherCycle false");
            cmd(c, "time set 6000"); cmd(c, "weather clear");
        }));
        add(waitMs(2600));
        add(action(c -> clearToasts(c)));
        add(waitMs(300));
        switch (mode) {
            case "screens":  buildScreens();  break;
            case "tooltips": buildTooltips(); break;
            case "effects":  buildEffects();  break;
            case "hud":      buildHud();      break;
            case "modules":  buildModules();  break;
            case "flicker":  buildFlicker();  break;
            case "vcombat":  buildCombat();   break;
            case "newmenu":  buildNewMenu();  break;
            case "newanim":  buildNewAnim();  break;
            case "settings": buildSettings(); break;
            case "trans":    buildTrans();    break;
            case "gap":      buildGap();      break;
            default: say("unknown mode " + mode);
        }
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));
    }

    // ---- (A) screens -------------------------------------------------------------------------------------------

    private static void buildScreens() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); effectsGive(c); fillInventory(c); cmd(c, "recipe give @a *"); }));
        add(waitMs(300));
        add(shot("scr-inventory", c -> open(c, new InventoryScreen(c.player)), 700));
        add(shot("scr-recipebook", c -> open(c, new InventoryScreen(c.player)), 900, DevShotVerify::openRecipeBook));
        add(action(c -> { close(c); closeRecipeBook(c); }));
        add(shot("scr-creative", c -> openCreativeSearch(c), 900));
        add(action(c -> gamemode(c, GameMode.SURVIVAL)));
        add(shot("scr-horse", DevShotVerify::openHorse, 800));
        add(shot("scr-advancements", c -> open(c, new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                c.getNetworkHandler().getAdvancementHandler())), 800));
        add(shot("scr-stats", c -> open(c, new net.minecraft.client.gui.screen.StatsScreen(null, c.player.getStatHandler())), 1600));
        add(shot("scr-book", c -> open(c, new net.minecraft.client.gui.screen.ingame.BookScreen(
                new net.minecraft.client.gui.screen.ingame.BookScreen.Contents() {
                    public int getPageCount() { return 1; }
                    public Text getPageUnchecked(int i) {
                        return new net.minecraft.text.LiteralText(
                                "液態玻璃書頁\n深色墨水必須清晰可讀。\n\nDark ink stays readable on the light warm parchment scrim over the glass page.");
                    }
                })), 700));
        add(shot("scr-book-edit", DevShotVerify::openBookEdit, 700));
        add(shot("scr-book-sign", c -> { openBookEdit(c); }, 700, DevShotVerify::bookSignMode));
        add(shot("scr-lectern", DevShotVerify::openLectern, 800));
        add(shot("scr-death", c -> open(c, new net.minecraft.client.gui.screen.DeathScreen(new net.minecraft.text.LiteralText("S1mp1e DevShot"), false)), 1600));
        add(shot("scr-anvil", DevShotVerify::openAnvil, 700));
        add(shot("scr-furnace", DevShotVerify::openFurnace, 700));
        add(shot("scr-brewing", DevShotVerify::openBrewing, 700));
        add(shot("scr-enchanting", DevShotVerify::openEnchanting, 900));
        add(shot("scr-chest", DevShotVerify::openChest, 700));
        add(shot("scr-stonecutter", DevShotVerify::openStonecutter, 800));
        add(shot("scr-loom", DevShotVerify::openLoom, 800));
        add(shot("scr-merchant", DevShotVerify::openMerchant, 800));
    }

    // ---- (E) tooltips on the very top ------------------------------------------------------------------------------

    private static void buildTooltips() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); fillInventory(c); }));
        add(waitMs(300));
        add(shot("tt-survival", c -> open(c, new InventoryScreen(c.player)), 900, c -> hoverSlot(c, 12)));
        add(action(c -> { close(c); gamemode(c, GameMode.SURVIVAL); effectsGive(c); namedItem(c, 17, "S1mp1e card over the effect strip"); }));
        add(waitMs(300));
        add(shot("tt-effects", c -> open(c, new InventoryScreen(c.player)), 900, c -> hoverSlot(c, 17)));
        add(action(c -> close(c)));
        add(shot("tt-creative", DevShotVerify::openCreativeSearch, 1100, c -> hoverSlot(c, 16)));
        add(action(c -> close(c)));
        add(shot("tt-stonecutter", DevShotVerify::openStonecutter, 900,
                c -> { ContainerScreen<?> s = hs(c); if (s != null) { hx = acc(s).s1mp1e$x() + 52 + 3 * 16 + 8; hy = acc(s).s1mp1e$y() + 14 + 9; } }));
        add(action(c -> close(c)));
        add(shot("tt-merchant", DevShotVerify::openMerchant, 900,
                c -> { ContainerScreen<?> s = hs(c); if (s != null) { hx = acc(s).s1mp1e$x() + 5 + 68 + 8; hy = acc(s).s1mp1e$y() + 18 + 20 + 10; } }));
        add(action(c -> { close(c); hx = hy = -1; }));
        // a card over stacked toasts: 5 advancement toasts + a long-named item hovered at the right edge
        add(action(c -> {
            gamemode(c, GameMode.SURVIVAL);
            clearToasts(c);
            namedHelmet(c, "S1mp1e 提示卡 — this card must stay above the stacked toasts");
            cmd(c, "advancement revoke @a everything");
        }));
        add(waitMs(400));
        add(action(c -> {
            clearToasts(c);
            for (String adv : new String[]{"story/mine_stone", "story/upgrade_tools", "story/smelt_iron",
                    "story/obtain_armor", "story/iron_tools", "story/lava_bucket"})
                cmd(c, "advancement grant @a only minecraft:" + adv);
        }));
        add(shot("tt-toast", c -> open(c, new InventoryScreen(c.player)), 1600, c -> hoverSlot(c, 5)));
        add(action(c -> { clearToasts(c); }));
        // ghost: hold a hover, then move off and catch 5 consecutive fade frames (150 ms) — still on top
        add(shot("tt-ghost-held", null, 500, c -> hoverSlot(c, 12)));
        add(burst("tt-ghost", 5, c -> { hx = 6; hy = 6; }, null));
        add(action(c -> close(c)));
        // compact effect column (narrow window) + its effect tooltip
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); DevShot.setTarget(760, 720); }));
        add(waitMs(400));
        add(shot("tt-compact", c -> open(c, new InventoryScreen(c.player)), 900, c -> hoverCompactEffect(c, 1)));
        add(action(c -> { close(c); DevShot.setTarget(1280, 720); }));
        add(waitMs(400));
        add(shot("tt-tab", DevShotVerify::openCreativeSearch, 1100,
                c -> { double[] p = tabCell(c, 5, true); if (p != null) { hx = p[0]; hy = p[1] + 4; } }));
    }

    // ---- (F) effects ------------------------------------------------------------------------------------------------

    private static void buildEffects() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); effectsGive(c); }));
        add(waitMs(300));
        add(shot("fx-survival-wide", c -> open(c, new InventoryScreen(c.player)), 900));
        add(action(c -> close(c)));
        add(shot("fx-creative-wide", DevShotVerify::openCreativeSearch, 1100, c -> effectsGive(c)));
        add(action(c -> { close(c); gamemode(c, GameMode.SURVIVAL); }));
        add(shot("fx-chest", DevShotVerify::openChest, 800));
        add(action(c -> { close(c); DevShot.setTarget(760, 720); }));
        add(waitMs(400));
        add(shot("fx-compact", c -> open(c, new InventoryScreen(c.player)), 900));
        add(action(c -> { close(c); DevShot.setTarget(1280, 720); }));
        add(waitMs(300));
    }

    // ---- (G) HUD overlays, staged with integrated-server commands -------------------------------------------------

    private static void buildHud() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); lookDown(c, 12f); emptyHand(c); }));
        add(waitMs(600));
        add(action(c -> {
            clearChat(c);
            cmd(c, "tellraw @a \"[S1mp1e] 液態玻璃聊天面板 — one continuous frosted panel\"");
            cmd(c, "tellraw @a \"<Steve> the glass hugs the widest visible line\"");
            cmd(c, "tellraw @a \"<Alex> per-line dark rects are dropped\"");
            cmd(c, "say grey readability scrim under the text");
            cmd(c, "tellraw @a \"fading with the newest line, like vanilla\"");
        }));
        add(shot("hud-chat", null, 900));
        add(shot("hud-chat-input", c -> open(c, new net.minecraft.client.gui.screen.ChatScreen("/tellraw")), 600));
        add(action(c -> { close(c); clearChat(c); tablistShow(c); }));
        add(shot("hud-tablist", null, 800));
        add(action(c -> { tablistHide(c); }));
        add(action(c -> {
            cmd(c, "bossbar add s1mp1e:a \"Ender Dragon\"");
            cmd(c, "bossbar set s1mp1e:a color blue");
            cmd(c, "bossbar set s1mp1e:a value 65");
            cmd(c, "bossbar set s1mp1e:a players @a");
            cmd(c, "bossbar add s1mp1e:b \"Wither\"");
            cmd(c, "bossbar set s1mp1e:b color purple");
            cmd(c, "bossbar set s1mp1e:b style notched_10");
            cmd(c, "bossbar set s1mp1e:b value 30");
            cmd(c, "bossbar set s1mp1e:b players @a");
        }));
        add(shot("hud-bossbar", null, 800));
        add(action(c -> { cmd(c, "bossbar remove s1mp1e:a"); cmd(c, "bossbar remove s1mp1e:b"); }));
        add(action(c -> { clearChat(c); cmd(c, "title @a actionbar \"液態玻璃動作列 — Action bar pill\""); }));
        add(shot("hud-actionbar", null, 500));
        add(shot("hud-actionbar-fading", null, 2050));   // ~2.5 s after the title: inside vanilla's last-second fade
        add(action(c -> { clearToasts(c); cmd(c, "advancement revoke @a everything"); }));
        add(waitMs(300));
        add(action(c -> { cmd(c, "advancement grant @a only minecraft:story/mine_stone"); cmd(c, "recipe give @a minecraft:diamond_sword");
            // a tutorial toast too: its title / description were dark on the glass card (ToastTextMixin)
            c.getToastManager().add(new net.minecraft.client.toast.TutorialToast(
                    net.minecraft.client.toast.TutorialToast.Type.RECIPE_BOOK,
                    new net.minecraft.text.LiteralText("Tutorial title"), new net.minecraft.text.LiteralText("description line"), false)); }));
        add(shot("hud-toast-slide", null, 330));
        add(shot("hud-toast", null, 1000));
        add(action(c -> { clearToasts(c); clearChat(c); }));
        add(action(c -> cmd(c, "execute as @p at @p run summon minecraft:armor_stand ^ ^ ^3 "
                + "{CustomName:'\"S1mp1e\"',CustomNameVisible:1b,NoGravity:1b}")));
        add(shot("hud-nametag", null, 900));
        add(action(c -> cmd(c, "kill @e[type=minecraft:armor_stand]")));
    }

    // ---- (H) modules ----------------------------------------------------------------------------------------------

    private static void buildModules() {
        add(action(c -> { snapshotModules(); lookDown(c, 62f); }));
        add(waitMs(500));
        add(action(c -> {
            modEnable("BlockOutline", true);
            modSetD("BlockOutline", "Line width", 1.0);
            modSetC("BlockOutline", "Colour", 0xFF30D158);
            modSetB("BlockOutline", "Chroma", false);
            modSetB("BlockOutline", "Fill", false);
        }));
        add(shot("mod-outline-w1", null, 400));
        add(action(c -> modSetD("BlockOutline", "Line width", 8.0)));
        add(shot("mod-outline-w8", null, 400));
        add(action(c -> modSetB("BlockOutline", "Chroma", true)));
        add(shot("mod-outline-chroma-a", null, 300));
        add(shot("mod-outline-chroma-b", null, 450));
        add(action(c -> { modSetB("BlockOutline", "Chroma", false); modSetB("BlockOutline", "Fill", true);
                          modSetC("BlockOutline", "Fill colour", 0x5530D158); modSetD("BlockOutline", "Line width", 2.5); }));
        add(shot("mod-outline-fill", null, 400));
        add(action(c -> { modEnable("BlockOutline", false); lookDown(c, 15f);
                          modEnable("FpsHUD", true); modEnable("CoordsHUD", true); modEnable("Keystrokes", true);
                          modEnable("ChromaHud", true); modSetD("ChromaHud", "Wave", 0.5); modSetD("ChromaHud", "Speed", 2.0); }));
        add(shot("mod-hud-chroma", null, 500));
        add(action(c -> modSetD("ChromaHud", "Wave", 0.0)));
        add(shot("mod-hud-chroma-flat", null, 400));
        add(action(c -> { modEnable("ChromaHud", false); modEnable("Keystrokes", false); }));
        add(shot("mod-hud-editor", c -> open(c, new dev.s1mp1e.client.gui.S1mp1eHudEditScreen()), 700));
        add(action(c -> openConfigModule(c, 2, "BlockOutline")));
        add(shot("mod-config-blockoutline", null, 700));
        add(action(c -> openConfigModule(c, 1, "ChromaHud")));
        add(shot("mod-config-chromahud", null, 700));
        // the CPS module's settings: its "Shadow" switch is hidden now (text has no drop shadow anywhere)
        add(action(c -> openConfigModule(c, 0, "CPS")));
        add(shot("mod-config-cps", null, 700));
        add(action(c -> { close(c); restoreModules(); }));
    }

    // ---- (R4) flicker ---------------------------------------------------------------------------------------------

    private static void buildFlicker() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); fillInventory(c); lookDown(c, 35f); emptyHand(c);
                          try { flkFps = c.options.maxFps; c.options.maxFps = 260; c.getWindow().setVsync(false); }
                          catch (Throwable t) { skip("uncap fps", t); } }));
        add(waitMs(1500));
        add(action(c -> open(c, new InventoryScreen(c.player))));
        add(waitMs(900));
        add(everyNth("flk-inventory", 6, 7));
        add(action(c -> close(c)));
        add(action(DevShotVerify::openCreativeSearch));
        add(waitMs(1200));
        add(everyNth("flk-creative", 6, 7));
        add(action(c -> close(c)));
        add(action(DevShotVerify::openMerchant));
        add(waitMs(900));
        add(everyNth("flk-merchant", 6, 7));
        add(action(c -> close(c)));
        add(waitMs(700));
        add(action(c -> {
            clearChat(c);
            cmd(c, "tellraw @a \"flicker probe: chat panel line one\"");
            cmd(c, "tellraw @a \"flicker probe: chat panel line two (wider than the first)\"");
        }));
        add(waitMs(700));
        add(everyNth("flk-chat", 6, 7));
        add(action(c -> { clearChat(c); tablistShow(c); }));
        add(waitMs(800));
        add(everyNth("flk-tablist", 6, 7));
        add(action(c -> tablistHide(c)));
        // put the frame cap back (the vsync OPTION was never touched): nothing of this sweep may reach options.txt
        add(action(c -> { if (flkFps > 0) c.options.maxFps = flkFps; }));
    }

    private static int flkFps;

    // ============================================================================================================
    //  scene primitives
    // ============================================================================================================

    private static void add(Scene s) { scenes.add(s); }

    private static Scene action(java.util.function.Consumer<MinecraftClient> a) {
        return (c, fr, ms) -> { try { a.accept(c); } catch (Throwable t) { skip("action", t); } return true; };
    }

    private static Scene waitMs(final double wait) { return (c, fr, ms) -> ms >= wait && fr >= 3; }

    private static Scene shot(String name, java.util.function.Consumer<MinecraftClient> setup, double wait) {
        return shot(name, setup, wait, null);
    }

    /** Setup on frame 1, {@code each} every frame from frame 3 (e.g. hover), capture once {@code wait} ms elapsed. */
    private static Scene shot(final String name, final java.util.function.Consumer<MinecraftClient> setup, final double wait,
                              final java.util.function.Consumer<MinecraftClient> each) {
        return (c, fr, ms) -> {
            if (fr == 1 && setup != null) { try { setup.accept(c); } catch (Throwable t) { skip("setup " + name, t); } }
            if (fr >= 3 && each != null) { try { each.accept(c); } catch (Throwable t) { if (fr == 3) skip("each " + name, t); } }
            if (ms >= wait && fr >= 12) {
                capture(c, name + ".png");
                say("shot " + name + " (" + (c.currentScreen == null ? "no screen" : c.currentScreen.getClass().getSimpleName())
                        + ", cursor " + Math.round(hx) + "," + Math.round(hy) + ")");
                return true;
            }
            return false;
        };
    }

    /** Kick on frame 1, then capture {@code n} CONSECUTIVE frames (logging the optional probe). */
    private static Scene burst(final String name, final int n, final java.util.function.Consumer<MinecraftClient> kick,
                              final java.util.function.Function<MinecraftClient, String> probe) {
        return (c, fr, ms) -> {
            if (fr == 1) {
                if (kick != null) { try { kick.accept(c); } catch (Throwable t) { skip("kick " + name, t); } }
                return false;
            }
            int k = fr - 2;
            if (k < n) {
                capture(c, String.format("%s-%02d.png", name, k));
                say("burst " + name + " " + k + " t=" + fmt(ms) + "ms" + (probe != null ? " " + probe.apply(c) : ""));
                return false;
            }
            return true;
        };
    }

    /** Uncapped frames; capture every {@code every}-th (so each captured frame follows a fast one), {@code n} shots. */
    private static Scene everyNth(final String name, final int n, final int every) {
        return (c, fr, ms) -> {
            if (fr % every == 0) {
                int k = fr / every - 1;
                if (k < n) {
                    capture(c, String.format("%s-%02d.png", name, k));
                    say("flicker " + name + " " + k + " prevFrameMs=" + fmt(lastFrameMs));
                }
                return k >= n - 1;
            }
            return false;
        };
    }

    // ============================================================================================================
    //  screen openers
    // ============================================================================================================

    private static void openCreativeSearch(MinecraftClient c) {
        gamemode(c, GameMode.CREATIVE);
        open(c, new CreativeInventoryScreen(c.player));
        selectTab(c, ItemGroup.SEARCH);
    }

    private static void openStonecutter(MinecraftClient c) {
        net.minecraft.container.StonecutterContainer h =
                new net.minecraft.container.StonecutterContainer(1, c.player.inventory);
        // screen FIRST (its constructor registers the contents listener -> canCraft), then the input
        open(c, new net.minecraft.client.gui.screen.ingame.StonecutterScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Stonecutter")));
        h.getSlot(0).setStack(new ItemStack(Items.STONE, 64));
        h.onContentChanged(h.getSlot(0).inventory);
    }

    private static void openLoom(MinecraftClient c) {
        net.minecraft.container.LoomContainer h = new net.minecraft.container.LoomContainer(1, c.player.inventory);
        open(c, new net.minecraft.client.gui.screen.ingame.LoomScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Loom")));
        h.getSlot(0).setStack(new ItemStack(Items.WHITE_BANNER));   // screen first: its constructor registers the
        h.getSlot(1).setStack(new ItemStack(Items.RED_DYE));        // inventory listener that sets canApplyDyePattern
    }

    private static void openMerchant(MinecraftClient c) {
        net.minecraft.container.MerchantContainer h = new net.minecraft.container.MerchantContainer(1, c.player.inventory);
        net.minecraft.village.TraderOfferList offers = new net.minecraft.village.TraderOfferList();
        Item[] sells = { Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.BREAD, Items.BOOK, Items.ARROW,
                Items.COAL, Items.APPLE, Items.STICK, Items.PAPER, Items.COMPASS, Items.CLOCK, Items.LANTERN,
                Items.GLASS, Items.BELL, Items.SHIELD, Items.BOW, Items.EMERALD_BLOCK, Items.NAME_TAG, Items.SADDLE };
        for (int i = 0; i < sells.length; i++) {
            offers.add(new net.minecraft.village.TradeOffer(
                    new ItemStack(Items.EMERALD, 1 + i),
                    i % 3 == 1 ? new ItemStack(Items.BOOK, 1) : ItemStack.EMPTY,
                    new ItemStack(sells[i], 1 + (i % 4)), 12, 5, 0.05f));
        }
        h.setOffers(offers);
        open(c, new net.minecraft.client.gui.screen.ingame.MerchantScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Villager")));
    }

    /** Press the survival inventory's recipe-book toggle once (the vanilla button handler repositions the panel). */
    private static void openRecipeBook(MinecraftClient c) {
        if (!(c.currentScreen instanceof InventoryScreen)) return;
        InventoryScreen s = (InventoryScreen) c.currentScreen;
        try {
            Field rb = InventoryScreen.class.getDeclaredField("recipeBook");
            rb.setAccessible(true);
            net.minecraft.client.gui.screen.recipebook.RecipeBookWidget w =
                    (net.minecraft.client.gui.screen.recipebook.RecipeBookWidget) rb.get(s);
            if (w.isOpen()) return;
            for (net.minecraft.client.gui.Element e : s.children()) {
                if (e instanceof net.minecraft.client.gui.widget.TexturedButtonWidget) { ((net.minecraft.client.gui.widget.TexturedButtonWidget) e).onPress(); break; }
            }
        } catch (Throwable t) { skip("open recipe book", t); }
    }

    /** Leave the recipe book closed for the following scenes (the open flag persists in the player's book). */
    private static void closeRecipeBook(MinecraftClient c) {
        try {
            open(c, new InventoryScreen(c.player));
            if (c.currentScreen instanceof InventoryScreen) {
                InventoryScreen s = (InventoryScreen) c.currentScreen;
                Field rb = InventoryScreen.class.getDeclaredField("recipeBook");
                rb.setAccessible(true);
                net.minecraft.client.gui.screen.recipebook.RecipeBookWidget w =
                        (net.minecraft.client.gui.screen.recipebook.RecipeBookWidget) rb.get(s);
                if (w.isOpen()) {
                    for (net.minecraft.client.gui.Element e : s.children()) {
                        if (e instanceof net.minecraft.client.gui.widget.TexturedButtonWidget) { ((net.minecraft.client.gui.widget.TexturedButtonWidget) e).onPress(); break; }
                    }
                }
            }
            close(c);
        } catch (Throwable t) { skip("close recipe book", t); }
    }

    private static void openHorse(MinecraftClient c) {
        net.minecraft.entity.passive.HorseEntity horse =
                new net.minecraft.entity.passive.HorseEntity(net.minecraft.entity.EntityType.HORSE, c.world);
        net.minecraft.inventory.BasicInventory inv = new net.minecraft.inventory.BasicInventory(2);
        net.minecraft.container.HorseContainer h =
                new net.minecraft.container.HorseContainer(1, c.player.inventory, inv, horse);
        open(c, new net.minecraft.client.gui.screen.ingame.HorseScreen(h, c.player.inventory, horse));
    }

    private static void openBookEdit(MinecraftClient c) {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        try {   // 1.20.1: book pages are NBT strings
            net.minecraft.nbt.ListTag pages = new net.minecraft.nbt.ListTag();
            pages.add(net.minecraft.nbt.StringTag.of("液態玻璃書寫頁\nDark ink stays readable while you type on the glass page."));
            book.getOrCreateTag().put("pages", pages);
        } catch (Throwable t) { skip("book content", t); }
        open(c, new net.minecraft.client.gui.screen.ingame.BookEditScreen(c.player, book, net.minecraft.util.Hand.MAIN_HAND));
    }

    private static void bookSignMode(MinecraftClient c) {
        if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.ingame.BookEditScreen)) return;
        net.minecraft.client.gui.screen.ingame.BookEditScreen s = (net.minecraft.client.gui.screen.ingame.BookEditScreen) c.currentScreen;
        try {
            Field sg = net.minecraft.client.gui.screen.ingame.BookEditScreen.class.getDeclaredField("signing");
            sg.setAccessible(true);
            if (!sg.getBoolean(s)) {
                sg.setBoolean(s, true);
                Method ub = net.minecraft.client.gui.screen.ingame.BookEditScreen.class.getDeclaredMethod("updateButtons");
                ub.setAccessible(true);
                ub.invoke(s);
            }
        } catch (Throwable t) { skip("book sign mode", t); }
    }

    private static void openLectern(MinecraftClient c) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        try {   // 1.20.1: a written book is title / author / JSON-text pages in NBT
            net.minecraft.nbt.CompoundTag tag = book.getOrCreateTag();
            tag.putString("title", "S1mp1e");
            tag.putString("author", "DevShot");
            net.minecraft.nbt.ListTag pages = new net.minecraft.nbt.ListTag();
            pages.add(net.minecraft.nbt.StringTag.of(Text.Serializer.toJson(new net.minecraft.text.LiteralText(
                    "講台上的玻璃書頁\nLectern page: glass + light parchment scrim."))));
            tag.put("pages", pages);
            tag.putBoolean("resolved", true);
        } catch (Throwable t) { skip("lectern book", t); }
        net.minecraft.container.LecternContainer h = new net.minecraft.container.LecternContainer(1);
        h.getSlot(0).setStack(book);
        open(c, new net.minecraft.client.gui.screen.ingame.LecternScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Lectern")));
    }

    private static void openAnvil(MinecraftClient c) {
        net.minecraft.container.AnvilContainer h = new net.minecraft.container.AnvilContainer(1, c.player.inventory);
        open(c, new net.minecraft.client.gui.screen.ingame.AnvilScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Repair & Name")));
        h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
        h.getSlot(1).setStack(new ItemStack(Items.DIRT, 3));   // invalid combination -> vanilla red error X stays on top
    }

    private static void openFurnace(MinecraftClient c) {
        net.minecraft.container.FurnaceContainer h = new net.minecraft.container.FurnaceContainer(1, c.player.inventory);
        open(c, new net.minecraft.client.gui.screen.ingame.FurnaceScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Furnace")));
        h.getSlot(0).setStack(new ItemStack(Items.IRON_ORE, 12));
        h.getSlot(1).setStack(new ItemStack(Items.COAL, 5));
        h.setProperty(0, 120); h.setProperty(1, 200); h.setProperty(2, 110); h.setProperty(3, 200);   // lit + ~55% cooked
    }

    private static void openBrewing(MinecraftClient c) {
        net.minecraft.container.BrewingStandContainer h = new net.minecraft.container.BrewingStandContainer(1, c.player.inventory);
        open(c, new net.minecraft.client.gui.screen.ingame.BrewingStandScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Brewing Stand")));
        h.getSlot(0).setStack(new ItemStack(Items.GLASS_BOTTLE));
        h.getSlot(3).setStack(new ItemStack(Items.NETHER_WART));
        h.getSlot(4).setStack(new ItemStack(Items.BLAZE_POWDER, 4));
        h.setProperty(0, 200); h.setProperty(1, 12);   // brewing half-way, fuel 12/20
    }

    private static void openEnchanting(MinecraftClient c) {
        net.minecraft.container.EnchantingTableContainer h = new net.minecraft.container.EnchantingTableContainer(1, c.player.inventory);
        open(c, new net.minecraft.client.gui.screen.ingame.EnchantingScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Enchant")));
        h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
        h.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 3));
        h.setProperty(0, 5); h.setProperty(1, 15); h.setProperty(2, 30); h.setProperty(3, 12345);
        h.setProperty(4, -1); h.setProperty(5, -1); h.setProperty(6, -1);
    }

    private static void openChest(MinecraftClient c) {
        net.minecraft.inventory.BasicInventory inv = new net.minecraft.inventory.BasicInventory(27);
        for (int i = 0; i < 27; i++) inv.setInvStack(i, new ItemStack(Items.STONE, 1 + i));
        net.minecraft.container.GenericContainer h =
                net.minecraft.container.GenericContainer.createGeneric9x3(1, c.player.inventory, inv);
        open(c, new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(h, c.player.inventory, new net.minecraft.text.LiteralText("Chest")));
    }

    // ============================================================================================================
    //  creative tab helpers
    // ============================================================================================================

    private static int cellOf(ItemGroup g, int pw) {
        int col = g.getColumn();
        // 1.15.2 (CreativeInventoryScreen.isClickInTab): 28 px tabs at 28*col + col, special tabs right-aligned
        int vx = g.isSpecial() ? (pw - 28 * (6 - col) + 2) : (28 * col + col);
        return Math.max(0, Math.min(6, Math.round(vx / (pw / 7f))));
    }

    private static ItemGroup groupAt(boolean top, int cell) {
        try {
            for (ItemGroup g : ItemGroup.GROUPS) {
                if (g.isTopRow() == top && cellOf(g, 195) == cell) return g;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void selectTab(MinecraftClient c, ItemGroup g) {
        if (g == null || !(c.currentScreen instanceof CreativeInventoryScreen)) return;
        try {
            Method m = CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab", ItemGroup.class);
            m.setAccessible(true);
            m.invoke(c.currentScreen, g);
        } catch (Throwable t) { skip("select tab", t); }
    }

    private static double[] tabCell(MinecraftClient c, int cell, boolean top) {
        ContainerScreen<?> s = hs(c);
        if (s == null) return null;
        int px = acc(s).s1mp1e$x(), py = acc(s).s1mp1e$y(), pw = acc(s).s1mp1e$backgroundWidth(), ph = acc(s).s1mp1e$backgroundHeight();
        double cw = pw / 7.0;
        return new double[]{px + (cell + 0.5) * cw, top ? py - 14 : py + ph + 14};
    }

    private static void tabClick(MinecraftClient c, boolean top, int cell) {
        ContainerScreen<?> s = hs(c);
        ItemGroup want = groupAt(top, cell);
        double[] p = tabCell(c, cell, top);
        if (s == null || want == null || p == null) { fail++; say("[CLICKS] tab " + (top ? "top" : "bottom") + cell + " FAIL: setup"); return; }
        hx = p[0]; hy = p[1];
        s.mouseClicked(p[0], p[1], 0);
        s.mouseReleased(p[0], p[1], 0);
        ItemGroup sel = null;
        try {
            Field f = CreativeInventoryScreen.class.getDeclaredField("selectedTab");
            f.setAccessible(true);
            Object v = f.get(null);                       // 1.15.2: a static int index into ItemGroup.GROUPS
            sel = v instanceof ItemGroup ? (ItemGroup) v : ItemGroup.GROUPS[((Number) v).intValue()];
        } catch (Throwable t) { skip("read selected tab", t); }
        boolean ok = sel == want;
        if (ok) pass++; else fail++;
        say("[CLICKS] tab " + (top ? "top" : "bottom") + " cell " + cell + " at " + fmt((float) p[0]) + "," + fmt((float) p[1])
                + " want=" + want.getTranslationKey() + " selected=" + (sel == null ? "null" : sel.getTranslationKey())
                + " -> " + (ok ? "PASS" : "FAIL"));
        hx = hy = -1;
    }

    // ============================================================================================================
    //  world / player helpers
    // ============================================================================================================

    private static void cmd(MinecraftClient c, String command) {
        MinecraftServer server = c.getServer();
        if (server == null) return;
        server.execute(() -> {
            try { server.getCommandManager().execute(server.getCommandSource().withSilent(), command); }
            catch (Throwable t) { skip("command " + command, t); }
        });
    }

    // ============================================================================================================
    //  combat trio (26.2 port): LowFire on/off, AttackRing charging + ready, HitMarker hit + real falling crit
    // ============================================================================================================

    private static boolean combatAttacked;
    private static double combatT;

    private static net.minecraft.entity.passive.PigEntity combatPig(MinecraftClient c) {
        if (c.world == null || c.player == null) return null;
        net.minecraft.entity.passive.PigEntity best = null;
        double bd = 1e9;
        for (net.minecraft.entity.Entity e : c.world.getEntities()) {
            if (e instanceof net.minecraft.entity.passive.PigEntity && e.isAlive()) {
                net.minecraft.entity.passive.PigEntity p = (net.minecraft.entity.passive.PigEntity) e;
                double d = p.squaredDistanceTo(c.player);
                if (d < bd) { bd = d; best = p; }
            }
        }
        return bd < 36 ? best : null;
    }

    private static void combatModule(String name, boolean on) {
        dev.s1mp1e.client.Module m = dev.s1mp1e.client.ModuleManager.byName(name);
        if (m != null) m.enabled = on;
    }

    private static void combatPigs(MinecraftClient c, String tag) {
        StringBuilder sb = new StringBuilder("combat pigs @" + tag + ":");
        if (c.world != null && c.player != null) {
            for (net.minecraft.entity.Entity e : c.world.getEntities()) {
                if (e instanceof net.minecraft.entity.passive.PigEntity) {
                    net.minecraft.entity.passive.PigEntity p = (net.minecraft.entity.passive.PigEntity) e;
                    sb.append(" #").append(p.getEntityId()).append(" hp=").append(fmt(p.getHealth())).append("/")
                      .append(fmt(p.getMaximumHealth())).append(" d=").append(fmt((float) Math.sqrt(p.squaredDistanceTo(c.player))))
                      .append(p.isAlive() ? "" : " DEAD");
                }
            }
        }
        say(sb.toString());
    }

    private static void combatHit(MinecraftClient c) {
        combatPigs(c, "before-hit");
        net.minecraft.entity.passive.PigEntity pig = combatPig(c);
        if (pig != null && c.interactionManager != null) c.interactionManager.attackEntity(c.player, pig);
        say("combat attack " + (pig != null ? "pig#" + pig.getEntityId() : "NO PIG") + " cooldown="
                + (c.player != null ? fmt(c.player.getAttackCooldownProgress(0f)) : "?"));
    }

    private static void buildCombat() {
        add(action(c -> {
            close(c); hx = hy = -1;
            gamemode(c, GameMode.SURVIVAL);
            cmd(c, "effect give @a minecraft:fire_resistance 600 0 true");
            cmd(c, "kill @e[type=minecraft:pig]");
            if (c.player != null) { c.player.inventory.selectedSlot = 0; c.player.pitch = 0f; }
        }));
        add(waitMs(700));
        // LowFire: burning, module on vs off
        add(action(c -> { final ServerPlayerEntity p = sp(c); if (p != null) c.getServer().execute(() -> p.setFireTicks(600)); }));
        add(shot("combat-fire-on", c -> combatModule("LowFire", true), 700));
        // the lowered flames only peek over the bottom edge on some frames of the fire animation: a short burst
        add(burst("combat-fire-low", 6, null, null));
        add(shot("combat-fire-off", c -> combatModule("LowFire", false), 300));
        add(action(c -> {
            combatModule("LowFire", true);
            final ServerPlayerEntity p = sp(c); if (p != null) c.getServer().execute(p::extinguish);
        }));
        add(waitMs(600));
        // AttackRing: a swing restarts the cooldown; the arc grows clockwise from 12 o'clock
        add(action(c -> { if (c.player != null) { c.player.resetLastAttackedTicks(); c.player.swingHand(net.minecraft.util.Hand.MAIN_HAND); } }));
        add(shot("combat-ring-a", null, 120));
        add(shot("combat-ring-b", null, 150));
        // the sweep, frame by frame without capturing (a capture stalls the frame): the drawn progress must move
        // EVERY frame; the per-tick value vanilla's indicator reads only moves 20 times a second
        add(waitMs(900));
        add((c, fr, ms) -> {
            if (c.player == null) return true;
            if (fr == 1) { c.player.resetLastAttackedTicks(); return false; }
            say("ring f=" + fr + " t=" + fmt(ms) + "ms drawn=" + String.format("%.4f", dev.s1mp1e.client.module.AttackRingModule.lastProgress)
                    + " tick=" + String.format("%.4f", c.player.getAttackCooldownProgress(0f)));
            return fr >= 45 || c.player.getAttackCooldownProgress(0f) >= 1f;
        });
        // ring stays full while aiming at a living target (sword: cooldown period 12.5 ticks > 5)
        add(action(c -> cmd(c, "execute as @p at @p rotated ~ 0 run summon minecraft:pig ^ ^ ^2.2 {NoAI:1b,Silent:1b,Health:100f,"
                + "Attributes:[{Name:\"minecraft:generic.max_health\",Base:100d}]}")));
        add(shot("combat-ring-ready", c -> { if (c.player != null) c.player.pitch = 28f; }, 1000,
                c -> { if (c.player != null) c.player.pitch = 28f; }));
        // HitMarker: a confirmed hit
        add((c, fr, ms) -> {
            if (fr == 1) { combatHit(c); return false; }
            if (ms >= 110 && fr >= 4) { capture(c, "combat-hit.png"); say("shot combat-hit"); return true; }
            return false;
        });
        add(waitMs(500));
        add(action(c -> combatPigs(c, "hit+500ms")));
        add(waitMs(400));
        // a REAL crit: up 1.5 blocks, attack on the way down (server: fallDistance > 0, not on ground)
        add(action(c -> cmd(c, "execute as @p at @p run tp @s ~ ~1.5 ~")));
        add((c, fr, ms) -> {
            if (fr == 1) { combatAttacked = false; return false; }
            if (!combatAttacked && c.player != null && fr > 3 && c.player.getVelocity().y < -0.08 && !c.player.onGround) {
                combatHit(c); combatAttacked = true; combatT = ms; return false;
            }
            if (combatAttacked && ms - combatT >= 110) { capture(c, "combat-crit.png"); say("shot combat-crit"); return true; }
            return ms > 3000;
        });
        add(waitMs(900));
        // new options: counter-clockwise + rainbow ring while charging
        add(action(c -> {
            dev.s1mp1e.client.module.AttackRingModule r =
                    (dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing");
            if (r != null) { r.clockwise.boolValue = false; r.chroma.boolValue = true; }
            if (c.player != null) { c.player.resetLastAttackedTicks(); c.player.swingHand(net.minecraft.util.Hand.MAIN_HAND); }
        }));
        add(shot("combat-ring-ccw-chroma", null, 300));
        add(action(c -> {
            dev.s1mp1e.client.module.AttackRingModule r =
                    (dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing");
            if (r != null) { r.clockwise.boolValue = true; r.chroma.boolValue = false; }
            dev.s1mp1e.client.module.HitMarkerModule h =
                    (dev.s1mp1e.client.module.HitMarkerModule) dev.s1mp1e.client.ModuleManager.byName("HitMarker");
            if (h != null) h.shape.modeValue = "Cross";
            cmd(c, "kill @e[type=minecraft:pig]");
            cmd(c, "execute as @p at @p rotated ~ 0 run summon minecraft:pig ^ ^ ^2.2 {NoAI:1b,Silent:1b,Health:1f}");
        }));
        add(waitMs(1100));
        // new options: "+" shape and a KILL (a 1-HP pig) -> kill colour
        add((c, fr, ms) -> {
            if (fr == 1) { if (c.player != null) c.player.pitch = 28f; combatHit(c); return false; }
            if (ms >= 110 && fr >= 4) { capture(c, "combat-kill-cross.png"); say("shot combat-kill-cross"); return true; }
            return false;
        });
        add(action(c -> {
            dev.s1mp1e.client.module.HitMarkerModule h =
                    (dev.s1mp1e.client.module.HitMarkerModule) dev.s1mp1e.client.ModuleManager.byName("HitMarker");
            if (h != null) h.shape.modeValue = "X";
            cmd(c, "kill @e[type=minecraft:pig]");
        }));
        add(waitMs(900));
        // shapes while charging: rounded square, crosshair wrap
        for (final String sh : new String[] {"Square", "Wrap"}) {
            add(action(c -> {
                dev.s1mp1e.client.module.AttackRingModule r =
                        (dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing");
                if (r != null) r.shape.modeValue = sh;
                if (c.player != null) { c.player.resetLastAttackedTicks(); c.player.swingHand(net.minecraft.util.Hand.MAIN_HAND); }
            }));
            add(shot("combat-ring-" + sh.toLowerCase(), null, 330));
        }
        // ready shape = Wrap: charged + aiming at a living target -> the full indicator wraps the crosshair
        add(action(c -> {
            dev.s1mp1e.client.module.AttackRingModule r =
                    (dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing");
            if (r != null) { r.shape.modeValue = "Circle"; r.readyShape.modeValue = "Wrap"; }
            cmd(c, "execute as @p at @p rotated ~ 0 run summon minecraft:pig ^ ^ ^2.2 {NoAI:1b,Silent:1b,Health:100f,"
                    + "Attributes:[{Name:\"minecraft:generic.max_health\",Base:100d}]}");
        }));
        add(shot("combat-ring-ready-wrap", c -> { if (c.player != null) c.player.pitch = 28f; }, 1100,
                c -> { if (c.player != null) c.player.pitch = 28f; }));
        add(action(c -> {
            dev.s1mp1e.client.module.AttackRingModule r =
                    (dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing");
            if (r != null) r.readyShape.modeValue = "Same";
            cmd(c, "kill @e[type=minecraft:pig]");
            if (c.player != null) c.player.pitch = 0f;
        }));
        // Fit crosshair: the S1mp1e crosshair (big, thick, rotated 45) -> the wrap follows it; a Circle -> a round wrap
        for (final String xs : new String[] {"Cross", "Circle"}) {
            add(action(c -> {
                dev.s1mp1e.client.module.CrosshairModule ch =
                        (dev.s1mp1e.client.module.CrosshairModule) dev.s1mp1e.client.ModuleManager.byName("Crosshair");
                if (ch != null) {
                    ch.enabled = true; ch.shape.modeValue = xs;
                    ch.size.intValue = 7; ch.gap.intValue = 3; ch.thick.intValue = 2;
                    ch.rotation.intValue = "Cross".equals(xs) ? 45 : 0;
                }
                if (((dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing")) != null) ((dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing")).shape.modeValue = "Wrap";
                if (c.player != null) { c.player.resetLastAttackedTicks(); c.player.swingHand(net.minecraft.util.Hand.MAIN_HAND); };
            }));
            add(shot("combat-fit-" + xs.toLowerCase(), null, 330));
        }
        add(action(c -> {
            dev.s1mp1e.client.module.CrosshairModule ch =
                    (dev.s1mp1e.client.module.CrosshairModule) dev.s1mp1e.client.ModuleManager.byName("Crosshair");
            if (ch != null) {
                ch.enabled = false; ch.shape.modeValue = "Cross";
                ch.size.intValue = 4; ch.gap.intValue = 2; ch.thick.intValue = 1; ch.rotation.intValue = 0;
            }
            if (((dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing")) != null) ((dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing")).shape.modeValue = "Circle";
        }));
        add(waitMs(300));
    }

    // ============================================================================================================
    //  trans: the snapshot cross-dissolve (ScreenDissolve). Each transition = one "pre" still, then consecutive frames.
    //  Offline measure: progress% = d(frame, pre) / d(last, pre); a hard cut reads 100 % on frame 00.
    // ============================================================================================================

    private static Scene still(final String name) {
        return (c, fr, ms) -> { capture(c, name + ".png"); return true; };
    }

    private static void trans(String name, java.util.function.Consumer<MinecraftClient> kick) {
        add(waitMs(650));
        add(still(name + "-pre"));
        add(burst(name, 9, kick, c -> "dissolve=" + dev.s1mp1e.glass.render.ScreenDissolve.active()));
    }

    private static void buildTrans() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); emptyHand(c); clearChat(c); clearToasts(c); }));
        trans("tr0-game-pause", c -> c.openScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true)));
        trans("tr1-pause-options", c -> c.openScreen(
                new net.minecraft.client.gui.screen.SettingsScreen(c.currentScreen, c.options)));
        trans("tr2-options-video", c -> c.openScreen(
                new net.minecraft.client.gui.screen.VideoOptionsScreen(c.currentScreen, c.options)));
        trans("tr3-video-back", DevShotVerify::pressDone);
        trans("tr4-options-game", c -> c.openScreen(null));
        trans("tr5-game-config", c -> c.openScreen(new S1mp1eConfigScreen()));
        trans("tr6-config-game", c -> { if (c.currentScreen != null) c.currentScreen.onClose(); });
        // in-screen content switches (onTabSwitch): creative category, advancement tab
        add(action(DevShotVerify::openCreativeSearch));
        trans("tr7-creative-tab", c -> selectTab(c, ItemGroup.BUILDING_BLOCKS));
        add(action(c -> { close(c); gamemode(c, GameMode.SURVIVAL); cmd(c, "advancement grant @a everything"); }));
        add(waitMs(900));
        add(action(c -> { clearToasts(c); clearChat(c);
            open(c, new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(c.player.networkHandler.getAdvancementHandler())); }));
        trans("tr8-advancement-tab", DevShotVerify::nextAdvancementTab);
        add(action(c -> close(c)));
        // create-world: 1.15.2 has no tabs (TabManager is 1.19.4+); its one in-screen content switch is the
        // "More World Options" toggle. The screen loads its data packs first.
        add(action(c -> { try { c.openScreen(new net.minecraft.client.gui.screen.world.CreateWorldScreen(new net.minecraft.client.gui.screen.TitleScreen())); }
                          catch (Throwable t) { skip("create world screen", t); } }));
        add(waitMs(1200));
        trans("tr9-createworld-more", DevShotVerify::createWorldMore);
        trans("tr10-createworld-back", DevShotVerify::createWorldMore);
        add(action(c -> close(c)));
    }

    /** Press the open screen's Done button (1.15.2: Esc / onClose closes every screen, "back" is the Done button). */
    private static void pressDone(MinecraftClient c) {
        try {
            if (c.currentScreen == null) return;
            String done = net.minecraft.client.resource.language.I18n.translate("gui.done");
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.ButtonWidget
                        && done.equals(((net.minecraft.client.gui.widget.ButtonWidget) e).getMessage())) {
                    ((net.minecraft.client.gui.widget.ButtonWidget) e).onPress();
                    return;
                }
            }
            say("no Done button on " + c.currentScreen.getClass().getSimpleName());
        } catch (Throwable t) { skip("press done", t); }
    }

    /** Select a different advancement root tab than the current one (drives AdvancementsScreen.selectTab). */
    private static void nextAdvancementTab(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.advancement.AdvancementsScreen)) return;
            net.minecraft.client.gui.screen.advancement.AdvancementsScreen s = (net.minecraft.client.gui.screen.advancement.AdvancementsScreen) c.currentScreen;
            Field tf = net.minecraft.client.gui.screen.advancement.AdvancementsScreen.class.getDeclaredField("tabs");
            tf.setAccessible(true);
            Field sf = net.minecraft.client.gui.screen.advancement.AdvancementsScreen.class.getDeclaredField("selectedTab");
            sf.setAccessible(true);
            java.util.Map<?, ?> tabs = (java.util.Map<?, ?>) tf.get(s);
            Object cur = sf.get(s);
            for (java.util.Map.Entry<?, ?> e : tabs.entrySet()) {
                if (e.getValue() != cur) {
                    c.player.networkHandler.getAdvancementHandler()
                            .selectTab((net.minecraft.advancement.Advancement) e.getKey(), true);
                    say("advancement tab -> " + ((net.minecraft.advancement.Advancement) e.getKey()).getId() + " of " + tabs.size());
                    return;
                }
            }
            say("advancement tabs: only " + tabs.size());
        } catch (Throwable t) { skip("next advancement tab", t); }
    }

    /** Press the open CreateWorldScreen's "More World Options…" / "Done" toggle (1.15.2: private toggleMoreOptions). */
    private static void createWorldMore(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen)) {
                say("no CreateWorldScreen: " + (c.currentScreen == null ? "null" : c.currentScreen.getClass().getSimpleName()));
                return;
            }
            // 1.15.2: the toggle is setMoreOptionsOpen(boolean moreOptionsOpen), the flag moreOptionsOpen (both unnamed in yarn)
            Field f = net.minecraft.client.gui.screen.world.CreateWorldScreen.class.getDeclaredField("moreOptionsOpen");
            f.setAccessible(true);
            Method m = net.minecraft.client.gui.screen.world.CreateWorldScreen.class.getDeclaredMethod("setMoreOptionsOpen", boolean.class);
            m.setAccessible(true);
            m.invoke(c.currentScreen, !f.getBoolean(c.currentScreen));
            say("create-world more-options toggled");
        } catch (Throwable t) { skip("create world more options", t); }
    }

    // ============================================================================================================
    //  gap: the second-pass ports (filled in as each lands)
    // ============================================================================================================

    private static void buildGap() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); emptyHand(c); clearChat(c); clearToasts(c); lookDown(c, 20f); }));
        add(waitMs(500));

        // (1) cycle-button value roll + press pulse: a REAL click on the Options screen's difficulty button
        add(action(c -> { try { c.openScreen(new net.minecraft.client.gui.screen.world.CreateWorldScreen(new net.minecraft.client.gui.screen.TitleScreen())); }
                          catch (Throwable t) { skip("create world screen", t); } }));
        add(waitMs(1200));
        add(still("gp-cycle-pre"));
        add(burst("gp-cycle", 9, DevShotVerify::clickCycleButton, null));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (2) the waiting screen (TaskScreen / GenericWaitingScreen) does not exist in 1.15.2 (added in 1.19): N/A

        // (3) SF Symbols: a gallery of every mapped sprite (top row vanilla, bottom row replaced) + real checkboxes
        add(shot("gp-sf-gallery", c -> open(c, sfGallery()), 900));
        add(shot("gp-sf-gallery-hover", null, 500, c -> { hx = 60 + 8; hy = 150 + 8; }));
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));
        // ...and in place: the recipe book (page arrows + filter)
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); cmd(c, "recipe give @a *"); fillInventory(c); }));
        add(waitMs(500));
        add(action(c -> open(c, new InventoryScreen(c.player))));
        add(waitMs(500));
        // the book slides out from behind the inventory, and back under it on close (RecipeBookSlide)
        add(still("gp-rb-pre"));
        add(burst("gp-rb-open", 12, DevShotVerify::openRecipeBook, null));
        add(shot("gp-sf-recipebook", null, 700));
        add(burst("gp-rb-close", 12, DevShotVerify::toggleRecipeBook, null));
        add(waitMs(300));
        add(action(c -> { closeRecipeBook(c); close(c); }));
        add(waitMs(400));

        // (4) command suggestions: fade/rise in, gliding highlight, fade out
        add(action(c -> open(c, new net.minecraft.client.gui.screen.ChatScreen(""))));
        add(waitMs(500));
        add(still("gp-sugg-pre"));
        add(burst("gp-sugg-in", 9, c -> { net.minecraft.client.gui.widget.TextFieldWidget tf = chatField(c); if (tf != null) tf.setText("/ga"); }, null));
        add(waitMs(400));
        add(burst("gp-sugg-move", 8, c -> { if (c.currentScreen != null) c.currentScreen.keyPressed(264, 0, 0); }, null));
        add(waitMs(300));
        add(burst("gp-sugg-out", 9, c -> { net.minecraft.client.gui.widget.TextFieldWidget tf = chatField(c); if (tf != null) tf.setText(""); }, null));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (5) list entries arriving later cascade in: the world list loads its saves asynchronously
        add(burst("gp-worldlist", 26, c -> open(c, new net.minecraft.client.gui.screen.world.SelectWorldScreen(
                new net.minecraft.client.gui.screen.TitleScreen())), null));
        add(shot("gp-worldlist-hover", null, 700, DevShotVerify::hoverWorldEntry));
        // 1.15.2: the saves are usually listed by the list's first frame (nothing arrives "later"), so the cascade is
        // shown with a search refill instead — typing in the search box rebuilds the entries
        add(action(c -> { hx = hy = -1; }));
        add(waitMs(300));
        add(burst("gp-worldlist-refill", 12, c -> worldSearch(c, "dev"), null));
        add(waitMs(300));
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));

        // (6) server list: the ping result (here: unreachable) fades/rises in
        add(action(c -> {
            try {
                net.minecraft.client.options.ServerList sl = new net.minecraft.client.options.ServerList(c);
                sl.loadFile();
                if (sl.size() == 0) {
                    sl.add(new net.minecraft.client.network.ServerInfo("S1mp1e 測試伺服器", "127.0.0.1:1", false));
                    sl.saveFile();
                }
            } catch (Throwable t) { skip("server list", t); }
        }));
        add(burst("gp-serverlist", 40, c -> open(c, new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(
                new net.minecraft.client.gui.screen.TitleScreen())), c -> "t"));
        // 1.15.2: the refused ping of 127.0.0.1:1 only comes back after ~3-4 s here (later than on the newer lines), so
        // the status fade is caught by a second, later burst
        add(burst("gp-serverlist-b", 40, null, null));
        add(shot("gp-serverlist-hover", null, 600, c -> { hx = c.getWindow().getScaledWidth() / 2.0 - 120; hy = 52; }));
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));

        // (7) connect screen card (constructed, never connects) — previously unverified
        add(shot("gp-connect", c -> {
            try {
                // 1.15.2: both ConnectScreen constructors disconnect the client and START CONNECTING (there is no
                // parent-only constructor before 1.17). The harness must do neither, so the screen object is
                // allocated without running a constructor and given the fields Screen / ConnectScreen need.
                Class<?> unsafe = Class.forName("sun.misc.Unsafe");   // by name: --release 8 hides sun.misc from javac
                Field uf = unsafe.getDeclaredField("theUnsafe");
                uf.setAccessible(true);
                Object cs = unsafe.getMethod("allocateInstance", Class.class)
                        .invoke(uf.get(null), net.minecraft.client.gui.screen.ConnectScreen.class);
                setField(Screen.class, cs, "title", new net.minecraft.text.LiteralText(""));
                setField(Screen.class, cs, "children", new ArrayList<net.minecraft.client.gui.Element>());
                setField(Screen.class, cs, "buttons", new ArrayList<net.minecraft.client.gui.widget.AbstractButtonWidget>());
                setField(net.minecraft.client.gui.screen.ConnectScreen.class, cs, "parent", new net.minecraft.client.gui.screen.TitleScreen());
                setField(net.minecraft.client.gui.screen.ConnectScreen.class, cs, "status", new net.minecraft.text.TranslatableText("connect.connecting"));
                setField(net.minecraft.client.gui.screen.ConnectScreen.class, cs, "narratorTimer", Long.valueOf(Long.MAX_VALUE / 2));   // (unnamed in this yarn)
                open(c, (Screen) cs);
            } catch (Throwable t) { skip("connect screen", t); }
        }, 900));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (8) sign typing — previously unverified
        add(action(c -> {
            try {
                net.minecraft.block.entity.SignBlockEntity be = new net.minecraft.block.entity.SignBlockEntity();
                be.setLocation(c.world, c.player.getBlockPos());
                // 1.15.2: the edit screen closes itself unless the sign's cached state is a sign block (there is no
                // (pos, state) constructor yet, and no sign stands at that position)
                setField(net.minecraft.block.entity.BlockEntity.class, be, "cachedState", net.minecraft.block.Blocks.OAK_SIGN.getDefaultState());
                open(c, new net.minecraft.client.gui.screen.ingame.SignEditScreen(be));
            } catch (Throwable t) { skip("sign screen", t); }
        }));
        add(waitMs(600));
        add(typeScreen("gp-sign", "S1mp1e 玻璃"));
        add(shot("gp-sign-settled", null, 500));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (8b) book & quill: per-glyph typing with word wrap (BookEditTypingMixin)
        add(action(DevShotVerify::openBookEdit));
        add(waitMs(700));
        add(still("gp-book-pre"));
        add(typeScreen("gp-book", "Liquid glass 液態玻璃 types like this, wrapping words"));
        add(shot("gp-book-settled", null, 500));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (9) item flight (shift-click in a chest) — previously unverified. Empty inventory (room to land in) and make
        //     the locally built handler the player's current one, or clickSlot ignores the click (mismatching container).
        add(action(c -> cmd(c, "clear @a")));
        add(waitMs(500));
        add(action(c -> { openChest(c);
            if (c.currentScreen instanceof ContainerScreen<?>) c.player.container = ((ContainerScreen<?>) c.currentScreen).getContainer(); }));
        add(waitMs(600));
        add(still("gp-flight-pre"));
        add(action(c -> dev.s1mp1e.glass.render.ItemFlights.debugLog = true));
        add(burst("gp-flight", 10, DevShotVerify::quickMoveFirstSlot, null));
        // a move made for the player (pick up and put down in one go, the way Item Scroller does it): flies
        add(burst("gp-autoflight", 6, c -> pickUpAndPlace(c, 0), null));
        add(waitMs(400));
        // by hand: picked up in one frame, put down in a later one — must NOT fly (no "[ItemFlights] spawn" line
        // between the marks, and the item sits in the slot on the first frame)
        add(action(c -> pickUpAndPlace(c, 1)));
        add(waitMs(300));
        add(burst("gp-noflight", 6, c -> pickUpAndPlace(c, 2), null));
        add(action(c -> dev.s1mp1e.glass.render.ItemFlights.debugLog = false));
        add(action(c -> close(c)));
        add(waitMs(500));

        // (10) health trail — previously unverified
        add(action(c -> cmd(c, "effect give @a minecraft:instant_health 1 4 true")));
        add(waitMs(600));
        add(still("gp-health-pre"));
        add(burst("gp-health", 22, c -> { try { sp(c).setHealth(13f); } catch (Throwable t) { skip("set health", t); } },
                c -> "client hp=" + c.player.getHealth() + " server hp=" + (sp(c) == null ? -1f : sp(c).getHealth())
                        + " paused=" + c.isPaused() + " gm=" + c.interactionManager.getCurrentGameMode()));
        add(waitMs(600));
        add(action(c -> cmd(c, "effect give @a minecraft:instant_health 1 9 true")));
        add(waitMs(400));

        // (11) tab list: fade in on press, fade OUT on release (was too fast to see)
        add(still("gp-tablist-pre"));
        add(burst("gp-tablist-in", 8, DevShotVerify::tablistShow, null));
        add(waitMs(500));
        // release the key only: removing the fake entries too would take a single-player list below vanilla's
        // "more than one player" condition, and it would vanish in one frame for a reason unrelated to the fade
        add(burst("gp-tablist-out", 10, c -> DevShot.pressKey(c.options.keyPlayerList, false), null));
        add(waitMs(400));
        add(action(DevShotVerify::tablistHide));
        add(waitMs(200));
    }

    /** Press the recipe-book button of the open inventory (toggles the book either way). */
    private static void toggleRecipeBook(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof InventoryScreen)) return;
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.TexturedButtonWidget) { ((net.minecraft.client.gui.widget.TexturedButtonWidget) e).onPress(); break; }
            }
        } catch (Throwable t) { skip("toggle recipe book", t); }
    }

    /** A real left click on the first cycle button of the open screen (drives PressPulse + the value roll). */
    private static void clickCycleButton(MinecraftClient c) {
        try {
            if (c.currentScreen == null) return;
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (!(e instanceof net.minecraft.client.gui.widget.ButtonWidget)) continue;
                net.minecraft.client.gui.widget.ButtonWidget b = (net.minecraft.client.gui.widget.ButtonWidget) e;
                String bm = b.getMessage();
                if (b.visible && (b instanceof net.minecraft.client.gui.widget.OptionButtonWidget
                        || bm.indexOf(':') >= 0 || bm.indexOf('\uFF1A') >= 0)) {
                    double mx = b.x + b.getWidth() / 2.0, my = b.y + ((dev.s1mp1e.glass.mixin.ClickableWidgetAccessor) b).s1mp1e$getHeight() / 2.0;
                    c.currentScreen.mouseClicked(mx, my, 0);
                    c.currentScreen.mouseReleased(mx, my, 0);
                    say("cycle click " + b.getMessage());
                    return;
                }
            }
            say("no cycle button on " + c.currentScreen.getClass().getSimpleName());
        } catch (Throwable t) { skip("cycle click", t); }
    }

    /** Type one char per frame into the open screen through charTyped, capturing each frame. */
    private static Scene typeScreen(final String name, final String text) {
        return (c, fr, ms) -> {
            int idx = fr - 1;
            if (c.currentScreen == null) return true;
            if (idx >= 0 && idx < text.length()) {
                try { c.currentScreen.charTyped(text.charAt(idx), 0); } catch (Throwable t) { skip("type " + name, t); }
                capture(c, String.format("%s-%02d.png", name, idx));
                return false;
            }
            return true;
        };
    }

    /** Shift-click (QUICK_MOVE) the first container slot of the open handled screen. */
    private static void quickMoveFirstSlot(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof ContainerScreen<?>)) return;
            ContainerScreen<?> s = (ContainerScreen<?>) c.currentScreen;
            Method m = ContainerScreen.class.getDeclaredMethod("onMouseClick", net.minecraft.container.Slot.class,
                    int.class, int.class, net.minecraft.container.SlotActionType.class);
            m.setAccessible(true);
            net.minecraft.container.Slot slot = s.getContainer().getSlot(13);
            m.invoke(s, slot, slot.id, 0, net.minecraft.container.SlotActionType.QUICK_MOVE);
            say("quick move slot 13");
        } catch (Throwable t) { skip("quick move", t); }
    }

    /** PICKUP a filled container slot, then PICKUP an empty player slot: a move that rides the cursor (no flight). */
    private static void pickUpAndPlace(MinecraftClient c, int phase) {
        try {
            if (!(c.currentScreen instanceof ContainerScreen<?>)) return;
            ContainerScreen<?> s = (ContainerScreen<?>) c.currentScreen;
            Method m = ContainerScreen.class.getDeclaredMethod("onMouseClick", net.minecraft.container.Slot.class,
                    int.class, int.class, net.minecraft.container.SlotActionType.class);
            m.setAccessible(true);
            net.minecraft.container.Slot from = null, to = null;
            for (net.minecraft.container.Slot sl : s.getContainer().slots) {
                if (from == null && sl.hasStack() && phase != 2 && !(sl.inventory instanceof net.minecraft.entity.player.PlayerInventory)) from = sl;
                if (to == null && !sl.hasStack() && sl.inventory instanceof net.minecraft.entity.player.PlayerInventory) to = sl;
            }
            if ((from == null && phase != 2) || to == null) { say("flight probe: no slots"); return; }
            say("flight probe phase " + phase + ": pick slot " + (from == null ? -1 : from.id) + " place slot " + to.id);
            if (phase != 2) m.invoke(s, from, from.id, 0, net.minecraft.container.SlotActionType.PICKUP);
            say("flight probe carried=" + c.player.inventory.getCursorStack());
            if (phase != 1) m.invoke(s, to, to.id, 0, net.minecraft.container.SlotActionType.PICKUP);
            say("flight probe end: target has " + to.getStack() + " carried=" + c.player.inventory.getCursorStack());
        } catch (Throwable t) { skip("pick up and place", t); }
    }

    /**
     * Every atlas region SfIcons maps in 1.20.1 (there are no GUI sprites yet: each icon is a region of a
     * {@code textures/gui/...png} atlas): top row drawn as vanilla (devBypass), bottom row replaced; plus two real
     * checkboxes. Each item = {texture, u, v, w, h, texW, texH}.
     */
    private static Screen sfGallery() {
        final String[][] items = dev.s1mp1e.glass.render.SfIcons.devGallery();
        return new Screen(new net.minecraft.text.LiteralText("SF Symbols")) {
            @Override protected void init() {
                addButton(new net.minecraft.client.gui.widget.CheckboxWidget(60, 150, 20, 20, "未勾選", false));
                addButton(new net.minecraft.client.gui.widget.CheckboxWidget(160, 150, 20, 20, "已勾選", true));
            }
            @Override public void render(int mx, int my, float delta) {
                this.renderBackground();
                super.render(mx, my, delta);
                for (int row = 0; row < 2; row++) {
                    dev.s1mp1e.glass.render.SfIcons.devBypass = row == 0;
                    int x = 20, y = 40 + row * 50;
                    for (String[] it : items) {
                        int u = Integer.parseInt(it[1]), v = Integer.parseInt(it[2]);
                        int w = Integer.parseInt(it[3]), h = Integer.parseInt(it[4]);
                        int tw = Integer.parseInt(it[5]), th = Integer.parseInt(it[6]);
                        try {   // 1.15.2: bind, then blit (SfIconMixin matches the BOUND atlas)
                            com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
                            net.minecraft.client.MinecraftClient.getInstance().getTextureManager().bindTexture(new net.minecraft.util.Identifier(it[0]));
                            net.minecraft.client.gui.DrawableHelper.blit(x, y, (float) u, (float) v, w, h, tw, th);
                        } catch (Throwable t) { /* texture missing in this version */ }
                        x += w + 6;
                        if (x > this.width - 40) { x = 20; y += 22; }
                    }
                }
                dev.s1mp1e.glass.render.SfIcons.devBypass = false;
            }
        };
    }

    // ============================================================================================================
    //  newmenu (Package B/C): loading-status glass cards + LiquidLoader, world-list dark scrim, smooth menu scroll
    // ============================================================================================================

    private static void buildNewMenu() {
        // (B) ProgressScreen — determinate: title + task/percent line + LiquidLoader (fills to progress/100)
        add(shot("nm-progress-determinate", c -> {
            net.minecraft.client.gui.screen.ProgressScreen ps = new net.minecraft.client.gui.screen.ProgressScreen();
            open(c, ps);
            ps.method_15413(new net.minecraft.text.LiteralText("正在儲存世界"));
            ps.method_15414(new net.minecraft.text.LiteralText("寫入區塊 12,480 / 27,700"));
            ps.progressStagePercentage(45);
        }, 900));
        // (B) ProgressScreen — indeterminate sweep: title only, no percent line -> LiquidLoader sweeps
        add(action(c -> {
            net.minecraft.client.gui.screen.ProgressScreen ps = new net.minecraft.client.gui.screen.ProgressScreen();
            open(c, ps);
            ps.method_15413(new net.minecraft.text.LiteralText("正在準備資源"));
        }));
        add(waitMs(500));
        add(burst("nm-progress-indeterminate", 6, null, null));
        add(action(c -> close(c)));
        add(waitMs(400));
        // (C) world-list dark hover scrim (WorldEntryScrimMixin): open the select-world list, hover the first entry
        add(action(c -> open(c, new net.minecraft.client.gui.screen.world.SelectWorldScreen(new net.minecraft.client.gui.screen.TitleScreen()))));
        add(waitMs(1600));   // the WorldListWidget loads its saves asynchronously
        add(shot("nm-worldlist-hover", null, 800, DevShotVerify::hoverWorldEntry));
        add(action(c -> close(c)));
        add(waitMs(400));
        // (C) smooth eased wheel scroll on a long menu list (ListMotionMixin): the key-binds list
        add(action(c -> open(c, new net.minecraft.client.gui.screen.options.ControlsOptionsScreen(new net.minecraft.client.gui.screen.TitleScreen(), c.options))));
        add(waitMs(700));
        add(shot("nm-keybinds-rest", null, 500));
        add(burst("nm-keybinds-scroll", 10, DevShotVerify::wheelMenuList, null));
        // the key-binds page is a settings-shell page now (its own eased scroll); a plain EntryListWidget for
        // ListMotionMixin is the statistics list
        add(action(c -> open(c, new net.minecraft.client.gui.screen.StatsScreen(null, c.player.getStatHandler()))));
        add(waitMs(1800));
        add(shot("nm-stats-rest", null, 400));
        add(burst("nm-stats-scroll", 10, c -> { for (int i = 0; i < 3; i++) wheelMenuList(c); }, DevShotVerify::listProbe));
        add(action(c -> close(c)));
        add(waitMs(400));
    }

    /** Type {@code text} into the open world-select screen's search box (rebuilds the list entries). */
    private static void worldSearch(MinecraftClient c, String text) {
        try {
            if (c.currentScreen == null) return;
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.TextFieldWidget) { ((net.minecraft.client.gui.widget.TextFieldWidget) e).setText(text); say("world search '" + text + "'"); return; }
            }
            say("no search box on " + c.currentScreen.getClass().getSimpleName());
        } catch (Throwable t) { skip("world search", t); }
    }

    /** Scroll amount of the first entry list of the open screen (the eased wheel scroll must move it every frame). */
    private static String listProbe(MinecraftClient c) {
        try {
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.EntryListWidget<?>) {
                    net.minecraft.client.gui.widget.EntryListWidget<?> l = (net.minecraft.client.gui.widget.EntryListWidget<?>) e;
                    return "scroll=" + fmt(l.getScrollAmount());
                }
            }
        } catch (Throwable ignored) {}
        return "no list";
    }

    /** Hover the centre of the first world-list entry (GUI coords) so the dark hover scrim draws. */
    private static void hoverWorldEntry(MinecraftClient c) {
        // WorldListWidget centres on the screen; the first row sits a little below the search box (~y 55, row h 36).
        hx = c.getWindow().getScaledWidth() / 2.0;
        hy = 72;
    }

    /** One wheel notch down over the centre of whatever menu list is on screen (ListMotionMixin eases it). */
    private static void wheelMenuList(MinecraftClient c) {
        try {
            double mx = c.getWindow().getScaledWidth() / 2.0, my = c.getWindow().getScaledHeight() / 2.0;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(mx, my, -1.0);
        } catch (Throwable t) { skip("wheel menu list", t); }
    }

    // ============================================================================================================
    //  newanim (Package D/E): typing, chat arrival/close, tab-list & scoreboard & boss fades, health trail, recipe book
    // ============================================================================================================

    private static void buildNewAnim() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); lookDown(c, 20f); emptyHand(c); clearChat(c); clearToasts(c); }));
        add(waitMs(600));
        // (D) per-glyph typing in the chat input (EditBoxTypingMixin): type one glyph per frame, capture each
        add(action(c -> open(c, new net.minecraft.client.gui.screen.ChatScreen(""))));
        add(waitMs(300));
        add(typeChat("S1mp1e 液態玻璃輸入"));
        add(action(c -> close(c)));
        add(waitMs(400));
        // (D) chat arrival: new lines rise / old lines glide up as they are appended
        add(action(c -> { clearChat(c);
            cmd(c, "tellraw @a \"[S1mp1e] 液態玻璃聊天面板\"");
            cmd(c, "tellraw @a \"<Steve> new lines rise from the bottom\""); }));
        add(waitMs(200));
        add(burst("na-chat-arrival", 8, c -> {
            cmd(c, "tellraw @a \"<Alex> older lines glide up as this one arrives\""); }, null));
        add(action(c -> clearChat(c)));
        add(waitMs(400));
        // (D) tab list fade IN on press, then fade OUT on release (TabListGateMixin holds render through the fade)
        add(burst("na-tablist-in", 8, DevShotVerify::tablistShow, null));
        add(shot("na-tablist-settled", null, 500));
        add(burst("na-tablist-out", 8, c -> DevShot.pressKey(c.options.keyPlayerList, false), null));
        add(waitMs(300));
        add(action(DevShotVerify::tablistHide));
        add(waitMs(400));
        // (D) scoreboard sidebar fade in, then fade out (ScoreboardGlassMixin)
        add(burst("na-scoreboard-in", 8, c -> {
            cmd(c, "scoreboard objectives add s1side dummy \"側邊玻璃記分板\"");
            cmd(c, "scoreboard objectives setdisplay sidebar s1side");
            cmd(c, "scoreboard players set 液態玻璃 s1side 256");
            cmd(c, "scoreboard players set Alex s1side 128");
            cmd(c, "scoreboard players set Steve s1side 64");
            cmd(c, "scoreboard players set Glassmith s1side 32"); }, null));
        add(shot("na-scoreboard-settled", null, 500));
        add(burst("na-scoreboard-out", 8, c -> cmd(c, "scoreboard objectives setdisplay sidebar"), null));
        add(waitMs(300));
        add(action(c -> cmd(c, "scoreboard objectives remove s1side")));
        add(waitMs(400));
        // (D) boss bar appear, then removal ghost fade (BossOverlayGhostMixin)
        add(burst("na-boss-in", 8, c -> {
            cmd(c, "bossbar add s1mp1e:d \"末影龍\"");
            cmd(c, "bossbar set s1mp1e:d color blue");
            cmd(c, "bossbar set s1mp1e:d value 68");
            cmd(c, "bossbar set s1mp1e:d players @a"); }, null));
        add(shot("na-boss-settled", null, 500));
        add(burst("na-boss-out", 8, c -> cmd(c, "bossbar remove s1mp1e:d"), null));
        add(waitMs(400));
        // (E) health trail: full health -> take damage -> the just-lost hearts linger white then drain (HeartTrailMixin)
        add(action(c -> { cmd(c, "effect give @a minecraft:instant_health 1 4 true"); }));
        add(waitMs(500));
        add(burst("na-health-trail", 10, c -> {   // 1.15.2 has no /damage command
            final ServerPlayerEntity p = sp(c);
            if (p != null) c.getServer().execute(() -> p.damage(net.minecraft.entity.damage.DamageSource.GENERIC, 7f));
        }, null));
        add(waitMs(600));
        add(action(c -> cmd(c, "effect give @a minecraft:instant_health 1 9 true")));
        add(waitMs(400));
        // (E) recipe-book open: whole-inventory glide + panel/tab fade + result cascade (RecipeBookInvGlideMixin/RecipeCascadeMixin)
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); cmd(c, "recipe give @a *"); fillInventory(c); }));
        add(waitMs(500));
        add(action(c -> open(c, new InventoryScreen(c.player))));
        add(waitMs(500));
        add(burst("na-recipebook-open", 12, DevShotVerify::openRecipeBook, null));
        add(shot("na-recipebook-settled", null, 700));
        add(action(c -> { closeRecipeBook(c); close(c); }));
        add(waitMs(400));
        add(action(c -> { gamemode(c, GameMode.CREATIVE); open(c, new net.minecraft.client.gui.screen.ChatScreen("closing the chat")); }));
        add(waitMs(900));
        add(burst("na-chat-close", 8, DevShotVerify::close, null));
        add(waitMs(400));
    }

    /** Type {@code text} into the open ChatScreen's field one glyph per frame, capturing each frame so the per-glyph
     *  entrance animation (blur -> sharp + rise, glass caret) is visible frame by frame. */
    private static Scene typeChat(final String text) {
        return (c, fr, ms) -> {
            net.minecraft.client.gui.widget.TextFieldWidget tf = chatField(c);
            int idx = fr - 1;
            if (tf == null) return idx >= text.length();
            if (idx >= 0 && idx < text.length()) {
                try { tf.write(String.valueOf(text.charAt(idx))); } catch (Throwable t) { skip("type", t); }
                capture(c, String.format("na-chattype-%02d.png", idx));
                return false;
            }
            return true;
        };
    }

    private static net.minecraft.client.gui.widget.TextFieldWidget chatField(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) return null;
            Field f = net.minecraft.client.gui.screen.ChatScreen.class.getDeclaredField("chatField");
            f.setAccessible(true);
            return (net.minecraft.client.gui.widget.TextFieldWidget) f.get(c.currentScreen);
        } catch (Throwable t) { return null; }
    }

    // ---- settings shell: every vanilla settings page in the Video Settings layout ---------------------------------

    /** Click the {@code i}-th sidebar entry (0 = General) of the settings shell: M 16 + pad 4, rows of 18 from 47 + 4. */
    private static void shellTab(MinecraftClient c, int i) { sodiumClick(c, 60, 47 + 4 + i * 18 + 9); }

    private static double shellRowY(int i) { return 47 + i * 18 + 9; }

    private static void buildSettings() {
        add(action(c -> { gamemode(c, GameMode.CREATIVE); lookDown(c, 18f); }));
        add(waitMs(500));
        add(shot("st-main", c -> open(c, new net.minecraft.client.gui.screen.SettingsScreen(
                new net.minecraft.client.gui.screen.GameMenuScreen(true), c.options)), 1300));
        add(burst("st-main-slider", 8, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = shellRowY(0); }, null));
        add(shot("st-main-hover", null, 900));
        add(burst("st-to-sound", 10, c -> shellTab(c, 2), null));
        add(shot("st-sound", null, 900, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = shellRowY(0); }));
        // Master Volume: press ON the pill (the grab offset is kept), drag left; press on the pill again, drag right;
        // then past the right end (rubber band) — which also leaves the volume where it was, at 100%
        add(dragRow("st-drag", shellRowY(0), Double.NaN, 0.36, 14));
        add(dragRow("st-drag-back", shellRowY(0), Double.NaN, 0.77, 8));
        add(dragRow("st-drag-end", shellRowY(0), Double.NaN, 1.25, 6));
        // vanilla Video Settings (there is no Sodium for 1.15.2)
        add(shot("st-video", c -> shellTab(c, 3), 900));
        add(shot("st-video-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -6); }, 900));
        add(shot("st-skin", c -> shellTab(c, 1), 900));
        // a cycle row (Main Hand): the value rolls; a second click puts it back
        add(burst("st-roll", 12, c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(7)), null));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(7))));
        add(waitMs(400));
        add(burst("st-skin-toggle", 10, c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0)), null));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0))));
        add(shot("st-controls", c -> shellTab(c, 4), 900));
        add(shot("st-mouse", c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0)), 900));
        add(action(c -> shellTab(c, 0)));
        add(waitMs(500));
        add(action(c -> shellTab(c, 4)));
        add(waitMs(500));
        // 1.15.2: no separate Key Binds page - the key list is the Controls page itself (below the Mouse Settings link
        // and the Auto-Jump switch); scrolled down, then back to the top for the capture test
        add(shot("st-keys", null, 600));
        add(shot("st-keys-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -8); }, 1000));
        add(action(c -> { if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, 400); }));
        add(waitMs(800));
        // the Auto-Jump switch of the same page, flipped and flipped back (burst)
        add(burst("st-controls-toggle", 10, c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(1)), null));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(1))));
        add(waitMs(400));
        // key capture stays vanilla: click the first binding's edit button ("> key <"), press R (rebinds, red = a
        // conflict with nothing here), then its Reset button puts the default back
        add(shot("st-keys-capture", c -> keyRowClick(c, 0, false), 500));
        add(shot("st-keys-bound", c -> { if (c.currentScreen != null) c.currentScreen.keyPressed(82, 0, 0); }, 500));
        add(shot("st-keys-reset", c -> keyRowClick(c, 0, true), 500));
        // a slider row pressed on BARE track (Mouse Settings > Sensitivity): the pill glides to the pointer, then rides it
        add(action(c -> { shellTab(c, 0); }));
        add(waitMs(400));
        add(action(c -> shellTab(c, 4)));
        add(waitMs(500));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0))));
        add(waitMs(600));
        add(dragRow("st-track-drag", shellRowY(0), 0.85, 0.15, 10));
        // the drag is in pixels, so it does not land on the starting value: put the default back exactly
        add(action(c -> { c.options.mouseSensitivity = 0.5; c.options.write(); }));
        add(waitMs(300));
        add(shot("st-language", c -> shellTab(c, 5), 1000));
        // the top of that page: the loose "Force Unicode Font" option is a card of its own above the language rows
        add(shot("st-language-top", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, 400); }, 1100));
        add(shot("st-chat", c -> shellTab(c, 6), 900));
        // 1.15.2: a SWITCH row with a tooltip (the whole row is the widget): Chat Settings row 14, Hide Matched Names
        add(shot("st-chat-tooltip", null, 1600, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = shellRowY(14); }));
        add(action(c -> { hx = hy = -1; }));
        // 1.15.2: the Accessibility page fits the window (14 rows), Chat Settings is the page that scrolls
        add(shot("st-chat-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -4); }, 1100));
        add(shot("st-access", c -> shellTab(c, 8), 900));
        add(shot("st-access-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -4); }, 1100));
        // over the track: a slider row's widget is only its track (+ the pill's overhang), and the tooltip belongs to
        // the widget. 1.15.2: only some options carry a tooltip. Row 10 = Distortion Effects (a slider).
        add(shot("st-access-tooltip", null, 1600, c -> hoverSliderRow(c, shellRowY(10))));
        // (1.15.2: Accessibility has 13 rows and no switch with a tooltip - see st-chat-tooltip for the switch case)
        // (1.15.2 has no Online Options page - it arrived in 1.18.)
        // 1.15.2 only: the resource pack screen extends GameOptionsScreen (PackScreen is a plain Screen from 1.16) and
        // must stay the vanilla two-list screen, not a shell page
        add(shot("st-packs", c -> shellTab(c, 7), 1200));
        add(action(c -> open(c, new net.minecraft.client.gui.screen.SettingsScreen(
                new net.minecraft.client.gui.screen.GameMenuScreen(true), c.options))));
        add(waitMs(700));
        add(shot("st-general-back", c -> shellTab(c, 0), 900));
        add(action(c -> { DevShot.setTarget(854, 480); }));
        add(waitMs(600));
        add(shot("st-small", c -> shellTab(c, 6), 900));
        add(action(c -> { close(c); hx = hy = -1; DevShot.setTarget(1280, 720); }));
        add(waitMs(600));
        // regression: a vanilla slider OUTSIDE the settings pages keeps the normal glass skin and its own pointer
        // mapping (the scripted drag cannot hold the physical button, so the pill glides instead of lifting)
        add(shot("st-plain", c -> open(c, plainSliderScreen()), 900, c -> { hx = 40; hy = 40; }));
        add(drag("st-plain-drag", 300, 380, 110, 10));
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));
    }

    /** A bare screen (no settings shell) with two vanilla sliders, the second one stepped. */
    private static Screen plainSliderScreen() {
        return new Screen(new net.minecraft.text.LiteralText("Plain sliders")) {
            @Override protected void init() {
                // 1.15.2: the slider constructor takes no message; updateMessage() sets it (called once by hand)
                addButton(new net.minecraft.client.gui.widget.SliderWidget(245, 100, 150, 20, 0.5) {
                    { updateMessage(); }
                    @Override protected void updateMessage() { setMessage("Value: " + Math.round(this.value * 100.0) + "%"); }
                    @Override protected void applyValue() { }
                });
                addButton(new net.minecraft.client.gui.widget.SliderWidget(245, 130, 150, 20, 0.25) {
                    { updateMessage(); }
                    @Override protected void updateMessage() { setMessage("Steps: " + (1 + Math.round(this.value * 4.0))); }
                    @Override protected void applyValue() { this.value = Math.round(this.value * 4.0) / 4.0; }
                });
            }
            @Override public void render(int mx, int my, float delta) {
                this.renderBackground();
                super.render(mx, my, delta);
            }
        };
    }

    /** The vanilla slider whose widget covers the row at {@code y} (a settings-row slider's widget is its track). */
    private static net.minecraft.client.gui.widget.SliderWidget sliderAt(MinecraftClient c, double y) {
        try {
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (!(e instanceof net.minecraft.client.gui.widget.SliderWidget)) continue;
                net.minecraft.client.gui.widget.SliderWidget sw = (net.minecraft.client.gui.widget.SliderWidget) e;
                if (sw.visible && y >= sw.y && y < sw.y + ((dev.s1mp1e.glass.mixin.ClickableWidgetAccessor) sw).s1mp1e$getHeight()) return sw;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Virtual cursor onto the middle of the track of the slider row at {@code y}. */
    private static void hoverSliderRow(MinecraftClient c, double y) {
        net.minecraft.client.gui.widget.SliderWidget sw = sliderAt(c, y);
        hx = sw != null ? sw.x + sw.getWidth() / 2.0 : c.getWindow().getScaledWidth() - 16 - 6 - 34 - 14 - 66;
        hy = y;
    }

    private static String sliderText(MinecraftClient c, double y) {
        net.minecraft.client.gui.widget.SliderWidget sw = sliderAt(c, y);
        return sw == null ? "" : " [" + sw.getMessage() + " v="
                + fmt((float) ((dev.s1mp1e.client.gui.SettingsShell.SliderAccess) sw).s1mp1e$value()) + "]";
    }

    /** A scripted slider drag: press at (xa, y), move to xb over {@code n} frames (one capture each), release, settle. */
    private static Scene drag(final String name, final double xa, final double xb, final double y, final int n) {
        return (c, fr, ms) -> {
            Screen s = c.currentScreen;
            if (s == null) return true;
            if (fr == 1) { hx = xa; hy = y; return false; }
            if (fr < 4) return false;
            if (fr == 4) {
                dev.s1mp1e.client.gui.VanillaSliderSkin.devMouseDown = true;
                s.mouseClicked(xa, y, 0);
                say("drag " + name + " press x=" + fmt(xa) + sliderText(c, y));
                return false;
            }
            int k = fr - 5;
            if (k < n) {
                double x = xa + (xb - xa) * (k + 1) / n;
                hx = x;
                s.mouseDragged(x, y, 0, 0, 0);
                capture(c, String.format("%s-%02d.png", name, k));
                say("drag " + name + " " + k + " x=" + fmt(x) + sliderText(c, y));
                return false;
            }
            if (k == n) {
                s.mouseReleased(xb, y, 0);
                dev.s1mp1e.client.gui.VanillaSliderSkin.devMouseDown = false;
                say("drag " + name + " release x=" + fmt(xb) + sliderText(c, y));
                return false;
            }
            if (k <= n + 8) { capture(c, String.format("%s-r%02d.png", name, k - n)); return false; }
            return true;
        };
    }

    /**
     * {@link #drag} on the settings-row slider at {@code y}, in fractions of its track (the widget is the track plus the
     * pill's 9 px overhang on either side). {@code f0 = NaN}: press on the pill, wherever it is.
     */
    private static Scene dragRow(final String name, final double y, final double f0, final double f1, final int n) {
        final Scene[] inner = new Scene[1];
        return (c, fr, ms) -> {
            if (fr == 1) {
                net.minecraft.client.gui.widget.SliderWidget sw = sliderAt(c, y);
                if (sw == null) { say("drag " + name + ": no slider row at y=" + fmt(y)); return true; }
                double tx0 = sw.x + 9, tx1 = sw.x + sw.getWidth() - 9;
                double v = ((dev.s1mp1e.client.gui.SettingsShell.SliderAccess) sw).s1mp1e$value();
                say("drag " + name + " track " + fmt(tx0) + ".." + fmt(tx1) + " value=" + fmt((float) v));
                inner[0] = drag(name, tx0 + (tx1 - tx0) * (Double.isNaN(f0) ? v : f0), tx0 + (tx1 - tx0) * f1, y, n);
            }
            return inner[0] == null || inner[0].run(c, fr, ms);
        };
    }

    /** Click the edit (or reset) button of the {@code i}-th key-binding row of the open Key Binds page. */
    private static void keyRowClick(MinecraftClient c, int i, boolean reset) {
        try {
            Screen s = c.currentScreen;
            if (s == null) return;
            int n = 0;
            net.minecraft.client.gui.widget.ButtonWidget edit = null;
            for (net.minecraft.client.gui.Element e : s.children()) {
                if (!(e instanceof net.minecraft.client.gui.widget.ButtonWidget)) continue;
                net.minecraft.client.gui.widget.ButtonWidget b = (net.minecraft.client.gui.widget.ButtonWidget) e;
                if (b.getWidth() == 84) { if (n++ == i) edit = b; continue; }      // the shell's edit-button width
                if (edit != null && reset) { edit = b; break; }                     // its reset button follows it
            }
            if (edit == null) { say("key row " + i + " not found"); return; }
            double mx = edit.x + edit.getWidth() / 2.0, my = edit.y + ((dev.s1mp1e.glass.mixin.ClickableWidgetAccessor) edit).s1mp1e$getHeight() / 2.0;
            hx = mx; hy = my;
            s.mouseClicked(mx, my, 0);
            s.mouseReleased(mx, my, 0);
            say("key row " + i + (reset ? " reset" : " edit") + " -> " + edit.getMessage());
        } catch (Throwable t) { skip("key row click", t); }
    }

    private static void sodiumClick(MinecraftClient c, double x, double y) {
        try {
            Screen s = c.currentScreen;
            if (s == null) return;
            hx = x; hy = y;
            s.mouseClicked(x, y, 0);
            s.mouseReleased(x, y, 0);
        } catch (Throwable t) { skip("sodium click", t); }
    }

    private static ServerPlayerEntity sp(MinecraftClient c) {
        MinecraftServer s = c.getServer();
        return s == null || s.getPlayerManager().getPlayerList().isEmpty() ? null : s.getPlayerManager().getPlayerList().get(0);
    }

    private static void gamemode(MinecraftClient c, GameMode m) {
        cmd(c, "gamemode " + m.getName() + " @a");
        try { if (c.interactionManager != null) c.interactionManager.setGameMode(m); } catch (Throwable t) { skip("client gamemode", t); }
    }

    private static List<StatusEffectInstance> effectsList() {
        List<StatusEffectInstance> l = new ArrayList<StatusEffectInstance>();
        l.add(new StatusEffectInstance(StatusEffects.SPEED, 12000, 0, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.STRENGTH, 36000, 1, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.HASTE, 9000, 0, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.POISON, 6000, 0, false, false, true));
        l.add(new StatusEffectInstance(StatusEffects.REGENERATION, 18000, 0, true, false, true));
        l.add(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 999999, 0, false, false, true));
        return l;
    }

    private static void effectsGive(MinecraftClient c) {
        final ServerPlayerEntity p = sp(c);
        if (p != null && c.getServer() != null) {
            c.getServer().execute(() -> { p.clearStatusEffects(); for (StatusEffectInstance e : effectsList()) p.addStatusEffect(e); });
        }
        try {
            ClientPlayerEntity cp = c.player;
            cp.clearStatusEffects();
            for (StatusEffectInstance e : effectsList()) cp.addStatusEffect(e);
        } catch (Throwable t) { skip("effects (client)", t); }
    }

    private static void clearEffects(MinecraftClient c) {
        cmd(c, "effect clear @a");
        try { c.player.clearStatusEffects(); } catch (Throwable ignored) {}
    }

    private static void fillInventory(MinecraftClient c) {
        Item[] pool = { Items.GOLDEN_APPLE, Items.DIAMOND_SWORD, Items.BOW, Items.IRON_PICKAXE, Items.TORCH, Items.OAK_LOG,
                Items.BREAD, Items.ARROW, Items.REDSTONE, Items.ENDER_PEARL, Items.BOOK, Items.COMPASS, Items.CLOCK,
                Items.EMERALD, Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COAL, Items.GLASS, Items.BRICKS,
                Items.SAND, Items.GRAVEL, Items.CACTUS, Items.PUMPKIN, Items.MELON_SLICE, Items.CARROT, Items.POTATO };
        final ServerPlayerEntity p = sp(c);
        final List<ItemStack> stacks = new ArrayList<ItemStack>();
        for (int i = 9; i < 36; i++) {
            Item it = pool[(i - 9) % pool.length];
            stacks.add(new ItemStack(it, Math.min(it.getMaxCount(), 1 + (i * 7) % 24)));
        }
        if (p != null && c.getServer() != null) {
            c.getServer().execute(() -> { for (int i = 9; i < 36; i++) p.inventory.setInvStack(i, stacks.get(i - 9).copy()); });
        }
        try { for (int i = 9; i < 36; i++) c.player.inventory.setInvStack(i, stacks.get(i - 9).copy()); }
        catch (Throwable t) { skip("fill inventory (client)", t); }
    }

    private static void namedItem(MinecraftClient c, int slot, String name) {
        ItemStack st = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 7);
        st.setCustomName(new net.minecraft.text.LiteralText(name));
        final ServerPlayerEntity p = sp(c);
        if (p != null && c.getServer() != null) { final ItemStack sc = st.copy(); c.getServer().execute(() -> p.inventory.setInvStack(slot, sc)); }
        try { c.player.inventory.setInvStack(slot, st.copy()); } catch (Throwable ignored) {}
    }

    private static void namedHelmet(MinecraftClient c, String name) {
        ItemStack st = new ItemStack(Items.IRON_HELMET);
        st.setCustomName(new net.minecraft.text.LiteralText(name));
        final ServerPlayerEntity p = sp(c);
        if (p != null && c.getServer() != null) {
            final ItemStack sc = st.copy();
            c.getServer().execute(() -> p.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, sc));
        }
        try { c.player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, st.copy()); } catch (Throwable ignored) {}
    }

    private static void emptyHand(MinecraftClient c) {
        try {
            c.player.inventory.selectedSlot = 5;   // an empty hotbar slot: no held item across the HUD shots
            c.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(5));
        } catch (Throwable t) { skip("empty hand", t); }
    }

    private static void lookDown(MinecraftClient c, float pitch) {
        try {
            ClientPlayerEntity cp = c.player;
            cp.yaw = 0f; cp.prevYaw = 0f; cp.headYaw = 0f;
            cp.pitch = pitch; cp.prevPitch = pitch;
            final ServerPlayerEntity p = sp(c);
            if (p != null && c.getServer() != null) c.getServer().execute(() ->
                    p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), 0f, pitch));
        } catch (Throwable t) { skip("look", t); }
    }

    private static void clearChat(MinecraftClient c) { try { c.inGameHud.getChatHud().clear(false); } catch (Throwable ignored) {} }
    private static void clearToasts(MinecraftClient c) { try { c.getToastManager().clear(); } catch (Throwable ignored) {} }

    private static final List<net.minecraft.client.network.PlayerListEntry> fakeEntries = new ArrayList<net.minecraft.client.network.PlayerListEntry>();

    @SuppressWarnings("unchecked")
    private static void tablistShow(MinecraftClient c) {
        try {
            net.minecraft.client.gui.hud.PlayerListHud tab = c.inGameHud.getPlayerListWidget();
            tab.setHeader(new net.minecraft.text.LiteralText("S1mp1e — 玻璃玩家列表\nheader / list / footer are refracting glass"));
            tab.setFooter(new net.minecraft.text.LiteralText("grey readability scrim under the names\nping bars / names stay on top"));
            // three extra players so the LIST panel is a tall plate (single-player lists only you). 1.15.2: one
            // playerListEntries map; an entry is built from a player-list packet entry.
            Field lf = net.minecraft.client.network.ClientPlayNetworkHandler.class.getDeclaredField("playerListEntries");
            lf.setAccessible(true);
            java.util.Map<java.util.UUID, net.minecraft.client.network.PlayerListEntry> listed =
                    (java.util.Map<java.util.UUID, net.minecraft.client.network.PlayerListEntry>) lf.get(c.getNetworkHandler());
            if (fakeEntries.isEmpty()) {
                int ping = 40;
                for (String n : new String[]{"Alex", "Steve_2", "Glassmith"}) {
                    fakeEntries.add(new net.minecraft.client.network.PlayerListEntry(
                            new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket().new Entry(
                                    new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(n.getBytes()), n),
                                    ping += 120, GameMode.SURVIVAL, null)));
                }
            }
            for (net.minecraft.client.network.PlayerListEntry e : fakeEntries) listed.put(e.getProfile().getId(), e);
            DevShot.pressKey(c.options.keyPlayerList, true);
        } catch (Throwable t) { skip("tablist show", t); }
    }

    @SuppressWarnings("unchecked")
    private static void tablistHide(MinecraftClient c) {
        try { DevShot.pressKey(c.options.keyPlayerList, false); } catch (Throwable ignored) {}
        try {
            Field lf = net.minecraft.client.network.ClientPlayNetworkHandler.class.getDeclaredField("playerListEntries");
            lf.setAccessible(true);
            java.util.Map<java.util.UUID, net.minecraft.client.network.PlayerListEntry> listed =
                    (java.util.Map<java.util.UUID, net.minecraft.client.network.PlayerListEntry>) lf.get(c.getNetworkHandler());
            for (net.minecraft.client.network.PlayerListEntry e : fakeEntries) listed.remove(e.getProfile().getId());
        } catch (Throwable ignored) {}
    }

    // ---- hover helpers ---------------------------------------------------------------------------------------------

    private static void hoverSlot(MinecraftClient c, int index) {
        ContainerScreen<?> s = hs(c);
        if (s == null) return;
        Container h = s.getContainer();
        if (index < 0 || index >= h.slots.size()) return;
        net.minecraft.container.Slot slot = h.slots.get(index);
        hx = acc(s).s1mp1e$x() + slot.xPosition + 8;
        hy = acc(s).s1mp1e$y() + slot.yPosition + 8;
    }

    /** Compact effect column: icon boxes 32x32 at x+backgroundWidth+2, spacing 33 from the panel top. */
    private static void hoverCompactEffect(MinecraftClient c, int i) {
        ContainerScreen<?> s = hs(c);
        if (s == null) return;
        hx = acc(s).s1mp1e$x() + acc(s).s1mp1e$backgroundWidth() + 2 + 16;
        hy = acc(s).s1mp1e$y() + i * 33 + 16;
    }

    private static Field mouseX, mouseY;

    private static void applyCursor(MinecraftClient c) {
        // No scene cursor -> park the virtual cursor in the empty top-left corner, so the real OS cursor (wherever it
        // sits on the desktop) never produces a stray hover/tooltip in a shot.
        double gx = (hx < 0 || hy < 0) ? 4 : hx, gy = (hx < 0 || hy < 0) ? 4 : hy;
        try {
            net.minecraft.client.util.Window w = c.getWindow();
            double px = gx * w.getWidth() / (double) w.getScaledWidth();
            double py = gy * w.getHeight() / (double) w.getScaledHeight();
            if (mouseX == null) {
                mouseX = net.minecraft.client.Mouse.class.getDeclaredField("x");
                mouseY = net.minecraft.client.Mouse.class.getDeclaredField("y");
                mouseX.setAccessible(true);
                mouseY.setAccessible(true);
            }
            mouseX.setDouble(c.mouse, px);
            mouseY.setDouble(c.mouse, py);
        } catch (Throwable t) { skip("cursor", t); hx = hy = -1; }
    }

    // ---- module helpers (snapshot + restore everything touched) ---------------------------------------------------

    private static final java.util.Map<String, Object[]> saved = new java.util.HashMap<String, Object[]>();
    private static boolean snapTaken;

    private static void snapshotModules() {
        saved.clear();
        try {
            for (Module m : ModuleManager.all()) {
                List<Object> vals = new ArrayList<Object>();
                vals.add(Boolean.valueOf(m.enabled));
                for (Setting s : m.settings) vals.add(new Object[]{s.boolValue, s.intValue, s.doubleValue, s.colorValue, s.modeValue});
                saved.put(m.name, vals.toArray());
            }
            snapTaken = true;
        } catch (Throwable t) { skip("snapshot modules", t); }
    }

    private static void restoreModules() {
        if (!snapTaken) return;
        try {
            for (Module m : ModuleManager.all()) {
                Object[] v = saved.get(m.name);
                if (v == null) continue;
                m.enabled = (Boolean) v[0];
                for (int i = 0; i < m.settings.size() && i + 1 < v.length; i++) {
                    Object[] sv = (Object[]) v[i + 1];
                    Setting s = m.settings.get(i);
                    s.boolValue = (Boolean) sv[0];
                    s.intValue = (Integer) sv[1];
                    s.doubleValue = (Double) sv[2];
                    s.colorValue = (Integer) sv[3];
                    s.modeValue = (String) sv[4];
                }
            }
            say("modules restored");
        } catch (Throwable t) { skip("restore modules", t); }
        snapTaken = false;
    }

    private static void modEnable(String module, boolean on) {
        Module m = ModuleManager.byName(module);
        if (m != null) m.enabled = on; else say("no module " + module);
    }

    private static void modSetD(String module, String setting, double v) {
        Module m = ModuleManager.byName(module);
        Setting s = m == null ? null : m.setting(setting);
        if (s != null) s.setDouble(v); else say("no setting " + module + "." + setting);
    }

    private static void modSetB(String module, String setting, boolean v) {
        Module m = ModuleManager.byName(module);
        Setting s = m == null ? null : m.setting(setting);
        if (s != null) s.boolValue = v; else say("no setting " + module + "." + setting);
    }

    private static void modSetC(String module, String setting, int argb) {
        Module m = ModuleManager.byName(module);
        Setting s = m == null ? null : m.setting(setting);
        if (s != null) s.colorValue = argb; else say("no setting " + module + "." + setting);
    }

    private static void openConfigModule(MinecraftClient c, int tabIndex, String module) {
        try {
            S1mp1eConfigScreen sc = new S1mp1eConfigScreen();
            c.openScreen(sc);
            Field tabF = S1mp1eConfigScreen.class.getDeclaredField("tab");
            tabF.setAccessible(true);
            tabF.setInt(sc, tabIndex);
            sc.resize(c, c.getWindow().getScaledWidth(), c.getWindow().getScaledHeight());
            Module m = ModuleManager.byName(module);
            if (m != null) {
                Method sel = S1mp1eConfigScreen.class.getDeclaredMethod("applySelect", Module.class);
                sel.setAccessible(true);
                sel.invoke(sc, m);
            }
        } catch (Throwable t) { skip("open config " + module, t); }
    }

    // ============================================================================================================
    //  plumbing
    // ============================================================================================================

    private static ContainerScreen<?> hs(MinecraftClient c) {
        return c.currentScreen instanceof ContainerScreen<?> ? (ContainerScreen<?>) c.currentScreen : null;
    }

    private static HandledScreenAccessor acc(ContainerScreen<?> s) { return (HandledScreenAccessor) (Object) s; }

    private static void setField(Class<?> cl, Object o, String name, Object value) throws Exception {
        Field f = cl.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static float getF(Object o, Class<?> cl, String name) throws Exception {
        Field f = cl.getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(o);
    }

    private static Object getO(Object o, Class<?> cl, String name) throws Exception {
        Field f = cl.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void open(MinecraftClient c, Screen s) {
        try { c.openScreen(s); } catch (Throwable t) { skip("open " + (s == null ? "null" : s.getClass().getSimpleName()), t); }
    }

    private static void close(MinecraftClient c) { try { c.openScreen(null); } catch (Throwable ignored) {} }

    private static String fmt(float v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    private static void say(String s) { System.out.println("[S1mp1e][VERIFY] " + s); }

    private static void skip(String what, Throwable t) {
        System.out.println("[S1mp1e][VERIFY] skipped " + what + ": " + t);
    }

    /** The world-entry loop on DevShot's preview screen. */
    static void loopPreview(float seconds) {
        dev.s1mp1e.client.gui.BrandIntro.draw(seconds, dev.s1mp1e.client.gui.BrandIntro.MODE_LOOP, 1.0F);
    }

    private static void capture(MinecraftClient c, String name) {
        NativeImage img = null;
        try {
            img = ScreenshotUtils.takeScreenshot(c.getFramebuffer().textureWidth, c.getFramebuffer().textureHeight, c.getFramebuffer());
            File f = new File(out, name);
            img.writeFile(f);
        } catch (Throwable t) {
            skip("capture " + name, t);
        } finally {
            if (img != null) try { img.close(); } catch (Throwable ignored) {}
        }
    }
}
