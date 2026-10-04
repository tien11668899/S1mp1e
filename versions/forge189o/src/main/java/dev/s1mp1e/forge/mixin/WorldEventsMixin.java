package dev.s1mp1e.forge.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forge 的 EntityJoinWorldEvent：實體真的加進世界前發；取消＝不加（玩家／強制生成的照加）。 */
@Mixin(value = World.class, priority = 1050)
public abstract class WorldEventsMixin {
    @Inject(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getChunkAt(II)Lnet/minecraft/world/chunk/WorldChunk;"), cancellable = true)
    private void s1f$join(Entity e, CallbackInfoReturnable<Boolean> cir) {
        boolean forced = e.teleporting || e instanceof PlayerEntity;
        if (MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(e, (World) (Object) this)) && !forced) cir.setReturnValue(false);
    }
}
