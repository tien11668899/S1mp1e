package dev.s1mp1e.glass.render;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Draws one liquid-glass quad. This is LiquidGlass26's GlassRectRenderState
 * translated to immediate mode, and it keeps that class's contracts exactly:
 *
 * <ul>
 *   <li>the quad is expanded by {@code pad} GUI px on every side so the shader
 *       can draw its drop shadow OUTSIDE the shape, while the UVs stay
 *       normalised to the INNER rect (extending past 0..1 over the padding) so
 *       the shader's fwidth px reconstruction is unchanged;</li>
 *   <li>the vertex colour carries the four knobs —
 *       <b>R</b> = corner-radius scale, <b>G</b> = 1-lift (neutral brighten),
 *       <b>B</b> = element opacity, <b>A</b> = frost / neighbour-mask /
 *       enabled-dim depending on the program.</li>
 * </ul>
 */
public final class GlassRenderer {

    private GlassRenderer() {}

    /** Default frost for panels (26.2's 0x80 alpha knob). */
    public static final float FROST_PANEL = 0.5f;
    /** No frost — pure sharp refraction, used by the hotbar selector. */
    public static final float FROST_NONE  = 1.0f;

    /** 26.2's shadow padding for panels / capsules. */
    public static final float PAD_PANEL = 12f;
    public static final float PAD_PILL  = 18f;

    /**
     * Core draw. {@code kind} selects the program
     * ({@link GlassProgram#GLASS}/{@link GlassProgram#LINE}/{@link GlassProgram#BTN}).
     */
    public static void draw(int kind, float x0, float y0, float x1, float y1,
                            float pad, float r, float g, float b, float a) {
        if (!beginBatch(kind)) return;
        batchQuad(x0, y0, x1, y1, pad, r, g, b, a);
        endBatch();
    }

    // ---- batching ---------------------------------------------------------
    //
    // Every quad used to do its own glPushAttrib/glPopAttrib, glUseProgram and
    // glBegin/glEnd. The inventory lattice is one quad PER SLOT (46 of them),
    // so that was 46 full attribute-stack pushes and program switches a frame —
    // and glPushAttrib forces a pipeline flush on modern drivers, which is what
    // made the animations feel like they were running at half rate. Now the
    // state is set once, every quad in a group streams into a single
    // glBegin/glEnd, and the state is restored once.
    //
    // State goes through GlStateManager rather than raw glEnable/glDisable so
    // MC's cached view of GL stays in sync; only the immediate-mode vertex
    // calls and the program bind are raw.

    private static int  batchKind = -1;
    private static boolean batchTex;

    /** Begin a group of quads sharing one program. False -> nothing to draw. */
    public static boolean beginBatch(int kind) {
        if (!GlassProgram.ensureReady()) return false;
        if (kind == GlassProgram.GLASS && !GlassProgram.usable())    return false;
        if (kind == GlassProgram.LINE  && !GlassProgram.lineUsable()) return false;
        if (kind == GlassProgram.BTN   && !GlassProgram.btnUsable())  return false;
        batchTex = GlassProgram.needsBackdrop(kind);
        if (batchTex && !SceneCapture.hasBackdrop()) return false;

        batchKind = kind;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlpha();
        GlStateManager.depthMask(false);
        if (batchTex) {
            GlStateManager.enableTexture2D();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(SceneCapture.texture());
        } else {
            GlStateManager.disableTexture2D();
        }
        GlassProgram.bind(kind);
        GL11.glBegin(GL11.GL_QUADS);
        return true;
    }

    /** One quad inside an open batch. Same knob contract as {@link #draw}. */
    public static void batchQuad(float x0, float y0, float x1, float y1,
                                 float pad, float r, float g, float b, float a) {
        if (batchKind < 0) return;
        float w = Math.max(x1 - x0, 1f);
        float h = Math.max(y1 - y0, 1f);
        float u0 = -pad / w, u1 = 1f + pad / w;
        float v0 = -pad / h, v1 = 1f + pad / h;
        float qx0 = x0 - pad, qy0 = y0 - pad, qx1 = x1 + pad, qy1 = y1 + pad;

        GL11.glColor4f(r, g, b, a);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(qx0, qy0);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(qx0, qy1);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(qx1, qy1);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(qx1, qy0);
    }

    /** Close the group and put the state back the way MC expects it. */
    public static void endBatch() {
        if (batchKind < 0) return;
        GL11.glEnd();
        GlassProgram.unbind();
        batchKind = -1;
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ---- convenience wrappers matching 26.2's call sites -------------------

    /** Refractive glass: corner scale, neutral lift, opacity, frost. */
    public static void glass(float x0, float y0, float x1, float y1,
                             float pad, float corner, float lift,
                             float opacity, float frost) {
        draw(GlassProgram.GLASS, x0, y0, x1, y1, pad, corner, 1f - lift, opacity, frost);
    }

    /** Frosted container panel — 26.2 uses pad 12 and corner ~0.19 (0x31/255). */
    public static void panel(float x0, float y0, float x1, float y1, float opacity) {
        glass(x0, y0, x1, y1, PAD_PANEL, 0.19f, 0f, opacity, FROST_PANEL);
    }

    /**
     * One slot-separator cell. {@code mask} is the 4-bit neighbour mask
     * (1=E 2=W 4=S 8=N) the lattice shader reads from vertex alpha.
     */
    public static void latticeCell(float x0, float y0, float x1, float y1,
                                   int mask, float opacity) {
        draw(GlassProgram.LINE, x0, y0, x1, y1, 0f,
             1f, 1f, opacity, (mask * 17) / 255f);
    }

    /** Translucent capsule button: corner 1 = full capsule. */
    public static void button(float x0, float y0, float x1, float y1,
                              float corner, float lift, float opacity, boolean enabled) {
        draw(GlassProgram.BTN, x0, y0, x1, y1, 10f,
             corner, 1f - lift, opacity, enabled ? 1f : 0.4f);
    }
}
