package com.seagull.liquidglass.client.compat;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.render.GlassCorners;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import dev.s1mp1e.client.hud.HudGlass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.OptionRow;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.PageFrame;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.BaseWidget;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.FlatButtonWidget;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.LabelWidget;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.ScrollBarWidget;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.TabHeaderWidget;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Liquid-glass restyle of Reese's Sodium Options' video settings (user: "能不能改一下顯示設定的 ui"), in the
 * Apple "inset grouped" style of the S1mp1e config screen:
 * <ul>
 *   <li>the tab rail becomes one glass panel with a sliding glass pill on the selected page;</li>
 *   <li>each run of adjacent option rows (an option group) becomes a glass card with inset hairline separators, a
 *       hovered/focused row gets a soft highlight;</li>
 *   <li>booleans become iOS switches, integer sliders our glass slider (blue fill + white knob → lens while held);</li>
 *   <li>the search field and flat buttons become glass capsules, scroll thumbs slim capsules, tooltips glass cards;</li>
 *   <li>every RSO outline (frames, focus borders) and dark block background is dropped.</li>
 * </ul>
 * RSO paints everything through {@code BaseWidget.drawRect/drawBorder}; {@code RsoBaseWidgetMixin} routes those here
 * while the screen extracts ({@link #active}). Widgets whose classes are package-private are recognised through
 * marker interfaces their mixins add. Render thread only.
 */
public final class RsoGlass {
   private RsoGlass() {}

   /** True while {@code SodiumVideoOptionsScreen.extractRenderState} runs. */
   public static boolean active;
   /** Dev only: log unrecognised small rects once. */
   public static boolean DEBUG = Boolean.getBoolean("s1mp1e.preloadMixinTargets");
   private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

   // ---- markers / state views added to RSO's package-private widgets by the compat mixins ----

   /** Any option row ({@code AbstractOptionRow}). */
   public interface Row {}

   public interface BoolRow extends Row {
      boolean lg$value();
      boolean lg$enabled();
      Switch lg$switch();
   }

   public interface SliderRow extends Row {
      boolean lg$held();
      Knob lg$knob();
   }

   public interface TabButton {
      boolean lg$selected();
   }

   public interface Rail {
      LayoutBounds lg$dim();
      BaseWidget lg$selectedWidget();
   }

   public interface FlatState {
      boolean lg$enabled();

      int lg$labelWidth();
   }

   public static void begin() {
      active = true;
      GlassWidgets.resetScissorMirror();
   }

   public static void end() {
      active = false;
   }

   // ---- colours / geometry ----

   private static final float R = GlassCorners.HOTBAR_RADIUS;
   /** The one gap between sibling surfaces on this screen (S1mp1e config screen: 2·GRID). */
   public static final int GAP = 8;
   /** RSO spaces option groups 4 apart (an IINC, not a patchable constant): each card is drawn 2 px inside its rows
    *  on top and bottom so the visible gap is GAP; the rail and the toolbar lift count the same inset. */
   private static final int CARD_INSET = (GAP - 4) / 2;
   /** Horizontal padding a capsule button's label needs past the capsule's round ends (radius h/2 + 2). */
   private static final int LABEL_PAD = 12;
   private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759, BLUE = 0x0A84FF;

   // ---- hover fades (100 ms, like every glass button) ----

   private static final WeakHashMap<Object, Fade> HOVER = new WeakHashMap<>();

   private static float hover(Object w, boolean over) {
      Fade f = HOVER.get(w);
      if (f == null) {
         f = new Fade(over ? 1.0F : 0.0F, 100.0F);
         HOVER.put(w, f);
      }
      float t = over ? 1.0F : 0.0F;
      if (f.target() != t) {
         f.snap(f.value());
         f.to(t);
      }
      return f.value();
   }

   // ---- the drawRect router ----

   /** {@code BaseWidget.drawRect}: true = handled here, skip RSO's flat fill. */
   public static boolean rect(BaseWidget w, GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
      if (!active) return false;
      int dx = x2 - x1, dy = y2 - y1;
      boolean full = x1 == w.getX() && y1 == w.getY() && x2 == w.getLimitX() && y2 == w.getLimitY();

      if (w instanceof Row) {
         if (DEBUG && dx <= 12 && dy <= 12) {
            String k = w.getClass().getName() + " " + dx + "x" + dy + " bool=" + (w instanceof BoolRow);
            if (SEEN.add(k)) System.out.println("[RsoGlass] small rect: " + k);
         }
         if (w instanceof BoolRow && dx <= 12 && dy <= 12) return true;   // checkbox fill / disabled corners: the
                                                                           // switch is drawn at renderControl HEAD
         if (w instanceof SliderRow s && dy <= 12) {                       // track, then the 4 px thumb
            if (dx <= 5) drawKnob(g, s, (x1 + x2) / 2.0F, (y1 + y2) / 2.0F);
            else s.lg$knob().track(x1, y1, x2, y2);
            return true;
         }
         if (dx <= 4) {                                                    // search-result marker
            GlassWidgets.fillRound(g, x1, y1 + 2, x1 + 3, y2 - 2, 0xFF000000 | BLUE, 1.5F);
            return true;
         }
         float h = hover(w, w.isHovered() || w.isFocused());              // the row background
         if (h > 0.01F) {
            GlassWidgets.fillRound(g, w.getX() + 3, w.getY() + CARD_INSET, w.getLimitX() - 3, w.getLimitY() - CARD_INSET,
                  (Math.round(h * 0x1A) << 24) | 0xFFFFFF, R - 2.0F);
         }
         return true;
      }
      if (w instanceof TabButton || w instanceof TabHeaderWidget) return true;   // the rail pill replaces these
      if (w instanceof LabelWidget) {
         float h = hover(w, w.isHovered());
         if (h > 0.01F) GlassWidgets.fillRound(g, x1, y1, x2, y2, (Math.round(h * 0x14) << 24) | 0xFFFFFF, R - 2.0F);
         return true;
      }
      if (w instanceof FlatButtonWidget) {
         if (full) {
            boolean enabled = !(w instanceof FlatState f) || f.lg$enabled();
            float h = hover(w, enabled && w.isHovered());
            GlassWidgets.capsule(g, x1, y1, x2, y2, GlassCorners.knobByte(dx, dy) / 255.0F, 0.5F * h, 1.0F, enabled);
         }
         return true;                                                      // (+ the "selected" underline)
      }
      if (w instanceof TextFieldWidget) {
         if (!full) return false;                                          // keep the text-selection highlight
         float h = hover(w, w.isFocused());
         GlassWidgets.capsule(g, x1, y1, x2, y2, GlassCorners.knobByte(dx, dy) / 255.0F, 0.35F * h, 1.0F, true);
         return true;
      }
      if (w instanceof ScrollBarWidget) {                                  // thumb (the track outline was dropped) →
         float tw = w.getWidth();                                          // draw a faint full-height track + a clear thumb
         float tx0 = w.getX() + tw * 0.5F - 1.5F, tx1 = w.getX() + tw * 0.5F + 1.5F;
         GlassWidgets.fillRound(g, tx0, w.getY(), tx1, w.getLimitY(), 0x26FFFFFF, 1.5F);
         float cx = (x1 + x2) / 2.0F;
         GlassWidgets.fillRound(g, cx - 1.5F, y1, cx + 1.5F, y2, 0xB0FFFFFF, 1.5F);
         return true;
      }
      if (w instanceof PageFrame) {                                        // option tooltip box
         GlassWidgets.panel(g, x1, y1, x2, y2, 1.0F, 4.0F, 0.55F);   // small corner: RSO's text sits ~2 px in
         return true;
      }
      return false;
   }

   // ---- option action buttons (reset / undo): glass circle + SF Symbol ----

   private static final int ACTION_BG_HOVERED = 0xA0000000, ACTION_BG_DISABLED = 0x33000000;
   /** Circle inset from the (row-high) button box, so it sits inside the row's hover capsule instead of spilling out. */
   private static final float ACTION_INSET = 4.0F;

   /** OptionActionButtonRenderer's background fill → a small glass circle (lifted when hovered, dim when disabled). */
   public static void actionBackground(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
      boolean hovered = color == ACTION_BG_HOVERED, disabled = color == ACTION_BG_DISABLED;
      float d = Math.min(x2 - x1, y2 - y1) - 2.0F * ACTION_INSET, cx = (x1 + x2) / 2.0F, cy = (y1 + y2) / 2.0F;
      if (d <= 2.0F) return;
      GlassWidgets.capsule(g, cx - d / 2.0F, cy - d / 2.0F, cx + d / 2.0F, cy + d / 2.0F, 1.0F, hovered ? 0.55F : 0.0F,
            disabled ? 0.5F : 1.0F, !disabled);
   }

   /** Its icon → the matching SF Symbol in white (grey when disabled); false = unknown icon, draw the original. */
   public static boolean actionIcon(GuiGraphicsExtractor g, String path, int x, int y, int w, int h, int color) {
      String glyph = path.contains("reset_to_default") ? "arrow.counterclockwise"
            : path.contains("undo_to_unmodified") ? "arrow.uturn.left" : null;
      if (glyph == null) return false;
      int tint = color == -1 ? 0xFFFFFFFF : 0x99FFFFFF;
      // the icon box is ICON_SIZE (10) centred in the button; keep the glyph well inside the inset circle
      float d = Math.min(w, h) * 0.62F, cx = x + w / 2.0F, cy = y + h / 2.0F;
      return com.seagull.liquidglass.client.render.SfIcons.drawGlyph(g, glyph, cx - d / 2.0F, cy - d / 2.0F,
            cx + d / 2.0F, cy + d / 2.0F, tint);
   }

   // ---- toolbars: uniform GAP between the capsules and to the content ----

   /**
    * After RSO lays the screen out: right-align each toolbar as a chain with {@link #GAP} between its members (the
    * rightmost keeps its place, buttons keep their width, the search field gives up the width), and lift the top
    * toolbar so it sits {@link #GAP} above the content. Widgets that don't exist in this layout are skipped.
    */
   public static void respaceToolbars(BaseWidget search, BaseWidget donate, BaseWidget hideDonate, BaseWidget undo,
                                      BaseWidget apply, BaseWidget close, BaseWidget content) {
      for (BaseWidget b : new BaseWidget[]{donate, hideDonate, undo, apply, close}) padLabel(b);
      chain(search, donate, hideDonate);
      chain(undo, apply, close);
      if (search != null && content != null) {
         int lift = GAP - (content.getY() + CARD_INSET - search.getLimitY());
         if (DEBUG) System.out.println("[RsoGlass] layout lift=" + lift);
         if (lift > 0) {
            for (BaseWidget w : new BaseWidget[]{search, donate, hideDonate}) {
               if (w == null) continue;
               LayoutBounds d = w.getDimensions();
               w.setDim(new LayoutBounds(d.x(), d.y() - lift, d.width(), d.height()));
            }
         }
      }
      if (content != null) {                                                // bottom toolbar: GAP below the content
         int want = content.getLimitY() - CARD_INSET + GAP;
         for (BaseWidget w : new BaseWidget[]{undo, apply, close}) {
            if (w == null || w.getY() < content.getLimitY() - CARD_INSET) continue;
            LayoutBounds d = w.getDimensions();
            if (d.y() != want) w.setDim(new LayoutBounds(d.x(), want, d.width(), d.height()));
         }
      }
      dbg("search", search); dbg("donate", donate); dbg("hideDonate", hideDonate);
      dbg("undo", undo); dbg("apply", apply); dbg("close", close); dbg("content", content);
   }

   /** A wide capsule button whose label would run into its round ends grows (keeping its right edge). */
   private static void padLabel(BaseWidget w) {
      if (!(w instanceof FlatState f)) return;
      LayoutBounds d = w.getDimensions();
      if (d.width() < d.height() * 1.5F) return;                        // the round "x" stays a circle
      int need = f.lg$labelWidth() + 2 * LABEL_PAD;
      if (need > d.width()) w.setDim(new LayoutBounds(d.getLimitX() - need, d.y(), need, d.height()));
   }

   private static void dbg(String what, BaseWidget w) {
      if (DEBUG && w != null) System.out.println("[RsoGlass] layout " + what + " x=" + w.getX() + ".." + w.getLimitX()
            + " y=" + w.getY() + ".." + w.getLimitY());
   }

   /** Members left→right; the last one anchors. A member that doesn't sit on the same row is left alone. */
   private static void chain(BaseWidget... ws) {
      BaseWidget next = null;
      for (int i = ws.length - 1; i >= 0; i--) {
         BaseWidget w = ws[i];
         if (w == null) continue;
         if (next != null && Math.abs(w.getY() - next.getY()) <= 2 && w.getX() < next.getX()) {
            LayoutBounds d = w.getDimensions();
            int right = next.getX() - GAP;
            if (i == 0 && w instanceof TextFieldWidget) {
               w.setDim(new LayoutBounds(d.x(), d.y(), Math.max(20, right - d.x()), d.height()));
            } else {
               w.setDim(new LayoutBounds(right - d.width(), d.y(), d.width(), d.height()));
            }
         }
         next = w;
      }
   }

   // ---- rail: glass panel + sliding selected pill (per tab frame) ----

   private static final class Pill {
      final Motion.Spring y = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
      final Motion.Spring h = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
      final Motion.Clock clock = new Motion.Clock();
      boolean placed;
   }

   private static final WeakHashMap<Object, Pill> PILLS = new WeakHashMap<>();

   public static void drawRail(Object frame, GuiGraphicsExtractor g, Rail rail, int contentX) {
      if (!active || rail == null) return;
      LayoutBounds d = rail.lg$dim();
      if (d == null || d.width() <= 0 || d.height() <= 0) return;
      float railX1 = Math.min(d.getLimitX(), contentX - GAP);
      if (DEBUG && SEEN.add("rail " + d + contentX)) {
         System.out.println("[RsoGlass] rail x=" + d.x() + ".." + railX1 + " y=" + (d.y() + CARD_INSET) + ".."
               + (d.getLimitY() - CARD_INSET) + " contentX=" + contentX);
      }
      GlassWidgets.panel(g, d.x(), d.y() + CARD_INSET, railX1, d.getLimitY() - CARD_INSET, 1.0F, R + 2.0F, 0.30F);

      BaseWidget sel = rail.lg$selectedWidget();
      if (sel == null) return;
      Pill p = PILLS.computeIfAbsent(frame, k -> new Pill());
      float dt = p.clock.tick();
      if (!p.placed) {
         p.y.x = p.y.target = sel.getY();
         p.h.x = p.h.target = sel.getHeight();
         p.placed = true;
      }
      p.y.retarget(sel.getY());
      p.h.retarget(sel.getHeight());
      p.y.update(dt);
      p.h.update(dt);
      float x0 = d.x() + 4.0F, x1 = railX1 - 4.0F, y0 = p.y.x + 1.0F, y1 = p.y.x + p.h.x - 1.0F;
      GlassWidgets.enableScissor(g, d.x(), d.y() + CARD_INSET, d.getLimitX(), d.getLimitY() - CARD_INSET);
      GlassWidgets.capsule(g, x0, y0, x1, y1, GlassCorners.knobByte(x1 - x0, y1 - y0) / 255.0F, 0.45F, 1.0F, true);
      GlassWidgets.disableScissor(g);
   }

   // ---- option groups → glass cards ----

   // ---- page content: smooth scroll (visual lag) + per-row cascade on page switch ----

   private static final class PageAnim {
      float lag;                 // pixels the display is behind the logical scroll (eased to 0)
      int prevFirstY = Integer.MIN_VALUE;
      int prevCount = -1;
      Object prevFirst;
      long lastNs;
      long cascadeNs;            // when the current page's rows began cascading in
   }

   private static final WeakHashMap<Object, PageAnim> PAGES = new WeakHashMap<>();
   private static final WeakHashMap<Object, Long> ROW_BORN = new WeakHashMap<>();
   private static final long ROW_STAGGER_NS = 24_000_000L;   // 24 ms between rows
   private static final float ROW_ENTER_W = 18.0F;           // ~0.3 s settle
   private static final float ROW_RISE = 8.0F;

   /**
    * Called around a page's content render: eases the scroll (RSO repositions rows instantly; the jump is absorbed into
    * {@code lag} and eased out — "滑動要絲滑") and, when the row set changes (a page/tab switch), schedules the rows to
    * cascade in one-by-one ("和合成欄那邊切換頁面一樣 一個一個出來"). Returns the y-shift to translate the content by.
    */
   public static float contentShift(Object frame, List<OptionRow> rows) {
      PageAnim a = PAGES.computeIfAbsent(frame, k -> new PageAnim());
      long now = net.minecraft.util.Util.getNanos();
      float dt = a.lastNs == 0L ? 1F / 60F : Math.min(0.05F, (now - a.lastNs) / 1.0e9F);
      a.lastNs = now;
      if (rows == null || rows.isEmpty()) { a.prevFirstY = Integer.MIN_VALUE; a.prevFirst = null; a.prevCount = -1; return 0F; }
      OptionRow first = rows.get(0);
      int curY = first.getDimensions().y();
      boolean pageChanged = a.prevCount != rows.size() || a.prevFirst != first;
      if (pageChanged) {
         a.lag = 0F;
         a.cascadeNs = now + 40_000_000L;   // let the page settle a frame, then cascade
         for (int i = 0; i < rows.size(); i++) ROW_BORN.put(rows.get(i), a.cascadeNs + i * ROW_STAGGER_NS);
      } else if (a.prevFirstY != Integer.MIN_VALUE && curY != a.prevFirstY) {
         a.lag += a.prevFirstY - curY;       // scroll jump this frame → absorb, then ease out
      }
      a.prevFirst = first;
      a.prevFirstY = curY;
      a.prevCount = rows.size();
      a.lag *= (float) Math.exp(-dt / 0.08F);
      if (Math.abs(a.lag) < 0.4F) a.lag = 0F;
      return a.lag;
   }

   /** Per-row cascade transform, or null when the row is settled/not scheduled. {@code [risePx, alpha]}. */
   public static float[] rowEnter(Object row) {
      Long born = ROW_BORN.get(row);
      if (born == null) return null;
      float t = (net.minecraft.util.Util.getNanos() - born) / 1.0e9F;
      if (t < 0F) return new float[]{ROW_RISE, 0F};                       // not yet its turn: hidden
      float p = 1F - (1F + ROW_ENTER_W * t) * (float) Math.exp(-ROW_ENTER_W * t);
      if (p >= 0.998F) { ROW_BORN.remove(row); return null; }
      float inv = 1F - p;
      return new float[]{ROW_RISE * inv, 1F - inv * inv};
   }

   public static void drawCards(GuiGraphicsExtractor g, List<OptionRow> rows) {
      if (!active || rows == null || rows.isEmpty()) return;
      List<LayoutBounds> b = new ArrayList<>(rows.size());
      for (OptionRow r : rows) {
         LayoutBounds d = r.getDimensions();
         if (d != null && d.height() > 0) b.add(d);
      }
      if (b.isEmpty()) return;
      b.sort(Comparator.comparingInt(LayoutBounds::y));
      int i = 0;
      while (i < b.size()) {
         int j = i;
         while (j + 1 < b.size() && b.get(j + 1).y() <= b.get(j).getLimitY() + 1) j++;
         int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
         for (int k = i; k <= j; k++) {
            x0 = Math.min(x0, b.get(k).x());
            x1 = Math.max(x1, b.get(k).getLimitX());
         }
         int y0 = b.get(i).y() + CARD_INSET, y1 = b.get(j).getLimitY() - CARD_INSET;
         GlassWidgets.panel(g, x0, y0, x1, y1, 1.0F, R + 2.0F, 0.30F);
         if (DEBUG && SEEN.add("card " + x0 + "," + y0 + "," + x1 + "," + y1)) {
            System.out.println("[RsoGlass] card x=" + x0 + ".." + x1 + " y=" + y0 + ".." + y1);
         }
         for (int k = i; k < j; k++) {                                     // inset hairlines between rows
            int y = b.get(k).getLimitY();
            GlassWidgets.fill(g, x0 + 8, y - 0.5F, x1 - 8, y, 0x24FFFFFF);
         }
         i = j + 1;
      }
   }

   // ---- iOS switch ----

   /** Per-row switch animation (same springs / look as the config screen's ToggleWidget, no drag). */
   public static final class Switch {
      final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0.0F);
      final Motion.Spring lift = new Motion.Spring(0.085F, 0.0F);
      final Motion.Clock clock = new Motion.Clock();
      boolean placed, lifted;
   }

   /** The iOS switch, right-aligned to RSO's checkbox box (drawn for both values — RSO paints no fill when off). */
   public static void drawSwitch(GuiGraphicsExtractor g, BoolRow row, int cx0, int cy0, int cx1, int cy1) {
      if (!active) return;
      Switch s = row.lg$switch();
      float dt = s.clock.tick();
      float want = row.lg$value() ? 1.0F : 0.0F;
      if (!s.placed) {
         s.travel.x = s.travel.target = want;
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

      float alpha = row.lg$enabled() ? 1.0F : 0.45F;
      int a = Math.round(alpha * 255.0F);
      float h = 12.0F, w = 26.0F;                                         // ~2.2:1 like the config screen switch
      float cy = (cy0 + cy1) / 2.0F, x1 = cx1, x0 = x1 - w, y0 = cy - h / 2.0F, y1 = cy + h / 2.0F, r = h / 2.0F;
      float pos = Motion.clamp01(s.travel.x), morph = Motion.clamp01(s.lift.x);
      GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * 0.55F) << 24) | OFF_TRACK, r);
      if (pos > 0.003F) GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);
      float khh = 0.85F * h / 2.0F, khw = khh * 1.55F;
      float tx0 = x0 + 2.0F + khw, tx1 = x1 - 2.0F - khw;
      float kx = tx0 + (tx1 - tx0) * pos;
      int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);
      GlassWidgets.knobLens(g, kx, cy, khw, khh, morph, 1.55F, 1.65F, 0.90F, x0, x1, r, Float.NaN, band, band, alpha);
   }

   // ---- glass slider ----

   public static final class Knob {
      final Motion.Spring lift = new Motion.Spring(Motion.MORPH_IN_S, 0.0F);
      final Motion.Clock clock = new Motion.Clock();
      float tx0, ty0, tx1, ty1;
      boolean hasTrack;

      void track(int x0, int y0, int x1, int y1) {
         tx0 = x0;
         ty0 = y0;
         tx1 = x1;
         ty1 = y1;
         hasTrack = true;
      }
   }

   private static void drawKnob(GuiGraphicsExtractor g, SliderRow row, float cx, float cy) {
      Knob k = row.lg$knob();
      float dt = k.clock.tick();
      k.lift.retarget(row.lg$held() ? 1.0F : 0.0F).update(dt);
      float morph = Motion.clamp01(k.lift.x);
      float trackH = 4.0F, ty = cy;
      float x0 = k.hasTrack ? k.tx0 : cx - 45.0F, x1 = k.hasTrack ? k.tx1 : cx + 45.0F;
      k.hasTrack = false;
      GlassWidgets.fillRound(g, x0, ty - trackH / 2.0F, x1, ty + trackH / 2.0F, 0x4DFFFFFF, trackH / 2.0F);
      GlassWidgets.fillRound(g, x0, ty - trackH / 2.0F, Math.max(x0 + trackH, cx), ty + trackH / 2.0F, 0xFF000000 | BLUE,
            trackH / 2.0F);
      float khh = 4.0F, khw = 6.5F;
      GlassWidgets.knobLens(g, cx, ty, khw, khh, morph, 1.5F, 1.6F, 0.9F, x0, x1, trackH / 2.0F, cx,
            0xFF000000 | BLUE, 0x4DFFFFFF, 1.0F);
   }
}
