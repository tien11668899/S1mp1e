package dev.s1mp1e.forge.mixin.donor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** 自動產生（forgetool/package.sh）：要移植 Forge 新增成員的原版介面（named 名稱）。 */
@Pseudo
@Mixin(targets = {
        "net/minecraft/world/WorldView",
        "net/minecraft/entity/living/mob/passive/VillagerEntity$TradeSource",
}, priority = 1)
public interface DonorItfNamed {
}
