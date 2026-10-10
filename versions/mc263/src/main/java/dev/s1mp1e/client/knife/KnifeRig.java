package dev.s1mp1e.client.knife;

/**
 * CS2 viewmodel rig. One DRIVER skeleton (weapon_arms) takes the clip; every rendered mesh (arms or a glove, and
 * the knife) is bone-merged onto it the way Source 2 does: a mesh bone with the same name as a driver bone follows
 * it, the knife's {@code weapon} root follows the driver's {@code wpn}, anything else hangs off its own parent with
 * its rest local. Then plain linear-blend skinning on the CPU (a few ten-thousand vertices — sub-millisecond).
 */
public final class KnifeRig {

    final KMesh driver;
    /** per driver node: t(3) q(4) */
    final float[] local;
    final float[] world;
    private final float[] tmp = new float[16];
    /**
     * Clip channels for bones the driver doesn't have — the knife's own {@code weapon_offset} (every twirl / flip
     * in draw and inspect lives there) and moving parts like the butterfly's handles. Bone-merged meshes take these
     * as the local transform of their same-name bone instead of its rest pose.
     */
    private final java.util.HashMap<String, float[]> extra = new java.util.HashMap<>();
    private final java.util.HashSet<String> extraSet = new java.util.HashSet<>();

    public KnifeRig(KMesh driver) {
        this.driver = driver;
        int n = driver.nodeName.length;
        local = new float[n * 7];
        world = new float[n * 16];
        reset();
    }

    public void reset() {
        int n = driver.nodeName.length;
        for (int i = 0; i < n; i++) {
            System.arraycopy(driver.nodeT, i * 3, local, i * 7, 3);
            System.arraycopy(driver.nodeQ, i * 4, local, i * 7 + 3, 4);
        }
        extraSet.clear();
    }

    /** Set driver node {@code node}'s local pos+quat from {@code s[0..6]}. */
    void setLocal(int node, float[] s) {
        System.arraycopy(s, 0, local, node * 7, 7);
    }

    /** Set the local pos+quat of a non-driver bone (by name) for this frame. */
    void setExtra(String name, float[] s) {
        System.arraycopy(s, 0, extra.computeIfAbsent(name, k -> new float[7]), 0, 7);
        extraSet.add(name);
    }

    /** This frame's local pos+quat of a non-driver bone, or null if no clip drives it. */
    float[] extra(String name) {
        return extraSet.contains(name) ? extra.get(name) : null;
    }

    public void solve() {
        KMesh d = driver;
        for (int k = 0; k < d.order.length; k++) {
            int i = d.order[k];
            int o = i * 7;
            KMath.trs(local[o], local[o + 1], local[o + 2], local[o + 3], local[o + 4], local[o + 5], local[o + 6],
                    d.nodeS[i * 3], d.nodeS[i * 3 + 1], d.nodeS[i * 3 + 2], tmp, 0);
            int p = d.nodeParent[i];
            if (p < 0) KMath.copy(tmp, 0, world, i * 16);
            else KMath.mul(world, p * 16, tmp, 0, world, i * 16);
        }
    }

    /** A mesh bone-merged onto the driver, with its skinned output buffers. */
    public static final class Attached {
        public final KMesh mesh;
        private final int[] merge;
        private final float[] world;
        private final float[] skin, nskin;
        private final int[] infStart, infJ;
        private final float[] infW;
        private final float[] tmp = new float[16];
        /** skinned positions / normals (3 per vertex), in driver model space (render space when posed with M/N) */
        public final float[] outPos, outNrm;
        /**
         * Vertices pre-formatted in Minecraft's NEW_ENTITY layout (36 B: pos 3f, colour, uv 2f, overlay, light,
         * normal 3b) so emitting can block-copy them. Static fields are written once; pos/normal/light per frame.
         */
        public static final int STRIDE = 36;
        final java.nio.ByteBuffer stageBuf;
        public final long stage;
        /** packed light for this frame's staging writes */
        public int light;

