package dev.s1mp1e.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.ScreenTransition;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.widget.ModeWidget;
import dev.s1mp1e.client.gui.widget.SliderWidget;
import dev.s1mp1e.client.knife.GloveSkins;
import dev.s1mp1e.client.knife.KnifePack;
import dev.s1mp1e.client.knife.KnifeRenderer;
import dev.s1mp1e.client.knife.KnifeSkins;
import dev.s1mp1e.client.module.KnifeModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CS2-inventory-style locker with two tabs: KNIVES (knife type / finish / wear) and GLOVES (glove model / glove
 * skin / wear). The glass panel sits on the left; the right half stays open so the live first-person viewmodel is
 * the preview — picking a skin composites it and plays CS2's inspect, like inspecting the item in CS2.
 *
 * <p>Motion is the config menu's, not a new vocabulary ({@link Anim}, {@link Fade}, {@link Motion} springs, the
 * glass {@link SliderWidget}): open/close fade; the tab and list highlights slide; a tab or list change fades the
 * grid out, swaps it and brings the tiles back in a short stagger; the selection ring slides between tiles; tiles
 * ease their hover lift and dip on press; a tile whose skin is still compositing shows a thin progress sweep.
 */
public final class KnifeInventoryScreen extends Screen {

    private static final int BLUE = 0xFF0A84FF;
    /** CS2 wear tiers (Factory New ... Battle-Scarred), upper bounds. */
    private static final float[] TIER_MAX = {0.07f, 0.15f, 0.38f, 0.45f, 1.0f};
    private static final String[] TIER_ZH = {"全新出廠", "輕微磨損", "久經沙場", "嚴重磨損", "戰痕累累"};
    private static final int[] NEUTRAL = {0xFF8E8E93, 0xFFAEAEB2, 0xFF8E8E93, 0xFF636366};
    private static final float ROW = 22f, TILE_H = 50f, TILE_GAP = 6f;
    /** grid entrance: per-tile delay and duration (ms), lift (px) */
    private static final float STAGGER_MS = 14f, APPEAR_MS = 170f, APPEAR_LIFT = 5f;

    private final KnifeModule mod = KnifeModule.get();
    private final List<String> grid = new ArrayList<>();
    private final Map<String, int[]> swatch = new HashMap<>();
    private String listedKey = "";
    private boolean gloveTab;

    // ---- motion state ----
    private final Fade openFade = new Fade(0f, 150f);
    private final Fade pageFade = new Fade(1f, 130f);     // whole tab content, on a tab switch
    private final Fade gridFade = new Fade(1f, 120f);     // grid only, on a knife / glove change
    private final Fade subFade = new Fade(1f, 140f), tierFade = new Fade(1f, 120f);
    private final Anim tabSlide = new Anim(0f), listSlide = new Anim(0f), ringCol = new Anim(0f), ringRow = new Anim(0f);
    private final Motion.Clock clock = new Motion.Clock();
    private final Map<Integer, Motion.Spring> hover = new HashMap<>();
    private final Motion.Spring press = new Motion.Spring(0.12f, 0f);
    private int pressedTile = -1;
    private boolean closing, gridSwapPending;
    private int pendingTab = -1;
    private long gridAppear;
    private String sub = "", prevSub = "", tier = "", prevTier = "";

    // ---- widgets (the config menu's own controls) ----
    private SliderWidget wearSlider, seedSlider;
    private Setting seedProxy;
    private Setting wearProxy;
    private String wearKey = "";
    private ModeWidget armsWidget;
    private dev.s1mp1e.client.gui.widget.ToggleWidget allArmsToggle;

    // layout (computed in init)
    private float px0, py0, px1, py1, listX0, listX1, gridX0, gridX1, gridY0, gridY1, wearY, seedY, armsY, allArmsY, tabY;
    private float listScroll, listScrollTarget, gridScroll, gridScrollTarget;

    public KnifeInventoryScreen() { super(Component.literal("CS2 刀庫存")); }

    /** Open straight on the gloves tab. */
    public KnifeInventoryScreen gloves() { gloveTab = true; tabSlide.snap(1f); return this; }

    @Override public boolean isPauseScreen() { return false; }   // the live viewmodel is the preview

