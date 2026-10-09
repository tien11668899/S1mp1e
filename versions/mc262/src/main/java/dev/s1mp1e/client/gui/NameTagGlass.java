package dev.s1mp1e.client.gui;

import com.seagull.liquidglass.client.render.GlassCorners;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.ArrayList;

/**
 * REAL refractive liquid-glass entity / player name-tag backdrop (the {@code NameTags} module's {@code Glass} mode).
 *
 * <p><b>Why this exists.</b> Name tags live in WORLD space, but the mod's refraction pipeline is GUI space: a glass
 * element refracts the per-frame "world-only" backdrop grabbed at {@code GuiRenderer.render} HEAD (before any GUI is
 * drawn). A frosted RGB retint of the vanilla world-space plate ({@link dev.s1mp1e.client.module.NameTagModule#plate})
 * can only ever be a flat tint — it has nothing to refract. To make the plate truly refract the terrain behind it, the
 * whole tag is moved to the GUI stage:
 * <ol>
 *   <li>{@link #beginFrame} stores this frame's world PROJECTION matrix (captured at {@code LevelRenderer.render} HEAD)
 *       and clears last frame's tags.</li>
 *   <li>{@link #capture} is called from the name-tag submit hook (world render) in Glass mode: it projects the vanilla
 *       plate's four local corners through {@code projection * pose} to a GUI-scaled screen rect, records it, and the
 *       hook then CANCELS the vanilla submission — so that spot of the backdrop grab is clean terrain, no vanilla plate
 *       or text.</li>
 *   <li>{@link #render} is called at the HUD extract TAIL: it draws a real refractive glass panel over each recorded
 *       rect (the same {@code glass} pipeline the hotbar uses, which refracts the clean-terrain backdrop) with the name
 *       text crisp on top, far tags first so nearer ones layer over them.</li>
 * </ol>
 *
 * <p><b>Fair play.</b> Only tags vanilla already submitted (i.e. already passed its visibility: sneak / distance / line
 * of sight / team rules) reach {@link #capture}; nothing extra is read, no see-through-walls behaviour changes, and a
 * name whose plate opacity is zero is left to vanilla (no glass plate is ever added where vanilla draws none).
 */
public final class NameTagGlass {

    private NameTagGlass() {}

    /** Temporary diagnostic logging for the projection-alignment bring-up (remove once verified). */
    private static final boolean DEBUG = Boolean.getBoolean("s1mp1e.nametag.debug");

    /** The vanilla name-tag background's local-space rect relative to the Submit's (x, y): see Font$PreparedTextBuilder
     *  (left = x - 1, top = y - 1, right = x + width, bottom = y + 9). */
    private static final float PAD_L = 1f, PAD_T = 1f, PAD_B = 9f;

    /** Local plate height in font units (top pad 1 + line 9) — used to scale the on-screen text to the projected plate. */
    private static final float LOCAL_H = PAD_T + PAD_B;   // 10

    private static final class Tag {
        final float x0, y0, x1, y1;   // GUI-scaled screen AABB
        final float depth;            // view-space depth (clip w at the plate centre): bigger = farther
        final String text;
        final int rgb;
        Tag(float x0, float y0, float x1, float y1, float depth, String text, int rgb) {
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.depth = depth; this.text = text; this.rgb = rgb;
        }
    }

    private static final ArrayList<Tag> TAGS = new ArrayList<>();

    /** Called once per frame (at {@code LevelRenderer.render} HEAD): drop last frame's tags. */
    public static void beginFrame() {
        TAGS.clear();
    }

    /**
     * Project the vanilla plate for a name tag to a GUI-scaled screen rect and record it. Returns {@code true} when a
     * valid on-screen rect was produced (the caller then cancels the vanilla submission); {@code false} leaves the
     * vanilla tag untouched (behind the camera or empty text).
     *
     * @param projection   the camera perspective projection ({@code CameraRenderState.projectionMatrix})
     * @param viewRotation the camera view rotation ({@code CameraRenderState.viewRotationMatrix})
     * @param pose         the Submit's pose (reconstructed from the submit PoseStack: translate→billboard→0.025 scale)
     * @param tx           the Submit's x (= -font.width(text) / 2)
     * @param ty           the Submit's y
     * @param text         the name
     * @param rgb          the name colour (RGB; alpha ignored — drawn opaque for readability)
     */
    public static boolean capture(Matrix4fc projection, Matrix4fc viewRotation, Matrix4fc pose,
                                  float tx, float ty, Component text, int rgb) {
        if (projection == null || pose == null || text == null) return false;
        String s = text.getString();
        if (s.isEmpty()) return false;

        Minecraft mc = Minecraft.getInstance();
        float w = mc.font.width(text);

        // vanilla plate rect in the Submit's local (font-pixel) space
        float lx0 = tx - PAD_L, ly0 = ty - PAD_T;
        float lx1 = tx + w,     ly1 = ty + PAD_B;

        int gw = mc.getWindow().getGuiScaledWidth();
        int gh = mc.getWindow().getGuiScaledHeight();

        // The Submit pose is the MODEL matrix (camera-relative, but without the camera view rotation — verified at
        // bring-up: projection * pose alone lands behind the camera). The full transform to clip space is therefore
        // projection * viewRotation * pose, matching the vanilla vertex path (projection uniform, viewRotation in the
        // modelview, this pose on top).
        if (viewRotation == null) return false;
        Matrix4f mvp = new Matrix4f(projection).mul(viewRotation).mul(pose);

        float[] r = projectRect(mvp, lx0, ly0, lx1, ly1, gw, gh);
        if (r == null) return false;
        if (DEBUG) System.out.println("[S1mp1e][NTG] '" + s + "' gui=" + gw + "x" + gh + " rect=" + fmt(r));

        float sx0 = r[0], sy0 = r[1], sx1 = r[2], sy1 = r[3], depth = r[4];
        if (sx1 - sx0 < 0.5f || sy1 - sy0 < 0.5f) return false;

        TAGS.add(new Tag(sx0, sy0, sx1, sy1, depth, s, rgb & 0xFFFFFF));
        return true;
    }