        public Attached(KMesh mesh, KMesh driver) {
            this.mesh = mesh;
            int n = mesh.nodeName.length;
            merge = new int[n];
            int wpn = driver.nodeIndex("wpn");
            for (int i = 0; i < n; i++) {
                int d = driver.nodeIndex(mesh.nodeName[i]);
                if (d < 0 && "weapon".equals(mesh.nodeName[i])) d = wpn;
                merge[i] = d;
            }
            world = new float[n * 16];
            skin = new float[mesh.jointNode.length * 16];
            nskin = new float[mesh.jointNode.length * 9];
            outPos = new float[mesh.vertexCount * 3];
            outNrm = new float[mesh.vertexCount * 3];
            // compact non-zero influences (most viewmodel vertices ride 1-2 bones)
            infStart = new int[mesh.vertexCount + 1];
            int total = 0;
            for (int v = 0; v < mesh.vertexCount; v++) {
                infStart[v] = total;
                for (int c = 0; c < 4; c++) if (mesh.weight[v * 4 + c] != 0f) total++;
            }
            infStart[mesh.vertexCount] = total;
            infJ = new int[total];
            infW = new float[total];
            for (int v = 0, t = 0; v < mesh.vertexCount; v++) {
                for (int c = 0; c < 4; c++) {
                    float w = mesh.weight[v * 4 + c];
                    if (w == 0f) continue;
                    infJ[t] = mesh.joint[v * 4 + c];
                    infW[t] = w;
                    t++;
                }
            }
            stageBuf = java.nio.ByteBuffer.allocateDirect(Math.max(1, mesh.vertexCount) * STRIDE).order(java.nio.ByteOrder.nativeOrder());
            stage = org.lwjgl.system.MemoryUtil.memAddress(stageBuf);
            for (int v = 0; v < mesh.vertexCount; v++) {
                long o = stage + (long) v * STRIDE;
                org.lwjgl.system.MemoryUtil.memPutInt(o + 12, 0xFFFFFFFF);
                org.lwjgl.system.MemoryUtil.memPutFloat(o + 16, mesh.uv[v * 2]);
                org.lwjgl.system.MemoryUtil.memPutFloat(o + 20, mesh.uv[v * 2 + 1]);
                org.lwjgl.system.MemoryUtil.memPutInt(o + 24, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                org.lwjgl.system.MemoryUtil.memPutByte(o + 35, (byte) 0);
            }
        }

        public void pose(KnifeRig rig) { pose(rig, null, null); }

        /** GPU path: only the joint (skin) matrices, rig space — the vertex shader does the vertices. */
        public float[] poseJoints(KnifeRig rig) {
            nodeWorlds(rig);
            KMesh m = mesh;
            for (int j = 0; j < m.jointNode.length; j++) KMath.mul(world, m.jointNode[j] * 16, m.ibm, j * 16, skin, j * 16);
            return skin;
        }

        /** Per-node world matrices for this mesh (bone-merged onto the driver, or its own clip-driven / rest locals). */
        private void nodeWorlds(KnifeRig rig) {
            KMesh m = mesh;
            for (int k = 0; k < m.order.length; k++) {
                int i = m.order[k];
                int d = merge[i];
                if (d >= 0) {
                    KMath.copy(rig.world, d * 16, world, i * 16);
                    continue;
                }
                float[] e = rig.extra(m.nodeName[i]);
                if (e != null) {
                    KMath.trs(e[0], e[1], e[2], e[3], e[4], e[5], e[6],
                            m.nodeS[i * 3], m.nodeS[i * 3 + 1], m.nodeS[i * 3 + 2], tmp, 0);
                } else {
                    KMath.trs(m.nodeT[i * 3], m.nodeT[i * 3 + 1], m.nodeT[i * 3 + 2],
                            m.nodeQ[i * 4], m.nodeQ[i * 4 + 1], m.nodeQ[i * 4 + 2], m.nodeQ[i * 4 + 3],
                            m.nodeS[i * 3], m.nodeS[i * 3 + 1], m.nodeS[i * 3 + 2], tmp, 0);
                }
                int p = m.nodeParent[i];
                if (p < 0) KMath.copy(tmp, 0, world, i * 16);
                else KMath.mul(world, p * 16, tmp, 0, world, i * 16);
            }
        }

        /** Lazily-built GPU copy of this mesh (null until the GPU path first uses it). */
        GpuSkin.Mesh gpu;

        /**
         * Skin into {@code outPos/outNrm}. With {@code M}/{@code N} (the final pose and normal matrices) folded into
         * the per-joint skin matrices, the output is already in render space with unit normals, so emitting is a
         * plain copy — each unique vertex is transformed once instead of once per triangle corner.
         */
        public void pose(KnifeRig rig, org.joml.Matrix4f M, org.joml.Matrix3f N) {
            KMesh m = mesh;
            for (int k = 0; k < m.order.length; k++) {
                int i = m.order[k];
                int d = merge[i];
                if (d >= 0) {
                    KMath.copy(rig.world, d * 16, world, i * 16);
                    continue;
                }
                float[] e = rig.extra(m.nodeName[i]);
                if (e != null) {
                    KMath.trs(e[0], e[1], e[2], e[3], e[4], e[5], e[6],
                            m.nodeS[i * 3], m.nodeS[i * 3 + 1], m.nodeS[i * 3 + 2], tmp, 0);
                } else {
                    KMath.trs(m.nodeT[i * 3], m.nodeT[i * 3 + 1], m.nodeT[i * 3 + 2],
                            m.nodeQ[i * 4], m.nodeQ[i * 4 + 1], m.nodeQ[i * 4 + 2], m.nodeQ[i * 4 + 3],
                            m.nodeS[i * 3], m.nodeS[i * 3 + 1], m.nodeS[i * 3 + 2], tmp, 0);
                }
                int p = m.nodeParent[i];
                if (p < 0) KMath.copy(tmp, 0, world, i * 16);
                else KMath.mul(world, p * 16, tmp, 0, world, i * 16);
            }
            for (int j = 0; j < m.jointNode.length; j++) {
                KMath.mul(world, m.jointNode[j] * 16, m.ibm, j * 16, skin, j * 16);
                if (M != null) {
                    int o = j * 16;
                    // normal part: N * upper3x3(skin), stored in nskin (column-major 3x3 in a 9-stride)
                    for (int c = 0; c < 3; c++) {
                        float a = skin[o + c * 4], b = skin[o + c * 4 + 1], d = skin[o + c * 4 + 2];
                        nskin[j * 9 + c * 3]     = N.m00() * a + N.m10() * b + N.m20() * d;
                        nskin[j * 9 + c * 3 + 1] = N.m01() * a + N.m11() * b + N.m21() * d;
                        nskin[j * 9 + c * 3 + 2] = N.m02() * a + N.m12() * b + N.m22() * d;
                    }
                    // position part: M * skin
                    for (int c = 0; c < 4; c++) {
                        float a = skin[o + c * 4], b = skin[o + c * 4 + 1], d = skin[o + c * 4 + 2], e = skin[o + c * 4 + 3];
                        tmp[c * 4]     = M.m00() * a + M.m10() * b + M.m20() * d + M.m30() * e;
                        tmp[c * 4 + 1] = M.m01() * a + M.m11() * b + M.m21() * d + M.m31() * e;
                        tmp[c * 4 + 2] = M.m02() * a + M.m12() * b + M.m22() * d + M.m32() * e;
                        tmp[c * 4 + 3] = M.m03() * a + M.m13() * b + M.m23() * d + M.m33() * e;
                    }
                    System.arraycopy(tmp, 0, skin, o, 16);
                } else {
                    int o = j * 16;
                    for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) nskin[j * 9 + c * 3 + r] = skin[o + c * 4 + r];
                }
            }
            stageOut = M != null;
            int n = m.vertexCount;
            if (n >= 2 * CHUNK) {                       // big meshes: skin chunks on the common pool
                int chunks = (n + CHUNK - 1) / CHUNK;
                java.util.stream.IntStream.range(0, chunks).parallel()
                        .forEach(c -> skinRange(c * CHUNK, Math.min(n, (c + 1) * CHUNK)));
            } else {
                skinRange(0, n);
            }
        }

