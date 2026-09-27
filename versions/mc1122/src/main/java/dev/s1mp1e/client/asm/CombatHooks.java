package dev.s1mp1e.client.asm;

import dev.s1mp1e.client.module.NoHurtCamModule;
import dev.s1mp1e.client.module.OldAnimationsModule;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.math.MathHelper;

/**
 * Static hook targets called from bytecode spliced by {@link CombatTransformer}.
 *
 * <p><b>These methods run in the render hot path and must never throw.</b> A hook
 * that raised out of {@code EntityRenderer.hurtCameraEffect} or
 * {@code ItemRenderer.renderItemInFirstPerson} would crash the frame from inside
 * MC code we cannot try/catch, so every body here is wrapped and degrades to
 * vanilla behaviour on any failure.
 *
 * <p><b>FAIR-PLAY:</b> every method is render-only. Nothing here reads or writes a
 * packet, moves the player, changes hit timing, block-hit behaviour, reach, or the
 * attack cooldown. The swing hook only changes the GL matrix the held item is
 * <em>drawn</em> with; it does not decide whether a swing is sent — that stays
 * entirely with vanilla and is untouched.
 *
 * <p>Modules bind themselves here in their constructors, so the hooks read a plain
 * field reference rather than doing a {@code ModuleManager} lookup on every entity,
 * every frame.
 */
public final class CombatHooks {

    private CombatHooks() {}

    private static volatile NoHurtCamModule noHurtCam;
    private static volatile OldAnimationsModule oldAnimations;

    public static void bindNoHurtCam(NoHurtCamModule m)         { noHurtCam = m; }
    public static void bindOldAnimations(OldAnimationsModule m) { oldAnimations = m; }

    /** True once the {@code hurtCameraEffect} head guard went in. */
    public static boolean hurtCamPatched()   { return CombatTransformer.hurtCamPatched; }
    /** True once the {@code RenderLivingBase.setBrightness} head guard went in. */
    public static boolean hurtFlashPatched() { return CombatTransformer.hurtFlashPatched; }
    /** True once the swing-while-using splice went in. */
    public static boolean swingPatched()     { return CombatTransformer.swingPatched; }

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
    // 2) RenderLivingBase.setBrightness head guard
    // -----------------------------------------------------------------------

    /**
     * True -> {@code setBrightness} returns false immediately (no colour overlay
     * applied, and its paired {@code unsetBrightness} is correctly skipped because
     * every caller keys off the same false return).
     *
     * <p>Deliberately narrow: it only short-circuits when the entity is actually in
     * its hurt/death window ({@code hurtTime > 0 || deathTime > 0}) — exactly the
     * frames vanilla would paint the red flash. When the entity is NOT hurt the
     * hook returns false, so a legitimate {@code getColorMultiplier} tint (e.g. a
     * charging creeper's white flash) still runs through the untouched vanilla
     * path.
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
    // 3) Swing while using (ItemRenderer.renderItemInFirstPerson, per hand)
    // -----------------------------------------------------------------------

    // The per-hand renderer's arguments, captured at its method ENTRY and consumed at
    // the renderItemSide call a few instructions later.
    //
    // WHY NOT READ THE LOCALS AT THE CALL: measured, not assumed. In the SRG form of
    // 1.12.2 (the obfuscator's own minimal stack-map frames, which the FML remapper
    // hands through unchanged) the frame covering the renderItemSide call declares NO
    // live locals — the parameters are dead by then. Emitting the reference ALOADs the
    // spec sketches there is a hard "VerifyError: Bad local variable type ... Type top
    // is not assignable to reference type" that kills ItemRenderer at class load. At
    // method entry the locals are the method descriptor by definition, in every
    // bytecode shape, so capturing there is frame-proof.
    //
    // Client render thread only, and nothing between the entry and the call re-enters
    // this method, so one slot is enough; the read clears it, so a stale pose can never
    // leak into a later frame.
    private static AbstractClientPlayer fpPlayer;
    private static EnumHand fpHand;
    private static float fpSwing;

    /**
     * Spliced at the head of the per-hand
     * {@code ItemRenderer.renderItemInFirstPerson(AbstractClientPlayer,F,F,EnumHand,F,ItemStack,F)}:
     * captures the arguments {@link #swingWhileUsing()} needs. Pure assignment, no game state read.
     */
    public static void beginFirstPerson(AbstractClientPlayer player, EnumHand hand, float swing) {
        try {
            fpPlayer = player;
            fpHand = hand;
            fpSwing = swing;
        } catch (Throwable t) {
            // never-throw
        }
    }

    /**
     * Spliced immediately before the {@code renderItemSide} call of the per-hand
     * {@code renderItemInFirstPerson}. 1.12.2 applies the swing transform
     * ({@code transformFirstPerson}) only in the "not using an item" branch, so the
     * held item freezes while eating/drinking/drawing a bow/blocking even though the
     * swing progress keeps running. In exactly those frames — module on, main hand,
     * a live swing, and the main hand actively using an item (the mc1201
     * {@code HeldItemSwingMixin} design) — this re-applies vanilla's own swing math on
     * top of the use pose. In every other frame it does nothing, so vanilla is drawn
     * untouched. Purely visual.
     *
     * <p>Takes no arguments on purpose: see the fields above. It reads the values
     * {@link #beginFirstPerson} captured for THIS invocation and clears them.
     */
    public static void swingWhileUsing() {
        try {
            AbstractClientPlayer player = fpPlayer;
            EnumHand hand = fpHand;
            float swing = fpSwing;
            fpPlayer = null;                                               // consume: never reuse a stale pose
            fpHand = null;

            OldAnimationsModule m = oldAnimations;
            if (m == null || !m.enabled || !m.swingWhileUsing()) return;   // off -> vanilla untouched
            if (hand != EnumHand.MAIN_HAND) return;                        // main-hand animation only
            if (!(swing > 0.0F)) return;                                   // no live swing -> nothing to restore
            if (player == null) return;
            // Only add the swing in the frames vanilla dropped it (its use-action branch).
            if (!player.isHandActive()) return;
            if (player.getItemInUseCount() <= 0) return;
            if (player.getActiveHand() != EnumHand.MAIN_HAND) return;
            applySwing(player.getPrimaryHand(), swing);
        } catch (Throwable t) {
            // never-throw; a skipped swing is just the vanilla frozen pose
        }
    }

    /**
     * 1.12.2's private {@code ItemRenderer.transformFirstPerson(EnumHandSide, float)}
     * ({@code func_187453_a}), reproduced exactly — reproduced rather than reflected so the
     * hot path does no reflective call and cannot be broken by a renamed private method.
     * With {@code swing == 0} it is the identity (the two Y rotations cancel).
     */
    private static void applySwing(EnumHandSide side, float swing) {
        int i = side == EnumHandSide.RIGHT ? 1 : -1;
        float f = MathHelper.sin(swing * swing * (float) Math.PI);
        GlStateManager.rotate((float) i * (45.0F + f * -20.0F), 0.0F, 1.0F, 0.0F);
        float f1 = MathHelper.sin(MathHelper.sqrt(swing) * (float) Math.PI);
        GlStateManager.rotate((float) i * f1 * -20.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(f1 * -80.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate((float) i * -45.0F, 0.0F, 1.0F, 0.0F);
    }
}
