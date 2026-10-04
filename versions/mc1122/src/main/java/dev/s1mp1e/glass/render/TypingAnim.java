package dev.s1mp1e.glass.render;

import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * Per-text-field typing animation and the settings-row value roll — the 1.12.2 immediate-mode port of
 * {@code versions/mc1144}'s {@code glass/render/TypingAnim} (itself the 26.2 one). Vanilla draws the visible text as
 * two substrings split at the caret, so every edit is a hard cut; this draws it one glyph at a time instead:
 * <ul>
 *   <li><b>Entrance</b> — a new glyph blurs&rarr;sharp, rises from below and fades in on one critically damped spring
 *       (~0.2 s); several glyphs inserted at once cascade.</li>
 *   <li><b>Exit</b> — a deleted glyph floats up, blurs and fades (a whole-string replacement reads as an odometer
 *       roll — that is the settings-row value roll, {@link #extractLabel}).</li>
 *   <li><b>Glide</b> — glyphs after a mid-string edit slide to their new place; the horizontal scroll eases.</li>
 *   <li><b>Caret / selection</b> — a system-blue capsule caret gliding in text coordinates with a smooth blink; the
 *       selection edges glide.</li>
 * </ul>
 *
 * <p><b>1.12.2 adaptation.</b> Text goes through {@code FontRenderer.drawString(String,float,float,int,boolean)} (the
 * float overload — on this line the replaced FontRenderer routes it to PingFang at sub-pixel positions); widths come
 * from {@link GlassFont#width} when PingFang is live (float, sub-pixel) else the bitmap font. Fills use
 * {@link GlassWidgets#fillRound} / {@code Gui.drawRect}. There is no matrix-aware scissor in 1.12.2, so the clip rect
 * is transformed by the live {@code GL_MODELVIEW_MATRIX} (the sign editor draws in a scaled, translated space) and set
 * with {@code glScissor} directly; {@link #restoreScissor} lets a caller put its own scissor back afterwards.
 * Render thread only.
 */
public final class TypingAnim {

    private static final float ENTER_W = 22.0F;
    private static final float RISE = 3.0F;
    private static final float BLUR = 1.4F;
    private static final float OUT_S = 0.15F;
    private static final float FLOAT_UP = 3.0F;
    private static final float BLUR_OUT = 1.2F;
    private static final float GLIDE_TAU = 0.05F;
    private static final float SCROLL_TAU = 0.07F;
    private static final float CARET_TAU = 0.045F;
    private static final float HL_TAU = 0.05F;
    private static final int CARET_RGB = 0x0A84FF;
    private static final float CARET_W = 2.0F;
    private static final int HL_ARGB = 0x660A84FF;
    private static final long MAX_CASCADE_NS = 120_000_000L;
    private static final long CASCADE_STEP_NS = 12_000_000L;

    /** Set when rendering threw once: the caller falls back to vanilla drawing for this box. */
    public boolean broken;

    /** Dev only (DevShot): &lt;1 slows entrance/exit down to inspect them frame by frame. */
    public static float timeScale = 1.0F;
    /** Dev only (DevShot): log the newest glyph's entrance progress each frame. */
    public static boolean debugLog;

    private static final class Glyph {
        long bornNs;
        float x = Float.NaN;
        float y = Float.NaN;
        int cp;
        String style = "";
        float w;
    }

    private static final class Dying {
        int cp; String style; float x; float y; long deathNs; int color;
    }

    private String last;
    private final ArrayList<Glyph> glyphs = new ArrayList<Glyph>();
    private final ArrayList<Dying> dying = new ArrayList<Dying>();
    private float[] full = new float[1];
    private float scrollCur = Float.NaN, scrollTarget;
    private long lastNs;
    private float frameDt;
    private int textX, textY, clipX0, clipX1, clipY0, clipY1, cursorPos, lineHeight;

    private float caretCur = Float.NaN, caretTarget = Float.NaN;
    private long caretMoveNs;
    private float hl0 = Float.NaN, hl1 = Float.NaN;
    private boolean hlPrev, hlNow;

    // ---- text field pass -----------------------------------------------------------------------------------

    public void extractText(FontRenderer font, String value, String formatted,
                            int displayPos, int cursorPos, int textX, int textY, int innerWidth,
                            int color, boolean shadow) {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F) * timeScale;
        lastNs = now;
        frameDt = dt;
        this.textX = textX;
        this.textY = textY;
        this.cursorPos = cursorPos;
        this.lineHeight = font.FONT_HEIGHT;
        this.clipX0 = textX;
        this.clipX1 = textX + innerWidth;
        this.clipY0 = textY - 8;
        this.clipY1 = textY + font.FONT_HEIGHT + 8;
        this.hlPrev = this.hlNow;
        this.hlNow = false;

        if (last == null) {
            insertGlyphs(0, value.length(), now);
            last = value;
        } else if (!value.equals(last)) {
            applyEdit(last, value, cursorPos, now, color);
            last = value;
        }

        styleAndMeasure(font, value, formatted);

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

        scissor(clipX0, clipY0, clipX1, clipY1);
        try {
            for (int k = dying.size() - 1; k >= 0; k--) {
                Dying d = dying.get(k);
                float q = (now - d.deathNs) / 1.0e9F * timeScale / OUT_S;
                if (q >= 1F) { dying.remove(k); continue; }
                float sx = textX + d.x - scrollCur;
                if (sx > clipX1 + 4 || sx < clipX0 - 16) continue;
                float e = q * q;
                float a = (1F - q) * (1F - q);
                drawGlyph(font, d.cp, d.style, sx, textY - FLOAT_UP * e, withAlpha(d.color, a), shadow, BLUR_OUT * q);
            }
            for (int i = 0; i < len; i++) {
                Glyph gl = glyphs.get(i);
                if (gl.cp < 0) continue;
                float sx = textX + gl.x - scrollCur;
                float rel = sx - textX;
                if (scrolling || Math.abs(gl.x - full[i]) > 0.05F) {
                    if (rel + gl.w < -0.5F || rel > visibleLimit + 0.5F) continue;
                } else if (i < dp || full[i + 1] - scrollTarget > visibleLimit) {
                    continue;
                }
                float p = enter(now, gl.bornNs);
                if (p <= 0F) continue;
                float inv = 1F - p;
                float a = 1F - inv * inv;
                drawGlyph(font, gl.cp, gl.style, sx, textY + RISE * inv, withAlpha(color, a), shadow, BLUR * inv);
            }
        } finally {
            unscissor();
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

    // ---- centred label mode (sign lines, settings value roll) -------------------------------------------------

    private float originCur = Float.NaN;
    private static final float ORIGIN_TAU = 0.06F;

    public void extractLabel(FontRenderer font, String value, String formatted,
                             int centerX, int textY, int clipX0, int clipX1, int color, boolean shadow) {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F) * timeScale;
        lastNs = now;
        frameDt = dt;
        this.textY = textY;
        this.lineHeight = font.FONT_HEIGHT;
        this.clipX0 = clipX0;
        this.clipX1 = clipX1;
        this.clipY0 = textY - 8;
        this.clipY1 = textY + font.FONT_HEIGHT + 8;

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

        scissor(clipX0, clipY0, clipX1, clipY1);
        try {
            for (int k = dying.size() - 1; k >= 0; k--) {
                Dying d = dying.get(k);
                float q = (now - d.deathNs) / 1.0e9F * timeScale / OUT_S;
                if (q >= 1F) { dying.remove(k); continue; }
                float e = q * q;
                drawGlyph(font, d.cp, d.style, originCur + d.x, textY - FLOAT_UP * e,
                        withAlpha(d.color, (1F - q) * (1F - q)), shadow, BLUR_OUT * q);
            }
            for (int i = 0; i < len; i++) {
                Glyph gl = glyphs.get(i);
                if (gl.cp < 0) continue;
                float p = enter(now, gl.bornNs);
                if (p <= 0F) continue;
                float inv = 1F - p;
                drawGlyph(font, gl.cp, gl.style, originCur + gl.x, textY + RISE * inv,
                        withAlpha(color, 1F - inv * inv), shadow, BLUR * inv);
            }
        } finally {
            unscissor();
        }
    }

    /** True while any glyph is still entering or leaving (a caller may keep re-rendering). */
    public boolean animating() {
        if (!dying.isEmpty()) return true;
        long now = System.nanoTime();
        for (Glyph g : glyphs) if (g.bornNs != Long.MIN_VALUE && enter(now, g.bornNs) < 1F) return true;
        return false;
    }

    public float scrollDelta() {
        return Float.isNaN(scrollCur) ? 0F : scrollTarget - scrollCur;
    }

    // ---- caret -----------------------------------------------------------------------------------------------

    public void drawCaret() {
        if (last == null) return;
        int c = Math.max(0, Math.min(cursorPos, last.length()));
        drawCaretAt(full[c] - 1.5F, textX - scrollCur);
    }

    public void drawLabelCaret(int cursor, int textY) {
        if (last == null || Float.isNaN(originCur)) return;
        this.textY = textY;
        int c = Math.max(0, Math.min(cursor, last.length()));
        drawCaretAt(full[c] - 1.5F, originCur);
    }

    private void drawCaretAt(float target, float origin) {
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
        GlassWidgets.fillRound(sx, textY - 1, sx + CARET_W, textY + lineHeight, (alpha << 24) | CARET_RGB, CARET_W * 0.5F);
    }

    // ---- selection -------------------------------------------------------------------------------------------

    public void drawHighlight(int highlightPos, int y0, int y1) {
        if (last == null) return;
        int len = last.length();
        int c = Math.max(0, Math.min(cursorPos, len));
        int h = Math.max(0, Math.min(highlightPos, len));
        float t0 = full[Math.min(c, h)] - 1F, t1 = full[Math.max(c, h)] - 1F;
        if (!hlPrev || Float.isNaN(hl0)) hl0 = hl1 = full[h] - 1F;
        hlNow = true;
        hl0 = approach(hl0, t0, frameDt, HL_TAU);
        hl1 = approach(hl1, t1, frameDt, HL_TAU);
        int x0 = Math.round(textX + hl0 - scrollCur), x1 = Math.round(textX + hl1 - scrollCur);
        if (x1 <= x0) return;
        scissor(clipX0, clipY0, clipX1, clipY1);
        try {
            Gui.drawRect(x0, y0, x1, y1, HL_ARGB);
            GlassWidgets.resetColorCache();
        } finally {
            unscissor();
        }
    }

    // ---- edit tracking ---------------------------------------------------------------------------------------

    private void applyEdit(String o, String n, int cursor, long now, int color) {
        int lo = o.length(), ln = n.length();
        int p = 0, maxP = Math.min(lo, ln);
        while (p < maxP && o.charAt(p) == n.charAt(p)) p++;
        if (ln > lo) p = Math.min(p, Math.max(0, cursor - (ln - lo)));
        else p = Math.min(p, Math.max(0, cursor));
        if (p > 0 && p < ln && Character.isLowSurrogate(n.charAt(p))) p--;
        int s = 0, maxS = Math.min(lo, ln) - p;
        while (s < maxS && o.charAt(lo - 1 - s) == n.charAt(ln - 1 - s)) s++;
        if (s > 0 && lo - s < lo && Character.isLowSurrogate(o.charAt(lo - s))) s--;

        for (int i = p; i < lo - s && i < glyphs.size(); i++) {
            Glyph gl = glyphs.get(i);
            if (gl.cp < 0 || Float.isNaN(gl.x)) continue;
            if (enter(now, gl.bornNs) <= 0F) continue;
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

    private void styleAndMeasure(FontRenderer font, String value, String formatted) {
        final int len = value.length();
        while (glyphs.size() < len) { Glyph gl = new Glyph(); gl.bornNs = Long.MIN_VALUE; glyphs.add(gl); }
        while (glyphs.size() > len) glyphs.remove(glyphs.size() - 1);
        if (formatted == null || !assign(font, formatted, len, true)) {
            assign(font, value, len, false);
        }
    }

    private boolean assign(FontRenderer font, String s, int len, boolean styled) {
        String color = "", mods = "";
        int i = 0;
        for (int k = 0; k < s.length(); ) {
            char c = s.charAt(k);
            if (styled && c == '§' && k + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(k + 1));
                if (code == 'r') { color = ""; mods = ""; }
                else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) { color = "§" + code; mods = ""; }
                else if (code >= 'k' && code <= 'o') { mods = mods + "§" + code; }
                k += 2;
                continue;
            }
            int cp = s.codePointAt(k);
            int n = Character.charCount(cp);
            if (i >= len) return false;
            Glyph gl = glyphs.get(i);
            gl.cp = cp;
            gl.style = color + mods;
            gl.w = width(font, new String(Character.toChars(cp)));
            if (n == 2 && i + 1 < len) { Glyph lo = glyphs.get(i + 1); lo.cp = -1; lo.w = 0F; lo.style = gl.style; }
            i += n;
            k += n;
        }
        return i == len;
    }

    /** Advance of one glyph: sub-pixel PingFang when live, else the bitmap font. */
    private static float width(FontRenderer font, String one) {
        if (GlassFont.available()) return GlassFont.width(one);
        return font.getStringWidth(one);
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    private static void drawGlyph(FontRenderer font, int cp, String style, float x, float y, int argb,
                                  boolean shadow, float blur) {
        int a = argb >>> 24;
        if (a < 4) return;
        String one = style + new String(Character.toChars(cp));
        if (blur < 0.08F) {
            tap(font, one, x, y, argb, shadow);
            return;
        }
        float u = Math.min(1F, blur / BLUR);
        int rgb = argb & 0xFFFFFF;
        int outer = Math.round(a * 0.09F * u), inner = Math.round(a * 0.14F * u);
        int core = Math.round(a * (1F - 0.5F * u));
        float r2 = blur * 0.5F;
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4.0;
            tap(font, one, x + (float) Math.cos(ang) * blur, y + (float) Math.sin(ang) * blur, outer << 24 | rgb, false);
            double ang2 = ang + Math.PI / 8.0;
            tap(font, one, x + (float) Math.cos(ang2) * r2, y + (float) Math.sin(ang2) * r2, inner << 24 | rgb, false);
        }
        tap(font, one, x, y, core << 24 | rgb, shadow);
    }

    private static void tap(FontRenderer font, String one, float x, float y, int argb, boolean shadow) {
        if ((argb >>> 24) < 4) return;
        font.drawString(one, x, y, argb, false);   // no drop shadow anywhere (global rule)
    }

    // ---- math / GL -------------------------------------------------------------------------------------------

    private static float enter(long now, long bornNs) {
        if (bornNs == Long.MIN_VALUE) return 1F;
        float t = (now - bornNs) / 1.0e9F * timeScale;
        if (t <= 0F) return 0F;
        float wt = ENTER_W * t;
        float p = 1F - (1F + wt) * (float) Math.exp(-wt);
        return p > 0.998F ? 1F : p;
    }

    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    /** Scissor in the CALLER's coordinate space: the rect is transformed by the live modelview (AABB). */
    private static void scissor(int x0, int y0, int x1, int y1) {
        float[] m = new float[16];
        try {
            MAT.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT);
            for (int i = 0; i < 16; i++) m[i] = MAT.get(i);
        } catch (Throwable t) {
            for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1f : 0f;
        }
        float ax0 = m[0] * x0 + m[4] * y0 + m[12], ay0 = m[1] * x0 + m[5] * y0 + m[13];
        float ax1 = m[0] * x1 + m[4] * y1 + m[12], ay1 = m[1] * x1 + m[5] * y1 + m[13];
        float rx0 = Math.min(ax0, ax1), rx1 = Math.max(ax0, ax1), ry0 = Math.min(ay0, ay1), ry1 = Math.max(ay0, ay1);
        GlassWidgets.beginScissor(Math.max(-30000F, rx0), Math.max(-30000F, ry0),
                Math.min(30000F, rx1), Math.min(30000F, ry1));
    }

    private static void unscissor() {
        GlassWidgets.endScissor();
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

    @SuppressWarnings("unused")
    private static int scaleFactor() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
    }
}
