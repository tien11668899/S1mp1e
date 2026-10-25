package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.s1mp1e.client.gui.NameTagGlass;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands this frame's world PROJECTION matrix and camera VIEW ROTATION to {@link NameTagGlass} at
 * {@code LevelRenderer.renderLevel} HEAD, so the Glass name-tag mode can project each tag's world-space pose to a GUI
 * screen rect. Also marks the frame boundary (clears last frame's captured tags).
 *
 * <p>1.21.9+ (javap): {@code renderLevel(alloc, delta, outline, camera, frustumMatrix, projectionMatrix,
 * cullingProjection, fog, fogColor, sky)} — the {@code frustumMatrix} is the camera rotation it pushes onto the
 * model-view stack; entity poses are camera-relative under it. ({@code CameraRenderState} carries no matrices before
 * 26.x.)
 *
 * <p>Inert unless the {@code NameTags} module's Glass mode actually captures a tag later this frame; it only copies two
 * matrices each frame, so there is no cost when the mode is off.
 */
@Mixin(LevelRenderer.class)
public abstract class NameTagProjectionMixin {

   @Inject(method = "renderLevel", at = @At("HEAD"))
   private void lg$nameTagProjection(GraphicsResourceAllocator alloc, DeltaTracker delta, boolean renderBlockOutline,
                                     Camera camera, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                     Matrix4f cullingProjection, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean sky,
                                     CallbackInfo ci) {
      NameTagGlass.beginFrame(projectionMatrix, frustumMatrix);
   }
}
