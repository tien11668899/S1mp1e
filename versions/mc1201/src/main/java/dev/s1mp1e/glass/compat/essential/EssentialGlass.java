package dev.s1mp1e.glass.compat.essential;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.WeakHashMap;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import gg.essential.universal.UMatrixStack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Liquid glass for Essential's UI (Elementa on UniversalCraft) — the 1.21.1 counterpart of 26.2's {@code EssentialGlass}.
 *
 * <p>On 1.21.1 Elementa paints straight into the main framebuffer (no off-screen pass), with Elementa's GL scissor live,
 * over a background the screen has already drawn. So the glass is drawn <b>at the moment</b> Elementa would paint the
 * surface, and that surface is skipped: panels become refracting glass plates with a light grey scrim, cards faint rounded
 * white scrims, buttons glass_btn capsules, toggles the S1mp1e iOS switch; dividers become hairlines and outlines of a
 * glass piece are dropped. A frame is one Elementa {@code Window.draw} (screens, overlays and the main-menu side bar all
 * draw through one).
 *
 * <p>Essential's full-screen windows have no panel of their own (a full-screen {@code GUI_BACKGROUND} framed by 3 px
 * borders): the full-screen fill is skipped and ONE glass plate is laid over the window's extent as measured from the
 * frame / card rectangles of the PREVIOUS frame of that Window (it does not move); the borders on that rim are dropped.
 * Everything is a no-op when the glass program is unavailable (Essential then looks as shipped).
 */
public final class EssentialGlass {
   private EssentialGlass() {}

   private static final int PANEL = 0, BUTTON = 1, CARD = 2;
   private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759;

   private static final class Rect {
      final int kind;
      final float x0, y0, x1, y1;
      /** a BUTTON already tinted as Essential's "selected" state (its white bevel edges come as several lines) */
      boolean selected;
      Rect(int kind, float x0, float y0, float x1, float y1) { this.kind = kind; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; }
   }

   /** One recorded piece of an extracted Essential frame, replayed as glass at the composite. */
   private static final class Op {
      static final int RECT = 0, MENU = 1, SWITCH = 2;
      int kind;
      float x0, y0, x1, y1;
      Color c;
      Object owner;
      boolean value, enabled;
      boolean clip;
      float cx0, cy0, cx1, cy1;
   }

   private static final class Frame {
      /** Essential 1.5 extractor frame: decisions now, glass at the composite (pixels × k = GUI units). */
      boolean extract;
      float k = 1F;
      final ArrayList<Op> ops = new ArrayList<>();
      final ArrayDeque<float[]> clips = new ArrayDeque<>();
      Object owner;
      final ArrayList<Rect> drawn = new ArrayList<>();
      boolean window, grabbed;
      float ux0 = Float.MAX_VALUE, uy0 = Float.MAX_VALUE, ux1 = -Float.MAX_VALUE, uy1 = -Float.MAX_VALUE;
      float[] prev;
   }

   private static final class SwitchState {
      final Motion.Spring travel = new Motion.Spring(Motion.TRAVEL_S, 0F);
      final Motion.Spring lift = new Motion.Spring(0.085F, 0F);
      final Motion.Clock clock = new Motion.Clock();
      boolean placed, lifted;
   }

   private static final ArrayDeque<Frame> STACK = new ArrayDeque<>();
   /** Window → its frame/card extent last frame (the full-screen window's glass plate). */
   private static final WeakHashMap<Object, float[]> UNIONS = new WeakHashMap<>();
   private static final WeakHashMap<Object, SwitchState> SWITCHES = new WeakHashMap<>();
   /** Set while we re-issue an original draw with a replacement colour, so the hook lets it through. */
   public static boolean guard;
   /** Whether this rendered frame's backdrop has been grabbed (reset by {@link #newFrame}). */
   private static boolean grabbedThisFrame;
   private static long frameStartNanos;

   private static boolean clipOn;
   private static float clipX0, clipY0, clipX1, clipY1;

   /**
    * {@code UGraphics.enableScissor(x, y, w, h)}: GL framebuffer pixels, origin bottom-left (Elementa's ScissorEffect) →
    * GUI units, so a piece scrolled out of its list is neither drawn as glass nor counted into the window's extent.
    */
   public static void scissor(int x, int y, int w, int h) {
      try {
         net.minecraft.client.util.Window win = MinecraftClient.getInstance().getWindow();
         double s = win.getScaleFactor();
         int fh = win.getFramebufferHeight();
         if (s <= 0) return;
         clipOn = true;
         clipX0 = (float) (x / s);
         clipX1 = (float) ((x + w) / s);
         clipY0 = (float) ((fh - (y + h)) / s);
         clipY1 = (float) ((fh - y) / s);
      } catch (Throwable ignored) { }
   }

   public static void noScissor() {
      clipOn = false;
   }

