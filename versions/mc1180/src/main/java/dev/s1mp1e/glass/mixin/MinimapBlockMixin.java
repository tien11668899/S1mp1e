package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.minimap.MinimapData;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimap (1.21.1): {@code ClientWorld.updateListeners} runs for every client-side block change (server block and
 * section updates, and local prediction), after the chunk's heightmaps were updated. The data layer decides whether
 * the change can be seen on the map at all. One boolean check when the minimap is off.
 */
@Mixin(ClientWorld.class)
public class MinimapBlockMixin {

    @Inject(method = "updateListeners", at = @At("RETURN"))
    private void s1mp1e$minimapBlock(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (MinimapData.active && oldState != newState) MinimapData.onBlockChanged(pos);
    }
}
