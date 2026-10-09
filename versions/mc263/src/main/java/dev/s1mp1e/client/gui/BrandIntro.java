package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import com.seagull.liquidglass.client.render.GlassPipeline;
import java.io.InputStream;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * The S1mp1e brand intro, drawn full-screen through the {@code s1mp1e_intro} pipeline. The whole animation is procedural
 * inside the fragment shader; this class only feeds it three things per frame, all through one full-screen quad:
 * <ul>
 *   <li><b>time</b> on {@code UV0.x} (seconds since the intro began),</li>
 *   <li><b>mode</b> on {@code UV0.y} (0 = the full boot intro from the "Welcome to S1mp1e" headline; 1 = the short cut
 *       from the point of light, for later resource reloads; 2 = the seamless world-entry loop),</li>
 *   <li><b>opacity</b> on the vertex alpha (used to cross-fade the intro out into the screen behind it).</li>
 * </ul>
 * The text the mode needs is bound on Sampler0 as a 16-bit-packed signed distance field ({@code intro_head_sdf.png}
 * for the boot, {@code intro_sdf.png} for the loop), read in the shader with texelFetch. Both callers
 * ({@code LoadingOverlay}, world entry) sit on a black base, so where the shader has drawn nothing it stays black.
 */
public final class BrandIntro {
   private BrandIntro() {
   }

   /**
    * The full intro settles the mark by ~3.45 s (headline glyph by glyph → squeeze to a point → two tiles orbit out
    * and land → the overlap opens) and then breathes.
    * Boot holds at least this long before the loading screen is allowed to fade to the title.
    */
   public static final float HOLD_FULL = 3.9F;
   /** The short cut (world entry) reaches the settled mark by ~1.7 s of real time (the shader offsets +1.80 s itself). */
   public static final float HOLD_SHORT = 1.9F;

   /** Shader textures, 16-bit-packed signed distance fields read with texelFetch: [0] the boot headline "Welcome to
    *  S1mp1e", [1] "S1m" / "p1e" for the world-entry loop. */
   private static final String[] STRIP_NAMES = {"intro_head_sdf", "intro_sdf"};
   private static final GpuTextureView[] STRIP_VIEWS = new GpuTextureView[2];
   private static final boolean[] STRIP_FAILED = new boolean[2];

   /** Load (once) strip {@code i} and return its texture view, or {@code null} if it could not be read. */
   @Nullable
   private static GpuTextureView strip(int i) {
      if (STRIP_VIEWS[i] != null || STRIP_FAILED[i]) {
         return STRIP_VIEWS[i];
      }
      String name = STRIP_NAMES[i];
      try (InputStream in = BrandIntro.class.getResourceAsStream("/assets/liquidglass/textures/gui/" + name + ".png")) {
         if (in != null) {
            NativeImage img = NativeImage.read(in);
            Supplier<String> label = () -> "liquidglass:" + name;
            DynamicTexture tex = new DynamicTexture(label, img);   // ctor uploads + builds the view eagerly
            Minecraft.getInstance().getTextureManager().register(Identifier.fromNamespaceAndPath("liquidglass", name), tex);
            STRIP_VIEWS[i] = tex.getTextureView();
         }
      } catch (Throwable ignored) {
      }
      if (STRIP_VIEWS[i] == null) {
         STRIP_FAILED[i] = true;
      }
      return STRIP_VIEWS[i];
   }

   private static int stripFor(int mode) {
      return mode == MODE_LOOP ? 1 : 0;
   }

   /** True once the intro pipeline and the boot headline strip are ready — the caller falls back to vanilla otherwise. */
   public static boolean ready() {
      return ready(MODE_FULL);
   }

   /** True once the intro pipeline and the strip {@code mode} samples are both ready. */
   public static boolean ready(int mode) {
      return GlassPipeline.ensureReady() && GlassPipeline.introUsable() && strip(stripFor(mode)) != null;
   }

   /** Shader modes (UV0.y): the full boot intro, the short cut from the point of light, the seamless world-entry loop. */
   public static final int MODE_FULL = 0;
   public static final int MODE_SHORT = 1;
   public static final int MODE_LOOP = 2;

