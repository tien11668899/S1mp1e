package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.o.client.module.MouseDelayFixModule;
import dev.s1mp1e.o.client.module.SmoothSneakModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two camera-feel fixes in {@code GameRenderer}: {@link MouseDelayFixModule} pumps the input right before the frame's
 * mouse delta is read ({@code render(FJ)}, at {@code Mouse.tick}); {@link SmoothSneakModule} places the camera at the
 * eased eye height ({@code transformCamera}, the one {@code getEyeHeight} read that positions the view).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFeelMixin {

    @Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;tick()V"))
    private void s1mp1e$freshMouse(float tickDelta, long startTime, CallbackInfo ci) {
        MouseDelayFixModule.beforeMouseRead();
    }

    @ModifyExpressionValue(method = "transformCamera(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getEyeHeight()F"))
    private float s1mp1e$smoothEye(float vanilla, float tickDelta) {
        return SmoothSneakModule.eyeHeight(Minecraft.getInstance().getCamera(), vanilla, tickDelta);
    }
}
