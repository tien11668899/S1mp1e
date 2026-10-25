package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.HungerSaturationHudModule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the food bar's TRUE on-screen rectangle so {@link HungerSaturationHudModule} can overlay its
 * hidden-saturation glint exactly on top of the drumsticks — wherever they end up.
 *
 * <p>{@code InGameHud.renderFood(DrawContext, PlayerEntity, int top, int right)} (method_58477, verified
 * yarn 1.21.1+build.3) receives the food bar's logical {@code top} (Y) and {@code right} (right-edge X)
 * each frame; the bar's logical rect is {@code [right-81, top] .. [right, top+9]} (10 icons, 9px tall).
 * But that is NOT where the bar is drawn: our own {@code InGameHudMixin} lifts the status-bar cluster by
 * {@code DECO_LIFT} through the RenderSystem model-view, so the bar sits higher. We therefore transform both
 * corners through the LIVE model-view here at HEAD to recover the actual screen rect — self-correcting for
 * that lift (and any future scale/translate), with no hard-coded offset. Pure observe (no cancel).
 */
@Mixin(InGameHud.class)
public class InGameHudFoodMixin {

    @Inject(method = "renderFood", at = @At("HEAD"))
    private void s1mp1e$captureFood(DrawContext context, PlayerEntity player, int top, int right, CallbackInfo ci) {
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        Vector3f a = mv.transformPosition(new Vector3f(right - 81f, top,       0f));
        Vector3f b = mv.transformPosition(new Vector3f(right,       top + 9f,  0f));
        HungerSaturationHudModule.captureFood(a.x, a.y, b.x, b.y);
    }
}
