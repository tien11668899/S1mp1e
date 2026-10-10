package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.knife.CsArm;
import dev.s1mp1e.o.knife.KnifeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CS2 knives on 1.8.9's first-person renderer. Two injects into {@code renderInFirstPerson(float)}:
 * <ul>
 *   <li>right after the first {@code pushMatrix} (modelview = camera + hand sway): if a sword is in hand (or the
 *       locker is open), draw the CS2 knife viewmodel instead of the vanilla item, then balance the state vanilla's
 *       tail would (popMatrix / disableRescaleNormal / Lighting.turnOff) and cancel.</li>
 *   <li>at the empty-hand {@code renderHand} call: with "CS arms everywhere" on, draw the boxing arms instead.</li>
 * </ul>
 * Priority 1050 so this runs before the HandPosition offset splice at the same point (the knife uses its own chain).
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1050)
public abstract class ItemInHandRendererKnifeMixin {

    @Inject(method = "renderInFirstPerson", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/render/platform/GlStateManager;pushMatrix()V", shift = At.Shift.AFTER),
            cancellable = true)
    private void s1mp1e$knife(float pt, CallbackInfo ci) {
        ClientPlayerEntity p = Minecraft.getInstance().player;
        if (!KnifeRenderer.holdingKnife(p)) return;
        if (KnifeRenderer.render(p)) {
            GlStateManager.popMatrix();
            GlStateManager.disableRescaleNormal();
            Lighting.turnOff();
            ci.cancel();
        }
    }

    /**
     * Replaces only the {@code renderHand} call (WrapOperation, not a cancelling @Inject: cancelling would return from
     * renderInFirstPerson and skip vanilla's popMatrix / disableRescaleNormal / Lighting.turnOff tail — the modelview
     * stack then grows every frame and lighting stays on, which blanks HUD text and every inventory item).
     */
    @WrapOperation(method = "renderInFirstPerson", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/ItemInHandRenderer;renderHand(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;FF)V"))
    private void s1mp1e$csArm(ItemInHandRenderer self, ClientPlayerEntity p, float handH, float attack, Operation<Void> op) {
        if (CsArm.enabled() && p != null && CsArm.render(handH, attack)) return;
        op.call(self, p, handH, attack);
    }
}
