package dev.s1mp1e.glass.render;

import dev.s1mp1e.client.module.HudGlass;
import java.util.ArrayList;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;

/**
 * Per-{@code TextFieldWidget} typing animation (one instance per box, owned by {@code EditBoxTypingMixin}) — the 1.16.5
 * immediate-mode ({@link MatrixStack}) port of the 26.2 deferred {@code TypingAnim}. Vanilla draws the visible text as two
 * substrings split at the caret, so every edit is a hard cut. This renders the text one glyph at a time instead, with
 * sub-pixel positions (1.16.5's {@code TextRenderer.draw} takes float coordinates):
 * <ul>
 *   <li><b>Entrance</b> — a newly typed glyph blurs&rarr;sharp, rises from below and fades in, all three on ONE critically
 *       damped spring (no overshoot, ~0.2 s). Several glyphs inserted at once (paste, history, tab-complete) cascade.</li>
 *   <li><b>Exit</b> — a deleted glyph floats up, blurs and fades (a whole-string replacement reads as an odometer roll).</li>
 *   <li><b>Glide</b> — glyphs after a mid-string insert/delete slide to their new place instead of jumping.</li>
 *   <li><b>Smooth scroll</b> — when the text is wider than the box, the horizontal scroll eases instead of stepping a whole
 *       glyph; glyphs sliding past the box edge are clipped by a scissor.</li>
 *   <li><b>Caret and selection</b> — the caret glides in the same coordinates as the text (so it stays locked to it while
 *       scrolling) and blinks smoothly; the selection highlight's edges glide.</li>
 * </ul>
 * Edits are detected by diffing the value each frame (common prefix/suffix, biased toward the caret). Render thread only.
 *
 * <p><b>1.16.5 adaptation.</b> The glass caret is drawn through {@link HudGlass#roundFillCtx} (a rounded rect baked
 * through the matrix and drawn immediately by {@code GlassRenderer}); text goes through
 * {@code TextRenderer.draw(MatrixStack, OrderedText, float, float, int)} — an immediate draw in this version, so glyphs
 * land in call order and a scissor needs no flush of its own; widths come from
 * {@code TextRenderer.getTextHandler().getWidth(OrderedText)} (float, sub-pixel like 26.2's splitter).
 */
public final class TypingAnim {

   // ---- tuning (identical to 26.2) ---------------------------------------------------------------------------------
   /** Entrance spring (critically damped): omega = 22 settles in ~0.22 s. */
   private static final float ENTER_W = 22.0F;
   private static final float RISE = 3.0F;          // px below the baseline a glyph starts from
   private static final float BLUR = 1.4F;          // px blur radius at the start (~ stroke width, so taps merge)
   private static final float OUT_S = 0.15F;        // exit length
   private static final float FLOAT_UP = 3.0F;      // px a leaving glyph floats up
   private static final float BLUR_OUT = 1.2F;
   private static final float GLIDE_TAU = 0.05F;    // neighbours sliding after a mid-string edit
   private static final float SCROLL_TAU = 0.07F;   // horizontal scroll
   private static final float CARET_TAU = 0.045F;
   private static final float HL_TAU = 0.05F;
   /** Caret: Apple system blue (same blue as the config slider fill and the boss bar), a 2 px capsule. */
   private static final int CARET_RGB = 0x0A84FF;
   private static final float CARET_W = 2.0F;
   /** Selection highlight: translucent system blue with gliding edges. */
   private static final int HL_ARGB = 0x66_0A84FF;
   private static final long MAX_CASCADE_NS = 120_000_000L;
   private static final long CASCADE_STEP_NS = 12_000_000L;

   /** Set when rendering threw once: the mixin falls back to vanilla drawing for this box. */
   public boolean broken;

   /** Dev only (DevShot): &lt;1 slows entrance/exit down to inspect them frame by frame. */
   public static float timeScale = 1.0F;
   /** Dev only (DevShot): log the newest glyph's entrance progress each frame. */
   public static boolean debugLog;

   private static final class Glyph {
      long bornNs;            // entrance start (may be in the future for a cascade); Long.MIN_VALUE = settled
      float x = Float.NaN;    // animated position in full-string coordinates
      float y = Float.NaN;    // multi-line mode only: animated screen y
      int cp;                 // code point, or -1 for the low half of a surrogate pair
      Style style = Style.EMPTY;
      float w;
   }

