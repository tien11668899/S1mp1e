package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.event.DrawBlockHighlightEvent;
import dev.s1mp1e.o.event.EntityViewRenderEvent;
import dev.s1mp1e.o.event.GuiScreenEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.world.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Forge 在 GameRenderer 上的事件：畫面繪製前後（DrawScreenEvent）、FOV 修改（FOVModifier）、方塊選取框（DrawBlockHighlight）。 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class GameRendererMixin {

    @WrapOperation(method = "render(FJ)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/Screen;render(IIF)V"))
    private void s1mp1e$drawScreen(Screen s, int mx, int my, float pt, Operation<Void> op) {
        if (!MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.DrawScreenEvent.Pre(s, mx, my, pt))) {
            op.call(s, mx, my, pt);
        }
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.DrawScreenEvent.Post(s, mx, my, pt));
    }

    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float s1mp1e$fov(float fov, float tickDelta, boolean changing) {
        EntityViewRenderEvent.FOVModifier e = new EntityViewRenderEvent.FOVModifier(
                (GameRenderer) (Object) this, Minecraft.getInstance().getCamera(), tickDelta, fov);
        MinecraftForge.EVENT_BUS.post(e);
        return e.getFOV();
    }

    @WrapOperation(method = "render(IFJ)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/world/WorldRenderer;renderBlockOutline(Lnet/minecraft/entity/living/player/PlayerEntity;Lnet/minecraft/world/HitResult;IF)V"))
    private void s1mp1e$highlight(WorldRenderer wr, PlayerEntity p, HitResult hit, int sub, float pt, Operation<Void> op) {
        DrawBlockHighlightEvent e = new DrawBlockHighlightEvent(wr, p, hit, sub, p.inventory.getSelectedItem(), pt);
        if (!MinecraftForge.EVENT_BUS.post(e)) op.call(wr, p, hit, sub, pt);
    }
}
