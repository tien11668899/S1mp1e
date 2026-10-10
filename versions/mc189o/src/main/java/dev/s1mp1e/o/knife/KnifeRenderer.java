package dev.s1mp1e.o.knife;

import dev.s1mp1e.o.client.module.KnifeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.util.Map;

/**
 * Draws the CS2 knife viewmodel in place of the main-hand sword (1.8.9). Injected after the first {@code pushMatrix}
 * of {@code ItemInHandRenderer.renderInFirstPerson}, where the modelview already carries the camera + hand sway — the
 * same base the 26.2 PoseStack hook gets. The rig poses in Source viewmodel space (X fwd, Y left, Z up, metres, eye at
 * the origin); that is mapped into Minecraft view space (X right, Y up, -Z fwd) by {@link #SRC_TO_MC}, after CS2's
 * viewmodel_offset (inches) and the FOV correction tan(35)/tan(vfov/2). The skinned vertices come out in eye space so
 * {@link KnifeDraw} can draw them under an identity modelview.
 */
public final class KnifeRenderer {

    private KnifeRenderer() {}

    private static final KnifeAnimator ANIM = new KnifeAnimator();
    private static KnifeRig rig;
    private static KnifeRig.Attached arms, knife;
    private static String armsKey = "", knifeKey = "";
    private static int lastSlot = -1;
    private static boolean wasHolding;

    /** Source viewmodel axes -> Minecraft view axes: mc = (-y, z, -x). */
    static final Quaternionf SRC_TO_MC = new Quaternionf().setFromNormalized(new Matrix3f(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f));

    public static KnifeAnimator animator() { return ANIM; }

    /** Client tick: clear the equip edge when the knife isn't out, so the next draw counts as a fresh equip. */
    public static void tickEquipState() {
        try {
            if (!holdingKnife(Minecraft.getInstance().player)) { wasHolding = false; lastSlot = -1; }
        } catch (Throwable ignored) {}
    }

    public static boolean holdingKnife(ClientPlayerEntity p) {
        if (p == null || !KnifeModule.active() || !KnifePack.available()) return false;
        if (lockerOpen()) return true;
        ItemStack s = p.getItemInHand();
        return s != null && s.getItem() instanceof SwordItem;
    }

    /** The knife locker previews the knife in hand even without a sword. */
    private static boolean lockerOpen() {
        return Minecraft.getInstance().screen instanceof dev.s1mp1e.o.client.gui.KnifeLockerScreen;
    }

    /** @return true when the knife was drawn (the caller then cancels vanilla's main-hand render). */
    public static boolean render(ClientPlayerEntity player) {
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
                arms = new KnifeRig.Attached(am, rig.driver);
                armsKey = gloveName;
            }
            if (!knifeName.equals(knifeKey) || knife == null) {
                KMesh km = KnifePack.knifeMesh(knifeName);
                if (km == null) return false;
                knife = new KnifeRig.Attached(km, rig.driver);
                knifeKey = knifeName;
            }

            // one draw per equip (knife change / slot hot-swap also fires exactly one)
            boolean changedKnife = ANIM.setKnife(knifeName);
            boolean justEquipped = !wasHolding;
            wasHolding = true;
            int slot = player.inventory.selectedSlot;
            boolean swapped = !justEquipped && slot != lastSlot;
            if (changedKnife || justEquipped || swapped) ANIM.draw();
            lastSlot = slot;

            boolean mcArms = "mc_arms".equals(mod.arms.modeValue);
            ANIM.update();
            ANIM.apply(rig);
            rig.solve();

            // Source-viewmodel -> hand-space chain, in front of the captured base modelview
            Matrix4f base = base();
            Matrix4f chain = new Matrix4f();
            float vfov = (float) (2.0 * Math.atan(Math.tan(Math.toRadians(mod.fov.doubleValue) / 2.0) * 0.75));
            float k = (float) (Math.tan(Math.toRadians(35.0)) / Math.tan(vfov / 2.0));
            chain.scale(k, k, 1f);
            chain.rotate(SRC_TO_MC);
            float in = 0.0254f;
            chain.translate((float) mod.offY.doubleValue * in, (float) -mod.offX.doubleValue * in, (float) mod.offZ.doubleValue * in);
            Matrix4f PM = new Matrix4f(base).mul(chain);
            Matrix3f NM = PM.normal(new Matrix3f());

            KnifeSkins.Skin skin = KnifeSkins.skin(knifeName, mod.skin.modeValue, (float) mod.wear.doubleValue);
            Map<String, Integer> gloveTex = mcArms ? null
                    : GloveSkins.skin(gloveName, mod.gloveSkin.modeValue, (float) mod.gloveWear.doubleValue);

            if (mcArms) {
                McArms.draw(PM, NM, rig, player);
            } else {
                arms.pose(rig, PM, NM);
                final KMesh am = arms.mesh;
                final Map<String, Integer> gt = gloveTex;
                KnifeDraw.drawPosed(arms, p -> partTex(am, p, null, gt), 0xF000F0);
            }
            knife.pose(rig, PM, NM);
            final KMesh km = knife.mesh;
            final KnifeSkins.Skin sk = skin;
            KnifeDraw.drawPosed(knife, p -> partTex(km, p, sk, null), 0xF000F0);
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] CS2 knife render failed, falling back to vanilla: " + t);
            KnifeModule m = KnifeModule.get();
            if (m != null) m.enabled = false;
            return false;
        }
    }

    /** The GL modelview at the injection point = camera + hand sway (our render-space base). */
    static Matrix4f base() {
        float[] buf = new float[16];
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, buf);
        return new Matrix4f().set(buf);     // GL is column-major, JOML set(float[]) is column-major
    }

    /** GL texture id for part {@code p}: glove material override, else the knife skin, else the pack texture. */
    static int partTex(KMesh m, int p, KnifeSkins.Skin skin, Map<String, Integer> byMaterial) {
        if (byMaterial != null) {
            Integer glove = byMaterial.get(m.partMaterial[p]);
            if (glove != null) return glove;
        }
        String file = m.partTexture[p];
        if (skin != null && file != null && stem(file).equals(skin.base())) return skin.texture();
        return KnifePack.texture(file);
    }

    private static String stem(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
