package com.seagull.liquidglass.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline.Snippet;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.seagull.liquidglass.client.LiquidGlassClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

public final class GlassPipeline {
   private static RenderPipeline glass;
   private static RenderPipeline line;
   private static RenderPipeline btn;
   /** Flat AA rounded rect (glass_round.fsh) — the 26.2 port of 1.21.1's ROUND program. */
   private static RenderPipeline round;
   private static int state = 0;
   private static RenderPipeline fade;
   private static GpuTexture snapTex;
   private static GpuTextureView snapView;
   private static int sw0;
   private static int sh0;
   private static GpuTexture grabTex;
   private static GpuTextureView grabView;
   private static GpuSampler sampler;
   private static int gw;
   private static int gh;

   private GlassPipeline() {
   }

   public static RenderPipeline glass() {
      return glass;
   }

   public static RenderPipeline line() {
      return line;
   }

   public static boolean lineUsable() {
      return state == 1 && line != null;
   }

   public static RenderPipeline btn() {
      return btn;
   }

   public static RenderPipeline fade() {
      return fade;
   }

   public static boolean fadeUsable() {
      return state == 1 && fade != null && snapView != null;
   }

   public static GpuTextureView snapshotView() {
      return snapView;
   }

   public static RenderPipeline round() {
      return round;
   }

   public static boolean roundUsable() {
      return state == 1 && round != null;
   }

   public static boolean btnUsable() {
      return state == 1 && btn != null;
   }

   private static String readResource(String path) {
      try {
         String var2;
         try (InputStream in = GlassPipeline.class.getResourceAsStream(path)) {
            var2 = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
         }

         return var2;
      } catch (Exception var6) {
         return null;
      }
   }

   public static boolean ensureReady() {
      if (state != 0) {
         return state == 1;
      } else {
         try {
            glass = RenderPipeline.builder(new Snippet[0])
               .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/glass"))
               .withBindGroupLayout(BindGroupLayouts.GLOBALS)
               .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
               .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
               .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
               .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
               .withPrimitiveTopology(PrimitiveTopology.QUADS)
               .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
               .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
               .build();
            GpuDevice dev = RenderSystem.getDevice();
            ShaderSource src = (id, type) -> {
               if ("liquidglass".equals(id.getNamespace())) {
                  String ext = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
                  return readResource("/assets/liquidglass/shaders/" + id.getPath() + ext);
               } else {
                  return Minecraft.getInstance().getShaderManager().getShader(id, type);
               }
            };
            CompiledRenderPipeline compiled = dev.precompilePipeline(glass, src);
            if (compiled == null || !compiled.isValid()) {
               throw new IllegalStateException("glass pipeline did not compile (invalid)");
            }

            try {
               RenderPipeline lp = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/glass_line"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass_line"))
                  .build();
               CompiledRenderPipeline lc = dev.precompilePipeline(lp, src);
               if (lc != null && lc.isValid()) {
                  line = lp;
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] line pipeline invalid, slot separators fall back to outlines");
               }
            } catch (Throwable var7) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] line pipeline unavailable: {}", var7.toString());
            }

            try {
               RenderPipeline bp = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/glass_btn"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass_btn"))
                  .build();
               CompiledRenderPipeline bc = dev.precompilePipeline(bp, src);
               if (bc != null && bc.isValid()) {
                  btn = bp;
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] button pipeline invalid, buttons stay vanilla");
               }
            } catch (Throwable var6) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] button pipeline unavailable: {}", var6.toString());
            }

            try {
               RenderPipeline rp = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/glass_round"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass_round"))
                  .build();
               CompiledRenderPipeline rc = dev.precompilePipeline(rp, src);
               if (rc != null && rc.isValid()) {
                  round = rp;
                  LiquidGlassClient.LOG.info("[LiquidGlass] round pipeline compiled (AA rounded rects)");
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] round pipeline invalid, rounded fills use stepped fallback");
               }
            } catch (Throwable var9) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] round pipeline unavailable: {}", var9.toString());
            }

            try {
               RenderPipeline fp = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/glass_fade"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass_fade"))
                  .build();
               CompiledRenderPipeline fc = dev.precompilePipeline(fp, src);
               if (fc != null && fc.isValid()) {
                  fade = fp;
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] fade pipeline invalid, screen dissolve off");
               }
            } catch (Throwable var5) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] fade pipeline unavailable: {}", var5.toString());
            }

            sampler = dev.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            state = 1;
            LiquidGlassClient.LOG.info("[LiquidGlass] glass pipeline compiled - real refraction ACTIVE");
         } catch (Throwable var8) {
            state = -1;
            LiquidGlassClient.LOG.warn("[LiquidGlass] glass pipeline unavailable, using primitive fallback: {}", var8.toString());
         }

         return state == 1;
      }
   }

   public static void grabBackdrop() {
      if (state == 1) {
         try {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int w = main.width;
            int h = main.height;
            if (w <= 0 || h <= 0) {
               return;
            }

            GpuDevice dev = RenderSystem.getDevice();
            if (grabTex == null || gw != w || gh != h) {
               if (grabView != null) {
                  grabView.close();
                  grabView = null;
               }

               if (grabTex != null) {
                  grabTex.close();
                  grabTex = null;
               }

               grabTex = dev.createTexture("liquidglass_backdrop", 5, main.getColorTexture().getFormat(), w, h, 1, 1);
               grabView = dev.createTextureView(grabTex);
               gw = w;
               gh = h;
            }

            dev.createCommandEncoder().copyTextureToTexture(main.getColorTexture(), grabTex, 0, 0, 0, 0, 0, w, h);
         } catch (Throwable var4) {
            state = -1;
            LiquidGlassClient.LOG.warn("[LiquidGlass] backdrop grab failed, disabling refraction: {}", var4.toString());
         }
      }
   }

   public static void grabSnapshot() {
      if (state == 1) {
         try {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int w = main.width;
            int h = main.height;
            if (w <= 0 || h <= 0) {
               return;
            }

            GpuDevice dev = RenderSystem.getDevice();
            if (snapTex == null || sw0 != w || sh0 != h) {
               if (snapView != null) {
                  snapView.close();
                  snapView = null;
               }

               if (snapTex != null) {
                  snapTex.close();
                  snapTex = null;
               }

               snapTex = dev.createTexture("liquidglass_snapshot", 5, main.getColorTexture().getFormat(), w, h, 1, 1);
               snapView = dev.createTextureView(snapTex);
               sw0 = w;
               sh0 = h;
            }

            dev.createCommandEncoder().copyTextureToTexture(main.getColorTexture(), snapTex, 0, 0, 0, 0, 0, w, h);
         } catch (Throwable var4) {
            LiquidGlassClient.LOG.warn("[LiquidGlass] snapshot failed: {}", var4.toString());
         }
      }
   }

   public static boolean usable() {
      return state == 1 && grabView != null && sampler != null;
   }

   public static GpuTextureView backdropView() {
      return grabView;
   }

   public static GpuSampler sampler() {
      return sampler;
   }
}
