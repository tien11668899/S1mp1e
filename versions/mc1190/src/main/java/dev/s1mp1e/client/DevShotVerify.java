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
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;
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
 * DevShot VERIFY sweeps (1.19.2, ported from the 1.20.1 line, itself from 1.21.1) — the acceptance-checklist scenes of the port spec §5, driven one frame at a time from
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
 *
 * <p>1.19.2: this line's own {@link DevShot} already has single-mode sweeps called {@code screens / tabs / scroll /
 * hud / modules / combat} (kept as the regression baseline), so the sweeps of the same name here are reached with a
 * {@code v:} prefix on the mode list ({@code S1MP1E_SHOT_MODE=v:screens,lists}); the modes that only exist here
 * ({@code settings, sodium, trans, gap, newmenu, newanim, lists, tooltips, effects, flicker, vcombat}) need no prefix.
 * 1.19.2 API notes: creative tabs are the static {@code ItemGroup.GROUPS} array, there is no tabbed create-world
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
            if (t.equals("lists") || t.equals("vcombat") || t.equals("newmenu") || t.equals("newanim") || t.equals("sodium")
                    || t.equals("settings") || t.equals("trans") || t.equals("gap") || t.equals("tooltips")
                    || t.equals("effects") || t.equals("flicker")) return true;
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
            case "vcombat":  buildCombat();   break;
            case "newmenu":  buildNewMenu();  break;
            case "newanim":  buildNewAnim();  break;
            case "sodium":   buildSodium();   break;
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
        add(shot("scr-social", c -> open(c, new net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen()), 800));
        add(shot("scr-book", c -> open(c, new net.minecraft.client.gui.screen.ingame.BookScreen(
                new net.minecraft.client.gui.screen.ingame.BookScreen.Contents() {
                    public int getPageCount() { return 1; }
                    public net.minecraft.text.StringVisitable getPageUnchecked(int i) {
                        return net.minecraft.text.StringVisitable.plain(
                                "液態玻璃書頁\n深色墨水必須清晰可讀。\n\nDark ink stays readable on the light warm parchment scrim over the glass page.");
                    }
                })), 700));
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
        add(action(c -> { cmd(c, "advancement grant @a only minecraft:story/mine_stone"); cmd(c, "recipe give @a minecraft:diamond_sword");
            // a tutorial toast too: its title / description were dark on the glass card (ToastTextMixin)
            c.getToastManager().add(new net.minecraft.client.toast.TutorialToast(
                    net.minecraft.client.toast.TutorialToast.Type.RECIPE_BOOK,
                    net.minecraft.text.Text.literal("Tutorial title"), net.minecraft.text.Text.literal("description line"), false)); }));
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
            for (int i = 0; i < steps; i++) s.mouseScrolled(p[0], p[1], -1.0);
        }

        void toTop(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            double[] p = listCenter(s);
            for (int i = 0; i < 400; i++) s.mouseScrolled(p[0], p[1], 1.0);
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
                        verdict(ok, label, "slot " + idx + " expected=" + Registry.ITEM.getId(expected) + " got="
                                + Registry.ITEM.getId(got.getItem()) + (expectSnap ? " snapped=" + snapped : ""));
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
                                ? String.valueOf(Registry.ITEM.getId(h.getAvailableRecipes().get(expected)
                                        .getOutput().getItem())) : "-";
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
                        String item = expected < offers.size() ? String.valueOf(Registry.ITEM.getId(offers.get(expected).getSellItem().getItem())) : "-";
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
        open(c, new CreativeInventoryScreen(c.player));
        selectTab(c, ItemGroup.SEARCH);
    }

    private static void openStonecutter(MinecraftClient c) {
        net.minecraft.screen.StonecutterScreenHandler h =
                new net.minecraft.screen.StonecutterScreenHandler(1, c.player.getInventory());
        // screen FIRST (its constructor registers the contents listener -> canCraft), then the input
        open(c, new net.minecraft.client.gui.screen.ingame.StonecutterScreen(h, c.player.getInventory(), Text.literal("Stonecutter")));
        h.getSlot(0).setStack(new ItemStack(Items.COBBLED_DEEPSLATE, 64));
        h.onContentChanged(h.getSlot(0).inventory);
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
                    new ItemStack(Items.EMERALD, 1 + i),
                    i % 3 == 1 ? new ItemStack(Items.BOOK, 1) : ItemStack.EMPTY,
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

    private static void openHorse(MinecraftClient c) {
        net.minecraft.entity.passive.HorseEntity horse =
                new net.minecraft.entity.passive.HorseEntity(net.minecraft.entity.EntityType.HORSE, c.world);
        net.minecraft.inventory.SimpleInventory inv = new net.minecraft.inventory.SimpleInventory(2);
        net.minecraft.screen.HorseScreenHandler h =
                new net.minecraft.screen.HorseScreenHandler(1, c.player.getInventory(), inv, horse);
        open(c, new net.minecraft.client.gui.screen.ingame.HorseScreen(h, c.player.getInventory(), horse));
    }

    private static void openBookEdit(MinecraftClient c) {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        try {   // 1.20.1: book pages are NBT strings
            net.minecraft.nbt.NbtList pages = new net.minecraft.nbt.NbtList();
            pages.add(net.minecraft.nbt.NbtString.of("液態玻璃書寫頁\nDark ink stays readable while you type on the glass page."));
            book.getOrCreateNbt().put("pages", pages);
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
        try {   // 1.20.1: a written book is title / author / JSON-text pages in NBT
            net.minecraft.nbt.NbtCompound tag = book.getOrCreateNbt();
            tag.putString("title", "S1mp1e");
            tag.putString("author", "DevShot");
            net.minecraft.nbt.NbtList pages = new net.minecraft.nbt.NbtList();
            pages.add(net.minecraft.nbt.NbtString.of(Text.Serializer.toJson(Text.literal(
                    "講台上的玻璃書頁\nLectern page: glass + light parchment scrim."))));
            tag.put("pages", pages);
            tag.putBoolean("resolved", true);
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
        h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_CHESTPLATE));   // 1.19.2: no smithing template yet
        h.getSlot(1).setStack(new ItemStack(Items.NETHERITE_INGOT));
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
        // 1.19.2 (CreativeInventoryScreen.isClickInTab): 28 px tabs at 28*col + col, special tabs right-aligned
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
            Object v = f.get(null);                       // 1.19.2: a static int index into ItemGroup.GROUPS
            sel = v instanceof ItemGroup ? (ItemGroup) v : ItemGroup.GROUPS[((Number) v).intValue()];
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
                    + "Attributes:[{Name:\"minecraft:generic.max_health\",Base:100d}]}");
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
        trans("tr0-game-pause", c -> c.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true)));
        trans("tr1-pause-options", c -> c.setScreen(
                new net.minecraft.client.gui.screen.option.OptionsScreen(c.currentScreen, c.options)));
        trans("tr2-options-video", c -> c.setScreen(
                new net.minecraft.client.gui.screen.option.VideoOptionsScreen(c.currentScreen, c.options)));
        trans("tr3-video-back", c -> { if (c.currentScreen != null) c.currentScreen.close(); });
        trans("tr4-options-game", c -> c.setScreen(null));
        trans("tr5-game-config", c -> c.setScreen(new S1mp1eConfigScreen()));
        trans("tr6-config-game", c -> { if (c.currentScreen != null) c.currentScreen.close(); });
        // in-screen content switches (onTabSwitch): creative category, advancement tab
        add(action(DevShotVerify::openCreativeSearch));
        trans("tr7-creative-tab", c -> selectTab(c, ItemGroup.BUILDING_BLOCKS));
        add(action(c -> { close(c); gamemode(c, GameMode.SURVIVAL); cmd(c, "advancement grant @a everything"); }));
        add(waitMs(900));
        add(action(c -> { clearToasts(c); clearChat(c);
            open(c, new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(c.player.networkHandler.getAdvancementHandler())); }));
        trans("tr8-advancement-tab", DevShotVerify::nextAdvancementTab);
        add(action(c -> close(c)));
        // create-world: 1.19.2 has no tabs (TabManager is 1.19.4+); its one in-screen content switch is the
        // "More World Options" toggle. The screen loads its data packs first.
        add(action(c -> { try { net.minecraft.client.gui.screen.world.CreateWorldScreen.create(c, null); }
                          catch (Throwable t) { skip("create world screen", t); } }));
        add(waitMs(5000));
        trans("tr9-createworld-more", DevShotVerify::createWorldMore);
        trans("tr10-createworld-back", DevShotVerify::createWorldMore);
        add(action(c -> close(c)));
    }

    /** Select a different advancement root tab than the current one (drives AdvancementsScreen.selectTab). */
    private static void nextAdvancementTab(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.advancement.AdvancementsScreen s)) return;
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

    /** Press the open CreateWorldScreen's "More World Options…" / "Done" toggle (1.19.2: private toggleMoreOptions). */
    private static void createWorldMore(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen s)) {
                say("no CreateWorldScreen: " + (c.currentScreen == null ? "null" : c.currentScreen.getClass().getSimpleName()));
                return;
            }
            Method m = net.minecraft.client.gui.screen.world.CreateWorldScreen.class.getDeclaredMethod("toggleMoreOptions");
            m.setAccessible(true);
            m.invoke(s);
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
        add(action(c -> { try { net.minecraft.client.gui.screen.world.CreateWorldScreen.create(c, null); }
                          catch (Throwable t) { skip("create world screen", t); } }));
        add(waitMs(5000));
        add(still("gp-cycle-pre"));
        add(burst("gp-cycle", 9, DevShotVerify::clickCycleButton, null));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (2) waiting screen (TaskScreen) glass card: dots -> liquid loader, then the result variant with a description
        add(shot("gp-task-waiting", c -> open(c, net.minecraft.client.gui.screen.TaskScreen.createRunningScreen(
                Text.literal("正在準備世界"), Text.literal("取消"), () -> { })), 900));
        add(shot("gp-task-result", c -> open(c, net.minecraft.client.gui.screen.TaskScreen.createResultScreen(
                Text.literal("備份完成"), Text.literal("世界已備份到 backups 資料夾，共 128 MB。"), Text.literal("完成"), () -> { })), 900));
        add(action(c -> close(c)));
        add(waitMs(400));

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
        // 1.19.2: the saves are usually listed by the list's first frame (nothing arrives "later"), so the cascade is
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
                net.minecraft.client.option.ServerList sl = new net.minecraft.client.option.ServerList(c);
                sl.loadFile();
                if (sl.size() == 0) {
                    sl.add(new net.minecraft.client.network.ServerInfo("S1mp1e 測試伺服器", "127.0.0.1:1", false), false);
                    sl.saveFile();
                }
            } catch (Throwable t) { skip("server list", t); }
        }));
        add(burst("gp-serverlist", 40, c -> open(c, new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(
                new net.minecraft.client.gui.screen.TitleScreen())), c -> "t"));
        add(shot("gp-serverlist-hover", null, 600, c -> { hx = c.getWindow().getScaledWidth() / 2.0 - 120; hy = 52; }));
        add(action(c -> { close(c); hx = hy = -1; }));
        add(waitMs(400));

        // (7) connect screen card (constructed, never connects) — previously unverified
        add(shot("gp-connect", c -> {
            try {
                java.lang.reflect.Constructor<net.minecraft.client.gui.screen.ConnectScreen> k =
                        net.minecraft.client.gui.screen.ConnectScreen.class.getDeclaredConstructor(Screen.class);
                k.setAccessible(true);   // 1.19.2: the private constructor only takes the parent
                open(c, k.newInstance(new net.minecraft.client.gui.screen.TitleScreen()));
            } catch (Throwable t) { skip("connect screen", t); }
        }, 900));
        add(action(c -> close(c)));
        add(waitMs(400));

        // (8) sign typing — previously unverified
        add(action(c -> {
            try {
                net.minecraft.block.entity.SignBlockEntity be = new net.minecraft.block.entity.SignBlockEntity(
                        c.player.getBlockPos(), net.minecraft.block.Blocks.OAK_SIGN.getDefaultState());
                be.setWorld(c.world);
                open(c, new net.minecraft.client.gui.screen.ingame.SignEditScreen(be, false));
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
            if (c.currentScreen instanceof HandledScreen<?> s) c.player.currentScreenHandler = s.getScreenHandler(); }));
        add(waitMs(600));
        add(still("gp-flight-pre"));
        add(burst("gp-flight", 10, DevShotVerify::quickMoveFirstSlot, null));
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
        add(burst("gp-tablist-out", 10, c -> c.options.playerListKey.setPressed(false), null));
        add(waitMs(400));
        add(action(DevShotVerify::tablistHide));
        add(waitMs(200));
    }

    /** Press the recipe-book button of the open inventory (toggles the book either way). */
    private static void toggleRecipeBook(MinecraftClient c) {
        try {
            if (!(c.currentScreen instanceof InventoryScreen s)) return;
            for (net.minecraft.client.gui.Element e : s.children()) {
                if (e instanceof net.minecraft.client.gui.widget.TexturedButtonWidget b) { b.onPress(); break; }
            }
        } catch (Throwable t) { skip("toggle recipe book", t); }
    }

    /** A real left click on the first cycle button of the open screen (drives PressPulse + the value roll). */
    private static void clickCycleButton(MinecraftClient c) {
        try {
            if (c.currentScreen == null) return;
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.CyclingButtonWidget<?> b) {
                    double mx = b.x + b.getWidth() / 2.0, my = b.y + b.getHeight() / 2.0;
                    c.currentScreen.mouseClicked(mx, my, 0);
                    c.currentScreen.mouseReleased(mx, my, 0);
                    say("cycle click " + b.getMessage().getString());
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
            if (!(c.currentScreen instanceof HandledScreen<?> s)) return;
            Method m = HandledScreen.class.getDeclaredMethod("onMouseClick", net.minecraft.screen.slot.Slot.class,
                    int.class, int.class, net.minecraft.screen.slot.SlotActionType.class);
            m.setAccessible(true);
            net.minecraft.screen.slot.Slot slot = s.getScreenHandler().getSlot(13);
            m.invoke(s, slot, slot.id, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE);
            say("quick move slot 13");
        } catch (Throwable t) { skip("quick move", t); }
    }

    /**
     * Every atlas region SfIcons maps in 1.20.1 (there are no GUI sprites yet: each icon is a region of a
     * {@code textures/gui/...png} atlas): top row drawn as vanilla (devBypass), bottom row replaced; plus two real
     * checkboxes. Each item = {texture, u, v, w, h, texW, texH}.
     */
    private static Screen sfGallery() {
        final String[][] items = dev.s1mp1e.glass.render.SfIcons.devGallery();
        return new Screen(Text.literal("SF Symbols")) {
            @Override protected void init() {
                addDrawableChild(new net.minecraft.client.gui.widget.CheckboxWidget(60, 150, 20, 20, Text.literal("未勾選"), false));
                addDrawableChild(new net.minecraft.client.gui.widget.CheckboxWidget(160, 150, 20, 20, Text.literal("已勾選"), true));
            }
            @Override public void render(net.minecraft.client.util.math.MatrixStack ctx, int mx, int my, float delta) {
                this.renderBackground(ctx);
                super.render(ctx, mx, my, delta);
                for (int row = 0; row < 2; row++) {
                    dev.s1mp1e.glass.render.SfIcons.devBypass = row == 0;
                    int x = 20, y = 40 + row * 50;
                    for (String[] it : items) {
                        int u = Integer.parseInt(it[1]), v = Integer.parseInt(it[2]);
                        int w = Integer.parseInt(it[3]), h = Integer.parseInt(it[4]);
                        int tw = Integer.parseInt(it[5]), th = Integer.parseInt(it[6]);
                        try {   // 1.19.2: bind, then blit (SfIconMixin matches the BOUND atlas)
                            com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexShader);
                            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, new net.minecraft.util.Identifier(it[0]));
                            net.minecraft.client.gui.DrawableHelper.drawTexture(ctx, x, y, (float) u, (float) v, w, h, tw, th);
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
            net.minecraft.client.gui.screen.ProgressScreen ps = new net.minecraft.client.gui.screen.ProgressScreen(false);
            open(c, ps);
            ps.setTitle(Text.literal("正在儲存世界"));
            ps.setTask(Text.literal("寫入區塊 12,480 / 27,700"));
            ps.progressStagePercentage(45);
        }, 900));
        // (B) ProgressScreen — indeterminate sweep: title only, no percent line -> LiquidLoader sweeps
        add(action(c -> {
            net.minecraft.client.gui.screen.ProgressScreen ps = new net.minecraft.client.gui.screen.ProgressScreen(false);
            open(c, ps);
            ps.setTitle(Text.literal("正在準備資源"));
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
        add(action(c -> open(c, new net.minecraft.client.gui.screen.option.KeybindsScreen(new net.minecraft.client.gui.screen.TitleScreen(), c.options))));
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
                if (e instanceof net.minecraft.client.gui.widget.TextFieldWidget tf) { tf.setText(text); say("world search '" + text + "'"); return; }
            }
            say("no search box on " + c.currentScreen.getClass().getSimpleName());
        } catch (Throwable t) { skip("world search", t); }
    }

    /** Scroll amount of the first entry list of the open screen (the eased wheel scroll must move it every frame). */
    private static String listProbe(MinecraftClient c) {
        try {
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.EntryListWidget<?> l) {
                    return "scroll=" + fmt(l.getScrollAmount()) + " max=" + l.getMaxScroll();
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
        add(burst("na-tablist-out", 8, c -> c.options.playerListKey.setPressed(false), null));
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
        add(burst("na-health-trail", 10, c -> {   // 1.19.2 has no /damage command
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

    // ============================================================================================================
    //  sodium (Package F): liquid-glass restyle of Sodium's own Video Settings screen (only when the Sodium mod loads)
    // ============================================================================================================

    private static void buildSodium() {
        // In-world: the glass cards refract the blurred world. Geometry = SodiumGlass's grid (M 16, TOP 19, rows 18).
        add(action(c -> { gamemode(c, GameMode.CREATIVE); lookDown(c, 18f); }));
        add(waitMs(500));
        add(shot("sd-open", DevShotVerify::openSodiumOptions, 1400));
        // hover the first row (a slider): the slider slides out, the value moves aside, then the description card
        add(burst("sd-slider-in", 8, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 47 + 9; }, null));
        add(shot("sd-hover-slider", null, 1100));
        // a boolean row + its description
        add(shot("sd-hover-bool", null, 1100, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 47 + 54 + 8 + 18 + 9; }));
        // flip a switch (group 3, row 1): the knob travels, the label goes italic, Undo appears, Done dims
        add(burst("sd-toggle", 10, c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, 47 + 54 + 8 + 36 + 9), null));
        add(shot("sd-changed", null, 700, c -> { hx = 30; hy = 300; }));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() - 16 - 3 * 65 - 16 + 30, c.getWindow().getScaledHeight() - 29)));
        add(shot("sd-undone", null, 600));
        // the next page: the capsule slides, the rows cascade in
        add(burst("sd-page", 12, c -> sodiumClick(c, 60, 47 + 20 + 18 + 9), null));
        add(shot("sd-page2", null, 900));
        add(shot("sd-page3", c -> sodiumClick(c, 60, 47 + 20 + 36 + 9), 900));
        add(shot("sd-page4", c -> sodiumClick(c, 60, 47 + 20 + 54 + 9), 900));
        // a short window: the page no longer fits and scrolls
        add(action(c -> { DevShot.setTarget(854, 480); }));
        add(waitMs(600));
        add(shot("sd-small", c -> sodiumClick(c, 60, 47 + 20 + 18 + 9), 900));
        add(shot("sd-small-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 100;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -3); }, 900));
        add(action(c -> { close(c); hx = hy = -1; DevShot.setTarget(1280, 720); }));
        add(waitMs(600));
    }

    // ---- settings shell: every vanilla settings page in the Video Settings layout ---------------------------------

    /** Click the {@code i}-th sidebar entry (0 = General) of the settings shell: M 16 + pad 4, rows of 18 from 47 + 4. */
    private static void shellTab(MinecraftClient c, int i) { sodiumClick(c, 60, 47 + 4 + i * 18 + 9); }

    private static double shellRowY(int i) { return 47 + i * 18 + 9; }

    private static void buildSettings() {
        add(action(c -> { gamemode(c, GameMode.CREATIVE); lookDown(c, 18f); }));
        add(waitMs(500));
        add(shot("st-main", c -> open(c, new net.minecraft.client.gui.screen.option.OptionsScreen(
                new net.minecraft.client.gui.screen.GameMenuScreen(true), c.options)), 1300));
        add(burst("st-main-slider", 8, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = shellRowY(0); }, null));
        add(shot("st-main-hover", null, 900));
        add(burst("st-to-sound", 10, c -> shellTab(c, 2), null));
        add(shot("st-sound", null, 900, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = shellRowY(0); }));
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            // vanilla Video Settings (Sodium replaces that page with its own screen, see the sodium mode)
            add(shot("st-video", c -> shellTab(c, 3), 900));
            add(shot("st-video-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
                if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -6); }, 900));
        } else {
            add(shot("st-video-sodium", c -> shellTab(c, 3), 1200));
            add(action(c -> { if (c.currentScreen != null) c.currentScreen.close(); }));
            add(waitMs(700));
        }
        add(shot("st-skin", c -> shellTab(c, 1), 900));
        add(burst("st-skin-toggle", 10, c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0)), null));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0))));
        add(shot("st-controls", c -> shellTab(c, 4), 900));
        add(shot("st-mouse", c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0)), 900));
        add(action(c -> shellTab(c, 0)));
        add(waitMs(500));
        add(action(c -> shellTab(c, 4)));
        add(waitMs(500));
        add(shot("st-keys", c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(1)), 1000));
        // key capture stays vanilla: click the first binding's edit button ("> key <"), press R (rebinds, red = a
        // conflict with nothing here), then its Reset button puts the default back
        add(shot("st-keys-capture", c -> keyRowClick(c, 0, false), 500));
        add(shot("st-keys-bound", c -> { if (c.currentScreen != null) c.currentScreen.keyPressed(82, 0, 0); }, 500));
        add(shot("st-keys-reset", c -> keyRowClick(c, 0, true), 500));
        // a REAL drag on a slider row (Mouse Settings > Sensitivity): press on the track, drag left, release
        add(action(c -> { shellTab(c, 0); }));
        add(waitMs(400));
        add(action(c -> shellTab(c, 4)));
        add(waitMs(500));
        add(action(c -> sodiumClick(c, c.getWindow().getScaledWidth() * 0.6, shellRowY(0))));
        add(waitMs(600));
        add(action(c -> { hx = c.getWindow().getScaledWidth() - 16 - 50; hy = shellRowY(0); }));
        add(waitMs(500));
        add(action(c -> { Screen s = c.currentScreen; if (s != null) s.mouseClicked(hx, hy, 0); }));
        add(burst("st-slider-drag", 8, c -> { Screen s = c.currentScreen;
            if (s != null) { hx -= 30; s.mouseDragged(hx, hy, 0, -30, 0); } }, c -> sliderProbe(c)));
        add(action(c -> { Screen s = c.currentScreen; if (s != null) { s.mouseDragged(hx + 30, hy, 0, 30, 0); s.mouseReleased(hx + 30, hy, 0); } }));
        // the drag is in pixels, so it does not land on the starting value: put the default back exactly
        add(action(c -> { c.options.getMouseSensitivity().setValue(0.5); c.options.write(); }));
        add(waitMs(300));
        add(shot("st-language", c -> shellTab(c, 5), 1000));
        add(shot("st-chat", c -> shellTab(c, 6), 900));
        add(shot("st-access", c -> shellTab(c, 8), 900));
        add(shot("st-access-scrolled", c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = 150;
            if (c.currentScreen != null) c.currentScreen.mouseScrolled(hx, hy, -4); }, 1100));
        // over the right end of the row: a slider row's widget is only its track, and the tooltip belongs to the widget
        // 1.19.2: only some options carry a tooltip. Row 10 = Distortion Effects (a slider: its widget is only the
        // track at the right end), row 13 = Hide Lightning Flashes (a switch: the whole row).
        add(shot("st-access-tooltip", null, 1600, c -> { hx = c.getWindow().getScaledWidth() - 16 - 50; hy = shellRowY(10); }));
        add(shot("st-access-tooltip2", null, 1600, c -> { hx = c.getWindow().getScaledWidth() * 0.6; hy = shellRowY(13); }));
        add(shot("st-online", c -> open(c, new net.minecraft.client.gui.screen.option.OnlineOptionsScreen(
                new net.minecraft.client.gui.screen.option.OptionsScreen(
                        new net.minecraft.client.gui.screen.GameMenuScreen(true), c.options), c.options)), 1200));
        add(action(c -> open(c, new net.minecraft.client.gui.screen.option.OptionsScreen(
                new net.minecraft.client.gui.screen.GameMenuScreen(true), c.options))));
        add(waitMs(700));
        add(shot("st-general-back", c -> shellTab(c, 0), 900));
        add(action(c -> { DevShot.setTarget(854, 480); }));
        add(waitMs(600));
        add(shot("st-small", c -> shellTab(c, 6), 900));
        add(action(c -> { close(c); hx = hy = -1; DevShot.setTarget(1280, 720); }));
        add(waitMs(600));
    }

    /** Click the edit (or reset) button of the {@code i}-th key-binding row of the open Key Binds page. */
    private static void keyRowClick(MinecraftClient c, int i, boolean reset) {
        try {
            Screen s = c.currentScreen;
            if (s == null) return;
            int n = 0;
            net.minecraft.client.gui.widget.ButtonWidget edit = null;
            for (net.minecraft.client.gui.Element e : s.children()) {
                if (!(e instanceof net.minecraft.client.gui.widget.ButtonWidget b)) continue;
                if (b.getWidth() == 84) { if (n++ == i) edit = b; continue; }      // the shell's edit-button width
                if (edit != null && reset) { edit = b; break; }                     // its reset button follows it
            }
            if (edit == null) { say("key row " + i + " not found"); return; }
            double mx = edit.x + edit.getWidth() / 2.0, my = edit.y + edit.getHeight() / 2.0;
            hx = mx; hy = my;
            s.mouseClicked(mx, my, 0);
            s.mouseReleased(mx, my, 0);
            say("key row " + i + (reset ? " reset" : " edit") + " -> " + edit.getMessage().getString());
        } catch (Throwable t) { skip("key row click", t); }
    }

    /** The first slider of the open screen: its 0..1 value and message (the drag must move it with the pointer). */
    private static String sliderProbe(MinecraftClient c) {
        try {
            for (net.minecraft.client.gui.Element e : c.currentScreen.children()) {
                if (e instanceof net.minecraft.client.gui.widget.SliderWidget sw) {
                    return "slider " + sw.getMessage().getString() + " x=" + sw.x + " w=" + sw.getWidth() + " cursor=" + fmt(hx);
                }
            }
        } catch (Throwable ignored) {}
        return "no slider";
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

    /** Open Sodium's own options screen via its public factory ({@code SodiumOptionsGUI.createScreen(Screen)}), by
     *  reflection so there is no compile/runtime dependency on Sodium. No-op (skip) when Sodium is not present. */
    static void openSodiumOptions(MinecraftClient c) {
        try {
            Class<?> gui = Class.forName("me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI");   // Sodium 0.5
            Screen prev = new net.minecraft.client.gui.screen.TitleScreen();
            Screen s = null;
            for (Method m : gui.getMethods()) {
                if (m.getName().equals("createScreen") && m.getParameterCount() == 1) { s = (Screen) m.invoke(null, prev); break; }
            }
            if (s == null) {
                for (java.lang.reflect.Constructor<?> k : gui.getConstructors()) {
                    if (k.getParameterCount() == 1) { s = (Screen) k.newInstance(prev); break; }
                }
            }
            if (s != null) c.setScreen(s); else say("sodium options screen factory not found");
        } catch (Throwable t) { skip("open sodium options", t); }
    }

    /** Click a tab in the Sodium options tab row (top-left) to move to the next page. Best-effort; guarded. */
    private static void sodiumNextPage(MinecraftClient c) {
        try {
            Screen s = c.currentScreen;
            if (s == null) return;
            // Sodium's page tabs are FlatButtonWidget rows near the top-left; click a little below the first tab.
            s.mouseClicked(40, 55, 0);
            s.mouseReleased(40, 55, 0);
        } catch (Throwable t) { skip("sodium next page", t); }
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
            c.getServer().execute(() -> { for (int i = 9; i < 36; i++) p.getInventory().setStack(i, stacks.get(i - 9).copy()); });
        }
        try { for (int i = 9; i < 36; i++) c.player.getInventory().setStack(i, stacks.get(i - 9).copy()); }
        catch (Throwable t) { skip("fill inventory (client)", t); }
    }

    private static void namedItem(MinecraftClient c, int slot, String name) {
        ItemStack st = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 7);
        st.setCustomName(Text.literal(name));
        final ServerPlayerEntity p = sp(c);
        if (p != null && c.getServer() != null) { final ItemStack sc = st.copy(); c.getServer().execute(() -> p.getInventory().setStack(slot, sc)); }
        try { c.player.getInventory().setStack(slot, st.copy()); } catch (Throwable ignored) {}
    }

    private static void namedHelmet(MinecraftClient c, String name) {
        ItemStack st = new ItemStack(Items.IRON_HELMET);
        st.setCustomName(Text.literal(name));
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
            // three extra players so the LIST panel is a tall plate (single-player lists only you). 1.19.2: one
            // playerListEntries map; an entry is built from a player-list packet entry.
            Field lf = net.minecraft.client.network.ClientPlayNetworkHandler.class.getDeclaredField("playerListEntries");
            lf.setAccessible(true);
            java.util.Map<java.util.UUID, net.minecraft.client.network.PlayerListEntry> listed =
                    (java.util.Map<java.util.UUID, net.minecraft.client.network.PlayerListEntry>) lf.get(c.getNetworkHandler());
            if (fakeEntries.isEmpty()) {
                int ping = 40;
                for (String n : new String[]{"Alex", "Steve_2", "Glassmith"}) {
                    fakeEntries.add(new net.minecraft.client.network.PlayerListEntry(
                            new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Entry(
                                    new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(n.getBytes()), n),
                                    ping += 120, GameMode.SURVIVAL, null, null),
                            c.getServicesSignatureVerifier(), false));
                }
            }
            for (net.minecraft.client.network.PlayerListEntry e : fakeEntries) listed.put(e.getProfile().getId(), e);
            c.options.playerListKey.setPressed(true);
        } catch (Throwable t) { skip("tablist show", t); }
    }

    @SuppressWarnings("unchecked")
    private static void tablistHide(MinecraftClient c) {
        try { c.options.playerListKey.setPressed(false); } catch (Throwable ignored) {}
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

    /** The world-entry loop on DevShot's preview screen. */
    static void loopPreview(net.minecraft.client.util.math.MatrixStack matrices, float seconds) {
        dev.s1mp1e.client.gui.BrandIntro.draw(matrices, seconds, dev.s1mp1e.client.gui.BrandIntro.MODE_LOOP, 1.0F);
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
