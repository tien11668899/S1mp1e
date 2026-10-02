package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Apple-style icons for the game's interactive glyph buttons (user: "只要替換遊戲內的交互按鈕 — 打勾、叉叉、翻頁、篩選按鈕與翻頁箭頭").
 * The 1.14.4 port of the 1.20.1 line's {@code SfIcons} (itself the 1.21.1 line's, itself LiquidGlass26's).
 *
 * <p><b>1.14.4 has no GUI sprites</b> ({@code drawGuiTexture} arrived with 1.20.2) <b>and no {@code DrawContext}</b>:
 * every one of these glyphs is a REGION of an atlas texture ({@code textures/gui/recipe_book.png},
 * {@code server_selection.png}, …) that the caller first binds with {@code GlStateManager.setShaderTexture(0, id)} and
 * then draws with one of the {@code DrawableHelper.drawTexture} overloads. All of those overloads funnel into one
 * private static {@code drawTexture(x0, x1, y0, y1, z, regionW, regionH, u, v, texW, texH)}, which
 * {@code SfIconMixin} hooks; the texture is not an argument there, so {@link #atlasSpriteBound} first matches the
 * region geometry and only then compares the BOUND GL texture with the handful of atlases listed below.
 * {@link #atlasSprite} turns an exact (texture, u, v, size) into the name the same glyph has as a 1.20.2+ sprite, so
 * the table below is the 1.21.1 one, unchanged. The sprites were cut out of exactly these atlas cells (identical PNG
 * layouts in 1.14.4, checked against the client jar), so the per-icon fractions carry over as they are. Not in
 * 1.14.4: {@code widget/cross_button} (no such button yet).
 *
 * <p>For the sprites listed in {@link #MAP} the pixel-art glyph is replaced by an SF
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
    private static final class Icon {
        final String glyph;
        final float fx0, fy0, fx1, fy1, fill;
        final int tint, hoverTint;
        final Frame frame;

        Icon(String glyph, float fx0, float fy0, float fx1, float fy1, float fill, int tint, int hoverTint, Frame frame) {
            this.glyph = glyph;
            this.fx0 = fx0; this.fy0 = fy0; this.fx1 = fx1; this.fy1 = fy1;
            this.fill = fill;
            this.tint = tint; this.hoverTint = hoverTint;
            this.frame = frame;
        }
    }

    private static final Map<String, Icon> MAP = new HashMap<String, Icon>();

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
        // written / lectern books: page turn. These two only ever sit on the cream book page, where the reference's
        // white chevron is close to invisible -> dark ink (the page text colour family); hover still goes blue.
        glyph("widget/page_forward", "chevron.right", 0.0f, 0.0f, 1.0f, 1.0f, 0.85f, 0xFF3A3A3C, true);
        glyph("widget/page_backward", "chevron.left", 0.0f, 0.0f, 1.0f, 1.0f, 0.85f, 0xFF3A3A3C, true);
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

    // ---- 1.20.1: atlas region -> sprite name -------------------------------------------------------------------

    private static final Map<String, String> ATLAS = new HashMap<String, String>();
    private static final java.util.List<String[]> GALLERY = new java.util.ArrayList<String[]>();

    private static String key(String tex, int u, int v, int w, int h, int texW) {
        return tex + '|' + u + '|' + v + '|' + w + '|' + h + '|' + texW;
    }

    /** One atlas cell; {@code dv > 0} also registers the hovered cell {@code dv} px below as "_highlighted". */
    private static void cell(String tex, int u, int v, int w, int h, int texW, int texH, int dv, String sprite) {
        ATLAS.put(key(tex, u, v, w, h, texW), sprite);
        if (dv > 0) ATLAS.put(key(tex, u, v + dv, w, h, texW), sprite + "_highlighted");
        GALLERY.add(new String[]{tex, String.valueOf(u), String.valueOf(v), String.valueOf(w), String.valueOf(h),
                String.valueOf(texW), String.valueOf(texH)});
    }

    static {
        // ToggleButtonWidget (recipe book): u + 13 = the mirrored arrow, u + 28 = filter on, v + 18 = hovered
        String rb = "textures/gui/recipe_book.png";
        cell(rb, 1, 208, 12, 17, 256, 256, 18, "recipe_book/page_forward");
        cell(rb, 14, 208, 12, 17, 256, 256, 18, "recipe_book/page_backward");
        cell(rb, 152, 41, 26, 16, 256, 256, 18, "recipe_book/filter_disabled");
        cell(rb, 180, 41, 26, 16, 256, 256, 18, "recipe_book/filter_enabled");
        cell(rb, 152, 182, 26, 16, 256, 256, 18, "recipe_book/furnace_filter_disabled");
        cell(rb, 180, 182, 26, 16, 256, 256, 18, "recipe_book/furnace_filter_enabled");
        // PageTurnWidget (books, lectern): u + 23 = hovered, v 192 forward / 205 backward
        String book = "textures/gui/book.png";
        ATLAS.put(key(book, 0, 192, 23, 13, 256), "widget/page_forward");
        ATLAS.put(key(book, 23, 192, 23, 13, 256), "widget/page_forward_highlighted");
        ATLAS.put(key(book, 0, 205, 23, 13, 256), "widget/page_backward");
        ATLAS.put(key(book, 23, 205, 23, 13, 256), "widget/page_backward_highlighted");
        GALLERY.add(new String[]{book, "0", "205", "23", "13", "256", "256"});
        GALLERY.add(new String[]{book, "0", "192", "23", "13", "256", "256"});
        // beacon confirm / cancel icons, the chat-report tick
        cell("textures/gui/container/beacon.png", 90, 220, 18, 18, 256, 256, 0, "container/beacon/confirm");
        cell("textures/gui/container/beacon.png", 112, 220, 18, 18, 256, 256, 0, "container/beacon/cancel");
        // (textures/gui/checkmark.png, the chat-report tick, does not exist in 1.14.4: icon/checkmark stays unmapped)
        // server / world / pack lists: 32 px cells, v + 32 = hovered
        String sv = "textures/gui/server_selection.png";
        cell(sv, 0, 0, 32, 32, 256, 256, 32, "server_list/join");
        cell(sv, 96, 0, 32, 32, 256, 256, 32, "server_list/move_up");
        cell(sv, 64, 0, 32, 32, 256, 256, 32, "server_list/move_down");
        String wl = "textures/gui/world_selection.png";
        cell(wl, 0, 0, 32, 32, 256, 256, 32, "world_list/join");
        cell(wl, 32, 0, 32, 32, 256, 256, 32, "world_list/marked_join");
        cell(wl, 64, 0, 32, 32, 256, 256, 32, "world_list/warning");
        cell(wl, 96, 0, 32, 32, 256, 256, 32, "world_list/error");
        String pk = "textures/gui/resource_packs.png";
        cell(pk, 96, 0, 32, 32, 256, 256, 32, "transferable_list/move_up");
        cell(pk, 64, 0, 32, 32, 256, 256, 32, "transferable_list/move_down");
        // statistics column sort (128 px atlas), spectator menu
        cell("textures/gui/container/stats_icons.png", 36, 0, 18, 18, 128, 128, 0, "statistics/sort_up");
        cell("textures/gui/container/stats_icons.png", 18, 0, 18, 18, 128, 128, 0, "statistics/sort_down");
        String sp = "textures/gui/spectator_widgets.png";
        cell(sp, 144, 0, 16, 16, 256, 256, 0, "spectator/scroll_left");
        cell(sp, 160, 0, 16, 16, 256, 256, 0, "spectator/scroll_right");
        cell(sp, 128, 0, 16, 16, 256, 256, 0, "spectator/close");
        // checkbox - 1.14.4: a 32 x 64 sheet, v + 20 = checked; there is no focused cell (that is the 1.16 64 px atlas)
        String cb = "textures/gui/checkbox.png";
        cell(cb, 0, 0, 20, 20, 32, 64, 0, "widget/checkbox");
        cell(cb, 0, 20, 20, 20, 32, 64, 0, "widget/checkbox_selected");
    }

    /**
     * The 1.20.2+ sprite name of an atlas region drawn unscaled ({@code w x h} px of {@code texture} at
     * {@code (u, v)}, texture width {@code texW}), or null when it is not one of the mapped glyphs.
     */
    public static String atlasSprite(String texturePath, float u, float v, int w, int h, int texW) {
        if (devBypass || u != (int) u || v != (int) v) return null;
        return ATLAS.get(key(texturePath, (int) u, (int) v, w, h, texW));
    }

    // ---- 1.14.4: the texture is the BOUND one, not an argument ---------------------------------------------------

    /** Region geometry "u|v|w|h|texW" -> the atlas paths that have a mapped cell there (built lazily from ATLAS). */
    private static Map<String, java.util.List<String>> byGeometry;
    private static final Map<String, Identifier> ATLAS_IDS = new HashMap<String, Identifier>();

    /**
     * {@link #atlasSprite} for a draw whose texture is whatever {@code GlStateManager.setShaderTexture(0, …)} bound:
     * null unless the region is a mapped cell of one of our atlases AND that atlas is the bound texture.
     */
    public static String atlasSpriteBound(float u, float v, int w, int h, int texW) {
        if (devBypass || u != (int) u || v != (int) v) return null;
        if (byGeometry == null) {
            Map<String, java.util.List<String>> m = new HashMap<String, java.util.List<String>>();
            for (String k : ATLAS.keySet()) {
                int bar = k.indexOf('|');
                java.util.List<String> l = m.get(k.substring(bar + 1));
                if (l == null) {
                    l = new java.util.ArrayList<String>();
                    m.put(k.substring(bar + 1), l);
                }
                l.add(k.substring(0, bar));
            }
            byGeometry = m;
        }
        java.util.List<String> paths = byGeometry.get("" + (int) u + '|' + (int) v + '|' + w + '|' + h + '|' + texW);
        if (paths == null) return null;
        int bound = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);   // 1.14.4: the GL binding itself
        if (bound <= 0) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        for (String path : paths) {
            Identifier id = ATLAS_IDS.get(path);
            if (id == null) {
                id = new Identifier(path);
                ATLAS_IDS.put(path, id);
            }
            net.minecraft.client.texture.Texture tex = mc.getTextureManager().getTexture(id);   // null when never bound
            if (tex != null && tex.getGlId() == bound) return ATLAS.get(key(path, (int) u, (int) v, w, h, texW));
        }
        return null;
    }

    /** Dev only (DevShot icon gallery): every mapped region as {texture, u, v, w, h, texW, texH}. */
    public static String[][] devGallery() { return GALLERY.toArray(new String[0][]); }

    /** True when {@code spritePath} (minecraft namespace) has an SF replacement. */
    public static boolean has(String spritePath) { return !devBypass && MAP.containsKey(spritePath); }

    // ---- textures ------------------------------------------------------------------------------------------------

    private static final class Glyph {
        final Identifier id;
        final int w, h;

        Glyph(Identifier id, int w, int h) {
            this.id = id;
            this.w = w;
            this.h = h;
        }
    }

    private static final Map<String, Glyph> GLYPHS = new HashMap<String, Glyph>();
    private static final Map<String, Boolean> FAILED = new HashMap<String, Boolean>();

    private static Glyph glyphTex(String name) {
        Glyph gl = GLYPHS.get(name);
        if (gl != null || FAILED.containsKey(name)) return gl;
        String path = "/assets/s1mp1e/textures/gui/sf/" + name + ".png";
        try (InputStream in = SfIcons.class.getResourceAsStream(path)) {
            if (in != null) {
                NativeImage img = NativeImage.read(in);
                Identifier id = new Identifier("s1mp1e", "textures/gui/sf/" + name + ".png");
                int w = img.getWidth(), h = img.getHeight();
                NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                tex.bindTexture();
                tex.setFilter(true, false);   // bilinear: the glyphs are minified (1.14.4: applies to the bound texture)
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

    private static final Map<Long, Fade> HOVER = new HashMap<Long, Fade>();

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
    private static void blitGlyph(Glyph gl, float px, float py, float s, int argb, float alpha) {
        blitGlyph(gl, px, py, s, argb, alpha, true);
    }

    /**
     * @param alphaFromShader {@code alpha} was read from the current shader colour, which already contains an open
     *                        {@link dev.s1mp1e.client.gui.GuiAlpha} scope: set it raw (not scoped a second time)
     */
    private static void blitGlyph(Glyph gl, float px, float py, float s, int argb, float alpha,
                                  boolean alphaFromShader) {
        float[] sc = currentColor();
        float pr = sc[0], pg = sc[1], pb = sc[2], pa = sc[3];
        float a = ((argb >>> 24) & 255) / 255f * alpha;
        // 1.14.4: the caller bound its atlas and may go on drawing other regions of it after this call returns
        // (the world list draws its icon, then the join arrow, from one binding) -> put the binding back.
        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        net.minecraft.client.MinecraftClient.getInstance().getTextureManager().bindTexture(gl.id);
        float cr = ((argb >> 16) & 255) / 255f, cg = ((argb >> 8) & 255) / 255f, cb = (argb & 255) / 255f;
        GlStateManager.color4f(cr, cg, cb, a);   // 1.14.4: a GuiAlpha scope is not part of the colour (texture-env stage)
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.pushMatrix();
        GlStateManager.translated(px, py, 0f);
        GlStateManager.scalef(s, s, 1f);
        DrawableHelper.blit(0, 0, 0f, 0f, gl.w, gl.h, gl.w, gl.h);
        GlStateManager.popMatrix();
        GlStateManager.color4f(pr, pg, pb, pa);
        GlStateManager.bindTexture(prevTex);
    }

    private static final java.nio.FloatBuffer COLOR4 = org.lwjgl.BufferUtils.createFloatBuffer(16);

    /**
     * 1.14.4: the fixed-function current colour (vanilla widgets set {@code GlStateManager.color4f(1, 1, 1, alpha)}
     * right before they blit a sprite) — the counterpart of the shader colour the newer lines read.
     */
    private static float[] currentColor() {
        COLOR4.clear();
        org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_CURRENT_COLOR, COLOR4);
        return new float[]{COLOR4.get(0), COLOR4.get(1), COLOR4.get(2), COLOR4.get(3)};
    }

    /** Draw SF Symbol {@code glyph} aspect-fitted into the box (x0,y0)-(x1,y1), tinted {@code argb}; false if missing. */
    public static boolean drawGlyph(String glyph, float x0, float y0, float x1, float y1, int argb) {
        Glyph gl = glyphTex(glyph);
        if (gl == null || x1 <= x0 || y1 <= y0) return false;
        float s = Math.min((x1 - x0) / gl.w, (y1 - y0) / gl.h);
        float dw = gl.w * s, dh = gl.h * s;
        blitGlyph(gl, (x0 + x1) / 2.0f - dw / 2.0f, (y0 + y1) / 2.0f - dh / 2.0f, s, argb, 1f, false);
        return true;
    }

    // ---- draw ----------------------------------------------------------------------------------------------------

    /** Draw the replacement for {@code spritePath} (minecraft namespace); false = not ours, draw vanilla. */
    public static boolean draw(String spritePath, int x, int y, int w, int h) {
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
        float alpha = Math.max(0f, Math.min(1f, currentColor()[3]));   // the widget's alpha

        if (framed) {
            float open = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
            alpha *= open;
            dev.s1mp1e.glass.render.GuiFlush.flush();   // the immediate glass body must land on top of what was queued before it
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
        blitGlyph(gl, cx - dw / 2.0f, cy - dh / 2.0f, s, tint, alpha);
        return true;
    }
}
