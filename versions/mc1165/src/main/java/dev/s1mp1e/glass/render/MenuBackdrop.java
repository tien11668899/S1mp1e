package dev.s1mp1e.glass.render;

import java.lang.ref.WeakReference;

import dev.s1mp1e.glass.mixin.TitleScreenPanoramaAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Replaces vanilla's tiled dirt background on world-less screens with the title
 * screen's own frame, blurred — the 1.8.9 equivalent of 26.2's native menu blur.
 *
 * <p>The source is the panorama ALONE — captured from an ASM hook the instant
 * {@code GuiMainMenu.renderSkybox} returns, before the logo, splash and buttons
 * are drawn. Grabbing the finished title frame instead would blur the title
 * artwork and ghost the buttons into the backdrop. Once you leave the main menu
 * nothing re-captures, so the panorama simply stays frozen behind the menus.
 *
 * <p><b>Live panorama (V-5).</b> A frozen capture behind Options/Multiplayer looks dead next
 * to 1.21, where the panorama keeps drifting. So the last {@link TitleScreen} is remembered
 * (weakly) and, while a world-less screen that is NOT the title screen is up, its own
 * {@code backgroundRenderer} is stepped and re-rendered into the framebuffer before the
 * capture — at the shared ~30 Hz capture rate. If the render ever fails, {@code liveDisabled}
 * latches for the session and the frozen capture is used forever after.
 */
public final class MenuBackdrop {

    /** Blur strength in physical px, and how far the result is darkened. */
    public static final float RADIUS = 14f;
    public static final float DIM    = 0.35f;

    private static int texture = 0;
    private static int texW = 0, texH = 0;
    private static boolean hasFrame = false;

    /** ~30 captures a second is far more than a slow panorama pan needs. */
    private static final long CAPTURE_GAP_NS = 33_000_000L;
    private static long lastCaptureNanos = 0L;

    private MenuBackdrop() {}

    /** True once a title-screen frame has been captured. */
    public static boolean ready() {
        return hasFrame && texture != 0
            && GlassProgram.ensureReady() && GlassProgram.blurUsable();
    }

    /**
     * Snapshot the panorama. Driven from an ASM hook placed immediately after
     * {@code GuiMainMenu.renderSkybox} returns, because that is the only point
     * where the title screen's BACKGROUND is on screen by itself — capturing at
     * end-of-frame would bake in the logo, splash text and buttons and blur
     * those too.
     */
    public static void capture() {
        // Throttled: the panorama pans slowly and this freezes the moment you
        // leave the menu anyway, so a full-screen copy every frame is waste.
        long now = System.nanoTime();
        if (now - lastCaptureNanos < CAPTURE_GAP_NS) return;
        lastCaptureNanos = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture == 0 || texW != w || texH != h) {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            // Level 0 must exist before glCopyTexSubImage2D, and the min filter
            // must not be a mipmap filter — an incomplete texture makes GL act
            // as if texturing were off, which renders flat white.
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                              GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            texW = w; texH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        // Resync RenderSystem's texture cache after the raw restore, else MC
        // samples our capture texture everywhere and the screen goes white.
        RenderSystem.bindTexture(0);
        RenderSystem.bindTexture(prevTex);
        hasFrame = true;
    }

    /** The captured panorama texture id (0 if none captured yet). */
    public static int panoramaTex() { return texture; }

    /** Draw the blurred panorama backdrop full-screen. False -> caller draws the dirt. */
    public static boolean draw() {
        refreshLive();          // keep the panorama moving; no-op once it has failed
        if (!ready()) return false;
        float h = MinecraftClient.getInstance().getWindow().getScaledHeight();
        drawTexture(texture, RADIUS, DIM, 0f, h);
        return true;
    }

    /**
     * Draw the current framebuffer (the world + the screen's darken gradient), blurred, as the
     * backdrop behind a non-container in-world screen (mc189 V-4). Grabs a fresh composite first
     * ({@link SceneCapture#grabNow()} — force, so the deduped world grab a base layer may have taken
     * this frame does not shadow it). False -> caller keeps whatever it drew.
     */
    public static boolean drawLive(float radius, float dim) {
        if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return false;
        SceneCapture.grabNow();
        int tex = SceneCapture.texture();
        if (tex == 0) return false;
        float h = MinecraftClient.getInstance().getWindow().getScaledHeight();
        drawTexture(tex, radius, dim, 0f, h);
        return true;
    }

