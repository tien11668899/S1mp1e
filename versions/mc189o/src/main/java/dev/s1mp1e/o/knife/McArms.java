package dev.s1mp1e.o.knife;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.resource.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * "Minecraft arms" option (1.8.9): instead of CS2's arms/gloves, the player's own blocky skin arm (base + sleeve)
 * rides the CS2 animation. The knife hand's forearm is one rigid box laid along the CS2 forearm (elbow -> wrist, plus
 * a hand's length past the wrist), rolled with the CS2 hand bone — so the knife still twirls exactly as in CS2, held
 * by a vanilla arm. Drawn fixed-function in eye space under an identity modelview (vertices pre-multiplied by the
 * final pose matrix {@code PM}).
 */
final class McArms {

    private McArms() {}

    private static final float HAND_EXTRA = 0.085f;   // past the wrist: CS2 hand length (m)

    static void draw(Matrix4f PM, Matrix3f NM, KnifeRig rig, ClientPlayerEntity player) {
        Identifier skin;
        boolean slim;
        try {
            skin = player.getSkinTextureLocation();
            slim = "slim".equals(player.getModelType());
        } catch (Throwable t) {
            return;
        }
        if (skin == null) return;
        float[] box = frame(rig, "arm_lower_R", "hand_R");
        if (box == null) return;
        int w = slim ? 3 : 4;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GlStateManager.pushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Minecraft.getInstance().getTextureManager().bind(skin);

        // base layer (opaque, alpha test), then sleeve (translucent, slightly inflated)
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        emitArm(PM, NM, box, w, 40, 16, 0f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        emitArm(PM, NM, box, w, 40, 32, 0.25f);
        GL11.glDisable(GL11.GL_BLEND);

        GlStateManager.popMatrix();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
    }

    /** Arm frame in rig space: [origin(3), L(3) elbow->hand, F(3) front, O(3) outer, len]. */
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
        float yx = W[h * 16 + 4], yy = W[h * 16 + 5], yz = W[h * 16 + 6];
        float dd = yx * lx + yy * ly + yz * lz;
        float fx = yx - dd * lx, fy = yy - dd * ly, fz = yz - dd * lz;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-4f) return null;
        fx /= fl; fy /= fl; fz /= fl;
        float ox = ly * fz - lz * fy, oy = lz * fx - lx * fz, oz = lx * fy - ly * fx;
        return new float[] { ex, ey, ez, lx, ly, lz, fx, fy, fz, ox, oy, oz, fore + HAND_EXTRA };
    }

    private static void emitArm(Matrix4f PM, Matrix3f NM, float[] f, int w, int u0, int v0, float inflate) {
        final float len = f[12];
        final float px = len / 12f;
        final float pw = px * 0.8f;
        final float hw = (w / 2f + inflate) * pw, hd = (2f + inflate) * pw;
        final float t0 = -inflate * px, t1 = len + inflate * px;
        final int dd = 4;
        GL11.glBegin(GL11.GL_QUADS);
        float vt = v0 + dd, vb = v0 + dd + 12;
        face(PM, NM, f, +1, 0, hd, hw, t0, t1, u0 + dd, u0 + dd + w, vt, vb);
        face(PM, NM, f, -1, 0, hd, hw, t0, t1, u0 + 2 * dd + w, u0 + 2 * dd + 2 * w, vt, vb);
        face(PM, NM, f, +1, 1, hw, hd, t0, t1, u0, u0 + dd, vt, vb);
        face(PM, NM, f, -1, 1, hw, hd, t0, t1, u0 + dd + w, u0 + 2 * dd + w, vt, vb);
        cap(PM, NM, f, t1, hw, hd, u0 + dd + w, u0 + dd + 2 * w, v0, v0 + dd, true);
        cap(PM, NM, f, t0, hw, hd, u0 + dd, u0 + dd + w, v0, v0 + dd, false);
        GL11.glEnd();
    }

    private static void face(Matrix4f PM, Matrix3f NM, float[] f, int s, int axis, float off, float half,
                             float t0, float t1, float ua, float ub, float vt, float vb) {
        int nI = axis == 0 ? 6 : 9, aI = axis == 0 ? 9 : 6;
        float nx = f[nI] * s, ny = f[nI + 1] * s, nz = f[nI + 2] * s;
        float ax = f[aI], ay = f[aI + 1], az = f[aI + 2];
        float cx = ay * f[5] - az * f[4], cy = az * f[3] - ax * f[5], cz = ax * f[4] - ay * f[3];
        if (cx * nx + cy * ny + cz * nz < 0) { ax = -ax; ay = -ay; az = -az; }
        float bx = f[0] + nx * off, by = f[1] + ny * off, bz = f[2] + nz * off;
        vtx(PM, NM, pt(bx, by, bz, ax, ay, az, -half, f, t0), nx, ny, nz, ua, vt);
        vtx(PM, NM, pt(bx, by, bz, ax, ay, az, +half, f, t0), nx, ny, nz, ub, vt);
        vtx(PM, NM, pt(bx, by, bz, ax, ay, az, +half, f, t1), nx, ny, nz, ub, vb);
        vtx(PM, NM, pt(bx, by, bz, ax, ay, az, -half, f, t1), nx, ny, nz, ua, vb);
    }

    private static void cap(Matrix4f PM, Matrix3f NM, float[] f, float t, float hw, float hd,
                            float ua, float ub, float va, float vb, boolean end) {
        float s = end ? 1 : -1;
        float nx = f[3] * s, ny = f[4] * s, nz = f[5] * s;
        float cx = f[0] + f[3] * t, cy = f[1] + f[4] * t, cz = f[2] + f[5] * t;
        float ox = f[9], oy = f[10], oz = f[11], fx = f[6], fy = f[7], fz = f[8];
        float crx = oy * fz - oz * fy, cry = oz * fx - ox * fz, crz = ox * fy - oy * fx;
        if (crx * nx + cry * ny + crz * nz < 0) { fx = -fx; fy = -fy; fz = -fz; }
        vtx(PM, NM, new float[] { cx - ox * hw - fx * hd, cy - oy * hw - fy * hd, cz - oz * hw - fz * hd }, nx, ny, nz, ua, va);
        vtx(PM, NM, new float[] { cx + ox * hw - fx * hd, cy + oy * hw - fy * hd, cz + oz * hw - fz * hd }, nx, ny, nz, ub, va);
        vtx(PM, NM, new float[] { cx + ox * hw + fx * hd, cy + oy * hw + fy * hd, cz + oz * hw + fz * hd }, nx, ny, nz, ub, vb);
        vtx(PM, NM, new float[] { cx - ox * hw + fx * hd, cy - oy * hw + fy * hd, cz - oz * hw + fz * hd }, nx, ny, nz, ua, vb);
    }

    private static float[] pt(float bx, float by, float bz, float ax, float ay, float az, float a, float[] f, float t) {
        return new float[] { bx + ax * a + f[3] * t, by + ay * a + f[4] * t, bz + az * a + f[5] * t };
    }

    private static void vtx(Matrix4f PM, Matrix3f NM, float[] p, float nx, float ny, float nz, float u, float v) {
        float x = p[0], y = p[1], z = p[2];
        float tx = PM.m00() * x + PM.m10() * y + PM.m20() * z + PM.m30();
        float ty = PM.m01() * x + PM.m11() * y + PM.m21() * z + PM.m31();
        float tz = PM.m02() * x + PM.m12() * y + PM.m22() * z + PM.m32();
        float qx = NM.m00() * nx + NM.m10() * ny + NM.m20() * nz;
        float qy = NM.m01() * nx + NM.m11() * ny + NM.m21() * nz;
        float qz = NM.m02() * nx + NM.m12() * ny + NM.m22() * nz;
        float l = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
        if (l > 1e-6f) { qx /= l; qy /= l; qz /= l; }
        GL11.glNormal3f(qx, qy, qz);
        GL11.glTexCoord2f(u / 64f, v / 64f);
        GL11.glVertex3f(tx, ty, tz);
    }
}
