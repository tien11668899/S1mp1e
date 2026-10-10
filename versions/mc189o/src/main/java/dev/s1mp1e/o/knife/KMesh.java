package dev.s1mp1e.o.knife;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A skinned mesh from the CS2 knife pack ({@code .s1m}, written by cs2-knives/pipeline/export_pack.py).
 * Space: Source viewmodel model axes (X forward, Y left, Z up), meters.
 */
public final class KMesh {

    public final String[] nodeName;
    public final int[] nodeParent;
    /** rest local TRS per node: t(3) q(4, xyzw) s(3) */
    public final float[] nodeT, nodeQ, nodeS;
    /** node evaluation order: every parent before its children */
    public final int[] order;

    public final int[] jointNode;
    /** inverse bind matrices, 16 floats per joint, column-major (JOML layout) */
    public final float[] ibm;

    public final int vertexCount;
    public final float[] pos, nrm, uv, weight;
    public final int[] joint;
    public final int[] index;

    public final int[] partStart, partCount;
    public final String[] partTexture, partMaterial;

    private KMesh(ByteBuffer b) {
        expect(b, "S1KM");
        b.getInt(); // version
        int nn = b.getInt();
        nodeName = new String[nn];
        nodeParent = new int[nn];
        nodeT = new float[nn * 3]; nodeQ = new float[nn * 4]; nodeS = new float[nn * 3];
        for (int i = 0; i < nn; i++) {
            nodeName[i] = str(b);
            nodeParent[i] = b.getInt();
            for (int k = 0; k < 3; k++) nodeT[i * 3 + k] = b.getFloat();
            for (int k = 0; k < 4; k++) nodeQ[i * 4 + k] = b.getFloat();
            for (int k = 0; k < 3; k++) nodeS[i * 3 + k] = b.getFloat();
        }
        order = topo(nodeParent);

        int nj = b.getInt();
        jointNode = new int[nj];
        ibm = new float[nj * 16];
        for (int j = 0; j < nj; j++) {
            jointNode[j] = b.getInt();
            for (int k = 0; k < 16; k++) ibm[j * 16 + k] = b.getFloat();
        }

        int nv = b.getInt();
        vertexCount = nv;
        pos = new float[nv * 3]; nrm = new float[nv * 3]; uv = new float[nv * 2];
        joint = new int[nv * 4]; weight = new float[nv * 4];
        for (int v = 0; v < nv; v++) {
            for (int k = 0; k < 3; k++) pos[v * 3 + k] = b.getFloat();
            for (int k = 0; k < 3; k++) nrm[v * 3 + k] = b.getFloat();
            for (int k = 0; k < 2; k++) uv[v * 2 + k] = b.getFloat();
            for (int k = 0; k < 4; k++) joint[v * 4 + k] = b.getShort() & 0xFFFF;
            for (int k = 0; k < 4; k++) weight[v * 4 + k] = b.getFloat();
        }
        int ni = b.getInt();
        index = new int[ni];
        for (int i = 0; i < ni; i++) index[i] = b.getInt();

        int np = b.getInt();
        partStart = new int[np]; partCount = new int[np];
        partTexture = new String[np]; partMaterial = new String[np];
        for (int p = 0; p < np; p++) {
            partStart[p] = b.getInt();
            partCount[p] = b.getInt();
            partTexture[p] = str(b);
            partMaterial[p] = str(b);
        }
    }

    public static KMesh load(Path p) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        return new KMesh(b);
    }

    public int nodeIndex(String name) {
        for (int i = 0; i < nodeName.length; i++) if (nodeName[i].equals(name)) return i;
        return -1;
    }

    static void expect(ByteBuffer b, String magic) {
        byte[] m = new byte[4];
        b.get(m);
        if (!new String(m, StandardCharsets.US_ASCII).equals(magic)) throw new IllegalStateException("bad magic, want " + magic);
    }

    static String str(ByteBuffer b) {
        int n = b.getShort() & 0xFFFF;
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private static int[] topo(int[] parent) {
        int n = parent.length;
        int[] out = new int[n];
        boolean[] done = new boolean[n];
        int k = 0;
        for (int pass = 0; pass < n && k < n; pass++) {
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                int p = parent[i];
                if (p < 0 || done[p]) { out[k++] = i; done[i] = true; }
            }
        }
        return out;
    }
}
