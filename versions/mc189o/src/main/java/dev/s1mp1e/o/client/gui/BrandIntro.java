package dev.s1mp1e.o.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.Window;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * The S1mp1e brand intro (group 10), drawn full-screen through {@link IntroPipeline} on pure black — the 1.12.2 port
 * of mc1144's {@code BrandIntro}. The animation is procedural in {@code s1mp1e_intro.fsh}; this feeds it time / mode /
 * opacity and binds the 16-bit-packed SDF strip ({@code intro_head_sdf.png} for the boot headline). Strips are
 * uploaded raw (RGBA8, NEAREST, CLAMP) so the packed distance bytes survive exactly.
 *
 * <p><b>1.12.2 adaptation.</b> The newer lines play the boot intro inside the main-thread resource-reload overlay;
 * 1.12.2 shows Forge's {@code SplashProgress} there instead (its own thread and GL context — nothing of ours can draw
 * into it). So the boot intro is {@link IntroScreen}: shown once per session right before the first title screen (a
 * {@code GuiOpenEvent} swap in {@code BrandIntroHandler}), holding {@link #HOLD_FULL} and then handing over to the
 * title through the screen cross-dissolve; any key or click skips it.
 */
public final class BrandIntro {
    private BrandIntro() {}

    public static final float HOLD_FULL = 3.9F;
    public static final int MODE_FULL = 0;
    public static final int MODE_SHORT = 1;
    public static final int MODE_LOOP = 2;

    private static final String[] STRIP_NAMES = {"intro_head_sdf", "intro_sdf"};
    private static final int[] STRIP_GLID = {-1, -1};
    private static final int[][] STRIP_SIZE = {{0, 0}, {0, 0}};
    private static final boolean[] STRIP_FAILED = new boolean[2];

    private static int strip(int i) {
        if (STRIP_GLID[i] >= 0 || STRIP_FAILED[i]) return STRIP_GLID[i];
        InputStream in = null;
        try {
            in = BrandIntro.class.getResourceAsStream("/assets/s1mp1e/textures/gui/" + STRIP_NAMES[i] + ".png");
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                int w = img.getWidth(), h = img.getHeight();
                int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
                for (int p : px) {
                    buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
                }
                buf.flip();
                int tex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                net.minecraft.client.render.platform.GlStateManager.bindTexture(0);
                STRIP_GLID[i] = tex;
                STRIP_SIZE[i][0] = w; STRIP_SIZE[i][1] = h;
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e] intro strip " + STRIP_NAMES[i] + " failed: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        if (STRIP_GLID[i] < 0) STRIP_FAILED[i] = true;
        return STRIP_GLID[i];
    }

    private static int stripFor(int mode) { return mode == MODE_LOOP ? 1 : 0; }

    public static boolean ready(int mode) {
        return IntroPipeline.ensureReady() && IntroPipeline.usable() && strip(stripFor(mode)) >= 0;
    }

    public static void draw(float tSeconds, int mode, float opacity) {
        if (opacity <= 0.0F || !ready(mode)) return;
        Window sr = new Window(Minecraft.getInstance());
        int i = stripFor(mode);
        IntroPipeline.draw(sr.getWidth(), sr.getHeight(), tSeconds, (float) mode, opacity,
                strip(i), STRIP_SIZE[i][0], STRIP_SIZE[i][1]);
    }

    // ---- the boot intro screen ------------------------------------------------------------------------

    private static boolean shownThisSession;

    /** Swap the first title screen of the session for the intro (BrandIntroHandler, GuiOpenEvent). */
    public static Screen maybeIntro(Screen next) {
        if (shownThisSession || !(next instanceof TitleScreen)) return next;
        shownThisSession = true;
        return new IntroScreen(next);
    }

    /** DevShot: true while the intro screen is up. */
    public static boolean showing() {
        return Minecraft.getInstance().screen instanceof IntroScreen;
    }

    public static final class IntroScreen extends Screen {
        private final Screen next;
        private long start;
        private boolean handedOver;

        IntroScreen(Screen next) { this.next = next; }

        @Override public boolean shouldPauseGame() { return false; }

        @Override
        public void render(int mouseX, int mouseY, float partialTicks) {
            GuiElement.fill(0, 0, this.width, this.height, 0xFF000000);          // brand screens are pure black
            if (!ready(MODE_FULL)) { handOver(); return; }                     // no GL 2.0 / strip: straight to title
            long now = System.nanoTime();
            if (start == 0L) start = now;
            float t = (now - start) / 1.0e9f;
            draw(t, MODE_FULL, 1f);
            if (t >= HOLD_FULL) handOver();
        }

        @Override
        protected void keyPressed(char typedChar, int keyCode) { handOver(); }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) { handOver(); }

        private void handOver() {
            if (handedOver) return;
            handedOver = true;
            this.minecraft.openScreen(next);
        }
    }
}