    /** Project the local rect's four corners; returns {x0,y0,x1,y1, depth} GUI-scaled, or null if behind the camera. */
    private static float[] projectRect(Matrix4f mvp, float lx0, float ly0, float lx1, float ly1, int gw, int gh) {
        if (mvp == null) return null;
        float sx0 = Float.MAX_VALUE, sy0 = Float.MAX_VALUE, sx1 = -Float.MAX_VALUE, sy1 = -Float.MAX_VALUE;
        float[][] corners = { {lx0, ly0}, {lx1, ly0}, {lx1, ly1}, {lx0, ly1} };
        for (float[] c : corners) {
            Vector4f v = mvp.transform(new Vector4f(c[0], c[1], 0f, 1f));
            if (v.w <= 1e-4f) return null;
            float ndcx = v.x / v.w, ndcy = v.y / v.w;
            float sx = (ndcx * 0.5f + 0.5f) * gw;
            float sy = (1f - (ndcy * 0.5f + 0.5f)) * gh;
            if (sx < sx0) sx0 = sx;
            if (sy < sy0) sy0 = sy;
            if (sx > sx1) sx1 = sx;
            if (sy > sy1) sy1 = sy;
        }
        Vector4f ctr = mvp.transform(new Vector4f((lx0 + lx1) * 0.5f, (ly0 + ly1) * 0.5f, 0f, 1f));
        return new float[]{sx0, sy0, sx1, sy1, ctr.w};
    }

    private static String fmt(float[] r) {
        if (r == null) return "null";
        return "[" + (int) r[0] + "," + (int) r[1] + " -> " + (int) r[2] + "," + (int) r[3] + " w=" + r[4] + "]";
    }

    /** Called at the HUD extract TAIL: draw the recorded tags as refractive glass + text, far first, then clear. */
    public static void render(GuiGraphicsExtractor g) {
        if (DEBUG && !TAGS.isEmpty()) System.out.println("[S1mp1e][NTG] render " + TAGS.size() + " tag(s)");
        if (TAGS.isEmpty()) return;
        try {
            // far tags first so a nearer tag's glass draws over (and refracts past) a farther one
            TAGS.sort((a, b) -> Float.compare(b.depth, a.depth));
            for (Tag t : TAGS) {
                float w = t.x1 - t.x0, h = t.y1 - t.y0;
                if (w <= 0f || h <= 0f) continue;

                // REAL refracting glass plate: the recovered glass() pipeline (the hotbar's), hotbar corner, a whisper
                // of readability scrim. This samples the clean-terrain backdrop grab, so it refracts the world behind
                // the name rather than tinting it.
                // Breathing room: vanilla pads the plate by 1 font px, which leaves the glyphs touching the rounded glass
                // rim. Grow the glass (not the text) around its centre, proportional to the projected height.
                float px = h * 0.32f, py = h * 0.10f;
                float gx0 = t.x0 - px, gy0 = t.y0 - py, gx1 = t.x1 + px, gy1 = t.y1 + py;
                float radius = Math.min(GlassCorners.HOTBAR_RADIUS, Math.min(gx1 - gx0, gy1 - gy0) * 0.5f);
                GlassWidgets.panel(g, gx0, gy0, gx1, gy1, 1f, radius, 0.14f);

                // name text, scaled to the projected plate and centred; no shadow (global rule)
                float ts = h / LOCAL_H;
                float tw = GlassFont.width(t.text) * ts;
                float th = GlassFont.height() * ts;
                float cx = (t.x0 + t.x1) * 0.5f, cy = (t.y0 + t.y1) * 0.5f;
                g.pose().pushMatrix();
                try {
                    g.pose().translate(cx - tw * 0.5f, cy - th * 0.5f);
                    g.pose().scale(ts, ts);
                    GlassFont.draw(g, t.text, 0f, 0f, t.rgb, 1f, false);
                } finally {
                    g.pose().popMatrix();
                }
            }
        } catch (Throwable thr) {
            dev.s1mp1e.client.ErrorOnce.report("NameTag glass render", thr);
        } finally {
            TAGS.clear();
        }
    }
}