   /** Start of a rendered frame (MinecraftClient.render HEAD). */
   public static void newFrame() {
      grabbedThisFrame = false;
      frameStartNanos = System.nanoTime();
   }

   // ---- frame bookkeeping (Window.draw hooks) -------------------------------------------------------------------------

   public static void begin(Object owner) {
      if (STACK.size() > 16) STACK.clear();
      Frame f = new Frame();
      f.owner = owner;
      f.prev = UNIONS.get(owner);
      STACK.push(f);
   }

   public static void end() {
      if (STACK.isEmpty()) return;
      Frame f = STACK.pop();
      if (f.owner != null && f.window && f.ux1 > f.ux0 && f.uy1 > f.uy0) {
         UNIONS.put(f.owner, new float[] {f.ux0, f.uy0, f.ux1, f.uy1});
      }
   }

   public static boolean recording() {
      return !guard && !STACK.isEmpty() && GlassProgram.ensureReady() && GlassProgram.usable();
   }

   // ---- Essential 1.5 (Elementa 774): extract → render-to-texture → composite ------------------------------------------
   //
   // On 1.21.1 Essential 1.5 no longer paints into the framebuffer either: an ElementaExtractorImpl records the frame, it is
   // rendered into a texture and composited (UScreen.drawImmediate / LayerRenderer). The fill decides only (a DRY pass of
   // the same palette rules, so glass pieces stay out of the texture) and is recorded; the glass is drawn when the texture
   // is composited, by replaying the recording through the immediate rules — the whole window extent is known by then.

   /** Set during an extract decision pass: the draw helpers do nothing. */
   private static boolean dry;
   private static final java.util.IdentityHashMap<Object, Frame> PENDING = new java.util.IdentityHashMap<>();
   private static final java.util.IdentityHashMap<Object, Object> ALIAS = new java.util.IdentityHashMap<>();

   public static void beginExtract(int pxWidth) {
      if (STACK.size() > 16) STACK.clear();
      Frame f = new Frame();
      f.extract = true;
      try {
         int sw = MinecraftClient.getInstance().getWindow().getScaledWidth();
         if (pxWidth > 0 && sw > 0) f.k = sw / (float) pxWidth;
      } catch (Throwable ignored) { }
      STACK.push(f);
      clipOn = false;
   }

   public static boolean extracting() {
      Frame f = STACK.peek();
      return !guard && f != null && f.extract && GlassProgram.ensureReady() && GlassProgram.usable();
   }

   private static Op op(Frame f, int kind, float x0, float y0, float x1, float y1) {
      Op o = new Op();
      o.kind = kind;
      o.x0 = x0; o.y0 = y0; o.x1 = x1; o.y1 = y1;
      o.clip = clipOn;
      o.cx0 = clipX0; o.cy0 = clipY0; o.cx1 = clipX1; o.cy1 = clipY1;
      f.ops.add(o);
      return o;
   }

   /** {@code ElementaExtractorImpl.fill(l, t, r, b, colour)} (pixels). */
   public static Color onFill(int l, int t, int r, int b, Color c) {
      Frame f = STACK.peek();
      if (f == null || !f.extract || c == null || c.getAlpha() < 24) return c;
      float x0 = Math.min(l, r) * f.k, y0 = Math.min(t, b) * f.k, x1 = Math.max(l, r) * f.k, y1 = Math.max(t, b) * f.k;
      Color out;
      dry = true;
      try {
         out = onRectAbs(f, c, x0, y0, x1, y1);
      } finally {
         dry = false;
      }
      if (out != c) op(f, Op.RECT, x0, y0, x1, y1).c = c;
      return out;
   }

   /** Pixels per Elementa unit of an extractor ({@code ElementaExtractor.getGuiScale()}), times the frame's px → GUI. */
   private static float unitScale(Frame f, Object extractor) {
      try {
         Object g = extractor.getClass().getMethod("getGuiScale").invoke(extractor);
         if (g instanceof Float fl && fl > 0F) return fl * f.k;
      } catch (Throwable ignored) { }
      return f.k;
   }

   /** Essential 1.5 {@code MenuButton.extractButton} on an extractor frame (Elementa units). */
   public static boolean menuButtonExtract(Object extractor, double ax0, double ay0, double ax1, double ay1, Color background) {
      if (!extracting()) return false;
      Frame f = STACK.peek();
      float u = unitScale(f, extractor);
      float x0 = (float) Math.min(ax0, ax1) * u, y0 = (float) Math.min(ay0, ay1) * u, x1 = (float) Math.max(ax0, ax1) * u, y1 = (float) Math.max(ay0, ay1) * u;
      if (x1 - x0 < 2F || y1 - y0 < 2F) return false;
      op(f, Op.MENU, x0, y0, x1, y1).c = background;
      f.drawn.add(new Rect(BUTTON, x0, y0, x1, y1));
      return true;
   }

