package dev.s1mp1e.o.client.gui;

import dev.s1mp1e.o.glass.anim.Fade;
import dev.s1mp1e.o.glass.render.GlassCorners;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.options.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.options.LanguageOptionsScreen;
import net.minecraft.client.gui.widget.OptionButtonWidget;
import net.minecraft.client.gui.widget.OptionSliderWidget;
import net.minecraft.client.gui.screen.options.OptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SoundsScreen;
import net.minecraft.client.gui.screen.ResourcePacksScreen;
import net.minecraft.client.gui.screen.SnooperScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.screen.SkinCustomizationScreen;
import net.minecraft.client.gui.screen.options.ChatOptionsScreen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.options.GameOptions;
import net.minecraft.text.Formatting;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One liquid-glass layout for every vanilla settings page on the 1.8.9 line — the sidebar + grouped
 * cards look of the S1mp1e config screen ({@link S1mp1eConfigScreen}) brought to {@code OptionsScreen},
 * {@code VideoOptionsScreen}, {@code ControlsOptionsScreen}, {@code LanguageOptionsScreen}, {@code ChatOptionsScreen},
 * {@code SoundsScreen}, {@code SkinCustomizationScreen}, {@code ResourcePacksScreen} and
 * {@code SnooperScreen}. A port of {@code versions/mc1144}'s {@code client/gui/SettingsShell}: the same
 * geometry, grouping and motion, re-expressed for 1.8.9's Forge GUI (immediate-mode GL, {@code ButtonWidget}
 * grids and {@code ListWidget} lists instead of the yarn widget model).
 *
 * <p><b>Interception.</b> Unlike the Fabric lines there are no mixins: a coremod transformer splices the
 * head of each options screen's {@code drawScreen} with {@code if (SettingsShell.render(this,mx,my,pt))
 * return;} (so the shell draws instead of vanilla), and {@code Screen.mouseClicked} /
 * {@code handleMouseInput} with gated calls into {@link #mouseClicked} / {@link #handleWheel} so the shell
 * owns clicks and the wheel. Everything the vanilla screen would do on a click — toggling an option,
 * committing a slider, capturing a key, selecting a language, opening a sub-page, Done — is driven here
 * through the screen's own widgets, so saving and behaviour stay vanilla.
 *
 * <p><b>1.8.9 port.</b> Converted from mc1122's SettingsShell by {@code C:/Temp/s1port/189/tools/port_shell.py}:
 * {@code ButtonWidget.x/yPosition} (not x/y), {@code fontRendererObj}, {@code Formatting},
 * {@code GameOptions.Option.isBoolean()}, key names via {@code GameOptions.getKeyName}, the lock
 * button state via {@code isLocked}. The SRG names the reflection uses are the same as 1.12.2 (checked against
 * mcp_stable_22); several are un-named in 1.8.9, i.e. the dev name IS the SRG name, which the SRG-first lookup covers.
 * 1.8.9's main Options page also has "Broadcast Settings" (id 107) and "Super Secret Settings" (id 8675309): they
 * are not categories, so they stay as action rows on the General page.
 *
 * <p><b>Two widget families.</b> 1.8.9 options screens are either a fixed {@code ButtonWidget} grid
 * ({@code buttonList}: Options, Chat, Sounds, Skin) or a scrolling {@code ListWidget} list whose option
 * widgets live inside list entries ({@code VideoOptionsScreen}/{@code OptionListWidget},
 * {@code ControlsOptionsScreen}/{@code ControlsListWidget}, {@code LanguageOptionsScreen}). The shell harvests rows from both:
 * the screen's {@code buttonList}, and — reflectively — the buttons inside any options/key list entry.
 * A row is read by its text exactly as the newer lines do (label / value split on the colon; an iOS
 * switch only when the value reads On/Off), so the same shell fits every version's widgets.
 *
 * <p>Private vanilla members are read with dual MCP/SRG reflection (dev vs. production names); public
 * {@code ButtonWidget} fields (x/y/width/height/displayString/id/enabled/visible) and methods are reobf-mapped
 * so they are used directly. Any failure anywhere returns {@code false} / falls back, so a settings page
 * can never be left broken or textless. EntityRenderer thread only.
 */
public final class SettingsShell {

    private SettingsShell() {}

    // ---- grid (mirrors S1mp1eConfigScreen / mc1144) ----
    private static final float M = 16, TOP = 19, GAP = 8, ROW_H = 18, BTN_H = 20, BTN_W = 65, SIDE_PAD = 4;
    private static final float KEY_ROW_H = 22, HEADING_H = 14;
    private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759, BLUE = 0x0A84FF;
    private static final int TEXT = 0xFFFFFF, TEXT_DIM = 0xAEAEB2, TEXT_OFF = 0x8E8E93;

    private static final int K_CYCLE = 0, K_SLIDER = 2, K_NAV = 3, K_RAW = 4, K_LOCK = 5, K_KEYS = 6, K_HEADING = 7,
            K_CHOICE = 8;

    // ---- the fixed category list (identical sidebar on every settings page) ----
    private static final int CAT_GENERAL = 0, CAT_SKIN = 1, CAT_SOUND = 2, CAT_VIDEO = 3, CAT_CONTROLS = 4,
            CAT_LANGUAGE = 5, CAT_CHAT = 6, CAT_RESPACK = 7, CAT_SNOOPER = 8;
    private static final String[] CAT_KEY = {
            "options.title", "options.skinCustomisation", "options.sounds", "options.video", "options.controls",
            "options.language", "options.chat.title", "options.resourcepack", "options.snooper.view" };

    // ============================================================================================
    //  state
    // ============================================================================================

    private static final class Row {
        final ButtonWidget w;        // null for a heading
        final int kind;
        ButtonWidget extra;          // K_KEYS: reset button
        String label;             // K_KEYS / K_HEADING / K_CHOICE
        Object entry;             // K_KEYS / K_CHOICE: the list entry
        int entryIndex = -1;      // K_CHOICE: list index
        float h = ROW_H;
        boolean groupStart;
        int gy;
        final Fade hover = new Fade(0f, 110f);

        Row(ButtonWidget w, int kind) { this.w = w; this.kind = kind; }
        boolean inCard() { return kind != K_HEADING; }
    }

    private static final class State {
        final Screen screen;
        final List<Row> rows = new ArrayList<Row>();
        final List<ButtonWidget> footer = new ArrayList<ButtonWidget>();
        int selectedCat = -1;
        int built = -1;
        int contentH;
        float scroll, scrollTarget, maxScroll;
        float valueCol, valueShown;
        boolean clipOn;
        final IdentityHashMap<Object, Fade> catHover = new IdentityHashMap<Object, Fade>();
        Object vanillaList;       // ListWidget/EntryListWidget backing this page, or null
        float sideScroll;
        long lastNanos;
        ButtonWidget heldSlider;     // the slider currently being dragged
        float clipX0, clipY0, clipX1, clipY1;   // the content scissor while clipOn
        String footNote;                         // a line vanilla drew by hand (the language warning)
        boolean centerOnChoice;                  // open a pick-one list on the current choice

        State(Screen s) { this.screen = s; }
    }

    private static final Map<Screen, State> STATES = new WeakHashMap<Screen, State>();

    /** The sidebar selection capsule — shared by every settings page, so switching category slides it. */
    private static final Anim CAT_SLIDE = new Anim(0f);
    private static boolean catSnapped;

    /** Per-slider row skin (the config-menu slider of the settings rows). */
    private static final Map<ButtonWidget, VanillaSliderSkin> SKINS = new WeakHashMap<ButtonWidget, VanillaSliderSkin>();
    /** Per-cycle-row value roll. */
    private static final Map<ButtonWidget, dev.s1mp1e.o.glass.render.TypingAnim> ROLLS =
            new WeakHashMap<ButtonWidget, dev.s1mp1e.o.glass.render.TypingAnim>();

    /** DEV-only (DevShot): a virtual pointer in GUI px that stands in for the real mouse when set (NaN = real). */
    public static float devX = Float.NaN, devY = Float.NaN;

    /** DEV-only (DevShot): force the content scroll of the current settings page, for the scrolled shot. */
    public static void devScroll(Screen s, float amount) {
        State st = STATES.get(s);
        if (st != null) { st.scrollTarget = clamp(amount, 0f, st.maxScroll); st.scroll = st.scrollTarget; }
    }

    /**
     * DEV-only (DevShot): centre of the first visible row of a kind on the current page, scrolling it into view.
     * kind: "switch", "cycle" (a non-switch value row), "slider", "keys", "choice". Returns {x, y} in GUI px (for a
     * slider: the pill's current x and the track's y), or null.
     */
    public static int[] devRow(Screen s, String kind) {
        State st = STATES.get(s);
        if (st == null) return null;
        float by0 = TOP + BTN_H + GAP, by1 = s.height - TOP - BTN_H - GAP;
        for (Row r : st.rows) {
            String msg = r.w == null ? "" : (r.w.message == null ? "" : r.w.message);
            int c = colon(msg);
            String value = c >= 0 ? msg.substring(c + 1).trim() : "";
            boolean sw = value.equals(tr("options.on")) || value.equals(tr("options.off"));
            boolean match = ("switch".equals(kind) && r.kind == K_CYCLE && sw)
                    || ("cycle".equals(kind) && r.kind == K_CYCLE && !sw && c >= 0)
                    || ("slider".equals(kind) && r.kind == K_SLIDER)
                    || ("keys".equals(kind) && r.kind == K_KEYS)
                    || ("choice".equals(kind) && r.kind == K_CHOICE);
            if (match && "switch".equals(kind) && r.w instanceof OptionButtonWidget) {
                // never flip an option that reloads resources or changes the window (3D anaglyph, fullscreen, VSync,
                // VBOs): a scripted probe must not disturb the client
                Object opt = field(r.w, OptionButtonWidget.class, dev.s1mp1e.o.util.Names.of("option", "f_58671892"), "enumOptions");
                if (opt == GameOptions.Option.ANAGLYPH || opt == GameOptions.Option.USE_FULLSCREEN
                        || opt == GameOptions.Option.ENABLE_VSYNC || opt == GameOptions.Option.USE_VBO) match = false;
            }
            if (!match) continue;
            float y = by0 - st.scroll + r.gy;
            if (y < by0 || y + r.h > by1) {                // bring it into view
                st.scrollTarget = clamp(r.gy - 8f, 0f, st.maxScroll);
                st.scroll = st.scrollTarget;
                y = by0 - st.scroll + r.gy;
            }
            int cy = Math.round(y + r.h / 2f);
            if (r.kind == K_SLIDER && r.w != null) {
                VanillaSliderSkin k = SKINS.get(r.w);
                float v = sliderValue(r.w);
                int px = Math.round(r.w.x + 4 + v * (r.w.width - 8));
                return new int[] { px, cy, r.w.x + 4, r.w.x + r.w.width - 4 };
            }
            return new int[] { Math.round(s.width - M - 20), cy };
        }
        return null;
    }

    /**
     * DEV-only (DevShot): scroll the page so a row is cut in half by the bottom edge of the content, and return the
     * centre of the footer's last (Done) button — the click that must reach Done, not the half-hidden row.
     */
    public static int[] devStraddle(Screen s) {
        State st = STATES.get(s);
        if (st == null || st.footer.isEmpty()) return null;
        float by0 = TOP + BTN_H + GAP, by1 = s.height - TOP - BTN_H - GAP;
        for (Row r : st.rows) {
            float want = r.gy + r.h / 2f - (by1 - by0);           // this row's middle on the bottom edge
            if (want > 0f && want <= st.maxScroll) { st.scroll = st.scrollTarget = want; break; }
        }
        ButtonWidget done = st.footer.get(st.footer.size() - 1);
        return new int[] { done.x + done.width / 2, done.y + done.height / 2 };
    }

    /** The settings pages the shell lays out. */
    public static boolean handles(Screen s) {
        if (s == null) return false;
        // Resource Packs (two side-by-side pack lists, not option widgets) and Snooper (a read-only text list) have
        // nothing to lay out as rows: they keep their existing glass look (the sidebar still navigates to them).
        if (!(s instanceof OptionsScreen || s instanceof VideoOptionsScreen || s instanceof ControlsOptionsScreen
                || s instanceof LanguageOptionsScreen || s instanceof ChatOptionsScreen || s instanceof SoundsScreen
                || s instanceof SkinCustomizationScreen))
            return false;
        return dev.s1mp1e.o.glass.render.GlassProgram.ensureReady()
                && dev.s1mp1e.o.glass.render.GlassProgram.usable()
                && dev.s1mp1e.o.glass.render.GlassProgram.roundUsable();
    }

    private static int categoryOf(Screen s) {
        if (s instanceof OptionsScreen) return CAT_GENERAL;
        if (s instanceof SkinCustomizationScreen) return CAT_SKIN;
        if (s instanceof SoundsScreen) return CAT_SOUND;
        if (s instanceof VideoOptionsScreen) return CAT_VIDEO;
        if (s instanceof ControlsOptionsScreen) return CAT_CONTROLS;
        if (s instanceof LanguageOptionsScreen) return CAT_LANGUAGE;
        if (s instanceof ChatOptionsScreen) return CAT_CHAT;
        if (s instanceof ResourcePacksScreen) return CAT_RESPACK;
        if (s instanceof SnooperScreen) return CAT_SNOOPER;
        return -1;
    }

    // ============================================================================================
    //  harvest
    // ============================================================================================

    @SuppressWarnings("unchecked")
    private static State ensure(Screen screen) {
        State st = STATES.get(screen);
        List<ButtonWidget> buttons = buttonList(screen);
        int sig = signature(screen, buttons);
        if (st == null || st.built != sig) {
            State old = st;
            st = harvest(screen, buttons);
            st.built = sig;
            if (old != null) { st.scroll = old.scroll; st.scrollTarget = old.scrollTarget; st.valueCol = old.valueCol;
                               st.valueShown = old.valueShown; st.sideScroll = old.sideScroll;
                if (old.heldSlider != null) {
                    for (Row r : st.rows) {
                        if (r.w != null && r.w.id == old.heldSlider.id && r.w.getClass() == old.heldSlider.getClass()) {
                            VanillaSliderSkin k = SKINS.get(old.heldSlider);
                            if (k != null) SKINS.put(r.w, k);       // same pill state, same grab offset
                            // resume the drag WITHOUT a press (a press would map the click point and jump the value):
                            // set the slider's own drag flag (OptionSliderWidget.dragging / the sound slider's pressed)
                            setDragFlag(r.w);
                            st.heldSlider = r.w;
                            break;
                        }
                    }
                }
            }
            STATES.put(screen, st);
        }
        return st;
    }

    /**
     * Identity of what the page is built from: the buttonList elements and the backing list object. A window resize or
     * a resource reload re-runs initGui, which REPLACES the widgets (same count) and the ListWidget list — the page must
     * then be harvested again, or the shell would keep driving the old, dead widgets.
     */
    private static int signature(Screen screen, List<ButtonWidget> buttons) {
        int h = 17;
        if (buttons != null) for (ButtonWidget b : buttons) h = h * 31 + System.identityHashCode(b);
        h = h * 31 + System.identityHashCode(currentList(screen));
        return h;
    }

    private static Object currentList(Screen screen) {
        if (screen instanceof VideoOptionsScreen) return field(screen, VideoOptionsScreen.class, dev.s1mp1e.o.util.Names.of("listWidget", "f_56679197"), "optionsRowList");
        if (screen instanceof ControlsOptionsScreen) return field(screen, ControlsOptionsScreen.class, dev.s1mp1e.o.util.Names.of("controlsList", "f_16993731"), "keyBindingList");
        if (screen instanceof LanguageOptionsScreen) return field(screen, LanguageOptionsScreen.class, dev.s1mp1e.o.util.Names.of("languageSelectionListWidget", "f_48580008"), "list");
        return null;
    }

    private static State harvest(Screen screen, List<ButtonWidget> buttons) {
        State st = new State(screen);
        st.selectedCat = categoryOf(screen);
        st.built = buttons == null ? 0 : buttons.size();

        // 1) the screen's own buttonList: footer (Done / Reset All / Open folder …) vs option rows.
        if (buttons != null) {
            for (ButtonWidget b : new ArrayList<ButtonWidget>(buttons)) {
                if (b == null) continue;
                if (isFooter(screen, b)) st.footer.add(b);
                else if (isNav(screen, b)) { /* nav buttons are the sidebar — dropped */ }
                else st.rows.add(new Row(b, kindOf(b)));
            }
        }

        // Done stays the last (rightmost) footer capsule
        for (int i = 0; i < st.footer.size(); i++) {
            ButtonWidget b = st.footer.get(i);
            if (clean(b.message).equals(clean(doneText())) && i != st.footer.size() - 1) {
                st.footer.remove(i);
                st.footer.add(b);
                break;
            }
        }
        if (screen instanceof LanguageOptionsScreen) {
            st.footNote = "(" + tr("options.languageWarning") + ")";
            st.centerOnChoice = true;
        }

        // 2) a ListWidget-based options / key / language list: harvest its entries into rows.
        harvestList(screen, st);

        layoutRows(st);
        return st;
    }

    /** Pull rows out of VideoOptionsScreen' OptionListWidget / ControlsOptionsScreen' ControlsListWidget / LanguageOptionsScreen's list. */
    private static void harvestList(Screen screen, State st) {
        try {
            if (screen instanceof VideoOptionsScreen) {
                Object list = field(screen, VideoOptionsScreen.class, dev.s1mp1e.o.util.Names.of("listWidget", "f_56679197"), "optionsRowList");
                st.vanillaList = list;
                List<?> rows = (List<?>) field(list, list.getClass(), dev.s1mp1e.o.util.Names.of("entries", "f_58969307"), "options");
                if (rows != null) for (Object row : rows) {
                    ButtonWidget a = (ButtonWidget) field(row, row.getClass(), dev.s1mp1e.o.util.Names.of("left", "f_02513961"), "buttonA");
                    ButtonWidget b = (ButtonWidget) field(row, row.getClass(), dev.s1mp1e.o.util.Names.of("right", "f_11694244"), "buttonB");
                    if (a != null) st.rows.add(new Row(a, kindOf(a)));
                    if (b != null) st.rows.add(new Row(b, kindOf(b)));
                }
            } else if (screen instanceof ControlsOptionsScreen) {
                Object list = field(screen, ControlsOptionsScreen.class, dev.s1mp1e.o.util.Names.of("controlsList", "f_16993731"), "keyBindingList");
                st.vanillaList = list;
                Object[] entries = (Object[]) field(list, list.getClass(), dev.s1mp1e.o.util.Names.of("entries", "f_55584428"), "listEntries");
                if (entries != null) for (Object e : entries) {
                    if (e == null) continue;
                    String cn = e.getClass().getName();
                    if (cn.contains("CategoryEntry")) {
                        Row r = new Row(null, K_HEADING);
                        r.label = String.valueOf(field(e, e.getClass(), dev.s1mp1e.o.util.Names.of("name", "f_71381292"), "labelText"));
                        r.h = HEADING_H;
                        r.groupStart = true;
                        st.rows.add(r);
                    } else if (cn.contains("KeyEntry")) {
                        ButtonWidget change = (ButtonWidget) field(e, e.getClass(), dev.s1mp1e.o.util.Names.of("keyBindingButton", "f_03612561"), "btnChangeKeyBinding");
                        ButtonWidget reset = (ButtonWidget) field(e, e.getClass(), dev.s1mp1e.o.util.Names.of("resetButton", "f_13037609"), "btnReset");
                        Row r = new Row(change, K_KEYS);
                        r.extra = reset;
                        r.entry = e;
                        r.label = String.valueOf(field(e, e.getClass(), dev.s1mp1e.o.util.Names.of("name", "f_28715026"), "keyDesc"));
                        r.h = KEY_ROW_H;
                        st.rows.add(r);
                    }
                }
            } else if (screen instanceof LanguageOptionsScreen) {
                Object list = field(screen, LanguageOptionsScreen.class, dev.s1mp1e.o.util.Names.of("languageSelectionListWidget", "f_48580008"), "list");
                st.vanillaList = list;
                List<String> codes = (List<String>) field(list, list.getClass(), dev.s1mp1e.o.util.Names.of("languageCodes", "f_73959247"), "langCodeList");
                Map<String, ?> langs = (Map<String, ?>) field(list, list.getClass(), dev.s1mp1e.o.util.Names.of("languages", "f_57083869"), "languageMap");
                if (codes != null) for (int i = 0; i < codes.size(); i++) {
                    String code = codes.get(i);
                    Object lang = langs == null ? null : langs.get(code);
                    Row r = new Row(null, K_CHOICE);
                    r.label = lang != null ? lang.toString() : code;
                    r.entry = lang;
                    r.entryIndex = i;
                    r.h = ROW_H;
                    st.rows.add(r);
                }
            } else if (screen instanceof SnooperScreen) {
                st.vanillaList = field(screen, SnooperScreen.class, dev.s1mp1e.o.util.Names.of("snooperList", "f_11890905"), "list");
            }
        } catch (Throwable t) {
            // leave the list harvest empty; the buttonList rows + footer still render.
        }
    }

    /**
     * Push the backing ListWidget/EntryListWidget off-screen and disable it, so the overriding settings
     * screens' own {@code list.mouseClicked / handleMouseInput / drawScreen} become no-ops (the shell
     * draws the list content as rows and routes input itself). drawScreen is already skipped by the
     * render splice; this covers the input forwarders that still run on the subclass.
     */
    private static void neutralizeList(State st) {
        if (st.vanillaList == null) return;
        try {
            invoke(st.vanillaList, st.vanillaList.getClass(), new String[]{dev.s1mp1e.o.util.Names.of("setScrolling", "m_10173350"), "setEnabled"},
                    new Class<?>[]{boolean.class}, Boolean.FALSE);
        } catch (Throwable ignored) {}
        try {
            // setDimensions(width, height, top, bottom) — park it well off-screen so no slot is ever hit.
            invoke(st.vanillaList, st.vanillaList.getClass(), new String[]{dev.s1mp1e.o.util.Names.of("setBounds", "m_70844354"), "setDimensions"},
                    new Class<?>[]{int.class, int.class, int.class, int.class}, 0, 0, -10000, -10000);
        } catch (Throwable ignored) {}
    }

    private static void layoutRows(State st) {
        int y = 0;
        for (int i = 0; i < st.rows.size(); i++) {
            Row r = st.rows.get(i);
            if (i > 0 && r.groupStart && r.kind != K_KEYS) y += GAP;
            r.gy = y;
            y += r.h;
        }
        st.contentH = y;
    }

    // ---- classification ----

    private static boolean isFooter(Screen screen, ButtonWidget b) {
        if (b.getClass() != ButtonWidget.class && !(b instanceof OptionButtonWidget)) {
            // custom slider etc. — never a footer
        }
        String s = b.message == null ? "" : b.message;
        if (s.equals(doneText())) return true;
        // Controls "Reset All" (id 201), ResourcePacks Open folder/Done (id 1/2), plain actions with no colon
        if (screen instanceof ControlsOptionsScreen && b.id == 201) return true;
        if (screen instanceof ResourcePacksScreen && (b.id == 1 || b.id == 2)) return true;
        if (screen instanceof LanguageOptionsScreen && (b.id == 6 || b.id == 100)) return true;
        if (screen instanceof SnooperScreen && b.id == 2) return true;
        return false;
    }

    /** A plain nav button that opens a sub-screen — these become the sidebar, so they are not rows. */
    private static boolean isNav(Screen screen, ButtonWidget b) {
        if (!(screen instanceof OptionsScreen)) return false;
        if (b.getClass() != ButtonWidget.class) return false;   // OptionButtonWidget/slider stay as rows
        switch (b.id) {
            case 110: case 106: case 101: case 100: case 102: case 103: case 105: case 104: return true;
            default: return false;
        }
    }

    private static int kindOf(ButtonWidget b) {
        if (b instanceof net.minecraft.client.gui.widget.LockButtonWidget) return K_LOCK;
        if (b instanceof OptionSliderWidget) return K_SLIDER;
        if (isSoundSlider(b)) return K_SLIDER;
        String s = b.message == null ? "" : b.message;
        if (colon(s) >= 0) return K_CYCLE;
        if (b.getClass() != ButtonWidget.class && b instanceof OptionButtonWidget) return K_CYCLE;
        if (b.getClass() == ButtonWidget.class) return K_NAV;
        return K_RAW;
    }

    private static boolean isSoundSlider(ButtonWidget b) {
        return b.getClass().getName().contains("SoundsScreen");
    }

    // ============================================================================================
    //  frame
    // ============================================================================================

    public static boolean render(Screen screen, int mx, int my, float pt) {
        if (!handles(screen)) return false;
        try {
            renderInner(screen, mx, my, pt);
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] settings shell render failed, vanilla fallback: " + t);
            return false;
        }
    }

    private static void renderInner(Screen screen, int mx, int my, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (!Float.isNaN(devX)) { mx = Math.round(devX); my = Math.round(devY); }
        State st = ensure(screen);
        long now = System.nanoTime();
        float dt = st.lastNanos == 0L ? 0f : Math.min(0.05f, (now - st.lastNanos) / 1.0e9f);
        st.lastNanos = now;
        // value() must run EVERY frame, also while a cross-dissolve holds the fade at 1: it is what registers this
        // screen and starts its open clock; skipping it during the hold made the page re-fade from 0 (one blank
        // frame) the moment the dissolve released the hold.
        float openV = ScreenOpenFade.value(screen);
        float a = ScreenOpenFade.held() ? 1f : Math.max(0.001f, openV);

        int w = screen.width, h = screen.height;
        float sideW = Math.max(96f, Math.min(148f, w * 0.24f));
        float sideX0 = M, sideX1 = M + sideW;
        float bodyY0 = TOP + BTN_H + GAP;
        float bodyY1 = h - TOP - BTN_H - GAP;
        float contX0 = sideX1 + GAP, contX1 = w - M;
        float view = bodyY1 - bodyY0;
        st.maxScroll = Math.max(0f, st.contentH - view);
        if (st.centerOnChoice) {                       // open a pick-one list on the current choice
            st.centerOnChoice = false;
            for (Row r : st.rows) {
                if (r.kind == K_CHOICE && selectedLang(st, r)) {
                    st.scroll = st.scrollTarget = clamp(r.gy + r.h / 2f - view / 2f, 0f, st.maxScroll);
                }
            }
        }
        st.scrollTarget = clamp(st.scrollTarget, 0f, st.maxScroll);
        if (st.scroll != st.scrollTarget) {
            st.scroll = GlassWidgets.approach(st.scroll, st.scrollTarget, dt * 1000f, 90f);
            if (Math.abs(st.scrollTarget - st.scroll) < 0.25f) st.scroll = st.scrollTarget;
        }
        st.scroll = clamp(st.scroll, 0f, st.maxScroll);

        neutralizeList(st);

        screen.renderBackground();
        SceneCapture.forceGrab();

        // page title (top-left, over the sidebar column)
        label(stripDots(title(screen)), sideX0 + 6f, TOP + BTN_H / 2f - 4f, TEXT, a);

        drawSidebar(st, sideX0, sideX1, bodyY0, bodyY1, mx, my, a, dt);
        drawRows(mc, st, contX0, contX1, bodyY0, bodyY1, mx, my, pt, a, dt);
        drawFooter(mc, st, mx, my, pt, a);
    }

    // ---- sidebar ----

    private static void drawSidebar(State st, float x0, float x1, float y0, float y1, int mx, int my,
                                    float a, float dt) {
        card(x0, y0, x1, y1, a, 0.16f);
        int n = CAT_KEY.length;
        int sel = st.selectedCat < 0 ? 0 : st.selectedCat;
        if (!catSnapped) { CAT_SLIDE.snap(sel); catSnapped = true; }
        CAT_SLIDE.to(sel);

        float max = Math.max(0f, n * ROW_H + 2 * SIDE_PAD - (y1 - y0));
        st.sideScroll = clamp(st.sideScroll, 0f, max);
        boolean clip = max > 0.5f;
        if (clip) GlassWidgets.beginScissor(x0, y0 + 2, x1, y1 - 2);
        float ix0 = x0 + SIDE_PAD, ix1 = x1 - SIDE_PAD;
        float cy = y0 + SIDE_PAD + CAT_SLIDE.value() * ROW_H - st.sideScroll;
        GlassWidgets.capsule(ix0 + 0.5f, cy + 1.5f, ix1 - 0.5f, cy + ROW_H - 1.5f, 1f, 0.6f, a, true);
        boolean inSide = mx >= x0 && mx < x1 && my >= y0 && my < y1;
        for (int i = 0; i < n; i++) {
            float y = y0 + SIDE_PAD + i * ROW_H - st.sideScroll;
            if (y + ROW_H < y0 || y > y1) continue;
            boolean over = inSide && mx >= ix0 && mx < ix1 && my >= y && my < y + ROW_H;
            Fade f = st.catHover.get(Integer.valueOf(i));
            if (f == null) { f = new Fade(0f, 110f); st.catHover.put(Integer.valueOf(i), f); }
            f.to(over && i != sel ? 1f : 0f);
            float hv = f.value();
            if (hv > 0.01f)
                GlassWidgets.fillRound(ix0 + 0.5f, y + 1.5f, ix1 - 0.5f, y + ROW_H - 1.5f,
                        (Math.round(hv * a * 0x1C) << 24) | 0xFFFFFF, (ROW_H - 3) / 2f);
            float near = Math.max(0f, 1f - Math.abs(CAT_SLIDE.value() - i));
            int rgb = lerpRGB(0xD8D8DE, 0xFFFFFF, near);
            label(catLabel(i), ix0 + 6f, y + ROW_H / 2f - 4f, rgb, a);
        }
        if (clip) GlassWidgets.endScissor();
    }

    private static String catLabel(int i) {
        if (i == CAT_GENERAL) return stripDots(tr("stat.generalButton"));
        return stripDots(tr(CAT_KEY[i]));
    }

    // ---- rows ----

    private static void drawRows(Minecraft mc, State st, float cx0, float cx1, float by0, float by1,
                                 int mx, int my, float pt, float a, float dt) {
        int x0 = Math.round(cx0), x1 = Math.round(cx1);
        boolean clip = st.maxScroll > 0.5f;
        st.clipOn = clip;
        st.clipX0 = cx0 - 14; st.clipY0 = by0; st.clipX1 = cx1 + 14; st.clipY1 = by1;
        if (clip) GlassWidgets.beginScissor(st.clipX0, st.clipY0, st.clipX1, st.clipY1);
        int off = Math.round(by0) - Math.round(st.scroll);

        // cards: one per run of rows up to the next group start / heading
        int n = st.rows.size();
        for (int i = 0; i < n; ) {
            if (!st.rows.get(i).inCard()) { i++; continue; }
            int j = i + 1;
            while (j < n && st.rows.get(j).inCard() && !st.rows.get(j).groupStart) j++;
            float cy0 = off + st.rows.get(i).gy, cy1 = off + st.rows.get(j - 1).gy + st.rows.get(j - 1).h;
            if (cy1 > by0 - 16 && cy0 < by1 + 16) {
                card(x0, cy0, x1, cy1, a, 0.30f);
                int div = (Math.round(a * 0.10f * 255f) << 24) | 0xFFFFFF;
                for (int k = i + 1; k < j; k++) {
                    float y = off + st.rows.get(k).gy;
                    GlassWidgets.drawRect(x0 + 7f, y - 0.5f, x1 - 7f, y + 0.5f, div);
                }
            }
            i = j;
        }
        GlassWidgets.resetColorCache();

        // one value column for every slider on the page (tracks line up); only widens, eased
        float want = 0f;
        for (Row r : st.rows) {
            if (r.kind != K_SLIDER || r.w == null) continue;
            String m = r.w.message == null ? "" : r.w.message;
            int c = colon(m);
            want = Math.max(want, Math.max(34f, sw(c >= 0 ? m.substring(c + 1).trim() : m)));
        }
        st.valueCol = Math.max(st.valueCol, want);
        st.valueShown = st.valueShown <= 0f ? st.valueCol
                : GlassWidgets.approach(st.valueShown, st.valueCol, dt * 1000f, 80f);

        boolean inView = mx >= x0 && mx < x1 && my >= by0 && my < by1;
        for (Row r : st.rows) {
            int y = off + r.gy;
            boolean shown = y + r.h > by0 && y < by1;
            if (r.w != null) r.w.visible = shown;
            if (r.extra != null) r.extra.visible = shown;
            if (!shown) continue;
            boolean over = inView && my >= y && my < y + r.h;
            drawRow(mc, st, r, x0, x1, y, over, mx, my, pt, a, dt, by0, by1);
        }
        st.clipOn = false;
        if (clip) {
            GlassWidgets.endScissor();
            float view = by1 - by0, len = Math.max(16f, view * view / st.contentH);
            float ty = by0 + (view - len) * (st.scroll / st.maxScroll);
            GlassWidgets.fillRound(x1 + 3f, ty, x1 + 5f, ty + len, (Math.round(a * 0.35f * 255f) << 24) | 0xFFFFFF, 1f);
        }
    }

    private static void drawRow(Minecraft mc, State st, Row r, int x0, int x1, int y, boolean over,
                                int mx, int my, float pt, float a, float dt, float by0, float by1) {
        float cy = y + r.h / 2f, right = x1 - 6f;
        if (r.kind == K_HEADING) {
            label(r.label, x0 + 7f, y + r.h - 10f, TEXT_DIM, a);
            return;
        }
        ButtonWidget w = r.w;
        boolean active = w == null || w.active;
        boolean held = w != null && w == st.heldSlider;
        // 1.8.9：K_RAW（動作按鈕，例如「Super Secret Settings…」）是整列可點的動作列，所以也有列懸停
        boolean rowHover = r.kind != K_KEYS;
        r.hover.to(active && rowHover && (over || held) ? 1f : 0f);
        float hv = r.hover.value();
        if (hv > 0.01f)
            GlassWidgets.fillRound(x0 + 3f, y + 2f, x1 - 3f, y + r.h - 2f,
                    (Math.round(hv * a * 0x20) << 24) | 0xFFFFFF, 6f);

        String msg = w == null ? "" : (w.message == null ? "" : w.message);
        String label = msg, value = "";
        boolean isSwitch = false, on = false;

        switch (r.kind) {
            case K_CYCLE: {
                int c = colon(msg);
                if (c >= 0) { label = msg.substring(0, c).trim(); value = msg.substring(c + 1).trim(); }
                // a switch only when the value reads On / Off AND, where the widget knows its option, the option is
                // two-state (Narrator "Off" is 4-state, Smooth Lighting / Clouds are 3-state: they stay values)
                boolean bool = true;
                if (w instanceof OptionButtonWidget) {
                    Object opt = field(w, OptionButtonWidget.class, dev.s1mp1e.o.util.Names.of("option", "f_58671892"), "enumOptions");
                    if (opt instanceof GameOptions.Option) bool = ((GameOptions.Option) opt).isBoolean();
                }
                String v = clean(value);
                if (bool && v.equals(clean(tr("options.on")))) { isSwitch = true; on = true; }
                else if (bool && v.equals(clean(tr("options.off")))) { isSwitch = true; }
                break;
            }
            case K_SLIDER: {
                int c = colon(msg);
                if (c >= 0) { label = msg.substring(0, c).trim(); value = msg.substring(c + 1).trim(); }
                break;
            }
            case K_NAV: label = stripDots(msg); break;
            case K_LOCK: label = tr("difficulty.lock.title"); break;
            case K_KEYS: case K_CHOICE: label = r.label; break;
            case K_RAW: label = stripDots(msg); break;
            default: break;
        }
        // 動作列的標籤用藍色（iOS 設定 App 的動作列，例如「重置」），一般列用白色
        int labelRgb = !active ? TEXT_OFF : (r.kind == K_RAW ? BLUE : TEXT);
        if (!label.isEmpty()) label(label, x0 + 7f, cy - 4f, labelRgb, a);

        switch (r.kind) {
            case K_CYCLE: {
                placeHidden(w, x0, y, x1 - x0, (int) r.h);
                if (isSwitch) drawSwitch(r, on, right, cy, active ? a : a * 0.4f, dt);
                else rollValue(mc, st, r, y, clean(value), right, cy,
                        x0 + 7 + sw(label) + 8, x1, active ? TEXT : TEXT_OFF, a);
                break;
            }
            case K_SLIDER: {
                float tx1 = right - st.valueShown - 14f;
                float tw = clamp((x1 - x0) * 0.30f, 64f, 132f), tx0 = tx1 - tw;
                int vw = sw(value);
                label(value, right - vw, cy - 4f, active ? TEXT : TEXT_OFF, a);
                drawSliderRow(mc, st, w, tx0, tx1, cy, held, a);
                break;
            }
            case K_CHOICE: {
                if (selectedLang(st, r)) {
                    label("✓", right - sw("✓"), cy - 4f,
                            (Math.round(a * 255f) << 24) | BLUE, a);
                }
                break;
            }
            case K_KEYS: {
                refreshKeyRow(st, r);
                int bh = (int) r.h - 4, by = y + 2;
                String rmsg = r.extra == null ? "" : r.extra.message;
                int rw = Math.max(40, sw(rmsg) + 16);
                if (r.extra != null) {
                    placeHidden(r.extra, Math.round(right) - rw, by, rw, bh);
                    drawMiniButton(r.extra, Math.round(right) - rw, by, rw, bh, mx, my, a);
                }
                int kw = 84;
                placeHidden(w, Math.round(right) - rw - 4 - kw, by, kw, bh);
                drawMiniButton(w, Math.round(right) - rw - 4 - kw, by, kw, bh, mx, my, a);
                break;
            }
            case K_LOCK: {
                // a round glass key with a padlock; the vanilla button sits under it for the click (an icon button
                // must never turn into an empty capsule)
                float d = 14f, bx1 = right, bx0 = right - d;
                placeHidden(w, Math.round(bx0), Math.round(cy - d / 2f), Math.round(d), Math.round(d));
                boolean hot = active && mx >= bx0 && mx < bx1 && my >= cy - d / 2f && my < cy + d / 2f;
                GlassWidgets.capsule(bx0, cy - d / 2f, bx1, cy + d / 2f, 1f, hot ? 0.6f : 0.2f, a, active);
                boolean locked = ((net.minecraft.client.gui.widget.LockButtonWidget) w).isLocked();
                padlock(bx0 + d / 2f, cy, locked, (Math.round((active ? a : a * 0.5f) * 255f) << 24) | 0xFFFFFF);
                break;
            }
            case K_NAV: {
                placeHidden(w, x0, y, x1 - x0, (int) r.h);
                label("›", right - sw("›"), cy - 5f, TEXT_DIM, active ? a : a * 0.5f);
                break;
            }
            default: {
                // 1.8.9：動作按鈕（1.8.9 主選項頁的「Super Secret Settings…」）——整列就是按鈕，標籤已用藍色畫在左邊；
                // 原本右邊再放一顆寫同樣字的小按鈕，同一句話出現兩次。
                if (w != null) placeHidden(w, x0, y, x1 - x0, (int) r.h);
                break;
            }
        }
    }

    /** A small padlock glyph (body + shackle) centred at (cx, cy); the shackle swings open when unlocked. */
    private static void padlock(float cx, float cy, boolean locked, int argb) {
        float bw = 7f, bh = 5f, bx0 = cx - bw / 2f, by0 = cy - 0.5f;
        GlassWidgets.fillRound(bx0, by0, bx0 + bw, by0 + bh, argb, 1.2f);
        float sw = 5f, sx0 = cx - sw / 2f + (locked ? 0f : 1.5f), sy0 = cy - 4.5f;
        GlassWidgets.fillRound(sx0, sy0, sx0 + 1.2f, by0 + 0.5f - (locked ? 0f : 1.5f), argb, 0.6f);
        GlassWidgets.fillRound(sx0 + sw - 1.2f, sy0, sx0 + sw, by0 + 0.5f, argb, 0.6f);
        GlassWidgets.fillRound(sx0, sy0, sx0 + sw, sy0 + 1.2f, argb, 0.6f);
    }

    /** Position a widget to its row rect for hit-testing but keep it from drawing (visible false during draw). */
    private static void placeHidden(ButtonWidget w, int x, int y, int width, int height) {
        if (w == null) return;
        w.x = x; w.y = y; w.width = width; w.height = height;
    }

    /** A small glass capsule button (key change / reset, raw buttons) drawn in place with its label. */
    private static void drawMiniButton(ButtonWidget w, int x, int y, int ww, int hh, int mx, int my, float a) {
        boolean hot = w.active && mx >= x && mx < x + ww && my >= y && my < y + hh;
        float pulse = dev.s1mp1e.o.glass.anim.PressPulse.scale(w);           // group 9 tap pulse
        boolean pulsing = pulse < 0.9995f;
        if (pulsing) {
            float cx = x + ww / 2f, cy = y + hh / 2f;
            net.minecraft.client.render.platform.GlStateManager.pushMatrix();
            net.minecraft.client.render.platform.GlStateManager.translatef(cx, cy, 0f);
            net.minecraft.client.render.platform.GlStateManager.scalef(pulse, pulse, 1f);
            net.minecraft.client.render.platform.GlStateManager.translatef(-cx, -cy, 0f);
        }
        try {
            GlassWidgets.capsule(x, y, x + ww, y + hh, 1f, hot ? 0.6f : 0.2f, w.active ? a : a * 0.5f, w.active);
            String s = w.message == null ? "" : w.message;
            label(s, x + (ww - sw(s)) / 2f, y + hh / 2f - 4f, w.active ? TEXT : TEXT_OFF, a);
        } finally {
            if (pulsing) net.minecraft.client.render.platform.GlStateManager.popMatrix();
        }
    }

    /** Per-switch springs: travel (0 off .. 1 on) and the knob's glass lift while it flips (mc1144 drawSwitch). */
    private static final class SwitchAnim {
        final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0f);
        final Motion.Spring lift = new Motion.Spring(0.085f, 0f);
        boolean placed, lifted;
    }
    private static final Map<ButtonWidget, SwitchAnim> SWITCHES = new WeakHashMap<ButtonWidget, SwitchAnim>();

    private static void drawSwitch(Row row, boolean value, float right, float cy, float alpha, float dt) {
        SwitchAnim s = SWITCHES.get(row.w);
        if (s == null) { s = new SwitchAnim(); SWITCHES.put(row.w, s); }
        float want = value ? 1f : 0f;
        if (!s.placed) { s.travel.snap(want); s.placed = true; }
        if (s.travel.target != want) {
            s.travel.retarget(want);
            s.lifted = true;
            s.lift.tune(0.085f, 0f).retarget(1f);
        }
        s.travel.update(dt);
        if (s.lifted && Math.abs(s.travel.target - s.travel.x) < 0.08f) {
            s.lifted = false;
            s.lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        s.lift.update(dt);

        int aa = Math.round(alpha * 255f);
        float hgt = 12f, wid = 26f;
        float x1 = right, x0 = x1 - wid, y0 = cy - hgt / 2f, y1 = cy + hgt / 2f, r = hgt / 2f;
        float p = Motion.clamp01(s.travel.x), morph = Motion.clamp01(s.lift.x);
        GlassWidgets.fillRound(x0, y0, x1, y1, ((int) (aa * 0.55f) << 24) | OFF_TRACK, r);
        if (p > 0.003f) GlassWidgets.fillRound(x0, y0, x1, y1, ((int) (aa * p) << 24) | ON_TRACK, r);
        float khh = 0.85f * hgt / 2f, khw = khh * 1.55f;
        float tx0 = x0 + 2f + khw, tx1 = x1 - 2f - khw;
        int band = GlassWidgets.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, p);
        GlassWidgets.knobLens(tx0 + (tx1 - tx0) * p, cy, khw, khh, morph, 1.55f, 1.65f, 0.90f, x0, x1, r,
                Float.NaN, band, band, alpha);
    }

    private static VanillaSliderSkin skin(ButtonWidget slider) {
        VanillaSliderSkin k = SKINS.get(slider);
        if (k == null) { k = new VanillaSliderSkin(); SKINS.put(slider, k); }
        return k;
    }

    /** The pointer x vanilla's own mapping {@code (mx-(x+4))/(width-8)} must see for the skin's row value. */
    private static int vanillaX(ButtonWidget slider, VanillaSliderSkin k, double px, float tx0, float tx1) {
        double v = Math.max(0.0, Math.min(1.0, k.rowValueAt(px)));
        return (int) Math.round(slider.x + 4 + v * (slider.width - 8));
    }

    /**
     * The config-menu slider on a row (PORT_DELTA_SLIDER_ROLL): label | track | value. The vanilla slider is moved
     * onto our track so its own value mapping matches the drawn track; while held it is fed the pointer through the
     * skin's grab-aware {@code rowValueAt} (its knob blit suppressed), and the skin paints the pill / lens / springs.
     */
    private static void drawSliderRow(Minecraft mc, State st, ButtonWidget slider, float tx0, float tx1, float cy,
                                      boolean held, float a) {
        int bx = Math.round(tx0) - 4, bw = Math.round(tx1 - tx0) + 8;
        slider.x = bx; slider.y = Math.round(cy - 10f); slider.width = bw; slider.height = 20;
        VanillaSliderSkin k = skin(slider);
        double px = pointerX(mc);
        if (held) {
            if (!pointerDown()) {
                try { invoke(slider, ButtonWidget.class, new String[]{dev.s1mp1e.o.util.Names.of("mouseReleased", "m_77579459"), "mouseReleased"},
                        new Class<?>[]{int.class, int.class}, vanillaX(slider, k, px, tx0, tx1), Math.round(cy)); }
                catch (Throwable ignored) {}
                st.heldSlider = null;
                held = false;
            } else {
                dev.s1mp1e.o.glass.asm.BlitSuppressor.beginSuppressAll();
                try { invoke(slider, ButtonWidget.class, new String[]{dev.s1mp1e.o.util.Names.of("renderBackground", "m_77395853"), "mouseDragged"},
                        new Class<?>[]{Minecraft.class, int.class, int.class},
                        mc, vanillaX(slider, k, px, tx0, tx1), Math.round(cy)); }
                catch (Throwable ignored) {}
                finally { dev.s1mp1e.o.glass.asm.BlitSuppressor.endSuppressAll(); }
            }
        }
        k.paintRow(tx0, tx1, cy, sliderValue(slider), held, px, slider.active, a);
    }

    /**
     * A cycle row's value: when it changes, the old value leaves upward and the new one rises in (the roll the glass
     * cycle buttons have), right-aligned at {@code right}. TypingAnim sets and clears its own scissor, so on a scrolling
     * page the content scissor is put back afterwards, and a row cut by the page edge is drawn plain (mc1144 rule).
     */
    private static void rollValue(Minecraft mc, State st, Row r, int rowY, String value, float right, float cy,
                                  float clipX0, float clipX1, int rgb, float alpha) {
        int al = Math.round(clamp(alpha, 0f, 1f) * 255f);
        if (al < 8 || value.isEmpty() || r.w == null) return;
        int w = sw(value);
        boolean cut = st.clipOn && (rowY < st.clipY0 || rowY + r.h > st.clipY1);
        dev.s1mp1e.o.glass.render.TypingAnim roll = cut ? null : ROLLS.get(r.w);
        if (cut) ROLLS.remove(r.w);
        else if (roll == null) { roll = new dev.s1mp1e.o.glass.render.TypingAnim(); ROLLS.put(r.w, roll); }
        if (roll != null && !roll.broken) {
            try {
                roll.extractLabel(mc.textRenderer, value, value, Math.round(right) - w + w / 2,
                        Math.round(cy - 4f), Math.round(clipX0), Math.round(clipX1), (al << 24) | (rgb & 0xFFFFFF), false);
                if (st.clipOn) GlassWidgets.beginScissor(st.clipX0, st.clipY0, st.clipX1, st.clipY1);
                return;
            } catch (Throwable t) {
                roll.broken = true;
                if (st.clipOn) GlassWidgets.beginScissor(st.clipX0, st.clipY0, st.clipX1, st.clipY1);
            }
        }
        label(value, Math.round(right) - w, Math.round(cy - 4f), rgb, alpha);
    }

    // ---- footer ----

    private static void drawFooter(Minecraft mc, State st, int mx, int my, float pt, float a) {
        int x = st.screen.width - (int) M, y = st.screen.height - (int) TOP - (int) BTN_H;
        for (int i = st.footer.size() - 1; i >= 0; i--) {
            ButtonWidget w = st.footer.get(i);
            String s = w.message == null ? "" : w.message;
            int bw = Math.max((int) BTN_W, sw(s) + 24);
            placeHidden(w, x - bw, y, bw, (int) BTN_H);
            drawMiniButton(w, x - bw, y, bw, (int) BTN_H, mx, my, a);
            x -= bw + GAP;
        }
        if (st.footNote != null) {
            float sideW = Math.max(96f, Math.min(148f, st.screen.width * 0.24f));
            float nx = M + sideW + GAP + 6f;
            if (sw(st.footNote) < x - nx) label(st.footNote, nx, y + BTN_H / 2f - 4f, TEXT_DIM, a);
        }
    }

    // ============================================================================================
    //  input
    // ============================================================================================

    /** Called from the spliced Screen.mouseClicked. Returns true when the shell consumed the click. */
    public static boolean mouseClicked(Screen screen, int mx, int my, int button) {
        if (!handles(screen) || button != 0) return false;
        try {
            return click(screen, mx, my);
        } catch (Throwable t) {
            System.out.println("[S1mp1e] settings shell click failed: " + t);
            return false;
        }
    }

    private static boolean click(Screen screen, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        State st = ensure(screen);
        int w = screen.width, h = screen.height;
        float sideW = Math.max(96f, Math.min(148f, w * 0.24f));
        float sideX0 = M, sideX1 = M + sideW;
        float bodyY0 = TOP + BTN_H + GAP, bodyY1 = h - TOP - BTN_H - GAP;
        float contX0 = sideX1 + GAP, contX1 = w - M;

        // sidebar
        if (mx >= sideX0 + SIDE_PAD && mx < sideX1 - SIDE_PAD && my >= bodyY0 && my < bodyY1) {
            int i = (int) Math.floor((my - (bodyY0 + SIDE_PAD - st.sideScroll)) / ROW_H);
            if (i >= 0 && i < CAT_KEY.length) {
                if (i != st.selectedCat) openCategory(screen, i);
                return true;
            }
        }

        // footer
        for (ButtonWidget b : st.footer) {
            if (b.visible && mx >= b.x && mx < b.x + b.width && my >= b.y && my < b.y + b.height) {
                pressButton(screen, b, mx, my);
                return true;
            }
        }

        // rows
        int off = Math.round(bodyY0) - Math.round(st.scroll);
        boolean inContent = mx >= contX0 && mx < contX1 + 8 && my >= bodyY0 && my < bodyY1;
        if (!inContent) return mx >= sideX0 && mx < sideX1;   // swallow clicks in the sidebar card gutter
        for (Row r : st.rows) {
            int y = off + r.gy;
            if (r.kind == K_HEADING || my < y || my >= y + r.h) continue;
            activateRow(screen, st, r, mx, my);
            return true;
        }
        return true;   // inside the content area: consume
    }

    private static void activateRow(Screen screen, State st, Row r, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        switch (r.kind) {
            case K_SLIDER: {
                if (r.w == null) return;
                // only the track (+ the pill's overhang) takes a press: a click on the label does nothing
                if (mx < r.w.x - 6 || mx >= r.w.x + r.w.width + 6) return;
                VanillaSliderSkin k = skin(r.w);
                double px = pointerX(mc);
                k.rowPress(px);
                int vx = vanillaX(r.w, k, px, r.w.x + 4, r.w.x + r.w.width - 4);
                try { invoke(r.w, ButtonWidget.class, new String[]{dev.s1mp1e.o.util.Names.of("mouseClicked", "m_07260289"), "mousePressed"},
                        new Class<?>[]{Minecraft.class, int.class, int.class}, mc, vx, r.w.y + r.w.height / 2); }
                catch (Throwable ignored) {}
                st.heldSlider = r.w;
                break;
            }
            case K_CYCLE: {
                toggleOption(screen, r.w, mx, my);
                break;
            }
            case K_KEYS: {
                // route to the specific mini button under the cursor (change vs reset) via the entry.
                if (r.entry != null) pressKeyEntry(r, mx, my);
                break;
            }
            case K_CHOICE: {
                selectLanguage(screen, st, r);
                break;
            }
            case K_NAV: {
                pressButton(screen, r.w, mx, my);
                break;
            }
            default: {
                if (r.w != null) pressButton(screen, r.w, mx, my);
            }
        }
    }

    /** Toggle a cycling option (OptionButtonWidget or a colon button) exactly as vanilla would. */
    private static void toggleOption(Screen screen, ButtonWidget b, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        playClick(b);
        if (b instanceof OptionButtonWidget) {
            Object opt = field(b, OptionButtonWidget.class, dev.s1mp1e.o.util.Names.of("option", "f_58671892"), "enumOptions");
            if (opt instanceof GameOptions.Option) {
                GameOptions gs = mc.options;
                gs.set((GameOptions.Option) opt, 1);
                b.message = gs.getAsString((GameOptions.Option) opt);
                gs.save();
                maybeRebuildOnScaleChange(screen, (GameOptions.Option) opt);
                return;
            }
        }
        // Fallback: the screen's own actionPerformed (difficulty, main-hand, etc.).
        actionPerformed(screen, b);
    }

    private static void pressButton(Screen screen, ButtonWidget b, int mx, int my) {
        if (b == null) return;
        playClick(b);
        actionPerformed(screen, b);
    }

    private static void pressKeyEntry(Row r, int mx, int my) {
        // entry.mouseClicked(index, mouseX, mouseY, relativeX, relativeY, mouseEvent)
        try {
            invoke(r.entry, r.entry.getClass(), new String[]{dev.s1mp1e.o.util.Names.of("mouseClicked", "m_33696468"), "mousePressed"},
                    new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class},
                    0, mx, my, mx - r.w.x, my - r.w.y, 0);
        } catch (Throwable t) {
            // direct: clicking the change button sets the binding wait; reset resets.
        }
    }

    private static void selectLanguage(Screen screen, State st, Row r) {
        if (st.vanillaList == null || r.entryIndex < 0) return;
        try {
            invoke(st.vanillaList, st.vanillaList.getClass(), new String[]{dev.s1mp1e.o.util.Names.of("entryClicked", "m_07503748"), "elementClicked"},
                    new Class<?>[]{int.class, boolean.class, int.class, int.class},
                    r.entryIndex, false, 0, 0);
        } catch (Throwable ignored) {}
        // refresh the harvested row messages (Done/Force-unicode text may have changed)
        STATES.remove(screen);
    }

    /** Called from the spliced Screen.handleMouse. Returns true when the shell consumed the wheel. */
    public static boolean handleWheel(Screen screen) {
        if (!handles(screen)) return false;
        try {
            int d = Mouse.getEventDWheel();
            if (d == 0) return false;
            State st = ensure(screen);
            Minecraft mc = Minecraft.getInstance();
            int w = screen.width, h = screen.height;
            float sideW = Math.max(96f, Math.min(148f, w * 0.24f));
            float sideX0 = M, sideX1 = M + sideW;
            float bodyY0 = TOP + BTN_H + GAP, bodyY1 = h - TOP - BTN_H - GAP;
            int mx = mouseGuiX(mc), my = mouseGuiY(mc);
            float step = d > 0 ? -ROW_H * 2f : ROW_H * 2f;
            if (mx >= sideX0 && mx < sideX1 && my >= bodyY0 && my < bodyY1) {
                float max = Math.max(0f, CAT_KEY.length * ROW_H + 2 * SIDE_PAD - (bodyY1 - bodyY0));
                st.sideScroll = clamp(st.sideScroll + (d > 0 ? -ROW_H : ROW_H), 0f, max);
            } else {
                st.scrollTarget = clamp(st.scrollTarget + step, 0f, st.maxScroll);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================================================
    //  navigation
    // ============================================================================================

    private static void openCategory(Screen current, int cat) {
        Minecraft mc = Minecraft.getInstance();
        GameOptions gs = mc.options;
        Screen parent = parentOf(current);
        if (parent == null) parent = current;
        try { mc.getSoundManager(); } catch (Throwable ignored) {}
        Screen next = null;
        switch (cat) {
            case CAT_GENERAL:  next = new OptionsScreen(parent, gs); break;
            case CAT_SKIN:     next = new SkinCustomizationScreen(parent); break;
            case CAT_SOUND:    next = new SoundsScreen(parent, gs); break;
            case CAT_VIDEO:    next = new VideoOptionsScreen(parent, gs); break;
            case CAT_CONTROLS: next = new ControlsOptionsScreen(parent, gs); break;
            case CAT_LANGUAGE: next = new LanguageOptionsScreen(parent, gs, mc.getLanguageManager()); break;
            case CAT_CHAT:     next = new ChatOptionsScreen(parent, gs); break;
            case CAT_RESPACK:  next = new ResourcePacksScreen(parent); break;
            case CAT_SNOOPER:  next = new SnooperScreen(parent, gs); break;
        }
        if (next != null) mc.openScreen(next);
    }

    /** The screen the current settings page returns to (its first Screen-typed field). */
    private static Screen parentOf(Screen s) {
        Class<?> c = s.getClass();
        while (c != null && c != Screen.class && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Screen.class.isAssignableFrom(f.getType())) {
                    try { f.setAccessible(true); Object v = f.get(s); if (v instanceof Screen) return (Screen) v; }
                    catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // ============================================================================================
    //  vanilla glue
    // ============================================================================================

    private static void maybeRebuildOnScaleChange(Screen screen, GameOptions.Option opt) {
        if (opt == GameOptions.Option.GUI_SCALE) {
            try { Minecraft mc = Minecraft.getInstance();
                screen.init(mc, screen.width, screen.height); } catch (Throwable ignored) {}
            STATES.remove(screen);
        }
    }

    private static void refreshKeyRow(State st, Row r) {
        if (r.w == null) return;
        try {
            net.minecraft.client.options.KeyBinding kb =
                    (net.minecraft.client.options.KeyBinding) field(r.entry, r.entry.getClass(),
                            dev.s1mp1e.o.util.Names.of("keyBinding", "f_04704659"), "keybinding");
            if (kb == null) return;
            if (r.extra != null) r.extra.active = !kb.getName().isEmpty() && kb.getKeyCode() != kb.getDefaultKeyCode();
            String key = GameOptions.getKeyName(kb.getKeyCode());
            boolean capturing = false;
            if (st.screen instanceof ControlsOptionsScreen) {
                Object waiting = field(st.screen, ControlsOptionsScreen.class, dev.s1mp1e.o.util.Names.of("selectedKeyBinding", "f_46607866"), "buttonId");
                capturing = waiting == kb;
            }
            if (capturing) key = Formatting.WHITE + "> " + Formatting.YELLOW + key + Formatting.WHITE + " <";
            r.w.message = key;
        } catch (Throwable ignored) {}
    }

    private static boolean selectedLang(State st, Row r) {
        try {
            if (!(st.screen instanceof LanguageOptionsScreen)) return false;
            String cur = Minecraft.getInstance().getLanguageManager().getLanguage().getCode();
            return r.entry != null && r.entry.toString() != null && cur != null
                    && r.entry.toString().length() > 0 && langCodeMatches(st, r, cur);
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static boolean langCodeMatches(State st, Row r, String cur) {
        try {
            List<String> codes = (List<String>) field(st.vanillaList, st.vanillaList.getClass(),
                    dev.s1mp1e.o.util.Names.of("languageCodes", "f_73959247"), "langCodeList");
            return codes != null && r.entryIndex >= 0 && r.entryIndex < codes.size()
                    && cur.equals(codes.get(r.entryIndex));
        } catch (Throwable t) { return false; }
    }

    private static void setDragFlag(ButtonWidget b) {
        for (String[] f : new String[][]{{dev.s1mp1e.o.util.Names.of("dragging", "f_73479383"), "dragging"}, {dev.s1mp1e.o.util.Names.of("dragging", "f_03209506"), "pressed"}}) {
            Field fl = findField(b.getClass(), f[0], f[1]);
            if (fl != null && fl.getType() == boolean.class) {
                try { fl.setBoolean(b, true); return; } catch (Throwable ignored) {}
            }
        }
    }

    private static float sliderValue(ButtonWidget b) {
        Object v = field(b, b.getClass(), dev.s1mp1e.o.util.Names.of("value", "f_96577240"), "sliderValue");   // OptionSliderWidget
        if (v instanceof Float) return (Float) v;
        v = field(b, b.getClass(), dev.s1mp1e.o.util.Names.of("sliderButtonPos", "f_62442638"), "volume");               // sound Button
        if (v instanceof Float) return (Float) v;
        return 0f;
    }

    private static void actionPerformed(Screen screen, ButtonWidget b) {
        try {
            Method m = method(screen.getClass(), new String[]{dev.s1mp1e.o.util.Names.of("buttonClicked", "m_30778170"), "actionPerformed"},
                    new Class<?>[]{ButtonWidget.class});
            if (m != null) { m.setAccessible(true); m.invoke(screen, b); }
        } catch (Throwable t) {
            System.out.println("[S1mp1e] actionPerformed failed: " + t);
        }
    }

    private static void playClick(ButtonWidget b) {
        try { b.playClickSound(Minecraft.getInstance().getSoundManager()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static List<ButtonWidget> buttonList(Screen s) {
        Object v = field(s, Screen.class, dev.s1mp1e.o.util.Names.of("buttons", "f_78519977"), "buttonList");
        return v instanceof List ? (List<ButtonWidget>) v : null;
    }

    private static String title(Screen s) {
        // Each screen keeps its own title String field; grab the first String field that looks like a title.
        int cat = categoryOf(s);
        if (cat >= 0) return tr(cat == CAT_GENERAL ? "options.title" : CAT_KEY[cat]);
        return "";
    }

    // ---- small helpers ----

    private static int mouseGuiX(Minecraft mc) {
        if (!Float.isNaN(devX)) return Math.round(devX);
        return Mouse.getX() * mc.screen.width / mc.width;
    }

    private static int mouseGuiY(Minecraft mc) {
        if (!Float.isNaN(devY)) return Math.round(devY);
        return mc.screen.height - Mouse.getY() * mc.screen.height / mc.height - 1;
    }

    /** Sub-pixel GUI-scaled pointer x (the slider rides it 1:1). */
    private static double pointerX(Minecraft mc) {
        if (!Float.isNaN(devX)) return devX;
        if (mc.screen == null || mc.width <= 0) return 0.0;
        return Mouse.getX() * (double) mc.screen.width / (double) mc.width;
    }

    /** The left button is held (or, in a DevShot drag, the dev stand-in for it). */
    private static boolean pointerDown() {
        return VanillaSliderSkin.devMouseDown || Mouse.isButtonDown(0);
    }

    /** A grouped glass card (lighter than the config panel): frosted glass + a faint readability scrim,
     *  the hotbar corner radius — matching mc1144's SettingsShell.card. */
    private static void card(float x0, float y0, float x1, float y1, float alpha, float scrim) {
        if (alpha < 0.004f) return;
        float w = x1 - x0, h = y1 - y0;
        float r = Math.min(GlassCorners.HOTBAR_RADIUS + 2f, Math.min(w, h) / 2f);
        if (SceneCapture.hasBackdrop() && GlassProgram.usable()) {
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                    GlassCorners.cornerKnob(w, h), 0f, alpha, GlassRenderer.FROST_PANEL);
            GlassRenderer.roundRect(x0, y0, x1, y1, r, (Math.round(alpha * scrim * 255f) << 24) | 0x1C1C1E);
        } else {
            GlassRenderer.roundRect(x0, y0, x1, y1, r, (Math.round(alpha * 0.58f * 255f) << 24) | 0x1C1C1E);
        }
        GlassWidgets.resetColorCache();
    }

    /** Width as the TextRenderer measures it (section-sign colour codes take no space). */
    private static int sw(String s) {
        if (s == null || s.isEmpty()) return 0;
        return Minecraft.getInstance().textRenderer.getWidth(s);
    }

    private static String clean(String s) {
        String t = s == null ? "" : Formatting.strip(s);
        return t == null ? "" : t.trim();
    }

    private static void label(String s, float x, float y, int rgb, float alpha) {
        int al = Math.round(clamp(alpha, 0f, 1f) * 255f);
        if (al < 8 || s == null || s.isEmpty()) return;
        Minecraft.getInstance().textRenderer.draw(s, x, y, (al << 24) | (rgb & 0xFFFFFF), false);
        GlassWidgets.resetColorCache();
    }

    private static int colon(String s) {
        int a = s.indexOf(": "), b = s.indexOf('：');
        if (a >= 0 && (b < 0 || a < b)) return a;
        if (b >= 0) return b;
        return s.indexOf(':');
    }

    private static String stripDots(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith(".") || t.endsWith("…")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    private static String doneText() { return tr("gui.done"); }

    private static String tr(String key) {
        try { return I18n.translate(key); } catch (Throwable t) { return key; }
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static int byteOf(float a) {
        int v = Math.round(a * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int lerpRGB(int c0, int c1, float t) {
        int r = (int) (((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
        int g = (int) (((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
        int b = (int) ((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

    // ---- reflection (dual MCP/SRG, cached) ----

    private static final Map<String, Field> FCACHE = new java.util.concurrent.ConcurrentHashMap<String, Field>();
    private static final Map<String, Method> MCACHE = new java.util.concurrent.ConcurrentHashMap<String, Method>();

    private static Object field(Object o, Class<?> owner, String srg, String mcp) {
        if (o == null) return null;
        String key = owner.getName() + "#" + srg;
        Field f = FCACHE.get(key);
        if (f == null) {
            f = findField(owner, srg, mcp);
            if (f != null) FCACHE.put(key, f);
        }
        if (f == null) return null;
        try { return f.get(o); } catch (Throwable t) { return null; }
    }

    private static Field findField(Class<?> owner, String srg, String mcp) {
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            for (String n : new String[]{srg, mcp}) {
                try { Field f = c.getDeclaredField(n); f.setAccessible(true); return f; }
                catch (NoSuchFieldException ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method method(Class<?> owner, String[] names, Class<?>[] sig) {
        StringBuilder key = new StringBuilder(owner.getName()).append('#').append(names[0]);
        for (Class<?> s : sig) key.append(s.getName());
        Method m = MCACHE.get(key.toString());
        if (m != null) return m;
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            for (String n : names) {
                try { Method mm = c.getDeclaredMethod(n, sig); mm.setAccessible(true); MCACHE.put(key.toString(), mm); return mm; }
                catch (NoSuchMethodException ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object invoke(Object o, Class<?> owner, String[] names, Class<?>[] sig, Object... args)
            throws Exception {
        Method m = method(owner, names, sig);
        if (m == null) throw new NoSuchMethodException(names[0]);
        return m.invoke(o, args);
    }
}