    // ---- DevShot hooks: the same paths a click takes ----
    public void testTab(int i) { int cur = gloveTab ? 1 : 0; if (i != cur) { tabSlide.to(i); pendingTab = i; pageFade.to(0f); } }
    public void testLeft(String id) { selectLeft(id); }
    public void testGrid(int i) { if (i < grid.size()) { pressedTile = i; press.tune(0.12f, 0f).retarget(1f); selectGrid(grid.get(i)); } }
    public void testRelease() { press.tune(0.30f, 0f).retarget(0f); }
    private static final boolean LOG = System.getenv("S1MP1E_LOCKER_LOG") != null;

    @Override
    protected void init() {
        px0 = 10; py0 = 10;
        px1 = Math.max(330, this.width * 0.5f);
        py1 = this.height - 10;
        listX0 = px0 + 10; listX1 = listX0 + 104;
        gridX0 = listX1 + 10; gridX1 = px1 - 10;
        tabY = py0 + 30;
        gridY0 = py0 + 56; allArmsY = py1 - 40; armsY = allArmsY - 24;
        layoutRows();
        mod.refreshGloveSkins();
        if (ScreenOpenFade.held()) openFade.snap(1f); else { openFade.snap(0f); openFade.to(1f); }
        listedKey = gridKey();
        rebuildGrid();
        listSlide.snap(leftIndex(leftSelected()));
        snapRing();
        gridAppear = net.minecraft.util.Util.getNanos();
        armsWidget = new ModeWidget(mod.arms);
        allArmsToggle = dev.s1mp1e.client.gui.widget.ToggleWidget.forSetting(mod.allArms);
        syncWearSlider(true);
        seedProxy = Setting.integer("Pattern seed", mod.seed.intValue, 0, 1000);
        seedSlider = new SliderWidget(seedProxy);
    }

    /** Knife tab has a pattern-seed row above the wear row; the glove tab gives that space back to the grid. */
    private void layoutRows() {
        seedY = armsY - 36;
        wearY = gloveTab ? armsY - 36 : seedY - 36;
        gridY1 = wearY - 12;
    }

    /** The seed slider edits a proxy; the real setting (a recomposite) is written when the drag / type-in ends. */
    private void commitSeed() {
        if (seedProxy == null || seedSlider.captures() || seedSlider.editing()) return;
        if (mod.seed.intValue != seedProxy.intValue) {
            mod.seed.setInt(seedProxy.intValue);
            KnifeRenderer.animator().inspect();
        }
    }

    // ---- tab data ------------------------------------------------------------------------------------------

    private String knife() { return mod.knife.modeValue; }
    private String glove() { return mod.gloves.modeValue; }
    private String gridKey() { return gloveTab ? "g|" + glove() : "k|" + knife(); }

    private List<String> leftItems() {
        List<String> out = new ArrayList<>();
        if (gloveTab) {
            for (String g : KnifeModule.GLOVES) out.add(g);
        } else {
            for (String k : KnifeModule.KNIVES) if (KnifePack.hasKnife(k)) out.add(k);
        }
        return out;
    }

    private int leftIndex(String id) { return Math.max(0, leftItems().indexOf(id)); }
    private String leftSelected() { return gloveTab ? glove() : knife(); }

    private String leftName(String id) {
        String n = Lang.mode(id);
        return n == null || n.equals(id) ? id.replace("knife_", "").replace("glove_", "") : n;
    }

    private void selectLeft(String id) {
        if (gloveTab) {
            mod.gloves.setMode(id);
            if (GloveSkins.info(id, mod.gloveSkin.modeValue) == null) mod.gloveSkin.setMode("vanilla");
        } else {
            mod.knife.setMode(id);
            if (KnifeSkins.paintFor(id, mod.skin.modeValue) == null) mod.skin.setMode("vanilla");
        }
        listSlide.to(leftIndex(id));
    }

    private void rebuildGrid() {
        grid.clear();
        grid.add("vanilla");
        if (gloveTab) {
            for (JsonObject o : GloveSkins.skinsFor(glove())) {
                String s = o.get("skin").getAsString();
                grid.add(s);
                swatch.computeIfAbsent("g|" + s, x -> gloveSwatch(o));
            }
        } else {
            JsonObject cat = KnifeSkins.catalog();
            String k = knife();
            if (cat != null && cat.getAsJsonObject("knives").has(k)) {
                for (JsonElement e : cat.getAsJsonObject("knives").getAsJsonArray(k)) {
                    JsonObject o = e.getAsJsonObject();
                    if (!o.get("paint").isJsonNull()) {
                        grid.add(o.get("finish").getAsString());
                        String paint = o.get("paint").getAsString();
                        swatch.computeIfAbsent("k|" + k + "|" + paint, x -> knifeSwatch(k, paint));
                    }
                }
            }
        }
        gridScroll = gridScrollTarget = 0;
        hover.clear();
    }

