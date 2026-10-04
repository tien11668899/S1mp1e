package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.s1mp1e.o.event.FOVUpdateEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Forge 在 AbstractClientPlayer.getFovModifier 回傳前發 FOVUpdateEvent。 */
@Mixin(value = ClientPlayerEntity.class, priority = 1100)
public abstract class ClientPlayerEntityMixin {
    @ModifyReturnValue(method = "getFovModifier", at = @At("RETURN"))
    private float s1mp1e$fovUpdate(float fov) {
        FOVUpdateEvent e = new FOVUpdateEvent((ClientPlayerEntity) (Object) this, fov);
        MinecraftForge.EVENT_BUS.post(e);
        return e.newfov;
    }
}
