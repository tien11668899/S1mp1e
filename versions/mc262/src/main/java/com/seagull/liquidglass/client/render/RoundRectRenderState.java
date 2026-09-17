package com.seagull.liquidglass.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * A flat coloured rounded rect with true anti-aliased SDF corners, drawn by {@link GlassPipeline#round()}
 * ({@code glass_round.fsh}) — the 26.2 equivalent of 1.21.1's {@code GlassRenderer.roundRect}.
 *
 * <p>Same quad/UV contract as {@link GlassRectRenderState} (the quad is padded 1px for the AA edge, with UVs
 * normalised to the inner rect), except that the corner radius travels as an integer offset on U — see the
 * shader header: 26.2 batches GUI elements, so a per-draw uniform isn't available. The corner is quantised to
 * 64 steps of the half-size, far finer than any visible difference. Coordinates are FLOAT so animated shapes
 * (the liquid-glass knob) move sub-pixel instead of snapping; int callers widen implicitly.
 */
public final class RoundRectRenderState implements GuiElementRenderState {

   private static final float PAD = 1.0F;

   private final RenderPipeline pipeline;
   private final Matrix3x2fc pose;
   private final float x0;
   private final float y0;
   private final float x1;
   private final float y1;
   private final int color;
   private final int cornerStep;   // 0..63
   @Nullable
   private final ScreenRectangle scissor;
   @Nullable
   private final ScreenRectangle bounds;

   /**
    * @param radiusPx corner radius in GUI px (clamped to half the short side, i.e. at most a full capsule)
    * @param color    packed ARGB fill (straight alpha)
    * @param scissor  clip rect, or null
    */
   public RoundRectRenderState(RenderPipeline pipeline, Matrix3x2fc pose, float x0, float y0, float x1, float y1,
                               float radiusPx, int color, @Nullable ScreenRectangle scissor) {
      this.pipeline = pipeline;
      this.pose = new Matrix3x2f(pose);
      this.x0 = x0;
      this.y0 = y0;
      this.x1 = x1;
      this.y1 = y1;
      this.color = color;
      float half = Math.min(x1 - x0, y1 - y0) / 2.0F;
      float corner = half <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, radiusPx / half));
      this.cornerStep = Math.round(corner * 63.0F);
      this.scissor = scissor;
      int bx0 = (int)Math.floor(x0 - PAD);
      int by0 = (int)Math.floor(y0 - PAD);
      int bx1 = (int)Math.ceil(x1 + PAD);
      int by1 = (int)Math.ceil(y1 + PAD);
      ScreenRectangle b = new ScreenRectangle(bx0, by0, Math.max(1, bx1 - bx0), Math.max(1, by1 - by0)).transformMaxBounds(this.pose);
      this.bounds = scissor != null ? scissor.intersection(b) : b;
   }

   public void buildVertices(VertexConsumer vc) {
      float w = Math.max(this.x1 - this.x0, 1.0F);
      float h = Math.max(this.y1 - this.y0, 1.0F);
      float off = 4.0F * this.cornerStep;                 // corner code, stripped again in glass_round.fsh
      float u0 = -PAD / w + off;
      float u1 = 1.0F + PAD / w + off;
      float v0 = -PAD / h;
      float v1 = 1.0F + PAD / h;
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
