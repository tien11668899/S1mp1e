package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.LowFireModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link LowFireModule}: shift the first-person fire overlay down and fade it. 1.16.5's
 * {@code InGameOverlayRenderer.renderFireOverlay(MinecraftClient, MatrixStack)} (private static, javap-verified) draws
 * the two flame quads through the passed stack with {@code VertexConsumer.color(1, 1, 1, 0.9f)} per vertex - so a
 * push -> translate(0, -drop, 0) at HEAD / pop at RETURN lowers both flames, and the colour's alpha argument is scaled.
 */
@Mixin(InGameOverlayRenderer.class)
public abstract class LowFireMixin {

    @Unique private static boolean s1mp1e$pushed;

    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$lowerFire(MinecraftClient client, MatrixStack matrices, CallbackInfo ci) {
        s1mp1e$pushed = false;
        if (!LowFireModule.active()) return;
        if (LowFireModule.hidden()) { ci.cancel(); return; }   // "Hide fire" / "Hide with Fire Res"
        float drop = LowFireModule.drop();
        float size = LowFireModule.size();
        if (drop <= 0f && size >= 0.999f) return;
        matrices.push();
        matrices.translate(0.0F, -drop, 0.0F);
        if (size < 0.999f) matrices.scale(size, size, 1.0F);
        s1mp1e$pushed = true;
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    private static void s1mp1e$restoreFire(MinecraftClient client, MatrixStack matrices, CallbackInfo ci) {
        if (s1mp1e$pushed) {
            matrices.pop();
            s1mp1e$pushed = false;
        }
    }

    @ModifyArg(method = "renderFireOverlay",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/VertexConsumer;color(FFFF)Lnet/minecraft/client/render/VertexConsumer;"),
            index = 3)
    private static float s1mp1e$fadeFire(float alpha) {
        return LowFireModule.active() ? LowFireModule.scaleAlpha(alpha) : alpha;
    }
}