   /**
    * Enqueue one full-screen intro quad.
    *
    * @param tSeconds  seconds since the intro began
    * @param shortMode {@code true} for the short cut (skips the headline)
    * @param opacity   overall opacity 0..1 (1 while playing, ramped down to cross-fade out)
    */
   public static void draw(GuiGraphicsExtractor g, float tSeconds, boolean shortMode, float opacity) {
      draw(g, tSeconds, shortMode ? MODE_SHORT : MODE_FULL, opacity);
   }

   /**
    * Enqueue one full-screen quad in the given {@code mode} ({@link #MODE_FULL}, {@link #MODE_SHORT}, {@link #MODE_LOOP}).
    * The loop opens with the word "S1mp1e" focusing in on pure black; its two halves ("S1m" / "p1e") then melt into
    * the pixel and glass tiles of the mark and back (distance-field morph on springs), forever with no seam (period
    * 4.8 s in the shader), so it can run for however long the world takes to load.
    */
   public static void draw(GuiGraphicsExtractor g, float tSeconds, int mode, float opacity) {
      if (opacity <= 0.0F || !ready(mode)) {
         return;
      }
      int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
      int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
      int alpha = Math.round(Math.min(Math.max(opacity, 0.0F), 1.0F) * 255.0F) & 0xFF;
      int color = alpha << 24 | 0xFFFFFF;
      TextureSetup ts = TextureSetup.singleTexture(strip(stripFor(mode)), GlassPipeline.sampler());
      ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState()
            .addGuiElement(new IntroState(GlassPipeline.intro(), ts, g.pose(), w, h, tSeconds, (float) mode, color));
   }

   /**
    * A full-screen quad whose UV carries (time, mode) uniformly across the surface and whose colour carries the
    * opacity in its alpha. The vertex shader is the shared {@code core/glass} one, so UV0 arrives as {@code vLocal} and
    * the colour as {@code vColor}; the intro fragment shader reads {@code vLocal.x} = time, {@code vLocal.y} = mode,
    * {@code vColor.a} = opacity, and rebuilds design space from {@code gl_FragCoord}.
    */
   private static final class IntroState implements GuiElementRenderState {
      private final RenderPipeline pipeline;
      private final TextureSetup textureSetup;
      private final Matrix3x2f pose;
      private final int w;
      private final int h;
      private final float t;
      private final float mode;
      private final int color;
      private final ScreenRectangle bounds;

      IntroState(RenderPipeline pipeline, TextureSetup ts, Matrix3x2fc pose, int w, int h, float t, float mode, int color) {
         this.pipeline = pipeline;
         this.textureSetup = ts;
         this.pose = new Matrix3x2f(pose);
         this.w = w;
         this.h = h;
         this.t = t;
         this.mode = mode;
         this.color = color;
         this.bounds = new ScreenRectangle(0, 0, Math.max(w, 1), Math.max(h, 1)).transformMaxBounds(this.pose);
      }

      @Override
      public void buildVertices(VertexConsumer vc) {
         vc.addVertexWith2DPose(this.pose, 0.0F, 0.0F).setUv(this.t, this.mode).setColor(this.color);
         vc.addVertexWith2DPose(this.pose, 0.0F, this.h).setUv(this.t, this.mode).setColor(this.color);
         vc.addVertexWith2DPose(this.pose, this.w, this.h).setUv(this.t, this.mode).setColor(this.color);
         vc.addVertexWith2DPose(this.pose, this.w, 0.0F).setUv(this.t, this.mode).setColor(this.color);
      }

      @Override
      public RenderPipeline pipeline() {
         return this.pipeline;
      }

      @Override
      public TextureSetup textureSetup() {
         return this.textureSetup;
      }

      @Override
      @Nullable
      public ScreenRectangle scissorArea() {
         return null;
      }

      @Override
      @Nullable
      public ScreenRectangle bounds() {
         return this.bounds;
      }
   }
}