    private String gridSelected() { return gloveTab ? mod.gloveSkin.modeValue : mod.skin.modeValue; }

    private String gridName(String id) {
        if ("vanilla".equals(id)) return "原廠";
        return gloveTab ? GloveSkins.displayName(glove(), id, true) : KnifeSkins.displayName(id, true);
    }

    private int[] gridSwatch(String id) {
        if ("vanilla".equals(id)) return NEUTRAL;
        int[] s = gloveTab ? swatch.get("g|" + id) : swatch.get("k|" + knife() + "|" + KnifeSkins.paintFor(knife(), id));
        return s != null ? s : NEUTRAL;
    }

    private boolean gridPending(String id) {
        return gloveTab ? GloveSkins.pending(glove(), id, (float) mod.gloveWear.doubleValue)
                : KnifeSkins.pending(knife(), id, (float) mod.wear.doubleValue);
    }

    private void selectGrid(String id) {
        if (gloveTab) {
            mod.gloveSkin.setMode(id);
            float[] r = GloveSkins.wearRange(glove(), id);
            if (mod.gloveWear.doubleValue < r[0] || mod.gloveWear.doubleValue > r[1]) mod.gloveWear.setDouble(r[0] + (r[1] - r[0]) * 0.1f);
        } else {
            mod.skin.setMode(id);
            float[] r = KnifeSkins.wearRange(id);
            if (mod.wear.doubleValue < r[0] || mod.wear.doubleValue > r[1]) mod.wear.setDouble(r[0] + (r[1] - r[0]) * 0.1f);
        }
        int i = grid.indexOf(id), cols = cols();
        ringCol.to(i % cols);
        ringRow.to(i / cols);
        syncWearSlider(true);
        KnifeRenderer.animator().inspect();
    }

    private void snapRing() {
        int i = Math.max(0, grid.indexOf(gridSelected())), cols = cols();
        ringCol.snap(i % cols);
        ringRow.snap(i / cols);
    }

    private float[] wearRange() {
        return gloveTab ? GloveSkins.wearRange(glove(), mod.gloveSkin.modeValue) : KnifeSkins.wearRange(mod.skin.modeValue);
    }

    private Setting wearSetting() { return gloveTab ? mod.gloveWear : mod.wear; }

    /**
     * The wear slider edits a proxy clamped to the selected skin's own range; the real setting (which triggers a
     * composite) is only written when the drag / type-in ends, so a drag doesn't queue a composite per pixel.
     */
    private void syncWearSlider(boolean force) {
        float[] r = wearRange();
        String key = (gloveTab ? "g|" : "k|") + gridSelected() + "|" + r[0] + "|" + r[1];
        if (!force && key.equals(wearKey) && wearSlider != null) return;
        wearKey = key;
        double v = Math.max(r[0], Math.min(r[1], wearSetting().doubleValue));
        wearProxy = Setting.number("Wear", v, r[0], r[1]);
        wearSlider = new SliderWidget(wearProxy);
    }

    private void commitWear() {
        if (wearProxy == null || wearSlider.captures() || wearSlider.editing()) return;
        Setting real = wearSetting();
        if (Math.abs(real.doubleValue - wearProxy.doubleValue) > 1e-6) {
            real.setDouble(wearProxy.doubleValue);
            KnifeRenderer.animator().inspect();
        }
    }

