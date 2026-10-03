package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;

/**
 * The boot brand intro on 1.13.2.
 *
 * <p>1.14+ plays the intro on the {@code SplashScreen} overlay while the resources load in the background. 1.13.2 has
 * neither the overlay nor a background reload: {@code MinecraftClient.initializeGame} draws ONE frame of the Mojang
 * logo screen ({@code class_4158}), then blocks until everything is loaded (no frames at all), then opens the title.
 * So the intro cannot run during the load; it plays right after it instead, on this screen, which takes the title's
 * place and hands over to it:
 * <ul>
 *   <li><b>Hold</b> ({@link BrandIntro#HOLD_FULL} s): the full intro on pure black;</li>
 *   <li><b>Fade</b> (1 s, the same reveal the 1.14+ overlay does): the title renders behind a black veil that goes
 *       1 -> 0 while the settled mark fades out with it, so the mark melts into the title with no pop;</li>
 *   <li>then the title becomes the current screen (in {@link #tick()}, not in the middle of a frame).</li>
 * </ul>
 * If the intro pipeline is unavailable the title is opened at once.
 */
public final class BootIntroScreen extends Screen implements BrandIntro.BootIntro {
    private static final float FADE_S = 1.0F;

    private final Screen next;
    /** {@code System.nanoTime()} of the first drawn frame (ns clock: ms steps judder at high refresh rates). */
    private long start;
    private boolean nextInit;
    private boolean done;

    public BootIntroScreen(Screen next) {
        this.next = next;
    }

    @Override
    protected void init() {
        this.nextInit = false;      // (re)sized: the title behind is laid out again when the fade needs it
    }

    @Override
    public void tick() {
        if (this.done && this.client != null && this.client.currentScreen == this) {
            this.client.setScreen(this.next);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        if (!BrandIntro.ready()) {              // no pipeline / strip: no intro
            this.done = true;
            DrawableHelper.fill(0, 0, this.width, this.height, 0xFF000000);
            return;
        }
        long now = System.nanoTime();
        if (this.start == 0L) {
            this.start = now;
        }
        float t = (float) ((now - this.start) / 1.0E9);
        if (t < BrandIntro.HOLD_FULL) {
            DrawableHelper.fill(0, 0, this.width, this.height, 0xFF000000);
            dev.s1mp1e.glass.render.GuiFlush.flush();
            BrandIntro.draw(t, false, 1.0F);
            return;
        }
        float p = Math.min(1.0F, (t - BrandIntro.HOLD_FULL) / FADE_S);
        if (!this.nextInit) {
            this.nextInit = true;
            this.next.init(this.client, this.width, this.height);
        }
        this.next.render(-1, -1, delta);        // the pointer is not on the title yet: nothing hovers
        int a = Math.round((1.0F - p) * 255.0F);
        if (a > 0) {
            com.mojang.blaze3d.platform.GlStateManager.disableDepthTest();
            DrawableHelper.fill(0, 0, this.width, this.height, a << 24);
            dev.s1mp1e.glass.render.GuiFlush.flush();
            BrandIntro.draw(t, false, 1.0F - p);
        }
        if (p >= 1.0F) {
            this.done = true;
        }
    }

    /** Nothing is interactive while the intro plays (Esc included, as on the 1.14+ overlay). */
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return true;
    }
}
