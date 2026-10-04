package dev.s1mp1e.glass.asm;

import java.lang.reflect.Field;
import java.util.List;
import java.util.WeakHashMap;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiButtonToggle;
import net.minecraft.client.gui.recipebook.GuiButtonRecipe;
import net.minecraft.client.gui.recipebook.GuiButtonRecipeTab;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;

/**
 * Liquid glass for the recipe book — the 1.12.2 counterpart of mc1211/mc1201's
 * {@code RecipeBookGlassMixin}, {@code RecipeTabGlassMixin},
 * {@code RecipeButtonGlassMixin} and {@code RecipeToggleGlassMixin}. 1.8.9 has no
 * recipe book, so there is no 1.8.9 reference for this file; the design is 26.2's,
 * ported onto 1.12.2's {@code GuiRecipeBook}.
 *
 * <p>All four entry points replace exactly one
 * {@code drawTexturedModalRect(int,int,int,int,int,int)} call — the transformer
 * swaps that {@code INVOKEVIRTUAL} for an {@code INVOKESTATIC} into the matching
 * method here. The receiver is already first on the stack, so each hook takes the
 * owner plus the six blit ints and the stack shape is unchanged; no branches are
 * introduced, so none of the four needs a stack-map frame.
 *
 * <p>Every hook falls back to the vanilla blit whenever the glass pipeline is
 * unusable, no backdrop was grabbed this frame, or anything throws — the recipe
 * book must never end up invisible.
 *
 * <h3>Four sites</h3>
 * <ol>
 *   <li><b>{@link #panel}</b> — {@code GuiRecipeBook.render} ({@code func_191861_a}),
 *       the 147x166 book background. Draws the frosted glass panel plus ONE vertical
 *       glass bar spanning the visible category tabs, with a two-spring sliding pill
 *       on the selected tab (26.2's rig: lead {@link Spring#OMEGA_SNAP}, trail
 *       {@link Spring#OMEGA_MED}). It inherits vanilla's {@code translate(0, 0, 100)}.</li>
 *   <li><b>{@link #tab}</b> — {@code GuiButtonRecipeTab.drawButton}. Draws NOTHING
 *       while the bar is live (the bar replaces the five tab sprites); the tab icons
 *       are vanilla's and keep drawing. 1.12.2's {@code renderIcon} reads
 *       {@code this.x} directly and does not apply the selected {@code -2} shift that
 *       the sprite blit does, so unlike mc1211 there is no icon-centring patch.</li>
 *   <li><b>{@link #cell}</b> — {@code GuiButtonRecipe.drawButton}, one result cell.
 *       Clear refractive glass, lifted when craftable ({@code u == 29}; vanilla adds
 *       25 for uncraftable). It sits inside vanilla's "new recipe" bounce
 *       push/scale, so the cell bounces for free.</li>
 *   <li><b>{@link #toggle}</b> — {@code GuiButtonToggle.drawButton}. A BTN capsule
 *       for the craftable-filter button ONLY, identified by its 26x16 size and its
 *       {@code (152, 41)} sprite origin ({@code GuiRecipeBook.toggleRecipesBtn}).
 *       {@code RecipeBookPage}'s page arrows are {@code GuiButtonToggle} too, but
 *       they are 12x17 at {@code (1, 208)} and their arrow glyph IS the sprite, so
 *       they stay vanilla. {@code GuiButtonRecipeTab} overrides {@code drawButton}
 *       and never reaches this hook.</li>
 * </ol>
 *
 * <p>No {@link PanelGhost} bookkeeping here, deliberately. {@code PanelGhost}'s
 * per-frame rect list is cleared by {@code GlassContainerHandler.beginFrame()} on
 * the container panel path, and {@code GuiInventory.drawScreen} renders the book
 * BEFORE {@code super.drawScreen} in its wide layout — a rect remembered here would
 * either be cleared straight away or, if the container panel path is skipped
 * entirely, accumulate every frame. The container panel's own close-ghost is
 * unaffected.
 */
public final class RecipeBookHook {

    private RecipeBookHook() {}

    /** 26.2's selected/craftable lift (G = 0xD8 -> 1 - 0xD8/255). */
    private static final float LIFT_SELECTED = 0.153f;

    /** The craftable-filter toggle: 26x16 at sprite origin (152, 41), +28 when triggered. */
    private static final int FILTER_W = 26, FILTER_H = 16;
    private static final int FILTER_U = 152, FILTER_U_ON = 152 + 28;

    // -----------------------------------------------------------------------
    // (a) GuiRecipeBook.render -> glass book panel + tab bar
    // -----------------------------------------------------------------------

