package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.s1mp1e.client.gui.NameTagGlass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands this frame's world PROJECTION matrix (the 5th {@code render} argument, {@code Matrix4fc}) to
 * {@link NameTagGlass} at {@code LevelRenderer.render} HEAD, so the Glass name-tag mode can project each tag's
 * world-space pose to a GUI screen rect. Also marks the frame boundary (clears last frame's captured tags).
 *
 * <p>Inert unless the {@code NameTags} module's Glass mode actually captures a tag later this frame; it only stores a
 * matrix reference each frame, so there is no cost when the mode is off.
 */
@Mixin(LevelRenderer.class)
public abstract class NameTagProjectionMixin {

   @Inject(
      method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
      at = @At("HEAD")
   )
   private void lg$nameTagProjection(GraphicsResourceAllocator alloc, DeltaTracker delta, boolean renderBlockOutline,
                                     CameraRenderState cam, Matrix4fc projection, GpuBufferSlice fogBuffer,
                                     Vector4f fogColor, boolean sky, CallbackInfo ci) {
      NameTagGlass.beginFrame();
   }
}
