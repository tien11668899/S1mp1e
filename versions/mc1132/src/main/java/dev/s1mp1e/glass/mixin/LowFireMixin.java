package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.LowFireModule;
import net.minecraft.class_4225;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link LowFireModule} on 1.13.2 (Legacy Fabric): the first-person fire overlay is the unmapped
 * {@code class_4225.method_19149()V} (the first-person renderer; private, no args, immediate-mode Tessellator;
 * javap-verified - it is the only method there reading {@code class_4288.field_21065} = {@code block/fire_1}). It sets the
 * flame alpha with {@code GlStateManager.color(1, 1, 1, 0.9f)} and draws two quads at vanilla's -0.3 screen-effect
 * offset. Wrap the whole method in a push -> translate(0, -drop, 0) -> scale so both flames drop / shrink, and scale
 * that 0.9 alpha argument. Same shape as the 1.14.4 {@code HeldItemRenderer.renderFireOverlay} mixin.
 */
@Mixin(class_4225.class)
public abstract class LowFireMixin {

    @Unique private static boolean s1mp1e$pushed;

    @Inject(method = "method_19149()V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$lowerFire(CallbackInfo ci) {
        s1mp1e$pushed = false;
        if (!LowFireModule.active()) return;
        if (LowFireModule.hidden()) { ci.cancel(); return; }   // "Hide fire" / "Hide with Fire Res"
        float drop = LowFireModule.drop();
        float size = LowFireModule.size();
        if (drop <= 0f && size >= 0.999f) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, -drop, 0.0F);
        if (size < 0.999f) GlStateManager.scale(size, size, 1.0F);
        s1mp1e$pushed = true;
    }

    @Inject(method = "method_19149()V", at = @At("RETURN"))
    private void s1mp1e$restoreFire(CallbackInfo ci) {
        if (s1mp1e$pushed) {
            GlStateManager.popMatrix();
            s1mp1e$pushed = false;
        }
    }

    @ModifyArg(method = "method_19149()V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;color(FFFF)V", ordinal = 0),   // only the 0.9 flame alpha, NOT the trailing color(1,1,1,1) reset
            index = 3)
    private float s1mp1e$fadeFire(float alpha) {
        return LowFireModule.active() ? LowFireModule.scaleAlpha(alpha) : alpha;
    }
}