    /** Per-book animation state; weakly keyed so a closed screen evicts itself. */
    private static final class Book {
        final Fade open = new Fade(0f, PanelGhost.FADE_MS);
        Spring py1, py2;
        long lastNanos;
        int lastTabY = Integer.MIN_VALUE;
        Book() { open.to(1f); }
    }

    private static final WeakHashMap<GuiRecipeBook, Book> BOOKS =
            new WeakHashMap<GuiRecipeBook, Book>();

    /**
     * True while the glass tab bar owns the tab column. Latched by {@link #panel}
     * and read by {@link #tab}, which runs a few instructions later in the very
     * same {@code render} call. The timestamp keeps a stale latch from suppressing
     * a tab sprite drawn from somewhere else entirely.
     */
    private static boolean barActive = false;
    private static long barNanos = 0L;

    public static void panel(GuiRecipeBook self, int x, int y, int u, int v, int w, int h) {
        boolean drew = false;
        barActive = false;
        try {
            if (GlassProgram.ensureReady() && GlassProgram.usable()) {
                // 2026-10-04：沿用 HUD 這一幀拍的「只有世界」背景（26.2 的做法），和容器面板一致；
                // 在暗色遮罩之後重拍會讓配方書跟背包一樣發暗。
                if (!SceneCapture.hasBackdrop()) SceneCapture.forceGrab();
                if (SceneCapture.hasBackdrop()) {
                    Book b = book(self);
                    float fade = b.open.value();
                    GlassRenderer.panel(x, y, x + w, y + h, fade);
                    drew = true;
                    drawTabBar(self, b, fade);
                }
            }
        } catch (Throwable t) {
            warn("recipe book panel", t);
        }
        if (!drew) vanilla(self, x, y, u, v, w, h);
    }

    private static Book book(GuiRecipeBook self) {
        Book b = BOOKS.get(self);
        if (b == null) {
            b = new Book();
            BOOKS.put(self, b);
        }
        return b;
    }

    /**
     * 26.2's tab treatment: the bounding box of the VISIBLE tabs becomes ONE
     * frosted vertical bar, and the selected tab is a liquid-sliding pill driven by
     * two critically damped springs at different stiffnesses (the hotbar rig).
     */
    private static void drawTabBar(GuiRecipeBook self, Book b, float fade) {
        List<?> tabs = tabs(self);
        if (tabs == null || tabs.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int tabH = 27, visible = 0;
        for (int i = 0; i < tabs.size(); i++) {
            Object o = tabs.get(i);
            if (!(o instanceof GuiButton)) continue;
            GuiButton t = (GuiButton) o;
            if (!t.visible) continue;          // hidden tabs park at (0, 0)
            visible++;
            if (t.x < minX) minX = t.x;
            if (t.y < minY) minY = t.y;
            if (t.y + t.height > maxY) maxY = t.y + t.height;
            tabH = t.height;
        }
        if (visible == 0) return;

        // Bar width 27 ([minX+1, minX+28]) inside the 35 px tab sprite; length runs
        // from the first tab's top to the last tab's bottom.
        int barX0 = minX + 1, barX1 = minX + 28;
        int barY0 = minY,     barY1 = maxY;
        // bar 0x80FFFF00 -> frost 0.5, full-pill corner, no lift, opacity = open fade
        GlassRenderer.glass(barX0, barY0, barX1, barY1,
                            GlassRenderer.PAD_PILL, 1.0f, 0f, fade, GlassRenderer.FROST_PANEL);
        barActive = true;
        barNanos = System.nanoTime();

        GuiButton cur = current(self);
        if (cur == null) return;

        float ty = cur.y;
        long now = System.nanoTime();
        float dt = (b.lastNanos == 0L) ? (1f / 60f)
                 : Math.min(0.1f, (now - b.lastNanos) * 1e-9f);
        b.lastNanos = now;
        if (b.py1 == null) {
            b.py1 = new Spring(ty, Spring.OMEGA_SNAP, Spring.DAMPING);
            b.py2 = new Spring(ty, Spring.OMEGA_MED,  Spring.DAMPING);
            b.lastTabY = (int) ty;
        } else if ((int) ty != b.lastTabY) {
            b.py1.setTarget(ty);
            b.py2.setTarget(ty);
            b.lastTabY = (int) ty;
        }
        b.py1.advance(dt);
        b.py2.advance(dt);
        float v1 = b.py1.value(), v2 = b.py2.value();
        if (Float.isNaN(v1) || Float.isInfinite(v1) || Float.isNaN(v2) || Float.isInfinite(v2)) {
            b.py1.snap(ty); b.py2.snap(ty);
            v1 = ty; v2 = ty;
        }
        int py0 = Math.round(Math.min(v1, v2)) + 2;
        int py1 = Math.round(Math.max(v1, v2)) + tabH - 2;
        // pill 0xFFFFD800 -> no frost, full corner, lift 0.153, opacity = open fade
        GlassRenderer.glass(barX0 + 2, py0, barX1 - 2, py1,
                            6f, 1.0f, LIFT_SELECTED, fade, GlassRenderer.FROST_NONE);
    }

    // -----------------------------------------------------------------------
    // (b) GuiButtonRecipeTab.drawButton -> nothing (the bar replaced it)
    // -----------------------------------------------------------------------

    public static void tab(GuiButtonRecipeTab self, int x, int y, int u, int v, int w, int h) {
        try {
            // Only suppress while the bar really drew, this frame. Otherwise the
            // tabs would vanish with nothing standing in for them.
            if (barActive && System.nanoTime() - barNanos < 100_000_000L) return;
        } catch (Throwable t) {
            warn("recipe tab", t);
        }
        vanilla(self, x, y, u, v, w, h);
    }

    // -----------------------------------------------------------------------
    // (c) GuiButtonRecipe.drawButton -> glass result cell
    // -----------------------------------------------------------------------

    public static void cell(GuiButtonRecipe self, int x, int y, int u, int v, int w, int h) {
        try {
            if (GlassProgram.ensureReady() && GlassProgram.usable()
                    && SceneCapture.hasBackdrop()) {
                // vanilla: u = 29 craftable, u = 29 + 25 = 54 uncraftable
                float lift = (u == 29) ? LIFT_SELECTED : 0f;
                GlassRenderer.glass(x, y, x + w, y + h, 6f, 1.0f, lift, 1.0f,
                                    GlassRenderer.FROST_NONE);
                return;
            }
        } catch (Throwable t) {
            warn("recipe cell", t);
        }
        vanilla(self, x, y, u, v, w, h);
    }

    // -----------------------------------------------------------------------
    // (d) GuiButtonToggle.drawButton -> glass capsule, craftable filter only
    // -----------------------------------------------------------------------

    public static void toggle(GuiButtonToggle self, int x, int y, int u, int v, int w, int h) {
        try {
            boolean filter = w == FILTER_W && h == FILTER_H
                          && (u == FILTER_U || u == FILTER_U_ON);
            if (filter && GlassProgram.ensureReady() && GlassProgram.btnUsable()) {
                float lift = self.isStateTriggered() ? LIFT_SELECTED : 0f;
                GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, 1.0f, true);
                return;
            }
        } catch (Throwable t) {
            warn("recipe toggle", t);
        }
        vanilla(self, x, y, u, v, w, h);
    }