   private static final class Dying {
      int cp; Style style; float x; float y; long deathNs; int color;
   }

   private String last;
   private final ArrayList<Glyph> glyphs = new ArrayList<>();
   private final ArrayList<Dying> dying = new ArrayList<>();
   private float[] full = new float[1];         // full[i] = target x of glyph i; full[len] = total width
   private float scrollCur = Float.NaN, scrollTarget;
   private long lastNs;
   private float frameDt;
   private int textX, textY, clipX0, clipX1, clipY0, clipY1, cursorPos, lineHeight;

   private float caretCur = Float.NaN, caretTarget = Float.NaN;
   private long caretMoveNs;
   private float hl0 = Float.NaN, hl1 = Float.NaN;
   private boolean hlPrev, hlNow;

   // ---- text pass ---------------------------------------------------------------------------------------------------

   /**
    * Draw the whole text for this frame. Called once per frame from inside {@code renderWidget}, after the visible
    * substring is trimmed and before the caret / highlight.
    */
   public void extractText(MatrixStack g, TextRenderer font, String value, OrderedText formatted,
                           int displayPos, int cursorPos, int textX, int textY, int innerWidth,
                           int color, boolean shadow) {
      long now = System.nanoTime();
      float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F) * timeScale;
      lastNs = now;
      frameDt = dt;
      this.textX = textX;
      this.textY = textY;
      this.cursorPos = cursorPos;
      this.lineHeight = font.fontHeight;
      this.clipX0 = textX;
      this.clipX1 = textX + innerWidth;
      this.clipY0 = textY - 8;
      this.clipY1 = textY + font.fontHeight + 8;
      this.hlPrev = this.hlNow;
      this.hlNow = false;

      if (last == null) {
         // First frame of this box: its initial content (e.g. the "/" chat was opened with) cascades in.
         insertGlyphs(0, value.length(), now);
         last = value;
      } else if (!value.equals(last)) {
         applyEdit(last, value, cursorPos, now, color);
         last = value;
      }

      styleAndMeasure(font, value, formatted);

      // targets (full-string coordinates)
      int len = value.length();
      if (full.length < len + 1) full = new float[len + 1];
      float acc = 0F;
      for (int i = 0; i < len; i++) {
         full[i] = acc;
         acc += glyphs.get(i).w;
      }
      full[len] = acc;

      int dp = Math.max(0, Math.min(displayPos, len));
      scrollTarget = full[dp];
      if (Float.isNaN(scrollCur)) {
         scrollCur = scrollTarget;
      } else {
         // A jump wider than the box (paste, long history recall, Home/End) would sweep the whole line past: land it
         // from 30 % of a box width away instead, so it still eases in but never "flies".
         float gap = scrollTarget - scrollCur;
         if (Math.abs(gap) > innerWidth) scrollCur = scrollTarget - Math.signum(gap) * innerWidth * 0.3F;
         scrollCur = approach(scrollCur, scrollTarget, dt, SCROLL_TAU);
      }
      boolean scrolling = Math.abs(scrollTarget - scrollCur) > 0.05F;
      float visibleLimit = innerWidth;

      for (int i = 0; i < len; i++) {
         Glyph gl = glyphs.get(i);
         gl.x = Float.isNaN(gl.x) ? full[i] : approach(gl.x, full[i], dt, GLIDE_TAU);
      }

