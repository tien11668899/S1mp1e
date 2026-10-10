package dev.s1mp1e.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * "Minecraft arms" option: instead of CS2's arms/gloves, the player's own blocky skin arms (base + sleeve layer,
 * wide 4x12x4 or slim 3x12x4) ride the CS2 animation. Each arm is one rigid box laid along the CS2 forearm
 * (elbow -> wrist, plus a hand's length past the wrist so the box end is the fist), rolled with the CS2 hand bone —
 * so the knife still twirls exactly as in CS2, held by a vanilla arm.
 */
final class McArms {

    private McArms() {}

    private static final float HAND_EXTRA = 0.085f;   // past the wrist: CS2 hand length (m)

    static void submit(PoseStack ps, SubmitNodeCollector snc, KnifeRig rig, AbstractClientPlayer player,
                       int light, boolean mirrored) {
        Identifier skin;
        boolean slim;
        try {
            skin = player.getSkin().body().texturePath();
            slim = player.getSkin().model() == PlayerModelType.SLIM;
        } catch (Throwable t) {
            return;
        }
        // Only the knife hand, like vanilla first person (CS2's idle shows a relaxed off-hand; as a skin box it
        // just reads as a brick in the corner). Skin offsets (64x64): right arm base 40,16, sleeve 40,32.
        float[] box = frame(rig, "arm_lower_R", "hand_R");
        if (box == null) return;
        int w = slim ? 3 : 4;
        emitArm(ps, snc, skin, box, w, 40, 16, 0f, light, mirrored, false);
        emitArm(ps, snc, skin, box, w, 40, 32, 0.25f, light, mirrored, true);
    }

    /**
     * Arm frame in rig space: [origin(3), L(3) long axis elbow->hand, F(3) front, O(3) outer side, len].
     * The roll comes from the hand bone: F = the hand's Y axis made perpendicular to the forearm.
     */
    private static float[] frame(KnifeRig rig, String elbow, String hand) {
        int e = rig.driver.nodeIndex(elbow), h = rig.driver.nodeIndex(hand);
        if (e < 0 || h < 0) return null;
        float[] W = rig.world;
        float ex = W[e * 16 + 12], ey = W[e * 16 + 13], ez = W[e * 16 + 14];
        float hx = W[h * 16 + 12], hy = W[h * 16 + 13], hz = W[h * 16 + 14];
        float lx = hx - ex, ly = hy - ey, lz = hz - ez;
        float fore = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (fore < 1e-4f) return null;
        lx /= fore; ly /= fore; lz /= fore;
        // hand Y axis, orthogonalised against L
        float yx = W[h * 16 + 4], yy = W[h * 16 + 5], yz = W[h * 16 + 6];
        float d = yx * lx + yy * ly + yz * lz;
        float fx = yx - d * lx, fy = yy - d * ly, fz = yz - d * lz;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-4f) return null;
        fx /= fl; fy /= fl; fz /= fl;
        // O = L x F
        float ox = ly * fz - lz * fy, oy = lz * fx - lx * fz, oz = lx * fy - ly * fx;
        return new float[] { ex, ey, ez, lx, ly, lz, fx, fy, fz, ox, oy, oz, fore + HAND_EXTRA };
    }

    /**
     * One MC arm cube (w x 12 x 4 px) on frame {@code f}: v runs shoulder (top) -> hand (bottom) along L, the
     * w-wide faces are front/back (F), the 4-deep faces outer/inner (O). {@code inflate} in px (sleeve = 0.25).
     */
    private static void emitArm(PoseStack ps, SubmitNodeCollector snc, Identifier skin, float[] f, int w,
                                int u0, int v0, float inflate, int light, boolean mirrored, boolean sleeve) {
        final float len = f[12];
        final float px = len / 12f;                       // one skin pixel in metres along the arm
        final float pw = px * 0.8f;                       // a touch slimmer across, so the knife still reads
        final float hw = (w / 2f + inflate) * pw, hd = (2f + inflate) * pw;
        final float t0 = -inflate * px, t1 = len + inflate * px;
        final int dd = 4;
        snc.submitCustomGeometry(ps, sleeve ? RenderTypes.entityTranslucent(skin) : RenderTypes.entityCutout(skin),
                (pose, vc) -> {
                    Matrix4f M = pose.pose();
                    Matrix3f N = pose.normal();
                    // side faces: v from v0+4 (shoulder) to v0+16 (hand)
                    float vt = v0 + dd, vb = v0 + dd + 12;
                    // front (+F): u u0+4 .. u0+4+w   across O (outer -> inner)
                    face(vc, M, N, f, +1, 0, hd, hw, t0, t1, u0 + dd, u0 + dd + w, vt, vb, light, mirrored);
                    // back (-F): u u0+8+w .. u0+8+2w
                    face(vc, M, N, f, -1, 0, hd, hw, t0, t1, u0 + 2 * dd + w, u0 + 2 * dd + 2 * w, vt, vb, light, mirrored);
                    // outer (+O): u u0 .. u0+4   across F
                    face(vc, M, N, f, +1, 1, hw, hd, t0, t1, u0, u0 + dd, vt, vb, light, mirrored);
                    // inner (-O): u u0+4+w .. u0+8+w
                    face(vc, M, N, f, -1, 1, hw, hd, t0, t1, u0 + dd + w, u0 + 2 * dd + w, vt, vb, light, mirrored);
                    // hand end (bottom): u u0+4+w .. u0+4+2w, v v0 .. v0+4
                    cap(vc, M, N, f, t1, hw, hd, u0 + dd + w, u0 + dd + 2 * w, v0, v0 + dd, light, mirrored, true);
                    // shoulder end (top): u u0+4 .. u0+4+w
                    cap(vc, M, N, f, t0, hw, hd, u0 + dd, u0 + dd + w, v0, v0 + dd, light, mirrored, false);
                });
    }

    /**
     * A long side face. {@code axis} 0 = normal along F (spans O), 1 = normal along O (spans F); {@code s} the
     * sign of the normal; {@code off} distance of the face from the axis; {@code half} its half-width.
     */
    private static void face(VertexConsumer vc, Matrix4f M, Matrix3f N, float[] f, int s, int axis, float off,
                             float half, float t0, float t1, float ua, float ub, float vt, float vb, int light,
                             boolean mirrored) {
        int nI = axis == 0 ? 6 : 9, aI = axis == 0 ? 9 : 6;   // normal axis / across axis
        float nx = f[nI] * s, ny = f[nI + 1] * s, nz = f[nI + 2] * s;
        float ax = f[aI], ay = f[aI + 1], az = f[aI + 2];
        // across direction chosen so (across x L) points along the normal -> counter-clockwise from outside
        float cx = ay * f[5] - az * f[4], cy = az * f[3] - ax * f[5], cz = ax * f[4] - ay * f[3];
        if (cx * nx + cy * ny + cz * nz < 0) { ax = -ax; ay = -ay; az = -az; }
        float bx = f[0] + nx * off, by = f[1] + ny * off, bz = f[2] + nz * off;
        float[][] p = new float[4][];
        p[0] = pt(bx, by, bz, ax, ay, az, -half, f, t0);
        p[1] = pt(bx, by, bz, ax, ay, az, +half, f, t0);
        p[2] = pt(bx, by, bz, ax, ay, az, +half, f, t1);
        p[3] = pt(bx, by, bz, ax, ay, az, -half, f, t1);
        float[][] uv = { { ua, vt }, { ub, vt }, { ub, vb }, { ua, vb } };
        quad(vc, M, N, p, uv, nx, ny, nz, light, mirrored);
    }

    private static void cap(VertexConsumer vc, Matrix4f M, Matrix3f N, float[] f, float t, float hw, float hd,
                            float ua, float ub, float va, float vb, int light, boolean mirrored, boolean end) {
        float s = end ? 1 : -1;
        float nx = f[3] * s, ny = f[4] * s, nz = f[5] * s;
        float cx = f[0] + f[3] * t, cy = f[1] + f[4] * t, cz = f[2] + f[5] * t;
        // in-plane axes O (across, w) and F (depth); orient so O x F = normal
        float ox = f[9], oy = f[10], oz = f[11], fx = f[6], fy = f[7], fz = f[8];
        float crx = oy * fz - oz * fy, cry = oz * fx - ox * fz, crz = ox * fy - oy * fx;
        if (crx * nx + cry * ny + crz * nz < 0) { fx = -fx; fy = -fy; fz = -fz; }
        float[][] p = {
            { cx - ox * hw - fx * hd, cy - oy * hw - fy * hd, cz - oz * hw - fz * hd },
            { cx + ox * hw - fx * hd, cy + oy * hw - fy * hd, cz + oz * hw - fz * hd },
            { cx + ox * hw + fx * hd, cy + oy * hw + fy * hd, cz + oz * hw + fz * hd },
            { cx - ox * hw + fx * hd, cy - oy * hw + fy * hd, cz - oz * hw + fz * hd },
        };
        float[][] uv = { { ua, va }, { ub, va }, { ub, vb }, { ua, vb } };
        quad(vc, M, N, p, uv, nx, ny, nz, light, mirrored);
    }

    private static float[] pt(float bx, float by, float bz, float ax, float ay, float az, float a, float[] f, float t) {
        return new float[] { bx + ax * a + f[3] * t, by + ay * a + f[4] * t, bz + az * a + f[5] * t };
    }

    private static void quad(VertexConsumer vc, Matrix4f M, Matrix3f N, float[][] p, float[][] uv,
                             float nx, float ny, float nz, int light, boolean mirrored) {
        float qx = N.m00() * nx + N.m10() * ny + N.m20() * nz;
        float qy = N.m01() * nx + N.m11() * ny + N.m21() * nz;
        float qz = N.m02() * nx + N.m12() * ny + N.m22() * nz;
        float l = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
        if (l > 1e-6f) { qx /= l; qy /= l; qz /= l; }
        int[] order = mirrored ? new int[] { 0, 3, 2, 1 } : new int[] { 0, 1, 2, 3 };
        for (int k : order) {
            float x = p[k][0], y = p[k][1], z = p[k][2];
            float tx = M.m00() * x + M.m10() * y + M.m20() * z + M.m30();
            float ty = M.m01() * x + M.m11() * y + M.m21() * z + M.m31();
            float tz = M.m02() * x + M.m12() * y + M.m22() * z + M.m32();
            vc.addVertex(tx, ty, tz, 0xFFFFFFFF, uv[k][0] / 64f, uv[k][1] / 64f, OverlayTexture.NO_OVERLAY, light, qx, qy, qz);
        }
    }
}
