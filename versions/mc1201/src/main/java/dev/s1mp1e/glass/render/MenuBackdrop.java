package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

/**
 * All-glass #26 (1.20.1): world-less screens get the title screen's panorama, blurred and dimmed — the look 26.2 /
 * 1.21.1 have natively — instead of vanilla's tiled dirt ({@code Screen.renderBackgroundTexture}, CreateWorldScreen's
 * light dirt, the credits roll).
 *
 * <p>Per frame, where the dirt would be drawn: render the shared {@link RotatingCubeMapRenderer} of
 * {@link TitleScreen#PANORAMA_CUBE_MAP} (its pitch/yaw is kept in step with the title screen's own renderer by
 * {@code TitleScreenBackdropCaptureMixin}, so the panorama keeps turning without a jump as you go in and out of menus),
 * copy the frame into a private texture and redraw it full-screen through the {@code menu_blur} program (two passes —
 * the 7x7 kernel's tap spacing would ghost a single wide pass — dim on the last). Then {@link SceneCapture#grabNow()}
 * so every glass piece drawn afterwards (list selection capsules, panels, fields) refracts the new background.
 *
 * <p>With a world loaded nothing changes ({@link #draw} returns false → the caller keeps its vanilla behaviour), and
 * without a usable blur program the vanilla dirt is drawn as before.
 */
public final class MenuBackdrop {

    /** Blur radius per pass in GUI px (scaled to framebuffer px), and how far the result is darkened. */
    private static final float RADIUS_GUI = 7f;
    private static final float DIM = 0.35f;

    private static RotatingCubeMapRenderer panorama;
    private static int src = 0, srcW = 0, srcH = 0;
    private static long lastDrawNs;

    private MenuBackdrop() {}

    /** The shared rotating panorama (lazily created on the render thread). */
    public static RotatingCubeMapRenderer panorama() {
        if (panorama == null) panorama = new RotatingCubeMapRenderer(TitleScreen.PANORAMA_CUBE_MAP);
        return panorama;
    }

    /** True when the blurred panorama can be drawn (no world, blur program linked). */
    public static boolean ready() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null && GlassProgram.ensureReady() && GlassProgram.blurUsable();
    }

    /** Kept for the title-screen hook's contract; the panorama is rendered live, so nothing to capture. */
    public static void capture() {}

    /** True while the current screen's frame already starts with the backdrop (set by {@link #beginScreen}). */
    private static boolean frameDrawn;

    /**
     * Start of a screen's frame ({@code Screen.renderWithTooltip} HEAD). 1.20.1's world/server/pack list screens never
     * call {@code renderBackground} — their list's dirt band and strips WERE the full-screen background — so once those
     * are dropped (#26) nothing would clear the frame. Paint the backdrop first for every world-less screen except the
     * title (it draws its own live panorama); a later {@code renderBackgroundTexture} then has nothing left to do.
     */
    public static void beginScreen(DrawContext ctx, net.minecraft.client.gui.screen.Screen screen) {
        frameDrawn = false;
        if (screen instanceof TitleScreen) return;
        frameDrawn = draw(ctx);
    }

    /** End of the screen's frame. */
    public static void endScreen() {
        frameDrawn = false;
    }

    /**
     * For the places that would paint a full-screen dirt background: true when the blurred panorama is (now) behind
     * everything, so the caller skips its dirt; false with a world loaded / no blur program (draw the vanilla dirt).
     */
    public static boolean cover(DrawContext ctx) {
        return frameDrawn || draw(ctx);
    }

    /**
     * Paint the blurred panorama full-screen. Returns false (and draws nothing) when a world is loaded or the blur
     * program is unavailable — the caller then draws its vanilla background.
     */
    public static boolean draw(DrawContext ctx) {
        if (!ready()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        int fw = mc.getWindow().getFramebufferWidth(), fh = mc.getWindow().getFramebufferHeight();
        if (fw <= 0 || fh <= 0) return false;

        float[] sc = RenderSystem.getShaderColor();
        float r = sc[0], g = sc[1], b = sc[2], a = sc[3];
        ctx.draw();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // advance the rotation once per frame even if several callers paint a background in the same frame
        long now = System.nanoTime();
        float delta = now - lastDrawNs < 2_000_000L ? 0f : mc.getLastFrameDuration();
        lastDrawNs = now;
        panorama().render(delta, 1.0f);

        float scale = (float) mc.getWindow().getScaleFactor();
        float sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        RenderSystem.disableDepthTest();
        copyFrame(fw, fh);
        blurPass(sw, sh, RADIUS_GUI * scale, 0f);
        copyFrame(fw, fh);
        blurPass(sw, sh, RADIUS_GUI * 0.5f * scale, DIM);
        RenderSystem.enableDepthTest();

        RenderSystem.setShaderColor(r, g, b, a);
        SceneCapture.grabNow();   // glass drawn after this refracts the blurred panorama
        return true;
    }

    /** Copy the current framebuffer into {@link #src} (same recipe as {@link SceneCapture}). */
    private static void copyFrame(int w, int h) {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (src == 0 || srcW != w || srcH != h) {
            if (src != 0) GL11.glDeleteTextures(src);
            src = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, src);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);
            srcW = w;
            srcH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, src);
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.bindTexture(0);
        RenderSystem.bindTexture(prevTex);
    }

    /** One full-screen {@code menu_blur} pass sampling {@link #src} (unit 0). */
    private static void blurPass(float sw, float sh, float radiusPx, float dim) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(src);
        if (GlassRenderer.beginBatch(GlassProgram.BLUR)) {
            GlassProgram.setBlur(radiusPx, dim);
            GlassRenderer.batchQuad(0f, 0f, sw, sh, 0f, 1f, 1f, 1f, 1f);
            GlassRenderer.endBatch();
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(0);
    }
}
