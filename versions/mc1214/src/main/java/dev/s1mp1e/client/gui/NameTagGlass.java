package dev.s1mp1e.client.gui;

import dev.s1mp1e.glass.render.GlassCorners;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.ArrayList;

/**
 * REAL refractive liquid-glass entity / player name-tag backdrop (the {@code NameTags} module's {@code Glass} mode).
 * 1.21.1 (Fabric / DrawContext / core-profile) port of 26.2's {@code NameTagGlass}.
 *
 * <p><b>Why this exists.</b> Name tags live in WORLD space, but the mod's refraction pipeline is GUI space: a glass
 * element refracts the per-frame "world-only" backdrop grabbed at {@code InGameHud.render} HEAD (before any HUD is
 * drawn). A frosted RGB retint of the vanilla world-space plate ({@link dev.s1mp1e.client.module.NameTagModule#plate})
 * can only ever be a flat tint — it has nothing to refract. To make the plate truly refract the terrain behind it, the
 * whole tag is moved to the HUD stage:
 * <ol>
 *   <li>{@link #beginFrame} stores this frame's world view-rotation + projection matrices (captured at
 *       {@code WorldRenderer.render} HEAD) and clears last frame's tags.</li>
 *   <li>{@link #capture} is called from the name-tag render hook ({@code EntityRenderer.renderLabelIfPresent}, world
 *       render) in Glass mode: it projects the vanilla plate's four local corners through
 *       {@code projection * viewRotation * pose} to a GUI-scaled screen rect, records it, and the hook then CANCELS the
 *       vanilla label — so that spot of the backdrop grab is clean terrain, no vanilla plate or text.</li>
 *   <li>{@link #render} is called from the Fabric {@code HudRenderCallback} (after the vanilla HUD, the world backdrop
 *       already grabbed): it draws a real refractive glass panel over each recorded rect (the same {@code glass}
 *       pipeline the hotbar uses, which refracts the clean-terrain backdrop) with the name text crisp on top, far tags
 *       first so nearer ones layer over them.</li>
 * </ol>
 *
 * <p><b>1.21.1 transform note.</b> Unlike 26.2 (whose Submit pose was model-only), 1.21.1 renders entities from a FRESH
 * identity {@code MatrixStack} while the camera view rotation lives in {@code RenderSystem}'s model-view
 * (WorldRenderer.render pushes {@code positionMatrix} onto the model-view stack, then builds entities on a new stack).
 * So the label's pose is camera-relative WITHOUT the view rotation, and the full clip transform is
 * {@code projection * viewRotation * pose} — the same three-matrix product 26.2 used.
 *
 * <p><b>Fair play.</b> Only tags vanilla already drew (i.e. already passed its visibility: sneak / distance / line of
 * sight / team rules) reach {@link #capture}; nothing extra is read, no see-through-walls behaviour changes, and a name
 * whose plate opacity is zero is left to vanilla (no glass plate is ever added where vanilla draws none).
 */
public final class NameTagGlass {

    private NameTagGlass() {}

    /** Temporary diagnostic logging for the projection-alignment bring-up. MUST stay a plain getBoolean — never OR'd
     *  with a literal {@code true} (that forced-on flag has shipped in the past). */
    private static final boolean DEBUG = Boolean.getBoolean("s1mp1e.nametag.debug");

    /** The vanilla name-tag background's local-space rect relative to the label's (x, y): the TextRenderer background
     *  quad spans left = x - 1, top = y - 1, right = x + width, bottom = y + 9. */
    private static final float PAD_L = 1f, PAD_T = 1f, PAD_B = 9f;

    /** Local plate height in font units (top pad 1 + line 9) — used to scale the on-screen text to the projected plate. */
    private static final float LOCAL_H = PAD_T + PAD_B;   // 10

    /** This frame's world matrices, captured at WorldRenderer.render HEAD. */
    private static final Matrix4f PROJ = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    private static boolean haveMatrices;

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

