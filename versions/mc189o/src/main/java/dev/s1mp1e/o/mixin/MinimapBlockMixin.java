package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.minimap.MinimapData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimap (1.8.9): {@code World.notifyBlockChanged} tells the world's listeners (the chunk renderer) about every block
 * change that needs a redraw, after the chunk and its heightmap were updated: server block and multi-block packets and
 * local prediction (breaking, placing) alike. Only the client world is interesting; the integrated server's world runs
 * the same method on its own thread and leaves at the first check. The data layer decides whether the change can be
 * seen on the map at all. 1.21.x hooks {@code ClientWorld.updateListeners}.
 */
@Mixin(World.class)
public abstract class MinimapBlockMixin {

    @Shadow @Final public boolean isClient;

    @Inject(method = "notifyBlockChanged", at = @At("HEAD"))
    private void s1mp1e$minimapBlock(BlockPos pos, CallbackInfo ci) {
        if (isClient && MinimapData.active) MinimapData.onBlockChanged(pos);
    }
}
