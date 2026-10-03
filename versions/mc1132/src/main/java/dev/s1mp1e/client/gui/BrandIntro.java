package dev.s1mp1e.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.class_4277;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;

/**
 * The S1mp1e brand intro, drawn full-screen through the {@link IntroPipeline} program. The whole animation is
 * procedural inside the fragment shader; this class only feeds it three things per frame, all through one full-screen
 * quad:
 * <ul>
 *   <li><b>time</b> on {@code UV0.x} (seconds since the intro began),</li>
 *   <li><b>mode</b> on {@code UV0.y} (0 = the full boot intro from the "Welcome to S1mp1e" headline; 1 = the short cut
 *       from the point of light, for later resource reloads; 2 = the seamless world-entry loop),</li>
 *   <li><b>opacity</b> on the vertex alpha (used to cross-fade the intro out into the screen behind it).</li>
 * </ul>
 * The text the mode needs is bound on Sampler0 as a 16-bit-packed signed distance field ({@code intro_head_sdf.png}
 * for the boot, {@code intro_sdf.png} for the loop), read in the shader with texelFetch. Both callers
 * ({@code SplashScreen}, world entry) sit on a black base, so where the shader has drawn nothing it stays black.
 *
 * <p>This is the 1.13.2 (core-profile / MatrixStack) port of LiquidGlass26's {@code dev.s1mp1e.client.gui.BrandIntro}.
 * The look, timing constants, modes and hold durations are unchanged; only the plumbing differs — the deferred
 * {@code GuiElementRenderState} the 26.2 version enqueued becomes an immediate {@link IntroPipeline} draw, and the
 * shader/strip load off the classpath so no {@code ShaderManager} mixin is needed for the first-reload boot.
 */
public final class BrandIntro {
    private BrandIntro() {}

    /**
     * The full intro settles the mark by ~3.45 s (headline glyph by glyph → squeeze to a point → two tiles orbit out
     * and land → the overlap opens) and then breathes.
     * Boot holds at least this long before the loading screen is allowed to fade to the title.
     */
    public static final float HOLD_FULL = 3.9F;
    /** The short cut (world entry) reaches the settled mark by ~1.7 s of real time (the shader offsets +1.80 s itself). */
    public static final float HOLD_SHORT = 1.9F;

    /** Shader modes (UV0.y): the full boot intro, the short cut from the point of light, the seamless world-entry loop. */
    public static final int MODE_FULL = 0;
    public static final int MODE_SHORT = 1;
    public static final int MODE_LOOP = 2;

    /** Shader textures, 16-bit-packed signed distance fields read with texelFetch: [0] the boot headline "Welcome to
     *  S1mp1e", [1] "S1m" / "p1e" for the world-entry loop. */
    private static final String[] STRIP_NAMES = {"intro_head_sdf", "intro_sdf"};
    private static final int[] STRIP_GLID = {-1, -1};
    private static final boolean[] STRIP_FAILED = new boolean[2];

    /** Load (once) strip {@code i} and return its GL texture id, or {@code -1} if it could not be read. */
    private static int strip(int i) {
        if (STRIP_GLID[i] >= 0 || STRIP_FAILED[i]) {
            return STRIP_GLID[i];
        }
        String name = STRIP_NAMES[i];
        try (InputStream in = BrandIntro.class.getResourceAsStream("/assets/s1mp1e/textures/gui/" + name + ".png")) {
            if (in != null) {
                class_4277 img = class_4277.method_19472(in);
                // ctor uploads the image and allocates the GL texture eagerly; getGlId() then returns a valid id.
                NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                MinecraftClient.getInstance().getTextureManager()
                        .loadTexture(new Identifier("s1mp1e", name), tex);
                STRIP_GLID[i] = tex.getGlId();
            }
        } catch (Throwable ignored) {
        }
        if (STRIP_GLID[i] < 0) {
            STRIP_FAILED[i] = true;
        }
        return STRIP_GLID[i];
    }

    private static int stripFor(int mode) {
        return mode == MODE_LOOP ? 1 : 0;
    }

    /** True once the intro pipeline and the boot headline strip are ready — the caller falls back to vanilla otherwise. */
    public static boolean ready() {
        return ready(MODE_FULL);
    }

    /** True once the intro program and the strip {@code mode} samples are both ready. */
    public static boolean ready(int mode) {
        return IntroPipeline.ensureReady() && IntroPipeline.usable() && strip(stripFor(mode)) >= 0;
    }

    /**
     * Enqueue one full-screen intro quad.
     *
     * @param context   the MatrixStack (already flushed by the caller); used for the GUI size
     * @param tSeconds  seconds since the intro began
     * @param shortMode {@code true} for the short cut (skips the headline)
     * @param opacity   overall opacity 0..1 (1 while playing, ramped down to cross-fade out)
     */
    public static void draw(float tSeconds, boolean shortMode, float opacity) {
        draw(tSeconds, shortMode ? MODE_SHORT : MODE_FULL, opacity);
    }

    /**
     * Draw one full-screen quad in the given {@code mode} ({@link #MODE_FULL}, {@link #MODE_SHORT}, {@link #MODE_LOOP}).
     * The loop opens with the word "S1mp1e" focusing in on pure black; its two halves ("S1m" / "p1e") then melt into
     * the pixel and glass tiles of the mark and back (distance-field morph on springs), forever with no seam (period
     * 4.8 s in the shader), so it can run for however long the world takes to load.
     *
     * <p>The caller must already have flushed the {@code MatrixStack} ({@code context.draw()}) so this immediate-GL
     * quad composites on top of the deferred draws.
     */
    public static void draw(float tSeconds, int mode, float opacity) {
        if (opacity <= 0.0F || !ready(mode)) {
            return;
        }
        net.minecraft.class_4117 win = MinecraftClient.getInstance().field_19944;
        int w = win.method_18321();
        int h = win.method_18322();
        IntroPipeline.draw(w, h, tSeconds, (float) mode, opacity, strip(stripFor(mode)));
    }

    /**
     * 1.13.2: there is no SplashScreen overlay before 1.14 - the boot intro plays on a screen of its own in front of the
     * title ({@code IntroScreen}). True while that screen is up (the dev harness shoots its frames).
     */
    public static boolean bootScreenUp(net.minecraft.client.gui.screen.Screen screen) {
        return screen instanceof BootIntro;
    }

    /** Marker for the boot intro screen. */
    public interface BootIntro {}

    /** 1.13.2: the {@code ProgressScreen} that {@code startIntegratedServer} opened last - the singleplayer world-entry
     *  screen, the one that plays the brand loop (every other {@code ProgressScreen} keeps its glass status card). */
    public static net.minecraft.client.gui.screen.Screen worldEntryScreen;
    /** True once the loop has actually been drawn on {@link #worldEntryScreen} (the frame pump then runs smoothly). */
    public static boolean worldEntryLoopShown;
    /** Loop frames drawn on the current world-entry screen and the nanoTime of the first / last one (dev statistics:
     *  the harness reports the frame rate of the loop during the blocking server start). */
    public static int worldEntryFrames;
    public static long worldEntryFirstNs, worldEntryLastNs;
}
