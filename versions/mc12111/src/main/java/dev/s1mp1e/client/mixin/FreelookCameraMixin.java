package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.FreelookModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Freelook (26.3): {@code Camera.alignWithEntity(F)} copies the entity's view rotation with {@code setRotation(FF)} —
 * ordinal 0 on the minecart-lerp path, ordinal 1 on the normal path — and only then, when detached, mirrors it for the
 * front view (ordinal 2, reads the fields we just set) and pulls the camera back along it. Swapping the first two
 * calls' arguments for the freelook angles therefore orbits the third-person camera around the player, with vanilla's
 * wall collision ({@code getMaxZoom}) still applied. Render-only.
 */
@Mixin(Camera.class)
public class FreelookCameraMixin {

    @Inject(method = "setup", at = @At("HEAD"))   // 26.x: Camera.update(DeltaTracker)
    private void s1mp1e$freelookFrame(net.minecraft.world.level.Level level, net.minecraft.world.entity.Entity entity,
                                      boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        FreelookModule.frame();
    }

    @ModifyArgs(method = "setup",   // 26.x: alignWithEntity(F)
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0))
    private void s1mp1e$freelookCart(Args args) {
        s1mp1e$apply(args);
    }

    @ModifyArgs(method = "setup",   // 26.x: alignWithEntity(F)
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1))
    private void s1mp1e$freelook(Args args) {
        s1mp1e$apply(args);
    }

    private static void s1mp1e$apply(Args args) {
        if (!FreelookModule.active()) return;
        args.set(0, FreelookModule.yaw());
        args.set(1, FreelookModule.pitch());
    }
}
