package dev.s1mp1e.forge.mixin;

import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FML 的 TickEvent.PlayerTickEvent START/END（EntityPlayer.onUpdate 前後）。 */
@Mixin(value = PlayerEntity.class, priority = 1050)
public abstract class PlayerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void s1f$pre(CallbackInfo ci) {
        FMLCommonHandler.instance().onPlayerPreTick((PlayerEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void s1f$post(CallbackInfo ci) {
        FMLCommonHandler.instance().onPlayerPostTick((PlayerEntity) (Object) this);
    }
}
