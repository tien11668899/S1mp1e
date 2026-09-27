package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NoHurtCam (red flash), 1.14.4 port. 1.14.4 has no {@code getOverlay}/{@code OverlayTexture}; the red
 * hurt/death tint is set up in the fixed-function texture combiner by
 * {@code LivingEntityRenderer.applyOverlayColor(LivingEntity, float, boolean)} (protected; erased
 * descriptor {@code (Lnet/minecraft/entity/LivingEntity;FZ)Z}, javap-verified, yarn 1.14.4+build.18):
 *
 * <pre>
 *   int i = getOverlayColor(entity, brightness, tickDelta);   // creeper swell / other white flash
 *   boolean overlay = (i &gt;&gt; 24 &amp; 0xFF) &gt; 0;
 *   boolean hurt = entity.hurtTime &gt; 0 || entity.deathTime &gt; 0;   // the red tint
 * </pre>
 *
 * The method reads {@code hurtTime} and {@code deathTime} exactly once each (2 GETFIELDs). While the
 * setting is on we make both reads yield 0 via MixinExtras {@code @ModifyExpressionValue} (chains with
 * other mods), so {@code hurt} is false and the red tint is never applied — while the
 * {@code getOverlayColor} flash (creeper) is kept. The entity's real fields are never written, so the
 * death animation and everything else keep working. Cosmetic only (the counterpart of the 1.21.1
 * reference's {@code getOverlay} hook).
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererFlashMixin {

    @ModifyExpressionValue(
        method = "applyOverlayColor(Lnet/minecraft/entity/LivingEntity;FZ)Z",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/entity/LivingEntity;hurtTime:I",
                 opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private int s1mp1e$noHurtFlash(int hurtTime) {
        return s1mp1e$suppress() ? 0 : hurtTime;
    }

    @ModifyExpressionValue(
        method = "applyOverlayColor(Lnet/minecraft/entity/LivingEntity;FZ)Z",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/entity/LivingEntity;deathTime:I",
                 opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private int s1mp1e$noDeathFlash(int deathTime) {
        return s1mp1e$suppress() ? 0 : deathTime;
    }

    @Unique
    private static boolean s1mp1e$suppress() {
        try {
            return NoHurtCamModule.flashSuppressed();
        } catch (Throwable t) {
            return false;   // vanilla flash on any failure
        }
    }
}
