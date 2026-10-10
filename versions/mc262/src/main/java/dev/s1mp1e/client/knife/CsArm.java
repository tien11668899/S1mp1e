package dev.s1mp1e.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.KnifeModule;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "CS arms everywhere": the CS2 gloved arm in every first-person context the knife viewmodel does not own (empty hand,
 * other items...). It is posed exactly like the knife viewmodel — its own rig sampled from the current knife's idle
 * clip (with CS2's breathing sway) and pushed through the same Source-viewmodel -> hand-space chain as
 * {@link KnifeRenderer} (FOV correction, left-hand mirror, SRC_TO_MC, viewmodel offset) — so it sits where CS2 puts
 * the hand. Only one side of the arms mesh is drawn per hand, like vanilla's single first-person arm. Vanilla's
 * player-skin arms are suppressed separately (CsArmMixin); held items still render.
 */
public final class CsArm {

    private CsArm() {}

    private static final Quaternionf SRC_TO_MC = new Quaternionf().setFromNormalized(new Matrix3f(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f));

    private static KnifeRig rig;
    private static KnifeRig.Attached arms;
    private static String armsKey = "";
    private static final Map<String, GpuSkin.Mesh> SIDE = new HashMap<>();
    private static final long T0 = System.nanoTime();

    /** Whether CS arms replace vanilla first-person arms right now. */
    public static boolean enabled() {
        KnifeModule mod = KnifeModule.get();
        return mod != null && KnifeModule.active() && mod.allArms.boolValue
                && !"mc_arms".equals(mod.arms.modeValue) && KnifePack.available() && GpuSkin.available();
    }

    // this frame's base hand pose (submitHandsWithItems HEAD) and the renderPlayerArm window
    private static final Matrix4f BASE = new Matrix4f();
    private static boolean inPlayerArm;
    private static float equip, attack, lastAttack;
    /** which fist throws the current punch (true = the off-hand fist) and which throws the next one */
    private static boolean punchOff, nextOff;
    /** the current swing is digging a block (hammer-fist motion instead of a straight punch) */
    private static boolean mining;

    /*
     * Boxing guard, in the base hand space (x right, y up, -z forward; right fist, the left one mirrors x):
     * guard fist position, punch end position, guard forearm direction (elbow -> fist), punch forearm direction, roll
     * of the fist about the forearm (deg). Tunable in game: the CS2 knife module's Guard / Punch / Dig settings.
     */
    /** defaults (also the fallback without the module): guard pos, punch pos, guard / punch forearm dirs, rolls */
    private static final float[] BOX_DEF = { 0.19f, -0.35f, -0.35f,   0.15f, -0.06f, -0.80f,   -0.04f, 0.06f, -1.0f,   -0.03f, 0.08f, -1.0f,   0f, 90f };
    /** upper-arm direction (elbow -> shoulder), right arm, base space: guard (hanging down-back) and punch (driven forward) */
    private static final float[] UG = { 0.30f, -0.85f, 0.45f }, UP = { 0.15f, -0.45f, 0.88f };
    /** DevShot sweep overrides (NaN = off): fixed fist roll (deg) and fixed punch extension. */
    public static float DBG_ROLL = Float.NaN, DBG_E = Float.NaN;

    /** The live boxing parameters (the CS2 knife module's settings; distances are positive forward). */
    private static float[] box() {
        float[] d = BOX_DEF.clone();
        KnifeModule m = KnifeModule.get();
        if (m == null) return d;
        d[0] = (float) m.guardX.doubleValue; d[1] = (float) m.guardY.doubleValue; d[2] = -(float) m.guardZ.doubleValue;
        d[3] = (float) m.punchX.doubleValue; d[4] = (float) m.punchY.doubleValue; d[5] = -(float) m.punchZ.doubleValue;
        d[12] = (float) m.guardRoll.doubleValue; d[13] = (float) m.punchRoll.doubleValue;
        return d;
    }

    private static float lungeAmount() { KnifeModule m = KnifeModule.get(); return m == null ? 0.15f : (float) m.shoulderDrive.doubleValue; }
    private static float punchPeak() { KnifeModule m = KnifeModule.get(); return m == null ? 0.3f : (float) m.punchSpeed.doubleValue; }
    private static float digLift() { KnifeModule m = KnifeModule.get(); return m == null ? 0.33f : (float) m.digLift.doubleValue; }
    private static float digReach() { KnifeModule m = KnifeModule.get(); return m == null ? 0.27f : (float) m.digReach.doubleValue; }

    public static void base(PoseStack ps) { BASE.set(ps.last().pose()); }

    public static void beginPlayerArm(float inverseArmHeight, float attackValue) {
        inPlayerArm = true;
        equip = inverseArmHeight;
        // a new swing (vanilla's attack value restarts): alternate fists, right first; left only with a free off hand
        if (attackValue > 0f && (lastAttack <= 0f || attackValue + 0.15f < lastAttack)) {
            mining = isMining();
            if (mining) {
                punchOff = false;                                   // digging: the main fist hammers, no alternation
            } else {
                punchOff = nextOff && offFistFree();
                nextOff = !punchOff;
            }
        }
        attack = attackValue;
        lastAttack = attackValue;
    }

    public static void endPlayerArm() { inPlayerArm = false; }

    private static boolean isMining() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        try { if (mc.gameMode != null && mc.gameMode.isDestroying()) return true; } catch (Throwable ignored) {}
        return mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
    }

    /*
     * Hammer fist for digging (right fist; the left mirrors x). Keyframes over vanilla's swing value: guard -> raised ->
     * strike forward-down onto the block with the pinky side of the vertical fist -> back to guard.
     * Each pose: x, y, z, forearm dir x, y, z, roll.
     */
    /** keyframe times: raised, strike (guard at 0 and 1) */
    private static final float MINE_UP = 0.30f, MINE_HIT = 0.55f;

    private static float smooth(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3f - 2f * t); }

    /** Pose (x, y, z, dx, dy, dz, roll) of a fist: guard, straight punch at extension e, or a hammer at swing a. */
    private static float[] pose(boolean active, float e, float a) {
        float[] B = box();
        float[] guard = { B[0], B[1], B[2], B[6], B[7], B[8], B[12],   UG[0], UG[1], UG[2] };
        if (!active) return guard;
        if (!mining) {
            float[] punch = { B[3], B[4], B[5], B[9], B[10], B[11], B.length > 13 ? B[13] : B[12],   UP[0], UP[1], UP[2] };
            return lerp(guard, punch, e);
        }
        if (a <= 0f) return guard;
        // hammer relative to the guard (follows it when the guard is adjusted); the fist keeps the guard's angle
        float lift = digLift(), reach = digReach();
        float[] up  = { B[0] - 0.01f, B[1] + lift,         B[2] - 0.09f,    -0.05f, 0.45f, -0.90f,   B[12],   0.30f, -0.90f, 0.30f };
        float[] hit = { B[0] - 0.05f, B[1] + lift * 0.45f, B[2] - reach,    -0.05f, -0.10f, -1.0f,   B[12],   0.20f, -0.55f, 0.80f };
        if (a <= MINE_UP) return lerp(guard, up, smooth(a / MINE_UP));
        if (a <= MINE_HIT) return lerp(up, hit, smooth((a - MINE_UP) / (MINE_HIT - MINE_UP)));
        return lerp(hit, guard, smooth((a - MINE_HIT) / (1f - MINE_HIT)));
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        int n = Math.min(a.length, b.length);
        float[] o = new float[n];
        for (int i = 0; i < n; i++) o[i] = a[i] + (b[i] - a[i]) * t;
        return o;
    }

    private static boolean offFistFree() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player != null && mc.player.getOffhandItem().isEmpty();
    }

    /** Punch extension 0..1 over vanilla's swing: a fast straight jab out, a slower pull back to guard. */
    private static float extension(float a) {
        if (a <= 0f) return 0f;
        float pk = Math.max(0.05f, Math.min(0.9f, punchPeak()));      // share of the swing spent driving out
        if (a < pk) return (float) Math.sin(a / pk * Math.PI / 2);
        float t = Math.min(1f, (a - pk) / (1f - pk));
        return (float) (0.5 + 0.5 * Math.cos(t * Math.PI));
    }

    /**
     * Called from AvatarRenderer.renderRightHand/LeftHand inside renderPlayerArm (vanilla's empty hand): draws both
     * fists in a boxing guard (the off fist only while the off hand is free) and throws straight punches, alternating
     * main / off on every swing.
     * @return true when drawn (caller skips the player-skin arm).
     */
    public static boolean renderPlayerArm(HumanoidArm arm, PoseStack ps, int light) {
        if (!inPlayerArm || !enabled()) return false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || KnifeRenderer.holdingKnife(mc.player)) return false;
        boolean mainMirror = arm == HumanoidArm.LEFT;
        float e = Float.isNaN(DBG_E) ? extension(attack) : DBG_E;
        float lunge = mining ? 0f : e;                              // the punching shoulder drives forward with the jab
        boolean ok = draw(BASE, light, mainMirror, pose(!punchOff, e, attack), punchOff ? 0f : lunge);
        if (offFistFree()) draw(BASE, light, !mainMirror, pose(punchOff, e, attack), punchOff ? lunge : 0f);
        return ok;
    }

    private static boolean draw(Matrix4f base, int light, boolean mirror, float[] P, float lunge) {
        try {
            KnifeModule mod = KnifeModule.get();
            String knife = mod.knife.modeValue, glove = mod.gloves.modeValue;

            if (rig == null) rig = new KnifeRig(KnifePack.armsMesh("default"));
            if (arms == null || !glove.equals(armsKey)) {
                KMesh am = KnifePack.armsMesh(glove);
                if (am == null) return false;
                arms = new KnifeRig.Attached(am, rig.driver);
                armsKey = glove;
                for (GpuSkin.Mesh g : SIDE.values()) try { g.close(); } catch (Throwable ignored) {}
                SIDE.clear();
            }

            // idle pose (looping, with CS2's breathing sway) from the current knife's clip set: the grip reads as a fist
            KClip idle = idleClip(knife);
            rig.reset();
            if (idle != null) {
                float d = Math.max(1e-3f, idle.duration());
                float t = ((System.nanoTime() - T0) / 1e9f) % d;
                float[] s = new float[7];
                for (int b = 0; b < idle.bone.length; b++) {
                    idle.sample(b, t, s);
                    int node = rig.driver.nodeIndex(idle.bone[b]);
                    if (node >= 0) rig.setLocal(node, s); else rig.setExtra(idle.bone[b], s);
                }
            }
            rig.solve();

            // the knife's own Source-viewmodel -> hand-space chain (from identity; the base pose goes in front of it)
            PoseStack ps = new PoseStack();
            float vfov = (float) (2.0 * Math.atan(Math.tan(Math.toRadians(mod.fov.doubleValue) / 2.0) * 0.75));
            float k = (float) (Math.tan(Math.toRadians(35.0)) / Math.tan(vfov / 2.0));
            ps.scale(k, k, 1f);
            if (mirror) ps.scale(-1f, 1f, 1f);
            ps.mulPose(SRC_TO_MC);
            float in = 0.0254f;
            ps.translate((float) mod.offY.doubleValue * in, (float) -mod.offX.doubleValue * in, (float) mod.offZ.doubleValue * in);
            Matrix4f chain = new Matrix4f(ps.last().pose());

            // two-bone IK in rig space: shoulder (armUpperShoulder_R) stays where the idle clip has it, the wrist goes to
            // the pose target, the elbow bends outward-down; the fist roll is a forearm pronation about its own axis
            float sx = mirror ? -1f : 1f;
            if (!ik(chain, P, sx, lunge)) return false;
            Matrix4f PM = new Matrix4f(base).mul(chain);
            Matrix3f NM = new Matrix3f(PM).invert().transpose();

            // one arm per fist: the rig's right arm (mirrored for a left fist, like the knife)
            GpuSkin.Mesh g = SIDE.get("R");
            if (g == null) { g = new GpuSkin.Mesh(arms.mesh, 'R'); SIDE.put("R", g); }
            Map<String, Identifier> gloveTex = GloveSkins.skin(glove, mod.gloveSkin.modeValue, (float) mod.gloveWear.doubleValue);
            final KMesh mm = arms.mesh;
            GpuSkin.draw(g, mm, arms.poseJoints(rig), PM, NM, light, mirror, part -> tex(mm, part, gloveTex));
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] CS arm render failed: " + t);
            return false;
        }
    }


    private static int nSh = -2, nEl, nHand, nSt;

    private static Matrix4f world(int i) { return new Matrix4f().set(rig.world, i * 16); }

    private static void setLocalFrom(int node, Matrix4f parentWorld, Matrix4f desiredWorld) {
        Matrix4f l = new Matrix4f(parentWorld).invert().mul(desiredWorld);
        org.joml.Vector3f t = l.getTranslation(new org.joml.Vector3f());
        Quaternionf q = l.getNormalizedRotation(new Quaternionf());
        rig.setLocal(node, new float[] { t.x, t.y, t.z, q.x, q.y, q.z, q.w });
    }

    /** Rotation about point {@code c}: T(c) * R * T(-c). */
    private static Matrix4f about(org.joml.Vector3f c, Quaternionf r) {
        return new Matrix4f().translation(c).rotate(r).translate(-c.x, -c.y, -c.z);
    }

    /**
     * Poses the solved idle rig's right arm so the wrist reaches pose P (base space, right-side coords mirrored by sx)
     * with an outward-down elbow and the fist rolled to P's roll. Re-solves the rig.
     */
    private static boolean ik(Matrix4f chain, float[] P, float sx, float lunge) {
        if (nSh == -2) {
            nSh = rig.driver.nodeIndex("armUpperShoulder_R"); nEl = rig.driver.nodeIndex("arm_lower_R");
            nHand = rig.driver.nodeIndex("hand_R"); nSt = rig.driver.nodeIndex("armUpperStraighten_0_R");
        }
        if (nSh < 0 || nEl < 0 || nHand < 0) return false;
        Matrix4f inv = new Matrix4f(chain).invert();
        Matrix4f Wsh = world(nSh), Wel = world(nEl), Wh = world(nHand), Wst = nSt >= 0 ? world(nSt) : null;
        org.joml.Vector3f S = Wsh.getTranslation(new org.joml.Vector3f());
        org.joml.Vector3f E0 = Wel.getTranslation(new org.joml.Vector3f());
        org.joml.Vector3f W0 = Wh.getTranslation(new org.joml.Vector3f());
        float L1 = S.distance(E0), L2 = E0.distance(W0);

        // the shoulder is anchored where the GUARD puts it: build the guard arm back from its wrist (forearm along the
        // guard direction, upper arm along the guard upper-arm direction). Every pose is then a two-bone IK from that
        // shoulder, with the guard's elbow as the pole, so a punch straightens the arm instead of dragging the shoulder
        // into view.
        float[] B = box();
        float drop = 0.6f * equip * 0.3f;
        org.joml.Vector3f Tg = inv.transformPosition(new org.joml.Vector3f(B[0] * sx, B[1] - drop, B[2]));
        org.joml.Vector3f fwdG = inv.transformDirection(new org.joml.Vector3f(B[6] * sx, B[7], B[8])).normalize();
        org.joml.Vector3f upG = inv.transformDirection(new org.joml.Vector3f(UG[0] * sx, UG[1], UG[2])).normalize();
        org.joml.Vector3f Eg = new org.joml.Vector3f(Tg).sub(new org.joml.Vector3f(fwdG).mul(L2));
        org.joml.Vector3f S2 = new org.joml.Vector3f(Eg).add(new org.joml.Vector3f(upG).mul(L1));
        // shoulder drive: a straight punch turns the shoulder into it, buying reach beyond the arm's own length
        if (lunge > 0f) S2.add(inv.transformDirection(new org.joml.Vector3f(-0.03f * sx, 0.01f, -lungeAmount()).mul(lunge)));

        org.joml.Vector3f T = inv.transformPosition(new org.joml.Vector3f(P[0] * sx, P[1] - drop, P[2]));
        org.joml.Vector3f st = new org.joml.Vector3f(T).sub(S2);
        float d = Math.max(Math.abs(L1 - L2) + 1e-3f, Math.min(L1 + L2 - 1e-3f, st.length()));
        org.joml.Vector3f u = st.normalize(new org.joml.Vector3f());
        T.set(S2).add(new org.joml.Vector3f(u).mul(d));                // clamp the reach (straight arm at most)
        float aLen = (L1 * L1 - L2 * L2 + d * d) / (2f * d);
        float h = (float) Math.sqrt(Math.max(0f, L1 * L1 - aLen * aLen));
        org.joml.Vector3f pole = new org.joml.Vector3f(Eg).sub(S2);
        org.joml.Vector3f v = pole.sub(new org.joml.Vector3f(u).mul(pole.dot(u)));
        if (v.lengthSquared() < 1e-8f) v = inv.transformDirection(new org.joml.Vector3f(0.5f * sx, -1f, 0f));
        v.normalize();
        org.joml.Vector3f E = new org.joml.Vector3f(S2).add(new org.joml.Vector3f(u).mul(aLen)).add(new org.joml.Vector3f(v).mul(h));

        // 1) shoulder: swing the upper arm onto the elbow
        Quaternionf q1 = new Quaternionf().rotationTo(new org.joml.Vector3f(E0).sub(S).normalize(), new org.joml.Vector3f(E).sub(S2).normalize());
        Matrix4f moveSh = new Matrix4f().translation(S2).rotate(q1).translate(-S.x, -S.y, -S.z);
        Matrix4f Wsh2 = new Matrix4f(moveSh).mul(Wsh);
        setLocalFrom(nSh, world(rig.driver.nodeParent[nSh]), Wsh2);
        rig.solve();

        // 2) elbow: swing the forearm onto the wrist target
        Matrix4f Wel1 = world(nEl);
        org.joml.Vector3f E1 = Wel1.getTranslation(new org.joml.Vector3f());
        org.joml.Vector3f W1 = world(nHand).getTranslation(new org.joml.Vector3f());
        org.joml.Vector3f dF = new org.joml.Vector3f(T).sub(E1).normalize();
        Quaternionf q2 = new Quaternionf().rotationTo(new org.joml.Vector3f(W1).sub(E1).normalize(), dF);
        Matrix4f Wel2 = about(E1, q2).mul(Wel1);

        // 3) fist roll: pronate the forearm about its own axis so the hand's facing (its Y axis) hits the target facing
        Matrix4f Wh2 = new Matrix4f(Wel2).mul(new Matrix4f(Wel1).invert()).mul(world(nHand));
        org.joml.Vector3f f = Wh2.transformDirection(new org.joml.Vector3f(0f, 1f, 0f));
        f.sub(new org.joml.Vector3f(dF).mul(f.dot(dF)));
        org.joml.Vector3f dB = chain.transformDirection(new org.joml.Vector3f(dF)).normalize();   // forearm in base space
        org.joml.Vector3f up = new org.joml.Vector3f(0f, 1f, 0f);
        if (Math.abs(up.dot(dB)) > 0.95f) up.set(0f, 0f, 1f);
        org.joml.Vector3f fB = up.sub(new org.joml.Vector3f(dB).mul(up.dot(dB))).normalize();
        float rollDeg = Float.isNaN(DBG_ROLL) ? P[6] : DBG_ROLL;
        fB.rotateAxis((float) Math.toRadians(rollDeg * sx), dB.x, dB.y, dB.z);
        org.joml.Vector3f fT = inv.transformDirection(fB);
        fT.sub(new org.joml.Vector3f(dF).mul(fT.dot(dF)));
        if (f.lengthSquared() > 1e-8f && fT.lengthSquared() > 1e-8f) {
            f.normalize(); fT.normalize();
            float ang = (float) Math.atan2(new org.joml.Vector3f(f).cross(fT).dot(dF), f.dot(fT));
            Wel2 = about(E1, new Quaternionf().rotationAxis(ang, dF.x, dF.y, dF.z)).mul(Wel2);
        }
        setLocalFrom(nEl, world(nSh), Wel2);

        // the upper-arm skin helper hangs off the elbow in this rig: keep it with the shoulder, not the forearm
        if (Wst != null) setLocalFrom(nSt, Wel2, new Matrix4f(moveSh).mul(Wst));
        rig.solve();
        return true;
    }

    private static final Map<String, String> IDLE = new HashMap<>();

    private static KClip idleClip(String knife) {
        String name = IDLE.get(knife);
        if (name == null) {
            name = "";
            try {
                List<String> names = KnifePack.clipNames(knife);
                for (String n : names) if (n.startsWith("idle")) { name = n; break; }
            } catch (Throwable ignored) {}
            IDLE.put(knife, name);
        }
        return name.isEmpty() ? null : KnifePack.clip(knife, name);
    }

    private static Identifier tex(KMesh m, int p, Map<String, Identifier> byMaterial) {
        Identifier glove = byMaterial != null ? byMaterial.get(m.partMaterial[p]) : null;
        if (glove != null) return glove;
        return KnifePack.texture(m.partTexture[p]);
    }
}
