package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.event.MinecraftForge;
import dev.s1mp1e.o.event.RenderBlockOverlayEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Forge 在第一人稱火焰覆蓋畫之前發 RenderBlockOverlayEvent(FIRE)，可取消（LowFire 模組用）。 */
@Mixin(value = ItemInHandRenderer.class, priority = 1100)
public abstract class ItemInHandRendererMixin {
    @WrapOperation(method = "renderScreenEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/ItemInHandRenderer;renderOnFireEffect(F)V"))
    private void s1mp1e$fire(ItemInHandRenderer self, float pt, Operation<Void> op) {
        Minecraft mc = Minecraft.getInstance();
        RenderBlockOverlayEvent e = new RenderBlockOverlayEvent(mc.player, pt, RenderBlockOverlayEvent.OverlayType.FIRE,
                null, null);
        if (!MinecraftForge.EVENT_BUS.post(e)) op.call(self, pt);
    }
}
