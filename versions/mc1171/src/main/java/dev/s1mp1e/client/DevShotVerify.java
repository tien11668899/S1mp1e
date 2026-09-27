package dev.s1mp1e.client;

import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.TabsProbe;
import dev.s1mp1e.glass.mixin.HandledScreenAccessor;
import dev.s1mp1e.glass.render.GlassTabs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.LiteralText;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DevShot VERIFY sweeps for stage 2 (B fused tabs / C glass scrollbar / D silky glide) — 1.17.1. <b>Inert</b> unless
 * DevShot is active ({@code S1MP1E_SHOT}) with {@code S1MP1E_SHOT_MODE} = {@code tabs}, {@code lists}, {@code flicker}
 * or a comma list of them ({@code all} = every one). Driven one frame at a time from {@link DevShot} (render TAIL) after
 * the world has settled; returns control to DevShot's stop phase when the scene list is exhausted.
 *
 * <ul>
 *   <li>{@code tabs} — (B) creative: fused band at rest, a same-row selection SLIDE burst, a cross-row CROSS-FADE burst,
 *       the hover pill in / glide / out bursts (virtual cursor), tab-name tooltip, and programmatic cell clicks (incl. a
 *       point vanilla's own tab boxes miss) asserting the selected tab.</li>
 *   <li>{@code lists} — (C/D) creative (search tab) / stonecutter (cobbled deepslate, 16 recipes) / loom (banner + dye,
 *       all patterns) / merchant (20 client-side trade offers): rest, wheel-to-mid, a REAL thumb press + 1:1 drag burst
 *       (held lens), a drag far past the end (rubber band), release, a 10-frame glide burst after one wheel kick, and the
 *       automated click assertions at rest / mid-glide (snap) / edge cell — logged {@code [CLICKS] ... PASS|FAIL} and a
 *       final {@code N PASS / M FAIL}. Plus the R1 tooltip over the glass scrollbar.</li>
 *   <li>{@code flicker} — (R4) uncapped fps, vsync off: every 3rd frame of the creative sheet and the merchant panel at
 *       rest, so frame-to-frame deltas of the glass can be measured offline.</li>
 * </ul>
 * The virtual cursor is applied every frame by writing {@code Mouse.x/y} (GLFW cursor warps fail on the unfocused dev
 * window); clicks / drags / wheel steps are programmatic {@code mouseClicked}/{@code mouseDragged}/{@code mouseReleased}/
 * {@code mouseScrolled} calls at the drawn geometry. Every step is guarded; a failure skips that step only.
 */
final class DevShotVerify {

    private DevShotVerify() {}

    interface Scene { boolean step(MinecraftClient c, int frame, double ms); }

    private static final List<Scene> scenes = new ArrayList<Scene>();
    private static int idx, frame;
    private static long sceneStartNanos, lastFrameNanos;
    private static double lastFrameMs;
    private static double hx = -1, hy = -1;   // virtual cursor (GUI px); <0 = parked off-screen
    private static int pass, fail;
    private static boolean built;

    static boolean handles(String mode) {
        if (mode == null) return false;
        for (String t : mode.split(",")) {
            t = t.trim();
            if (t.equals("all") || t.equals("tabs") || t.equals("lists") || t.equals("flicker")
                    || t.equals("regress") || t.equals("hud") || t.equals("modules")) return true;
        }
        return false;
    }

    /** Build the scene list for {@code mode} (once). */
    private static void build(String mode) {
        built = true;
        add(action(c -> { gamemode(c, GameMode.CREATIVE); clearEffects(c); }));
        add(waitMs(500));
        boolean all = mode.contains("all");
        if (all || mode.contains("tabs"))    buildTabs();
        if (all || mode.contains("lists"))   buildLists();
        if (all || mode.contains("flicker")) buildFlicker();
        if (all || mode.contains("regress")) buildRegress();
        if (all || mode.contains("hud"))     buildHud();
        if (all || mode.contains("modules")) buildModules();
        add(action(c -> { close(c); say("[CLICKS] TOTAL " + pass + " PASS / " + fail + " FAIL"); }));
        add(waitMs(300));
    }

    /** One frame; true when every scene ran. */
    static boolean step(MinecraftClient c, String mode) {
        if (!built) build(mode);
        long now = System.nanoTime();
        lastFrameMs = lastFrameNanos == 0L ? 0 : (now - lastFrameNanos) / 1e6;
        lastFrameNanos = now;
        applyCursor(c);
        if (idx >= scenes.size()) return true;
        if (frame == 0) sceneStartNanos = now;
        frame++;
        double ms = (now - sceneStartNanos) / 1e6;
        boolean done;
        try {
            done = scenes.get(idx).step(c, frame, ms);
        } catch (Throwable t) {
            skip("scene " + idx, t);
            done = true;
        }
        if (!done && frame > 2000) { say("scene " + idx + " timed out"); done = true; }
        if (done) { idx++; frame = 0; }
        return idx >= scenes.size();
    }

    // ============================================================================================================
    //  (B) fused creative tabs
    // ============================================================================================================

    private static void buildTabs() {
        add(shot("tabs-a", c -> { openCreative(c); selectTab(c, group(true, 0)); hx = hy = -1; }, 900));
        // same-row slide: building blocks (col 0) -> transportation (col 3): the pill slides (springs 55/30)
        add(burst("tabs-slide", 8, c -> selectTab(c, group(true, 3)), DevShotVerify::tabsProbe));
        add(shot("tabs-slide-settled", null, 500));
        // cross-row: transportation (top col 3) -> food (bottom col 1): old pill fades out 150 ms, new fades in 100 ms
        add(burst("tabs-cross", 6, c -> selectTab(c, group(false, 1)), DevShotVerify::tabsProbe));
        add(shot("tabs-cross-settled", null, 500));
        // hover pill: in on top col 2, glide to top col 4, out
        add(burst("tabs-hover", 6, c -> { double[] p = cell(c, true, 2); if (p != null) { hx = p[0]; hy = p[1]; } },
                DevShotVerify::tabsProbe));
        add(shot("tabs-hover-settled", null, 400));
        add(burst("tabs-hoverglide", 6, c -> { double[] p = cell(c, true, 4); if (p != null) { hx = p[0]; hy = p[1]; } },
                DevShotVerify::tabsProbe));
        add(burst("tabs-hoverout", 5, c -> { hx = hy = -1; }, DevShotVerify::tabsProbe));
        add(waitMs(300));
        // hit-tests: click the centre of a fused cell -> that tab becomes selected (isClickInTab follows the cells)
        add(action(c -> tabClick(c, true, 2, 0.5, "top-2 centre")));
        add(waitMs(150));
        add(action(c -> tabClick(c, false, 3, 0.5, "bottom-3 centre")));
        add(waitMs(150));
        add(action(c -> tabClick(c, true, 5, 0.5, "top-5 centre")));
        add(waitMs(150));
        add(action(c -> tabClick(c, false, 0, 0.5, "bottom-0 centre")));
        add(waitMs(150));
        // a point inside fused cell 4 that vanilla's own tab boxes MISS (the gap between vanilla col 4 and the
        // right-aligned col 5): proves the hit box moved to the drawn cell
        add(action(c -> tabClick(c, true, 4, 0.9, "top-4 right edge (vanilla gap)")));
        add(waitMs(300));
        add(shot("tabs-clicked", null, 300));
        // tab-name tooltip on the top layer, from the fused cell's hit box
        add(shot("tabs-tooltip", c -> { double[] p = cell(c, true, 1); if (p != null) { hx = p[0]; hy = p[1] + 4; } }, 500));
        // the creative INVENTORY tab on the fused sheet (armor / off-hand / delete slots, player model, lattice)
        add(shot("tabs-inventory", c -> { hx = hy = -1; selectTab(c, ItemGroup.INVENTORY); }, 600));
        add(action(c -> { hx = hy = -1; close(c); }));
        add(waitMs(300));
    }

    // ============================================================================================================
    //  (R3) regression: the generic container path now runs through ContainerGlass
    // ============================================================================================================

    private static void buildRegress() {
        add(action(c -> gamemode(c, GameMode.SURVIVAL)));
        add(waitMs(400));
        add(shot("reg-inventory", c -> { hx = hy = -1; open(c, new net.minecraft.client.gui.screen.ingame.InventoryScreen(c.player)); }, 900));
        // hover a hotbar slot: the glass hover pill (vanilla's white highlight stays suppressed)
        add(shot("reg-inventory-hover", c -> hoverAbs(c, 8 + 18 + 8, 142 + 8), 600, c -> hoverAbs(c, 8 + 18 + 8, 142 + 8)));
        // the recipe book (existing glass) opened with its toggle button (x+104, height/2-22)
        add(action(c -> {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            HandledScreenAccessor a = (HandledScreenAccessor) s;
            double bx = a.s1mp1e$x() + 104 + 10, by = s.height / 2.0 - 22 + 9;
            s.mouseClicked(bx, by, 0);
            s.mouseReleased(bx, by, 0);
        }));
        add(shot("reg-inventory-recipebook", c -> { hx = hy = -1; }, 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-chest", c -> open(c, new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(
                net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Chest"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-furnace", c -> open(c, new net.minecraft.client.gui.screen.ingame.FurnaceScreen(
                new net.minecraft.screen.FurnaceScreenHandler(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Furnace"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        // anvil with an item: the name field + its enabled background (drawn inside drawBackground) must survive
        add(shot("reg-anvil", c -> {
            net.minecraft.screen.AnvilScreenHandler h = new net.minecraft.screen.AnvilScreenHandler(1, c.player.getInventory());
            open(c, new net.minecraft.client.gui.screen.ingame.AnvilScreen(h, c.player.getInventory(), new LiteralText("Anvil")));
            h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
        }, 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        // grindstone with two incompatible inputs: vanilla's red error X (information) must survive
        add(shot("reg-grindstone", c -> {
            net.minecraft.screen.GrindstoneScreenHandler h = new net.minecraft.screen.GrindstoneScreenHandler(1, c.player.getInventory());
            open(c, new net.minecraft.client.gui.screen.ingame.GrindstoneScreen(h, c.player.getInventory(), new LiteralText("Grindstone")));
            h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
            h.getSlot(1).setStack(new ItemStack(Items.IRON_PICKAXE));
        }, 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        // enchanting: the book model + the three option slots are drawn inside drawBackground
        add(shot("reg-enchanting", c -> {
            net.minecraft.screen.EnchantmentScreenHandler h = new net.minecraft.screen.EnchantmentScreenHandler(1, c.player.getInventory());
            open(c, new net.minecraft.client.gui.screen.ingame.EnchantmentScreen(h, c.player.getInventory(), new LiteralText("Enchant")));
            h.getSlot(0).setStack(new ItemStack(Items.DIAMOND_SWORD));
            h.getSlot(1).setStack(new ItemStack(Items.LAPIS_LAZULI, 3));
        }, 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-brewing", c -> open(c, new net.minecraft.client.gui.screen.ingame.BrewingStandScreen(
                new net.minecraft.screen.BrewingStandScreenHandler(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Brewing"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        // cartography calls renderBackground INSIDE drawBackground: the dim must stay under the glass
        add(shot("reg-cartography", c -> open(c, new net.minecraft.client.gui.screen.ingame.CartographyTableScreen(
                new net.minecraft.screen.CartographyTableScreenHandler(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Cartography"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-crafting", c -> open(c, new net.minecraft.client.gui.screen.ingame.CraftingScreen(
                new net.minecraft.screen.CraftingScreenHandler(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Crafting"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-beacon", c -> open(c, new net.minecraft.client.gui.screen.ingame.BeaconScreen(
                new net.minecraft.screen.BeaconScreenHandler(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Beacon"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-hopper", c -> open(c, new net.minecraft.client.gui.screen.ingame.HopperScreen(
                new net.minecraft.screen.HopperScreenHandler(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Hopper"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("reg-chest-large", c -> open(c, new net.minecraft.client.gui.screen.ingame.GenericContainerScreen(
                net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(1, c.player.getInventory()),
                c.player.getInventory(), new LiteralText("Large Chest"))), 900));
        add(action(c -> close(c)));
        add(waitMs(300));
    }

    /** Park the virtual cursor at a panel-relative point of the current container. */
    private static void hoverAbs(MinecraftClient c, int rx, int ry) {
        HandledScreen<?> s = hs(c);
        if (s == null) return;
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        hx = a.s1mp1e$x() + rx;
        hy = a.s1mp1e$y() + ry;
    }

    private static ItemGroup group(boolean top, int col) {
        for (ItemGroup g : ItemGroup.GROUPS) {
            if (g != null && g.isTopRow() == top && g.getColumn() == col) return g;
        }
        return null;
    }

    /** Absolute centre of fused cell {@code col} (fraction {@code fx} across the cell) in the top/bottom band. */
    private static double[] cell(MinecraftClient c, boolean top, int col) { return cell(c, top, col, 0.5); }

    private static double[] cell(MinecraftClient c, boolean top, int col, double fx) {
        HandledScreen<?> s = hs(c);
        if (s == null) return null;
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        float cw = GlassTabs.cellW(a.s1mp1e$backgroundWidth());
        double x = a.s1mp1e$x() + (col + fx) * cw;
        double y = top ? a.s1mp1e$y() - GlassTabs.BAND / 2.0 : a.s1mp1e$y() + a.s1mp1e$backgroundHeight() + GlassTabs.BAND / 2.0;
        return new double[]{x, y};
    }

    private static void tabClick(MinecraftClient c, boolean top, int col, double fx, String label) {
        HandledScreen<?> s = hs(c);
        ItemGroup g = group(top, col);
        if (!(s instanceof CreativeInventoryScreen) || g == null) { fail++; say("[CLICKS] tab " + label + " FAIL: no screen/group"); return; }
        double[] p = cell(c, top, col, fx);
        hx = p[0]; hy = p[1];
        s.mouseClicked(p[0], p[1], 0);
        s.mouseReleased(p[0], p[1], 0);
        int sel = ((CreativeInventoryScreen) s).getSelectedTab();
        boolean ok = sel == g.getIndex();
        if (ok) pass++; else fail++;
        say("[CLICKS] tab " + label + " at " + fmt((float) p[0]) + "," + fmt((float) p[1]) + " expected=" + g.getName()
                + "(" + g.getIndex() + ") selected=" + sel + " -> " + (ok ? "PASS" : "FAIL"));
        hx = hy = -1;
    }

    private static String tabsProbe(MinecraftClient c) {
        if (!(c.currentScreen instanceof TabsProbe)) return "";
        GlassTabs t = ((TabsProbe) c.currentScreen).s1mp1e$probeTabs();
        return "selCx=" + fmt(t.probeSelCx()) + " selFade=" + fmt(t.probeSelFade()) + " ghostFade=" + fmt(t.probeGhostFade())
                + " hoverFade=" + fmt(t.probeHoverFade()) + " hoverCx=" + fmt(t.probeHoverCx());
    }

    // ============================================================================================================
    //  (C/D) lists: creative / stonecutter / loom / merchant
    // ============================================================================================================

    private static void buildLists() {
        for (final ListKind k : ListKind.values()) {
            add(shot(k.id + "-rest", c -> { hx = hy = -1; k.open(c); }, 1000));
            add(action(c -> say("[LIST] " + k.id + " opened: " + k.describe(c))));
            add(action(c -> k.wheel(c, k.midSteps(c))));
            add(shot(k.id + "-mid", null, 700));
            // held: a REAL press on the glass thumb + a 1:1 drag, the thumb morphs into the refracting lens
            add(action(c -> k.pressThumb(c)));
            add(burst(k.id + "-drag", 5, c -> k.dragBy(c, 6.0), c -> k.probe(c)));
            add(shot(k.id + "-held", c -> k.dragBy(c, 3.0), 260, c -> k.hold(c)));
            // drag far past the end of the track: the lens rubber-bands (1:1 until the end, then resisting)
            add(shot(k.id + "-rubber", c -> k.dragBy(c, 160.0), 220, c -> k.hold(c)));
            add(action(c -> say("[DRAG] " + k.id + " rubber " + k.probe(c) + " thumbPointerDy=" + k.rubberInfo(c))));
            add(action(c -> k.release(c)));
            add(burst(k.id + "-release", 4, null, c -> k.probe(c)));
            add(waitMs(600));
            add(action(c -> k.toTop(c)));
            add(waitMs(800));
            add(burst(k.id + "-glide", 10, c -> k.wheel(c, k.glideSteps(c)), c -> k.probe(c)));
            add(waitMs(800));
            add(action(c -> say("[LIST] " + k.id + " settled " + k.probe(c))));
            // clicks: at rest, mid-glide (snap), edge cell
            add(action(c -> k.toTop(c)));
            add(waitMs(700));
            add(action(c -> k.clickCheck(c, 1, 1, "rest", false)));
            add(waitMs(300));
            add(action(c -> k.wheel(c, k.clickSteps(c))));
            add(clickMidGlide(k));
            add(waitMs(600));
            add(action(c -> k.clickCheck(c, k.lastRow(), k.lastCol(), "edge", false)));
            add(waitMs(300));
            if (k == ListKind.CREATIVE) {
                // R1(b): an item tooltip whose card overlaps the glass scrollbar (+ the next row of items)
                add(shot("creative-tooltip-scrollbar", c -> k.hoverGrid(c, 1, 8), 700, c -> k.hoverGrid(c, 1, 8)));
                add(action(c -> { hx = hy = -1; }));
                add(waitMs(200));
            }
            if (k == ListKind.MERCHANT) {
                // the out-of-stock marker of the (disabled) selected trade survives the glass (select trade 0)
                add(action(c -> { k.toTop(c); }));
                add(waitMs(700));
                add(action(c -> k.clickCheck(c, 0, 0, "select disabled trade 0", false)));
                add(shot("merchant-outofstock", null, 500));
            }
            add(action(c -> { hx = hy = -1; close(c); }));
            add(waitMs(400));
        }
    }

    /** Two frames after a wheel kick the list must be mid-glide; click then (the HEAD snap acts on the drawn item). */
    private static Scene clickMidGlide(final ListKind k) {
        return (c, fr, ms) -> {
            if (fr < 2) return false;
            boolean g = k.gliding(c);
            say("[CLICKS] " + k.id + " mid-glide precondition gliding=" + g + " offset=" + fmt(k.offset(c)));
            if (!g) { fail++; say("[CLICKS] " + k.id + " mid-glide FAIL: not gliding at the click frame"); return true; }
            capture(c, k.id + "-click-midglide-before.png");
            k.clickCheck(c, 1, 2, "mid-glide", true);
            return true;
        };
    }

    private enum ListKind {
        CREATIVE("creative"), STONECUTTER("stonecutter"), LOOM("loom"), MERCHANT("merchant");

        final String id;
        private double dragY;

        ListKind(String id) { this.id = id; }

        void open(MinecraftClient c) {
            switch (this) {
                case CREATIVE:    openCreative(c); selectTab(c, ItemGroup.SEARCH); break;
                case STONECUTTER: openStonecutter(c); break;
                case LOOM:        openLoom(c); break;
                default:          openMerchant(c); break;
            }
        }

        int rows(MinecraftClient c) { return Math.round(maxOffsetPx(c) / pitch()); }
        float pitch() { return this == LOOM ? 14f : this == MERCHANT ? 20f : 18f; }

        int midSteps(MinecraftClient c)   { return this == STONECUTTER ? 1 : Math.max(1, rows(c) / 2); }
        int glideSteps(MinecraftClient c) { return this == STONECUTTER ? 1 : this == LOOM ? 3 : this == MERCHANT ? 5 : 3; }
        int clickSteps(MinecraftClient c) { return 1; }

        String describe(MinecraftClient c) {
            return "rows=" + rows(c) + " maxOffsetPx=" + fmt(maxOffsetPx(c)) + " " + probe(c);
        }

        double[] listCenter(HandledScreen<?> s) {
            HandledScreenAccessor a = (HandledScreenAccessor) s;
            int x = a.s1mp1e$x(), y = a.s1mp1e$y();
            switch (this) {
                case CREATIVE:    return new double[]{x + 90, y + 60};
                case STONECUTTER: return new double[]{x + 84, y + 40};
                case LOOM:        return new double[]{x + 88, y + 40};
                default:          return new double[]{x + 50, y + 90};
            }
        }

        /** Glass thumb centre at the CURRENT eased ratio. */
        double[] thumb(MinecraftClient c, HandledScreen<?> s) {
            HandledScreenAccessor a = (HandledScreenAccessor) s;
            int x = a.s1mp1e$x(), y = a.s1mp1e$y();
            float r = ratio(c);
            switch (this) {
                case CREATIVE:    return new double[]{x + 181, y + 18 + r * 97 + 7.5};
                case STONECUTTER: return new double[]{x + 125, y + 15 + r * 41 + 7.5};
                case LOOM:        return new double[]{x + 125, y + 13 + r * 41 + 7.5};
                default:          return new double[]{x + 97, y + 24 + r * 112 + 7.5};
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
                        int rowsTotal = (Integer) getStatic(net.minecraft.client.gui.screen.ingame.LoomScreen.class, "PATTERN_BUTTON_ROW_COUNT");
                        return Math.max(0, rowsTotal - 4) * 14f;
                    }
                    default: {
                        int n = ((net.minecraft.screen.MerchantScreenHandler) s.getScreenHandler()).getRecipes().size();
                        return Math.max(0, n - 7) * 20f;
                    }
                }
            } catch (Throwable t) { return 0; }
        }

        float offset(MinecraftClient c) {
            return c.currentScreen instanceof GlideProbe ? ((GlideProbe) c.currentScreen).s1mp1e$probeOffsetPx() : -1f;
        }

        boolean gliding(MinecraftClient c) {
            return c.currentScreen instanceof GlideProbe && ((GlideProbe) c.currentScreen).s1mp1e$probeGliding();
        }

        float lift(MinecraftClient c) {
            return c.currentScreen instanceof GlideProbe ? ((GlideProbe) c.currentScreen).s1mp1e$probeLift() : -1f;
        }

        String probe(MinecraftClient c) {
            return "gliding=" + gliding(c) + " offsetPx=" + fmt(offset(c)) + " logicalTop=" + logicalTop(c)
                    + " lensLift=" + fmt(lift(c));
        }

        String rubberInfo(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return "-";
            double[] t = thumb(c, s);
            return fmt((float) (dragY - t[1]));
        }

        /** Row-aligned logical top (rows for grids, trades for the merchant). */
        int logicalTop(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return -1;
            try {
                switch (this) {
                    case CREATIVE: {
                        float p = (Float) getO(s, CreativeInventoryScreen.class, "scrollPosition");
                        List<ItemStack> l = ((CreativeInventoryScreen.CreativeScreenHandler) s.getScreenHandler()).itemList;
                        int rc = Math.max(0, (l.size() + 8) / 9 - 5);
                        return Math.max((int) (p * rc + 0.5f), 0);
                    }
                    case STONECUTTER: return ((Integer) getO(s, net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollOffset")) / 4;
                    case LOOM:        return ((Integer) getO(s, net.minecraft.client.gui.screen.ingame.LoomScreen.class, "firstPatternButtonId") - 1) / 4;
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
            t[1] -= 3.0;   // a little above the glass-thumb centre: inside vanilla's (shorter) scroll hit box at the ends
            hx = t[0]; hy = t[1];
            dragY = t[1];
            s.mouseClicked(t[0], t[1], 0);
            say("[DRAG] " + id + " press thumb at " + fmt((float) t[0]) + "," + fmt((float) t[1]) + " " + probe(c));
        }

        void dragBy(MinecraftClient c, double dy) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            double[] t = thumb(c, s);
            dragY += dy;
            hx = t[0]; hy = dragY;
            s.mouseDragged(t[0], dragY, 0, 0.0, dy);
        }

        /** Keep the pointer where the drag left it (the held-lens shots re-read it every frame). */
        void hold(MinecraftClient c) { hy = dragY; }

        void release(MinecraftClient c) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            s.mouseReleased(hx, hy, 0);
            hx = hy = -1;
        }

        void hoverGrid(MinecraftClient c, int row, int col) {
            HandledScreen<?> s = hs(c);
            if (s == null) return;
            HandledScreenAccessor a = (HandledScreenAccessor) s;
            hx = a.s1mp1e$x() + 9 + col * 18 + 8;
            hy = a.s1mp1e$y() + 18 + row * 18 + 8;
        }

        int lastRow() { return this == CREATIVE ? 4 : this == STONECUTTER ? 2 : this == LOOM ? 3 : 6; }
        int lastCol() { return this == CREATIVE ? 8 : this == MERCHANT ? 0 : 3; }

        /** Click the drawn cell (visRow, col) and assert vanilla acted on the item drawn there. */
        void clickCheck(MinecraftClient c, int row, int col, String label, boolean expectSnap) {
            HandledScreen<?> s = hs(c);
            if (s == null) { fail++; say("[CLICKS] " + id + " " + label + " FAIL: no screen"); return; }
            HandledScreenAccessor a = (HandledScreenAccessor) s;
            int x = a.s1mp1e$x(), y = a.s1mp1e$y();
            try {
                switch (this) {
                    case CREATIVE: {
                        int si = row * 9 + col;
                        net.minecraft.screen.slot.Slot slot = s.getScreenHandler().slots.get(si);
                        double mx = x + slot.x + 8, my = y + slot.y + 8;
                        hx = mx; hy = my;
                        // the logical row vanilla hit-tests; mid-glide the HEAD snap makes it the drawn row
                        s.mouseClicked(mx, my, 0);
                        boolean snapped = !gliding(c);
                        Item expected = slot.getStack().getItem();
                        ItemStack got = s.getScreenHandler().getCursorStack();
                        boolean ok = !got.isEmpty() && got.getItem() == expected && (!expectSnap || snapped);
                        verdict(ok, label, "slot " + si + " top=" + logicalTop(c) + " expected=" + Registry.ITEM.getId(expected)
                                + " got=" + Registry.ITEM.getId(got.getItem()) + (expectSnap ? " snapped=" + snapped : ""));
                        s.getScreenHandler().setCursorStack(ItemStack.EMPTY);
                        s.mouseReleased(mx, my, 0);
                        s.getScreenHandler().setCursorStack(ItemStack.EMPTY);
                        break;
                    }
                    case STONECUTTER: {
                        net.minecraft.screen.StonecutterScreenHandler h = (net.minecraft.screen.StonecutterScreenHandler) s.getScreenHandler();
                        int topBefore = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollOffset");
                        double mx = x + 52 + col * 16 + 8, my = y + 14 + row * 18 + 2 + 9;
                        hx = mx; hy = my;
                        s.mouseClicked(mx, my, 0);
                        int top = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.StonecutterScreen.class, "scrollOffset");
                        int expected = top + row * 4 + col;
                        boolean snapped = !gliding(c);
                        boolean ok = h.getSelectedRecipe() == expected && (!expectSnap || snapped);
                        String item = expected < h.getAvailableRecipeCount()
                                ? String.valueOf(Registry.ITEM.getId(h.getAvailableRecipes().get(expected).getOutput().getItem())) : "-";
                        verdict(ok, label, "cell r" + row + "c" + col + " topBefore=" + topBefore + " top=" + top
                                + " expectedIndex=" + expected + " (" + item + ") selected=" + h.getSelectedRecipe()
                                + (expectSnap ? " snapped=" + snapped : ""));
                        s.mouseReleased(mx, my, 0);
                        break;
                    }
                    case LOOM: {
                        net.minecraft.screen.LoomScreenHandler h = (net.minecraft.screen.LoomScreenHandler) s.getScreenHandler();
                        double mx = x + 60 + col * 14 + 7, my = y + 13 + row * 14 + 7;
                        hx = mx; hy = my;
                        s.mouseClicked(mx, my, 0);
                        int first = (Integer) getO(s, net.minecraft.client.gui.screen.ingame.LoomScreen.class, "firstPatternButtonId");
                        int expected = first + row * 4 + col;
                        boolean snapped = !gliding(c);
                        boolean ok = h.getSelectedPattern() == expected && (!expectSnap || snapped);
                        verdict(ok, label, "cell r" + row + "c" + col + " firstId=" + first + " expectedPattern=" + expected
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
    //  (R4) flicker
    // ============================================================================================================

    private static void buildFlicker() {
        add(action(c -> {
            try {
                c.options.enableVsync = false;
                c.getWindow().setVsync(false);
                c.options.maxFps = 260;
                c.getWindow().setFramerateLimit(260);
            } catch (Throwable t) { skip("uncap fps", t); }
        }));
        add(shot("flk-creative-rest", c -> { hx = hy = -1; openCreative(c); selectTab(c, ItemGroup.SEARCH); }, 1200));
        add(everyNth("flk-creative", 8, 3));
        add(action(c -> close(c)));
        add(waitMs(300));
        add(shot("flk-merchant-rest", c -> { hx = hy = -1; openMerchant(c); }, 1200));
        add(everyNth("flk-merchant", 8, 3));
        add(action(c -> close(c)));
        add(waitMs(300));
    }

    // ============================================================================================================
    //  (G) HUD overlays  (mode "hud")
    // ============================================================================================================
    //
    // In-world, no screen: add chat lines (glass panel), open the chat screen (glass input bar), show an action-bar
    // message (glass pill), pop a system toast (glass card + slide), inject a client boss bar (blue capsule + concentric
    // glass capsule), spawn a named armor stand (frosted name-tag plate) and force the tab list. Every step is guarded.

    private static void buildHud() {
        add(action(c -> {
            close(c);
            net.minecraft.client.gui.hud.ChatHud chat = c.inGameHud.getChatHud();
            chat.addMessage(new LiteralText("<S1mp1e> liquid glass chat panel"));
            chat.addMessage(new LiteralText("the widest line sets the panel width"));
            chat.addMessage(new LiteralText("per-line dark rects are dropped"));
        }));
        add(shot("G-chat",       c -> {}, 600));                                                   // glass panel behind lines
        add(shot("G-chat-input", c -> open(c, new net.minecraft.client.gui.screen.ChatScreen("liquid glass input bar")), 500));
        add(action(c -> close(c)));
        add(shot("G-actionbar",  c -> c.inGameHud.setOverlayMessage(new LiteralText("Action Bar Pill"), false), 250)); // fading glass pill
        add(shot("G-toast",      DevShotVerify::showToast,   180));                                // glass toast card, mid slide-in
        add(shot("G-bossbar",    DevShotVerify::addBossBar,  500));                                // blue capsule + concentric glass
        add(shot("G-nametag",    DevShotVerify::spawnNameTag, 800));                               // frosted name-tag plate
        add(shot("G-tablist",    DevShotVerify::tabListVisible, 500));                             // glass header/list/footer plates
        add(action(c -> {
            try { clearBossBars(c); } catch (Throwable ignored) {}
            try { c.getToastManager().clear(); } catch (Throwable ignored) {}
            try { c.options.keyPlayerList.setPressed(false); } catch (Throwable ignored) {}
        }));
        add(waitMs(200));
    }

    /** Pop a system toast so the glass toast card (and its slide-in) can be captured. */
    private static void showToast(MinecraftClient c) {
        try {
            net.minecraft.client.toast.SystemToast.Type type = net.minecraft.client.toast.SystemToast.Type.values()[0];
            net.minecraft.client.toast.SystemToast.show(c.getToastManager(), type,
                    new LiteralText("S1mp1e"), new LiteralText("liquid glass toast"));
        } catch (Throwable t) { skip("show toast", t); }
    }

    /** Inject a client boss bar directly into the BossBarHud map (reflection) so the glass boss bar can be captured. */
    private static void addBossBar(MinecraftClient c) {
        try {
            net.minecraft.client.gui.hud.BossBarHud hud = c.inGameHud.getBossBarHud();
            java.lang.reflect.Field f = net.minecraft.client.gui.hud.BossBarHud.class.getDeclaredField("bossBars");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<java.util.UUID, net.minecraft.client.gui.hud.ClientBossBar> map =
                    (java.util.Map<java.util.UUID, net.minecraft.client.gui.hud.ClientBossBar>) f.get(hud);
            net.minecraft.client.gui.hud.ClientBossBar bar = new net.minecraft.client.gui.hud.ClientBossBar(
                    java.util.UUID.randomUUID(), new LiteralText("Liquid Glass Boss"), 0.65F,
                    net.minecraft.entity.boss.BossBar.Color.PURPLE, net.minecraft.entity.boss.BossBar.Style.PROGRESS,
                    false, false, false);
            map.put(java.util.UUID.randomUUID(), bar);
        } catch (Throwable t) { skip("add boss bar", t); }
    }

    private static void clearBossBars(MinecraftClient c) {
        try { c.inGameHud.getBossBarHud().clear(); } catch (Throwable ignored) {}
    }

    /** Spawn a named armor stand a few blocks in front of the player (facing it) so its name-tag plate renders. */
    private static void spawnNameTag(MinecraftClient c) {
        try {
            net.minecraft.client.network.ClientPlayerEntity p = c.player;
            p.setPitch(0f); p.prevPitch = 0f;               // look level so the tag is centred
            p.setYaw(0f);   p.prevYaw = 0f; p.headYaw = 0f;  // yaw 0 = facing +Z
            double x = p.getX(), y = p.getY(), z = p.getZ();
            net.minecraft.server.MinecraftServer server = c.getServer();
            net.minecraft.server.world.ServerWorld ow = server.getOverworld();
            net.minecraft.entity.decoration.ArmorStandEntity e =
                    new net.minecraft.entity.decoration.ArmorStandEntity(ow, x, y, z + 3.0);
            e.setCustomName(new LiteralText("Frosted Name Tag"));
            e.setCustomNameVisible(true);
            ow.spawnEntity(e);
        } catch (Throwable t) { skip("spawn name tag", t); }
    }

    /** Best-effort tab list: add a LIST-slot scoreboard objective on the server + hold the player-list key. */
    private static void tabListVisible(MinecraftClient c) {
        try {
            net.minecraft.server.world.ServerWorld ow = c.getServer().getOverworld();
            net.minecraft.scoreboard.Scoreboard sb = ow.getScoreboard();
            net.minecraft.scoreboard.ScoreboardObjective obj = sb.getNullableObjective("s1mp1e_tab");
            if (obj == null) {
                obj = sb.addObjective("s1mp1e_tab", net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                        new LiteralText("Players"), net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER);
            }
            sb.setObjectiveSlot(0, obj);   // slot 0 = LIST
            c.options.keyPlayerList.setPressed(true);
        } catch (Throwable t) { skip("tab list visible", t); }
    }

    // ============================================================================================================
    //  (H) modules  (mode "modules")
    // ============================================================================================================
    //
    // Enable Block Outline (recolour / chroma / width 1 & 8 / fill) while looking at the ground, and Chroma HUD
    // (per-char rainbow + uniform wave-0) over the FPS/Coords HUD, then open the config screen (zh-TW module labels).
    // All module state is snapshotted and restored at the end (nothing is saved to disk - the run quits after).

    private static void buildModules() {
        add(action(c -> { snapshotModules(); close(c); lookAtGround(c); }));
        add(shot("H-outline-width1",  c -> outline(0xCCFFFFFF, 1.0, false, false, 0),        300)); // thin end of 1..8
        add(shot("H-outline",         c -> outline(0xCCFF3060, 6.0, false, false, 0),        300)); // custom colour + width 6
        add(shot("H-outline-chroma-1",c -> outline(0xCCFF3060, 6.0, true,  false, 0),        300)); // chroma on
        add(shot("H-outline-chroma-2",c -> {},                                               400)); // hue advanced
        add(shot("H-outline-width8",  c -> outline(0xCCFFFFFF, 8.0, false, false, 0),        300)); // width 8 (core-profile check)
        add(shot("H-outline-fill",    c -> outline(0xCCFFFFFF, 2.5, false, true, 0x5533C0FF),300)); // translucent depth-tested fill
        add(action(c -> outlineOff()));
        add(shot("H-hud-chroma",      c -> chroma(true, 1.0, 0.75, 0.5),                     300)); // per-char rainbow HUD
        add(shot("H-hud-chroma-flat", c -> chroma(true, 1.0, 0.75, 0.0),                     300)); // wave 0 = one uniform hue
        add(action(c -> chroma(false, 1.0, 0.75, 0.5)));                                            // HUD back to neutral for the config shots
        add(shot("H-config-blockoutline", c -> openConfigModule(c, "BlockOutline"),          500)); // zh-TW: 方塊外框 page
        add(shot("H-config-chromahud",    c -> selectConfigModule("ChromaHud"),              500)); // zh-TW: 彩虹 HUD page
        add(action(c -> { close(c); restoreModules(); }));
        add(waitMs(200));
    }

    /** Pitch the player down so the crosshair targets a ground block (the outline recolour needs a targeted block). */
    private static void lookAtGround(MinecraftClient c) {
        try {
            net.minecraft.client.network.ClientPlayerEntity p = c.player;
            p.setPitch(72f); p.prevPitch = 72f;
            p.setYaw(0f); p.prevYaw = 0f; p.headYaw = 0f;
        } catch (Throwable t) { skip("look at ground", t); }
    }

    private static void outline(int colour, double width, boolean chroma, boolean fill, int fillColour) {
        Module m = ModuleManager.byName("BlockOutline");
        if (!(m instanceof dev.s1mp1e.client.module.BlockOutlineModule)) return;
        dev.s1mp1e.client.module.BlockOutlineModule bo = (dev.s1mp1e.client.module.BlockOutlineModule) m;
        bo.setEnabled(true);
        bo.color.colorValue = colour;
        bo.width.doubleValue = width;
        bo.chroma.boolValue = chroma;
        bo.fill.boolValue = fill;
        if (fill) bo.fillColour.colorValue = fillColour;
    }

    private static void outlineOff() {
        Module m = ModuleManager.byName("BlockOutline");
        if (m != null) m.setEnabled(false);
    }

    private static void chroma(boolean on, double speed, double sat, double wave) {
        Module m = ModuleManager.byName("ChromaHud");
        if (!(m instanceof dev.s1mp1e.client.module.ChromaHudModule)) return;
        dev.s1mp1e.client.module.ChromaHudModule ch = (dev.s1mp1e.client.module.ChromaHudModule) m;
        ch.setEnabled(on);
        ch.speed.doubleValue = speed;
        ch.saturation.doubleValue = sat;
        ch.wave.doubleValue = wave;
        Module coords = ModuleManager.byName("CoordsHUD");
        if (coords != null) coords.setEnabled(true);   // ensure there is HUD text to colour (FPS is on by default)
    }

    /** The config screen kept open across the two config-page captures (reused for both modules). */
    private static dev.s1mp1e.client.gui.S1mp1eConfigScreen cfgScreen;

    /** Open the settings screen and drive it to {@code moduleName}'s category tab + row (zh-TW page). */
    private static void openConfigModule(MinecraftClient c, String moduleName) {
        cfgScreen = new dev.s1mp1e.client.gui.S1mp1eConfigScreen();
        open(c, cfgScreen);
        selectConfigModule(moduleName);
    }

    /**
     * Reflectively switch the (already open) config screen to the tab holding {@code moduleName} and select that row,
     * so its zh-TW label + settings render. Harness-only: reaches the config screen's private {@code tab}/{@code
     * tabSlide}/{@code rebuildTab()}/{@code selectModule(..)} rather than change that file (R3).
     */
    private static void selectConfigModule(String moduleName) {
        try {
            if (cfgScreen == null) return;
            Module m = ModuleManager.byName(moduleName);
            if (m == null) { skip("select config module " + moduleName, new IllegalStateException("no such module")); return; }
            String[] tabs = {"Combat", "HUD", "Visual"};
            int ti = 0;
            for (int i = 0; i < tabs.length; i++) if (tabs[i].equalsIgnoreCase(m.category)) ti = i;
            Class<?> cl = cfgScreen.getClass();
            java.lang.reflect.Field tabF = cl.getDeclaredField("tab");
            tabF.setAccessible(true); tabF.setInt(cfgScreen, ti);
            try {
                java.lang.reflect.Field tsF = cl.getDeclaredField("tabSlide");
                tsF.setAccessible(true);
                Object ts = tsF.get(cfgScreen);
                ts.getClass().getMethod("snap", float.class).invoke(ts, (float) ti);
            } catch (Throwable ignored) {}
            java.lang.reflect.Method rebuild = cl.getDeclaredMethod("rebuildTab");
            rebuild.setAccessible(true); rebuild.invoke(cfgScreen);
            java.lang.reflect.Method sel = cl.getDeclaredMethod("selectModule", Module.class);
            sel.setAccessible(true); sel.invoke(cfgScreen, m);
        } catch (Throwable t) { skip("select config module " + moduleName, t); }
    }

    // Snapshot/restore so the modules sweep leaves no lasting change (nothing is written to disk).
    private static boolean[] snapEnabled;
    private static void snapshotModules() {
        String[] names = {"BlockOutline", "ChromaHud", "CoordsHUD", "FpsHUD"};
        snapEnabled = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            Module m = ModuleManager.byName(names[i]);
            snapEnabled[i] = m != null && m.enabled;
        }
    }
    private static void restoreModules() {
        try {
            String[] names = {"BlockOutline", "ChromaHud", "CoordsHUD", "FpsHUD"};
            for (int i = 0; snapEnabled != null && i < names.length; i++) {
                Module m = ModuleManager.byName(names[i]);
                if (m == null) continue;
                m.setEnabled(snapEnabled[i]);
                for (int s = 0; s < m.settings.size(); s++) m.settings.get(s).reset();
            }
        } catch (Throwable ignored) {}
    }

    // ============================================================================================================
    //  scene builders
    // ============================================================================================================

    private static void add(Scene s) { scenes.add(s); }

    private static Scene action(java.util.function.Consumer<MinecraftClient> a) {
        return (c, fr, ms) -> { try { a.accept(c); } catch (Throwable t) { skip("action", t); } return true; };
    }

    private static Scene waitMs(final double wait) { return (c, fr, ms) -> ms >= wait && fr >= 3; }

    private static Scene shot(String name, java.util.function.Consumer<MinecraftClient> setup, double wait) {
        return shot(name, setup, wait, null);
    }

    /** Setup on frame 1, {@code each} every frame from frame 3, capture once {@code wait} ms elapsed (>= 12 frames). */
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

    /** Kick on frame 1, then capture {@code n} CONSECUTIVE frames (logging the optional probe with the frame time). */
    private static Scene burst(final String name, final int n, final java.util.function.Consumer<MinecraftClient> kick,
                               final java.util.function.Function<MinecraftClient, String> probe) {
        return (c, fr, ms) -> {
            if (fr == 1) {
                if (kick != null) { try { kick.accept(c); } catch (Throwable t) { skip("kick " + name, t); } }
                return false;
            }
            int k = fr - 2;
            if (k < n) {
                capture(c, String.format(Locale.ROOT, "%s-%02d.png", name, k));
                say("burst " + name + " " + k + " t=" + fmt((float) ms) + "ms dt=" + fmt((float) lastFrameMs) + "ms"
                        + (probe != null ? " " + probe.apply(c) : ""));
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
                    capture(c, String.format(Locale.ROOT, "%s-%02d.png", name, k));
                    say("flicker " + name + " " + k + " prevFrameMs=" + fmt((float) lastFrameMs));
                }
                return k >= n - 1;
            }
            return false;
        };
    }

    // ============================================================================================================
    //  screen openers + helpers
    // ============================================================================================================

    private static void openCreative(MinecraftClient c) {
        gamemode(c, GameMode.CREATIVE);
        open(c, new CreativeInventoryScreen(c.player));
    }

    /** Invoke the private {@code CreativeInventoryScreen.setSelectedTab(ItemGroup)} (repopulates the grid). */
    private static void selectTab(MinecraftClient c, ItemGroup g) {
        if (!(c.currentScreen instanceof CreativeInventoryScreen) || g == null) return;
        try {
            java.lang.reflect.Method m = CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab", ItemGroup.class);
            m.setAccessible(true);
            m.invoke(c.currentScreen, g);
        } catch (Throwable t) { skip("select tab", t); }
    }

    private static void openStonecutter(MinecraftClient c) {
        net.minecraft.screen.StonecutterScreenHandler h =
                new net.minecraft.screen.StonecutterScreenHandler(1, c.player.getInventory());
        // screen FIRST (its constructor registers the contents listener -> canCraft), then the input
        open(c, new net.minecraft.client.gui.screen.ingame.StonecutterScreen(h, c.player.getInventory(), new LiteralText("Stonecutter")));
        h.input.setStack(0, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
    }

    private static void openLoom(MinecraftClient c) {
        net.minecraft.screen.LoomScreenHandler h = new net.minecraft.screen.LoomScreenHandler(1, c.player.getInventory());
        open(c, new net.minecraft.client.gui.screen.ingame.LoomScreen(h, c.player.getInventory(), new LiteralText("Loom")));
        h.getBannerSlot().setStack(new ItemStack(Items.WHITE_BANNER));   // screen first: its constructor registers the
        h.getDyeSlot().setStack(new ItemStack(Items.RED_DYE));           // inventory listener that sets canApplyDyePattern
    }

    private static void openMerchant(MinecraftClient c) {
        net.minecraft.screen.MerchantScreenHandler h = new net.minecraft.screen.MerchantScreenHandler(1, c.player.getInventory());
        net.minecraft.village.TradeOfferList offers = new net.minecraft.village.TradeOfferList();
        Item[] sells = { Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT, Items.BREAD, Items.BOOK, Items.ARROW,
                Items.COAL, Items.APPLE, Items.STICK, Items.PAPER, Items.COMPASS, Items.CLOCK, Items.LANTERN,
                Items.GLASS, Items.BELL, Items.SHIELD, Items.BOW, Items.EMERALD_BLOCK, Items.NAME_TAG, Items.SADDLE };
        for (int i = 0; i < sells.length; i++) {
            ItemStack costB = i % 3 == 1 ? new ItemStack(Items.BOOK, 1) : ItemStack.EMPTY;
            // trade 0 is sold out (uses == maxUses) -> vanilla's red out-of-stock marker when selected
            net.minecraft.village.TradeOffer o = new net.minecraft.village.TradeOffer(new ItemStack(Items.EMERALD, 1 + i),
                    costB, new ItemStack(sells[i], 1 + (i % 4)), i == 0 ? 12 : 0, 12, 5, 0.05f);
            if (i == 2) o.setSpecialPrice(-1);   // a discounted trade: exercises the first-buy strike line in the glide
            offers.add(o);
        }
        h.setOffers(offers);
        open(c, new net.minecraft.client.gui.screen.ingame.MerchantScreen(h, c.player.getInventory(), new LiteralText("Villager")));
    }

    private static void gamemode(MinecraftClient c, GameMode m) {
        try {
            if (c.getServer() != null) c.getServer().getCommandManager().execute(c.getServer().getCommandSource(),
                    "gamemode " + (m == GameMode.CREATIVE ? "creative" : "survival") + " @a");
        } catch (Throwable t) { skip("gamemode (server)", t); }
        try { if (c.interactionManager != null) c.interactionManager.setGameMode(m); } catch (Throwable t) { skip("gamemode (client)", t); }
    }

    private static void clearEffects(MinecraftClient c) {
        try {
            if (c.getServer() != null) c.getServer().getCommandManager().execute(c.getServer().getCommandSource(), "effect clear @a");
        } catch (Throwable t) { skip("effect clear (server)", t); }
        try { c.player.clearStatusEffects(); } catch (Throwable t) { skip("effect clear (client)", t); }
    }

    private static HandledScreen<?> hs(MinecraftClient c) {
        return c.currentScreen instanceof HandledScreen ? (HandledScreen<?>) c.currentScreen : null;
    }

    private static void open(MinecraftClient c, Screen s) {
        try { c.setScreen(s); } catch (Throwable t) { skip("open " + s.getClass().getSimpleName(), t); }
    }

    private static void close(MinecraftClient c) { try { c.setScreen(null); } catch (Throwable ignored) {} }

    /** Pin the virtual cursor (GUI px) by writing Mouse.x/y (window px) every frame; parked off-screen when < 0. */
    private static void applyCursor(MinecraftClient c) {
        try {
            net.minecraft.client.util.Window win = c.getWindow();
            double sx = (double) win.getWidth() / win.getScaledWidth(), sy = (double) win.getHeight() / win.getScaledHeight();
            double gx = hx < 0 ? -4000 : hx, gy = hy < 0 ? -4000 : hy;
            setMouseField(c.mouse, "x", gx * sx);
            setMouseField(c.mouse, "y", gy * sy);
        } catch (Throwable ignored) {}
    }

    private static void setMouseField(net.minecraft.client.Mouse mouse, String name, double value) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.Mouse.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setDouble(mouse, value);
        } catch (Throwable ignored) {}
    }

    private static Object getO(Object o, Class<?> owner, String field) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object getStatic(Class<?> owner, String field) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    private static String fmt(float v) { return Float.isNaN(v) ? "NaN" : String.format(Locale.ROOT, "%.2f", v); }

    private static void say(String s) { System.out.println("[S1mp1e][VERIFY] " + s); }

    private static void skip(String what, Throwable t) { System.out.println("[S1mp1e][VERIFY] skipped " + what + ": " + t); }

    private static void capture(MinecraftClient c, String name) { DevShot.captureShot(c, name); }
}
