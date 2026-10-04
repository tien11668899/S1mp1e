package dev.s1mp1e.forge.mixin;

import java.util.List;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forge 的 ItemTooltipEvent：工具提示行清單回傳前讓監聽者增刪。 */
@Mixin(value = ItemStack.class, priority = 1050)
public abstract class ItemStackMixin {
    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void s1f$tooltip(PlayerEntity player, boolean advanced, CallbackInfoReturnable<List<String>> cir) {
        ForgeEventFactory.onItemTooltip((ItemStack) (Object) this, player, cir.getReturnValue(), advanced);
    }
}