   /** Essential 1.5 toggle on an extractor frame (its bounds in Elementa units). */
   public static boolean toggleExtract(Object owner, Object extractor, float l, float t, float r, float b, boolean value, boolean enabled) {
      if (!extracting()) return false;
      Frame f = STACK.peek();
      float u = unitScale(f, extractor);
      float x0 = Math.min(l, r) * u, y0 = Math.min(t, b) * u, x1 = Math.max(l, r) * u, y1 = Math.max(t, b) * u;
      if (x1 - x0 < 4F || y1 - y0 < 2F) return false;
      Op o = op(f, Op.SWITCH, x0, y0, x1, y1);
      o.owner = owner; o.value = value; o.enabled = enabled;
      return true;
   }

   public static void pushClip(int l, int t, int r, int b) {
      Frame f = STACK.peek();
      if (f == null || !f.extract) return;
      float[] c = {Math.min(l, r) * f.k, Math.min(t, b) * f.k, Math.max(l, r) * f.k, Math.max(t, b) * f.k};
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
      if (f == null || !f.extract) return;
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
      Frame top = STACK.peek();
      if (top == null || !top.extract) return;
      STACK.pop();
      if (renderState != null && !top.ops.isEmpty()) {
         PENDING.put(renderState, top);
         if (PENDING.size() > 64) PENDING.clear();
      }
   }

   /** {@code ElementaRenderer.renderToTexture(texture, …, state)}. */
   public static void linkTexture(Object texture, Object renderState) {
      if (texture == null || renderState == null) return;
      Frame f = PENDING.remove(renderState);
      if (f == null) return;
      PENDING.put(texture, f);
      ALIAS.put(renderState, texture);
      if (ALIAS.size() > 64) ALIAS.clear();
   }

   /** Just before the texture (or the render state's texture) is composited: draw the frame's glass under it. */
   public static void beforeComposite(Object key) {
      if (key == null) return;
      Frame rec = PENDING.remove(key);
      if (rec == null) {
         Object tex = ALIAS.remove(key);
         if (tex != null) rec = PENDING.remove(tex);
      }
      if (rec == null || !GlassProgram.ensureReady() || !GlassProgram.usable()) return;
      Frame g = new Frame();
      if (rec.window && rec.ux1 > rec.ux0 && rec.uy1 > rec.uy0) g.prev = new float[] {rec.ux0, rec.uy0, rec.ux1, rec.uy1};
      STACK.push(g);
      try {
         net.minecraft.client.util.Window win = MinecraftClient.getInstance().getWindow();
         double s = win.getScaleFactor();
         int fh = win.getFramebufferHeight();
         for (Op o : rec.ops) {
            clipOn = o.clip;
            clipX0 = o.cx0; clipY0 = o.cy0; clipX1 = o.cx1; clipY1 = o.cy1;
            if (o.clip) {
               if (o.cx1 <= o.cx0 || o.cy1 <= o.cy0) continue;
               // flush what is batched first, so the scissor only cuts our glass
               MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers().draw();
               RenderSystem.enableScissor((int) Math.floor(o.cx0 * s), (int) Math.floor(fh - o.cy1 * s),
                     (int) Math.ceil((o.cx1 - o.cx0) * s), (int) Math.ceil((o.cy1 - o.cy0) * s));
            }
            try {
               switch (o.kind) {
                  case Op.RECT -> onRectAbs(g, o.c, o.x0, o.y0, o.x1, o.y1);
                  case Op.MENU -> menuButtonAbs(g, new float[] {o.x0, o.y0, o.x1, o.y1}, o.c);
                  case Op.SWITCH -> toggleAbs(g, o.owner, new float[] {o.x0, o.y0, o.x1, o.y1}, o.value, o.enabled);
                  default -> { }
               }
            } finally {
               if (o.clip) RenderSystem.disableScissor();
            }
         }
      } catch (Throwable t) {
         // never take Essential down over the glass
      } finally {
         clipOn = false;
         STACK.remove(g);
      }
   }

   // ---- classification (UIBlock / UIRoundedRectangle hooks) ------------------------------------------------------

   /**
    * Decide what happens to one solid rectangle Elementa is about to paint (glass, if any, is drawn right here).
    *
    * @return {@code null} to skip painting it, the same colour to paint it unchanged, or another colour to paint it with
    */
   public static Color onRect(UMatrixStack stack, Color c, double ax0, double ay0, double ax1, double ay1, float radius) {
      if (c == null || c.getAlpha() < 24) return c;
      Frame f = STACK.peek();
      if (f == null) return c;
      float[] r = abs(stack, ax0, ay0, ax1, ay1);
      return onRectAbs(f, c, r[0], r[1], r[2], r[3]);
   }

