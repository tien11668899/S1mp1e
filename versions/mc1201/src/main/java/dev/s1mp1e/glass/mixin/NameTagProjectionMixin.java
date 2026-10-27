package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.NameTagGlass;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands this frame's world projection matrix to {@link NameTagGlass} at {@code WorldRenderer.render} HEAD, so the Glass
 * name-tag mode can project each label's world-space pose to a GUI screen rect. Also marks the frame boundary (clears
 * last frame's captured tags).
 *
 * <p>1.20.1 multiplies the camera's view rotation into the {@code MatrixStack} it passes here (GameRenderer.renderWorld
 * rotates it by pitch and yaw + 180 first) and renders entities on that same stack, so the label pose captured later
 * already carries the view rotation; the last argument is the projection (with view bobbing). The view-rotation matrix
 * handed to {@link NameTagGlass#beginFrame} is therefore the identity ({@code projection * pose} reaches clip space),
 * unlike 1.21.1, which keeps the rotation in RenderSystem's model-view and passes it separately.
 *
 * <p>Inert unless the {@code NameTags} module's Glass mode actually captures a label later this frame; it only copies
 * two matrices each frame, so there is no cost when the mode is off.
 */
@Mixin(WorldRenderer.class)
public abstract class NameTagProjectionMixin {
    private static final Matrix4f S1MP1E$IDENTITY = new Matrix4f();

    @Inject(
        method = "render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;)V",
        at = @At("HEAD")
    )
    private void s1mp1e$nameTagProjection(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline,
                                          Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager,
                                          Matrix4f projectionMatrix, CallbackInfo ci) {
        NameTagGlass.beginFrame(S1MP1E$IDENTITY, projectionMatrix);
    }
}
