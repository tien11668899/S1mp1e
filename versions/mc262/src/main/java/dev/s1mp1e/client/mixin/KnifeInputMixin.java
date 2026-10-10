package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.knife.KnifeInput;
import dev.s1mp1e.client.knife.KnifeRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds attack / use / swap-hands input to the CS2 knife animator (observes only; never cancels the action). */
@Mixin(Minecraft.class)
public abstract class KnifeInputMixin {

    @Inject(method = "startAttack", at = @At("HEAD"))
    private void s1mp1e$knifeAttack(CallbackInfoReturnable<Boolean> cir) {
        KnifeInput.onAttack();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void s1mp1e$knifeUse(CallbackInfo ci) {
        KnifeInput.onUse();
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void s1mp1e$knifeKeys(CallbackInfo ci) {
        KnifeInput.onKeybinds();
    }

    /** Clear the knife's equip edge each tick when it isn't out, so re-equip draws exactly once. */
    @Inject(method = "tick", at = @At("RETURN"))
    private void s1mp1e$knifeTick(CallbackInfo ci) {
        KnifeRenderer.tickEquipState();
    }
}