   /** The palette rules on absolute GUI coordinates: the immediate path, and the replay of an extracted frame. */
   private static Color onRectAbs(Frame f, Color c, float x0, float y0, float x1, float y1) {
      int a = c.getAlpha();
      int rgb = c.getRGB() & 0xFFFFFF;
      float w = x1 - x0, h = y1 - y0;
      MinecraftClient mc = MinecraftClient.getInstance();
      float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
      boolean full = w >= sw * 0.9F && h >= sh * 0.9F;
      // scrolled out of its scroll area (fully outside the scissor): invisible anyway — no glass, no window growth
      if (clipOn && !full && (x1 <= clipX0 || x0 >= clipX1 || y1 <= clipY0 || y0 >= clipY1)) return null;

      if (full && rgb == 0x000000) return a > 0x40 ? new Color(0, 0, 0, 0x40) : c;
      if (full && (isPanel(rgb) || isSurface(rgb))) {
         f.window = true;
         if (f.prev != null) {
            panel(f, f.prev[0], f.prev[1], f.prev[2], f.prev[3]);
            f.drawn.add(new Rect(PANEL, f.prev[0], f.prev[1], f.prev[2], f.prev[3]));
         }
         return null;
      }
      boolean thin = w <= 3.01F || h <= 3.01F;
      if (!full && (isPanel(rgb) || isSurface(rgb) || (thin && isLine(rgb)))) grow(f, x0, y0, x1, y1);

      if (thin) {
         Rect edge = edgeRect(f, x0, y0, x1, y1);
         if (edge != null) {
            // a white bevel edge on a list button = Essential's "selected" state (e.g. the chosen world): blue selection
            if (edge.kind == BUTTON && !edge.selected && isSelectEdge(rgb)) {
               edge.selected = true;
               fill(f, edge.x0 + 1, edge.y0 + 1, edge.x1 - 1, edge.y1 - 1, 0x380A82FD, Math.max(0F, cornerPx(edge.x1 - edge.x0, edge.y1 - edge.y0) - 1F));
            }
            return null;
         }
         if (f.window && f.prev != null && onRim(f.prev, x0, y0, x1, y1)) return null;
         if (isScrollbar(rgb) && w <= 8.01F && h >= w * 6F) return new Color(255, 255, 255, 0x38);
         if (isLine(rgb) || isSurface(rgb)) {
            hairline(f, x0, y0, x1, y1);
            return null;
         }
         return c;
      }
      if (isScrollbar(rgb) && w <= 8.01F && h >= w * 2.5F) return new Color(255, 255, 255, 0x99);

      // the inset fill of a just-drawn button capsule (outline rect, then a 1 px inset fill): keep its meaning as a tint
      Rect last = f.drawn.isEmpty() ? null : f.drawn.get(f.drawn.size() - 1);
      if (h <= 40F && last != null && last.kind == BUTTON && x0 >= last.x0 - 0.5F && y0 >= last.y0 - 0.5F
            && x1 <= last.x1 + 0.5F && y1 <= last.y1 + 0.5F && (last.x1 - last.x0) - w <= 4.01F && (last.y1 - last.y0) - h <= 4.01F) {
         int tint = tintOf(rgb);
         float rr = cornerPx(last.x1 - last.x0, last.y1 - last.y0);
         if (tint != 0) fill(f, last.x0 + 1, last.y0 + 1, last.x1 - 1, last.y1 - 1, (isTintHover(rgb) ? 0x88 : 0x66) << 24 | tint, Math.max(0F, rr - 1F));
         else if (rgb == 0x474747) fill(f, last.x0 + 1, last.y0 + 1, last.x1 - 1, last.y1 - 1, 0x1AFFFFFF, Math.max(0F, rr - 1F));
         return null;
      }

      boolean inPanel = inside(f, x0, y0, x1, y1);
      // a modal's / window's own sheet (#474747 "Select world to host", #323232 the in-world hosting window): taller than any button → glass panel
      if ((rgb == 0x474747 || rgb == 0x323232) && !inPanel && w >= 60F && h > 40F) {
         grow(f, x0, y0, x1, y1);
         panel(f, x0, y0, x1, y1, 0x60101014);
         f.drawn.add(new Rect(PANEL, x0, y0, x1, y1));
         return null;
      }
      if (isPanel(rgb) || isSurface(rgb)) {
         if (!inPanel && w >= 40F && h >= 24F) {
            panel(f, x0, y0, x1, y1, 0x60101014);
            f.drawn.add(new Rect(PANEL, x0, y0, x1, y1));
            return null;
         }
         if (w >= 24F && h >= 12F) {
            float[] b = {x0, y0, x1, y1};
            float rr = inSheet(f, b, wholeInClip(x0, y0, x1, y1));
            fill(f, b[0], b[1], b[2], b[3], 0x16FFFFFF, rr >= 0F ? rr : cornerPx(w, h));
            f.drawn.add(new Rect(CARD, b[0], b[1], b[2], b[3]));
            return null;
         }
         return new Color(255, 255, 255, 0x14);
      }
      if (isSelected(rgb)) {
         capsule(f, x0, y0, x1, y1, 0.81F);
         fill(f, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x380A82FD, Math.max(0F, cornerPx(w, h) - 1F));
         f.drawn.add(new Rect(BUTTON, x0, y0, x1, y1));
         return null;
      }
      boolean whiteOutline = rgb == 0xFFFFFF && a == 255 && w >= 12F && h >= 10F;
      if (h <= 40F && w <= 420F && (whiteOutline || isGreyButton(rgb) || isOutline(rgb) || (rgb == 0x000000 && a == 255))) {
         capsule(f, x0, y0, x1, y1, whiteOutline || rgb == 0x474747 ? 0.81F : 0F);
         buttonScrim(f, x0, y0, x1, y1);
         f.drawn.add(new Rect(BUTTON, x0, y0, x1, y1));
         return null;
      }
      int tint = tintOf(rgb);
      if (tint != 0 && h <= 40F) {
         capsule(f, x0, y0, x1, y1, isTintHover(rgb) ? 0.81F : 0F);
         fill(f, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x66000000 | tint, Math.max(0F, cornerPx(w, h) - 1F));
         f.drawn.add(new Rect(BUTTON, x0, y0, x1, y1));
         return null;
      }
      return c;
   }

