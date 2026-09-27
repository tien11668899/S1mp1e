package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.S1mp1eConfigScreen;
import dev.s1mp1e.glass.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
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
 * DevShot VERIFY sweeps (1.21.1) — the acceptance-checklist scenes of the port spec §5, driven one frame at a time from
 * {@link DevShot}. Completely inert unless {@code S1MP1E_SHOT} is set AND {@code S1MP1E_SHOT_MODE} names one or more of
 * these comma-separated modes (or {@code all}):
 * <ul>
 *   <li>{@code screens}  — (A) creative, crafter, horse, advancements, stats, social, book, book-edit, book-sign, lectern,
 *       death, anvil (error X), smithing, furnace (flame + arrow), brewing, enchanting, chest, survival inventory;</li>
 *   <li>{@code tabs}     — (B) fused tabs: slide / cross-row / hover filmstrips + tab click hit-tests + tab tooltip;</li>
 *   <li>{@code lists}    — (C/D) creative / stonecutter / loom / merchant: rest, mid, held lens (real drag), a
 *       consecutive-frame glide filmstrip after one scroll, and click assertions at rest / mid-glide / edge;</li>
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
        for (String m : mode.split(",")) {
            String t = m.trim();
            if (t.equals("all") || t.equals("screens") || t.equals("tabs") || t.equals("lists") || t.equals("tooltips")
                    || t.equals("effects") || t.equals("hud") || t.equals("modules") || t.equals("flicker")
                    || t.equals("combat")) return true;
        }
        return false;
    }

    static void init(File dir, String modeCsv) {
        out = dir;
        modes.clear();
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
        try { c.setScreen(null); } catch (Throwable ignored) {}
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
        add(waitMs(700));
        switch (mode) {
            case "screens":  buildScreens();  break;
            case "tabs":     buildTabs();     break;
            case "lists":    buildLists();    break;
            case "tooltips": buildTooltips(); break;
            case "effects":  buildEffects();  break;
            case "hud":      buildHud();      break;
            case "modules":  buildModules();  break;
            case "flicker":  buildFlicker();  break;
            case "combat":   buildCombat();   break;
            default: say("unknown mode " + mode);
        }
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));
    }

    // ---- (A) screens -------------------------------------------------------------------------------------------

    private static void buildScreens() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); effectsGive(c); fillInventory(c); }));
        add(waitMs(300));
        add(shot("scr-inventory", c -> open(c, new InventoryScreen(c.player)), 700));
        add(shot("scr-recipebook", c -> open(c, new InventoryScreen(c.player)), 900, DevShotVerify::openRecipeBook));
        add(action(c -> { close(c); closeRecipeBook(c); }));
        add(shot("scr-creative", c -> openCreativeSearch(c), 900));
        add(action(c -> gamemode(c, GameMode.SURVIVAL)));
        add(shot("scr-crafter", DevShotVerify::openCrafter, 700));
        add(shot("scr-horse", DevShotVerify::openHorse, 800));
        add(shot("scr-advancements", c -> open(c, new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
                c.getNetworkHandler().getAdvancementHandler())), 800));
        add(shot("scr-stats", c -> open(c, new net.minecraft.client.gui.screen.StatsScreen(null, c.player.getStatHandler())), 1600));
        add(shot("scr-social", c -> open(c, new net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen()), 800));
        add(shot("scr-book", c -> open(c, new net.minecraft.client.gui.screen.ingame.BookScreen(
                new net.minecraft.client.gui.screen.ingame.BookScreen.Contents(java.util.Arrays.asList(
                        (Text) Text.literal("液態玻璃書頁\n深色墨水必須清晰可讀。\n\nDark ink stays readable on the light warm parchment scrim over the glass page."))))), 700));
        add(shot("scr-book-edit", DevShotVerify::openBookEdit, 700));
        add(shot("scr-book-sign", c -> { openBookEdit(c); }, 700, DevShotVerify::bookSignMode));
        add(shot("scr-lectern", DevShotVerify::openLectern, 800));
        add(shot("scr-death", c -> open(c, new net.minecraft.client.gui.screen.DeathScreen(Text.literal("S1mp1e DevShot"), false)), 1600));
        add(shot("scr-anvil", DevShotVerify::openAnvil, 700));
        add(shot("scr-smithing", DevShotVerify::openSmithing, 900));
        add(shot("scr-furnace", DevShotVerify::openFurnace, 700));
        add(shot("scr-brewing", DevShotVerify::openBrewing, 700));
        add(shot("scr-enchanting", DevShotVerify::openEnchanting, 900));
        add(shot("scr-chest", DevShotVerify::openChest, 700));
        add(shot("scr-stonecutter", DevShotVerify::openStonecutter, 800));
        add(shot("scr-loom", DevShotVerify::openLoom, 800));
        add(shot("scr-merchant", DevShotVerify::openMerchant, 800));
    }

    // ---- (B) fused creative tabs ----------------------------------------------------------------------------------

    private static void buildTabs() {
        add(shot("tabs-a", c -> { openCreativeSearch(c); }, 900, c -> selectTab(c, groupAt(true, 0))));
        add(burst("tabs-slide", 8, c -> selectTab(c, groupAt(true, 4)), null));
        add(waitMs(400));
        add(burst("tabs-cross", 6, c -> selectTab(c, groupAt(false, 1)), null));
        add(waitMs(400));
        add(burst("tabs-hover", 6, c -> { double[] p = tabCell(c, 2, true); if (p != null) { hx = p[0]; hy = p[1]; } }, null));
        add(shot("tabs-hover-settled", null, 400));
        add(burst("tabs-hoverout", 4, c -> { hx = 20; hy = 20; }, null));
        add(waitMs(300));
        // hit-tests: click the CENTRE of a fused cell -> that tab must become selected (getTabX follows the cells)
        add(action(c -> tabClick(c, true, 2)));
        add(waitMs(150));
        add(action(c -> tabClick(c, false, 3)));
        add(waitMs(150));
        add(action(c -> tabClick(c, true, 6)));
        add(waitMs(150));
        add(action(c -> tabClick(c, false, 0)));
        add(waitMs(300));
        add(shot("tabs-tooltip", c -> { double[] p = tabCell(c, 3, true); if (p != null) { hx = p[0]; hy = p[1] + 4; } }, 500));
    }

    // ---- (C/D) lists: creative / stonecutter / loom / merchant ----------------------------------------------------

    private static void buildLists() {
        for (final ListKind k : ListKind.values()) {
            add(shot(k.id + "-rest", c -> k.open(c), 900));
            add(action(c -> { hx = hy = -1; k.wheel(c, k.midSteps); }));
            add(shot(k.id + "-mid", null, 700));
            // held: a REAL press on the glass thumb + a drag partway down (1:1), the thumb morphs into the lens
            add(action(c -> k.pressThumb(c)));
            add(burst(k.id + "-drag", 4, c -> k.dragBy(c, 9.0), c -> k.probe(c)));
            add(shot(k.id + "-held", c -> k.dragBy(c, 4.0), 260));
            // drag far past the end of the track: the lens rubber-bands (1:1 until the end, then resisting)
            add(shot(k.id + "-rubber", c -> k.dragBy(c, 90.0), 200));
            add(action(c -> k.release(c)));
            add(waitMs(600));
            add(action(c -> k.toTop(c)));
            add(waitMs(700));
            add(burst(k.id + "-glide", 10, c -> k.wheel(c, k.glideSteps), c -> k.probe(c)));
            add(waitMs(700));
            // clicks: at rest, mid-glide (snap), edge cell
            add(action(c -> k.toTop(c)));
            add(waitMs(600));
            add(action(c -> k.clickCheck(c, 1, 1, "rest", false)));
            add(waitMs(300));
            add(action(c -> k.wheel(c, k.clickSteps)));
            add(clickMidGlide(k));
            add(waitMs(500));
            add(action(c -> k.clickCheck(c, k.lastRow(), k.lastCol(), "edge", false)));
            add(waitMs(300));
            add(action(c -> close(c)));
            add(waitMs(300));
        }
    }

    /** One frame after a wheel kick the list must be mid-glide; click then (the HEAD snap acts on the drawn item). */
    private static Scene clickMidGlide(final ListKind k) {
        return (c, fr, ms) -> {
            if (fr < 2) return false;
            boolean g = k.gliding(c);
            say("[CLICKS] " + k.id + " mid-glide precondition gliding=" + g + " offset=" + fmt(k.offset(c)));
            if (!g) { fail++; say("[CLICKS] " + k.id + " mid-glide FAIL: not gliding at the click frame"); return true; }
            k.clickCheck(c, 1, 2, "mid-glide", true);
            return true;
        };
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
                c -> { HandledScreen<?> s = hs(c); if (s != null) { hx = acc(s).s1mp1e$x() + 52 + 3 * 16 + 8; hy = acc(s).s1mp1e$y() + 14 + 9; } }));
        add(action(c -> close(c)));
        add(shot("tt-merchant", DevShotVerify::openMerchant, 900,
                c -> { HandledScreen<?> s = hs(c); if (s != null) { hx = acc(s).s1mp1e$x() + 5 + 68 + 8; hy = acc(s).s1mp1e$y() + 18 + 20 + 10; } }));
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
        add(action(c -> { cmd(c, "advancement grant @a only minecraft:story/mine_stone"); cmd(c, "recipe give @a minecraft:diamond_sword"); }));
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
        add(action(c -> { close(c); restoreModules(); }));
    }

    // ---- (R4) flicker ---------------------------------------------------------------------------------------------

    private static void buildFlicker() {
        add(action(c -> { gamemode(c, GameMode.SURVIVAL); clearEffects(c); fillInventory(c); lookDown(c, 35f); emptyHand(c);
                          try { c.options.getMaxFps().setValue(260); c.options.getEnableVsync().setValue(false); }
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
    }

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
    //  list kinds (C / D)
    // ============================================================================================================

    private enum ListKind {
        CREATIVE("creative", 12, 3, 1),
        STONECUTTER("stonecutter", 1, 2, 1),
        LOOM("loom", 2, 3, 1),
        MERCHANT("merchant", 5, 5, 1);

        final String id;
        final int midSteps, glideSteps, clickSteps;
        private float dragY;

        ListKind(String id, int mid, int glide, int click) {
            this.id = id; this.midSteps = mid; this.glideSteps = glide; this.clickSteps = click;
        }

        void open(MinecraftClient c) {
            switch (this) {
                case CREATIVE:    openCreativeSearch(c); break;
                case STONECUTTER: openStonecutter(c);    break;
                case LOOM:        openLoom(c);           break;
                default:          openMerchant(c);       break;
            }
        }

        double[] listCenter(HandledScreen<?> s) {
            int x = acc(s).s1mp1e$x(), y = acc(s).s1mp1e$y();
            switch (this) {
                case CREATIVE:    return new double[]{x + 90, y + 60};
                case STONECUTTER: return new double[]{x + 84, y + 40};
                case LOOM:        return new double[]{x + 88, y + 40};
                default:          return new double[]{x + 50, y + 90};
            }
        }

        /** Thumb centre at the CURRENT eased ratio (the glass thumb, not the vanilla sprite). */
        double[] thumb(MinecraftClient c, HandledScreen<?> s) {
            int x = acc(s).s1mp1e$x(), y = acc(s).s1mp1e$y();
            float r = ratio(c);
            switch (this) {
                case CREATIVE:    return new double[]{x + 181, y + 18 + r * 97 + 7.5};
                case STONECUTTER: return new double[]{x + 125, y + 15 + r * 41 + 7.5};
                case LOOM:        return new double[]{x + 125, y + 13 + r * 41 + 7.5};
                default:          return new double[]{x + 97, y + 18 + r * 124 + 7.5};
            }
        }

        float ratio(MinecraftClient c) {
            float max = maxOffsetPx(c);
            return max <= 0 ? 0f : Math.max(0f, Math.min(1f, offset(c) / max));
        }

        float maxOffsetPx(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return 0;
            try {
                switch (this) {
                    case CREATIVE: {
                        List<ItemStack> l = ((CreativeInventoryScreen.CreativeScreenHandler) s.getScreenHandler()).itemList;
                        return Math.max(0, (l.size() + 8) / 9 - 5) * 18f;
                    }
                    case STONECUTTER: {
                        int n = ((net.minecraft.screen.StonecutterScreenHandler) s.getScreenHandler()).getAvailableRecipeCount();
                        return Math.max(0, (n + 3) / 4 - 3) * 18f;
                    }
                    case LOOM: {
                        int n = ((net.minecraft.screen.LoomScreenHandler) s.getScreenHandler()).getBannerPatterns().size();
                        return Math.max(0, (n + 3) / 4 - 4) * 14f;
                    }
                    default: {
                        int n = ((net.minecraft.screen.MerchantScreenHandler) s.getScreenHandler()).getRecipes().size();
                        return Math.max(0, n - 7) * 20f;
                    }
                }
            } catch (Throwable t) { return 0; }
        }

        float offset(MinecraftClient c) {
            return c.currentScreen instanceof GlideProbe p ? p.s1mp1e$probeOffsetPx() : -1f;
        }

        boolean gliding(MinecraftClient c) {
            return c.currentScreen instanceof GlideProbe p && p.s1mp1e$probeGliding();
        }

        String probe(MinecraftClient c) {
            return "gliding=" + gliding(c) + " offsetPx=" + fmt(offset(c)) + " logicalTop=" + logicalTop(c);
        }

        /** Row-aligned logical top (rows for grids, trades for the merchant). */
        int logicalTop(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return -1;
            try {
                switch (this) {
                    case CREATIVE: {
                        float p = getF(s, CreativeInventoryScreen.class, "scrollPosition");
                        List<ItemStack> l = ((CreativeInventoryScreen.CreativeScreenHandler) s.getScreenHandler()).itemList;
                        int rc = Math.max(0, (l.size() + 8) / 9 - 5);
                        return Math.max((int) (p * rc + 0.5f), 0);
                    }
                    case STONECUTTER: return ((Integer) getO(s, net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollOffset")) / 4;
                    case LOOM:        return (Integer) getO(s, net.minecraft.client.gui.screen.ingame.LoomScreen.class, "visibleTopRow");
                    default:          return (Integer) getO(s, net.minecraft.client.gui.screen.ingame.MerchantScreen.class, "indexStartOffset");
                }
            } catch (Throwable t) { return -1; }
        }

        void wheel(MinecraftClient c, int steps) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            double[] p = listCenter(s);
            for (int i = 0; i < steps; i++) s.mouseScrolled(p[0], p[1], 0.0, -1.0);
        }

        void toTop(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            double[] p = listCenter(s);
            for (int i = 0; i < 400; i++) s.mouseScrolled(p[0], p[1], 0.0, 1.0);
        }

        void pressThumb(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            double[] t = thumb(c, s);
            t[1] -= 3.0;   // a little above the glass-thumb centre: inside vanilla's (shorter) thumb hit box at the ends
            hx = t[0]; hy = t[1];
            dragY = (float) t[1];
            s.mouseClicked(t[0], t[1], 0);
            say("[DRAG] " + id + " press thumb at " + fmt((float) t[0]) + "," + fmt((float) t[1]) + " " + probe(c));
        }

        void dragBy(MinecraftClient c, double dy) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            double[] t = thumb(c, s);
            dragY += (float) dy;
            hx = t[0]; hy = dragY;
            s.mouseDragged(t[0], dragY, 0, 0.0, dy);
        }

        void release(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            s.mouseReleased(hx, hy, 0);
            hx = hy = -1;
        }

        int lastRow() { return this == CREATIVE ? 4 : this == STONECUTTER ? 2 : this == LOOM ? 3 : 6; }
        int lastCol() { return this == CREATIVE ? 8 : this == MERCHANT ? 0 : 3; }

        /** Click the drawn cell (visRow, col) and assert vanilla acted on the item drawn there. */
        void clickCheck(MinecraftClient c, int row, int col, String label, boolean expectSnap) {
            HandledScreen<?> s = hs(c);
            if (s == null) { fail++; say("[CLICKS] " + id + " " + label + " FAIL: no screen"); return; }
            int x = acc(s).s1mp1e$x(), y = acc(s).s1mp1e$y();
            try {
                switch (this) {
                    case CREATIVE: {
                        int idx = row * 9 + col;
                        net.minecraft.screen.slot.Slot slot = s.getScreenHandler().slots.get(idx);
                        Item expected = slot.getStack().getItem();
                        double mx = x + slot.x + 8, my = y + slot.y + 8;
                        hx = mx; hy = my;
                        s.mouseClicked(mx, my, 0);
                        boolean snapped = !gliding(c);
                        ItemStack got = s.getScreenHandler().getCursorStack();
                        boolean ok = !got.isEmpty() && got.getItem() == expected && (!expectSnap || snapped);
                        verdict(ok, label, "slot " + idx + " expected=" + Registries.ITEM.getId(expected) + " got="
                                + Registries.ITEM.getId(got.getItem()) + (expectSnap ? " snapped=" + snapped : ""));
                        s.getScreenHandler().setCursorStack(ItemStack.EMPTY);
                        s.mouseReleased(mx, my, 0);
                        s.getScreenHandler().setCursorStack(ItemStack.EMPTY);
                        break;
                    }
                    case STONECUTTER: {
                        net.minecraft.screen.StonecutterScreenHandler h = (net.minecraft.screen.StonecutterScreenHandler) s.getScreenHandler();
                        int top = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollOffset");
                        double mx = x + 52 + col * 16 + 8, my = y + 14 + row * 18 + 9;
                        hx = mx; hy = my;
                        s.mouseClicked(mx, my, 0);
                        int topAfter = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollOffset");
                        int expected = topAfter + row * 4 + col;
                        boolean snapped = !gliding(c);
                        boolean ok = h.getSelectedRecipe() == expected && (!expectSnap || snapped);
                        String item = expected < h.getAvailableRecipeCount()
                                ? String.valueOf(Registries.ITEM.getId(h.getAvailableRecipes().get(expected).value()
                                        .getResult(c.world.getRegistryManager()).getItem())) : "-";
                        verdict(ok, label, "cell r" + row + "c" + col + " topBefore=" + top + " expectedIndex=" + expected
                                + " (" + item + ") selected=" + h.getSelectedRecipe() + (expectSnap ? " snapped=" + snapped : ""));
                        s.mouseReleased(mx, my, 0);
                        break;
                    }
                    case LOOM: {
                        net.minecraft.screen.LoomScreenHandler h = (net.minecraft.screen.LoomScreenHandler) s.getScreenHandler();
                        double mx = x + 60 + col * 14 + 7, my = y + 13 + row * 14 + 7;
                        hx = mx; hy = my;
                        s.mouseClicked(mx, my, 0);
                        int top = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.LoomScreen.class, "visibleTopRow");
                        int expected = (top + row) * 4 + col;
                        boolean snapped = !gliding(c);
                        boolean ok = h.getSelectedPattern() == expected && (!expectSnap || snapped);
                        verdict(ok, label, "cell r" + row + "c" + col + " top=" + top + " expectedPattern=" + expected
                                + " selected=" + h.getSelectedPattern() + (expectSnap ? " snapped=" + snapped : ""));
                        s.mouseReleased(mx, my, 0);
                        break;
                    }
                    default: {
                        double mx = x + 5 + 44, my = y + 18 + row * 20 + 10;
                        hx = mx; hy = my;
                        s.mouseClicked(mx, my, 0);
                        int top = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.MerchantScreen.class, "indexStartOffset");
                        int sel = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.MerchantScreen.class, "selectedIndex");
                        int expected = top + row;
                        boolean snapped = !gliding(c);
                        net.minecraft.village.TradeOfferList offers = ((net.minecraft.screen.MerchantScreenHandler) s.getScreenHandler()).getRecipes();
                        String item = expected < offers.size() ? String.valueOf(Registries.ITEM.getId(offers.get(expected).getSellItem().getItem())) : "-";
                        boolean ok = sel == expected && (!expectSnap || snapped);
                        verdict(ok, label, "row " + row + " top=" + top + " expectedTrade=" + expected + " (" + item
                                + ") selected=" + sel + (expectSnap ? " snapped=" + snapped : ""));
                        s.mouseReleased(mx, my, 0);
                        break;
                    }
                }
            } catch (Throwable t) {
                fail++;
                skip("click " + id + " " + label, t);
            }
            hx = hy = -1;
        }

        private void verdict(boolean ok, String label, String detail) {
            if (ok) pass++; else fail++;
            say("[CLICKS] " + id + " " + label + " " + detail + " -> " + (ok ? "PASS" : "FAIL"));
        }
    }

    // ============================================================================================================
    //  screen openers
    // ============================================================================================================

    private static void openCreativeSearch(MinecraftClient c) {
        gamemode(c, GameMode.CREATIVE);
        try {
            ItemGroups.updateDisplayContext(c.player.networkHandler.getEnabledFeatures(), true,
                    c.player.networkHandler.getRegistryManager());
        } catch (Throwable t) { skip("rebuild creative", t); }
        open(c, new CreativeInventoryScreen(c.player, c.player.networkHandler.getEnabledFeatures(), true));
        selectTab(c, ItemGroups.getSearchGroup());
    }

    private static void openStonecutter(MinecraftClient c) {
        net.minecraft.screen.StonecutterScreenHandler h =
                new net.minecraft.screen.StonecutterScreenHandler(1, c.player.getInventory());
        // screen FIRST (its constructor registers the contents listener -> canCraft), then the input
        open(c, new net.minecraft.client.gui.screen.ingame.StonecutterScreen(h, c.player.getInventory(), Text.literal("Stonecutter")));
        h.input.setStack(0, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
    }

    private static void openLoom(MinecraftClient c) {
        net.minecraft.screen.LoomScreenHandler h = new net.minecraft.screen.LoomScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.LoomScreen(h, c.player.getInventory(), Text.literal("Loom")));
        h.getSlot(0).setStack(new ItemStack(Items.WHITE_BANNER));   // screen first: its constructor registers the
        h.getSlot(1).setStack(new ItemStack(Items.RED_DYE));        // inventory listener that sets canApplyDyePattern
    }

    private static void openMerchant(MinecraftClient c) {
        net.minecraft.screen.MerchantScreenHandler h = new net.minecraft.screen.MerchantScreenHandler(1, c.player.getInventory());
        net.minecraft.village.TradeOfferList offers = new net.minecraft.village.TradeOfferList();
        Item[] sells = { Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.BREAD, Items.BOOK, Items.ARROW,
                Items.COAL, Items.APPLE, Items.STICK, Items.PAPER, Items.COMPASS, Items.CLOCK, Items.LANTERN,
                Items.GLASS, Items.BELL, Items.SHIELD, Items.BOW, Items.EMERALD_BLOCK, Items.NAME_TAG, Items.SADDLE };
        for (int i = 0; i < sells.length; i++) {
            offers.add(new net.minecraft.village.TradeOffer(
                    new net.minecraft.village.TradedItem(Items.EMERALD, 1 + i),
                    i % 3 == 1 ? java.util.Optional.of(new net.minecraft.village.TradedItem(Items.BOOK, 1)) : java.util.Optional.empty(),
                    new ItemStack(sells[i], 1 + (i % 4)), 12, 5, 0.05f));
        }
        h.setOffers(offers);
        open(c, new net.minecraft.client.gui.screen.ingame.MerchantScreen(h, c.player.getInventory(), Text.literal("Villager")));
    }

    /** Press the survival inventory's recipe-book toggle once (the vanilla button handler repositions the panel). */
    private static void openRecipeBook(MinecraftClient c) {
        if (!(c.currentScreen instanceof InventoryScreen s)) return;
        try {
            Field rb = InventoryScreen.class.getDeclaredField("recipeBook");
            rb.setAccessible(true);
            net.minecraft.client.gui.screen.recipebook.RecipeBookWidget w =
                    (net.minecraft.client.gui.screen.recipebook.RecipeBookWidget) rb.get(s);
            if (w.isOpen()) return;
            for (net.minecraft.client.gui.Element e : s.children()) {
                if (e instanceof net.minecraft.client.gui.widget.TexturedButtonWidget b) { b.onPress(); break; }
            }
        } catch (Throwable t) { skip("open recipe book", t); }
    }

    /** Leave the recipe book closed for the following scenes (the open flag persists in the player's book). */
    private static void closeRecipeBook(MinecraftClient c) {
        try {
            open(c, new InventoryScreen(c.player));
            if (c.currentScreen instanceof InventoryScreen s) {
                Field rb = InventoryScreen.class.getDeclaredField("recipeBook");
                rb.setAccessible(true);
                net.minecraft.client.gui.screen.recipebook.RecipeBookWidget w =
                        (net.minecraft.client.gui.screen.recipebook.RecipeBookWidget) rb.get(s);
                if (w.isOpen()) {
                    for (net.minecraft.client.gui.Element e : s.children()) {
                        if (e instanceof net.minecraft.client.gui.widget.TexturedButtonWidget b) { b.onPress(); break; }
                    }
                }
            }
            close(c);
        } catch (Throwable t) { skip("close recipe book", t); }
    }

    private static void openCrafter(MinecraftClient c) {
        net.minecraft.screen.CrafterScreenHandler h = new net.minecraft.screen.CrafterScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.CrafterScreen(h, c.player.getInventory(), Text.literal("Crafter")));
        h.getSlot(0).setStack(new ItemStack(Items.OAK_PLANKS));
        h.getSlot(1).setStack(new ItemStack(Items.OAK_PLANKS));
        h.getSlot(3).setStack(new ItemStack(Items.OAK_PLANKS));
        h.setSlotEnabled(4, false);   // a disabled crafter slot overlay (information) must stay vanilla on top
        h.setSlotEnabled(8, false);
    }

    private static void openHorse(MinecraftClient c) {
        net.minecraft.entity.passive.HorseEntity horse =
                new net.minecraft.entity.passive.HorseEntity(net.minecraft.entity.EntityType.HORSE, c.world);
        net.minecraft.inventory.SimpleInventory inv = new net.minecraft.inventory.SimpleInventory(2);
        net.minecraft.screen.HorseScreenHandler h =
                new net.minecraft.screen.HorseScreenHandler(1, c.player.getInventory(), inv, horse, 0);
        open(c, new net.minecraft.client.gui.screen.ingame.HorseScreen(h, c.player.getInventory(), horse, 0));
    }

    private static void openBookEdit(MinecraftClient c) {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        try {
            book.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new net.minecraft.component.type.WritableBookContentComponent(
                    java.util.List.of(net.minecraft.text.RawFilteredPair.of(
                            "液態玻璃書寫頁\nDark ink stays readable while you type on the glass page."))));
        } catch (Throwable t) { skip("book content", t); }
        open(c, new net.minecraft.client.gui.screen.ingame.BookEditScreen(c.player, book, net.minecraft.util.Hand.MAIN_HAND));
    }

    private static void bookSignMode(MinecraftClient c) {
        if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.ingame.BookEditScreen s)) return;
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
        try {
            book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new net.minecraft.component.type.WrittenBookContentComponent(
                    net.minecraft.text.RawFilteredPair.of("S1mp1e"), "DevShot", 0,
                    java.util.List.of(net.minecraft.text.RawFilteredPair.of((Text) Text.literal(
                            "講台上的玻璃書頁\nLectern page: glass + light parchment scrim."))), true));
        } catch (Throwable t) { skip("lectern book", t); }
        net.minecraft.screen.LecternScreenHandler h = new net.minecraft.screen.LecternScreenHandler(1);
        h.getSlot(0).setStack(book);
        open(c, new net.minecraft.client.gui.screen.ingame.LecternScreen(h, c.player.getInventory(), Text.literal("Lectern")));
    }

    private static void openAnvil(MinecraftClient c) {
        net.minecraft.screen.AnvilScreenHandler h = new net.minecraft.screen.AnvilScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.AnvilScreen(h, c.player.getInventory(), Text.literal("Repair & Name")));
        h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
        h.getSlot(1).setStack(new ItemStack(Items.DIRT, 3));   // invalid combination -> vanilla red error X stays on top
    }

    private static void openSmithing(MinecraftClient c) {
        net.minecraft.screen.SmithingScreenHandler h = new net.minecraft.screen.SmithingScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.SmithingScreen(h, c.player.getInventory(), Text.literal("Upgrade Gear")));
        h.getSlot(0).setStack(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        h.getSlot(1).setStack(new ItemStack(Items.DIAMOND_CHESTPLATE));
        h.getSlot(2).setStack(new ItemStack(Items.NETHERITE_INGOT));
    }

    private static void openFurnace(MinecraftClient c) {
        net.minecraft.screen.FurnaceScreenHandler h = new net.minecraft.screen.FurnaceScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.FurnaceScreen(h, c.player.getInventory(), Text.literal("Furnace")));
        h.getSlot(0).setStack(new ItemStack(Items.RAW_IRON, 12));
        h.getSlot(1).setStack(new ItemStack(Items.COAL, 5));
        h.setProperty(0, 120); h.setProperty(1, 200); h.setProperty(2, 110); h.setProperty(3, 200);   // lit + ~55% cooked
    }

    private static void openBrewing(MinecraftClient c) {
        net.minecraft.screen.BrewingStandScreenHandler h = new net.minecraft.screen.BrewingStandScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.BrewingStandScreen(h, c.player.getInventory(), Text.literal("Brewing Stand")));
        h.getSlot(0).setStack(new ItemStack(Items.GLASS_BOTTLE));
        h.getSlot(3).setStack(new ItemStack(Items.NETHER_WART));
        h.getSlot(4).setStack(new ItemStack(Items.BLAZE_POWDER, 4));
        h.setProperty(0, 200); h.setProperty(1, 12);   // brewing half-way, fuel 12/20
    }

    private static void openEnchanting(MinecraftClient c) {
        net.minecraft.screen.EnchantmentScreenHandler h = new net.minecraft.screen.EnchantmentScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.EnchantmentScreen(h, c.player.getInventory(), Text.literal("Enchant")));
        h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
        h.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 3));
        h.setProperty(0, 5); h.setProperty(1, 15); h.setProperty(2, 30); h.setProperty(3, 12345);
        h.setProperty(4, -1); h.setProperty(5, -1); h.setProperty(6, -1);
    }

    private static void openChest(MinecraftClient c) {
        net.minecraft.inventory.SimpleInventory inv = new net.minecraft.inventory.SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, new ItemStack(Items.STONE, 1 + i));
        net.minecraft.screen.GenericContainerScreenHandler h =
                net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(1, c.player.getInventory(), inv);
        open(c, new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(h, c.player.getInventory(), Text.literal("Chest")));
    }

    // ============================================================================================================
    //  creative tab helpers
    // ============================================================================================================

    private static int cellOf(ItemGroup g, int pw) {
        int col = g.getColumn();
        int vx = g.isSpecial() ? (pw - 27 * (7 - col) + 1) : (27 * col);
        return Math.max(0, Math.min(6, Math.round(vx / 27f)));
    }

    private static ItemGroup groupAt(boolean top, int cell) {
        try {
            for (ItemGroup g : ItemGroups.getGroupsToDisplay()) {
                if ((g.getRow() == ItemGroup.Row.TOP) == top && cellOf(g, 195) == cell) return g;
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
        HandledScreen<?> s = hs(c);
        if (s == null) return null;
        int px = acc(s).s1mp1e$x(), py = acc(s).s1mp1e$y(), pw = acc(s).s1mp1e$backgroundWidth(), ph = acc(s).s1mp1e$backgroundHeight();
        double cw = pw / 7.0;
        return new double[]{px + (cell + 0.5) * cw, top ? py - 14 : py + ph + 14};
    }

    private static void tabClick(MinecraftClient c, boolean top, int cell) {
        HandledScreen<?> s = hs(c);
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
            sel = (ItemGroup) f.get(null);
        } catch (Throwable t) { skip("read selected tab", t); }
        boolean ok = sel == want;
        if (ok) pass++; else fail++;
        say("[CLICKS] tab " + (top ? "top" : "bottom") + " cell " + cell + " at " + fmt((float) p[0]) + "," + fmt((float) p[1])
                + " want=" + want.getDisplayName().getString() + " selected=" + (sel == null ? "null" : sel.getDisplayName().getString())
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
            try { server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), command); }
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
            if (e instanceof net.minecraft.entity.passive.PigEntity p && p.isAlive()) {
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
                if (e instanceof net.minecraft.entity.passive.PigEntity p) {
                    sb.append(" #").append(p.getId()).append(" hp=").append(fmt(p.getHealth())).append("/")
                      .append(fmt(p.getMaxHealth())).append(" d=").append(fmt((float) Math.sqrt(p.squaredDistanceTo(c.player))))
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
        say("combat attack " + (pig != null ? "pig#" + pig.getId() : "NO PIG") + " cooldown="
                + (c.player != null ? fmt(c.player.getAttackCooldownProgress(0f)) : "?"));
    }

    private static void buildCombat() {
        add(action(c -> {
            close(c); hx = hy = -1;
            gamemode(c, GameMode.SURVIVAL);
            cmd(c, "effect give @a minecraft:fire_resistance 600 0 true");
            cmd(c, "kill @e[type=minecraft:pig]");
            if (c.player != null) { c.player.getInventory().selectedSlot = 0; c.player.setPitch(0f); }
        }));
        add(waitMs(700));
        // LowFire: burning, module on vs off
        add(action(c -> { final ServerPlayerEntity p = sp(c); if (p != null) c.getServer().execute(() -> p.setFireTicks(600)); }));
        add(shot("combat-fire-on", c -> combatModule("LowFire", true), 700));
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
        // ring stays full while aiming at a living target (sword: cooldown period 12.5 ticks > 5)
        add(action(c -> cmd(c, "execute as @p at @p rotated ~ 0 run summon minecraft:pig ^ ^ ^2.2 {NoAI:1b,Silent:1b,Health:100f,"
                + "attributes:[{id:\"minecraft:generic.max_health\",base:100d}]}")));
        add(shot("combat-ring-ready", c -> { if (c.player != null) c.player.setPitch(28f); }, 1000,
                c -> { if (c.player != null) c.player.setPitch(28f); }));
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
            if (!combatAttacked && c.player != null && fr > 3 && c.player.getVelocity().y < -0.08 && !c.player.isOnGround()) {
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
            if (fr == 1) { if (c.player != null) c.player.setPitch(28f); combatHit(c); return false; }
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
                    + "attributes:[{id:\"minecraft:generic.max_health\",base:100d}]}");
        }));
        add(shot("combat-ring-ready-wrap", c -> { if (c.player != null) c.player.setPitch(28f); }, 1100,
                c -> { if (c.player != null) c.player.setPitch(28f); }));
        add(action(c -> {
            dev.s1mp1e.client.module.AttackRingModule r =
                    (dev.s1mp1e.client.module.AttackRingModule) dev.s1mp1e.client.ModuleManager.byName("AttackRing");
            if (r != null) r.readyShape.modeValue = "Same";
            cmd(c, "kill @e[type=minecraft:pig]");
            if (c.player != null) c.player.setPitch(0f);
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
        l.add(new StatusEffectInstance(StatusEffects.NIGHT_VISION, -1, 0, false, false, true));
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
            c.getServer().execute(() -> { for (int i = 9; i < 36; i++) p.getInventory().setStack(i, stacks.get(i - 9).copy()); });
        }
        try { for (int i = 9; i < 36; i++) c.player.getInventory().setStack(i, stacks.get(i - 9).copy()); }
        catch (Throwable t) { skip("fill inventory (client)", t); }
    }

    private static void namedItem(MinecraftClient c, int slot, String name) {
        ItemStack st = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 7);
        st.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        final ServerPlayerEntity p = sp(c);
        if (p != null && c.getServer() != null) { final ItemStack sc = st.copy(); c.getServer().execute(() -> p.getInventory().setStack(slot, sc)); }
        try { c.player.getInventory().setStack(slot, st.copy()); } catch (Throwable ignored) {}
    }

    private static void namedHelmet(MinecraftClient c, String name) {
        ItemStack st = new ItemStack(Items.IRON_HELMET);
        st.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        final ServerPlayerEntity p = sp(c);
        if (p != null && c.getServer() != null) {
            final ItemStack sc = st.copy();
            c.getServer().execute(() -> p.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, sc));
        }
        try { c.player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, st.copy()); } catch (Throwable ignored) {}
    }

    private static void emptyHand(MinecraftClient c) {
        try {
            c.player.getInventory().selectedSlot = 5;   // an empty hotbar slot: no held item across the HUD shots
            c.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(5));
        } catch (Throwable t) { skip("empty hand", t); }
    }

    private static void lookDown(MinecraftClient c, float pitch) {
        try {
            ClientPlayerEntity cp = c.player;
            cp.setYaw(0f); cp.prevYaw = 0f; cp.headYaw = 0f;
            cp.setPitch(pitch); cp.prevPitch = pitch;
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
            net.minecraft.client.gui.hud.PlayerListHud tab = c.inGameHud.getPlayerListHud();
            tab.setHeader(Text.literal("S1mp1e — 玻璃玩家列表\nheader / list / footer are refracting glass"));
            tab.setFooter(Text.literal("grey readability scrim under the names\nping bars / names stay on top"));
            // three extra listed players so the LIST panel is a tall plate (single-player lists only you)
            Field lf = net.minecraft.client.network.ClientPlayNetworkHandler.class.getDeclaredField("listedPlayerListEntries");
            lf.setAccessible(true);
            java.util.Set<net.minecraft.client.network.PlayerListEntry> listed =
                    (java.util.Set<net.minecraft.client.network.PlayerListEntry>) lf.get(c.getNetworkHandler());
            if (fakeEntries.isEmpty()) {
                for (String n : new String[]{"Alex", "Steve_2", "Glassmith"}) {
                    fakeEntries.add(new net.minecraft.client.network.PlayerListEntry(
                            new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(n.getBytes()), n), false));
                }
            }
            listed.addAll(fakeEntries);
            c.options.playerListKey.setPressed(true);
        } catch (Throwable t) { skip("tablist show", t); }
    }

    @SuppressWarnings("unchecked")
    private static void tablistHide(MinecraftClient c) {
        try { c.options.playerListKey.setPressed(false); } catch (Throwable ignored) {}
        try {
            Field lf = net.minecraft.client.network.ClientPlayNetworkHandler.class.getDeclaredField("listedPlayerListEntries");
            lf.setAccessible(true);
            ((java.util.Set<net.minecraft.client.network.PlayerListEntry>) lf.get(c.getNetworkHandler())).removeAll(fakeEntries);
        } catch (Throwable ignored) {}
    }

    // ---- hover helpers ---------------------------------------------------------------------------------------------

    private static void hoverSlot(MinecraftClient c, int index) {
        HandledScreen<?> s = hs(c);
        if (s == null) return;
        ScreenHandler h = s.getScreenHandler();
        if (index < 0 || index >= h.slots.size()) return;
        net.minecraft.screen.slot.Slot slot = h.slots.get(index);
        hx = acc(s).s1mp1e$x() + slot.x + 8;
        hy = acc(s).s1mp1e$y() + slot.y + 8;
    }

    /** Compact effect column: icon boxes 32x32 at x+backgroundWidth+2, spacing 33 from the panel top. */
    private static void hoverCompactEffect(MinecraftClient c, int i) {
        HandledScreen<?> s = hs(c);
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
            c.setScreen(sc);
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

    private static HandledScreen<?> hs(MinecraftClient c) {
        return c.currentScreen instanceof HandledScreen<?> h ? h : null;
    }

    private static HandledScreenAccessor acc(HandledScreen<?> s) { return (HandledScreenAccessor) (Object) s; }

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
        try { c.setScreen(s); } catch (Throwable t) { skip("open " + (s == null ? "null" : s.getClass().getSimpleName()), t); }
    }

    private static void close(MinecraftClient c) { try { c.setScreen(null); } catch (Throwable ignored) {} }

    private static String fmt(float v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    private static void say(String s) { System.out.println("[S1mp1e][VERIFY] " + s); }

    private static void skip(String what, Throwable t) {
        System.out.println("[S1mp1e][VERIFY] skipped " + what + ": " + t);
    }

    private static void capture(MinecraftClient c, String name) {
        NativeImage img = null;
        try {
            img = ScreenshotRecorder.takeScreenshot(c.getFramebuffer());
            File f = new File(out, name);
            img.writeTo(f);
        } catch (Throwable t) {
            skip("capture " + name, t);
        } finally {
            if (img != null) try { img.close(); } catch (Throwable ignored) {}
        }
    }
}
