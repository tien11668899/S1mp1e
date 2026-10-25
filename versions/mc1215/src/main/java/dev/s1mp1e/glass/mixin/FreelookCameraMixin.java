package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.FreelookModule;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Freelook (1.21.1): {@code Camera.update} copies the entity's view rotation with {@code setRotation(FF)} (ordinal 0)
 * and only then, when detached, mirrors it for the front view (ordinal 1, reads the fields we just set) and pulls the
 * camera back along it. Swapping the first call's arguments for the freelook angles therefore orbits the third-person
 * camera around the player, with vanilla's wall collision ({@code clipToSpace}) still applied. Render-only.
 */
@Mixin(Camera.class)
public class FreelookCameraMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void s1mp1e$freelookFrame(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView,
                                      float tickDelta, CallbackInfo ci) {
        FreelookModule.frame();
    }

    @ModifyArgs(method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 0))
    private void s1mp1e$freelook(Args args) {
        if (!FreelookModule.active()) return;
        args.set(0, FreelookModule.yaw());
        args.set(1, FreelookModule.pitch());
    }
}
