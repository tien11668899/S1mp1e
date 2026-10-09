package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.s1mp1e.client.gui.NameTagGlass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frame boundary for the Glass name-tag path at {@code LevelRenderer.render} HEAD (clears last frame's
 * captured tags before this frame's entity render re-captures them).
 *
 * <p>26.3 note: unlike 26.2, 26.3's {@code render} has NO {@code Matrix4fc} projection argument (renderpearl uploads the
 * projection as a {@code GpuBufferSlice}). The projection is therefore NOT read here — {@link NameTagGlass#capture} takes
 * it from the {@code CameraRenderState.projectionMatrix} passed to {@code submitNameTag} instead. This hook only resets
 * the per-frame list, so no cost when the Glass mode is off.
 */
@Mixin(LevelRenderer.class)
public abstract class NameTagProjectionMixin {

   @Inject(
      method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
      at = @At("HEAD")
   )
   private void lg$nameTagFrame(GraphicsResourceAllocator alloc, boolean renderBlockOutline, CameraRenderState cam,
                                GpuBufferSlice fogBuffer, Vector4f fogColor, boolean sky, boolean flag, CallbackInfo ci) {
      NameTagGlass.beginFrame();
   }
}