   /** Essential's {@code MenuButton} (bevelled box drawn by its own pipeline) → one glass capsule. */
   public static boolean menuButton(UMatrixStack stack, double ax0, double ay0, double ax1, double ay1, Color background) {
      if (!recording()) return false;
      Frame f = STACK.peek();
      return menuButtonAbs(f, abs(stack, ax0, ay0, ax1, ay1), background);
   }

   private static boolean menuButtonAbs(Frame f, float[] r, Color background) {
      if (r[2] - r[0] < 2F || r[3] - r[1] < 2F) return false;
      int rgb = background == null ? 0 : background.getRGB() & 0xFFFFFF;
      float lum = ((rgb >> 16 & 0xFF) * 0.299F + (rgb >> 8 & 0xFF) * 0.587F + (rgb & 0xFF) * 0.114F) / 255F;
      capsule(f, r[0], r[1], r[2], r[3], lum > 0.17F ? 0.81F : 0F);
      int tint = tintOf(rgb);
      if (tint != 0) fill(f, r[0] + 1, r[1] + 1, r[2] - 1, r[3] - 1, 0x66000000 | tint, Math.max(0F, cornerPx(r[2] - r[0], r[3] - r[1]) - 1F));
      else buttonScrim(f, r[0], r[1], r[2], r[3]);
      f.drawn.add(new Rect(BUTTON, r[0], r[1], r[2], r[3]));
      return true;
   }

   /** Essential's toggle → the S1mp1e iOS glass switch (the config screen's springs). */
   public static boolean toggle(Object owner, UMatrixStack stack, float l, float t, float rr, float b, boolean value, boolean enabled) {
      if (!recording()) return false;
      Frame f = STACK.peek();
      return toggleAbs(f, owner, abs(stack, Math.min(l, rr), Math.min(t, b), Math.max(l, rr), Math.max(t, b)), value, enabled);
   }

