package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.minimap.MinimapData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimap (26.3): {@code ClientLevel.sendBlockUpdated} runs for every client-side block change (server block and
 * section updates, and local prediction), after the chunk's heightmaps were updated. The data layer decides whether
 * the change can be seen on the map at all. One boolean check when the minimap is off.
 */
@Mixin(ClientLevel.class)
public class MinimapBlockMixin {

    @Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("RETURN"))
    private void s1mp1e$minimapBlock(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (MinimapData.active && oldState != newState) MinimapData.onBlockChanged(pos);
    }
}
