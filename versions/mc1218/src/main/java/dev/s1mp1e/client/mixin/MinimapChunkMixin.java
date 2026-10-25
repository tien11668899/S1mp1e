package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.minimap.MinimapData;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Minimap: every chunk the server sends lands in {@code ClientChunkCache.replaceWithPacketData}; once it returns the
 * chunk is readable, so queue its tile. One boolean check when the minimap is off. (26.2 still passes the raw buffer,
 * heightmaps and block-entity consumer here; 26.3 hands the whole packet data object.)
 */
@Mixin(ClientChunkCache.class)
public class MinimapChunkMixin {

    @Inject(method = "replaceWithPacketData(IILnet/minecraft/network/FriendlyByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"))
    private void s1mp1e$minimapChunk(int x, int z, FriendlyByteBuf buf, Map<Heightmap.Types, long[]> heightmaps,
                                     Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
                                     CallbackInfoReturnable<LevelChunk> cir) {
        if (MinimapData.active && cir.getReturnValue() != null) MinimapData.onChunkLoaded(x, z);
    }
}