      scissor(g, clipX0, clipY0, clipX1, clipY1);
      try {
         // leaving glyphs first (under the arriving ones in a replacement)
         for (int k = dying.size() - 1; k >= 0; k--) {
            Dying d = dying.get(k);
            float q = (now - d.deathNs) / 1.0e9F * timeScale / OUT_S;
            if (q >= 1F) { dying.remove(k); continue; }
            float sx = textX + d.x - scrollCur;
            if (sx > clipX1 + 4 || sx < clipX0 - 16) continue;
            float e = q * q;                                   // ease-in: leaves gently, then goes
            float a = (1F - q) * (1F - q);                     // fades fast early: a replacement doesn't double-expose
            drawGlyph(g, font, d.cp, d.style, sx, textY - FLOAT_UP * e, withAlpha(d.color, a), shadow, BLUR_OUT * q);
         }
         for (int i = 0; i < len; i++) {
            Glyph gl = glyphs.get(i);
            if (gl.cp < 0) continue;
            float sx = textX + gl.x - scrollCur;
            float rel = sx - textX;
            if (scrolling || Math.abs(gl.x - full[i]) > 0.05F) {
               if (rel + gl.w < -0.5F || rel > visibleLimit + 0.5F) continue;      // moving: clip decides
            } else if (i < dp || full[i + 1] - scrollTarget > visibleLimit) {
               continue;                                                            // at rest: exactly vanilla's glyphs
            }
            float p = enter(now, gl.bornNs);
            if (p <= 0F) continue;
            float inv = 1F - p;
            float a = 1F - inv * inv;
            drawGlyph(g, font, gl.cp, gl.style, sx, textY + RISE * inv, withAlpha(color, a), shadow, BLUR * inv);
         }
      } finally {
         unscissor(g);
      }
      if (debugLog && len > 0) {
         Glyph newest = null;
         for (Glyph gl : glyphs) if (gl.bornNs != Long.MIN_VALUE && (newest == null || gl.bornNs > newest.bornNs)) newest = gl;
         if (newest != null) {
            float p = enter(now, newest.bornNs);
            if (p < 1F) System.out.printf("[TypingAnim] ns=%d p=%.3f alpha=%.3f rise=%.2f blur=%.2f dying=%d%n",
                  now, p, 1F - (1F - p) * (1F - p), RISE * (1F - p), BLUR * (1F - p), dying.size());
         }
      }
   }

   // ---- centred label mode (sign lines) ----------------------------------------------------------------------------

   private float originCur = Float.NaN;
   private static final float ORIGIN_TAU = 0.06F;

   /**
    * A centred, non-editable label (a sign line, centred at {@code centerX}). Same per-glyph machinery as the text box,
    * but the content on the label's first frame is simply there (no entrance on screen open); a value change keeps the
    * common prefix in place while the old value leaves upward and the new one rises in; and when the width changes, the
    * whole label re-centres by gliding. Positions match vanilla's centred draw exactly ({@code left = centerX - ceil(w)/2}).
    */
   public void extractLabel(MatrixStack g, TextRenderer font, String value, OrderedText formatted,
                            int centerX, int textY, int clipX0, int clipX1, int color, boolean shadow) {
      long now = System.nanoTime();
      float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F) * timeScale;
      lastNs = now;
      frameDt = dt;
      this.textY = textY;
      this.lineHeight = font.fontHeight;
      this.clipX0 = clipX0;
      this.clipX1 = clipX1;
      this.clipY0 = textY - 8;
      this.clipY1 = textY + font.fontHeight + 8;

      if (last == null) {
         for (int i = 0; i < value.length(); i++) { Glyph gl = new Glyph(); gl.bornNs = Long.MIN_VALUE; glyphs.add(gl); }
         last = value;
      } else if (!value.equals(last)) {
         applyEdit(last, value, value.length(), now, color);
         last = value;
      }
      styleAndMeasure(font, value, formatted);

      int len = value.length();
      if (full.length < len + 1) full = new float[len + 1];
      float acc = 0F;
      for (int i = 0; i < len; i++) { full[i] = acc; acc += glyphs.get(i).w; }
      full[len] = acc;

      float targetLeft = centerX - ((int) Math.ceil(acc)) / 2;
      originCur = Float.isNaN(originCur) ? targetLeft : approach(originCur, targetLeft, dt, ORIGIN_TAU);
      for (int i = 0; i < len; i++) {
         Glyph gl = glyphs.get(i);
         gl.x = Float.isNaN(gl.x) ? full[i] : approach(gl.x, full[i], dt, GLIDE_TAU);
      }

      scissor(g, clipX0, clipY0, clipX1, clipY1);
      try {
         for (int k = dying.size() - 1; k >= 0; k--) {
            Dying d = dying.get(k);
            float q = (now - d.deathNs) / 1.0e9F * timeScale / OUT_S;
            if (q >= 1F) { dying.remove(k); continue; }
            float e = q * q;
            drawGlyph(g, font, d.cp, d.style, originCur + d.x, textY - FLOAT_UP * e, withAlpha(d.color, (1F - q) * (1F - q)), shadow, BLUR_OUT * q);
         }
         for (int i = 0; i < len; i++) {
            Glyph gl = glyphs.get(i);
            if (gl.cp < 0) continue;
            float p = enter(now, gl.bornNs);
            if (p <= 0F) continue;
            float inv = 1F - p;
            drawGlyph(g, font, gl.cp, gl.style, originCur + gl.x, textY + RISE * inv, withAlpha(color, 1F - inv * inv), shadow, BLUR * inv);
         }
      } finally {
         unscissor(g);
      }
   }

   // ---- multi-line mode (book & quill: BookEditScreen) -------------------------------------------------------------

   private float caretY = Float.NaN;
   private float[] mtx = new float[0], mty = new float[0];
   private boolean[] mvis = new boolean[0];

   /**
    * A word-wrapped multi-line text area. Glyphs carry a 2-D animated position: typing / deleting behaves as in the text
    * box, and when word-wrap moves a word to the next line (or back) its glyphs glide there diagonally instead of jumping.
    * The book's existing text is simply there on the first frame. The caret is the blue capsule, gliding in 2-D.
    * Ported from LiquidGlass26's {@code TypingAnim.extractMultiline}; only the draw types changed.
    *
    * @param lines each visual line as {begin, end} into {@code value}
    */
   public void extractMultiline(MatrixStack g, TextRenderer font, String value, java.util.List<int[]> lines,
                                int left, int top, int lineH, int color, boolean shadow, int cursor, int cursorLine, boolean caret) {
      long now = System.nanoTime();
      float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F) * timeScale;
      lastNs = now;
      frameDt = dt;
      this.lineHeight = font.fontHeight;
      if (last == null) {
         for (int i = 0; i < value.length(); i++) { Glyph gl = new Glyph(); gl.bornNs = Long.MIN_VALUE; glyphs.add(gl); }
         last = value;
      } else if (!value.equals(last)) {
         applyEdit(last, value, cursor, now, color);
         last = value;
      }
      styleAndMeasure(font, value, OrderedText.styledForwardsVisitedString(value, Style.EMPTY));

      int len = value.length();
      if (mtx.length < len + 1) { mtx = new float[len + 1]; mty = new float[len + 1]; mvis = new boolean[len + 1]; }
      java.util.Arrays.fill(mvis, 0, len + 1, false);
      float endX = left, endY = top;
      for (int li = 0; li < lines.size(); li++) {
         int b = Math.max(0, Math.min(lines.get(li)[0], len)), e = Math.max(b, Math.min(lines.get(li)[1], len));
         float acc = 0F, y = top + li * lineH;
         for (int i = b; i < e; i++) { mtx[i] = left + acc; mty[i] = y; mvis[i] = true; acc += glyphs.get(i).w; }
         mtx[e] = left + acc; mty[e] = y;   // the slot right after the line (caret at line end)
         endX = left + acc; endY = y;
      }
      // characters no line covers (the newline / the space a wrap consumed): park them where the next glyph starts
      float nx = endX, ny = endY;
      for (int i = len - 1; i >= 0; i--) {
         if (mvis[i]) { nx = mtx[i]; ny = mty[i]; } else { mtx[i] = nx; mty[i] = ny; }
      }
      for (int i = 0; i < len; i++) {
         Glyph gl = glyphs.get(i);
         if (Float.isNaN(gl.x) || Float.isNaN(gl.y)) { gl.x = mtx[i]; gl.y = mty[i]; }
         else { gl.x = approach(gl.x, mtx[i], dt, 0.06F); gl.y = approach(gl.y, mty[i], dt, 0.06F); }
      }

      for (int k = dying.size() - 1; k >= 0; k--) {
         Dying d = dying.get(k);
         float q = (now - d.deathNs) / 1.0e9F * timeScale / OUT_S;
         if (q >= 1F || Float.isNaN(d.y)) { dying.remove(k); continue; }
         drawGlyph(g, font, d.cp, d.style, d.x, d.y - FLOAT_UP * q * q, withAlpha(d.color, (1F - q) * (1F - q)), shadow, BLUR_OUT * q);
      }
      for (int i = 0; i < len; i++) {
         Glyph gl = glyphs.get(i);
         if (gl.cp < 0 || !mvis[i]) continue;
         float p = enter(now, gl.bornNs);
         if (p <= 0F) continue;
         float inv = 1F - p;
         drawGlyph(g, font, gl.cp, gl.style, gl.x, gl.y + RISE * inv, withAlpha(color, 1F - inv * inv), shadow, BLUR * inv);
      }

      if (!caret) return;
      int c = Math.max(0, Math.min(cursor, len));
      int cl = Math.max(0, Math.min(cursorLine, lines.size() - 1));
      float tx = left - 1.5F, ty = top;
      if (!lines.isEmpty()) {
         int b = Math.max(0, Math.min(lines.get(cl)[0], len));
         float acc = 0F;
         for (int i = b; i < c && i < len; i++) acc += glyphs.get(i).w;
         tx = left + acc - 1.5F;
         ty = top + cl * lineH;
      }
      if (Float.isNaN(caretCur) || Float.isNaN(caretY)) { caretCur = tx; caretY = ty; caretTarget = tx; caretMoveNs = now; }
      float prevY = caretY;
      caretY = approach(caretY, ty, dt, CARET_TAU);
      if (Math.abs(ty - prevY) > 0.5F) caretMoveNs = now;
      this.textY = Math.round(caretY);
      drawCaretAt(g, tx, 0F);
   }

   /** Horizontal offset between where vanilla lays things out (settled scroll) and where the text is drawn now. */
   public float scrollDelta() {
      return Float.isNaN(scrollCur) ? 0F : scrollTarget - scrollCur;
   }

   // ---- caret --------------------------------------------------------------------------------------------------------

   /** The Apple caret: a system-blue capsule centred on the glyph gap, gliding in text coordinates, smooth blink. */
   public void drawCaret(MatrixStack g) {
      if (last == null) return;
      int c = Math.max(0, Math.min(cursorPos, last.length()));
      drawCaretAt(g, full[c] - 1.5F, textX - scrollCur);
   }

   /** Same caret for the centred-label mode (sign lines): positioned on the label's gliding origin. */
   public void drawLabelCaret(MatrixStack g, int cursor, int textY) {
      if (last == null || Float.isNaN(originCur)) return;
      this.textY = textY;
      int c = Math.max(0, Math.min(cursor, last.length()));
      drawCaretAt(g, full[c] - 1.5F, originCur);
   }

   /** @param target caret left in full-string coordinates; @param origin screen x of full-string 0 this frame. */
   private void drawCaretAt(MatrixStack g, float target, float origin) {
      long now = System.nanoTime();
      if (Float.isNaN(caretCur)) { caretCur = target; caretTarget = target; caretMoveNs = now; }
      if (Math.abs(target - caretTarget) > 0.01F) { caretTarget = target; caretMoveNs = now; }
      caretCur = approach(caretCur, caretTarget, frameDt, CARET_TAU);
      float elapsed = (now - caretMoveNs) / 1.0e9F;
      float a;
      if (elapsed < 0.5F) {
         a = 1F;
      } else {
         float ph = ((elapsed - 0.5F) % 1.06F) / 1.06F;
         a = 0.5F + 0.5F * (float) Math.cos(2 * Math.PI * ph);
         a = a * a * (3F - 2F * a);
      }
      int alpha = Math.round(a * 255F) & 0xFF;
      if (alpha < 4) return;
      float sx = origin + caretCur;
      int argb = (alpha << 24) | CARET_RGB;
      // Apple caret: system-blue capsule (radius = half the width -> both ends fully round), sub-pixel through the ctx
      // matrix; roundFillCtx falls back to a plain fill when the round program is unavailable.
      HudGlass.roundFillCtx(g, sx, textY - 1, sx + CARET_W, textY + lineHeight, CARET_W * 0.5F, argb);
   }

   // ---- selection ----------------------------------------------------------------------------------------------------

   /** Selection highlight with gliding edges (grows from the anchor when a selection starts). */
   public void drawHighlight(MatrixStack g, int highlightPos, int y0, int y1) {
      if (last == null) return;
      int len = last.length();
      int c = Math.max(0, Math.min(cursorPos, len));
      int h = Math.max(0, Math.min(highlightPos, len));
      float t0 = full[Math.min(c, h)] - 1F, t1 = full[Math.max(c, h)] - 1F;
      if (!hlPrev || Float.isNaN(hl0)) {                 // new selection: both edges start at the anchor
         hl0 = hl1 = full[h] - 1F;
      }
      hlNow = true;
      hl0 = approach(hl0, t0, frameDt, HL_TAU);
      hl1 = approach(hl1, t1, frameDt, HL_TAU);
      int x0 = Math.round(textX + hl0 - scrollCur), x1 = Math.round(textX + hl1 - scrollCur);
      if (x1 <= x0) return;
      scissor(g, clipX0, clipY0, clipX1, clipY1);
      try {
         DrawableHelper.fill(g, x0, y0, x1, y1, HL_ARGB);
      } finally {
         unscissor(g);
      }
   }

   // ---- edit tracking ------------------------------------------------------------------------------------------------

   private void applyEdit(String o, String n, int cursor, long now, int color) {
      int lo = o.length(), ln = n.length();
      int p = 0, maxP = Math.min(lo, ln);
      while (p < maxP && o.charAt(p) == n.charAt(p)) p++;
      // Repeated characters make the split ambiguous ("aa" -> "aaa"): resolve it at the caret, where the edit happened.
      if (ln > lo) p = Math.min(p, Math.max(0, cursor - (ln - lo)));
      else p = Math.min(p, Math.max(0, cursor));
      if (p > 0 && p < ln && Character.isLowSurrogate(n.charAt(p))) p--;
      int s = 0, maxS = Math.min(lo, ln) - p;
      while (s < maxS && o.charAt(lo - 1 - s) == n.charAt(ln - 1 - s)) s++;
      if (s > 0 && lo - s < lo && Character.isLowSurrogate(o.charAt(lo - s))) s--;

      // removed glyphs [p, lo - s) leave
      for (int i = p; i < lo - s && i < glyphs.size(); i++) {
         Glyph gl = glyphs.get(i);
         if (gl.cp < 0 || Float.isNaN(gl.x)) continue;
         if (enter(now, gl.bornNs) <= 0F) continue;       // never became visible: just drop it
         Dying d = new Dying();
         d.cp = gl.cp; d.style = gl.style; d.x = gl.x; d.y = gl.y; d.deathNs = now; d.color = color;
         dying.add(d);
      }
      int removeCount = Math.max(0, Math.min(glyphs.size(), lo - s) - p);
      for (int k = 0; k < removeCount; k++) glyphs.remove(p);
      insertGlyphs(p, ln - s - p, now);
   }

   private void insertGlyphs(int at, int count, long now) {
      if (count <= 0) return;
      long step = count <= 1 ? 0L : Math.min(CASCADE_STEP_NS, MAX_CASCADE_NS / count);
      for (int k = 0; k < count; k++) {
         Glyph gl = new Glyph();
         gl.bornNs = now + k * step;
         glyphs.add(Math.min(at + k, glyphs.size()), gl);
      }
   }

   /** Assign each glyph its code point, style and advance from the box's own formatter output. */
   private void styleAndMeasure(TextRenderer font, String value, OrderedText formatted) {
      int len = value.length();
      while (glyphs.size() < len) { Glyph gl = new Glyph(); gl.bornNs = Long.MIN_VALUE; glyphs.add(gl); }
      while (glyphs.size() > len) glyphs.remove(glyphs.size() - 1);
      final int[] pos = {0};
      final boolean[] ok = {true};
      formatted.accept((index, style, cp) -> {
         int i = pos[0];
         if (i >= len) { ok[0] = false; return false; }
         Glyph gl = glyphs.get(i);
         gl.cp = cp;
         gl.style = style;
         gl.w = font.getTextHandler().getWidth(OrderedText.styled(cp, style));
         int n = Character.charCount(cp);
         if (n == 2 && i + 1 < len) { Glyph lo = glyphs.get(i + 1); lo.cp = -1; lo.w = 0F; lo.style = style; }
         pos[0] = i + n;
         return true;
      });
      if (!ok[0] || pos[0] != len) {     // formatter changed the text shape: fall back to unstyled glyphs
         pos[0] = 0;
         OrderedText.styledForwardsVisitedString(value, Style.EMPTY).accept((index, style, cp) -> {
            int i = pos[0];
            if (i >= len) return false;
            Glyph gl = glyphs.get(i);
            gl.cp = cp; gl.style = style;
            gl.w = font.getTextHandler().getWidth(OrderedText.styled(cp, style));
            int n = Character.charCount(cp);
            if (n == 2 && i + 1 < len) { Glyph lo = glyphs.get(i + 1); lo.cp = -1; lo.w = 0F; }
            pos[0] = i + n;
            return true;
         });
      }
   }

   // ---- drawing ------------------------------------------------------------------------------------------------------

   /**
    * One glyph at a sub-pixel position. {@code blur} > 0 approximates a Gaussian blur with two rings of faint copies
    * around a dimmed core; as the blur shrinks the rings fade out and the core takes the full alpha, converging on the
    * crisp glyph.
    */
   private static void drawGlyph(MatrixStack g, TextRenderer font, int cp, Style style, float x, float y, int argb,
                                 boolean shadow, float blur) {
      int a = argb >>> 24;
      if (a < 4) return;
      OrderedText one = OrderedText.styled(cp, style);
      if (blur < 0.08F) {
         tap(g, font, one, x, y, argb, shadow);
         return;
      }
      // Two rings of 8 faint copies (r and r/2, the inner ring rotated half a step) around a dimmed core. The radius stays
      // near the stroke width so the copies overlap into a soft focus rather than reading as separate ghost images.
      float u = Math.min(1F, blur / BLUR);
      int rgb = argb & 0xFFFFFF;
      int outer = Math.round(a * 0.09F * u), inner = Math.round(a * 0.14F * u);
      int core = Math.round(a * (1F - 0.5F * u));
      float r2 = blur * 0.5F;
      for (int k = 0; k < 8; k++) {
         double ang = k * Math.PI / 4.0;
         tap(g, font, one, x + (float) Math.cos(ang) * blur, y + (float) Math.sin(ang) * blur, outer << 24 | rgb, false);
         double ang2 = ang + Math.PI / 8.0;
         tap(g, font, one, x + (float) Math.cos(ang2) * r2, y + (float) Math.sin(ang2) * r2, inner << 24 | rgb, false);
      }
      tap(g, font, one, x, y, core << 24 | rgb, shadow);
   }

   private static void tap(MatrixStack g, TextRenderer font, OrderedText one, float x, float y, int argb, boolean shadow) {
      if ((argb >>> 24) < 4) return;   // TextRenderer reads an alpha below 4 as "opaque"
      if (shadow) font.drawWithShadow(g, one, x, y, argb);
      else font.draw(g, one, x, y, argb);
   }

   // ---- math ---------------------------------------------------------------------------------------------------------

   /** Critically damped spring progress 0..1 since {@code bornNs} (0 before it, 1 when settled). */
   private static float enter(long now, long bornNs) {
      if (bornNs == Long.MIN_VALUE) return 1F;
      float t = (now - bornNs) / 1.0e9F * timeScale;
      if (t <= 0F) return 0F;
      float wt = ENTER_W * t;
      float p = 1F - (1F + wt) * (float) Math.exp(-wt);
      return p > 0.998F ? 1F : p;
   }

   /**
    * {@code enableScissor} in the CALLER's coordinate space. 1.16.5's static {@code DrawableHelper.enableScissor} takes
    * raw GUI coordinates and knows nothing of the matrix, so a clip given in a scaled / translated space — the sign
    * editor draws its lines in the sign's scaled space, centred on 0 — would land somewhere else on screen and clip
    * every glyph away. Transform the rect by the current matrix first (axis-aligned bounding box).
    *
    * <p>The sign editor queues its board model in the shared entity vertex consumers and only draws it after the text
    * loop; our glyphs are drawn immediately, so that batch is flushed first ({@link GuiFlush}) or the board would be
    * painted over them.
    */
   private static void scissor(MatrixStack g, int x0, int y0, int x1, int y1) {
      float[] r = HudGlass.absRect(g, x0, y0, x1, y1);
      int sx0 = (int) Math.floor(Math.max(-30000F, r[0]));
      int sy0 = (int) Math.floor(Math.max(-30000F, r[1]));
      int sx1 = (int) Math.ceil(Math.min(30000F, r[2]));
      int sy1 = (int) Math.ceil(Math.min(30000F, r[3]));
      GuiFlush.flush();
      dev.s1mp1e.client.gui.GuiScissor.enable(sx0, sy0, sx1, sy1);
   }

   /** Close {@link #scissor}. */
   private static void unscissor(MatrixStack g) {
      GuiFlush.flush();
      dev.s1mp1e.client.gui.GuiScissor.disable();
   }

   private static float approach(float cur, float target, float dt, float tau) {
      float k = 1F - (float) Math.exp(-dt / tau);
      float v = cur + (target - cur) * k;
      return Math.abs(target - v) < 0.02F ? target : v;
   }

   private static int withAlpha(int argb, float a) {
      int base = argb >>> 24;
      if (base == 0) base = 0xFF;
      int al = Math.round(base * Math.max(0F, Math.min(1F, a)));
      return (al & 0xFF) << 24 | argb & 0xFFFFFF;
   }
}