        private static final int CHUNK = 2048;
        private boolean stageOut;

        /** Linear-blend skin vertices [v0, v1) using only each vertex's non-zero influences. */
        private void skinRange(int v0, int v1) {
            KMesh m = mesh;
            float[] P = m.pos, N0 = m.nrm, s = skin, ns = nskin, iw = infW;
            int[] ij = infJ, is = infStart;
            boolean st = stageOut;
            for (int v = v0; v < v1; v++) {
                float px = P[v * 3], py = P[v * 3 + 1], pz = P[v * 3 + 2];
                float nx = N0[v * 3], ny = N0[v * 3 + 1], nz = N0[v * 3 + 2];
                float ox = 0, oy = 0, oz = 0, onx = 0, ony = 0, onz = 0;
                for (int c = is[v], ce = is[v + 1]; c < ce; c++) {
                    float w = iw[c];
                    int jj = ij[c], o = jj * 16, q = jj * 9;
                    ox += w * (s[o] * px + s[o + 4] * py + s[o + 8] * pz + s[o + 12]);
                    oy += w * (s[o + 1] * px + s[o + 5] * py + s[o + 9] * pz + s[o + 13]);
                    oz += w * (s[o + 2] * px + s[o + 6] * py + s[o + 10] * pz + s[o + 14]);
                    onx += w * (ns[q] * nx + ns[q + 3] * ny + ns[q + 6] * nz);
                    ony += w * (ns[q + 1] * nx + ns[q + 4] * ny + ns[q + 7] * nz);
                    onz += w * (ns[q + 2] * nx + ns[q + 5] * ny + ns[q + 8] * nz);
                }
                outPos[v * 3] = ox; outPos[v * 3 + 1] = oy; outPos[v * 3 + 2] = oz;
                float l = (float) Math.sqrt(onx * onx + ony * ony + onz * onz);
                if (l < 1e-6f) l = 1f;
                float fx = onx / l, fy = ony / l, fz = onz / l;
                outNrm[v * 3] = fx; outNrm[v * 3 + 1] = fy; outNrm[v * 3 + 2] = fz;
                if (st) {
                    long o = stage + (long) v * STRIDE;
                    org.lwjgl.system.MemoryUtil.memPutFloat(o, ox);
                    org.lwjgl.system.MemoryUtil.memPutFloat(o + 4, oy);
                    org.lwjgl.system.MemoryUtil.memPutFloat(o + 8, oz);
                    org.lwjgl.system.MemoryUtil.memPutInt(o + 28, light);
                    org.lwjgl.system.MemoryUtil.memPutByte(o + 32, (byte) ((int) (fx * 127f) & 255));
                    org.lwjgl.system.MemoryUtil.memPutByte(o + 33, (byte) ((int) (fy * 127f) & 255));
                    org.lwjgl.system.MemoryUtil.memPutByte(o + 34, (byte) ((int) (fz * 127f) & 255));
                }
            }
        }
    }
}
