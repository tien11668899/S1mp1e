package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/**
 * Draws one liquid-glass quad. This is LiquidGlass26's GlassRectRenderState
 * translated to MC 1.17.1's OpenGL 3.2 <b>core</b> profile, and it keeps that
 * class's contracts exactly:
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
 *
 * <p>Core profile forbids the fixed-function immediate mode this class used on
 * the 1.8.9-1.16.5 line (glBegin/glColor/glTexCoord/glVertex/glEnd, GL_QUADS,
 * glPushAttrib). The geometry now streams through our own VAO + VBO and is
 * drawn with {@code glDrawArrays(GL_TRIANGLES, ...)} — a quad is two triangles
 * (6 verts) so a whole group still batches into one draw call. All pipeline
 * state (blend/depth/cull/texture) goes through {@link RenderSystem}; the
 * vertex/UV/pad math and the four-knob colour contract are byte-for-byte the
 * same as the immediate-mode path.
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

    // ---- batching (core profile: VAO + VBO + glDrawArrays) ----------------
    //
    // The immediate-mode line did its own glPushAttrib/glBegin/glEnd per quad;
    // core profile makes every one of those an INVALID_OPERATION. Instead each
    // quad appends 6 interleaved vertices (two triangles) to a client-side
    // FloatBuffer, and endBatch uploads the whole group to a VBO and issues a
    // single glDrawArrays. The inventory lattice (up to 46 slots) is therefore
    // one buffer upload + one draw call, not 46 attribute-stack pushes.
    //
    // Vertex layout (matches GlassProgram's glBindAttribLocation + glass.vsh):
    //   loc 0 Position vec2  @ 0    loc 1 UV0 vec2 @ 8    loc 2 Color vec4 @ 16
    //   8 floats = 32 bytes, tightly packed. Colour is 4 FLOATS (not normalized
    //   bytes) so the knobs survive at full float precision as glColor4f gave.
    //
    // A VAO MUST be bound for glDrawArrays in core profile, so we own one; the
    // VBO + attrib pointers are (re)bound while it is bound in endBatch.

    private static final int FLOATS_PER_VERT = 8;
    private static final int STRIDE_BYTES    = FLOATS_PER_VERT * 4; // 32
    private static final int VERTS_PER_QUAD  = 6;                   // 2 triangles

    /** Lazily-created GL objects (0 until first bound; created once, reused). */
    private static int vao = 0, vbo = 0;
    /** MC's VAO/VBO bindings, saved and restored around our draw so its own
     *  GUI rendering in the same frame is not left pointing at VAO 0. */
    private static int prevVao = 0, prevVbo = 0;

    /** Growable client-side staging; grown on demand, uploaded each endBatch. */
    private static FloatBuffer cpu =
            BufferUtils.createFloatBuffer(64 * VERTS_PER_QUAD * FLOATS_PER_VERT);
    private static int vertCount = 0;

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
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0); // SRC_ALPHA, ONE_MINUS_SRC_ALPHA, 1, 0
        RenderSystem.disableCull();                      // triangles: never cull on winding
        RenderSystem.depthMask(false);
        if (batchTex) {
            // Bind our SceneCapture backdrop to unit 0 so the shader's Sampler0
            // (uniform = 0, set in GlassProgram.bind) reads it. bindTexture binds
            // to the active unit, so select GL_TEXTURE0 first. (Not setShaderTexture:
            // that feeds MC's own shader system, not our standalone program's sampler.)
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(SceneCapture.texture());
        }
        GlassProgram.bind(kind); // sets ProjMat/ModelViewMat/Sampler0/ScreenSize/ColorModulator

        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
        }
        cpu.clear();
        vertCount = 0;
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

        // Old GL_QUADS corners, same pos/UV as the immediate-mode path:
        //   A = (qx0,qy0, u0,v0) top-left    B = (qx0,qy1, u0,v1) bottom-left
        //   C = (qx1,qy1, u1,v1) bottom-right D = (qx1,qy0, u1,v0) top-right
        // Quad A-B-C-D -> triangles (A,B,C) + (A,C,D): same winding, same
        // coverage, and because both triangles keep each corner's exact UV the
        // interpolated vLocal (and thus the fwidth px, the SDF, the refraction)
        // is pixel-identical to GL_QUADS.
        vert(qx0, qy0, u0, v0, r, g, b, a); // A
        vert(qx0, qy1, u0, v1, r, g, b, a); // B
        vert(qx1, qy1, u1, v1, r, g, b, a); // C
        vert(qx0, qy0, u0, v0, r, g, b, a); // A
        vert(qx1, qy1, u1, v1, r, g, b, a); // C
        vert(qx1, qy0, u1, v0, r, g, b, a); // D
    }

    /** Append one interleaved vertex (8 floats: pos.xy, uv.xy, rgba). */
    private static void vert(float x, float y, float u, float v,
                             float r, float g, float b, float a) {
        ensure(FLOATS_PER_VERT);
        cpu.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
        vertCount++;
    }

    /** Grow the staging buffer (doubling) so at least {@code extra} floats fit. */
    private static void ensure(int extra) {
        if (cpu.remaining() >= extra) return;
        int need = cpu.position() + extra;
        int cap  = cpu.capacity();
        while (cap < need) cap <<= 1;
        FloatBuffer bigger = BufferUtils.createFloatBuffer(cap);
        cpu.flip();
        bigger.put(cpu);
        cpu = bigger;
    }

    /** Close the group: upload once, one draw call, restore MC's GL state. */
    public static void endBatch() {
        if (batchKind < 0) return;
        if (vertCount > 0) {
            cpu.flip();
            // Save MC's current bindings so we can restore them (NOT bind 0):
            // MC keeps its own VAO bound across the GUI pass and would otherwise
            // hit "Array object is not active" on every draw after ours.
            prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            // Orphan + upload exactly the used floats (buffer's data store is
            // reallocated each frame -> no CPU/GPU sync stall on the stream).
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cpu, GL15.GL_DYNAMIC_DRAW);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 0L);   // Position
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 8L);   // UV0
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, STRIDE_BYTES, 16L);  // Color
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertCount);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
            GL30.glBindVertexArray(prevVao);
        }
        GlassProgram.unbind();
        batchKind = -1;
        // Put back the state MC's GUI pipeline expects, via RenderSystem only
        // (no glPushAttrib/color4f/enableTexture/enableAlphaTest — none exist in
        // core; the shaders handle their own discard, and MC re-establishes its
        // own program + sampler on its next draw).
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        // CRITICAL (1.20+): beginBatch bound our SceneCapture backdrop to texture
        // unit 0 for the shader's Sampler0. MC's batched GUI pipeline (DrawContext
        // .drawItem etc.) reuses whatever is bound to unit 0, so leaving the backdrop
        // there makes every item drawn AFTER the glass this frame sample our capture
        // instead of the item atlas — the recipe-book result items rendered invisible
        // while the hotbar (drawn before any glass batch) looked fine. Unbind through
        // RenderSystem so its texture cache matches real GL again; bind 0 first to
        // defeat its no-op-when-cache-equal short circuit (same rule as the 1.16.5
        // SceneCapture resync).
        if (batchTex) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(0);
        }
        batchTex = false;
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
