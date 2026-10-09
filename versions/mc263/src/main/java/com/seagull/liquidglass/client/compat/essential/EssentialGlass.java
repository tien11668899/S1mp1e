package com.seagull.liquidglass.client.compat.essential;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import dev.s1mp1e.client.hud.HudGlass;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * Liquid glass for Essential's UI (Elementa on UniversalCraft).
 *
 * <p>On 26.2 UniversalCraft draws a whole Essential UI immediately into an off-screen texture
 * ({@code AdvancedDrawContext.drawImmediate} / {@code drawToTexture}) and then composites that texture into the GUI with one
 * blit ({@code AdvancedDrawContext.draw(GuiGraphicsExtractor, allocation)}). So the glass is done in two halves:
 * <ol>
 *   <li>while Elementa paints into the texture, its solid rectangles ({@code UIBlock} / {@code UIRoundedRectangle}) in
 *       Essential's palette are classified: panels, cards and buttons are <b>not</b> painted (left transparent) and recorded;
 *       hairlines that just outline a recorded piece are dropped (the glass has its own rim); dims are lightened;</li>
 *   <li>just before the composite blit the recording becomes glass in the GUI render state — panels refracting plates with
 *       a light grey scrim (readable text), cards faint rounded white scrims (the settings-shell card look), buttons glass_btn
 *       capsules (coloured ones keep a translucent tint) — so it all sits UNDER the composited texture and shows through
 *       its transparent holes.</li>
 * </ol>
 * Essential screens may run their own GUI scale ({@code UResolution}), so everything is recorded in Elementa units and
 * scaled to the vanilla GUI at emit time. A full-screen background (Essential's settings window is a full-screen
 * {@code GUI_BACKGROUND} framed by 3 px borders) becomes ONE glass plate over the window's actual extent, measured from the
 * frame and card rectangles drawn on it. Elementa's scissor is tracked so clipped content's glass is clipped too.
 * Everything is a no-op when the glass pipeline is unavailable (Essential then looks as shipped).
 */
public final class EssentialGlass {
   private EssentialGlass() {}

   private static final int PANEL = 0, BUTTON = 1, TINTED = 2, SELECTED = 3, CARD = 4, WINDOW = 5, LINE = 6, SWITCH = 7;
   /** The S1mp1e iOS switch colours (same as the config screen / RSO switch). */
   private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759;

   private static final class Entry {
      int kind, tint;
      float x0, y0, x1, y1, radius, lift;
      boolean clip;
      float cx0, cy0, cx1, cy1;
      /** SWITCH: knob travel 0..1, press morph 0..1, opacity. */
      float pos, morph, alpha = 1F;
   }

