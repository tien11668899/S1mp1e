package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.NameTagGlass;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands this frame's world view-rotation + projection matrices to {@link NameTagGlass} at {@code WorldRenderer.render}
 * HEAD, so the Glass name-tag mode can project each label's world-space pose to a GUI screen rect. Also marks the frame
 * boundary (clears last frame's captured tags).
 *
 * <p>1.21.1 renders the world by pushing {@code positionMatrix} (the 6th arg, the camera view rotation) onto
 * {@code RenderSystem}'s model-view stack and building entities on a fresh {@code MatrixStack}; the 7th arg is the
 * perspective projection. The label pose captured later is therefore camera-relative WITHOUT the view rotation, so
 * {@link NameTagGlass#capture} needs both matrices to reach clip space ({@code projection * viewRotation * pose}).
 *
 * <p>Inert unless the {@code NameTags} module's Glass mode actually captures a label later this frame; it only copies
 * two matrices each frame, so there is no cost when the mode is off.
 */
@Mixin(WorldRenderer.class)
public abstract class NameTagProjectionMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
        at = @At("HEAD")
    )
    private void s1mp1e$nameTagProjection(RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera,
                                          GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager,
                                          Matrix4f positionMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        NameTagGlass.beginFrame(positionMatrix, projectionMatrix);
    }
}
