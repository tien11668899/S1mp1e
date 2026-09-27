package dev.s1mp1e.glass.render;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Replaces vanilla's tiled dirt background on world-less screens with the title
 * screen's own frame, blurred — the 1.8.9 equivalent of 26.2's native menu blur.
 *
 * <p>The source is the panorama ALONE — captured from an ASM hook the instant
 * {@code GuiMainMenu.renderSkybox} returns, before the logo, splash and buttons
 * are drawn. Grabbing the finished title frame instead would blur the title
 * artwork and ghost the buttons into the backdrop.
 *
 * <p><b>Live panorama (V-5).</b> A frozen capture behind Options/Multiplayer
 * looks dead next to 1.21, where the panorama keeps drifting. So the last
 * {@link GuiMainMenu} is remembered (weakly), its {@code panoramaTimer} keeps
 * ticking while another world-less screen is up, and each backdrop frame first
 * re-runs that menu's own {@code renderSkybox} and re-captures it. Everything
 * about that path is defensive: if the reflection, the menu reference or the
 * render itself ever fails, {@link #liveDisabled} latches for the session and
 * the old frozen capture is used forever after.
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

        Minecraft mc = Minecraft.getMinecraft();
        int w = mc.displayWidth, h = mc.displayHeight;
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
        hasFrame = true;
    }

    /** The captured panorama texture id (0 if none captured yet). */
    public static int panoramaTex() { return texture; }

    /** Draw the blurred panorama backdrop full-screen. False -> caller draws the dirt. */
    public static boolean draw() {
        refreshLive();          // keep the panorama moving; no-op once it has failed
        if (!ready()) return false;
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        drawTexture(texture, RADIUS, DIM, 0f, sr.getScaledHeight());
        return true;
    }

    /**
     * Draw the current framebuffer (the world + the screen's darken gradient),
     * blurred, as the backdrop behind a non-container in-world screen. Grabs a
     * fresh composite first. False -> caller keeps whatever it drew.
     */
    public static boolean drawLive(float radius, float dim) {
        if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return false;
        SceneCapture.forceGrab();
        int tex = SceneCapture.texture();
        if (tex == 0) return false;
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        drawTexture(tex, radius, dim, 0f, sr.getScaledHeight());
        return true;
    }

    /**
     * Blit {@code tex} through the BLUR program over the full-width band
     * {@code [y0, y1)} in GUI pixels. The shader derives its UV from
     * {@code gl_FragCoord}, so a sub-rect quad samples the matching screen region
     * unchanged — used for the whole screen and for the GuiSlot header/footer
     * strips. Vertices wound TL→BL→BR→TR (front-facing under the GUI cull).
     */
    public static void drawTexture(int tex, float radius, float dim, float y0, float y1) {
        if (tex == 0) return;
        float w = new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth();

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

        // capture is framebuffer space (origin bottom-left); GUI space is
        // top-left, but the shader derives its UV from gl_FragCoord, so the
        // texcoords are cosmetic — only the quad's screen extent matters.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, y0);   // TL
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, y1);   // BL
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w,  y1);   // BR
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w,  y0);   // TR
        GL11.glEnd();

        GlassProgram.unbind();
        GL11.glDepthMask(true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        GlStateManager.bindTexture(0);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // =======================================================================
    // V-5: live (animated) panorama behind world-less screens
    // =======================================================================

    /** The last title screen seen, weakly — never keep a dead GUI alive. */
    private static WeakReference<GuiMainMenu> menuRef = null;
    /** {@code GuiMainMenu.renderSkybox(int,int,float)} — MCP in dev, SRG in production. */
    private static Method mRenderSkybox;
    /** {@code GuiMainMenu.panoramaTimer} (int). */
    private static Field fPanoramaTimer;
    private static boolean liveResolved = false;
    /** Latched on the first failure: the frozen capture is used for the rest of the session. */
    private static boolean liveDisabled = false;
    /** Partial ticks from the current render tick, so the panorama drifts smoothly. */
    private static float partialTicks = 0f;

    /** Remember the live title screen (called from the client tick while it is up). */
    public static void rememberMenu(GuiMainMenu menu) {
        if (menu == null) return;
        if (menuRef == null || menuRef.get() != menu) {
            menuRef = new WeakReference<GuiMainMenu>(menu);
        }
    }

    /** Fed from {@code RenderTickEvent.START}; {@code Minecraft.timer} is private in 1.8.9. */
    public static void setPartialTicks(float p) {
        partialTicks = p;
    }

    /**
     * Advance the remembered title screen's {@code panoramaTimer} by one tick.
     * Called from {@code ClientTickEvent.END}. While {@code GuiMainMenu} is itself
     * the current screen vanilla's {@code updateScreen} already does this, so we
     * only step in for the screens layered on top of it (Options, Multiplayer…),
     * which is exactly where vanilla would otherwise freeze the pan.
     */
    public static void tickPanorama() {
        if (liveDisabled) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld != null) return;      // never with a world loaded
            if (mc.currentScreen instanceof GuiMainMenu) {
                rememberMenu((GuiMainMenu) mc.currentScreen);
                return;                                          // vanilla advances it
            }
            GuiMainMenu menu = menu();
            if (menu == null) return;
            Field f = timerField();
            if (f == null) return;
            f.setInt(menu, f.getInt(menu) + 1);
        } catch (Throwable t) {
            liveDisabled = true;
            System.out.println("[S1mp1e] live panorama tick failed, freezing backdrop: " + t);
        }
    }

    private static GuiMainMenu menu() {
        return menuRef == null ? null : menuRef.get();
    }

    /**
     * Re-render the remembered title screen's panorama into the framebuffer and
     * re-capture it, so the backdrop about to be blurred is a moving frame rather
     * than a frozen one.
     *
     * <p>Throttled by the shared {@link #CAPTURE_GAP_NS} capture gap, which is
     * what bounds this to at most one skybox pass per rendered frame (a screen can
     * ask for the backdrop several times per frame — {@code drawDefaultBackground}
     * and then {@code GuiSlot.drawContainerBackground}).
     *
     * <p>{@code renderSkybox} unbinds the framebuffer and sets a 256×256 viewport
     * for its blur passes, so the framebuffer binding, the viewport and both matrix
     * stacks are saved and restored around it. Any failure latches
     * {@link #liveDisabled} and we fall back to the frozen capture forever.
     */
    private static boolean refreshLive() {
        if (liveDisabled) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld != null) return false;     // world loaded: never
        if (mc.currentScreen instanceof GuiMainMenu) return false; // it renders its own
        if (System.nanoTime() - lastCaptureNanos < CAPTURE_GAP_NS) return false;

        GuiMainMenu menu = menu();
        if (menu == null) return false;                          // keep the frozen capture
        Method sky = skyboxMethod();
        if (sky == null) { liveDisabled = true; return false; }
        if (mc.displayWidth <= 0 || mc.displayHeight <= 0) return false;

        IntBuffer vp = viewportBuf();
        vp.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        // GuiMainMenu.drawScreen brackets its own renderSkybox call exactly so.
        GlStateManager.disableAlpha();
        try {
            // renderSkybox lays its final quad out in GUI pixels from the screen's
            // own width/height, which go stale if the window was resized while the
            // title screen was not the active screen.
            ScaledResolution sr = new ScaledResolution(mc);
            menu.width  = sr.getScaledWidth();
            menu.height = sr.getScaledHeight();

            sky.invoke(menu, Integer.valueOf(0), Integer.valueOf(0),
                       Float.valueOf(partialTicks));
            capture();
            return true;
        } catch (Throwable t) {
            liveDisabled = true;
            System.out.println("[S1mp1e] live panorama render failed, freezing backdrop: " + t);
            return false;
        } finally {
            // renderSkybox rebinds the framebuffer and the display viewport itself,
            // but it may have thrown half-way through; put both back regardless.
            try {
                if (OpenGlHelper.isFramebufferEnabled()) {
                    Framebuffer fb = mc.getFramebuffer();
                    if (fb != null) fb.bindFramebuffer(false);
                }
                GlStateManager.viewport(vp.get(0), vp.get(1), vp.get(2), vp.get(3));
            } catch (Throwable ignored) {
                // nothing sane left to restore; the blur pass sets what it needs
            }
            GlStateManager.enableAlpha();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.bindTexture(0);
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    private static IntBuffer viewportBuf() {
        if (viewportBuf == null) viewportBuf = BufferUtils.createIntBuffer(16);
        return viewportBuf;
    }

    private static IntBuffer viewportBuf = null;

    private static Method skyboxMethod() {
        resolveLive();
        return mRenderSkybox;
    }

    private static Field timerField() {
        resolveLive();
        return fPanoramaTimer;
    }

    /** Resolve renderSkybox / panoramaTimer once: MCP name in dev, SRG in production. */
    private static void resolveLive() {
        if (liveResolved) return;
        liveResolved = true;
        String[] skyNames = { "renderSkybox", "func_73971_c" };
        for (int i = 0; i < skyNames.length && mRenderSkybox == null; i++) {
            try {
                Method m = GuiMainMenu.class.getDeclaredMethod(
                        skyNames[i], int.class, int.class, float.class);
                m.setAccessible(true);
                mRenderSkybox = m;
            } catch (NoSuchMethodException ignored) {
                // try the next candidate name
            }
        }
        String[] timerNames = { "panoramaTimer", "field_73979_m" };
        for (int i = 0; i < timerNames.length && fPanoramaTimer == null; i++) {
            try {
                Field f = GuiMainMenu.class.getDeclaredField(timerNames[i]);
                f.setAccessible(true);
                fPanoramaTimer = f;
            } catch (NoSuchFieldException ignored) {
                // try the next candidate name
            }
        }
        if (mRenderSkybox == null || fPanoramaTimer == null) {
            liveDisabled = true;
            System.out.println("[S1mp1e] GuiMainMenu.renderSkybox/panoramaTimer not found; "
                             + "menu backdrop stays a frozen capture");
        }
    }
}
