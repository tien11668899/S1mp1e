package dev.s1mp1e.client.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.render.EditBoxGlass;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.SfIcons;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.LockIconButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.WorldOptionsScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * One layout for every vanilla settings page — the look of the Video Settings page (the glass restyle of Reese's
 * Sodium Options, {@code RsoGlass}): a glass sidebar of the settings categories with a sliding selection capsule, the
 * page's options as rows in grouped glass cards (label left; iOS switch / value / slide-out slider right), the page
 * title top left and the page's own buttons (Done, …) as capsules bottom right. Same 8 px grid.
 *
 * <p><b>Nothing of vanilla's behaviour is replaced.</b> The widgets are the screen's own (so every option, tooltip,
 * narration, keyboard path and other mods' additions keep working); the shell only decides where they sit and what is
 * painted for them:
 * <ul>
 *   <li>{@code OptionsScreen}: its sub-screen buttons become the sidebar categories (pressing an entry presses that
 *       button), everything else on it (FOV, difficulty, …) is the "General" page;</li>
 *   <li>an {@code OptionsSubScreen} with an {@code OptionsList}: the list's widgets are taken out of the list (which
 *       stays alive but unattached — vanilla still applies its unsaved changes) and become rows, its headers the
 *       captions above the cards;</li>
 *   <li>the key-binds list: each entry's own two buttons become a row, each category a caption above a card;</li>
 *   <li>the language list: each entry becomes a choice row (a click is passed to the entry, so select / double-click
 *       / Done behave as before);</li>
 *   <li>any other list: placed in the content area untouched.</li>
 * </ul>
 * Rows are direct children of the screen positioned every frame, so their hit-tests are their own. An invisible child
 * ({@link Pane}) takes the sidebar clicks; the wheel is taken at {@code MouseHandler} ({@link #wheel}), because a
 * 26.2 screen routes clicks AND the wheel to the first child under the cursor. The page is rebuilt whenever the
 * screen's child list no longer contains that pane (a re-init) and extended when a mod adds a widget later.
 *
 * <p>Text-based on purpose: a row's label and value come from the widget's message ("Name: Value"), a switch is a
 * cycle button whose value reads On / Off. This is the 26.2 form of the shell the other version lines carry.
 * Render thread only.
 */
public final class SettingsShell {

    private SettingsShell() {}

    // ---- what the mixins expose ----

    /** {@code Screen}: the shell state slot and the protected child management. */
    public interface Host {
        State s1mp1e$shell();

        void s1mp1e$shell(State state);

        void s1mp1e$clear();

        void s1mp1e$addWidget(AbstractWidget widget);

        void s1mp1e$addPane(Pane pane);
    }

    /** {@code OptionsSubScreen}: the screen it returns to and its option list (null on list-of-its-own screens). */
    public interface OptionsAccess {
        Screen s1mp1e$parent();

        OptionsList s1mp1e$body();
    }

    /** {@code CycleButton}: its caption and the text of its current value. */
    public interface CycleAccess {
        Component s1mp1e$caption();

        boolean s1mp1e$captionOmitted();

        Component s1mp1e$valueText();
    }

    /** {@code AbstractSliderButton}: the 0..1 value and whether the thumb is being dragged. */
    public interface SliderAccess {
        double s1mp1e$value();

        boolean s1mp1e$held();

        /** Paint the config-menu slider on the track {@code tx0..tx1} (also arms the pointer mapping for it). */
        void s1mp1e$paintRow(GuiGraphicsExtractor g, float tx0, float tx1, float cy, float alpha);
    }

    /** A key-binds list entry: the binding's name and its two buttons. */
    public interface KeyEntry {
        Component s1mp1e$keyName();

        Button s1mp1e$editButton();

        Button s1mp1e$resetButton();
    }

    /** A caption entry (an options-list header, a key-binds category). */
    public interface HeadingEntry {
        Component s1mp1e$heading();
    }

    /** A pick-one list entry (a language). */
    public interface ChoiceEntry {
        Component s1mp1e$choiceLabel();
    }

    // ---- the settings grid (identical to the Video Settings page) ----

    private static final int M = 16, TOP = 19, GAP = 8, ROW_H = 18, BTN_H = 20, BTN_W = 65, SIDE_PAD = 4;
    private static final int KEY_ROW_H = 22, HEADING_H = 14;
    private static final float R = GlassCorners.HOTBAR_RADIUS + 2.0F;
    private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759, BLUE = 0x0A84FF;
    private static final int TEXT = 0xFFFFFF, TEXT_DIM = 0xAEAEB2, TEXT_OFF = 0x8E8E93;

    private static final int MODE_MAIN = 0, MODE_ROWS = 1, MODE_LIST = 2;
    private static final int K_CYCLE = 0, K_SLIDER = 2, K_NAV = 3, K_RAW = 4, K_LOCK = 5, K_KEYS = 6, K_HEADING = 7,
            K_CHOICE = 8;

    /** One row of the page. */
    private static final class Row {
        final AbstractWidget w;                              // null for a heading
        final int kind;
        AbstractWidget extra;                                // K_KEYS: the reset button
        Component label;                                     // K_KEYS / K_HEADING / K_CHOICE
        Object entry;                                        // K_CHOICE: the list entry
        int h = ROW_H;
        boolean groupStart;
        int gy;                                              // top in content space
        final Fade hover = new Fade(0.0F, 110.0F);
        final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
        final Motion.Spring lift = new Motion.Spring(0.085F, 0.0F);
        boolean placed, lifted;
        com.seagull.liquidglass.client.render.TypingAnim roll;   // cycle rows: the value rolls (old up and out, new in)

        Row(AbstractWidget w, int kind) {
            this.w = w;
            this.kind = kind;
        }

        boolean inCard() {
            return kind != K_HEADING;
        }
    }

    /** Per-screen shell state (lives in the screen through {@link Host}). */
    public static final class State {
        Screen screen;
        int mode;
        Pane pane;
        final List<Row> rows = new ArrayList<>();
        final List<AbstractWidget> footer = new ArrayList<>();
        final List<AbstractWidget> notes = new ArrayList<>();
        final List<AbstractWidget> header = new ArrayList<>();      // text fields (a search box): top row, right
        boolean direct;                                              // a page of plain widgets, no list at all
        int listSig;                                                 // what the adopted list held (a search refills it)
        final List<Button> categories = new ArrayList<>();           // MODE_MAIN only
        final IdentityHashMap<Object, Fade> tabHover = new IdentityHashMap<>();
        AbstractSelectionList<?> list;
        int active = -1;                                             // category last opened from this root
        float sideScroll;                                            // the root's sidebar offset (short windows)
        int built;
        int contentH;
        float scroll, scrollTarget, maxScroll;
        boolean centerOnChoice;
        float valueCol, valueShown;                                  // slider rows: widest value on the page / eased
        boolean sideChecked;
        final Motion.Clock clock = new Motion.Clock();
        float sideX0, sideX1, bodyY0, bodyY1, contX0, contX1;
        Screen root;
    }

    /** The settings pages the shell lays out. */
    public static boolean handles(Object screen) {
        return (screen instanceof OptionsScreen || screen instanceof OptionsSubScreen || screen instanceof WorldOptionsScreen)
                && GlassPipeline.ensureReady() && GlassPipeline.usable() && GlassPipeline.roundUsable();
    }

    /** {@link Fade#to} restarts from the old target; retarget from the live value so nothing pops. */
    private static float fade(Fade f, float target) {
        if (f.target() != target) {
            f.snap(f.value());
            f.to(target);
        }
        return f.value();
    }

    // ============================================================================================================
    //  building a page
    // ============================================================================================================

    private static State ensure(Screen screen) {
        Host host = (Host) screen;
        State st = host.s1mp1e$shell();
        List<? extends GuiEventListener> kids = screen.children();
        if (st == null || !kids.contains(st.pane)) {
            st = harvest(screen);
            host.s1mp1e$shell(st);
            attach(screen, st);
        } else if (st.mode == MODE_ROWS && st.list != null && sig(st.list) != st.listSig) {
            // the adopted list was refilled (the language search box): its rows are stale, take the new entries
            st.rows.clear();
            st.scroll = st.scrollTarget = 0.0F;
            adopt(st, st.list);
            attach(screen, st);
        } else if (kids.size() != st.built) {                // a mod added a widget after init: take it in
            boolean changed = false;
            for (GuiEventListener e : new ArrayList<>(kids)) {
                if (e == st.pane || !(e instanceof AbstractWidget w) || known(st, w)) continue;
                classify(st, w, screen);
                changed = true;
            }
            if (changed) attach(screen, st);
            else st.built = kids.size();
        }
        return st;
    }

    private static int sig(AbstractSelectionList<?> list) {
        List<?> c = list.children();
        int n = c.size();
        return n == 0 ? 0 : n * 31 + System.identityHashCode(c.get(0)) + System.identityHashCode(c.get(n - 1));
    }

    private static boolean known(State st, AbstractWidget w) {
        if (w == st.list || st.footer.contains(w) || st.notes.contains(w) || st.categories.contains(w)
                || st.header.contains(w)) return true;
        for (Row r : st.rows) if (r.w == w || r.extra == w) return true;
        return false;
    }

    private static State harvest(Screen screen) {
        State st = new State();
        st.screen = screen;
        st.pane = new Pane(st);
        OptionsList body = screen instanceof OptionsAccess a ? a.s1mp1e$body() : null;
        AbstractSelectionList<?> own = null;
        if (screen instanceof OptionsScreen) {
            st.mode = MODE_MAIN;
        } else {
            st.mode = MODE_ROWS;
            if (body == null) {
                for (GuiEventListener e : screen.children()) {
                    if (e instanceof AbstractSelectionList<?> l) {
                        own = l;
                        break;
                    }
                }
            }
        }
        if (own != null && !adopt(st, own)) {                // a list we cannot turn into rows: leave it a list
            st.mode = MODE_LIST;
            st.list = own;
        }
        st.direct = st.mode == MODE_ROWS && body == null && own == null;
        for (GuiEventListener e : new ArrayList<>(screen.children())) {
            if (e == body || e == own || !(e instanceof AbstractWidget w)) continue;
            classify(st, w, screen);
        }
        if (body != null) {
            boolean breakNext = false;
            for (Object entry : body.children()) {
                if (entry instanceof HeadingEntry he) {
                    Row r = new Row(null, K_HEADING);
                    r.label = he.s1mp1e$heading();
                    r.h = HEADING_H;
                    r.groupStart = true;
                    st.rows.add(r);
                    breakNext = true;
                    continue;
                }
                if (!(entry instanceof ContainerEventHandler ceh)) continue;
                List<AbstractWidget> ws = new ArrayList<>();
                for (GuiEventListener c : ceh.children()) if (c instanceof AbstractWidget w) ws.add(w);
                if (ws.isEmpty()) continue;
                boolean single = ws.size() == 1 && ws.get(0).getWidth() >= 300;     // a full-width option: its own card
                boolean first = true;
                for (AbstractWidget w : ws) {
                    Row r = new Row(w, kindOf(w));
                    r.groupStart = first && (single || breakNext);
                    first = false;
                    st.rows.add(r);
                }
                breakNext = single;
            }
        }
        return st;
    }

    /** Turn a list of the screen's own (key binds, languages) into rows. False = unknown entries, nothing taken. */
    private static boolean adopt(State st, AbstractSelectionList<?> list) {
        List<Row> out = new ArrayList<>();
        Object selected = list.getSelected();
        boolean afterHeading = false;
        for (Object entry : list.children()) {
            Row r;
            if (entry instanceof KeyEntry ke) {
                r = new Row(ke.s1mp1e$editButton(), K_KEYS);
                r.extra = ke.s1mp1e$resetButton();
                r.label = ke.s1mp1e$keyName();
                r.h = KEY_ROW_H;
                r.groupStart = afterHeading;
                afterHeading = false;
            } else if (entry instanceof HeadingEntry he) {
                r = new Row(null, K_HEADING);
                r.label = he.s1mp1e$heading();
                r.h = HEADING_H;
                r.groupStart = true;
                afterHeading = true;
            } else if (entry instanceof ChoiceEntry ce && entry instanceof GuiEventListener el) {
                r = new Row(new Choice(el, ce.s1mp1e$choiceLabel()), K_CHOICE);
                r.label = ce.s1mp1e$choiceLabel();
                r.entry = entry;
                if (entry == selected) st.centerOnChoice = true;
            } else {
                return false;
            }
            out.add(r);
        }
        if (out.isEmpty() && st.list != list) return false;   // (a refilled list may be empty: no match)
        st.rows.addAll(out);
        st.list = list;                                      // kept (unattached): the screen still talks to it
        st.listSig = sig(list);
        return true;
    }

    /** Sort one of the screen's own widgets into the page. */
    private static void classify(State st, AbstractWidget w, Screen screen) {
        if (w instanceof AbstractStringWidget) {
            if (!w.getMessage().equals(screen.getTitle())) st.notes.add(w);         // the title is drawn by the shell
            return;
        }
        if (w instanceof EditBox) {
            st.header.add(w);
            return;
        }
        boolean plainButton = w instanceof Button && !(w instanceof LockIconButton);
        if (plainButton && w.getMessage().equals(CommonComponents.GUI_DONE)) {
            st.footer.add(w);
        } else if (st.direct && plainButton) {
            st.rows.add(new Row(w, K_NAV));                  // on a list-less page a plain button opens a sub-page
        } else if (st.mode == MODE_MAIN) {
            if (plainButton) st.categories.add((Button) w);
            else st.rows.add(new Row(w, kindOf(w)));
        } else if (plainButton) {
            st.footer.add(st.footer.isEmpty() ? 0 : st.footer.size() - 1, w);       // Done stays the last (rightmost)
        } else if (st.mode == MODE_ROWS) {
            st.rows.add(new Row(w, kindOf(w)));
        } else {
            st.footer.add(0, w);
        }
    }

    private static int kindOf(AbstractWidget w) {
        if (w instanceof AbstractSliderButton) return K_SLIDER;
        if (w instanceof LockIconButton) return K_LOCK;
        if (w instanceof CycleButton<?> c) {
            return c instanceof CycleAccess ca && ca.s1mp1e$captionOmitted() ? K_NAV : K_CYCLE;
        }
        if (w instanceof AbstractButton) return K_NAV;
        return K_RAW;
    }

    /** Make the screen's children exactly ours: the pane (sidebar clicks), then what the page shows. */
    private static void attach(Screen screen, State st) {
        Host host = (Host) screen;
        GuiEventListener focused = screen.getFocused();
        host.s1mp1e$clear();
        screen.setFocused(null);
        host.s1mp1e$addPane(st.pane);
        if (st.mode == MODE_LIST) {
            if (st.list != null) host.s1mp1e$addWidget(st.list);
        } else {
            for (Row r : st.rows) {
                if (r.w != null) host.s1mp1e$addWidget(r.w);
                if (r.extra != null) host.s1mp1e$addWidget(r.extra);
            }
        }
        for (AbstractWidget w : st.header) host.s1mp1e$addWidget(w);
        for (AbstractWidget w : st.footer) host.s1mp1e$addWidget(w);
        for (AbstractWidget w : st.notes) host.s1mp1e$addWidget(w);
        st.built = screen.children().size();
        if (focused != null && screen.children().contains(focused)) screen.setFocused(focused);   // typing goes on

        int y = 0;
        for (int i = 0; i < st.rows.size(); i++) {
            Row r = st.rows.get(i);
            if (i > 0 && r.groupStart && (r.kind == K_HEADING || st.rows.get(i - 1).kind != K_HEADING)) y += GAP;
            r.gy = y;
            y += r.h;
        }
        st.contentH = y;
    }

    /** The {@code OptionsScreen} this page was opened from (through any number of sub-pages), or null. */
    private static Screen rootOf(Screen screen) {
        Screen s = screen;
        for (int i = 0; i < 6 && s != null; i++) {
            if (s instanceof OptionsScreen) return s;
            s = s instanceof OptionsAccess a ? a.s1mp1e$parent() : null;
        }
        return null;
    }

    private static State rootState(Screen root) {
        if (root == null) return null;
        State st = ((Host) root).s1mp1e$shell();
        if (st == null || st.categories.isEmpty()) {
            // opened without the options screen ever being shown (another mod's shortcut): build it once, off screen
            Minecraft mc = Minecraft.getInstance();
            try {
                root.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                st = ensure(root);
            } catch (Throwable t) {
                return null;
            }
        }
        return st;
    }

    // ============================================================================================================
    //  frame
    // ============================================================================================================

    private static final Motion.Spring TAB_SLIDE = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
    private static Object tabRoot;
    /** The row whose widget is being extracted right now: its own picture is suppressed (the shell drew the row). */
    private static AbstractWidget suppressed;

    /** Asked by {@code AbstractWidget.extractRenderState}: true = paint nothing, the shell row stands in for it. */
    public static boolean suppresses(Object widget) {
        return widget == suppressed;
    }

    public static void extract(Screen screen, GuiGraphicsExtractor g, int mx, int my, float delta) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        State st = ensure(screen);
        float dt = Math.min(0.05F, st.clock.tick());
        float a = ScreenOpenFade.held() ? 1.0F : Math.max(0.001F, ScreenOpenFade.value(screen));
        GlassWidgets.resetScissorMirror();

        st.root = rootOf(screen);
        State rs = st.mode == MODE_MAIN ? st : rootState(st.root);
        if (st.mode == MODE_MAIN) st.active = -1;
        boolean sidebar = rs != null && !rs.categories.isEmpty();

        int w = screen.width, h = screen.height;
        float sideW = Math.max(96.0F, Math.min(148.0F, w * 0.24F));
        st.sideX0 = M;
        st.sideX1 = M + sideW;
        st.bodyY0 = TOP + BTN_H + GAP;
        st.bodyY1 = h - TOP - BTN_H - GAP;
        st.contX0 = sidebar ? st.sideX1 + GAP : Math.max(M, (w - 460) / 2.0F);
        st.contX1 = sidebar ? w - M : w - Math.max(M, (w - 460) / 2.0F);
        float view = st.bodyY1 - st.bodyY0;
        st.maxScroll = Math.max(0.0F, st.contentH - view);
        if (st.centerOnChoice) {                             // open a pick-one list on the current choice
            st.centerOnChoice = false;
            for (Row r : st.rows) {
                if (r.kind == K_CHOICE && st.list != null && st.list.getSelected() == r.entry) {
                    st.scroll = st.scrollTarget = r.gy + r.h / 2.0F - view / 2.0F;
                }
            }
        }
        st.scrollTarget = clamp(st.scrollTarget, 0.0F, st.maxScroll);
        if (st.scroll != st.scrollTarget) {
            st.scroll += (st.scrollTarget - st.scroll) * (1.0F - (float) Math.exp(-dt / 0.09F));
            if (Math.abs(st.scrollTarget - st.scroll) < 0.25F) st.scroll = st.scrollTarget;
        }
        st.scroll = clamp(st.scroll, 0.0F, st.maxScroll);

        text(g, font, screen.getTitle(), (sidebar ? M : st.contX0) + 6.0F, TOP + BTN_H / 2.0F - 4.0F, TEXT, a);
        int hx = Math.round(st.contX1);
        for (AbstractWidget hw : st.header) {                // a search field: a glass capsule on the title row
            int ww = Math.min(180, Math.round((st.contX1 - st.contX0) * 0.5F));
            hw.setRectangle(ww, BTN_H, hx - ww, TOP);
            EditBoxGlass.frame = true;
            try {
                hw.extractRenderState(g, mx, my, delta);
            } finally {
                EditBoxGlass.frame = false;
            }
            hx -= ww + GAP;
        }
        if (sidebar) drawSidebar(g, font, st, rs, mx, my, a, dt);
        if (st.mode == MODE_LIST) {
            if (st.list != null) {
                st.list.setRectangle(Math.round(st.contX1 - st.contX0), Math.round(view), Math.round(st.contX0),
                        Math.round(st.bodyY0));
                st.list.extractRenderState(g, mx, my, delta);
            }
        } else {
            drawRows(g, font, st, mx, my, delta, a, dt);
        }
        drawFooter(g, font, st, mx, my, delta);
    }

    // ---- sidebar: "General" + the options screen's own sub-screen buttons ----

    private static int tabCount(State rs) {
        return rs.categories.size() + 1;
    }

    private static float tabY(State st, State rs, int i) {
        return st.bodyY0 + SIDE_PAD + i * ROW_H - rs.sideScroll;
    }

    private static float sideMax(State st, State rs) {
        return Math.max(0.0F, tabCount(rs) * ROW_H + 2 * SIDE_PAD - (st.bodyY1 - st.bodyY0));
    }

    private static void drawSidebar(GuiGraphicsExtractor g, Font font, State st, State rs, int mx, int my, float a, float dt) {
        GlassWidgets.panel(g, st.sideX0, st.bodyY0, st.sideX1, st.bodyY1, a, R, 0.16F);
        if (st.mode != MODE_MAIN && rs.active < 0) {         // opened some other way than through the sidebar: guess
            String title = st.screen.getTitle().getString();
            for (int i = 0; i < rs.categories.size(); i++) {
                String label = tabLabel(rs.categories.get(i));
                if (!label.isEmpty() && title.startsWith(label)) {
                    rs.active = i;
                    break;
                }
            }
        }
        int sel = st.mode == MODE_MAIN ? 0 : rs.active + 1;
        if (tabRoot != rs.screen) {
            tabRoot = rs.screen;
            TAB_SLIDE.snap(sel);
        }
        TAB_SLIDE.retarget(sel).update(dt);
        float max = sideMax(st, rs);
        if (!st.sideChecked) {                               // a page opens with its own entry in view
            st.sideChecked = true;
            float top = SIDE_PAD + sel * ROW_H, view = st.bodyY1 - st.bodyY0;
            if (top - rs.sideScroll < 0.0F) rs.sideScroll = top - SIDE_PAD;
            else if (top + ROW_H + SIDE_PAD - rs.sideScroll > view) rs.sideScroll = top + ROW_H + SIDE_PAD - view;
        }
        rs.sideScroll = clamp(rs.sideScroll, 0.0F, max);
        boolean clip = max > 0.5F;
        if (clip) {
            GlassWidgets.enableScissor(g, Math.round(st.sideX0), Math.round(st.bodyY0) + 2, Math.round(st.sideX1),
                    Math.round(st.bodyY1) - 2);
        }
        float x0 = st.sideX0 + SIDE_PAD, x1 = st.sideX1 - SIDE_PAD;
        float cy = tabY(st, rs, 0) + TAB_SLIDE.x * ROW_H;
        GlassWidgets.capsule(g, x0 + 0.5F, cy + 1.5F, x1 - 0.5F, cy + ROW_H - 1.5F, 1.0F, 0.6F, a, true);
        boolean inSide = my >= st.bodyY0 && my < st.bodyY1;
        int n = tabCount(rs);
        for (int i = 0; i < n; i++) {
            float y = tabY(st, rs, i);
            if (y + ROW_H < st.bodyY0 || y > st.bodyY1) continue;
            Button b = i == 0 ? null : rs.categories.get(i - 1);
            boolean enabled = b == null || b.active;
            boolean over = enabled && inSide && mx >= x0 && mx < x1 && my >= y && my < y + ROW_H;
            Fade f = st.tabHover.computeIfAbsent(b == null ? st : b, k -> new Fade(0.0F, 110.0F));
            float hv = fade(f, over && i != sel ? 1.0F : 0.0F);
            if (hv > 0.01F) {
                GlassWidgets.fillRound(g, x0 + 0.5F, y + 1.5F, x1 - 0.5F, y + ROW_H - 1.5F,
                        (Math.round(hv * a * 0x1C) << 24) | 0xFFFFFF, (ROW_H - 3) / 2.0F);
            }
            float near = Math.max(0.0F, 1.0F - Math.abs(TAB_SLIDE.x - i));
            int rgb = enabled ? HudGlass.lerpArgb(0xFF000000 | 0xD8D8DE, 0xFF000000 | TEXT, near) & 0xFFFFFF : TEXT_OFF;
            text(g, font, tabLabel(b), x0 + 6.0F, y + ROW_H / 2.0F - 4.0F, rgb, enabled ? a : a * 0.6F);
        }
        if (clip) GlassWidgets.disableScissor(g);
    }

    private static String tabLabel(Button b) {
        if (b == null) return Component.translatable("stat.generalButton").getString();
        return stripDots(b.getMessage().getString());
    }

    private static String stripDots(String s) {
        String t = s.trim();
        while (t.endsWith(".") || t.endsWith("…")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    /** A sidebar entry was clicked: leave this page the way its Done button would, then open the other one. */
    private static boolean clickTab(State st, MouseButtonEvent event) {
        State rs = st.mode == MODE_MAIN ? st : rootState(st.root);
        if (rs == null || rs.categories.isEmpty()) return false;
        double mx = event.x(), my = event.y();
        float x0 = st.sideX0 + SIDE_PAD, x1 = st.sideX1 - SIDE_PAD;
        if (mx < x0 || mx >= x1) return false;
        int i = (int) Math.floor((my - tabY(st, rs, 0)) / ROW_H);
        if (i < 0 || i >= tabCount(rs)) return false;
        int sel = st.mode == MODE_MAIN ? 0 : rs.active + 1;
        if (i == sel) return true;
        Button b = i == 0 ? null : rs.categories.get(i - 1);
        if (b != null && !b.active) return true;
        Minecraft mc = Minecraft.getInstance();
        AbstractWidget.playButtonClickSound(mc.getSoundManager());
        if (st.screen instanceof OptionsAccess oa && oa.s1mp1e$body() != null) oa.s1mp1e$body().applyUnsavedChanges();
        if (b == null) {
            mc.gui.setScreen(rs.screen);                        // back to "General" (removed() of this page saves)
        } else {
            rs.active = i - 1;
            b.onPress(event);
        }
        return true;
    }

    /** The wheel over a settings page ({@code MouseHandler}): true = taken (also keeps it from cycling a row). */
    public static boolean wheel(Screen screen, double mx, double my, double vertical) {
        if (!handles(screen)) return false;
        State st = ((Host) screen).s1mp1e$shell();
        if (st == null) return false;
        if (st.pane.overSidebar(mx, my)) {
            State rs = st.mode == MODE_MAIN ? st : rootState(st.root);
            if (rs != null) rs.sideScroll = clamp(rs.sideScroll - (float) vertical * ROW_H, 0.0F, sideMax(st, rs));
            return true;
        }
        if (st.mode == MODE_LIST || mx < st.contX0 || mx >= st.contX1 + 8 || my < st.bodyY0 || my >= st.bodyY1) return false;
        st.scrollTarget = clamp(st.scrollTarget - (float) vertical * ROW_H * 2.0F, 0.0F, st.maxScroll);
        return true;
    }

    // ---- content: rows in grouped cards ----

    private static void drawRows(GuiGraphicsExtractor g, Font font, State st, int mx, int my, float delta, float a, float dt) {
        boolean clip = st.maxScroll > 0.5F;
        if (clip) {
            GlassWidgets.enableScissor(g, Math.round(st.contX0) - 14, Math.round(st.bodyY0), Math.round(st.contX1) + 14,
                    Math.round(st.bodyY1));
        }
        int off = Math.round(st.bodyY0) - Math.round(st.scroll);
        int x0 = Math.round(st.contX0), x1 = Math.round(st.contX1);

        // cards: one per run of rows up to the next group start / heading
        int n = st.rows.size();
        for (int i = 0; i < n; ) {
            if (!st.rows.get(i).inCard()) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && st.rows.get(j).inCard() && !st.rows.get(j).groupStart) j++;
            float y0 = off + st.rows.get(i).gy, y1 = off + st.rows.get(j - 1).gy + st.rows.get(j - 1).h;
            if (y1 > st.bodyY0 - 16 && y0 < st.bodyY1 + 16) {
                GlassWidgets.panel(g, x0, y0, x1, y1, a, R, 0.30F);
                int div = (Math.round(a * 0.10F * 255.0F) << 24) | 0xFFFFFF;
                for (int k = i + 1; k < j; k++) {
                    float y = off + st.rows.get(k).gy;
                    GlassWidgets.fill(g, x0 + 7.0F, y - 0.5F, x1 - 7.0F, y + 0.5F, div);
                }
            }
            i = j;
        }

        // one value column for every slider on the page (their tracks line up); it only ever widens, eased
        for (Row r : st.rows) {
            if (r.kind != K_SLIDER) continue;
            String m = r.w.getMessage().getString();
            int c = colon(m);
            st.valueCol = Math.max(st.valueCol, Math.max(34.0F, font.width(c >= 0 ? m.substring(c + 1).trim() : m)));
        }
        st.valueShown = st.valueShown <= 0.0F ? st.valueCol
                : st.valueShown + (st.valueCol - st.valueShown) * (1.0F - (float) Math.exp(-dt / 0.08F));

        boolean inView = mx >= x0 && mx < x1 && my >= st.bodyY0 && my < st.bodyY1;
        for (Row r : st.rows) {
            int y = off + r.gy;
            boolean shown = y + r.h > st.bodyY0 && y < st.bodyY1;
            if (r.w != null) r.w.visible = shown;
            if (r.extra != null) r.extra.visible = shown;
            if (!shown) continue;
            boolean over = inView && my >= y && my < y + r.h;
            drawRow(g, font, st, r, x0, x1, y, over, mx, my, delta, a, dt);
        }
        if (clip) {
            GlassWidgets.disableScissor(g);
            float view = st.bodyY1 - st.bodyY0, len = Math.max(16.0F, view * view / st.contentH);
            float ty = st.bodyY0 + (view - len) * (st.scroll / st.maxScroll);
            GlassWidgets.fillRound(g, x1 + 3.0F, ty, x1 + 5.0F, ty + len, (Math.round(a * 0.35F * 255.0F) << 24) | 0xFFFFFF, 1.0F);
        }
    }

    private static void drawRow(GuiGraphicsExtractor g, Font font, State st, Row r, int x0, int x1, int y, boolean over,
                                int mx, int my, float delta, float a, float dt) {
        AbstractWidget w = r.w;
        float cy = y + r.h / 2.0F, right = x1 - 6.0F;
        if (r.kind == K_HEADING) {
            text(g, font, r.label, x0 + 7.0F, y + r.h - 10.0F, TEXT_DIM, a);
            return;
        }
        boolean active = w.active;
        boolean held = w instanceof SliderAccess sa && sa.s1mp1e$held();
        boolean rowHover = r.kind != K_RAW && r.kind != K_KEYS && r.kind != K_LOCK;
        float hv = fade(r.hover, active && rowHover && (over || held) ? 1.0F : 0.0F);
        if (hv > 0.01F) {
            GlassWidgets.fillRound(g, x0 + 3.0F, y + 2.0F, x1 - 3.0F, y + r.h - 2.0F,
                    (Math.round(hv * a * 0x20) << 24) | 0xFFFFFF, R - 2.0F);
        }

        String msg = w.getMessage().getString();
        String label = msg, value = "";
        boolean isSwitch = false, on = false;
        switch (r.kind) {
            case K_CYCLE -> {
                CycleAccess ca = (CycleAccess) w;
                label = ca.s1mp1e$caption().getString();
                value = ca.s1mp1e$valueText().getString();
                // a switch only when the option really reads On / Off ("Sneak: Toggle / Hold" is a boolean too)
                if (((CycleButton<?>) w).getValue() instanceof Boolean b
                        && value.equals(CommonComponents.optionStatus(b).getString())) {
                    isSwitch = true;
                    on = b;
                }
            }
            case K_SLIDER -> {
                int c = colon(msg);
                if (c >= 0) {
                    label = msg.substring(0, c).trim();
                    value = msg.substring(c + 1).trim();
                }
            }
            case K_NAV -> label = stripDots(msg);
            case K_LOCK -> label = Component.translatable("difficulty.lock.title").getString();
            case K_KEYS, K_CHOICE -> label = r.label.getString();
            default -> label = "";
        }
        if (!label.isEmpty()) text(g, font, label, x0 + 7.0F, cy - 4.0F, active ? TEXT : TEXT_OFF, a);

        boolean own = true;                                  // the widget paints itself (normal glass skin)
        switch (r.kind) {
            case K_CYCLE -> {
                w.setRectangle(x1 - x0, r.h, x0, y);
                if (isSwitch) drawSwitch(g, r, on, right, cy, active ? a : a * 0.4F, dt);
                else rollValue(g, font, r, value, right, cy, x0 + 7 + font.width(label) + 8, x1, active ? TEXT : TEXT_OFF, a);
                own = false;
            }
            case K_NAV -> {
                w.setRectangle(x1 - x0, r.h, x0, y);
                int c = (Math.round((active ? a : a * 0.5F) * 255.0F) << 24) | TEXT_DIM;
                if (!SfIcons.drawGlyph(g, "chevron.right", right - 5.0F, cy - 4.0F, right, cy + 4.0F, c)) {
                    text(g, font, "›", right - font.width("›"), cy - 4.0F, TEXT_DIM, active ? a : a * 0.5F);
                }
                own = false;
            }
            case K_CHOICE -> {
                w.setRectangle(x1 - x0, r.h, x0, y);
                if (st.list != null && st.list.getSelected() == r.entry) {
                    SfIcons.drawGlyph(g, "checkmark", right - 8.0F, cy - 4.0F, right, cy + 4.0F,
                            (Math.round(a * 255.0F) << 24) | BLUE);
                }
                own = false;
            }
            case K_SLIDER -> {
                // the config-menu slider: label | track | value. One value column for the page, eased, so no track
                // jumps while a number changes under a drag. The widget is the track plus the pill's overhang: a
                // click on the label does nothing, a drag follows the drawn pill exactly.
                int vw = font.width(value);
                float tx1 = right - st.valueShown - 14.0F;
                float tw = clamp((x1 - x0) * 0.30F, 64.0F, 132.0F), tx0 = tx1 - tw;
                w.setRectangle(Math.round(tw) + 18, r.h, Math.round(tx0) - 9, y);
                text(g, font, value, right - vw, cy - 4.0F, active ? TEXT : TEXT_OFF, a);
                ((SliderAccess) w).s1mp1e$paintRow(g, tx0, tx1, cy, a);
                own = false;
            }
            case K_LOCK -> {
                // a round glass key with the padlock; the vanilla button sits under it for the click
                float d = 14.0F, bx1 = right, bx0 = right - d;
                w.setRectangle(Math.round(d), Math.round(d), Math.round(bx0), Math.round(cy - d / 2.0F));
                boolean hot = active && w.isHovered();
                GlassWidgets.capsule(g, bx0, cy - d / 2.0F, bx1, cy + d / 2.0F, 1.0F, hot ? 0.6F : 0.2F, a, active);
                boolean locked = ((LockIconButton) w).isLocked();
                SfIcons.drawGlyph(g, locked ? "lock.fill" : "lock.open.fill", bx0 + 3.5F, cy - 4.0F, bx1 - 3.5F, cy + 4.0F,
                        (Math.round((active ? a : a * 0.5F) * 255.0F) << 24) | 0xFFFFFF);
                own = false;
            }
            case K_KEYS -> {
                int bh = r.h - 4, by = y + 2;
                int rw = Math.max(40, font.width(r.extra.getMessage()) + 16);
                r.extra.setRectangle(rw, bh, Math.round(right) - rw, by);
                w.setRectangle(84, bh, Math.round(right) - rw - 4 - 84, by);
            }
            default -> {
                int ww = Math.min(Math.max(w.getWidth(), 60), (x1 - x0) / 2);
                w.setRectangle(ww, r.h, Math.round(right) - ww, y);
            }
        }

        // the widget's own extract keeps its hover / tooltip / narration state alive; for a row the picture is ours
        suppressed = own ? null : w;
        try {
            w.extractRenderState(g, mx, my, delta);
        } finally {
            suppressed = null;
        }
        if (r.extra != null) r.extra.extractRenderState(g, mx, my, delta);
        if (w.isFocused() && !own && !over && !held) focusRing(g, x0, y, x1, y + r.h, a);   // keyboard focus only
    }

    private static int colon(String s) {
        int a = s.indexOf(": "), b = s.indexOf('：');
        if (a >= 0 && (b < 0 || a < b)) return a;
        if (b >= 0) return b;
        return s.indexOf(':');
    }

    // ---- footer: the page's own buttons (Done last), bottom right, drawn by the normal glass button skin ----

    private static void drawFooter(GuiGraphicsExtractor g, Font font, State st, int mx, int my, float delta) {
        int x = st.screen.width - M, y = st.screen.height - TOP - BTN_H;
        for (int i = st.footer.size() - 1; i >= 0; i--) {
            AbstractWidget w = st.footer.get(i);
            int bw = Math.max(BTN_W, font.width(w.getMessage()) + 24);
            w.setRectangle(bw, BTN_H, x - bw, y);
            w.extractRenderState(g, mx, my, delta);
            x -= bw + GAP;
        }
        float nx = st.contX0 + 6.0F;
        for (AbstractWidget w : st.notes) {
            int nw = Math.max(40, Math.min(font.width(w.getMessage()) + 4, x - Math.round(nx) - GAP));
            w.setRectangle(nw, BTN_H, Math.round(nx), y);
            w.extractRenderState(g, mx, my, delta);
            nx += nw + GAP;
        }
    }

    // ============================================================================================================
    //  pieces (the same ones the Video Settings page is made of)
    // ============================================================================================================

    private static void focusRing(GuiGraphicsExtractor g, float rx0, float ry0, float rx1, float ry1, float a) {
        int c = (Math.round(a * 0.55F * 255.0F) << 24) | BLUE;
        float x0 = rx0 + 2.0F, y0 = ry0 + 1.0F, x1 = rx1 - 2.0F, y1 = ry1 - 1.0F;
        GlassWidgets.fill(g, x0 + 3.0F, y0, x1 - 3.0F, y0 + 1.0F, c);
        GlassWidgets.fill(g, x0 + 3.0F, y1 - 1.0F, x1 - 3.0F, y1, c);
        GlassWidgets.fill(g, x0, y0 + 3.0F, x0 + 1.0F, y1 - 3.0F, c);
        GlassWidgets.fill(g, x1 - 1.0F, y0 + 3.0F, x1, y1 - 3.0F, c);
    }

    private static void drawSwitch(GuiGraphicsExtractor g, Row s, boolean value, float right, float cy, float alpha, float dt) {
        float want = value ? 1.0F : 0.0F;
        if (!s.placed) {
            s.travel.snap(want);
            s.placed = true;
        }
        if (s.travel.target != want) {
            s.travel.retarget(want);
            s.lifted = true;
            s.lift.tune(0.085F, 0.0F).retarget(1.0F);
        }
        s.travel.update(dt);
        if (s.lifted && Math.abs(s.travel.target - s.travel.x) < 0.08F) {
            s.lifted = false;
            s.lift.tune(Motion.MORPH_OUT_S, 0.0F).retarget(0.0F);
        }
        s.lift.update(dt);

        int a = Math.round(alpha * 255.0F);
        float h = 12.0F, w = 26.0F;
        float x1 = right, x0 = x1 - w, y0 = cy - h / 2.0F, y1 = cy + h / 2.0F, r = h / 2.0F;
        float pos = Motion.clamp01(s.travel.x), morph = Motion.clamp01(s.lift.x);
        GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * 0.55F) << 24) | OFF_TRACK, r);
        if (pos > 0.003F) GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);
        float khh = 0.85F * h / 2.0F, khw = khh * 1.55F;
        float tx0 = x0 + 2.0F + khw, tx1 = x1 - 2.0F - khw;
        int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);
        GlassWidgets.knobLens(g, tx0 + (tx1 - tx0) * pos, cy, khw, khh, morph, 1.55F, 1.65F, 0.90F, x0, x1, r,
                Float.NaN, band, band, alpha);
    }

    /**
     * A cycle row's value: when it changes, the old value leaves upward and the new one rises in (the same roll the
     * glass cycle buttons have), right-aligned at {@code right}. Any failure falls back to plain text for good.
     */
    private static void rollValue(GuiGraphicsExtractor g, Font font, Row r, String value, float right, float cy,
                                  float clipX0, float clipX1, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8 || value.isEmpty()) return;
        int w = font.width(value);
        if (r.roll == null) r.roll = new com.seagull.liquidglass.client.render.TypingAnim();
        if (!r.roll.broken) {
            try {
                r.roll.extractLabel(g, font, value, Component.literal(value).getVisualOrderText(), Math.round(right) - w + w / 2,
                        Math.round(cy - 4.0F), Math.round(clipX0), Math.round(clipX1), (a << 24) | (rgb & 0xFFFFFF), false);
                return;
            } catch (Throwable t) {
                r.roll.broken = true;
            }
        }
        g.text(font, value, Math.round(right) - w, Math.round(cy - 4.0F), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    private static void text(GuiGraphicsExtractor g, Font font, String s, float x, float y, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8 || s.isEmpty()) return;
        g.text(font, s, Math.round(x), Math.round(y), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    private static void text(GuiGraphicsExtractor g, Font font, Component s, float x, float y, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8) return;
        g.text(font, s, Math.round(x), Math.round(y), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ============================================================================================================
    //  the invisible child that takes the sidebar clicks
    // ============================================================================================================

    public static final class Pane implements GuiEventListener, NarratableEntry {
        private final State st;

        Pane(State st) {
            this.st = st;
        }

        boolean overSidebar(double mx, double my) {
            return st.contX0 > st.sideX1 && mx >= st.sideX0 && mx < st.sideX1 && my >= st.bodyY0 && my < st.bodyY1;
        }

        /**
         * A row cut by the body edge keeps its full-height widget, and the rows come before the footer in the child
         * list — so a click on the Done capsule's top could land on the half-hidden row under it. Outside the body the
         * pane takes such a point: it hands the click to the footer (or a note) under it, or drops it.
         */
        private boolean overHiddenRow(double mx, double my) {
            if (st.mode == MODE_LIST || (my >= st.bodyY0 && my < st.bodyY1)) return false;
            for (Row r : st.rows) {
                if ((r.w != null && r.w.visible && r.w.isMouseOver(mx, my))
                        || (r.extra != null && r.extra.visible && r.extra.isMouseOver(mx, my))) return true;
            }
            return false;
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return overSidebar(mx, my) || overHiddenRow(mx, my);   // never over visible content: rows get their own clicks
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0 && overSidebar(event.x(), event.y())) return clickTab(st, event);
            if (overHiddenRow(event.x(), event.y())) {
                for (AbstractWidget w : st.footer) {
                    if (w.visible && w.active && w.isMouseOver(event.x(), event.y())) return w.mouseClicked(event, doubleClick);
                }
                for (AbstractWidget w : st.notes) {
                    if (w.visible && w.active && w.isMouseOver(event.x(), event.y())) return w.mouseClicked(event, doubleClick);
                }
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public boolean isFocused() {
            return false;
        }

        @Override
        public NarrationPriority narrationPriority() {
            return NarrationPriority.NONE;
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
        }
    }

    /** The clickable stand-in for a pick-one list entry: every press is handed to the entry itself. */
    private static final class Choice extends AbstractButton {
        private final GuiEventListener entry;

        Choice(GuiEventListener entry, Component label) {
            super(0, 0, 10, ROW_H, label);
            this.entry = entry;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.entry.mouseClicked(event, doubleClick);     // select; a double click confirms, as in the list
        }

        @Override
        public void onPress(InputWithModifiers input) {
            this.entry.mouseClicked(new MouseButtonEvent(this.getX() + 1, this.getY() + 1,
                    new MouseButtonInfo(0, input.modifiers())), false);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