   /** Per-toggle spring state, so the glass switch slides and morphs exactly like the config-screen one. */
   private static final class SwitchState {
      final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0F);
      final Motion.Spring lift = new Motion.Spring(0.085F, 0F);
      final Motion.Clock clock = new Motion.Clock();
      boolean placed, lifted;
   }

   private static final java.util.WeakHashMap<Object, SwitchState> SWITCHES = new java.util.WeakHashMap<>();

   private static final class Frame {
      final ArrayList<Entry> entries = new ArrayList<>();
      /** Union of the frame / card rectangles (Elementa units), for a full-screen background's window extent. */
      float ux0 = Float.MAX_VALUE, uy0 = Float.MAX_VALUE, ux1 = -Float.MAX_VALUE, uy1 = -Float.MAX_VALUE;
      /** Elementa units → vanilla GUI units (Essential screens can force their own GUI scale). */
      float k = 1F;
      /** Extractor path (Essential 1.5): pixels per Elementa unit (0 = the old immediate / AdvancedDrawContext path). */
      float unit;
      /** Extractor path: the screen in Elementa units (the old path reads UResolution instead). */
      float sw, sh;
      /** Extractor path: the scissor stack, Elementa units {x0, y0, x1, y1}, already intersected. */
      final ArrayDeque<float[]> clips = new ArrayDeque<>();
   }

   private static final ArrayDeque<Frame> STACK = new ArrayDeque<>();
   private static final IdentityHashMap<Object, Frame> PENDING = new IdentityHashMap<>();
   /** Set while we re-issue an original draw with a replacement colour, so the hook lets it through. */
   public static boolean guard;

   private static boolean clipOn;
   private static float clipX0, clipY0, clipX1, clipY1;

   // ---- frame bookkeeping (AdvancedDrawContext hooks) ---------------------------------------------------------------

   public static void begin() {
      if (STACK.size() > 16) STACK.clear();   // an exception mid-draw must not leak frames forever
      Frame f = new Frame();
      try {
         int mc = Minecraft.getInstance().getWindow().getGuiScale();
         double uc = UResolution.getScaleFactor();
         if (mc > 0 && uc > 0) f.k = (float) (uc / mc);
      } catch (Throwable ignored) { }
      STACK.push(f);
   }

   /** End of an immediate draw: its composite has already been emitted inside it; drop what is left. */
   public static void end() {
      if (!STACK.isEmpty()) STACK.pop();
   }

   /** End of a draw-to-texture: keep the recording for the later composite of that allocation. */
   public static void endInto(Object allocation) {
      if (!STACK.isEmpty()) {
         Frame f = STACK.pop();
         if (allocation != null && !f.entries.isEmpty()) PENDING.put(allocation, f);
      }
   }

   /** Just before an allocation is composited into the GUI: lay the recorded glass under it. */
   public static void beforeComposite(GuiGraphicsExtractor g, Object allocation) {
      Frame f = allocation == null ? null : PENDING.remove(allocation);
      if (f == null && allocation != null) {   // extractor path: composited by its render state, recorded by its texture
         Object texture = ALIAS.remove(allocation);
         if (texture != null) f = PENDING.remove(texture);
      }
      if (f == null) {
         Frame top = STACK.peek();
         if (top != null && top.unit <= 0F) f = top;   // the old immediate path only
      }
      if (f == null || f.entries.isEmpty()) return;
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (f.unit > 0F) {
            g.pose().pushMatrix();
            g.pose().identity();
            try {
               emit(g, f);
            } finally {
               g.pose().popMatrix();
            }
         } else {
            emit(g, f);
         }
      }
      f.entries.clear();
   }

   // ---- Elementa 774+ (Essential 1.5): extract → render-to-texture → composite ------------------------------------------

   /** render state → the texture it was rendered into (the overlay composites by state, UScreen by texture). */
   private static final IdentityHashMap<Object, Object> ALIAS = new IdentityHashMap<>();

   /** A new {@code ElementaExtractorImpl}: one Essential frame starts recording (its coordinates are pixels). */
   public static void beginExtract(float guiScale, int pxWidth, int pxHeight) {
      if (STACK.size() > 16) STACK.clear();
      Frame f = new Frame();
      f.unit = guiScale > 0F ? guiScale : 1F;
      try {
         int mc = Minecraft.getInstance().getWindow().getGuiScale();
         if (mc > 0) f.k = f.unit / mc;
      } catch (Throwable ignored) { }
      f.sw = pxWidth / f.unit;
      f.sh = pxHeight / f.unit;
      STACK.push(f);
      clipOn = false;
   }

   /** Whether the innermost recording is an extractor frame (the extractor hooks only act then). */
   public static boolean extracting() {
      Frame f = STACK.peek();
      return !guard && f != null && f.unit > 0F && GlassPipeline.ensureReady() && GlassPipeline.usable();
   }

   /** {@code ElementaExtractor.fill(l, t, r, b, colour)} in pixels: the same decision as {@link #onRect}. */
   public static Color onFill(int l, int t, int r, int b, Color c) {
      Frame f = STACK.peek();
      if (f == null || f.unit <= 0F || c == null || c.getAlpha() < 24) return c;
      float u = f.unit;
      float x0 = Math.min(l, r) / u, y0 = Math.min(t, b) / u, x1 = Math.max(l, r) / u, y1 = Math.max(t, b) / u;
      Color out = classifyUnits(f, c, x0, y0, x1, y1, 0F, f.sw, f.sh);
      if (DUMP != null) dumpUnits(c, x0, y0, x1, y1, out);
      return out;
   }

   /** {@code ElementaExtractor.pushScissor(l, t, r, b)} (pixels), intersected with the enclosing one. */
   public static void pushClip(int l, int t, int r, int b) {
      Frame f = STACK.peek();
      if (f == null || f.unit <= 0F) return;
      float u = f.unit;
      float[] c = {Math.min(l, r) / u, Math.min(t, b) / u, Math.max(l, r) / u, Math.max(t, b) / u};
      float[] top = f.clips.peek();
      if (top != null) {
         c[0] = Math.max(c[0], top[0]); c[1] = Math.max(c[1], top[1]);
         c[2] = Math.min(c[2], top[2]); c[3] = Math.min(c[3], top[3]);
      }
      f.clips.push(c);
      applyClip(f);
   }

   public static void popClip() {
      Frame f = STACK.peek();
      if (f == null || f.unit <= 0F) return;
      if (!f.clips.isEmpty()) f.clips.pop();
      applyClip(f);
   }

   private static void applyClip(Frame f) {
      float[] c = f.clips.peek();
      clipOn = c != null;
      if (c != null) { clipX0 = c[0]; clipY0 = c[1]; clipX1 = c[2]; clipY1 = c[3]; }
   }

   /** {@code ElementaExtractorImpl.finish()}: keep the recording for the composite of that render state. */
   public static void endExtract(Object renderState) {
      clipOn = false;
      endInto(renderState);
   }

   /** {@code ElementaRenderer.renderToTexture(texture, …, state)}: the state's glass now belongs to that texture too. */
   public static void linkTexture(Object texture, Object renderState) {
      if (texture == null || renderState == null) return;
      Frame f = PENDING.remove(renderState);
      if (f == null) return;
      PENDING.put(texture, f);
      ALIAS.put(renderState, texture);
      if (ALIAS.size() > 64) ALIAS.clear();
   }

   // ---- scissor (UGraphics hooks) -------------------------------------------------------------------------------------

   /** {@code UGraphics.enableScissor(x, y, w, h)}: GL window pixels, origin bottom-left (Elementa's ScissorEffect). */
   public static void scissor(int x, int y, int w, int h) {
      double s = UResolution.getScaleFactor();
      int vh = UResolution.getViewportHeight();
      if (s <= 0) return;
      clipOn = true;
      clipX0 = (float) (x / s);
      clipX1 = (float) ((x + w) / s);
      clipY0 = (float) ((vh - (y + h)) / s);
      clipY1 = (float) ((vh - y) / s);
   }

   public static void noScissor() {
      clipOn = false;
   }

   // ---- classification (UIBlock / UIRoundedRectangle hooks) ------------------------------------------------------

   /** Whether a draw at this moment belongs to an Essential UI being recorded (and we are not re-issuing our own draw). */
   public static boolean recording() {
      return !guard && !STACK.isEmpty() && GlassPipeline.ensureReady() && GlassPipeline.usable();
   }

   /**
    * Decide what happens to one solid rectangle Elementa is about to paint.
    *
    * @return {@code null} to skip painting it (glass recorded instead, or a redundant outline), the same colour to paint
    *         it unchanged, or another colour to paint it with.
    */
   public static Color onRect(UMatrixStack stack, Color c, double ax0, double ay0, double ax1, double ay1, float radius) {
      Color out = classify(stack, c, ax0, ay0, ax1, ay1, radius);
      if (DUMP != null) dump(stack, c, ax0, ay0, ax1, ay1, radius, out);
      return out;
   }

   private static Color classify(UMatrixStack stack, Color c, double ax0, double ay0, double ax1, double ay1, float radius) {
      if (c == null || c.getAlpha() < 24) return c;
      Frame f = STACK.peek();
      if (f == null) return c;

      // to Elementa screen units
      Matrix3x2f m = stack.to3x2Joml(new Matrix3x2f());
      Vector2f p0 = m.transformPosition(new Vector2f((float) Math.min(ax0, ax1), (float) Math.min(ay0, ay1)));
      Vector2f p1 = m.transformPosition(new Vector2f((float) Math.max(ax0, ax1), (float) Math.max(ay0, ay1)));
      float x0 = Math.min(p0.x, p1.x), y0 = Math.min(p0.y, p1.y), x1 = Math.max(p0.x, p1.x), y1 = Math.max(p0.y, p1.y);
      float w = x1 - x0, h = y1 - y0;
      float sx = w / (float) Math.max(1e-6, Math.abs(ax1 - ax0));
      float r = radius * (Float.isFinite(sx) && sx > 0 ? sx : 1F);
      return classifyUnits(f, c, x0, y0, x1, y1, r, UResolution.getScaledWidth(), UResolution.getScaledHeight());
   }

   /** The palette rules, on a rectangle already in the frame's Elementa units (both render paths end here). */
   private static Color classifyUnits(Frame f, Color c, float x0, float y0, float x1, float y1, float r, float sw, float sh) {
      int a = c.getAlpha();
      int rgb = c.getRGB() & 0xFFFFFF;
      float w = x1 - x0, h = y1 - y0;
      boolean full = w >= sw * 0.9F && h >= sh * 0.9F;
      // scrolled out of its scroll area (fully outside the scissor): invisible anyway — must not record glass or widen the
      // window (it would show through below/above the list)
      if (clipOn && !full && (x1 <= clipX0 || x0 >= clipX1 || y1 <= clipY0 || y0 >= clipY1)) return null;

      // full-screen dims (modal / screen backdrop): keep them, but light, so the glass and the world show through
      if (full && rgb == 0x000000) {
         return a > 0x40 ? new Color(0, 0, 0, 0x40) : c;
      }
      // full-screen panel background: becomes one glass plate over the window measured from its frame (emit time)
      if (full && (isPanel(rgb) || isSurface(rgb))) {
         if (!hasKind(f, WINDOW)) add(f, WINDOW, 0, 0, sw, sh, 0F, 0F, 0);
         return null;
      }
      boolean thin = w <= 3.01F || h <= 3.01F;
      boolean frameColour = isPanel(rgb) || isSurface(rgb) || (thin && isLine(rgb));
      if (frameColour && !full) grow(f, x0, y0, x1, y1);

      // ≤3 px lines: outlines of a recorded piece are dropped (glass has its own rim); other dividers → faint hairline
      if (w <= 3.01F || h <= 3.01F) {
         Entry edge = edgeOf(f, x0, y0, x1, y1);
         if (edge != null) {
            // a white bevel edge on a list button = Essential's "selected" state (e.g. the chosen world): keep it visible
            if (edge.kind == BUTTON && isSelectEdge(rgb)) { edge.kind = SELECTED; edge.tint = 0x0A82FD; edge.lift = 0.81F; }
            return null;
         }
         if (hasKind(f, WINDOW) && (isLine(rgb) || isSurface(rgb)) && !(isScrollbar(rgb) && h >= w * 6F)) {
            add(f, LINE, x0, y0, x1, y1, 0F, 0F, 0);   // decided at emit time: dropped on the window rim
            return null;
         }
         if (isScrollbar(rgb) && w <= 8.01F && h >= w * 6F) return new Color(255, 255, 255, 0x38);
         return isLine(rgb) || isSurface(rgb) ? new Color(255, 255, 255, 0x22) : c;
      }
      // scrollbar knob: thin, tall, grey → a white Apple knob
      if (isScrollbar(rgb) && w <= 8.01F && h >= w * 2.5F) {
         return new Color(255, 255, 255, 0x99);
      }
      // the fill of a just-recorded button (outline rect, then a 1 px inset fill in any colour): fold it into that capsule
      if (h <= 40F && mergeIntoButton(f, x0, y0, x1, y1, rgb, a)) return null;

      boolean inPanel = insidePanel(f, x0, y0, x1, y1);
      // a modal's / window's own sheet (#474747 "Select world to host", #323232 the in-world hosting window): taller than any button → glass panel
      if ((rgb == 0x474747 || rgb == 0x323232) && !inPanel && w >= 60F && h > 40F) {
         grow(f, x0, y0, x1, y1);
         add(f, PANEL, x0, y0, x1, y1, r, 0F, 0);
         return null;
      }
      if (isPanel(rgb) || isSurface(rgb)) {
         if (!inPanel && w >= 40F && h >= 24F) {
            add(f, PANEL, x0, y0, x1, y1, r, 0F, 0);
            return null;
         }
         if (w >= 24F && h >= 12F) {   // a card / row on the glass sheet: faint rounded white scrim (settings-shell card)
            add(f, CARD, x0, y0, x1, y1, r, 0F, 0);
            return null;
         }
         return new Color(255, 255, 255, 0x14);
      }
      if (isSelected(rgb)) {
         add(f, SELECTED, x0, y0, x1, y1, r, 0.81F, 0x0A82FD);
         return null;
      }
      if (h <= 40F && w <= 420F && w >= 12F && h >= 10F && rgb == 0xFFFFFF && a == 255) {
         add(f, BUTTON, x0, y0, x1, y1, r, 0.81F, 0);
         return null;
      }
      if (h <= 40F && w <= 420F && (isGreyButton(rgb) || isOutline(rgb) || (rgb == 0x000000 && a == 255))) {
         add(f, BUTTON, x0, y0, x1, y1, r, rgb == 0x474747 ? 0.81F : 0F, 0);
         return null;
      }
      int tint = tintOf(rgb);
      if (tint != 0 && h <= 40F) {
         add(f, TINTED, x0, y0, x1, y1, r, isTintHover(rgb) ? 0.81F : 0F, tint);
         return null;
      }
      return c;
   }

   /**
    * Essential's {@code MenuButton} (main/pause-menu side bar, "Essential" buttons) paints its own bevelled box through its
    * own pipeline, not {@code UIBlock}: record it as one glass_btn capsule instead.
    *
    * @return whether it was taken over (the caller then skips the original draw)
    */
   public static boolean menuButton(UMatrixStack stack, double ax0, double ay0, double ax1, double ay1, Color background) {
      if (!recording()) return false;
      Frame f = STACK.peek();
      Matrix3x2f m = stack.to3x2Joml(new Matrix3x2f());
      Vector2f p0 = m.transformPosition(new Vector2f((float) Math.min(ax0, ax1), (float) Math.min(ay0, ay1)));
      Vector2f p1 = m.transformPosition(new Vector2f((float) Math.max(ax0, ax1), (float) Math.max(ay0, ay1)));
      float x0 = Math.min(p0.x, p1.x), y0 = Math.min(p0.y, p1.y), x1 = Math.max(p0.x, p1.x), y1 = Math.max(p0.y, p1.y);
      return menuButtonUnits(f, x0, y0, x1, y1, background);
   }

   /** Essential 1.5 {@code MenuButton.extractButton}: Elementa units (it multiplies by the extractor's GUI scale itself). */
   public static boolean menuButtonExtract(double ax0, double ay0, double ax1, double ay1, Color background) {
      if (!extracting()) return false;
      return menuButtonUnits(STACK.peek(), (float) Math.min(ax0, ax1), (float) Math.min(ay0, ay1),
            (float) Math.max(ax0, ax1), (float) Math.max(ay0, ay1), background);
   }

   private static boolean menuButtonUnits(Frame f, float x0, float y0, float x1, float y1, Color background) {
      if (x1 - x0 < 2F || y1 - y0 < 2F) return false;
      int rgb = background == null ? 0 : background.getRGB() & 0xFFFFFF;
      // a lighter-than-rest face is the hovered state → lifted
      float lum = ((rgb >> 16 & 0xFF) * 0.299F + (rgb >> 8 & 0xFF) * 0.587F + (rgb & 0xFF) * 0.114F) / 255F;
      int tint = tintOf(rgb);
      add(f, tint != 0 ? TINTED : BUTTON, x0, y0, x1, y1, 0F, lum > 0.17F ? 0.81F : 0F, tint);
      return true;
   }

   /** Whether the Essential UI being drawn right now has had its full-window background replaced by a glass plate. */
   public static boolean windowGlassActive() {
      Frame f = STACK.peek();
      return f != null && hasKind(f, WINDOW);
   }

   /** The Essential screen whose last frame laid a full-window glass plate (it needs the vanilla backdrop behind it). */
   private static java.lang.ref.WeakReference<Object> windowScreen = new java.lang.ref.WeakReference<>(null);

   /** Whether this Essential screen draws a full-window glass plate, so it must get the vanilla menu backdrop. */
   public static boolean wantsBackdrop(Object screen) {
      return screen != null && windowScreen.get() == screen;
   }

   /**
    * Essential's toggle switch (blue / grey squares) → the S1mp1e iOS glass switch (green track, white lens knob that
    * slides on the config screen's spring). Takes over the whole draw while an Essential UI is being recorded.
    *
    * @return whether it was taken over (the caller then skips the original draw)
    */
   public static boolean toggle(Object owner, UMatrixStack stack, float l, float t, float r, float b, boolean value, boolean enabled) {
      if (!recording()) return false;
      Frame f = STACK.peek();
      Matrix3x2f m = stack.to3x2Joml(new Matrix3x2f());
      Vector2f p0 = m.transformPosition(new Vector2f(Math.min(l, r), Math.min(t, b)));
      Vector2f p1 = m.transformPosition(new Vector2f(Math.max(l, r), Math.max(t, b)));
      float x0 = Math.min(p0.x, p1.x), y0 = Math.min(p0.y, p1.y), x1 = Math.max(p0.x, p1.x), y1 = Math.max(p0.y, p1.y);
      return toggleUnits(f, owner, x0, y0, x1, y1, value, enabled);
   }

   /** Essential 1.5 toggle ({@code extractComponent}): its bounds are already Elementa units. */
   public static boolean toggleExtract(Object owner, float l, float t, float r, float b, boolean value, boolean enabled) {
      if (!extracting()) return false;
      return toggleUnits(STACK.peek(), owner, Math.min(l, r), Math.min(t, b), Math.max(l, r), Math.max(t, b), value, enabled);
   }

   private static boolean toggleUnits(Frame f, Object owner, float x0, float y0, float x1, float y1, boolean value, boolean enabled) {
      if (x1 - x0 < 4F || y1 - y0 < 2F) return false;
      SwitchState s = SWITCHES.computeIfAbsent(owner, o -> new SwitchState());
      float dt = s.clock.tick();
      float want = value ? 1F : 0F;
      if (!s.placed) {
         s.travel.x = s.travel.target = want;
         s.placed = true;
      }
      if (s.travel.target != want) {
         s.travel.retarget(want);
         s.lifted = true;
         s.lift.tune(0.085F, 0F).retarget(1F);
      }
      s.travel.update(dt);
      if (s.lifted && Math.abs(s.travel.target - s.travel.x) < 0.08F) {
         s.lifted = false;
         s.lift.tune(Motion.MORPH_OUT_S, 0F).retarget(0F);
      }
      s.lift.update(dt);
      add(f, SWITCH, x0, y0, x1, y1, 0F, 0F, 0);
      Entry e = f.entries.get(f.entries.size() - 1);
      e.pos = Motion.clamp01(s.travel.x);
      e.morph = Motion.clamp01(s.lift.x);
      e.alpha = enabled ? 1F : 0.45F;
      return true;
   }

   /** The iOS switch in a box (GUI units): capsule track ~2.2:1 right-aligned, lens knob. Same recipe as RsoGlass. */
   private static void drawSwitch(GuiGraphicsExtractor g, float bx0, float by0, float bx1, float by1, float pos, float morph, float alpha) {
      float h = Math.min(by1 - by0, (bx1 - bx0) / 2.17F), w = h * 2.17F;
      float cy = (by0 + by1) / 2F, x1 = bx1, x0 = x1 - w, y0 = cy - h / 2F, y1 = cy + h / 2F, r = h / 2F;
      int a = Math.round(alpha * 255F);
      GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * 0.55F) << 24) | OFF_TRACK, r);
      if (pos > 0.003F) GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);
      float pad = h / 6F, khh = 0.85F * h / 2F, khw = khh * 1.55F;
      float tx0 = x0 + pad + khw, tx1 = x1 - pad - khw;
      float kx = tx0 + (tx1 - tx0) * pos;
      int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);
      GlassWidgets.knobLens(g, kx, cy, khw, khh, morph, 1.55F, 1.65F, 0.90F, x0, x1, r, Float.NaN, band, band, alpha);
   }

   private static void add(Frame f, int kind, float x0, float y0, float x1, float y1, float r, float lift, int tint) {
      Entry e = new Entry();
      e.kind = kind;
      e.x0 = x0; e.y0 = y0; e.x1 = x1; e.y1 = y1;
      e.radius = r; e.lift = lift; e.tint = tint;
      e.clip = clipOn;
      e.cx0 = clipX0; e.cy0 = clipY0; e.cx1 = clipX1; e.cy1 = clipY1;
      f.entries.add(e);
   }

   /** A sheet's corner: capped like the S1mp1e config panel, so a full-screen window is not over-rounded. */
   private static float sheetRadius(float w, float h) {
      return Math.min(14F, Math.min(w, h) * 0.0475F);
   }

   private static float[] sheet(float x0, float y0, float x1, float y1) {
      return new float[] {x0, y0, x1, y1, sheetRadius(x1 - x0, y1 - y0)};
   }

   /** Gap between the sheet edge and a bar inside it that hugs that edge (GUI px); bars nearer than SNAP are moved to it. */
   private static final float EDGE_GAP = 4F, EDGE_SNAP = 8F;

   /** The smallest sheet that contains this piece (and is not the piece itself), or null. */
   private static float[] container(java.util.List<float[]> sheets, float x0, float y0, float x1, float y1) {
      float[] best = null;
      for (float[] s : sheets) {
         if (x0 >= s[0] - 0.5F && y0 >= s[1] - 0.5F && x1 <= s[2] + 0.5F && y1 <= s[3] + 0.5F
               && (x1 - x0 < s[2] - s[0] - 0.01F || y1 - y0 < s[3] - s[1] - 0.01F)
               && (best == null || (s[2] - s[0]) * (s[3] - s[1]) < (best[2] - best[0]) * (best[3] - best[1]))) best = s;
      }
      return best;
   }

   /**
    * Concentric corner of a piece that sits in a corner of its sheet (outer radius − inset), so the two curves run
    * parallel instead of fighting; −1 when the piece is not in a sheet corner (it keeps the hotbar radius).
    */
   private static float concentric(java.util.List<float[]> sheets, float x0, float y0, float x1, float y1) {
      float[] best = container(sheets, x0, y0, x1, y1);
      if (best == null) return -1F;
      float r = best[4];
      float dx = Math.max(0F, Math.min(x0 - best[0], best[2] - x1)), dy = Math.max(0F, Math.min(y0 - best[1], best[3] - y1));
      if (dx >= r || dy >= r) return -1F;
      return Math.max(2F, r - Math.max(dx, dy));
   }

   /** Essential's light bevel edge of a selected button (#E5E5E5 and near-white greys). */
   private static boolean isSelectEdge(int rgb) {
      int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
      return r >= 0xC8 && Math.abs(r - g) <= 4 && Math.abs(g - b) <= 4;
   }

   /** A greyscale fade to transparent (Essential's scroll fades into its flat panel colours), not an opaque ramp. */
   public static boolean isGreyFade(Color a, Color b) {
      return a != null && b != null && grey(a) && grey(b) && Math.min(a.getAlpha(), b.getAlpha()) < 16;
   }

   private static boolean grey(Color c) {
      int r = c.getRed(), g = c.getGreen(), b = c.getBlue();
      return Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2;
   }

   private static void grow(Frame f, float x0, float y0, float x1, float y1) {
      if (clipOn) {   // only the part inside the scroll area's scissor is on screen
         x0 = Math.max(x0, clipX0); y0 = Math.max(y0, clipY0); x1 = Math.min(x1, clipX1); y1 = Math.min(y1, clipY1);
         if (x1 <= x0 || y1 <= y0) return;
      }
      f.ux0 = Math.min(f.ux0, x0); f.uy0 = Math.min(f.uy0, y0);
      f.ux1 = Math.max(f.ux1, x1); f.uy1 = Math.max(f.uy1, y1);
   }

   private static boolean hasKind(Frame f, int kind) {
      for (Entry e : f.entries) if (e.kind == kind) return true;
      return false;
   }

   private static boolean insidePanel(Frame f, float x0, float y0, float x1, float y1) {
      for (Entry e : f.entries) {
         if ((e.kind == PANEL || e.kind == WINDOW) && x0 >= e.x0 - 0.5F && y0 >= e.y0 - 0.5F && x1 <= e.x1 + 0.5F && y1 <= e.y1 + 0.5F) {
            return true;
         }
      }
      return false;
   }

   /** A thin line lying along an edge of a recorded panel / card / button (its outline). */
   private static boolean onEdgeOfRecorded(Frame f, float x0, float y0, float x1, float y1) {
      return edgeOf(f, x0, y0, x1, y1) != null;
   }

   /** The most recent recorded piece (not the window) whose edge this thin line runs along, or null. */
   private static Entry edgeOf(Frame f, float x0, float y0, float x1, float y1) {
      final float T = 1.6F;
      for (int i = f.entries.size() - 1; i >= 0; i--) {
         Entry e = f.entries.get(i);
         if (e.kind == WINDOW) continue;
         boolean horiz = (x1 - x0) >= (y1 - y0);
         if (horiz) {
            boolean spans = x0 >= e.x0 - T && x1 <= e.x1 + T;
            boolean onEdge = Math.abs(y0 - e.y0) <= T || Math.abs(y1 - e.y1) <= T || Math.abs(y1 - e.y0) <= T || Math.abs(y0 - e.y1) <= T;
            if (spans && onEdge) return e;
         } else {
            boolean spans = y0 >= e.y0 - T && y1 <= e.y1 + T;
            boolean onEdge = Math.abs(x0 - e.x0) <= T || Math.abs(x1 - e.x1) <= T || Math.abs(x1 - e.x0) <= T || Math.abs(x0 - e.x1) <= T;
            if (spans && onEdge) return e;
         }
      }
      return null;
   }

   /** Outline-then-fill buttons paint two nested rects; keep ONE capsule (the outer) and take the fill's state. */
   private static boolean mergeIntoButton(Frame f, float x0, float y0, float x1, float y1, int rgb, int a) {
      if (f.entries.isEmpty()) return false;
      Entry e = f.entries.get(f.entries.size() - 1);
      if (e.kind != BUTTON && e.kind != TINTED && e.kind != SELECTED) return false;
      if (x0 >= e.x0 - 0.5F && y0 >= e.y0 - 0.5F && x1 <= e.x1 + 0.5F && y1 <= e.y1 + 0.5F
            && (e.x1 - e.x0) - (x1 - x0) <= 4.01F && (e.y1 - e.y0) - (y1 - y0) <= 4.01F) {
         int tint = tintOf(rgb);
         if (tint != 0) {
            e.kind = TINTED;
            e.tint = tint;
            if (isTintHover(rgb)) e.lift = 0.81F;
         } else if (rgb == 0x474747) {
            e.lift = 0.81F;
         }
         return true;
      }
      return false;
   }

   private static void emit(GuiGraphicsExtractor g, Frame f) {
      float k = f.k;
      // the sheets (window / stand-alone panels) in GUI px, for concentric corners of the pieces inside them
      java.util.List<float[]> sheets = new java.util.ArrayList<>();
      for (Entry e : f.entries) {
         if (e.kind == WINDOW && f.ux1 > f.ux0 && f.uy1 > f.uy0) sheets.add(sheet(f.ux0 * k, f.uy0 * k, f.ux1 * k, f.uy1 * k));
         else if (e.kind == PANEL) sheets.add(sheet(e.x0 * k, e.y0 * k, e.x1 * k, e.y1 * k));
      }
      for (Entry e : f.entries) {
         float x0 = e.x0, y0 = e.y0, x1 = e.x1, y1 = e.y1;
         if (e.kind == WINDOW) {
            try {
               windowScreen = new java.lang.ref.WeakReference<>(Minecraft.getInstance().gui.screen());
            } catch (Throwable ignored) { }
            if (f.ux1 <= f.ux0 || f.uy1 <= f.uy0) continue;
            x0 = f.ux0; y0 = f.uy0; x1 = f.ux1; y1 = f.uy1;
         }
         x0 *= k; y0 *= k; x1 *= k; y1 *= k;
         if (e.kind != LINE && (x1 - x0 < 1F || y1 - y0 < 1F)) continue;
         if (e.clip) {
            float cx0 = e.cx0 * k, cy0 = e.cy0 * k, cx1 = e.cx1 * k, cy1 = e.cy1 * k;
            if (cx1 <= cx0 || cy1 <= cy0) continue;
            // the mirrored scissor: our custom glass render states only clip through GlassWidgets' own stack
            GlassWidgets.enableScissor(g, (int) Math.floor(cx0), (int) Math.floor(cy0), (int) Math.ceil(cx1), (int) Math.ceil(cy1));
         }
         // a bar hugging the sheet edge (header / footer / side strip): one uniform gap. Not a list row cut by its scroll
         // area's scissor (that one is only partly on screen, its geometry must not move)
         boolean whole = !e.clip || (x0 >= e.cx0 * k - 0.5F && y0 >= e.cy0 * k - 0.5F && x1 <= e.cx1 * k + 0.5F && y1 <= e.cy1 * k + 0.5F);
         if (e.kind == CARD && whole) {
            float[] sh = container(sheets, x0, y0, x1, y1);
            if (sh != null) {
               if (x0 - sh[0] < EDGE_SNAP) x0 = sh[0] + EDGE_GAP;
               if (y0 - sh[1] < EDGE_SNAP) y0 = sh[1] + EDGE_GAP;
               if (sh[2] - x1 < EDGE_SNAP) x1 = sh[2] - EDGE_GAP;
               if (sh[3] - y1 < EDGE_SNAP) y1 = sh[3] - EDGE_GAP;
               if (x1 - x0 < 1F || y1 - y0 < 1F) { if (e.clip) GlassWidgets.disableScissor(g); continue; }
            }
         }
         float h = y1 - y0;
         float half = Math.max(1.0F, Math.min(x1 - x0, h) / 2.0F);
         float rpx = Math.min(GlassCorners.HOTBAR_RADIUS, half);
         if (e.kind != PANEL && e.kind != WINDOW) {
            float cr = concentric(sheets, x0, y0, x1, y1);
            if (cr >= 0F) rpx = Math.min(cr, half);
         }
         float corner = Math.min(1.0F, rpx / half);
         switch (e.kind) {
            case PANEL, WINDOW -> {
               int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x1), iy1 = Math.round(y1);
               float sr = sheetRadius(ix1 - ix0, iy1 - iy0);
               GlassSurface.plate(g, ix0, iy0, ix1, iy1, 0xFF, sr);
               // a light grey scrim so Essential's grey/white text reads on bright backdrops (LOOK SPEC: scrim under content)
               GlassSurface.scrim(g, ix0, iy0, ix1, iy1, sr, 0x38101014);
            }
            case CARD -> GlassWidgets.fillRound(g, x0, y0, x1, y1, 0x16FFFFFF, rpx);
            case LINE -> {
               boolean horiz = (x1 - x0) >= (y1 - y0);
               float ux0 = f.ux0 * k, uy0 = f.uy0 * k, ux1 = f.ux1 * k, uy1 = f.uy1 * k, near = 2.5F * Math.max(k, 0.5F);
               boolean rim = horiz ? (Math.abs(y0 - uy0) <= near || Math.abs(y1 - uy1) <= near)
                                   : (Math.abs(x0 - ux0) <= near || Math.abs(x1 - ux1) <= near);
               float[] ls = container(sheets, x0, y0, x1, y1);
               if (ls != null) {   // a divider stops at the same uniform gap from the sheet edge as the bars
                  if (horiz) { x0 = Math.max(x0, ls[0] + EDGE_GAP); x1 = Math.min(x1, ls[2] - EDGE_GAP); }
                  else { y0 = Math.max(y0, ls[1] + EDGE_GAP); y1 = Math.min(y1, ls[3] - EDGE_GAP); }
               }
               if (!rim) {   // an inner divider: a 0.5 px hairline down its middle
                  if (horiz) { float cy = (y0 + y1) / 2F; GlassWidgets.fillRound(g, x0, cy - 0.25F, x1, cy + 0.25F, 0x2EFFFFFF, 0F); }
                  else { float cx = (x0 + x1) / 2F; GlassWidgets.fillRound(g, cx - 0.25F, y0, cx + 0.25F, y1, 0x2EFFFFFF, 0F); }
               }
            }
            case SWITCH -> drawSwitch(g, x0, y0, x1, y1, e.pos, e.morph, e.alpha);
            case BUTTON -> {
               GlassWidgets.capsule(g, x0, y0, x1, y1, corner, e.lift, 1.0F, true);
               // the same light grey scrim as the panels (LOOK SPEC: scrim under content): Essential's light-grey labels
               // sit on raw world/panorama on the title side menu, and with text shadows off a clear capsule over a
               // bright block left them unreadable
               GlassWidgets.fillRound(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x38101014, Math.max(0F, rpx - 1F));
            }
            case TINTED, SELECTED -> {
               GlassWidgets.capsule(g, x0, y0, x1, y1, corner, e.lift, 1.0F, true);
               // keep the button's meaning (blue = primary, red = destructive, green = confirm …) as a translucent tint
               int alpha = e.kind == SELECTED ? 0x38 : 0x66;
               GlassWidgets.fillRound(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, alpha << 24 | (e.tint & 0xFFFFFF), Math.max(0F, rpx - 1F));
            }
            default -> { }
         }
         if (e.clip) GlassWidgets.disableScissor(g);
      }
   }

   // ---- dev trace ------------------------------------------------------------------------------------------------------

   /** Dev only ({@code S1MP1E_ESS_DUMP=<file>}): one line per distinct rect kind, to tune the palette rules. */
   private static final String DUMP = System.getenv("S1MP1E_ESS_DUMP");
   private static final java.util.Set<String> DUMPED = new java.util.HashSet<>();
   private static final Color IDLE = new Color(0, 0, 0, 0);

   private static final java.util.Set<String> TRACED = new java.util.HashSet<>();

   /** Dev trace of one event (once per distinct message). */
   public static void trace(String msg) {
      if (DUMP == null || !TRACED.add(msg)) return;
      try {
         java.nio.file.Files.writeString(java.nio.file.Path.of(DUMP), "TRACE " + msg + System.lineSeparator(),
               java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Throwable ignored) { }
   }

   /** Dev trace of a draw that happened OUTSIDE any recording frame (not an Essential off-screen pass). */
   public static void idle(UMatrixStack stack, Color c, double ax0, double ay0, double ax1, double ay1, float radius) {
      if (DUMP != null && !guard && c != null) dump(stack, c, ax0, ay0, ax1, ay1, radius, IDLE);
   }

   private static void dumpUnits(Color c, float x0, float y0, float x1, float y1, Color out) {
      try {
         String decision = out == null ? "GLASS" : out == c ? "keep" : String.format("recolor#%08X", out.getRGB());
         String key = String.format("X #%06X a=%d w=%d h=%d %s", c.getRGB() & 0xFFFFFF, c.getAlpha(),
               Math.round(x1 - x0), Math.round(y1 - y0), decision);
         if (!DUMPED.add(key)) return;
         java.nio.file.Files.writeString(java.nio.file.Path.of(DUMP),
               key + String.format(" at=%.0f,%.0f", x0, y0) + System.lineSeparator(),
               java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Throwable ignored) { }
   }

   private static void dump(UMatrixStack stack, Color c, double ax0, double ay0, double ax1, double ay1, float radius, Color out) {
      try {
         Matrix3x2f m = stack.to3x2Joml(new Matrix3x2f());
         Vector2f p0 = m.transformPosition(new Vector2f((float) ax0, (float) ay0));
         Vector2f p1 = m.transformPosition(new Vector2f((float) ax1, (float) ay1));
         String decision = out == IDLE ? "IDLE" : out == null ? "GLASS" : out == c ? "keep" : String.format("recolor#%08X", out.getRGB());
         String key = String.format("#%06X a=%d w=%d h=%d r=%.0f %s", c.getRGB() & 0xFFFFFF, c.getAlpha(),
               Math.round(Math.abs(p1.x - p0.x)), Math.round(Math.abs(p1.y - p0.y)), radius, decision);
         if (!DUMPED.add(key)) return;
         String screen;
         try {
            net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().gui.screen();
            screen = s == null ? "-" : s.getClass().getSimpleName();
         } catch (Throwable t) {
            screen = "?";
         }
         java.nio.file.Files.writeString(java.nio.file.Path.of(DUMP),
               key + String.format(" at=%.0f,%.0f", Math.min(p0.x, p1.x), Math.min(p0.y, p1.y)) + " scr=" + screen + System.lineSeparator(),
               java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Throwable ignored) { }
   }

   // ---- Essential palette (gg.essential.gui.EssentialPalette, 1.4.x) ---------------------------------------------

   private static boolean isPanel(int rgb) {
      // GUI_BACKGROUND, TOAST_BACKGROUND, INPUT_MODAL_BACKGROUND, DARK_TRANSPARENT_BACKGROUND
      return rgb == 0x181818 || rgb == 0x1E1E1E || rgb == 0x121212 || rgb == 0x111111 || rgb == 0x0F0F0F;
   }

   private static boolean isSurface(int rgb) {
      // COMPONENT_BACKGROUND, INPUT_BACKGROUND, COMPONENT_HIGHLIGHT / LIGHT_DIVIDER, RECEIVED_MESSAGE, SEARCHBAR,
      // PURCHASE_CONFIRMATION_MODAL_SECONDARY
      return rgb == 0x232323 || rgb == 0x1C1C1C || rgb == 0x303030 || rgb == 0x333333 || rgb == 0x444444
            || rgb == 0x2A2A2A || rgb == 0x262626 || rgb == 0x1F1F1F;
   }

   private static boolean isGreyButton(int rgb) {
      // BUTTON / GRAY_BUTTON / COMPONENT_BACKGROUND_HIGHLIGHT (rest), BUTTON_HIGHLIGHT / GRAY_BUTTON_HOVER (hover)
      return rgb == 0x323232 || rgb == 0x474747;
   }

   private static boolean isOutline(int rgb) {
      // GRAY_OUTLINE_BUTTON_OUTLINE(+hover), MODAL_OUTLINE, GRAY_OUTLINE, CHECKBOX_OUTLINE
      return rgb == 0x5C5C5C || rgb == 0x757575 || rgb == 0x3F3F3F || rgb == 0x424242;
   }

   private static boolean isLine(int rgb) {
      return rgb == 0x474747 || rgb == 0x303030 || rgb == 0x3F3F3F || rgb == 0x424242 || rgb == 0x5C5C5C
            || rgb == 0x323232 || rgb == 0x232323 || rgb == 0x757575 || rgb == 0x555555;
   }

   private static boolean isScrollbar(int rgb) {
      return rgb == 0x5C5C5C || rgb == 0x555555 || rgb == 0x757575 || rgb == 0x474747;
   }

   private static boolean isSelected(int rgb) {
      // COMPONENT_SELECTED(+hover), PINNED_COMPONENT_BACKGROUND, DARK_TRANSPARENT_BACKGROUND_HIGHLIGHTED
      return rgb == 0x121E30 || rgb == 0x1E2A3C || rgb == 0x111E30 || rgb == 0x030C18;
   }

   /** The hue to keep for a coloured button (rest + hover + outline variants), or 0. */
   private static int tintOf(int rgb) {
      switch (rgb) {
         case 0x274673, 0x2F5FA4, 0x223F69, 0x2A5695, 0x3671C7, 0x5490E8: return 0x2F7BFF;          // blue
         case 0x642626, 0x9F4444, 0x461F1F, 0x8B3636: return 0xFF453A;                              // red
         case 0x1D4728, 0x327B44, 0x276136, 0x3E9252: return 0x30D158;                              // green
         case 0x734317, 0xA36226, 0x583E14, 0x74521D, 0x8F621F, 0xBA8537: return 0xFF9F0A;          // yellow
         case 0x473999, 0x5947BF, 0x292063, 0x352A7A, 0x6F5CE5: return 0xBF5AF2;                    // purple
         default: return 0;
      }
   }

   private static boolean isTintHover(int rgb) {
      return rgb == 0x2F5FA4 || rgb == 0x2A5695 || rgb == 0x9F4444 || rgb == 0x327B44
            || rgb == 0x276136 || rgb == 0xA36226 || rgb == 0x74521D || rgb == 0x5947BF || rgb == 0x352A7A;
   }
}
