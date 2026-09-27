package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.LowFireModule;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link LowFireModule}: shift the first-person fire overlay down and fade it. {@code submitFire} submits the fire quad
 * with {@code poseStack.last().copy()} (javap-verified), so push -> translate -> pop around it is safe; the alpha of the
 * quad colour (vanilla 0xE5FFFFFF) is scaled where {@code buildFireQuad} builds it.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class LowFireMixin {

    @Unique
    private static boolean s1mp1e$pushed;

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$lowerFire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        s1mp1e$pushed = false;
        if (!LowFireModule.active()) return;
        if (LowFireModule.hidden()) { ci.cancel(); return; }   // "Hide fire" / "Hide with Fire Res"
        float drop = LowFireModule.drop();
        float size = LowFireModule.size();
        if (drop <= 0f && size >= 0.999f) return;
        pose.pushPose();
        pose.translate(0.0F, -drop, 0.0F);
        if (size < 0.999f) pose.scale(size, size, 1.0F);
        s1mp1e$pushed = true;
    }

    @Inject(method = "submitFire", at = @At("RETURN"))
    private static void s1mp1e$restoreFire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (s1mp1e$pushed) {
            pose.popPose();
            s1mp1e$pushed = false;
        }
    }

    @ModifyArg(
        method = "buildFireQuad",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;buildSpriteQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;FFFFFI)V"
        ),
        index = 8
    )
    private static int s1mp1e$fadeFire(int argb) {
        return LowFireModule.active() ? LowFireModule.scaleAlpha(argb) : argb;
    }
}
