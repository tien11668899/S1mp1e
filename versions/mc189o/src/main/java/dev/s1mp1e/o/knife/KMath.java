package dev.s1mp1e.o.knife;

/** Allocation-free 4x4 (column-major, m[col*4+row]) and quaternion helpers for the knife rig. */
final class KMath {

    private KMath() {}

    /** Shortest-path normalized lerp of quaternions a[ia..] and b[ib..] into out[io..]. */
    static void nlerp(float[] a, int ia, float[] b, int ib, float u, float[] out, int io) {
        float ax = a[ia], ay = a[ia + 1], az = a[ia + 2], aw = a[ia + 3];
        float bx = b[ib], by = b[ib + 1], bz = b[ib + 2], bw = b[ib + 3];
        if (ax * bx + ay * by + az * bz + aw * bw < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; }
        float x = ax + (bx - ax) * u, y = ay + (by - ay) * u, z = az + (bz - az) * u, w = aw + (bw - aw) * u;
        float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (n < 1e-8f) n = 1f;
        out[io] = x / n; out[io + 1] = y / n; out[io + 2] = z / n; out[io + 3] = w / n;
    }

    /** m[o..o+15] = T * R(q) * S */
    static void trs(float tx, float ty, float tz, float qx, float qy, float qz, float qw,
                    float sx, float sy, float sz, float[] m, int o) {
        float xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wx = qw * qx, wy = qw * qy, wz = qw * qz;
        m[o]      = (1 - 2 * (yy + zz)) * sx; m[o + 1]  = (2 * (xy + wz)) * sx;     m[o + 2]  = (2 * (xz - wy)) * sx;     m[o + 3]  = 0;
        m[o + 4]  = (2 * (xy - wz)) * sy;     m[o + 5]  = (1 - 2 * (xx + zz)) * sy; m[o + 6]  = (2 * (yz + wx)) * sy;     m[o + 7]  = 0;
        m[o + 8]  = (2 * (xz + wy)) * sz;     m[o + 9]  = (2 * (yz - wx)) * sz;     m[o + 10] = (1 - 2 * (xx + yy)) * sz; m[o + 11] = 0;
        m[o + 12] = tx; m[o + 13] = ty; m[o + 14] = tz; m[o + 15] = 1;
    }

    /** out[oo..] = a[oa..] * b[ob..]   (out must not alias a or b) */
    static void mul(float[] a, int oa, float[] b, int ob, float[] out, int oo) {
        for (int c = 0; c < 4; c++) {
            float b0 = b[ob + c * 4], b1 = b[ob + c * 4 + 1], b2 = b[ob + c * 4 + 2], b3 = b[ob + c * 4 + 3];
            for (int r = 0; r < 4; r++) {
                out[oo + c * 4 + r] = a[oa + r] * b0 + a[oa + 4 + r] * b1 + a[oa + 8 + r] * b2 + a[oa + 12 + r] * b3;
            }
        }
    }

    static void copy(float[] src, int os, float[] dst, int od) {
        System.arraycopy(src, os, dst, od, 16);
    }
}
