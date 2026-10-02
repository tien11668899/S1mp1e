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
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.seagull.liquidglass.client.LiquidGlassClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

public final class GlassPipeline {
   private static RenderPipeline glass;
   /** Refracting glass for the creative TAB row, top / bottom variants: identical to {@link #glass} but each reflects the
    *  backdrop sample across the panel-side edge (glass_tab_top/bot.fsh) so a tab fused to the panel refracts the SAME
    *  terrain the panel does, even where it protrudes past the panel — see {@link com.seagull.liquidglass.client.render.GlassTabs}. */
   private static RenderPipeline tabTop;
   private static RenderPipeline tabBot;
   /** Refracting glass with TRUE capsule ends (glass_capsule.fsh: glass.fsh with CORNER_FRAC 1.0) - e.g. the boss bar. */
   private static RenderPipeline capsule;
   /** Refracting glass RING (glass_ring.fsh: annulus SDF) - the attack-cooldown ring track. */
   private static RenderPipeline ringGlass;
   /** Flat AA ARC with round caps (ring_arc.fsh) - the attack-cooldown progress. */
   private static RenderPipeline ringArc;
   /** Refracting LENS (glass_lens.fsh): glass + full-capsule corner, for the switch knob / slider thumb. */
   private static RenderPipeline lens;
   private static RenderPipeline line;
   private static RenderPipeline btn;
   /** Flat AA rounded rect (glass_round.fsh) — the 26.2 port of 1.21.1's ROUND program. */
   private static RenderPipeline round;
   private static int state = 0;
   private static RenderPipeline fade;
   /** The S1mp1e brand intro (s1mp1e_intro.fsh): a full-screen procedural animation; time on UV0.x, mode on UV0.y,
    *  overall opacity on the vertex alpha, the headline strip on Sampler0. Used by the boot / world-entry screens. */
   private static RenderPipeline intro;
   private static GpuTexture snapTex;
   private static GpuTextureView snapView;
   private static int sw0;
   private static int sh0;
   private static GpuTexture grabTex;
   private static GpuTextureView grabView;
   private static GpuSampler sampler;
   private static int gw;
   private static int gh;
   /** "GUI drawn so far" backdrop for the TOP-layer glass (hover tooltip card): filled mid-GUI-draw by {@link #grabOverlay()}
    *  right before the tooltip stratum is drawn, so the card refracts the inventory / items actually beneath it (the
    *  {@link #backdropView()} grab is taken before ANY GUI is drawn, i.e. world only). See {@link TooltipLayer}. */
   private static GpuTexture overlayTex;
   private static GpuTextureView overlayView;
   private static int ow;
   private static int oh;
   private static boolean overlayFailed;

   private GlassPipeline() {
   }

   public static RenderPipeline glass() {
      return glass;
   }

   public static RenderPipeline tabTop() {
      return tabTop;
   }

   public static RenderPipeline tabBot() {
      return tabBot;
   }

   public static RenderPipeline capsule() {
      return capsule;
   }

   public static RenderPipeline ringGlass() {
      return ringGlass;
   }

   public static boolean ringGlassUsable() {
      return state == 1 && ringGlass != null && grabView != null && sampler != null;
   }

   public static RenderPipeline ringArc() {
      return ringArc;
   }

   public static boolean ringArcUsable() {
      return state == 1 && ringArc != null;
   }

   /** The capsule program refracts the backdrop, so gate exactly like {@link #usable()}. */
   public static boolean capsuleUsable() {
      return state == 1 && capsule != null && grabView != null && sampler != null;
   }

   /** The creative-tab pipelines refract the backdrop (need SAMPLER0 + a live grab), so gate exactly like {@link #usable()}. */
   public static boolean tabUsable() {
      return state == 1 && tabTop != null && tabBot != null && grabView != null && sampler != null;
   }

   public static RenderPipeline lens() {
      return lens;
   }

