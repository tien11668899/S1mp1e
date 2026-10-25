package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.FreelookModule;
import dev.s1mp1e.client.module.ToggleSprintModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client tick (where Fabric API's END_CLIENT_TICK would fire) for ToggleSprint's latch release and Freelook's
 *  world-change reset. */
@Mixin(Minecraft.class)
public abstract class MovementTickMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void s1mp1e$movementTick(CallbackInfo ci) {
        ToggleSprintModule.tick();
        FreelookModule.tick();
        dev.s1mp1e.client.module.MinimapModule.tick();
    }
}
