package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.s1mp1e.client.module.FreelookModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook (26.3): {@code MouseHandler.turnPlayer(D)} ends in {@code player.turn(dx, dy)} with the sensitivity, smooth
 * camera and invert options already applied. While freelook holds the camera, that same delta turns the camera instead
 * and the player's rotation is left exactly as it was. Zoom's look scaling (HEAD of the same method) still applies.
 */
@Mixin(MouseHandler.class)
public class FreelookMouseMixin {

    @WrapWithCondition(method = "turnPlayer(D)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean s1mp1e$freelookTurn(LocalPlayer player, double dx, double dy) {
        if (!FreelookModule.capturing()) return true;
        FreelookModule.turn(dx, dy);
        return false;
    }
}
