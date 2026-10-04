package dev.s1mp1e.o.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.resource.Identifier;

/**
 * A drop-in {@link TextRenderer} that paints EVERY vanilla string in PingFang via
 * {@link GlassFont}. Installed over {@code mc.textRenderer} once at runtime, so all
 * vanilla text (chat, item names, GUIs, tooltips, scoreboard, HUD) matches the launcher.
 *
 * <p>Overrides every public draw + width entry point (the private {@code drawLayer}
 * funnel is reimplemented here), handling the {@code §} colour / reset codes and drop
 * shadow, and routing all width + wrapping through GlassFont so layout stays consistent
 * with what is drawn. Styling codes (bold/italic/obfuscated/underline) render as plain.
 *
 * <p>Only installed when {@link GlassFont#available()} is true; every method still guards
 * on it and falls back to the vanilla bitmap font, so text is never lost.
 */
public final class S1mp1eFontRenderer extends TextRenderer {

    private final int[] palette = new int[16];

    public S1mp1eFontRenderer(Minecraft mc) {
        super(mc.options, new Identifier("textures/font/ascii.png"), mc.getTextureManager(), false);
        // The 16 vanilla chat colours (§0-§f), built with TextRenderer's own formula.
        for (int i = 0; i < 16; i++) {
            int j = (i >> 3 & 1) * 85;
            int r = (i >> 2 & 1) * 170 + j;
            int g = (i >> 1 & 1) * 170 + j;
            int b = (i & 1) * 170 + j;
            if (i == 6) r += 85;
            palette[i] = (r & 255) << 16 | (g & 255) << 8 | (b & 255);
        }
    }

    @Override
    public int getWidth(char c) {
        if (!GlassFont.available()) return super.getWidth(c);
        if (c == 167) return -1;                                   // § format prefix
        return Math.max(c == ' ' ? 1 : 0, Math.round(GlassFont.charWidth(c)));
    }

    @Override
    public int getWidth(String text) {
        if (text == null) return 0;
        if (!GlassFont.available()) return super.getWidth(text);
        float w = 0f;
        boolean skip = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skip) { skip = false; continue; }                  // the char after § is the code
            if (c == 167) { skip = true; continue; }
            w += GlassFont.charWidth(c);
        }
        return Math.round(w);
    }

    // The single funnel every vanilla text draw routes through — draw(int,int,int)
    // and drawWithShadow both call this, so overriding it covers them.
    @Override
    public int draw(String text, float x, float y, int color, boolean dropShadow) {
        return render(text, x, y, color, dropShadow);
    }

    @Override
    public void splitAndDraw(String str, int x, int y, int wrapWidth, int color) {
        if (str == null || !GlassFont.available()) { super.splitAndDraw(str, x, y, wrapWidth, color); return; }
        java.util.List lines = this.split(str, wrapWidth);   // wraps via our getWidth
        for (int i = 0; i < lines.size(); i++) {
            render((String) lines.get(i), x, y, color, false);
            y += this.fontHeight;
        }
    }

    // ---- internals ----

    private int render(String text, float x, float y, int color, boolean shadow) {
        if (text == null) return (int) x;
        if (!GlassFont.available()) {
            return super.draw(text, x, y, color, shadow);   // super. = vanilla funnel, no recursion
        }
        if ((color & 0xFC000000) == 0) color |= 0xFF000000;        // default to opaque
        // Global no-text-shadow rule: never draw the offset dark pass. (Previously this drew
        // a full x+1/y+1 run in a dark colour — that is the drop shadow the user asked removed.)
        return (int) drawRuns(text, x, y, color, false);
    }

    /** Walk the string, splitting on § colour codes, drawing each run via GlassFont. */
    private float drawRuns(String text, float x, float y, int baseColor, boolean shadow) {
        float a = ((baseColor >>> 24) & 0xFF) / 255f;
        int base = baseColor & 0xFFFFFF;
        int cur = base;
        float penX = x;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 167 && i + 1 < text.length()) {
                penX = flush(run, penX, y, cur, a, shadow);
                char code = Character.toLowerCase(text.charAt(++i));
                int idx = "0123456789abcdef".indexOf(code);
                if (idx >= 0) cur = palette[idx];
                else if (code == 'r') cur = base;                  // reset; k/l/m/n/o ignored
                continue;
            }
            run.append(c);
        }
        return flush(run, penX, y, cur, a, shadow);
    }

    private float flush(StringBuilder run, float x, float y, int rgb, float a, boolean shadow) {
        if (run.length() == 0) return x;
        String s = run.toString();
        run.setLength(0);
        int c = shadow ? ((rgb & 0xFCFCFC) >> 2) : rgb;            // vanilla shadow = colour / 4
        GlassFont.draw(s, x, y, c, a, false);
        return x + GlassFont.width(s);
    }
}