   public static boolean lensUsable() {
      return state == 1 && lens != null && grabView != null && sampler != null;
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

   public static RenderPipeline intro() {
      return intro;
   }

   public static boolean introUsable() {
      return state == 1 && intro != null;
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

   /**
    * Build + precompile a backdrop-sampling program (same bind groups / vertex format / blend as {@link #glass}, its own
    * fragment shader). Returns the pipeline only if it compiled valid, else {@code null} (the caller keeps the plain glass
    * fallback). Used for the creative-tab variants so their one-line sampling difference does not duplicate the builder.
    */
   private static RenderPipeline buildBackdropProgram(GpuDevice dev, ShaderSource src, String fsh) {
      RenderPipeline p = RenderPipeline.builder(new Snippet[0])
         .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/" + fsh))
         .withBindGroupLayout(BindGroupLayouts.GLOBALS)
         .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
         .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
         .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
         .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
         .withPrimitiveTopology(PrimitiveTopology.QUADS)
         .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
         .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/" + fsh))
         .build();
      CompiledRenderPipeline c = dev.precompilePipeline(p, src);
      return c != null && c.isValid() ? p : null;
   }

   public static boolean ensureReady() {
      if (state != 0) {
         return state == 1;
      } else {
         try {
            GpuDevice dev = RenderSystem.getDevice();
            // 26.2 replaced the fixed OpenGL renderer with a GpuBackend abstraction and shipped an
            // experimental, user-selectable Vulkan backend. Our backdrop-sampling shaders
            // (glass / glass_lens / glass_fade) read Sampler0 at gl_FragCoord.xy / ScreenSize
            // assuming OpenGL's BOTTOM-left framebuffer origin (see the rationale in glass.fsh:88-92).
            // On Vulkan the fragment origin is TOP-left, so the refraction would sample the scene
            // vertically MIRRORED, and the custom GLSL->SPIR-V path is untested on this build. Until the
            // shaders are validated on Vulkan, drop to the coordinate-agnostic primitive painter on any
            // non-OpenGL backend. OpenGL (the default) is unaffected: it keeps full shader refraction.
            //
            // backendName() is the ONLY reliable discriminator here: 26.2's OpenGL backend reports a
            // unified [0,1] clip-Z (isZZeroToOne()==true) exactly like Vulkan, so isZZeroToOne is NOT a
            // backend signal (verified at runtime: backend='OpenGL' yet zZeroToOne=true, and the
            // bottom-left gl_FragCoord assumption still renders correctly there). A null/unknown name is
            // treated as OpenGL so we never disable the tested-good path on a naming quirk; a genuine
            // shader-compile failure on an odd backend still trips the existing try/catch fallback below.
            DeviceInfo gpu = dev.getDeviceInfo();
            String backend = gpu.backendName();
            LiquidGlassClient.LOG.info("[LiquidGlass] GPU backend='{}' vendor='{}' zZeroToOne={}",
               backend, gpu.vendorName(), gpu.isZZeroToOne());
            boolean openGl = backend == null
               || backend.toLowerCase(Locale.ROOT).contains("opengl");
            if (!openGl) {
               state = -1;
               LiquidGlassClient.LOG.warn("[LiquidGlass] non-OpenGL graphics backend ('{}') -> using primitive glass fallback (shader refraction assumes an OpenGL framebuffer origin; set Options > Graphics API to OpenGL for full liquid glass)", backend);
               return false;
            }
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
               tabTop = buildBackdropProgram(dev, src, "glass_tab_top");
               tabBot = buildBackdropProgram(dev, src, "glass_tab_bot");
               try {
                  capsule = buildBackdropProgram(dev, src, "glass_capsule");
                  ringGlass = buildBackdropProgram(dev, src, "glass_ring");
               } catch (Throwable varCap) {
                  capsule = null;
                  LiquidGlassClient.LOG.warn("[LiquidGlass] capsule pipeline unavailable: {}", varCap.toString());
               }
               if (tabTop != null && tabBot != null) {
                  LiquidGlassClient.LOG.info("[LiquidGlass] creative-tab pipelines compiled (edge-reflected refraction)");
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] creative-tab pipelines invalid, tabs fall back to the plain glass strip");
               }
            } catch (Throwable varTab) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] creative-tab pipelines unavailable: {}", varTab.toString());
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
               RenderPipeline ap = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/ring_arc"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/ring_arc"))
                  .build();
               CompiledRenderPipeline ac = dev.precompilePipeline(ap, src);
               if (ac != null && ac.isValid()) {
                  ringArc = ap;
                  LiquidGlassClient.LOG.info("[LiquidGlass] ring pipelines: arc={} glassRing={}", true, ringGlass != null);
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] ring arc pipeline invalid");
               }
            } catch (Throwable varArc) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] ring arc pipeline unavailable: {}", varArc.toString());
            }

            try {
               RenderPipeline lensP = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/glass_lens"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass_lens"))
                  .build();
               CompiledRenderPipeline lensC = dev.precompilePipeline(lensP, src);
               if (lensC != null && lensC.isValid()) {
                  lens = lensP;
                  LiquidGlassClient.LOG.info("[LiquidGlass] lens pipeline compiled (refracting knob / thumb)");
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] lens pipeline invalid, knob/thumb falls back to frosted white");
               }
            } catch (Throwable varLens) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] lens pipeline unavailable: {}", varLens.toString());
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

            try {
               RenderPipeline ip = RenderPipeline.builder(new Snippet[0])
                  .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/s1mp1e_intro"))
                  .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                  .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                  .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                  .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                  .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                  .withPrimitiveTopology(PrimitiveTopology.QUADS)
                  .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/glass"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("liquidglass", "core/s1mp1e_intro"))
                  .build();
               CompiledRenderPipeline ic = dev.precompilePipeline(ip, src);
               if (ic != null && ic.isValid()) {
                  intro = ip;
                  LiquidGlassClient.LOG.info("[LiquidGlass] intro pipeline compiled (S1mp1e brand intro)");
               } else {
                  LiquidGlassClient.LOG.warn("[LiquidGlass] intro pipeline invalid, boot/world-entry falls back to vanilla");
               }
            } catch (Throwable varIntro) {
               LiquidGlassClient.LOG.warn("[LiquidGlass] intro pipeline unavailable: {}", varIntro.toString());
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

   /** Neutral frost, used only as a fallback when there is no world AND the panorama grab could not run. */
   private static final Vector4f NEUTRAL_BACKDROP = new Vector4f(0.34F, 0.36F, 0.40F, 1.0F);

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

               // usage 13 = COPY_DST | TEXTURE_BINDING | RENDER_ATTACHMENT (the last so it can be cleared, below)
               grabTex = dev.createTexture("liquidglass_backdrop", 13, main.getColorTexture().getFormat(), w, h, 1, 1);
               grabView = dev.createTextureView(grabTex);
               gw = w;
               gh = h;
            }

            // Deferred GUI: this grab (GuiRenderer.render HEAD, before any GUI draws) is the world. In a world that is
            // the right backdrop for every glass panel. With NO world (title screen, options, world select…) the main
            // target is still black here — the menu panorama is drawn LATER, as the first GUI elements. So clear to a
            // neutral frost as a fallback; {@link MenuBackdrop} then re-grabs the framebuffer mid-draw, right before the
            // first glass panel, so the panels actually refract the panorama (see the GuiRenderer.draw split).
            if (Minecraft.getInstance().level == null) {
               dev.createCommandEncoder().clearColorTexture(grabTex, NEUTRAL_BACKDROP);
            } else {
               dev.createCommandEncoder().copyTextureToTexture(main.getColorTexture(), grabTex, 0, 0, 0, 0, 0, w, h);
            }
         } catch (Throwable var4) {
            state = -1;
            LiquidGlassClient.LOG.warn("[LiquidGlass] backdrop grab failed, disabling refraction: {}", var4.toString());
         }
      }
   }

   /**
    * Mid-draw re-grab of the base backdrop from the (partially drawn) framebuffer: used only with NO world, called by
    * {@link MenuBackdrop} at the draw split right before the first glass panel, so the panels refract the menu panorama /
    * background that has by then been drawn. Assumes {@code grabTex} already exists at the current size (created at the
    * HEAD grab this frame). Returns true if the copy ran.
    */
   public static boolean grabBackdropNow() {
      if (state != 1 || grabTex == null) {
         return false;
      }
      try {
         RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
         int w = main.width;
         int h = main.height;
         if (w <= 0 || h <= 0 || w != gw || h != gh) {
            return false;
         }
         RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), grabTex, 0, 0, 0, 0, 0, w, h);
         return true;
      } catch (Throwable t) {
         LiquidGlassClient.LOG.warn("[LiquidGlass] panorama backdrop grab failed: {}", t.toString());
         return false;
      }
   }

   /** True for the pipelines that refract the base backdrop (grabView) — the glass panels, tabs, ring and lens. */
   public static boolean samplesBackdrop(RenderPipeline p) {
      return p != null && (p == glass || p == round || p == tabTop || p == tabBot || p == capsule || p == ringGlass || p == lens);
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

   /**
    * The top-layer backdrop view, (re)created at the main target's size. Called at EXTRACTION time (the card's
    * {@code TextureSetup} needs the view object); its CONTENT is copied later, mid-draw, by {@link #grabOverlay()}.
    * Returns null when unavailable, so callers fall back to the world-only {@link #backdropView()}.
    */
   public static GpuTextureView overlayView() {
      if (state != 1 || overlayFailed) {
         return null;
      }
      try {
         RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
         int w = main.width;
         int h = main.height;
         if (w <= 0 || h <= 0) {
            return null;
         }
         if (overlayTex == null || ow != w || oh != h) {
            if (overlayView != null) {
               overlayView.close();
               overlayView = null;
            }
            if (overlayTex != null) {
               overlayTex.close();
               overlayTex = null;
            }
            GpuDevice dev = RenderSystem.getDevice();
            overlayTex = dev.createTexture("liquidglass_overlay_backdrop", 5, main.getColorTexture().getFormat(), w, h, 1, 1);
            overlayView = dev.createTextureView(overlayTex);
            ow = w;
            oh = h;
         }
         return overlayView;
      } catch (Throwable t) {
         overlayFailed = true;
         LiquidGlassClient.LOG.warn("[LiquidGlass] top-layer backdrop unavailable, tooltip glass refracts the world backdrop: {}", t.toString());
         return null;
      }
   }

   /** The existing top-layer backdrop view without (re)creating it (dev probe). */
   public static GpuTextureView currentOverlayView() {
      return overlayView;
   }

   /** Copy the main target (everything the GUI has drawn so far this frame) into the top-layer backdrop. Called between
    *  two GUI render passes, never inside one. Returns true when the copy was issued. */
   public static boolean grabOverlay() {
      if (state != 1 || overlayTex == null) {
         return false;
      }
      try {
         RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
         if (main.width != ow || main.height != oh) {
            return false;   // resized between extraction and draw: keep last frame's copy for this one frame
         }
         RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), overlayTex, 0, 0, 0, 0, 0, ow, oh);
         return true;
      } catch (Throwable t) {
         overlayFailed = true;
         LiquidGlassClient.LOG.warn("[LiquidGlass] top-layer backdrop grab failed, tooltip glass refracts the world backdrop: {}", t.toString());
         return false;
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
