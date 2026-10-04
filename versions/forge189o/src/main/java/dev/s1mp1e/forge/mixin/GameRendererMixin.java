package dev.s1mp1e.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 在世界渲染最後的 RenderWorldLastEvent（畫手之前）與可取消的 RenderHandEvent。 */
@Mixin(value = GameRenderer.class, priority = 1050)
public abstract class GameRendererMixin {
    @Shadow private Minecraft minecraft;

    @Inject(method = "render(IFJ)V", at = @At(value = "CONSTANT", args = "stringValue=hand"))
    private void s1f$renderLast(int pass, float pt, long nanos, CallbackInfo ci) {
        ForgeHooksClient.dispatchRenderLast(this.minecraft.worldRenderer, pt);
    }

    @WrapOperation(method = "render(IFJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderItemInHand(FI)V"))
    private void s1f$hand(GameRenderer self, float pt, int pass, Operation<Void> op) {
        if (ForgeHooksClient.renderFirstPersonHand(this.minecraft.worldRenderer, pt, pass)) return;
        op.call(self, pt, pass);
    }
}