    // ---- helpers ----------------------------------------------------------

    /** The vanilla blit we replaced — {@code drawTexturedModalRect} is public on {@code Gui}. */
    private static void vanilla(net.minecraft.client.gui.Gui self,
                                int x, int y, int u, int v, int w, int h) {
        try {
            self.drawTexturedModalRect(x, y, u, v, w, h);
        } catch (Throwable ignored) {
            // nothing sane left to do; better a missing sprite than a crashed GUI
        }
    }

    // GuiRecipeBook.recipeTabs / currentTab are private: cached reflection, MCP
    // name first (dev workspace) then the 1.12.2 SRG name (production).
    private static Field fTabs, fCurrent;
    private static boolean fieldsResolved = false;

    private static void resolveFields() {
        if (fieldsResolved) return;
        fieldsResolved = true;
        fTabs    = field(new String[] { "recipeTabs", "field_193018_j" });
        fCurrent = field(new String[] { "currentTab", "field_191913_x" });
        if (fTabs == null || fCurrent == null) {
            System.out.println("[S1mp1e] GuiRecipeBook.recipeTabs/currentTab not found; "
                             + "recipe-book tab bar disabled");
        }
    }

    private static Field field(String[] names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Field f = GuiRecipeBook.class.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next candidate name
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static List<?> tabs(GuiRecipeBook self) {
        resolveFields();
        if (fTabs == null) return null;
        try {
            Object o = fTabs.get(self);
            return (o instanceof List) ? (List<?>) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static GuiButton current(GuiRecipeBook self) {
        resolveFields();
        if (fCurrent == null) return null;
        try {
            Object o = fCurrent.get(self);
            return (o instanceof GuiButton) ? (GuiButton) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // One line per failing site, so a broken frame can't flood the log.
    private static final java.util.HashSet<String> WARNED = new java.util.HashSet<String>();

    private static void warn(String site, Throwable t) {
        if (WARNED.add(site)) {
            System.out.println("[S1mp1e] " + site + " glass failed, using vanilla: " + t);
        }
    }
}
