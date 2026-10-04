package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.event.AttackEntityEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 在 EntityPlayer.attackTargetEntityWithCurrentItem 開頭發 AttackEntityEvent（可取消）。 */
@Mixin(value = PlayerEntity.class, priority = 1100)
public abstract class PlayerEntityMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$attack(Entity target, CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent((PlayerEntity) (Object) this, target))) ci.cancel();
    }
}
