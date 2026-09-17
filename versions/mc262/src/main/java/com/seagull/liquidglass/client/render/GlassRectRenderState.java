package com.seagull.liquidglass.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

public final class GlassRectRenderState implements GuiElementRenderState {
   private final RenderPipeline pipeline;
   private final TextureSetup textureSetup;
   private final Matrix3x2fc pose;
   private final int x0;
   private final int y0;
   private final int x1;
   private final int y1;
   private final int color;
   @Nullable
   private final ScreenRectangle scissor;
   @Nullable
   private final ScreenRectangle bounds;
   private final int pad;

   public GlassRectRenderState(
      RenderPipeline pipeline,
      TextureSetup textureSetup,
      Matrix3x2fc pose,
      int x0,
      int y0,
      int x1,
      int y1,
      int pad,
      int color,
      @Nullable ScreenRectangle scissor
   ) {
      this.pipeline = pipeline;
      this.textureSetup = textureSetup;
      this.pose = new Matrix3x2f(pose);
      this.x0 = x0;
      this.y0 = y0;
      this.x1 = x1;
      this.y1 = y1;
      this.pad = pad;
      this.color = color;
      this.scissor = scissor;
      ScreenRectangle b = new ScreenRectangle(x0 - pad, y0 - pad, x1 - x0 + 2 * pad, y1 - y0 + 2 * pad).transformMaxBounds(this.pose);
      this.bounds = scissor != null ? scissor.intersection(b) : b;
   }

   public void buildVertices(VertexConsumer vc) {
      float w = (float)Math.max(this.x1 - this.x0, 1);
      float h = (float)Math.max(this.y1 - this.y0, 1);
      float u0 = (float)(-this.pad) / w;
      float u1 = 1.0F + (float)this.pad / w;
      float v0 = (float)(-this.pad) / h;
      float v1 = 1.0F + (float)this.pad / h;
      float qx0 = (float)(this.x0 - this.pad);
      float qy0 = (float)(this.y0 - this.pad);
      float qx1 = (float)(this.x1 + this.pad);
      float qy1 = (float)(this.y1 + this.pad);
      vc.addVertexWith2DPose(this.pose, qx0, qy0).setUv(u0, v0).setColor(this.color);
      vc.addVertexWith2DPose(this.pose, qx0, qy1).setUv(u0, v1).setColor(this.color);
      vc.addVertexWith2DPose(this.pose, qx1, qy1).setUv(u1, v1).setColor(this.color);
      vc.addVertexWith2DPose(this.pose, qx1, qy0).setUv(u1, v0).setColor(this.color);
   }

   public RenderPipeline pipeline() {
      return this.pipeline;
   }

   public TextureSetup textureSetup() {
      return this.textureSetup;
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
