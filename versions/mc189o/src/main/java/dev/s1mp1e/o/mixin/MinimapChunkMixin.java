package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.minimap.MinimapData;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimap (1.8.9): both chunk packets (single chunk and the bulk packet) fill the client chunk through
 * {@code WorldChunk.update}, which rebuilds the heightmap at its end; once it returns the chunk is readable, so queue
 * its tile. One boolean check when the minimap is off. 1.21.x hooks {@code ClientChunkManager.loadChunkFromPacket}.
 */
@Mixin(WorldChunk.class)
public abstract class MinimapChunkMixin {

    @Shadow @Final public int chunkX;
    @Shadow @Final public int chunkZ;

    @Inject(method = "update", at = @At("RETURN"))
    private void s1mp1e$minimapChunk(byte[] data, int sections, boolean full, CallbackInfo ci) {
        if (MinimapData.active) MinimapData.onChunkLoaded(chunkX, chunkZ);
    }
}
