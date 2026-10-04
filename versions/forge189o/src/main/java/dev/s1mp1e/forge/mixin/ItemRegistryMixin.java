package dev.s1mp1e.forge.mixin;

import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.resource.Identifier;
import net.minecraft.util.registry.IdRegistry;
import net.minecraftforge.fml.common.registry.GameData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 把物品註冊表與「方塊→物品」表換成 FML 的（GameData）。 */
@Mixin(Item.class)
public abstract class ItemRegistryMixin {
    @Shadow @Final @Mutable public static IdRegistry<Identifier, Item> REGISTRY;
    @Shadow @Final @Mutable private static Map<Block, Item> BLOCK_ITEMS;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void s1f$fmlRegistry(CallbackInfo ci) {
        REGISTRY = GameData.getItemRegistry();
        BLOCK_ITEMS = GameData.getBlockItemMap();
    }
}
