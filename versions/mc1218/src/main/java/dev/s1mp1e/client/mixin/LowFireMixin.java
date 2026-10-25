package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.LowFireModule;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LowFire on 1.21.6-1.21.8: {@code ScreenEffectRenderer.renderFire(PoseStack, MultiBufferSource)} draws the two burning
 * overlay quads straight into the buffer source (26.x submits them through {@code submitFire} / {@code buildFireQuad}).
 * HEAD lowers / shrinks the pose (or cancels when hidden), RETURN restores it, and every vertex colour's alpha (the
 * fourth {@code setColor} argument, vanilla 0.9) is scaled by the module's opacity.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class LowFireMixin {
    @Unique
    private static boolean s1mp1e$pushed;

    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$lowerFire(PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        s1mp1e$pushed = false;
        if (!LowFireModule.active()) return;
        if (LowFireModule.hidden()) { ci.cancel(); return; }   // "Hide fire" / "Hide with Fire Res"
        float drop = LowFireModule.drop();
        float size = LowFireModule.size();
        if (drop <= 0f && size >= 0.999f) return;
        pose.pushPose();
        pose.translate(0.0F, -drop, 0.0F);
        if (size < 0.999f) pose.scale(size, size, 1.0F);
        s1mp1e$pushed = true;
    }

    @Inject(method = "renderFire", at = @At("RETURN"))
    private static void s1mp1e$restoreFire(PoseStack pose, MultiBufferSource buffers, CallbackInfo ci) {
        if (s1mp1e$pushed) {
            pose.popPose();
            s1mp1e$pushed = false;
        }
    }

    @ModifyArg(method = "renderFire",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            index = 3)
    private static float s1mp1e$fadeFire(float alpha) {
        if (!LowFireModule.active()) return alpha;
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (LowFireModule.scaleAlpha(a << 24 | 0xFFFFFF) >>> 24) / 255f;
    }
}