    /** Called once per frame (at {@code WorldRenderer.render} HEAD): store this frame's matrices, drop last frame's tags. */
    public static void beginFrame(Matrix4fc viewRotation, Matrix4fc projection) {
        TAGS.clear();
        if (viewRotation != null && projection != null) {
            VIEW.set(viewRotation);
            PROJ.set(projection);
            haveMatrices = true;
        } else {
            haveMatrices = false;
        }
    }

    /**
     * Project the vanilla plate for a name tag to a GUI-scaled screen rect and record it. Returns {@code true} when a
     * valid on-screen rect was produced (the caller then cancels the vanilla label); {@code false} leaves the vanilla
     * tag untouched (no matrices this frame, behind the camera, or empty text).
     *
     * @param pose the label's pose ({@code matrices.peek().getPositionMatrix()} after translate -> billboard -> 0.025 scale)
     * @param tx   the label's x (= -font.width(text) / 2)
     * @param ty   the label's y (0, or -10 for the deadmau5 easter egg)
     * @param text the name
     * @param rgb  the name colour (RGB; alpha ignored — drawn opaque for readability)
     */
    public static boolean capture(Matrix4fc pose, float tx, float ty, Text text, int rgb) {
        if (!haveMatrices || pose == null || text == null) return false;
        String s = text.getString();
        if (s.isEmpty()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        float w = mc.textRenderer.getWidth(text);

        // vanilla plate rect in the label's local (font-pixel) space
        float lx0 = tx - PAD_L, ly0 = ty - PAD_T;
        float lx1 = tx + w,     ly1 = ty + PAD_B;

        int gw = mc.getWindow().getScaledWidth();
        int gh = mc.getWindow().getScaledHeight();

        // Full transform to clip space = projection * viewRotation * pose, matching the vanilla vertex path (projection
        // uniform, viewRotation in RenderSystem's model-view, this pose on top of a fresh entity stack).
        Matrix4f mvp = new Matrix4f(PROJ).mul(VIEW).mul(pose);

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

    /** Called from the Fabric HudRenderCallback: draw the recorded tags as refractive glass + text, far first, then clear. */
    public static void render(DrawContext ctx) {
        if (DEBUG && !TAGS.isEmpty()) System.out.println("[S1mp1e][NTG] render " + TAGS.size() + " tag(s)");
        if (TAGS.isEmpty()) return;
        try {
            // far tags first so a nearer tag's glass draws over (and refracts past) a farther one
            TAGS.sort((a, b) -> Float.compare(b.depth, a.depth));
            for (Tag t : TAGS) {
                float w = t.x1 - t.x0, h = t.y1 - t.y0;
                if (w <= 0f || h <= 0f) continue;

                // REAL refracting glass plate: the hotbar's glass() pipeline, hotbar corner, a whisper of readability
                // scrim. This samples the clean-terrain backdrop grab, so it refracts the world behind the name.
                // Breathing room: vanilla pads the plate by 1 font px, which leaves the glyphs touching the rounded glass
                // rim. Grow the glass (not the text) around its centre, proportional to the projected height.
                float px = h * 0.32f, py = h * 0.10f;
                float gx0 = t.x0 - px, gy0 = t.y0 - py, gx1 = t.x1 + px, gy1 = t.y1 + py;
                float radius = GlassCorners.hotbarRadiusPx(gx1 - gx0, gy1 - gy0);
                GlassWidgets.panel(ctx, gx0, gy0, gx1, gy1, 1f, radius, 0.14f);

                // name text, scaled to the projected plate and centred; no shadow (global rule)
                float ts = h / LOCAL_H;
                float tw = GlassFont.width(t.text) * ts;
                float th = GlassFont.height() * ts;
                float cx = (t.x0 + t.x1) * 0.5f, cy = (t.y0 + t.y1) * 0.5f;
                ctx.getMatrices().push();
                try {
                    ctx.getMatrices().translate(cx - tw * 0.5f, cy - th * 0.5f, 0f);
                    ctx.getMatrices().scale(ts, ts, 1f);
                    GlassFont.draw(ctx, t.text, 0f, 0f, t.rgb, 1f, false);
                } finally {
                    ctx.getMatrices().pop();
                }
            }
        } catch (Throwable ignored) {
            // one bad frame never breaks the HUD
        } finally {
            TAGS.clear();
        }
    }
}
