package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.s1mp1e.client.module.FreelookModule;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook (1.21.1): {@code Mouse.updateMouse(D)} ends in {@code player.changeLookDirection(dx, dy)} with the
 * sensitivity, smooth camera and invert options already applied. While freelook holds the camera, that same delta
 * turns the camera instead and the player's rotation is left exactly as it was. Zoom's look scaling (HEAD of the same
 * method) still applies.
 */
@Mixin(Mouse.class)
public class FreelookMouseMixin {

    @WrapWithCondition(method = "updateMouse",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private boolean s1mp1e$freelookTurn(ClientPlayerEntity player, double dx, double dy) {
        if (!FreelookModule.capturing()) return true;
        FreelookModule.turn(dx, dy);
        return false;
    }
}