   private static boolean toggleAbs(Frame f, Object owner, float[] r, boolean value, boolean enabled) {
      if (r[2] - r[0] < 4F || r[3] - r[1] < 2F) return false;
      if (dry) return true;
      SwitchState s = SWITCHES.computeIfAbsent(owner, o -> new SwitchState());
      float dt = s.clock.tick(), want = value ? 1F : 0F;
      if (!s.placed) { s.travel.snap(want); s.placed = true; }
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
      float pos = Motion.clamp01(s.travel.x), morph = Motion.clamp01(s.lift.x), alpha = enabled ? 1F : 0.45F;

      float h = Math.min(r[3] - r[1], (r[2] - r[0]) / 2.17F), w = h * 2.17F;
      float cy = (r[1] + r[3]) / 2F, x1 = r[2], x0 = x1 - w, y0 = cy - h / 2F, y1 = cy + h / 2F, rad = h / 2F;
      int a = Math.round(alpha * 255F);
      prepare(f);
      DrawContext ctx = ctx();
      GlassWidgets.fillRound(ctx, x0, y0, x1, y1, ((int) (a * 0.55F) << 24) | OFF_TRACK, rad);
      if (pos > 0.003F) GlassWidgets.fillRound(ctx, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, rad);
      float pad = h / 6F, khh = 0.85F * h / 2F, khw = khh * 1.55F;
      float tx0 = x0 + pad + khw, tx1 = x1 - pad - khw;
      int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);
      GlassWidgets.knobLens(ctx, tx0 + (tx1 - tx0) * pos, cy, khw, khh, morph, 1.55F, 1.65F, 0.90F, x0, x1, rad, Float.NaN, band, band, alpha);
      ctx.draw();
      return true;
   }

   // ---- immediate glass ------------------------------------------------------------------------------------------------

   /** A DrawContext with identity matrices on the shared buffers: our coordinates are already absolute. */
   private static DrawContext ctx() {
      MinecraftClient mc = MinecraftClient.getInstance();
      return new DrawContext(mc, mc.getBufferBuilders().getEntityVertexConsumers());
   }

   /** Land pending vanilla batches first (layering), and grab the backdrop once per frame before the first glass. */
   private static void prepare(Frame f) {
      if (dry) return;
      MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers().draw();
      // once per FRAME, before the first Essential glass: the backdrop is the screen behind every Essential window, so a
      // modal stacked on another does not refract (show) the one below it
      if (!grabbedThisFrame) {
         // reuse the screen's own grab when it already took one this frame (right after its background, before its
         // widgets); only grab ourselves when nothing did
         // (vanilla screens grab at their render HEAD, with a 3 ms throttle — that grab is clean: background only). A
         // backdrop up to 50 ms old is reused; only a stale one (an Essential full-screen window nobody grabs for) is
         // re-grabbed here — at its first glass, the full-screen background, so still before any of its content.
         if (!SceneCapture.hasBackdrop() || System.nanoTime() - SceneCapture.lastGrabNanos() > 50_000_000L) SceneCapture.grabNow();
         grabbedThisFrame = true;
      }
   }

   private static void panel(Frame f, float x0, float y0, float x1, float y1) {
      panel(f, x0, y0, x1, y1, 0x38101014);
   }

   /**
    * @param scrim the readability scrim over the glass: light for the full-window plate, heavier for a stand-alone panel
    *              (modal) — on 1.21.1 a modal's backdrop can still carry the widgets under it, which must not show through
    */
   private static void panel(Frame f, float x0, float y0, float x1, float y1, int scrim) {
      if (dry) return;
      prepare(f);
      RenderSystem.disableDepthTest();
      float r = sheetRadius(x1 - x0, y1 - y0), minSide = Math.max(1F, Math.min(x1 - x0, y1 - y0));
      GlassRenderer.panel(x0, y0, x1, y1, 1F, Math.min(0.19F, 4F * r / minSide));
      // a light grey scrim so Essential's grey/white text reads on bright backdrops (LOOK SPEC: scrim under content)
      if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, r, scrim);
      RenderSystem.enableDepthTest();
   }

   private static void capsule(Frame f, float x0, float y0, float x1, float y1, float lift) {
      if (dry) return;
      prepare(f);
      float half = Math.max(1F, Math.min(x1 - x0, y1 - y0) / 2F);
      float cr = concentric(sheetOf(f, x0, y0, x1, y1), x0, y0, x1, y1);
      float corner = Math.min(1F, (cr >= 0F ? Math.min(cr, half) : GlassCorners.HOTBAR_RADIUS) / half);
      RenderSystem.disableDepthTest();
      GlassRenderer.button(x0, y0, x1, y1, corner, lift, 1F, true);
      RenderSystem.enableDepthTest();
   }

   /**
    * The same light grey scrim as the panels (LOOK SPEC: scrim under content) inside a plain (untinted) button: Essential's
    * light-grey labels sit on raw world/panorama on the title side menu, and with text shadows off a clear capsule over a
    * bright block left them unreadable. Tinted / selected buttons keep their colour fill instead.
    */
   private static void buttonScrim(Frame f, float x0, float y0, float x1, float y1) {
      fill(f, x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x38101014, Math.max(0F, cornerPx(x1 - x0, y1 - y0) - 1F));
   }

   private static void fill(Frame f, float x0, float y0, float x1, float y1, int argb, float r) {
      if (dry) return;
      prepare(f);
      RenderSystem.disableDepthTest();
      if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, r, argb);
      RenderSystem.enableDepthTest();
   }

   private static void hairline(Frame f, float x0, float y0, float x1, float y1) {
      if (dry) return;
      float[] sh = sheetOf(f, x0, y0, x1, y1);
      if (sh != null) {
         if ((x1 - x0) >= (y1 - y0)) { x0 = Math.max(x0, sh[0] + EDGE_GAP); x1 = Math.min(x1, sh[2] - EDGE_GAP); }
         else { y0 = Math.max(y0, sh[1] + EDGE_GAP); y1 = Math.min(y1, sh[3] - EDGE_GAP); }
         if (x1 <= x0 || y1 <= y0) return;
      }
      if ((x1 - x0) >= (y1 - y0)) {
         float cy = (y0 + y1) / 2F;
         fill(f, x0, cy - 0.25F, x1, cy + 0.25F, 0x2EFFFFFF, 0F);
      } else {
         float cx = (x0 + x1) / 2F;
         fill(f, cx - 0.25F, y0, cx + 0.25F, y1, 0x2EFFFFFF, 0F);
      }
   }

   /** Gap between a sheet edge and a bar hugging it (GUI px); bars nearer than SNAP are moved to it. */
   private static final float EDGE_GAP = 4F, EDGE_SNAP = 8F;

   /** A sheet's corner (window / stand-alone panel): capped like the S1mp1e config panel (no over-rounded full screen). */
   private static float sheetRadius(float w, float h) {
      return Math.min(14F, Math.min(w, h) * 0.0475F);
   }

   /** The smallest sheet (this frame's window plate or a stand-alone panel) containing the piece, {x0,y0,x1,y1,r}. */
   private static float[] sheetOf(Frame f, float x0, float y0, float x1, float y1) {
      float[] best = null;
      ArrayList<float[]> cands = new ArrayList<>();
      if (f.window && f.prev != null) cands.add(f.prev);
      for (Rect e : f.drawn) if (e.kind == PANEL) cands.add(new float[] {e.x0, e.y0, e.x1, e.y1});
      for (float[] c : cands) {
         if (x0 >= c[0] - 0.5F && y0 >= c[1] - 0.5F && x1 <= c[2] + 0.5F && y1 <= c[3] + 0.5F
               && (x1 - x0 < c[2] - c[0] - 0.01F || y1 - y0 < c[3] - c[1] - 0.01F)
               && (best == null || (c[2] - c[0]) * (c[3] - c[1]) < (best[2] - best[0]) * (best[3] - best[1]))) best = c;
      }
      return best == null ? null : new float[] {best[0], best[1], best[2], best[3], sheetRadius(best[2] - best[0], best[3] - best[1])};
   }

   /** Concentric corner (outer radius − inset) of a piece in a sheet corner; −1 when it is not in one. */
   private static float concentric(float[] sh, float x0, float y0, float x1, float y1) {
      if (sh == null) return -1F;
      float r = sh[4];
      float dx = Math.max(0F, Math.min(x0 - sh[0], sh[2] - x1)), dy = Math.max(0F, Math.min(y0 - sh[1], sh[3] - y1));
      if (dx >= r || dy >= r) return -1F;
      return Math.max(2F, r - Math.max(dx, dy));
   }

   private static boolean wholeInClip(float x0, float y0, float x1, float y1) {
      return !clipOn || (x0 >= clipX0 - 0.5F && y0 >= clipY0 - 0.5F && x1 <= clipX1 + 0.5F && y1 <= clipY1 + 0.5F);
   }

   /**
    * A card in a sheet: a side nearer than SNAP to the sheet edge is moved to the uniform GAP (not for a row cut by its
    * scroll area — that one is only partly on screen). Edits {@code b}; returns the concentric corner or −1.
    */
   private static float inSheet(Frame f, float[] b, boolean whole) {
      float[] sh = sheetOf(f, b[0], b[1], b[2], b[3]);
      if (sh == null) return -1F;
      if (whole) {
         if (b[0] - sh[0] < EDGE_SNAP) b[0] = sh[0] + EDGE_GAP;
         if (b[1] - sh[1] < EDGE_SNAP) b[1] = sh[1] + EDGE_GAP;
         if (sh[2] - b[2] < EDGE_SNAP) b[2] = sh[2] - EDGE_GAP;
         if (sh[3] - b[3] < EDGE_SNAP) b[3] = sh[3] - EDGE_GAP;
      }
      return concentric(sh, b[0], b[1], b[2], b[3]);
   }

   /**
    * Essential 1.5 (Elementa 774) extractors draw immediately on 1.21.1 through a UMatrixStack: Elementa's
    * {@code ImmediateElementaExtractor.getMatrixStack()}, or Essential's {@code McElementaExtractor} wrapping one.
    */
   public static UMatrixStack stackOf(Object extractor) {
      if (extractor == null) return null;
      try {
         for (Class<?> c = extractor.getClass(); c != null; c = c.getSuperclass()) {
            try {
               java.lang.reflect.Method m = c.getDeclaredMethod("getMatrixStack");
               m.setAccessible(true);
               Object r = m.invoke(extractor);
               if (r instanceof UMatrixStack ms) return ms;
            } catch (NoSuchMethodException ignored) { }
            try {
               java.lang.reflect.Field fl = c.getDeclaredField("immediate");
               fl.setAccessible(true);
               return stackOf(fl.get(extractor));
            } catch (NoSuchFieldException ignored) { }
         }
      } catch (Throwable ignored) { }
      return null;
   }

   private static float cornerPx(float w, float h) {
      return Math.min(GlassCorners.HOTBAR_RADIUS, Math.min(w, h) / 2F);
   }

   // ---- geometry -------------------------------------------------------------------------------------------------------

   private static float[] abs(UMatrixStack stack, double ax0, double ay0, double ax1, double ay1) {
      Matrix4f m = stack.peek().getModel();
      Vector4f p0 = m.transform(new Vector4f((float) Math.min(ax0, ax1), (float) Math.min(ay0, ay1), 0F, 1F));
      Vector4f p1 = m.transform(new Vector4f((float) Math.max(ax0, ax1), (float) Math.max(ay0, ay1), 0F, 1F));
      return new float[] {Math.min(p0.x, p1.x), Math.min(p0.y, p1.y), Math.max(p0.x, p1.x), Math.max(p0.y, p1.y)};
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

   private static boolean inside(Frame f, float x0, float y0, float x1, float y1) {
      for (Rect e : f.drawn) {
         if (e.kind == PANEL && x0 >= e.x0 - 0.5F && y0 >= e.y0 - 0.5F && x1 <= e.x1 + 0.5F && y1 <= e.y1 + 0.5F) return true;
      }
      return false;
   }

   /** A thin line along an edge of a glass piece already drawn this frame (its outline). */
   private static boolean onEdge(Frame f, float x0, float y0, float x1, float y1) {
      return edgeRect(f, x0, y0, x1, y1) != null;
   }

   /** The most recent drawn piece whose edge this thin line runs along, or null. */
   private static Rect edgeRect(Frame f, float x0, float y0, float x1, float y1) {
      final float T = 1.6F;
      boolean horiz = (x1 - x0) >= (y1 - y0);
      for (int i = f.drawn.size() - 1; i >= 0; i--) {
         Rect e = f.drawn.get(i);
         if (horiz) {
            if (x0 >= e.x0 - T && x1 <= e.x1 + T && (Math.abs(y0 - e.y0) <= T || Math.abs(y1 - e.y1) <= T
                  || Math.abs(y1 - e.y0) <= T || Math.abs(y0 - e.y1) <= T)) return e;
         } else {
            if (y0 >= e.y0 - T && y1 <= e.y1 + T && (Math.abs(x0 - e.x0) <= T || Math.abs(x1 - e.x1) <= T
                  || Math.abs(x1 - e.x0) <= T || Math.abs(x0 - e.x1) <= T)) return e;
         }
      }
      return null;
   }

   /** A thin line lying on the rim of the window's glass plate. */
   private static boolean onRim(float[] u, float x0, float y0, float x1, float y1) {
      final float N = 2.5F;
      boolean horiz = (x1 - x0) >= (y1 - y0);
      return horiz ? (Math.abs(y0 - u[1]) <= N || Math.abs(y1 - u[3]) <= N) : (Math.abs(x0 - u[0]) <= N || Math.abs(x1 - u[2]) <= N);
   }

   // ---- dev trace ------------------------------------------------------------------------------------------------------

   /** Dev only ({@code S1MP1E_ESS_DUMP=<file>}): logs draws outside any Elementa frame. */
   public static void idle(UMatrixStack stack, Color c, double ax0, double ay0, double ax1, double ay1, float radius) { }

   // ---- Essential palette (gg.essential.gui.EssentialPalette, 1.4.x) ---------------------------------------------

   private static boolean isPanel(int rgb) {
      return rgb == 0x181818 || rgb == 0x1E1E1E || rgb == 0x121212 || rgb == 0x111111 || rgb == 0x0F0F0F;
   }

   private static boolean isSurface(int rgb) {
      return rgb == 0x232323 || rgb == 0x1C1C1C || rgb == 0x303030 || rgb == 0x333333 || rgb == 0x444444
            || rgb == 0x2A2A2A || rgb == 0x262626 || rgb == 0x1F1F1F;
   }

   private static boolean isGreyButton(int rgb) {
      return rgb == 0x323232 || rgb == 0x474747;
   }

   private static boolean isOutline(int rgb) {
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
      return rgb == 0x121E30 || rgb == 0x1E2A3C || rgb == 0x111E30 || rgb == 0x030C18;
   }

   private static int tintOf(int rgb) {
      switch (rgb) {
         case 0x274673, 0x2F5FA4, 0x223F69, 0x2A5695, 0x3671C7, 0x5490E8: return 0x2F7BFF;
         case 0x642626, 0x9F4444, 0x461F1F, 0x8B3636: return 0xFF453A;
         case 0x1D4728, 0x327B44, 0x276136, 0x3E9252: return 0x30D158;
         case 0x734317, 0xA36226, 0x583E14, 0x74521D, 0x8F621F, 0xBA8537: return 0xFF9F0A;
         case 0x473999, 0x5947BF, 0x292063, 0x352A7A, 0x6F5CE5: return 0xBF5AF2;
         default: return 0;
      }
   }

   private static boolean isTintHover(int rgb) {
      return rgb == 0x2F5FA4 || rgb == 0x2A5695 || rgb == 0x9F4444 || rgb == 0x327B44
            || rgb == 0x276136 || rgb == 0xA36226 || rgb == 0x74521D || rgb == 0x5947BF || rgb == 0x352A7A;
   }
}
