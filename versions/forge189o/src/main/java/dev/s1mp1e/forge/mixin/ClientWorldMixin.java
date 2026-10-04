package dev.s1mp1e.forge.mixin;

import net.minecraft.client.world.ClientWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 在 WorldClient 建構完成時發 WorldEvent.Load。 */
@Mixin(value = ClientWorld.class, priority = 1050)
public abstract class ClientWorldMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void s1f$load(CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load((ClientWorld) (Object) this));
    }
}
