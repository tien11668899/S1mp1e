package dev.s1mp1e.forge.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockState;
import net.minecraft.resource.Identifier;
import net.minecraft.util.CrudeIncrementalIntIdentityHashMap;
import net.minecraft.util.registry.DefaultedIdRegistry;
import net.minecraftforge.fml.common.registry.GameData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 把方塊註冊表與方塊狀態 ID 表換成 FML 的（GameData）；靜態初始化結束時照做（此時還沒註冊任何方塊）。 */
@Mixin(Block.class)
public abstract class BlockRegistryMixin {
    @Shadow @Final @Mutable public static DefaultedIdRegistry<Identifier, Block> REGISTRY;
    @Shadow @Final @Mutable public static CrudeIncrementalIntIdentityHashMap<BlockState> STATE_REGISTRY;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void s1f$fmlRegistry(CallbackInfo ci) {
        REGISTRY = GameData.getBlockRegistry();
        STATE_REGISTRY = GameData.getBlockStateIDMap();
    }
}
