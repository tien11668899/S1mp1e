package dev.s1mp1e.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.s1mp1e.client.module.KnifeModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws the CS2 knife viewmodel in place of the main-hand sword (called from {@code submitArmWithItem} HEAD).
 *
 * <p>The rig poses in Source viewmodel space — X forward, Y left, Z up, meters, eye at the origin — exactly what
 * CS2 renders. That is mapped into Minecraft's first-person hand space (X right, Y up, -Z forward) by a pure
 * rotation, after CS2's viewmodel_offset (inches) and the FOV correction: Minecraft draws hands with a fixed 70°
 * vertical FOV, CS2 with {@code viewmodel_fov} measured horizontally at 4:3, so lateral coordinates are scaled by
 * {@code tan(35°)/tan(vfov/2)} — the same picture CS2 would draw.
 */
public final class KnifeRenderer {

    private KnifeRenderer() {}

    private static final KnifeAnimator ANIM = new KnifeAnimator();
    private static KnifeRig rig;
    private static KnifeRig.Attached arms, knife;
    private static String armsKey = "", knifeKey = "";
    private static int lastSlot = -1;
    // ---- S1MP1E_KNIFE_PROF=1: per-stage CPU cost, averaged and printed every 600 frames ----
    static final boolean PROF = System.getenv("S1MP1E_KNIFE_PROF") != null;
    static final long[] PT = new long[6];      // anim, poseArms, poseKnife, lookups, emit, total
    static int pFrames;
    static void prof(int i, long t0) { if (PROF) PT[i] += System.nanoTime() - t0; }
    private static boolean wasHolding;

    /** Source viewmodel axes -> Minecraft view axes: mc = (-y, z, -x). */
    private static final Quaternionf SRC_TO_MC = new Quaternionf().setFromNormalized(new Matrix3f(
            0f, 0f, -1f,    // column 0: source +X (forward) -> mc -Z
            -1f, 0f, 0f,    // column 1: source +Y (left)    -> mc -X
            0f, 1f, 0f));   // column 2: source +Z (up)      -> mc +Y

    public static KnifeAnimator animator() { return ANIM; }

    /**
     * Per client tick: when the knife isn't out, clear the equip edge so the next time it IS brought out counts
     * as a fresh equip (one draw). Called from the client tick so a render-thread stall never affects it.
     */
    public static void tickEquipState() {
        try {
            if (!holdingKnife(net.minecraft.client.Minecraft.getInstance().player)) {
                wasHolding = false;
                lastSlot = -1;
            }
        } catch (Throwable ignored) {}
    }

    public static boolean holdingKnife(AbstractClientPlayer p) {
        return p != null && KnifeModule.active() && KnifePack.available()
                && (p.getMainHandItem().is(ItemTags.SWORDS) || lockerOpen());
    }

