package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Apple-style icons for the game's interactive glyph buttons (user: "只要替換遊戲內的交互按鈕 — 打勾、叉叉、翻頁、篩選按鈕與翻頁箭頭").
 * The 1.21.1 (DrawContext) port of LiquidGlass26's {@code SfIcons}.
 *
 * <p>Every vanilla GUI sprite draw funnels through {@code DrawContext.drawGuiTexture(id, x, y, z, w, h)}
 * ({@code SfIconMixin} hooks its HEAD). For the sprites listed in {@link #MAP} the pixel-art glyph is replaced by an SF
 * Symbol (from andrewtavis/sf-symbols-online, the same set the launcher uses; white PNGs trimmed to their ink, shipped in
 * {@code assets/s1mp1e/textures/gui/sf/}), aspect-fitted into the region the vanilla glyph occupied and tinted by
 * meaning (confirm green, cancel red, active/hover system blue, else white). Sprites that are a whole button (recipe
 * filter, checkbox, cross button) get the BTN glass body first — with the hotbar corner ({@link GlassCorners}), which
 * turns the small square ones into iOS-style circles — and a 100 ms hover lift like every other glass button.
 *
 * <p>The PNGs are read from the classpath and registered as bilinear {@link NativeImageBackedTexture}s (the ~30 px
 * glyphs are drawn at 8–17 GUI px). Anything that fails to load falls back to the vanilla sprite. Where 26.2 received
 * the widget alpha as the sprite's colour argument, 1.21.1 carries it on the shader colour, so it is read from there
 * and restored. Render thread only.
 */
public final class SfIcons {
    private SfIcons() {}

    /** Dev only (DevShot icon gallery): draw the vanilla sprite instead, for a side-by-side comparison. */
    public static boolean devBypass;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0A84FF;    // system blue (same as the caret)
    private static final int GREEN = 0xFF30D158;   // system green
    private static final int RED = 0xFFFF453A;     // system red

    private enum Frame { NONE, PILL, CHECK_OFF, CHECK_ON, CROSS }

    /** Region of the drawn rect the glyph may use (fractions), how much of it to fill, tints and body. */
    private record Icon(String glyph, float fx0, float fy0, float fx1, float fy1, float fill, int tint, int hoverTint,
                        Frame frame) {}

    private static final Map<String, Icon> MAP = new HashMap<>();

    private static void put(String sprite, Icon icon) { MAP.put(sprite, icon); }

    /** Glyph-only sprite; the "_highlighted" variant (if any) uses the hover tint. */
    private static void glyph(String sprite, String glyph, float fx0, float fy0, float fx1, float fy1, float fill, int tint,
                              boolean hasHighlight) {
        Icon icon = new Icon(glyph, fx0, fy0, fx1, fy1, fill, tint, hasHighlight ? BLUE : tint, Frame.NONE);
        put(sprite, icon);
        if (hasHighlight) put(sprite + "_highlighted", icon);   // hover blends tint -> blue over 100 ms
    }

    static {
        // recipe book: page arrows + the "craftable only" filter (crafting + furnace books)
        glyph("recipe_book/page_forward", "chevron.right", 0.0f, 0.0f, 0.917f, 1.0f, 0.72f, WHITE, true);
        glyph("recipe_book/page_backward", "chevron.left", 0.083f, 0.0f, 1.0f, 1.0f, 0.72f, WHITE, true);
        for (String f : new String[]{"recipe_book/filter", "recipe_book/furnace_filter"}) {
            Icon on = new Icon("line.horizontal.3.decrease", 0.0f, 0.0f, 1.0f, 1.0f, 0.62f, BLUE, BLUE, Frame.PILL);
            Icon off = new Icon("line.horizontal.3.decrease", 0.0f, 0.0f, 1.0f, 1.0f, 0.62f, WHITE, WHITE, Frame.PILL);
            put(f + "_enabled", on);
            put(f + "_enabled_highlighted", on);
            put(f + "_disabled", off);
            put(f + "_disabled_highlighted", off);
        }
        // written / lectern books: page turn
        glyph("widget/page_forward", "chevron.right", 0.0f, 0.0f, 1.0f, 1.0f, 0.85f, WHITE, true);
        glyph("widget/page_backward", "chevron.left", 0.0f, 0.0f, 1.0f, 1.0f, 0.85f, WHITE, true);
        // beacon confirm / cancel, the small green tick
        glyph("container/beacon/confirm", "checkmark", 0.0f, 0.0f, 1.0f, 1.0f, 0.72f, GREEN, false);
        glyph("container/beacon/cancel", "xmark", 0.0f, 0.0f, 1.0f, 1.0f, 0.64f, RED, false);
        glyph("icon/checkmark", "checkmark", 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, GREEN, false);
        // server / world lists: join, reorder
        glyph("server_list/join", "play.fill", 0.5f, 0.156f, 0.938f, 0.844f, 0.62f, WHITE, true);
        glyph("world_list/join", "play.fill", 0.312f, 0.156f, 0.75f, 0.844f, 0.62f, WHITE, true);
        // other-version / marked worlds show marked_join instead of join, and warning / error for incompatible or
        // broken worlds — replace all so they match the adapted join.
        glyph("world_list/marked_join", "play.fill", 0.5f, 0.156f, 0.938f, 0.844f, 0.62f, WHITE, true);
        glyph("world_list/warning", "exclamationmark.triangle.fill", 0.09f, 0.156f, 0.47f, 0.844f, 1.0f, 0xFFFFD60A, true);
        glyph("world_list/error", "exclamationmark.octagon.fill", 0.09f, 0.156f, 0.47f, 0.844f, 1.0f, RED, true);
        glyph("server_list/move_up", "chevron.up", 0.094f, 0.156f, 0.438f, 0.375f, 1.0f, WHITE, true);
        glyph("server_list/move_down", "chevron.down", 0.094f, 0.625f, 0.438f, 0.844f, 1.0f, WHITE, true);
        glyph("transferable_list/move_up", "chevron.up", 0.562f, 0.156f, 0.906f, 0.375f, 1.0f, WHITE, true);
        glyph("transferable_list/move_down", "chevron.down", 0.562f, 0.625f, 0.906f, 0.844f, 1.0f, WHITE, true);
        // statistics column sort, spectator menu
        glyph("statistics/sort_up", "chevron.up", 0.222f, 0.278f, 0.833f, 0.667f, 1.0f, WHITE, false);
        glyph("statistics/sort_down", "chevron.down", 0.222f, 0.333f, 0.833f, 0.722f, 1.0f, WHITE, false);
        glyph("spectator/scroll_left", "chevron.left", 0.062f, 0.125f, 0.5f, 0.812f, 0.9f, WHITE, false);
        glyph("spectator/scroll_right", "chevron.right", 0.5f, 0.125f, 0.938f, 0.812f, 0.9f, WHITE, false);
        glyph("spectator/close", "xmark.circle.fill", 0.062f, 0.062f, 0.938f, 0.938f, 0.9f, RED, false);
        // whole-button sprites: checkbox (iOS circle), cross button
        Icon offBox = new Icon(null, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, WHITE, WHITE, Frame.CHECK_OFF);
        Icon onBox = new Icon("checkmark", 0.0f, 0.0f, 1.0f, 1.0f, 0.5f, WHITE, WHITE, Frame.CHECK_ON);
        put("widget/checkbox", offBox);
        put("widget/checkbox_highlighted", offBox);
        put("widget/checkbox_selected", onBox);
        put("widget/checkbox_selected_highlighted", onBox);
        Icon cross = new Icon("xmark", 0.0f, 0.0f, 1.0f, 1.0f, 0.46f, WHITE, WHITE, Frame.CROSS);
        put("widget/cross_button", cross);
        put("widget/cross_button_highlighted", cross);
    }

    /** True when {@code spritePath} (minecraft namespace) has an SF replacement. */
    public static boolean has(String spritePath) { return !devBypass && MAP.containsKey(spritePath); }

    // ---- textures ------------------------------------------------------------------------------------------------

    private record Glyph(Identifier id, int w, int h) {}

    private static final Map<String, Glyph> GLYPHS = new HashMap<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();

    private static Glyph glyphTex(String name) {
        Glyph gl = GLYPHS.get(name);
        if (gl != null || FAILED.containsKey(name)) return gl;
        String path = "/assets/s1mp1e/textures/gui/sf/" + name + ".png";
        try (InputStream in = SfIcons.class.getResourceAsStream(path)) {
            if (in != null) {
                NativeImage img = NativeImage.read(in);
                Identifier id = Identifier.of("s1mp1e", "textures/gui/sf/" + name + ".png");
                int w = img.getWidth(), h = img.getHeight();
                NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                tex.setFilter(true, false);   // bilinear: the glyphs are minified
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                gl = new Glyph(id, w, h);
                GLYPHS.put(name, gl);
                return gl;
            }
        } catch (Throwable ignored) {
        }
        FAILED.put(name, Boolean.TRUE);
        return null;
    }

    // ---- hover fade (per on-screen position; sprites swap to "_highlighted" instantly in vanilla) -----------------

    private static final Map<Long, Fade> HOVER = new HashMap<>();

    private static float hover(String base, int x, int y, boolean over) {
        long key = ((long) x << 40) ^ ((long) y << 20) ^ base.hashCode();
        Fade f = HOVER.get(key);
        if (f == null) {
            if (HOVER.size() > 512) HOVER.clear();
            f = new Fade(over ? 1.0f : 0.0f, 100.0f);
            HOVER.put(key, f);
        }
        float target = over ? 1.0f : 0.0f;
        if (f.target() != target) {
            f.snap(f.value());
            f.to(target);
        }
        return f.value();
    }

    private static int mix(int a, int b, float t) {
        if (t <= 0.0f) return a;
        if (t >= 1.0f) return b;
        int ar = a >>> 16 & 0xFF, ag = a >>> 8 & 0xFF, ab = a & 0xFF, aa = a >>> 24;
        int br = b >>> 16 & 0xFF, bg = b >>> 8 & 0xFF, bb = b & 0xFF, ba = b >>> 24;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    /** Draw glyph texture {@code gl} scaled by {@code s} with its top-left at (px,py), tinted {@code argb} × alpha. */
    private static void blitGlyph(DrawContext ctx, Glyph gl, float px, float py, float s, int argb, float alpha) {
        float[] sc = RenderSystem.getShaderColor();
        float pr = sc[0], pg = sc[1], pb = sc[2], pa = sc[3];
        float a = ((argb >>> 24) & 255) / 255f * alpha;
        ctx.setShaderColor(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, a);
        RenderSystem.enableBlend();
        ctx.getMatrices().push();
        ctx.getMatrices().translate(px, py, 0f);
        ctx.getMatrices().scale(s, s, 1f);
        ctx.drawTexture(gl.id, 0, 0, 0f, 0f, gl.w, gl.h, gl.w, gl.h);
        ctx.getMatrices().pop();
        ctx.setShaderColor(pr, pg, pb, pa);
    }

    /** Draw SF Symbol {@code glyph} aspect-fitted into the box (x0,y0)-(x1,y1), tinted {@code argb}; false if missing. */
    public static boolean drawGlyph(DrawContext ctx, String glyph, float x0, float y0, float x1, float y1, int argb) {
        Glyph gl = glyphTex(glyph);
        if (gl == null || x1 <= x0 || y1 <= y0) return false;
        float s = Math.min((x1 - x0) / gl.w, (y1 - y0) / gl.h);
        float dw = gl.w * s, dh = gl.h * s;
        blitGlyph(ctx, gl, (x0 + x1) / 2.0f - dw / 2.0f, (y0 + y1) / 2.0f - dh / 2.0f, s, argb, 1f);
        return true;
    }

    // ---- draw ----------------------------------------------------------------------------------------------------

    /** Draw the replacement for {@code spritePath} (minecraft namespace); false = not ours, draw vanilla. */
    public static boolean draw(DrawContext ctx, String spritePath, int x, int y, int w, int h) {
        if (devBypass) return false;
        Icon icon = MAP.get(spritePath);
        if (icon == null || w <= 0 || h <= 0) return false;
        Glyph gl = icon.glyph == null ? null : glyphTex(icon.glyph);
        if (icon.glyph != null && gl == null) return false;
        boolean framed = icon.frame != Frame.NONE;
        if (framed && !(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return false;

        boolean highlighted = spritePath.endsWith("_highlighted");
        String base = highlighted ? spritePath.substring(0, spritePath.length() - "_highlighted".length()) : spritePath;
        float hov = hover(base, x, y, highlighted);
        float alpha = Math.max(0f, Math.min(1f, RenderSystem.getShaderColor()[3]));   // the widget's alpha

        if (framed) {
            float open = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
            alpha *= open;
            ctx.draw();   // the immediate glass body must land on top of what was queued before it
            GlassRenderer.button(x, y, x + w, y + h, GlassCorners.hotbarCornerFrac(w, h), 0.81f * hov, alpha, true);
            if (icon.frame == Frame.CHECK_ON) {
                float r = Math.min(w, h) / 2.0f;
                int a = Math.round(255f * alpha * 0.92f) & 0xFF;
                GlassRenderer.roundRect(x + 1.0f, y + 1.0f, x + w - 1.0f, y + h - 1.0f, r - 1.0f, a << 24 | (BLUE & 0xFFFFFF));
            }
        }
        if (gl == null) return true;

        int tint = mix(icon.tint, icon.hoverTint, hov);
        float bx0 = x + icon.fx0 * w, by0 = y + icon.fy0 * h, bx1 = x + icon.fx1 * w, by1 = y + icon.fy1 * h;
        float bw = (bx1 - bx0) * icon.fill, bh = (by1 - by0) * icon.fill;
        float s = Math.min(bw / gl.w, bh / gl.h);
        float dw = gl.w * s, dh = gl.h * s;
        float cx = (bx0 + bx1) / 2.0f, cy = (by0 + by1) / 2.0f;
        blitGlyph(ctx, gl, cx - dw / 2.0f, cy - dh / 2.0f, s, tint, alpha);
        return true;
    }
}