    /** The paint's four colours (CS2 stores linear; shown as sRGB). */
    private static int[] knifeSwatch(String knife, String paint) {
        int[] out = NEUTRAL.clone();
        try {
            Path p = KnifePack.root().resolve("skins").resolve(knife).resolve(paint + ".json");
            JsonObject u = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("uniforms");
            for (int i = 0; i < 4; i++) {
                JsonArray c = u.has("g_vColor" + i) ? u.getAsJsonArray("g_vColor" + i) : null;
                if (c == null) continue;
                out[i] = rgb(c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Glove catalog may carry "swatch": up to 4 sRGB "#rrggbb" colours. */
    private static int[] gloveSwatch(JsonObject o) {
        int[] out = NEUTRAL.clone();
        try {
            if (o.has("swatch")) {
                JsonArray a = o.getAsJsonArray("swatch");
                for (int i = 0; i < 4 && i < a.size(); i++) out[i] = 0xFF000000 | Integer.parseInt(a.get(i).getAsString().replace("#", ""), 16);
                for (int i = a.size(); i < 4 && a.size() > 0; i++) out[i] = out[i % a.size()];
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static int rgb(float r, float g, float b) {
        return 0xFF000000 | srgb(r) << 16 | srgb(g) << 8 | srgb(b);
    }

    private static int srgb(float c) {
        c = Math.max(0f, Math.min(1f, c));
        float s = c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
        return Math.round(s * 255f);
    }

    // ---- render -------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // no full-screen dim: the right half shows the live knife
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        float dt = clock.tick();
        long now = net.minecraft.util.Util.getNanos();
        if (closing) {
            if (openFade.value() <= 0.02f) { super.onClose(); return; }
        } else {
            openFade.to(1f);
        }
        // tab switch: swap once the page has faded out, then fade back in with a fresh grid
        if (pendingTab >= 0 && pageFade.value() <= 0.05f) {
            gloveTab = pendingTab == 1;
            pendingTab = -1;
            layoutRows();
            listScroll = listScrollTarget = 0;
            listedKey = gridKey();
            rebuildGrid();
            listSlide.snap(leftIndex(leftSelected()));
            snapRing();
            syncWearSlider(true);
            gridAppear = now;
            pageFade.to(1f);
        }
        // knife / glove change: fade the grid out, swap, bring the tiles back in
        if (pendingTab < 0 && !gridKey().equals(listedKey)) {
            if (!gridSwapPending) { gridSwapPending = true; gridFade.to(0f); }
            if (gridFade.value() <= 0.05f) {
                listedKey = gridKey();
                rebuildGrid();
                snapRing();
                syncWearSlider(true);
                gridAppear = now;
                gridSwapPending = false;
                gridFade.to(1f);
            }
        }
        syncWearSlider(false);
        commitWear();
        commitSeed();

        GlassWidgets.resetScissorMirror();
        float a = Math.max(0.001f, openFade.value());
        float pa = a * pageFade.value();
        float ga = pa * gridFade.value();
        float k = 1f - (float) Math.exp(-dt / 0.07f);
        listScroll += (listScrollTarget - listScroll) * k;
        gridScroll += (gridScrollTarget - gridScroll) * k;

        if (LOG) System.out.printf(java.util.Locale.ROOT, "[S1mp1e] locker t=%.1f dt=%.2f open=%.3f page=%.3f grid=%.3f tab=%.3f list=%.3f ring=%.3f,%.3f press=%.3f appear=%.0f%n",
                now / 1e6 % 100000, dt * 1000, openFade.value(), pageFade.value(), gridFade.value(), tabSlide.value(), listSlide.value(),
                ringCol.value(), ringRow.value(), press.x, (now - gridAppear) / 1e6);
        GlassWidgets.panel(g, px0, py0, px1, py1, a, GlassCorners.HOTBAR_RADIUS, 0.30f);
        GlassWidgets.label(g, "CS2 庫存", px0 + 12, py0 + 12, 0xF5F5F7, a);

        // subtitle cross-fade
        String s = gloveTab ? leftName(glove()) + " · " + gridName(mod.gloveSkin.modeValue)
                : leftName(knife()) + " · " + gridName(mod.skin.modeValue);
        if (!s.equals(sub)) { prevSub = sub; sub = s; subFade.snap(0f); subFade.to(1f); }
        float sf = subFade.value();
        if (sf < 1f && !prevSub.isEmpty())
            GlassWidgets.label(g, prevSub, px1 - 12 - GlassWidgets.strW(prevSub), py0 + 12, 0xC7C7CC, a * (1f - sf));
        GlassWidgets.label(g, sub, px1 - 12 - GlassWidgets.strW(sub), py0 + 12, 0xC7C7CC, a * sf);

        // tabs: sliding highlight + label colour by proximity (the config menu's tab bar)
        float tp = tabSlide.value();
        float[] t0 = tabRect(0), t1 = tabRect(1);
        float hx0 = t0[0] + (t1[0] - t0[0]) * tp, hx1 = t0[2] + (t1[2] - t0[2]) * tp;
        GlassWidgets.capsule(g, hx0, tabY, hx1, tabY + 18, 1f, 0.7f, a, true);
        drawTabLabel(g, "刀", t0, 1f - Math.min(1f, Math.abs(tp)), a);
        drawTabLabel(g, "手套", t1, 1f - Math.min(1f, Math.abs(tp - 1f)), a);

        // left list: sliding selection pill + label colour by proximity
        List<String> left = leftItems();
        GlassWidgets.enableScissor(g, (int) listX0, (int) gridY0, (int) listX1, (int) py1 - 8);
        float sp = listSlide.value();
        float pillY = gridY0 - listScroll + sp * ROW;
        GlassWidgets.capsule(g, listX0, pillY, listX1, pillY + ROW - 2, 0.5f, 0.7f, pa, true);
        for (int i = 0; i < left.size(); i++) {
            float y = gridY0 - listScroll + i * ROW;
            if (y > py1 || y + ROW < gridY0) continue;
            float prox = Math.max(0f, 1f - Math.abs(sp - i));
            GlassWidgets.label(g, fit(leftName(left.get(i)), listX1 - listX0 - 12), listX0 + 8, y + (ROW - 2) / 2f - GlassWidgets.fontH() / 2f,
                    lerpRGB(0xA1A1A6, 0xFFFFFF, prox), pa);
        }
        GlassWidgets.disableScissor(g);
        float listView = (py1 - 8) - gridY0, listContent = left.size() * ROW;
        GlassWidgets.scrollEdges(g, listX0, gridY0, listX1, py1 - 8, 24f, listScroll / 24f, (Math.max(0, listContent - listView) - listScroll) / 24f, pa);
        scroller(g, "list", listX1 + 3, gridY0, py1 - 8, listScroll, listContent, listView, mouseX, mouseY, pa);

        // skin grid
        int cols = cols();
        float tw = tileW(cols);
        String selG = gridSelected();
        float appearMs = (now - gridAppear) / 1e6f;
        GlassWidgets.enableScissor(g, (int) gridX0, (int) gridY0, (int) gridX1, (int) gridY1);
        press.update(dt);
        // selection: a rounded blue plate under the tiles that slides between them (the list pill's language);
        // its 1.5 px rim reads as a rounded ring around the selected tile, which also tints through its glass
        if (grid.contains(selG)) {
            float rx = gridX0 + ringCol.value() * (tw + TILE_GAP), ry = gridY0 + ringRow.value() * (TILE_H + TILE_GAP) - gridScroll;
            GlassWidgets.fillRound(g, rx - 1.5f, ry - 1.5f, rx + tw + 1.5f, ry + TILE_H + 1.5f,
                    GlassWidgets.scaleAlpha(0xB30A84FF, ga), TILE_H / 2f * 0.25f + 1.5f);
        }
        for (int i = 0; i < grid.size(); i++) {
            String f = grid.get(i);
            float e = easeOut(Motion.clamp01((appearMs - i * STAGGER_MS) / APPEAR_MS));
            float x0 = gridX0 + (i % cols) * (tw + TILE_GAP), y0 = gridY0 + (i / cols) * (TILE_H + TILE_GAP) - gridScroll + (1f - e) * APPEAR_LIFT;
            if (y0 > gridY1 || y0 + TILE_H < gridY0) continue;
            float ta = ga * e;
            boolean sel = f.equals(selG);
            boolean hot = GlassWidgets.inside(mouseX, mouseY, x0, y0, x0 + tw, y0 + TILE_H) && mouseY >= gridY0 && mouseY <= gridY1;
            Motion.Spring hs = hover.computeIfAbsent(i, x -> new Motion.Spring(Motion.HOVER_S, 0f));
            hs.retarget(hot ? 1f : 0f);
            hs.update(dt);
            float hv = Motion.clamp01(hs.x);
            float pr = i == pressedTile ? Motion.clamp01(press.x) : 0f;
            float inset = pr * 0.025f * tw;                 // press dips the tile ~5%
            float bx0 = x0 + inset, by0 = y0 + inset * TILE_H / tw, bx1 = x0 + tw - inset, by1 = y0 + TILE_H - inset * TILE_H / tw;
            GlassWidgets.capsule(g, bx0, by0, bx1, by1, 0.25f, (sel ? 0.75f : 0.25f) + 0.25f * hv, ta, true);
            int[] sw = gridSwatch(f);
            float sx0 = bx0 + 6, sx1 = bx1 - 6, sy0 = by0 + 6, sy1 = by0 + 22;
            float seg = (sx1 - sx0) / 4f;
            for (int c = 0; c < 4; c++) {
                GlassWidgets.fillRound(g, sx0 + c * seg, sy0, sx0 + (c + 1) * seg + (c < 3 ? 0.5f : 0f), sy1,
                        GlassWidgets.scaleAlpha(sw[c], ta), c == 0 || c == 3 ? 4f : 0f);
            }
            GlassWidgets.label(g, fit(gridName(f), tw - 10), bx0 + 6, by0 + 36 - GlassWidgets.fontH() / 2f,
                    lerpRGB(0xC7C7CC, 0xFFFFFF, sel ? 1f : hv), ta);
            if (sel && gridPending(f)) {                    // compositing: thin sweep along the swatch's foot
                float ph = (now / 1e6f % 900f) / 900f;
                float bw = (sx1 - sx0) * 0.35f, bxa = sx0 + (sx1 - sx0 + bw) * ph - bw;
                GlassWidgets.fillRound(g, Math.max(sx0, bxa), sy1 + 2, Math.min(sx1, bxa + bw), sy1 + 3.5f,
                        GlassWidgets.scaleAlpha(0xCCFFFFFF, ta), 0.75f);
            }
        }
        GlassWidgets.disableScissor(g);
        int rows = (grid.size() + cols - 1) / cols;
        float gridView = gridY1 - gridY0, gridContent = rows * (TILE_H + TILE_GAP);
        GlassWidgets.scrollEdges(g, gridX0, gridY0, gridX1, gridY1, 24f, gridScroll / 24f, (Math.max(0, gridContent - gridView) - gridScroll) / 24f, pa);
        scroller(g, "grid", gridX1 + 4, gridY0, gridY1, gridScroll, gridContent, gridView, mouseX, mouseY, pa);
        if (grid.size() <= 1) {
            GlassWidgets.label(g, gloveTab ? "這款手套沒有花色" : "這把刀沒有刀皮", gridX0 + 6, gridY0 + 70, 0x8E8E93, ga);
        }

        // wear: the config menu's glass slider (proxy clamped to this skin's range) + tier cross-fade
        float w = (float) (wearProxy != null ? wearProxy.doubleValue : wearSetting().doubleValue);
        String tr = tier(w);
        if (!tr.equals(tier)) { prevTier = tier; tier = tr; tierFade.snap(0f); tierFade.to(1f); }
        String wl = gloveTab ? "手套磨損" : "磨損";
        GlassWidgets.label(g, wl, gridX0, wearY, 0xC7C7CC, pa);
        float tx = gridX0 + GlassWidgets.strW(wl) + 8, tf = tierFade.value();
        if (tf < 1f && !prevTier.isEmpty()) GlassWidgets.label(g, prevTier, tx, wearY, 0x8E8E93, pa * (1f - tf));
        GlassWidgets.label(g, tier, tx, wearY, 0x8E8E93, pa * tf);
        if (wearSlider != null) {
            wearSlider.setBounds(gridX0, wearY + 11, gridX1, wearY + 27);
            wearSlider.draw(g, mouseX, mouseY, delta, pa);
        }

        // pattern seed (knife tab): CS2's own paint seed, typed or dragged 0-1000
        if (!gloveTab && seedSlider != null) {
            GlassWidgets.label(g, "花紋編號", gridX0, seedY, 0xC7C7CC, pa);
            GlassWidgets.label(g, "與 CS2 同一套編號", gridX0 + GlassWidgets.strW("花紋編號") + 8, seedY, 0x8E8E93, pa);
            seedSlider.setBounds(gridX0, seedY + 11, gridX1, seedY + 27);
            seedSlider.draw(g, mouseX, mouseY, delta, pa);
        }

        // arms: the config menu's mode chip
        GlassWidgets.label(g, "手臂", gridX0, armsY + 9 - GlassWidgets.fontH() / 2f, 0xC7C7CC, pa);
        armsWidget.setBounds(gridX0 + 40, armsY, gridX1, armsY + 18);
        armsWidget.draw(g, mouseX, mouseY, delta, pa);

        // CS gloved arm for the empty hand too (vanilla's bare first-person arm)
        GlassWidgets.label(g, "空手也用 CS 手臂", gridX0, allArmsY + 9 - GlassWidgets.fontH() / 2f, 0xC7C7CC, pa);
        allArmsToggle.setBounds(gridX1 - 40f, allArmsY, gridX1, allArmsY + 18);
        allArmsToggle.draw(g, mouseX, mouseY, delta, pa);

        String hint = KnifeRenderer.holdingKnife(minecraft.player) ? "右側即時預覽 · 點選播放檢視動畫" : "預覽中（拿劍時套用）";
        GlassWidgets.label(g, hint, px0 + 12, py1 - 18, 0x8E8E93, a);
    }

    private void scroller(GuiGraphicsExtractor g, String owner, float right, float top, float bottom, float scroll,
                          float content, float view, int mx, int my, float alpha) {
        if (content <= view + 0.5f) return;
        float track = bottom - top;
        float len = Math.max(AppleScroller.MIN_LEN, track * view / content);
        float knobTop = top + (track - len) * (scroll / (content - view));
        AppleScroller.draw(g, owner + System.identityHashCode(this), right, top, bottom, knobTop, len, scroll,
                AppleScroller.near(mx, my, right, top, bottom), false, alpha);
    }

    private float[] tabRect(int i) {
        float w0 = GlassWidgets.strW("刀") + 28, w1 = GlassWidgets.strW("手套") + 28;
        float x0 = i == 0 ? listX0 : listX0 + w0 + 6;
        return new float[] { x0, tabY, x0 + (i == 0 ? w0 : w1), tabY + 18 };
    }

    private void drawTabLabel(GuiGraphicsExtractor g, String label, float[] r, float prox, float a) {
        GlassWidgets.label(g, label, r[0] + (r[2] - r[0] - GlassWidgets.strW(label)) / 2f, tabY + 9 - GlassWidgets.fontH() / 2f,
                lerpRGB(0x9A9AA0, 0xFFFFFF, prox), a);
    }

    private int cols() { return Math.max(2, (int) ((gridX1 - gridX0 + TILE_GAP) / 96)); }

    private float tileW(int cols) { return (gridX1 - gridX0 - (cols - 1) * TILE_GAP) / cols; }

    private static float easeOut(float t) { float u = 1f - t; return 1f - u * u * u; }

    private static String tier(float w) {
        for (int i = 0; i < TIER_MAX.length; i++) if (w <= TIER_MAX[i]) return TIER_ZH[i];
        return TIER_ZH[4];
    }

    private static String fit(String s, float w) {
        if (GlassWidgets.strW(s) <= w) return s;
        while (s.length() > 1 && GlassWidgets.strW(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static int lerpRGB(int c0, int c1, float t) {
        int r = (int) (((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
        int g = (int) (((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
        int b = (int) ((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

    // ---- input --------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int btn = dev.s1mp1e.client.KeyCodes.legacyButton(event.button());
        try {
            if (wearSlider != null && wearSlider.editing() && !GlassWidgets.inside((int) mx, (int) my, wearSlider.x0, wearSlider.y0, wearSlider.x1, wearSlider.y1))
                wearSlider.loseFocus();
            if (seedSlider != null && seedSlider.editing() && !GlassWidgets.inside((int) mx, (int) my, seedSlider.x0, seedSlider.y0, seedSlider.x1, seedSlider.y1)) {
                seedSlider.loseFocus();
                commitSeed();
            }
            // tabs
            for (int i = 0; i < 2; i++) {
                float[] r = tabRect(i);
                if (GlassWidgets.inside((int) mx, (int) my, r[0], r[1], r[2], r[3])) {
                    int cur = pendingTab >= 0 ? pendingTab : (gloveTab ? 1 : 0);
                    if (i != cur) { tabSlide.to(i); pendingTab = i; pageFade.to(0f); }
                    return true;
                }
            }
            if (pendingTab >= 0) return true;               // mid tab-swap: ignore the half-faded page
            // left list
            if (mx >= listX0 && mx <= listX1 && my >= gridY0 && my <= py1 - 8) {
                List<String> left = leftItems();
                int i = (int) Math.floor((my - gridY0 + listScroll) / ROW);
                if (i >= 0 && i < left.size()) selectLeft(left.get(i));
                return true;
            }
            // grid
            int cols = cols();
            float tw = tileW(cols);
            if (!gridSwapPending && mx >= gridX0 && mx <= gridX1 && my >= gridY0 && my <= gridY1) {
                for (int i = 0; i < grid.size(); i++) {
                    float x0 = gridX0 + (i % cols) * (tw + TILE_GAP), y0 = gridY0 + (i / cols) * (TILE_H + TILE_GAP) - gridScroll;
                    if (mx >= x0 && mx <= x0 + tw && my >= y0 && my <= y0 + TILE_H) {
                        pressedTile = i;
                        press.tune(0.12f, 0f).retarget(1f);
                        selectGrid(grid.get(i));
                        return true;
                    }
                }
            }
            if (wearSlider != null && wearSlider.mouseClickedPrecise(mx, my, btn)) return true;
            if (!gloveTab && seedSlider != null && seedSlider.mouseClickedPrecise(mx, my, btn)) return true;
            if (armsWidget != null && armsWidget.mouseClickedPrecise(mx, my, btn)) return true;
            if (allArmsToggle != null && allArmsToggle.mouseClickedPrecise(mx, my, btn)) return true;
            return super.mouseClicked(event, doubleClick);
        } finally {
            if (wearSlider != null) wearSlider.clickDispatched();
            if (seedSlider != null) seedSlider.clickDispatched();
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (wearSlider != null && wearSlider.captures()) { wearSlider.mouseDraggedPrecise(event.x(), event.y(), dev.s1mp1e.client.KeyCodes.legacyButton(event.button())); return true; }
        if (seedSlider != null && seedSlider.captures()) { seedSlider.mouseDraggedPrecise(event.x(), event.y(), dev.s1mp1e.client.KeyCodes.legacyButton(event.button())); return true; }
        if (allArmsToggle != null && allArmsToggle.captures()) { allArmsToggle.mouseDraggedPrecise(event.x(), event.y(), dev.s1mp1e.client.KeyCodes.legacyButton(event.button())); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressedTile >= 0) { press.tune(0.30f, 0f).retarget(0f); }
        if (allArmsToggle != null && allArmsToggle.captures()) { allArmsToggle.mouseReleased(); S1mp1eConfig.save(); return true; }
        if (seedSlider != null && seedSlider.captures()) {
            seedSlider.mouseReleased(dev.s1mp1e.client.KeyCodes.legacyButton(event.button()));
            commitSeed();                                   // composite once, on release
            return true;
        }
        if (wearSlider != null && wearSlider.captures()) {
            wearSlider.mouseReleased(dev.s1mp1e.client.KeyCodes.legacyButton(event.button()));
            commitWear();                                   // composite once, on release
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mx >= listX0 && mx <= listX1 + 6) {
            float max = Math.max(0, leftItems().size() * ROW - ((py1 - 8) - gridY0));
            listScrollTarget = Math.max(0, Math.min(max, listScrollTarget - (float) scrollY * ROW));
            return true;
        }
        if (mx >= gridX0 && mx <= gridX1 + 8) {
            int rows = (grid.size() + cols() - 1) / cols();
            float max = Math.max(0, rows * (TILE_H + TILE_GAP) - (gridY1 - gridY0));
            gridScrollTarget = Math.max(0, Math.min(max, gridScrollTarget - (float) scrollY * 28f));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (wearSlider != null && wearSlider.editing() && wearSlider.keyPressed(event.key())) { commitWear(); return true; }
        if (seedSlider != null && seedSlider.editing() && seedSlider.keyPressed(event.key())) { commitSeed(); return true; }
        if (mod != null && event.key() == dev.s1mp1e.client.KeyCodes.glfwToSdl(mod.lockerKey.intValue)) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (wearSlider != null && wearSlider.editing() && wearSlider.charTyped((char) event.codepoint())) return true;
        if (seedSlider != null && seedSlider.editing() && seedSlider.charTyped((char) event.codepoint())) return true;
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        if (closing) return;
        if (wearSlider != null && wearSlider.editing()) wearSlider.loseFocus();
        if (seedSlider != null && seedSlider.editing()) seedSlider.loseFocus();
        commitWear();
        commitSeed();
        S1mp1eConfig.save();
        if (ScreenTransition.canDissolve()) { super.onClose(); return; }
        closing = true;
        openFade.to(0f);
    }
}
