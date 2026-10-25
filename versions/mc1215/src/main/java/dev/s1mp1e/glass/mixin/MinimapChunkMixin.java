package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.minimap.MinimapData;
import java.util.function.Consumer;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minimap (1.21.1): every chunk the server sends lands in {@code ClientChunkManager.loadChunkFromPacket}; once it
 * returns the chunk is readable, so queue its tile. One boolean check when the minimap is off.
 */
@Mixin(ClientChunkManager.class)
public class MinimapChunkMixin {

    // 1.21.5: the heightmaps arrive as a map (was NBT)
    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void s1mp1e$minimapChunk(int x, int z, PacketByteBuf buf, java.util.Map<?, ?> heightmaps,
                                     Consumer<ChunkData.BlockEntityVisitor> blockEntities,
                                     CallbackInfoReturnable<WorldChunk> cir) {
        if (MinimapData.active && cir.getReturnValue() != null) MinimapData.onChunkLoaded(x, z);
    }
}
