package com.seagull.liquidglass.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * A flat anti-aliased arc with round caps (ring_arc.fsh): centre ({@code cx},{@code cy}), outer size {@code outerR},
 * stroke width {@code thickness}, drawn from 12 o'clock over {@code |progress|} (clockwise for a positive progress,
 * counter-clockwise for a negative one) on a shape: 0 circle, 1 rounded square, 2 plus (arm half-width / reach =
 * {@code ratio}). The parameters ride on the UV's integer offsets (slot width 2), stripped again in the shader:
 * u + 2*(shape*600 + progress*250), v + 2*(ratioCode*201 + thickness/outer*200).
 */
public final class RingArcRenderState implements GuiElementRenderState {

   private static final float PAD = 1.0F;

   private final RenderPipeline pipeline;
   private final Matrix3x2fc pose;
   private final float x0;
   private final float y0;
   private final float x1;
   private final float y1;
   private final int color;
   private final int kx;
   private final int ky;
   @Nullable
   private final ScreenRectangle scissor;
   @Nullable
   private final ScreenRectangle bounds;

   public RingArcRenderState(RenderPipeline pipeline, Matrix3x2fc pose, float cx, float cy, float outerR, float thickness,
                             float progress, int color, @Nullable ScreenRectangle scissor) {
      this(pipeline, pose, cx, cy, outerR, thickness, progress, 0, 0.45F, 0.0F, color, scissor);
   }

   public RingArcRenderState(RenderPipeline pipeline, Matrix3x2fc pose, float cx, float cy, float outerR, float thickness,
                             float progress, int shape, float ratio, int color, @Nullable ScreenRectangle scissor) {
      this(pipeline, pose, cx, cy, outerR, thickness, progress, shape, ratio, 0.0F, color, scissor);
   }

   public RingArcRenderState(RenderPipeline pipeline, Matrix3x2fc pose, float cx, float cy, float outerR, float thickness,
                             float progress, int shape, float ratio, float gap, int color, @Nullable ScreenRectangle scissor) {
      this.pipeline = pipeline;
      this.pose = new Matrix3x2f(pose);
      this.x0 = cx - outerR;
      this.y0 = cy - outerR;
      this.x1 = cx + outerR;
      this.y1 = cy + outerR;
      this.color = color;
      int shapeCode = Math.max(0, Math.min(2, shape));
      this.kx = shapeCode * 600 + Math.round(Math.max(-1.0F, Math.min(1.0F, progress)) * 250.0F);
      int thickCode = Math.round(Math.max(0.02F, Math.min(1.0F, outerR <= 0.0F ? 1.0F : thickness / outerR)) * 200.0F);
      this.ky = GlassRing.gapCode(gap) * 3216 + GlassRing.ratioCode(ratio) * 201 + thickCode;
      this.scissor = scissor;
      int bx0 = (int)Math.floor(this.x0 - PAD);
      int by0 = (int)Math.floor(this.y0 - PAD);
      int bx1 = (int)Math.ceil(this.x1 + PAD);
      int by1 = (int)Math.ceil(this.y1 + PAD);
      ScreenRectangle b = new ScreenRectangle(bx0, by0, Math.max(1, bx1 - bx0), Math.max(1, by1 - by0)).transformMaxBounds(this.pose);
      this.bounds = scissor != null ? scissor.intersection(b) : b;
   }

   public void buildVertices(VertexConsumer vc) {
      float w = Math.max(this.x1 - this.x0, 1.0F);
      float h = Math.max(this.y1 - this.y0, 1.0F);
      float offU = 2.0F * this.kx;
      float offV = 2.0F * this.ky;
      float u0 = -PAD / w + offU;
      float u1 = 1.0F + PAD / w + offU;
      float v0 = -PAD / h + offV;
      float v1 = 1.0F + PAD / h + offV;
      float qx0 = this.x0 - PAD;
      float qy0 = this.y0 - PAD;
      float qx1 = this.x1 + PAD;
      float qy1 = this.y1 + PAD;
      vc.addVertexWith2DPose(this.pose, qx0, qy0).setUv(u0, v0).setColor(this.color);
      vc.addVertexWith2DPose(this.pose, qx0, qy1).setUv(u0, v1).setColor(this.color);
      vc.addVertexWith2DPose(this.pose, qx1, qy1).setUv(u1, v1).setColor(this.color);
      vc.addVertexWith2DPose(this.pose, qx1, qy0).setUv(u1, v0).setColor(this.color);
   }

   public RenderPipeline pipeline() {
      return this.pipeline;
   }

   public TextureSetup textureSetup() {
      return TextureSetup.noTexture();
   }

   @Nullable
   public ScreenRectangle scissorArea() {
      return this.scissor;
   }

   @Nullable
   public ScreenRectangle bounds() {
      return this.bounds;
   }
}
