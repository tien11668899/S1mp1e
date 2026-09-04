package dev.s1mp1e.client.asm;

import dev.s1mp1e.client.module.NoHurtCamModule;
import dev.s1mp1e.client.module.OldAnimationsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

/**
 * Static hook targets called from bytecode spliced by {@link CombatTransformer}.
 *
 * <p><b>These methods run in the render hot path and must never throw.</b> A hook
 * that raised out of {@code EntityRenderer.hurtCameraEffect} or
 * {@code ItemRenderer.transformFirstPersonItem} would crash the frame from inside
 * MC code we cannot try/catch, so every body here is wrapped and degrades to
 * vanilla behaviour on any failure.
 *
 * <p><b>FAIR-PLAY:</b> every method is render-only. Nothing here reads or writes a
 * packet, moves the player, changes hit timing, block-hit behaviour, reach, or the
 * attack cooldown. The old-animation hook only changes the GL matrix the held item
 * is <em>drawn</em> with; it does not decide whether a swing or a block-hit is sent
 * — that stays entirely with vanilla and is untouched.
 *
 * <p>Modules bind themselves here in their constructors (see the two module
 * classes), so the hooks read a plain field reference rather than doing a
 * {@code ModuleManager} lookup on every entity, every frame.
 */
public final class CombatHooks {

    private CombatHooks() {}

    private static volatile NoHurtCamModule noHurtCam;
    private static volatile OldAnimationsModule oldAnimations;

    public static void bindNoHurtCam(NoHurtCamModule m)       { noHurtCam = m; }
    public static void bindOldAnimations(OldAnimationsModule m) { oldAnimations = m; }

    // -----------------------------------------------------------------------
    // 1) EntityRenderer.hurtCameraEffect head guard
    // -----------------------------------------------------------------------

    /**
     * True -> the whole camera-shake pass is skipped. Spliced onto the head of
     * {@code hurtCameraEffect}, so both of its call sites (the two render passes)
     * are covered by this single guard. Also removes the death-spin rotate, which
     * lives in the same method — acceptable for a "no hurt cam" toggle.
     */
    public static boolean noHurtCam() {
        try {
            NoHurtCamModule m = noHurtCam;
            return m != null && m.enabled && m.suppressShake();
        } catch (Throwable t) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // 2) RendererLivingEntity.setBrightness head guard
    // -----------------------------------------------------------------------

    /**
     * True -> {@code setBrightness} returns false immediately (no colour overlay
     * applied, and its paired {@code unsetBrightness} is correctly skipped because
     * the caller keys off the same false return).
     *
     * <p>Deliberately narrow: it only short-circuits when the entity is actually in
     * its hurt/death window ({@code hurtTime > 0 || deathTime > 0}) — exactly the
     * frames vanilla would paint the red flash. When the entity is NOT hurt the
     * hook returns false, so a legitimate {@code getColorMultiplier} tint (e.g. a
     * charging creeper's white flash) still runs through the untouched vanilla
     * path. The one edge it also drops is a tint that coincides with a hurt frame,
     * which is negligible.
     */
    public static boolean suppressHurtFlash(EntityLivingBase e) {
        try {
            NoHurtCamModule m = noHurtCam;
            if (m == null || !m.enabled || !m.suppressFlash()) return false;
            return e != null && (e.hurtTime > 0 || e.deathTime > 0);
        } catch (Throwable t) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // 3) ItemRenderer.transformFirstPersonItem replacement
    // -----------------------------------------------------------------------

    /**
     * Drop-in replacement for {@code ItemRenderer.transformFirstPersonItem}: every
     * call site inside {@code renderItemInFirstPerson} is retargeted here with the
     * receiver already on the stack as {@code self}.
     *
     * <p>When the module is OFF this reproduces the vanilla transform stack
     * byte-for-byte (verified against 1.8.9 source), so the hand is drawn exactly
     * as vanilla draws it.
     *
     * <p>When ON it restores the one genuinely render-only 1.7 difference. The 1.7
     * and 1.8.9 first-person swing math is otherwise identical (same translate,
     * same three rotations, same scale — verified), but 1.8 passes
     * {@code swingProgress = 0} while an item is being used (blocking / eating /
     * drawing a bow), which stops the weapon from visibly swinging in those states.
     * 1.7 kept the swing. So when vanilla handed us a zero swing but the player is
     * actually mid-swing, we substitute the live swing progress — a purely visual
     * change to how the held item is drawn. It does not make a block-hit happen and
     * does not touch what is sent to the server.
     *
     * @param self  the ItemRenderer (unused, but present so the receiver on the
     *              stack has somewhere to go — keeps the bytecode a pure opcode swap)
     */
    public static void transformFirstPersonItem(ItemRenderer self, float equipProgress, float swingProgress) {
        try {
            float swing = swingProgress;
            OldAnimationsModule m = oldAnimations;
            if (m != null && m.enabled && m.swingWhileUsing() && swing == 0.0F) {
                float live = liveSwing();
                if (live > 0.0F) swing = live;
            }
            applyFirstPersonTransform(equipProgress, swing);
        } catch (Throwable t) {
            // Leaving the matrix stack half-applied would corrupt the whole HUD,
            // so on any failure fall back to the exact vanilla transform.
            try {
                applyFirstPersonTransform(equipProgress, swingProgress);
            } catch (Throwable t2) {
                // nothing left to do; a dropped transform is better than a crash
            }
        }
    }

    /** The vanilla 1.8.9 {@code transformFirstPersonItem} body, reproduced exactly. */
    private static void applyFirstPersonTransform(float equipProgress, float swingProgress) {
        GlStateManager.translate(0.56F, -0.52F, -0.71999997F);
        GlStateManager.translate(0.0F, equipProgress * -0.6F, 0.0F);
        GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F);
        float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float f1 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        GlStateManager.rotate(f * -20.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(f1 * -20.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(f1 * -80.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(0.4F, 0.4F, 0.4F);
    }

    /**
     * The player's current swing position, 0..1. Uses a partial tick of 1.0 rather
     * than fishing the private {@code Minecraft.timer} out by reflection: at 1.0,
     * {@code getSwingProgress} returns the latest ticked {@code swingProgress}
     * value, which is smooth enough for the draw and needs no fragile field access.
     */
    private static float liveSwing() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return 0.0F;
            return mc.thePlayer.getSwingProgress(1.0F);
        } catch (Throwable t) {
            return 0.0F;
        }
    }
}
