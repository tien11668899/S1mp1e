package dev.s1mp1e.o.knife;

import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Fixed-function draw of a skinned CS2 mesh for 1.8.9. The mesh has already been skinned into render (eye) space by
 * {@link KnifeRig.Attached#pose(KnifeRig, org.joml.Matrix4f, org.joml.Matrix3f)} — with the final pose + normal
 * matrices folded in — so each vertex is a plain copy into a client array and drawn under an identity modelview,
 * through the same lightmapped, standard-item-lighting GL state vanilla set up for the held item. One draw call per
 * material part. No VBOs (1.8.9's held item is itself fixed-function, so the context is compatibility profile).
 */
final class KnifeDraw {

    private KnifeDraw() {}

    // per-mesh static geometry buffers (UVs and the triangle index list never change), keyed by mesh identity
    private static final Map<KMesh, FloatBuffer> UV = new IdentityHashMap<>();
    private static final Map<KMesh, IntBuffer> IDX = new IdentityHashMap<>();
    // reused per-frame scratch for the skinned positions / normals (grown as needed)
    private static FloatBuffer pos, nrm;

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static FloatBuffer uv(KMesh m) {
        FloatBuffer b = UV.get(m);
        if (b == null) { b = floats(m.uv.length); b.put(m.uv).flip(); UV.put(m, b); }
        return b;
    }

    private static IntBuffer idx(KMesh m) {
        IntBuffer b = IDX.get(m);
        if (b == null) {
            b = ByteBuffer.allocateDirect(m.index.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            b.put(m.index).flip();
            IDX.put(m, b);
        }
        return b;
    }

    /**
     * Draw mesh {@code a} (already posed into eye space) with {@code partTex} giving the GL texture id for each
     * material part (0 = skip). {@code light} is unused on 1.8.9 (the lightmap is already bound on unit 1).
     */
    static void drawPosed(KnifeRig.Attached a, IntUnaryOperator partTex, int light) {
        draw(a, partTex, null);
    }

    /**
     * Like {@link #drawPosed} but only the triangles skinned to one body side ({@code 'R'}/{@code 'L'}) — the CS
     * arms mesh carries both arms, and the boxing draws one arm per fist (the left mirrors the right), like vanilla's
     * single first-person arm.
     */
    static void drawPosedSide(KnifeRig.Attached a, IntUnaryOperator partTex, int light, char side) {
        draw(a, partTex, sideIndex(a.mesh, side));
    }

    private static void draw(KnifeRig.Attached a, IntUnaryOperator partTex, IntBuffer[] sidePart) {
        KMesh m = a.mesh;
        int vc = m.vertexCount;
        if (pos == null || pos.capacity() < vc * 3) { pos = floats(vc * 3); nrm = floats(vc * 3); }
        pos.clear(); pos.put(a.outPos, 0, vc * 3).flip();
        nrm.clear(); nrm.put(a.outNrm, 0, vc * 3).flip();
        FloatBuffer uvb = uv(m);
        IntBuffer idxb = sidePart == null ? idx(m) : null;

        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevArray = GL11.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);

        GlStateManager.pushMatrix();
        GL11.glLoadIdentity();                                   // vertices are already in eye space
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);             // client arrays, not a VBO
        GL11.glDisable(GL11.GL_CULL_FACE);                      // the FOV scale / mirror flips winding; draw both sides
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, pos);
        GL11.glNormalPointer(GL11.GL_FLOAT, 0, nrm);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 0, uvb);

        for (int p = 0; p < m.partStart.length; p++) {
            int tex = partTex.applyAsInt(p);
            if (tex <= 0) continue;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            if (sidePart == null) {
                idxb.position(m.partStart[p]);
                idxb.limit(m.partStart[p] + m.partCount[p]);
                GL11.glDrawElements(GL11.GL_TRIANGLES, idxb);
                idxb.clear();
            } else {
                IntBuffer sb = sidePart[p];
                if (sb != null) { sb.position(0); sb.limit(sb.capacity()); GL11.glDrawElements(GL11.GL_TRIANGLES, sb); }
            }
        }

        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.popMatrix();

        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, prevArray);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
    }

    // ---- per-side triangle filtering (cached per mesh+side) ---------------------------------------------
    private static final Map<KMesh, Map<Character, IntBuffer[]>> SIDE = new IdentityHashMap<>();

    /** Per-part direct index buffers holding only triangles skinned to {@code side}. */
    private static IntBuffer[] sideIndex(KMesh m, char side) {
        Map<Character, IntBuffer[]> byChar = SIDE.computeIfAbsent(m, k -> new java.util.HashMap<>());
        IntBuffer[] got = byChar.get(side);
        if (got != null) return got;
        IntBuffer[] out = new IntBuffer[m.partStart.length];
        for (int p = 0; p < m.partStart.length; p++) {
            int s = m.partStart[p], c = m.partCount[p];
            java.util.ArrayList<Integer> keep = new java.util.ArrayList<>();
            for (int i = s; i + 2 < s + c; i += 3) {
                // a triangle belongs to a side if its dominant vertex is skinned there
                if (vertexSide(m, m.index[i]) == side) { keep.add(m.index[i]); keep.add(m.index[i + 1]); keep.add(m.index[i + 2]); }
            }
            if (keep.isEmpty()) { out[p] = null; continue; }
            IntBuffer b = ByteBuffer.allocateDirect(keep.size() * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            for (int v : keep) b.put(v);
            b.flip();
            out[p] = b;
        }
        byChar.put(side, out);
        return out;
    }

    private static final int TEXTURE_SRGB_DECODE_EXT = 0x8A48, SKIP_DECODE_EXT = 0x8A4A;

    /**
     * Tell the GPU not to sRGB-decode the currently-bound texture on sample. The skin/glove composites are
     * SRGB8_ALPHA8 (so the compositor's linear output is sRGB-encoded on store, matching CS2's own textures), but
     * 1.8.9's fixed-function pipeline multiplies lighting in gamma space and samples item textures raw — a second
     * decode here would darken bright finishes (fade, tiger tooth). Needs GL_EXT_texture_sRGB_decode; a no-op without
     * it (the INVALID_ENUM is cleared), which just leaves the pre-fix look.
     */
    static void skipSrgbDecode() {
        try {
            GL11.glGetError();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, TEXTURE_SRGB_DECODE_EXT, SKIP_DECODE_EXT);
            GL11.glGetError();
        } catch (Throwable ignored) {}
    }

    /** The body side of a vertex = the side token (_R / _L) in its highest-weight joint's bone name, or 0. */
    private static char vertexSide(KMesh m, int v) {
        float best = -1f; int bj = -1;
        for (int c = 0; c < 4; c++) {
            float w = m.weight[v * 4 + c];
            if (w > best) { best = w; bj = m.joint[v * 4 + c]; }
        }
        if (bj < 0) return 0;
        String bone = m.nodeName[m.jointNode[bj]];
        if (bone.contains("_R")) return 'R';
        if (bone.contains("_L")) return 'L';
        return 0;
    }
}
