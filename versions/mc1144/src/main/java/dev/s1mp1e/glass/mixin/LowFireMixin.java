package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.LowFireModule;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link LowFireModule} on 1.14.4: the first-person fire overlay is {@code HeldItemRenderer.renderFireOverlay()V}
 * (private instance, no args, immediate-mode Tessellator; javap-verified) which sets the flame alpha with
 * {@code GlStateManager.color4f(1, 1, 1, 0.9f)} then draws the two quads. Wrap the whole method in a
 * push -> translate(0, -drop, 0) -> scale so both flames drop / shrink, and scale that 0.9 alpha argument.
 */
@Mixin(HeldItemRenderer.class)
public abstract class LowFireMixin {

    @Unique private static boolean s1mp1e$pushed;

    @Inject(method = "renderFireOverlay()V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$lowerFire(CallbackInfo ci) {
        s1mp1e$pushed = false;
        if (!LowFireModule.active()) return;
        if (LowFireModule.hidden()) { ci.cancel(); return; }   // "Hide fire" / "Hide with Fire Res"
        float drop = LowFireModule.drop();
        float size = LowFireModule.size();
        if (drop <= 0f && size >= 0.999f) return;
        GlStateManager.pushMatrix();
        GlStateManager.translatef(0.0F, -drop, 0.0F);
        if (size < 0.999f) GlStateManager.scalef(size, size, 1.0F);
        s1mp1e$pushed = true;
    }

    @Inject(method = "renderFireOverlay()V", at = @At("RETURN"))
    private void s1mp1e$restoreFire(CallbackInfo ci) {
        if (s1mp1e$pushed) {
            GlStateManager.popMatrix();
            s1mp1e$pushed = false;
        }
    }

    @ModifyArg(method = "renderFireOverlay()V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;color4f(FFFF)V", ordinal = 0),   // only the 0.9 flame alpha, NOT the trailing color(1,1,1,1) reset
            index = 3)
    private float s1mp1e$fadeFire(float alpha) {
        return LowFireModule.active() ? LowFireModule.scaleAlpha(alpha) : alpha;
    }
}