    /**
     * Blit {@code tex} through the BLUR program over the full-width band {@code [y0, y1)} in GUI
     * pixels (mc189 V-4). The shader derives its UV from {@code gl_FragCoord}, so a sub-rect quad
     * samples the matching screen region unchanged — used for the whole screen and for the
     * EntryListWidget header/footer strips. Vertices wound TL→BL→BR→TR (front-facing under the GUI
     * cull). Keeps mc1165's GL conventions: glPushAttrib/glPopAttrib around the raw state, the program
     * bound and {@code setBlur} set BEFORE {@code glBegin}, then the RenderSystem texture/colour cache
     * resync so MC does not keep sampling this texture (white screen); blend is never left disabled.
     */
    public static void drawTexture(int tex, float radius, float dim, float y0, float y1) {
        drawTexture(tex, radius, dim, 0f, y0, MinecraftClient.getInstance().getWindow().getScaledWidth(), y1);
    }

    /**
     * The same blit confined to the rectangle {@code [x0, x1) x [y0, y1)} (a list that does not span the screen).
     * With {@code radius 0, dim 0} it is a 1:1 copy of that region of {@code tex}.
     */
    public static void drawTexture(int tex, float radius, float dim, float x0, float y0, float x1, float y1) {
        if (tex == 0) return;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_BLEND);          // opaque: it IS the background
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        GlassProgram.bind(GlassProgram.BLUR);
        GlassProgram.setBlur(radius, dim);

        // capture is framebuffer space (origin bottom-left); GUI space is top-left, but the shader
        // derives its UV from gl_FragCoord, so the texcoords are cosmetic — only the quad's screen
        // extent matters.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(x0, y0);   // TL
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(x0, y1);   // BL
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(x1, y1);   // BR
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(x1, y0);   // TR
        GL11.glEnd();

        GlassProgram.unbind();
        GL11.glDepthMask(true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        RenderSystem.bindTexture(0);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
    }
    // =======================================================================
    // V-5: live (animated) panorama behind world-less screens
    // =======================================================================

    /** The last title screen seen, weakly — never keep a dead screen alive. */
    private static WeakReference<TitleScreen> titleRef = null;
    /** Latched on the first failure: the frozen capture is used for the rest of the session. */
    private static boolean liveDisabled = false;
    /** When the panorama was last stepped, so it pans in real time rather than per frame. */
    private static long liveStepNanos = 0L;

    /**
     * Remember the live title screen. Called from {@code TitleScreenBackdropCaptureMixin}, i.e. while
     * the title screen is actually rendering. The reference is weak, so leaving the menu lets the
     * screen die and the backdrop simply freezes again.
     */
    public static void rememberTitle(TitleScreen screen) {
        if (screen == null) return;
        if (titleRef == null || titleRef.get() != screen) {
            titleRef = new WeakReference<TitleScreen>(screen);
        }
    }

    /**
     * Re-render the remembered title screen's panorama into the framebuffer and re-capture it, so the
     * backdrop about to be blurred is a MOVING frame rather than a frozen one (mc189 V-5).
     *
     * <p>Throttled by the shared {@link #CAPTURE_GAP_NS} capture gap, which also bounds this to at
     * most one panorama pass per rendered frame: a screen can ask for the backdrop several times per
     * frame ({@code renderBackgroundTexture}, then {@code EntryListWidget}'s interior), and every such
     * call blits the blur over the full screen straight afterwards, so a re-render can never leave the
     * raw panorama showing.
     *
     * <p>{@code CubeMapRenderer.draw} balances both matrix stacks itself but leaves the alpha test
     * off, the blend func defaulted and the colour set, so the GUI defaults are restored in a
     * {@code finally} (blend is left ENABLED — never disabled on the way out of a GUI draw path).
     * Any failure latches {@link #liveDisabled} and the frozen capture is used from then on.
     */
    private static boolean refreshLive() {
        if (liveDisabled) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world != null) return false;              // world loaded: never
        if (mc.currentScreen instanceof TitleScreen) return false;     // it renders (and captures) its own
        long now = System.nanoTime();
        if (now - lastCaptureNanos < CAPTURE_GAP_NS) return false;     // shared capture throttle
        TitleScreen title = titleRef == null ? null : titleRef.get();
        if (title == null) return false;                               // never saw one: keep the frozen capture
        if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return false;
        try {
            RotatingCubeMapRenderer sky = ((TitleScreenPanoramaAccessor) title).s1mp1e$backgroundRenderer();
            if (sky == null) { liveDisabled = true; return false; }
            // Step by REAL elapsed ticks: the throttle runs this at ~30 Hz while vanilla steps it once
            // per frame, so passing a frame delta would pan at a different speed than the title screen.
            float dt = (liveStepNanos == 0L) ? 1f
                     : (float) Math.min(5.0, (now - liveStepNanos) * 1e-9 * 20.0);
            liveStepNanos = now;
            sky.render(dt, 1.0f);
            capture();   // the throttle above guarantees this one is not deduped away
            return true;
        } catch (Throwable t) {
            liveDisabled = true;
            System.out.println("[S1mp1e] live panorama render failed, freezing backdrop: " + t);
            return false;
        } finally {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableAlphaTest();
            RenderSystem.defaultAlphaFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.color4f(1f, 1f, 1f, 1f);
        }
    }
}
