package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.minimap.MinimapData;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minimap (26.3): every chunk the server sends lands in {@code ClientChunkCache.replaceWithPacketData}; once it returns
 * the chunk is readable, so queue its tile. One boolean check when the minimap is off.
 */
@Mixin(ClientChunkCache.class)
public class MinimapChunkMixin {

    @Inject(method = "replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN"))
    private void s1mp1e$minimapChunk(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<LevelChunk> cir) {
        if (MinimapData.active && cir.getReturnValue() != null) MinimapData.onChunkLoaded(x, z);
    }
}
