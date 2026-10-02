package dev.s1mp1e.client.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.AbstractTextWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.LockButtonWidget;
import net.minecraft.client.gui.widget.OptionListWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * One layout for every vanilla settings page — the look of the Video Settings page ({@code SodiumGlass}, itself the
 * 26.2 settings screen): a glass sidebar of the settings categories with a sliding selection capsule, the page's
 * options as rows in grouped glass cards (label left; iOS switch / value / slide-out slider right), the page title
 * top left and the page's own buttons (Done, …) as capsules bottom right. Same 8 px grid.
 *
 * <p><b>Nothing of vanilla's behaviour is replaced.</b> The widgets are the screen's own (so every option, tooltip,
 * narration, keyboard path and other mods' additions keep working); the shell only decides where they sit and what is
 * painted for them:
 * <ul>
 *   <li>{@code OptionsScreen}: its sub-screen buttons become the sidebar categories (pressing an entry presses that
 *       button), everything else on it (FOV, difficulty, …) is the "General" page;</li>
 *   <li>a {@code GameOptionsScreen} with an {@code OptionListWidget} body: the list's widgets are taken out of the
 *       list (which stays alive but unattached — vanilla still applies its pending values) and become rows;</li>
 *   <li>the key-binds list: each entry's own two buttons become a row, each category a caption above a card;</li>
 *   <li>the language list: each entry becomes a choice row (a click is passed to the entry, so select / double-click
 *       / Done behave as before);</li>
 *   <li>any other list: placed in the content area untouched.</li>
 * </ul>
 * Rows are direct children of the screen positioned every frame, so their hit-tests are their own. A first, invisible
 * child ({@link Pane}) takes the wheel (scrolling) and the sidebar clicks. The page is rebuilt whenever the screen's
 * child list no longer contains that pane (a re-init) and extended when a mod adds a widget later.
 *
 * <p>Text-based on purpose: a row's label and value come from the widget's message ("Name: Value"), a switch is a
 * cycling button whose value reads On / Off — so the same shell fits every version's option widgets.
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

        void s1mp1e$addWidget(ClickableWidget widget);

        void s1mp1e$addPane(Pane pane);
    }

    /** {@code GameOptionsScreen}: the screen it returns to and its option list (null on list-of-its-own screens). */
    public interface OptionsAccess {
        Screen s1mp1e$parent();

        OptionListWidget s1mp1e$body();
    }

    /** {@code OptionListWidget.WidgetEntry}: the one or two widgets of a list row. */
    public interface EntryWidgets {
        List<ClickableWidget> s1mp1e$widgets();
    }

    /** {@code CyclingButtonWidget}: its caption and value-to-text function. */
    public interface CycleAccess {
        Text s1mp1e$caption();

        boolean s1mp1e$captionOmitted();

        Text s1mp1e$valueText();
    }

    /** {@code SliderWidget}: the 0..1 value and whether the thumb is being dragged. */
    public interface SliderAccess {
        double s1mp1e$value();

        boolean s1mp1e$held();

        /** Paint the config-menu slider on the track {@code tx0..tx1} (also arms the pointer mapping for it). */
        void s1mp1e$paintRow(DrawContext context, float tx0, float tx1, float cy, float alpha);
    }

    /** A key-binds list entry: the binding's name and its two buttons. */
    public interface KeyEntry {
        Text s1mp1e$keyName();

        ButtonWidget s1mp1e$editButton();

        ButtonWidget s1mp1e$resetButton();
    }

    /** A key-binds category entry. */
    public interface HeadingEntry {
        Text s1mp1e$heading();
    }

    /** A pick-one list entry (a language). */
    public interface ChoiceEntry {
        Text s1mp1e$choiceLabel();
    }

    // ---- the settings grid (identical to SodiumGlass) ----

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
        final ClickableWidget w;                             // null for a heading
        final int kind;
        ClickableWidget extra;                               // K_KEYS: the reset button
        Text label;                                          // K_KEYS / K_HEADING / K_CHOICE
        Object entry;                                        // K_CHOICE: the list entry
        int h = ROW_H;
        boolean groupStart;
        int gy;                                              // top in content space
        final Fade hover = new Fade(0.0F, 110.0F);
        final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
        final Motion.Spring lift = new Motion.Spring(0.085F, 0.0F);
        boolean placed, lifted;
        dev.s1mp1e.glass.render.TypingAnim roll;             // cycle rows: the value rolls (old up and out, new in)

        Row(ClickableWidget w, int kind) {
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
        final List<ClickableWidget> footer = new ArrayList<>();
        final List<ClickableWidget> notes = new ArrayList<>();
        final List<ButtonWidget> categories = new ArrayList<>();     // MODE_MAIN only
        final IdentityHashMap<Object, Fade> tabHover = new IdentityHashMap<>();
        EntryListWidget<?> list;
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
        return (screen instanceof OptionsScreen || screen instanceof GameOptionsScreen)
                && GlassProgram.ensureReady() && GlassProgram.usable() && GlassProgram.roundUsable();
    }

    // ============================================================================================================
    //  building a page
    // ============================================================================================================

    private static State ensure(Screen screen) {
        Host host = (Host) screen;
        State st = host.s1mp1e$shell();
        List<? extends Element> kids = screen.children();
        if (st == null || !kids.contains(st.pane)) {
            st = harvest(screen);
            host.s1mp1e$shell(st);
            attach(screen, st);
        } else if (kids.size() != st.built) {                // a mod added a widget after init: take it in
            boolean changed = false;
            for (Element e : new ArrayList<>(kids)) {
                if (e == st.pane || !(e instanceof ClickableWidget w) || known(st, w)) continue;
                classify(st, w, screen);
                changed = true;
            }
            if (changed) attach(screen, st);
            else st.built = kids.size();
        }
        return st;
    }

    private static boolean known(State st, ClickableWidget w) {
        if (w == st.list || st.footer.contains(w) || st.notes.contains(w) || st.categories.contains(w)) return true;
        for (Row r : st.rows) if (r.w == w || r.extra == w) return true;
        return false;
    }

    private static State harvest(Screen screen) {
        State st = new State();
        st.screen = screen;
        st.pane = new Pane(st);
        OptionListWidget body = screen instanceof OptionsAccess a ? a.s1mp1e$body() : null;
        EntryListWidget<?> own = null;
        if (screen instanceof OptionsScreen) {
            st.mode = MODE_MAIN;
        } else {
            st.mode = MODE_ROWS;
            if (body == null) {
                for (Element e : screen.children()) {
                    if (e instanceof EntryListWidget<?> l) {
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
        for (Element e : new ArrayList<>(screen.children())) {
            if (e == body || e == own || !(e instanceof ClickableWidget w)) continue;
            classify(st, w, screen);
        }
        if (body != null) {
            boolean breakNext = false;
            for (Object entry : body.children()) {
                if (!(entry instanceof EntryWidgets ew)) continue;
                List<ClickableWidget> ws = ew.s1mp1e$widgets();
                if (ws.isEmpty()) continue;
                boolean single = ws.size() == 1 && ws.get(0).getWidth() >= 300;     // a full-width option: its own card
                boolean first = true;
                for (ClickableWidget w : ws) {
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
    private static boolean adopt(State st, EntryListWidget<?> list) {
        List<Row> out = new ArrayList<>();
        Object selected = list.getSelectedOrNull();
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
            } else if (entry instanceof ChoiceEntry ce && entry instanceof Element el) {
                r = new Row(new Choice(list, el, ce.s1mp1e$choiceLabel()), K_CHOICE);
                r.label = ce.s1mp1e$choiceLabel();
                r.entry = entry;
                if (entry == selected) st.centerOnChoice = true;
            } else {
                return false;
            }
            out.add(r);
        }
        if (out.isEmpty()) return false;
        st.rows.addAll(out);
        st.list = list;                                      // kept (unattached): the screen still talks to it
        return true;
    }

    /** Sort one of the screen's own widgets into the page. */
    private static void classify(State st, ClickableWidget w, Screen screen) {
        if (w instanceof AbstractTextWidget) {
            if (!w.getMessage().equals(screen.getTitle())) st.notes.add(w);         // the title is drawn by the shell
            return;
        }
        boolean plainButton = w.getClass() == ButtonWidget.class;
        if (plainButton && w.getMessage().equals(ScreenTexts.DONE)) {
            st.footer.add(w);
        } else if (st.mode == MODE_MAIN) {
            if (plainButton) st.categories.add((ButtonWidget) w);
            else st.rows.add(new Row(w, kindOf(w)));
        } else if (w instanceof ButtonWidget && !(w instanceof LockButtonWidget)) {
            st.footer.add(st.footer.isEmpty() ? 0 : st.footer.size() - 1, w);       // Done stays the last (rightmost)
        } else if (st.mode == MODE_ROWS) {
            st.rows.add(new Row(w, kindOf(w)));
        } else {
            st.footer.add(0, w);
        }
    }

    private static int kindOf(ClickableWidget w) {
        if (w instanceof SliderWidget) return K_SLIDER;
        if (w instanceof LockButtonWidget) return K_LOCK;
        if (w instanceof CyclingButtonWidget<?> c) {
            return c instanceof CycleAccess ca && ca.s1mp1e$captionOmitted() ? K_NAV : K_CYCLE;
        }
        if (w instanceof PressableWidget) return K_NAV;
        return K_RAW;
    }

    /** Make the screen's children exactly ours: the pane first (wheel + sidebar), then what the page shows. */
    private static void attach(Screen screen, State st) {
        Host host = (Host) screen;
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
        for (ClickableWidget w : st.footer) host.s1mp1e$addWidget(w);
        for (ClickableWidget w : st.notes) host.s1mp1e$addWidget(w);
        st.built = screen.children().size();

        int y = 0;
        for (int i = 0; i < st.rows.size(); i++) {
            Row r = st.rows.get(i);
            if (i > 0 && r.groupStart && r.kind != K_KEYS) y += GAP;     // a heading already separates its card
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
            MinecraftClient mc = MinecraftClient.getInstance();
            try {
                root.init(mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
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
    /** The row whose widget is being rendered right now: its own painting is suppressed (the shell drew the row). */
    private static ClickableWidget suppressed;

    /** Called by the button / slider skins: true = paint nothing for this widget, the shell row stands in for it. */
    public static boolean suppresses(Object widget) {
        return widget == suppressed;
    }

    public static void render(Screen screen, DrawContext ctx, int mx, int my, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        State st = ensure(screen);
        float dt = Math.min(0.05F, st.clock.tick());
        float a = ScreenOpenFade.held() ? 1.0F : Math.max(0.001F, ScreenOpenFade.value(screen));

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
                if (r.kind == K_CHOICE && st.list != null && st.list.getSelectedOrNull() == r.entry) {
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

        screen.renderBackground(ctx, mx, my, delta);
        ctx.draw();                                          // land the blurred, dimmed background
        SceneCapture.grabNow();                              // every glass piece below refracts it

        text(ctx, tr, screen.getTitle(), (sidebar ? M : st.contX0) + 6.0F, TOP + BTN_H / 2.0F - 4.0F, TEXT, a);
        if (sidebar) drawSidebar(ctx, tr, st, rs, mx, my, a, dt);
        if (st.mode == MODE_LIST) {
            if (st.list != null) {
                st.list.setDimensionsAndPosition(Math.round(st.contX1 - st.contX0), Math.round(view),
                        Math.round(st.contX0), Math.round(st.bodyY0));
                st.list.render(ctx, mx, my, delta);
            }
        } else {
            drawRows(ctx, tr, st, mx, my, delta, a, dt);
        }
        drawFooter(ctx, tr, st, mx, my, delta);
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

    private static void drawSidebar(DrawContext ctx, TextRenderer tr, State st, State rs, int mx, int my, float a, float dt) {
        card(st.sideX0, st.bodyY0, st.sideX1, st.bodyY1, a, 0.16F);
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
            ctx.draw();
            ctx.enableScissor(Math.round(st.sideX0), Math.round(st.bodyY0) + 2, Math.round(st.sideX1), Math.round(st.bodyY1) - 2);
        }
        float x0 = st.sideX0 + SIDE_PAD, x1 = st.sideX1 - SIDE_PAD;
        float cy = tabY(st, rs, 0) + TAB_SLIDE.x * ROW_H;
        GlassWidgets.capsule(ctx, x0 + 0.5F, cy + 1.5F, x1 - 0.5F, cy + ROW_H - 1.5F, 1.0F, 0.6F, a, true);
        boolean inSide = my >= st.bodyY0 && my < st.bodyY1;
        int n = tabCount(rs);
        for (int i = 0; i < n; i++) {
            float y = tabY(st, rs, i);
            if (y + ROW_H < st.bodyY0 || y > st.bodyY1) continue;
            ButtonWidget b = i == 0 ? null : rs.categories.get(i - 1);
            boolean enabled = b == null || b.active;
            boolean over = enabled && inSide && mx >= x0 && mx < x1 && my >= y && my < y + ROW_H;
            Fade f = st.tabHover.computeIfAbsent(b == null ? st : b, k -> new Fade(0.0F, 110.0F));
            f.to(over && i != sel ? 1.0F : 0.0F);
            float hv = f.value();
            if (hv > 0.01F) {
                GlassWidgets.fillRound(ctx, x0 + 0.5F, y + 1.5F, x1 - 0.5F, y + ROW_H - 1.5F,
                        (Math.round(hv * a * 0x1C) << 24) | 0xFFFFFF, (ROW_H - 3) / 2.0F);
            }
            float near = Math.max(0.0F, 1.0F - Math.abs(TAB_SLIDE.x - i));
            int rgb = enabled ? HudGlass.lerpArgb(0xFF000000 | 0xD8D8DE, 0xFF000000 | TEXT, near) & 0xFFFFFF : TEXT_OFF;
            text(ctx, tr, tabLabel(b), x0 + 6.0F, y + ROW_H / 2.0F - 4.0F, rgb, enabled ? a : a * 0.6F);
        }
        if (clip) {
            ctx.draw();
            ctx.disableScissor();
        }
    }

    private static String tabLabel(ButtonWidget b) {
        if (b == null) return Text.translatable("stat.generalButton").getString();
        return stripDots(b.getMessage().getString());
    }

    private static String stripDots(String s) {
        String t = s.trim();
        while (t.endsWith(".") || t.endsWith("…")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    /** A sidebar entry was clicked: leave this page the way its Done button would, then open the other one. */
    private static boolean clickTab(State st, double mx, double my) {
        State rs = st.mode == MODE_MAIN ? st : rootState(st.root);
        if (rs == null || rs.categories.isEmpty()) return false;
        float x0 = st.sideX0 + SIDE_PAD, x1 = st.sideX1 - SIDE_PAD;
        if (mx < x0 || mx >= x1) return false;
        int i = (int) Math.floor((my - tabY(st, rs, 0)) / ROW_H);
        if (i < 0 || i >= tabCount(rs)) return false;
        int sel = st.mode == MODE_MAIN ? 0 : rs.active + 1;
        if (i == sel) return true;
        ButtonWidget b = i == 0 ? null : rs.categories.get(i - 1);
        if (b != null && !b.active) return true;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (st.screen instanceof OptionsAccess oa && oa.s1mp1e$body() != null) oa.s1mp1e$body().applyAllPendingValues();
        if (b == null) {
            mc.setScreen(rs.screen);                         // back to "General" (removed() of this page saves)
        } else {
            rs.active = i - 1;
            b.onPress();
        }
        return true;
    }

    // ---- content: rows in grouped cards ----

    private static void drawRows(DrawContext ctx, TextRenderer tr, State st, int mx, int my, float delta, float a, float dt) {
        boolean clip = st.maxScroll > 0.5F;
        if (clip) {
            ctx.draw();
            ctx.enableScissor(Math.round(st.contX0) - 14, Math.round(st.bodyY0), Math.round(st.contX1) + 14, Math.round(st.bodyY1));
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
                card(x0, y0, x1, y1, a, 0.30F);
                int div = (Math.round(a * 0.10F * 255.0F) << 24) | 0xFFFFFF;
                for (int k = i + 1; k < j; k++) {
                    float y = off + st.rows.get(k).gy;
                    GlassWidgets.fill(ctx, x0 + 7.0F, y - 0.5F, x1 - 7.0F, y + 0.5F, div);
                }
            }
            i = j;
        }

        // one value column for every slider on the page (their tracks line up); it only ever widens, eased
        for (Row r : st.rows) {
            if (r.kind != K_SLIDER) continue;
            String m = r.w.getMessage().getString();
            int c = colon(m);
            st.valueCol = Math.max(st.valueCol, Math.max(34.0F, tr.getWidth(c >= 0 ? m.substring(c + 1).trim() : m)));
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
            drawRow(ctx, tr, st, r, x0, x1, y, over, mx, my, delta, a, dt);
        }
        if (clip) {
            ctx.draw();
            ctx.disableScissor();
            float view = st.bodyY1 - st.bodyY0, len = Math.max(16.0F, view * view / st.contentH);
            float ty = st.bodyY0 + (view - len) * (st.scroll / st.maxScroll);
            GlassWidgets.fillRound(ctx, x1 + 3.0F, ty, x1 + 5.0F, ty + len, (Math.round(a * 0.35F * 255.0F) << 24) | 0xFFFFFF, 1.0F);
        }
    }

    private static void drawRow(DrawContext ctx, TextRenderer tr, State st, Row r, int x0, int x1, int y, boolean over,
                                int mx, int my, float delta, float a, float dt) {
        ClickableWidget w = r.w;
        float cy = y + r.h / 2.0F, right = x1 - 6.0F;
        if (r.kind == K_HEADING) {
            text(ctx, tr, r.label, x0 + 7.0F, y + r.h - 10.0F, TEXT_DIM, a);
            return;
        }
        boolean active = w.active;
        boolean held = w instanceof SliderAccess sa && sa.s1mp1e$held();
        boolean rowHover = r.kind != K_RAW && r.kind != K_KEYS && r.kind != K_LOCK;
        r.hover.to(active && rowHover && (over || held) ? 1.0F : 0.0F);
        float hv = r.hover.value();
        if (hv > 0.01F) {
            GlassWidgets.fillRound(ctx, x0 + 3.0F, y + 2.0F, x1 - 3.0F, y + r.h - 2.0F,
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
                if (((CyclingButtonWidget<?>) w).getValue() instanceof Boolean b && value.equals(ScreenTexts.onOrOff(b).getString())) {
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
            case K_LOCK -> label = Text.translatable("difficulty.lock.title").getString();
            case K_KEYS, K_CHOICE -> label = r.label.getString();
            default -> label = "";
        }
        if (!label.isEmpty()) text(ctx, tr, label, x0 + 7.0F, cy - 4.0F, active ? TEXT : TEXT_OFF, a);

        boolean own = true;                                  // the widget paints itself (normal glass skin)
        switch (r.kind) {
            case K_CYCLE -> {
                w.setDimensionsAndPosition(x1 - x0, r.h, x0, y);
                if (isSwitch) drawSwitch(ctx, r, on, right, cy, active ? a : a * 0.4F, dt);
                else rollValue(ctx, tr, r, value, right, cy, x0 + 7 + tr.getWidth(label) + 8, x1, active ? TEXT : TEXT_OFF, a);
                own = false;
            }
            case K_NAV -> {
                w.setDimensionsAndPosition(x1 - x0, r.h, x0, y);
                int c = (Math.round((active ? a : a * 0.5F) * 255.0F) << 24) | TEXT_DIM;
                if (!SfIcons.drawGlyph(ctx, "chevron.right", right - 5.0F, cy - 4.0F, right, cy + 4.0F, c)) {
                    text(ctx, tr, "›", right - tr.getWidth("›"), cy - 4.0F, TEXT_DIM, active ? a : a * 0.5F);
                }
                own = false;
            }
            case K_CHOICE -> {
                w.setDimensionsAndPosition(x1 - x0, r.h, x0, y);
                if (st.list != null && st.list.getSelectedOrNull() == r.entry) {
                    SfIcons.drawGlyph(ctx, "checkmark", right - 8.0F, cy - 4.0F, right, cy + 4.0F,
                            (Math.round(a * 255.0F) << 24) | BLUE);
                }
                own = false;
            }
            case K_SLIDER -> {
                // the config-menu slider: label | track | value. The value column eases to the widest value seen, so
                // the track does not jump while the number changes under a drag. The widget is the track plus the
                // pill's overhang: a click on the label does nothing, a drag follows the drawn pill exactly.
                int vw = tr.getWidth(value);
                float tx1 = right - st.valueShown - 14.0F;
                float tw = clamp((x1 - x0) * 0.30F, 64.0F, 132.0F), tx0 = tx1 - tw;
                w.setDimensionsAndPosition(Math.round(tw) + 18, r.h, Math.round(tx0) - 9, y);
                text(ctx, tr, value, right - vw, cy - 4.0F, active ? TEXT : TEXT_OFF, a);
                ((SliderAccess) w).s1mp1e$paintRow(ctx, tx0, tx1, cy, a);
                own = false;
            }
            case K_LOCK -> {
                // a round glass key with the padlock; the vanilla button sits under it for the click
                float d = 14.0F, bx1 = right, bx0 = right - d;
                w.setDimensionsAndPosition(Math.round(d), Math.round(d), Math.round(bx0), Math.round(cy - d / 2.0F));
                boolean hot = active && w.isHovered();
                GlassWidgets.capsule(ctx, bx0, cy - d / 2.0F, bx1, cy + d / 2.0F, 1.0F, hot ? 0.6F : 0.2F, a, active);
                boolean locked = ((LockButtonWidget) w).isLocked();
                SfIcons.drawGlyph(ctx, locked ? "lock.fill" : "lock.open.fill", bx0 + 3.5F, cy - 4.0F, bx1 - 3.5F, cy + 4.0F,
                        (Math.round((active ? a : a * 0.5F) * 255.0F) << 24) | 0xFFFFFF);
                own = false;
            }
            case K_KEYS -> {
                int bh = r.h - 4, by = y + 2;
                int rw = Math.max(40, tr.getWidth(r.extra.getMessage()) + 16);
                r.extra.setDimensionsAndPosition(rw, bh, Math.round(right) - rw, by);
                w.setDimensionsAndPosition(84, bh, Math.round(right) - rw - 4 - 84, by);
            }
            default -> {
                int ww = Math.min(Math.max(w.getWidth(), 60), (x1 - x0) / 2);
                w.setDimensionsAndPosition(ww, r.h, Math.round(right) - ww, y);
            }
        }

        // the widget's own render keeps its hover / tooltip / narration state alive; for a row the picture is ours
        suppressed = own ? null : w;
        try {
            w.render(ctx, mx, my, delta);
        } finally {
            suppressed = null;
        }
        if (r.extra != null) r.extra.render(ctx, mx, my, delta);
        if (w.isFocused() && !own && !over && !held) focusRing(ctx, x0, y, x1, y + r.h, a);   // keyboard focus only
    }

    private static int colon(String s) {
        int a = s.indexOf(": "), b = s.indexOf('：');
        if (a >= 0 && (b < 0 || a < b)) return a;
        if (b >= 0) return b;
        return s.indexOf(':');
    }

    // ---- footer: the page's own buttons (Done last), bottom right, drawn by the normal glass button skin ----

    private static void drawFooter(DrawContext ctx, TextRenderer tr, State st, int mx, int my, float delta) {
        int x = st.screen.width - M, y = st.screen.height - TOP - BTN_H;
        for (int i = st.footer.size() - 1; i >= 0; i--) {
            ClickableWidget w = st.footer.get(i);
            int bw = Math.max(BTN_W, tr.getWidth(w.getMessage()) + 24);
            w.setDimensionsAndPosition(bw, BTN_H, x - bw, y);
            w.render(ctx, mx, my, delta);
            x -= bw + GAP;
        }
        float nx = st.contX0 + 6.0F;
        for (ClickableWidget w : st.notes) {
            int nw = Math.max(40, Math.min(tr.getWidth(w.getMessage()) + 4, x - Math.round(nx) - GAP));
            w.setDimensionsAndPosition(nw, BTN_H, Math.round(nx), y);
            w.render(ctx, mx, my, delta);
            nx += nw + GAP;
        }
    }

    // ============================================================================================================
    //  pieces (the same ones the Video Settings page is made of)
    // ============================================================================================================

    private static void card(float x0, float y0, float x1, float y1, float alpha, float scrim) {
        if (alpha < 0.004F) return;
        float w = x1 - x0, h = y1 - y0, r = Math.min(R, Math.min(w, h) / 2.0F);
        if (SceneCapture.hasBackdrop()) {
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL, GlassCorners.cornerFrac(w, h, r), 0.0F,
                    alpha, GlassRenderer.FROST_PANEL);
            GlassRenderer.roundRect(x0, y0, x1, y1, r, (Math.round(alpha * scrim * 255.0F) << 24) | 0x1C1C1E);
        } else {
            GlassRenderer.roundRect(x0, y0, x1, y1, r, (Math.round(alpha * 0.58F * 255.0F) << 24) | 0x1C1C1E);
        }
    }

    private static void focusRing(DrawContext ctx, float rx0, float ry0, float rx1, float ry1, float a) {
        int c = (Math.round(a * 0.55F * 255.0F) << 24) | BLUE;
        float x0 = rx0 + 2.0F, y0 = ry0 + 1.0F, x1 = rx1 - 2.0F, y1 = ry1 - 1.0F;
        GlassWidgets.fill(ctx, x0 + 3.0F, y0, x1 - 3.0F, y0 + 1.0F, c);
        GlassWidgets.fill(ctx, x0 + 3.0F, y1 - 1.0F, x1 - 3.0F, y1, c);
        GlassWidgets.fill(ctx, x0, y0 + 3.0F, x0 + 1.0F, y1 - 3.0F, c);
        GlassWidgets.fill(ctx, x1 - 1.0F, y0 + 3.0F, x1, y1 - 3.0F, c);
    }

    private static void drawSwitch(DrawContext ctx, Row s, boolean value, float right, float cy, float alpha, float dt) {
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
        GlassWidgets.fillRound(ctx, x0, y0, x1, y1, ((int) (a * 0.55F) << 24) | OFF_TRACK, r);
        if (pos > 0.003F) GlassWidgets.fillRound(ctx, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);
        float khh = 0.85F * h / 2.0F, khw = khh * 1.55F;
        float tx0 = x0 + 2.0F + khw, tx1 = x1 - 2.0F - khw;
        int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);
        GlassWidgets.knobLens(ctx, tx0 + (tx1 - tx0) * pos, cy, khw, khh, morph, 1.55F, 1.65F, 0.90F, x0, x1, r,
                Float.NaN, band, band, alpha);
    }

    /**
     * A cycle row's value: when it changes, the old value leaves upward and the new one rises in (the same roll the
     * glass cycle buttons have), right-aligned at {@code right}. Any failure falls back to plain text for good.
     */
    private static void rollValue(DrawContext ctx, TextRenderer tr, Row r, String value, float right, float cy,
                                  float clipX0, float clipX1, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8 || value.isEmpty()) return;
        int w = tr.getWidth(value);
        if (r.roll == null) r.roll = new dev.s1mp1e.glass.render.TypingAnim();
        if (!r.roll.broken) {
            try {
                r.roll.extractLabel(ctx, tr, value, Text.literal(value).asOrderedText(), Math.round(right) - w + w / 2,
                        Math.round(cy - 4.0F), Math.round(clipX0), Math.round(clipX1), (a << 24) | (rgb & 0xFFFFFF), false);
                return;
            } catch (Throwable t) {
                r.roll.broken = true;
            }
        }
        ctx.drawText(tr, value, Math.round(right) - w, Math.round(cy - 4.0F), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    private static void text(DrawContext ctx, TextRenderer tr, String s, float x, float y, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8 || s.isEmpty()) return;
        ctx.drawText(tr, s, Math.round(x), Math.round(y), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    private static void text(DrawContext ctx, TextRenderer tr, Text s, float x, float y, int rgb, float alpha) {
        int a = Math.round(clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (a < 8) return;
        ctx.drawText(tr, s, Math.round(x), Math.round(y), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ============================================================================================================
    //  the invisible first child: wheel scrolling and sidebar clicks
    // ============================================================================================================

    public static final class Pane implements Element, Selectable {
        private final State st;

        Pane(State st) {
            this.st = st;
        }

        private boolean overSidebar(double mx, double my) {
            return st.contX0 > st.sideX1 && mx >= st.sideX0 && mx < st.sideX1 && my >= st.bodyY0 && my < st.bodyY1;
        }

        private boolean overContent(double mx, double my) {
            return st.mode != MODE_LIST && mx >= st.contX0 && mx < st.contX1 + 8 && my >= st.bodyY0 && my < st.bodyY1;
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return overSidebar(mx, my) || overContent(mx, my);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
            if (overSidebar(mx, my)) {
                State rs = st.mode == MODE_MAIN ? st : rootState(st.root);
                if (rs != null) rs.sideScroll = clamp(rs.sideScroll - (float) vertical * ROW_H, 0.0F, sideMax(st, rs));
                return true;
            }
            if (!overContent(mx, my)) return false;
            st.scrollTarget = clamp(st.scrollTarget - (float) vertical * ROW_H * 2.0F, 0.0F, st.maxScroll);
            return true;                                     // also keeps the wheel from cycling the row under it
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            return button == 0 && overSidebar(mx, my) && clickTab(st, mx, my);
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public boolean isFocused() {
            return false;
        }

        @Override
        public SelectionType getType() {
            return SelectionType.NONE;
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {
        }
    }

    /** The clickable stand-in for a pick-one list entry: every press is handed to the entry itself. */
    private static final class Choice extends PressableWidget {
        private final Element entry;

        Choice(EntryListWidget<?> list, Element entry, Text label) {
            super(0, 0, 10, ROW_H, label);
            this.entry = entry;
        }

        @Override
        public void onPress() {
            this.entry.mouseClicked(this.getX() + 1, this.getY() + 1, 0);   // select; a second quick one = vanilla's double-click
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            this.appendDefaultNarrations(builder);
        }
    }
}