    /** The knife locker previews the knife in hand even without a sword. */
    private static boolean lockerOpen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.gui != null && mc.gui.screen() instanceof dev.s1mp1e.client.gui.KnifeInventoryScreen;
    }

    /** @return true when the knife was drawn (the caller then cancels vanilla's main-hand render). */
    public static boolean render(AbstractClientPlayer player, ItemStack stack, PoseStack ps, SubmitNodeCollector snc, int light) {
        long tT = System.nanoTime();
        if (PROF && ++pFrames % 120 == 0) {
            System.out.printf("[S1mp1e] knife prof us/frame: anim %.0f  skinArms %.0f  skinKnife %.0f  lookups %.0f  emit %.0f  render %.0f%n",
                    PT[0] / 120e3, PT[1] / 120e3, PT[2] / 120e3, PT[3] / 120e3, PT[4] / 120e3, PT[5] / 120e3);
            java.util.Arrays.fill(PT, 0);
        }
        try { return render0(player, stack, ps, snc, light); } finally { prof(5, tT); }
    }

    private static boolean render0(AbstractClientPlayer player, ItemStack stack, PoseStack ps, SubmitNodeCollector snc, int light) {
        KnifeModule mod = KnifeModule.get();
        if (mod == null || !KnifePack.available()) return false;
        String knifeName = mod.knife.modeValue;
        String gloveName = mod.gloves.modeValue;
        if (!KnifePack.hasKnife(knifeName)) return false;

        try {
            if (rig == null) {
                KMesh driver = KnifePack.armsMesh("default");
                if (driver == null) return false;
                rig = new KnifeRig(driver);
            }
            if (!gloveName.equals(armsKey) || arms == null) {
                KMesh am = KnifePack.armsMesh(gloveName);
                if (am == null) return false;
                if (arms != null && arms.gpu != null) { arms.gpu.close(); arms.gpu = null; }
                arms = new KnifeRig.Attached(am, rig.driver);
                armsKey = gloveName;
            }
            if (!knifeName.equals(knifeKey) || knife == null) {
                KMesh km = KnifePack.knifeMesh(knifeName);
                if (km == null) return false;
                if (knife != null && knife.gpu != null) { knife.gpu.close(); knife.gpu = null; }
                knife = new KnifeRig.Attached(km, rig.driver);
                knifeKey = knifeName;
            }

            // One draw per equip. {@code wasHolding} is a holding EDGE reset every client tick by
            // {@link #tickEquipState} when the knife isn't out — frame-independent, so the skin-composite stall
            // (hundreds of ms on the render thread) can't be mistaken for a re-equip and double-fire the draw.
            // A knife-type change or a sword slot/stack hot-swap while still holding also fires exactly one draw.
            boolean changedKnife = ANIM.setKnife(knifeName);
            boolean justEquipped = !wasHolding;
            wasHolding = true;
            // Hot-swap = the selected hotbar SLOT changed. Deliberately NOT stack identity: a sword's stack
            // changes identity on durability loss or a server re-sync, which would double-fire the draw.
            int slot = player.getInventory().getSelectedSlot();
            boolean swapped = !justEquipped && slot != lastSlot;
            if (changedKnife || justEquipped || swapped) ANIM.draw();
            lastSlot = slot;

            boolean mcArms = "mc_arms".equals(mod.arms.modeValue);
            long tA = System.nanoTime();
            ANIM.update();
            ANIM.apply(rig);
            rig.solve();
            prof(0, tA);

            ps.pushPose();
            float vfov = (float) (2.0 * Math.atan(Math.tan(Math.toRadians(mod.fov.doubleValue) / 2.0) * 0.75));
            float k = (float) (Math.tan(Math.toRadians(35.0)) / Math.tan(vfov / 2.0));
            ps.scale(k, k, 1f);
            if (player.getMainArm() == HumanoidArm.LEFT) ps.scale(-1f, 1f, 1f);   // left-handed: mirror (cl_righthand 0)
            ps.mulPose(SRC_TO_MC);
            float in = 0.0254f;
            // CS2: offset_x = right, offset_y = forward, offset_z = up  -> source (+X fwd, +Y left, +Z up)
            ps.translate((float) mod.offY.doubleValue * in, (float) -mod.offX.doubleValue * in, (float) mod.offZ.doubleValue * in);
            // skin straight into render space (pose + normal matrices folded into the joint matrices)
            org.joml.Matrix4f PM = ps.last().pose();
            Matrix3f NM = ps.last().normal();
            boolean mirroredG = player.getMainArm() == HumanoidArm.LEFT;
            if (GpuSkin.available()) {
                // GPU path: joint matrices only; vertices are skinned in the vertex shader and drawn right now
                // (we're inside the hand phase: hand projection set, depth already cleared)
                long tG = System.nanoTime();
                KnifeSkins.Skin skinG = KnifeSkins.skin(knifeName, mod.skin.modeValue, (float) mod.wear.doubleValue);
                java.util.Map<String, Identifier> gloveG = mcArms ? null
                        : GloveSkins.skin(gloveName, mod.gloveSkin.modeValue, (float) mod.gloveWear.doubleValue);
                prof(3, tG);
                long tB2 = System.nanoTime();
                if (mcArms) McArms.submit(ps, snc, rig, player, light, mirroredG);
                else GpuSkin.draw(arms, arms.poseJoints(rig), PM, NM, light, mirroredG, part -> partTexture(arms.mesh, part, null, gloveG));
                prof(1, tB2);
                long tC2 = System.nanoTime();
                GpuSkin.draw(knife, knife.poseJoints(rig), PM, NM, light, mirroredG, part -> partTexture(knife.mesh, part, skinG, null));
                prof(2, tC2);
                ps.popPose();
                return true;
            }
            arms.light = light;
            knife.light = light;
            long tB = System.nanoTime();
            if (!mcArms) arms.pose(rig, PM, NM);
            prof(1, tB);
            long tC = System.nanoTime();
            knife.pose(rig, PM, NM);
            prof(2, tC);
            long tD = System.nanoTime();
            KnifeSkins.Skin skin = KnifeSkins.skin(knifeName, mod.skin.modeValue, (float) mod.wear.doubleValue);
            java.util.Map<String, Identifier> gloveTex = mcArms ? null
                    : GloveSkins.skin(gloveName, mod.gloveSkin.modeValue, (float) mod.gloveWear.doubleValue);
            prof(3, tD);
            boolean mirrored = player.getMainArm() == HumanoidArm.LEFT;
            if (mcArms) McArms.submit(ps, snc, rig, player, light, mirrored);
            else submit(ps, snc, arms, light, mirrored, null, gloveTex);
            submit(ps, snc, knife, light, mirrored, skin, null);
            ps.popPose();
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] CS2 knife render failed, falling back to vanilla: " + t);
            KnifeModule m = KnifeModule.get();
            if (m != null) m.enabled = false;
            return false;
        }
    }

    private static void submit(PoseStack ps, SubmitNodeCollector snc, KnifeRig.Attached a, int light, boolean mirrored,
                               KnifeSkins.Skin skin, java.util.Map<String, Identifier> byMaterial) {
        KMesh m = a.mesh;
        for (int p = 0; p < m.partStart.length; p++) {
            String file = m.partTexture[p];
            boolean painted = skin != null && file != null && stem(file).equals(skin.base());
            Identifier glove = byMaterial != null ? byMaterial.get(m.partMaterial[p]) : null;
            Identifier tex = glove != null ? glove : painted ? skin.texture() : KnifePack.texture(file);
            if (tex == null) continue;
            final int start = m.partStart[p], count = m.partCount[p];
            snc.submitCustomGeometry(ps, RenderTypes.entityCutout(tex),
                    (pose, vc) -> emit(pose, vc, a, start, count, light, mirrored));
        }
    }

    private static Identifier partTexture(KMesh m, int p, KnifeSkins.Skin skin, java.util.Map<String, Identifier> byMaterial) {
        String file = m.partTexture[p];
        Identifier glove = byMaterial != null ? byMaterial.get(m.partMaterial[p]) : null;
        if (glove != null) return glove;
        if (skin != null && file != null && stem(file).equals(skin.base())) return skin.texture();
        return KnifePack.texture(file);
    }

    private static String stem(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, KnifeRig.Attached a, int start, int count,
                             int light, boolean mirrored) {
        long tE = System.nanoTime();
        int[] idx = a.mesh.index;
        float[] P = a.outPos, Nn = a.outNrm, UV = a.mesh.uv;   // already in render space, unit normals
        int end = start - (count % 3) + count;
        int corners = (count / 3) * 4;                        // triangles through a QUADS pipeline: v0 v1 v2 v2
        if (corners > 1 && vc instanceof com.mojang.blaze3d.vertex.BufferBuilder bb
                && bb instanceof dev.s1mp1e.client.mixin.BufferBuilderAccessor acc
                && acc.s1mp1e$entityFormat() && acc.s1mp1e$vertexSize() == KnifeRig.Attached.STRIDE) {
            // first corner through the real fast path (keeps BufferBuilder's own checks/state), the rest as one block
            int f0 = idx[start];
            vert(vc, P, Nn, UV, f0, light);
            final int S = KnifeRig.Attached.STRIDE;
            final long dst = acc.s1mp1e$buffer().reserve((corners - 1) * S);
            final long src = a.stage;
            final int tris = corners / 4;
            // triangle t occupies stream corners 4t..4t+3 -> block offset (4t + c - 1) * S (corner 0 already written)
            final int TCH = 1024;
            java.util.function.IntConsumer fill = ch -> {
                int t0 = ch * TCH, t1 = Math.min(tris, t0 + TCH);
                for (int t = t0; t < t1; t++) {
                    int i = start + t * 3;
                    int i0 = idx[i], i1 = mirrored ? idx[i + 2] : idx[i + 1], i2 = mirrored ? idx[i + 1] : idx[i + 2];
                    long d = dst + (long) (4 * t - 1) * S;
                    if (t > 0) MemoryUtil.memCopy(src + (long) i0 * S, d, S);
                    MemoryUtil.memCopy(src + (long) i1 * S, d + S, S);
                    MemoryUtil.memCopy(src + (long) i2 * S, d + 2L * S, S);
                    MemoryUtil.memCopy(src + (long) i2 * S, d + 3L * S, S);
                }
            };
            int chunks = (tris + TCH - 1) / TCH;
            if (chunks > 1) java.util.stream.IntStream.range(0, chunks).parallel().forEach(fill);
            else fill.accept(0);
            long d = dst + (long) (corners - 1) * S;
            acc.s1mp1e$setVertices(acc.s1mp1e$vertices() + corners - 1);
            acc.s1mp1e$setVertexPointer(d - S);
            prof(4, tE);
            return;
        }
        for (int i = start; i + 2 < end; i += 3) {
            int i0 = idx[i], i1 = mirrored ? idx[i + 2] : idx[i + 1], i2 = mirrored ? idx[i + 1] : idx[i + 2];
            vert(vc, P, Nn, UV, i0, light);
            vert(vc, P, Nn, UV, i1, light);
            vert(vc, P, Nn, UV, i2, light);
            vert(vc, P, Nn, UV, i2, light);
        }
        prof(4, tE);
    }

    private static void vert(VertexConsumer vc, float[] P, float[] Nn, float[] UV, int v, int light) {
        int p = v * 3;
        vc.addVertex(P[p], P[p + 1], P[p + 2], 0xFFFFFFFF, UV[v * 2], UV[v * 2 + 1], OverlayTexture.NO_OVERLAY, light,
                Nn[p], Nn[p + 1], Nn[p + 2]);
    }
}
