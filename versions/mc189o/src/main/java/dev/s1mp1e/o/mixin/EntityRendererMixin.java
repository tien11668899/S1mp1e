package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.hook.GlassNameTag;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** coremod「Render.renderLivingLabel」：名牌底板的頂點顏色換成霧面炭灰（世界空間、不折射、可見度不變）。 */
@Mixin(value = EntityRenderer.class, priority = 1100)
public abstract class EntityRendererMixin {
    @WrapOperation(method = "renderNameTag(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/vertex/BufferBuilder;color(FFFF)Lnet/minecraft/client/render/vertex/BufferBuilder;"))
    private BufferBuilder s1mp1e$plate(BufferBuilder wr, float r, float g, float b, float a, Operation<BufferBuilder> op) {
        return GlassNameTag.color(wr, r, g, b, a);
    }
}
