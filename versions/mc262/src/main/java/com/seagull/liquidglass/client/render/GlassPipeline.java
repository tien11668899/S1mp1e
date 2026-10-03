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

   /**
    * Textures replaced after a resize are closed two frames later, not at once. GUI elements are extracted (and name
    * their texture view) BEFORE {@link #grabBackdrop()} runs in GuiRenderer.render, so closing the old view right there
    * left that frame's draws pointing at a closed view: "Texture view Sampler0 (liquidglass_backdrop) has been closed!"
    * the moment the window changed size (resize, fullscreen, a 4K window).
    */
   private static final java.util.ArrayList<Object[]> retired = new java.util.ArrayList<>();
   private static long frameNo;

   private static void retire(GpuTextureView view, GpuTexture tex) {
      if (view != null || tex != null) retired.add(new Object[]{frameNo, view, tex});
   }

   /** Close what was retired at least two frames ago. Called once a frame from {@link #grabBackdrop()}. */
   private static void closeRetired() {
      for (int i = retired.size() - 1; i >= 0; i--) {
         Object[] r = retired.get(i);
         if (frameNo - (Long) r[0] < 2) continue;
         try { if (r[1] != null) ((GpuTextureView) r[1]).close(); } catch (Throwable ignored) { }
         try { if (r[2] != null) ((GpuTexture) r[2]).close(); } catch (Throwable ignored) { }
         retired.remove(i);
      }
   }
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
            if (Boolean.getBoolean("s1mp1e.bench.noglass")) {   // benchmark only: measure the cost of real refraction
               state = -1;
               LiquidGlassClient.LOG.info("[LiquidGlass] -Ds1mp1e.bench.noglass -> primitive glass (benchmark)");
               return false;
            }
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

   /** Benchmark only: skip the per-frame backdrop copy to measure what it costs (the glass then shows stale content). */
   private static final boolean BENCH_NO_COPY = Boolean.getBoolean("s1mp1e.bench.nocopy");

   /**
    * Copy only the parts of the frame the glass reads, instead of the whole frame. A glass fragment samples the backdrop
    * at its own position plus the refraction offset, which is at most 0.08 * edgeFactor * screen height with
    * edgeFactor = tan(thetaI - thetaT) <= tan(90deg - asin(1/1.4)) = 0.98 (glass.fsh, IOR 1.4), plus the 3x3 frost
    * kernel (<= 4 px each way). So every element that samples the backdrop gets its bounds widened by that margin;
    * overlapping boxes are merged; everything else in the backdrop texture is never read.
    * On by default (4K benchmark: explore 661 -> 697 FPS, chunk loading 661 -> 707, identical picture; 723 debug
    * screenshots with no glass pixel reading outside the copied boxes). {@code -Ds1mp1e.glass.fullCopy=true} turns it
    * off; {@code -Ds1mp1e.glass.copyDebug=true} paints the backdrop magenta first, so any glass pixel that reads outside
    * the copied boxes shows up magenta in a screenshot.
    */
   private static final boolean PARTIAL_COPY = !Boolean.getBoolean("s1mp1e.glass.fullCopy");
   private static final boolean COPY_DEBUG = Boolean.getBoolean("s1mp1e.glass.copyDebug");
   private static final Vector4f DEBUG_MAGENTA = new Vector4f(1.0F, 0.0F, 1.0F, 1.0F);
   /** Boxes in GUI coordinates (x0, y0, x1, y1) of this frame's backdrop readers; null = copy the whole frame. */
   private static int[] readers = new int[256];
   private static int readerCount = -1;   // -1: unknown this frame -> full copy

   /** Called at GuiRenderer.render HEAD, before {@link #grabBackdrop()}, with the fully extracted GUI of this frame. */
   public static void collectReaders(net.minecraft.client.renderer.state.gui.GuiRenderState rs) {
      readerCount = -1;
      if (!PARTIAL_COPY || state != 1 || grabView == null || rs == null) {
         return;
      }
      final int[] n = {0};
      final boolean[] unknown = {false};
      final GpuTextureView view = grabView;
      try {
         rs.forEachElement(e -> {
            net.minecraft.client.gui.render.TextureSetup ts = e.textureSetup();
            if (ts == null || (ts.texure0() != view && ts.texure1() != view && ts.texure2() != view)) {
               return;
            }
            net.minecraft.client.gui.navigation.ScreenRectangle b = e.bounds();
            if (b == null) { unknown[0] = true; return; }
            if (n[0] * 4 + 4 > readers.length) readers = java.util.Arrays.copyOf(readers, readers.length * 2);
            int i = n[0]++ * 4;
            readers[i] = b.left(); readers[i + 1] = b.top(); readers[i + 2] = b.right(); readers[i + 3] = b.bottom();
         }, net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange.ALL);
      } catch (Throwable t) {
         return;   // unknown -> full copy
      }
      if (!unknown[0]) readerCount = n[0];
   }

   private static int copyImage = -1;   // -1 unknown, 0 no, 1 yes

   /** glCopyImageSubData needs OpenGL 4.3 or ARB_copy_image (every current desktop driver; checked once). */
   private static boolean copyImageAvailable() {
      if (copyImage < 0) {
         try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            copyImage = (caps.OpenGL43 || caps.GL_ARB_copy_image) && caps.glCopyImageSubData != 0L ? 1 : 0;
         } catch (Throwable t) {
            copyImage = 0;
         }
         LiquidGlassClient.LOG.info("[LiquidGlass] partial backdrop copy: {}", copyImage == 1 ? "available" : "unavailable, full copy");
      }
      return copyImage == 1;
   }

   /** Copy the boxes the glass reads this frame. False = copy everything instead. */
   private static boolean copyReaders(GpuDevice dev, RenderTarget main, int w, int h) {
      if (readerCount < 0 || !copyImageAvailable() || !(main.getColorTexture() instanceof com.mojang.blaze3d.opengl.GlTexture src)
            || !(grabTex instanceof com.mojang.blaze3d.opengl.GlTexture dst)) {
         return false;
      }
      var enc = dev.createCommandEncoder();
      if (COPY_DEBUG) enc.clearColorTexture(grabTex, DEBUG_MAGENTA);
      if (readerCount == 0) {
         return true;   // no glass reads the backdrop this frame
      }
      int scale = Minecraft.getInstance().getWindow().getGuiScale();
      int margin = (int) Math.ceil(0.08 * 0.98 * h) + 8;
      // widen to pixels, then merge overlapping boxes until none overlap
      java.util.ArrayList<int[]> boxes = new java.util.ArrayList<>(readerCount);
      for (int k = 0; k < readerCount; k++) {
         int i = k * 4;
         int x0 = Math.max(0, readers[i] * scale - margin), x1 = Math.min(w, readers[i + 2] * scale + margin);
         int y0 = Math.max(0, readers[i + 1] * scale - margin), y1 = Math.min(h, readers[i + 3] * scale + margin);
         if (x1 > x0 && y1 > y0) boxes.add(new int[]{x0, y0, x1, y1});
      }
      boolean merged = true;
      while (merged) {
         merged = false;
         outer:
         for (int a = 0; a < boxes.size(); a++) {
            for (int b = a + 1; b < boxes.size(); b++) {
               int[] p = boxes.get(a), q = boxes.get(b);
               if (p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3]) {
                  p[0] = Math.min(p[0], q[0]); p[1] = Math.min(p[1], q[1]);
                  p[2] = Math.max(p[2], q[2]); p[3] = Math.max(p[3], q[3]);
                  boxes.remove(b);
                  merged = true;
                  break outer;
               }
            }
         }
      }
      long area = 0;
      for (int[] bx : boxes) area += (long) (bx[2] - bx[0]) * (bx[3] - bx[1]);
      if (!COPY_DEBUG && area * 10 >= (long) w * h * 9) {
         return false;   // nearly the whole frame anyway: one copy is cheaper than many
      }
      for (int[] bx : boxes) {
         // GL texture rows count up from the bottom, GUI y counts down. Not CommandEncoder.copyTextureToTexture: its
         // blit passes width/height as the END coordinates, so only boxes starting at (0, 0) come out right.
         int ty = h - bx[3];
         org.lwjgl.opengl.GL43C.glCopyImageSubData(src.glId(), org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, bx[0], ty, 0,
               dst.glId(), org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, bx[0], ty, 0, bx[2] - bx[0], bx[3] - bx[1], 1);
      }
      return true;
   }

   public static void grabBackdrop() {
      if (state == 1) {
         frameNo++;
         closeRetired();
         try {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int w = main.width;
            int h = main.height;
            if (w <= 0 || h <= 0) {
               return;
            }

            GpuDevice dev = RenderSystem.getDevice();
            if (grabTex == null || gw != w || gh != h) {
               retire(grabView, grabTex);   // this frame's GUI elements were extracted with the old view
               grabView = null;
               grabTex = null;

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
            } else if (!BENCH_NO_COPY && !copyReaders(dev, main, w, h)) {
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
               retire(snapView, snapTex);
               snapView = null;
               snapTex = null;

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
            retire(overlayView, overlayTex);
            overlayView = null;
            overlayTex = null;
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
