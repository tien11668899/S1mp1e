package dev.s1mp1e.forge.mixin.donor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** 自動產生（forgetool/package.sh）：要移植 Forge 新增成員的原版介面（intermediary 名稱）。 */
@Pseudo
@Mixin(targets = {
        "net/minecraft/unmapped/C_46108969",
        "net/minecraft/unmapped/C_56964626$C_45502249",
}, priority = 1)
public interface DonorItfIntermediary {
}
