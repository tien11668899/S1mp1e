package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.o.client.asm.CameraHooks;
import dev.s1mp1e.o.client.asm.CombatHooks;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod CameraTransformer／CombatTransformer 在 EntityRenderer 上的修補：
 * <ul>
 *   <li>光照圖每次讀 gamma 都經過 CameraHooks.gamma（Fullbright 可超過選項的 0..1 上限）；</li>
 *   <li>縮放時滑鼠視角變慢：原版 {@code h = mouse.x * g; i = mouse.y * g}，g＝f³×8，只用在這兩行，
 *       而 scaleLook 是線性乘法，所以把 8.0F 這個常數乘上縮放倍率就等價於縮放兩個 delta；</li>
 *   <li>受傷鏡頭晃動（NoHurtCam）。</li>
 * </ul>
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class GameRendererCameraMixin {

    @ModifyExpressionValue(method = "updateLightMap", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/options/GameOptions;gamma:F"))
    private float s1mp1e$gamma(float v) {
        return CameraHooks.gamma(v);
    }

    @ModifyExpressionValue(method = "render(FJ)V",
            slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;tick()V")),
            at = @At(value = "CONSTANT", args = "floatValue=8.0", ordinal = 0))
    private float s1mp1e$lookScale(float eight) {
        return CameraHooks.scaleLook(eight);
    }

    @Inject(method = "applyHurtCam", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtCam(float pt, CallbackInfo ci) {
        if (CombatHooks.noHurtCam()) ci.cancel();
    }
}
