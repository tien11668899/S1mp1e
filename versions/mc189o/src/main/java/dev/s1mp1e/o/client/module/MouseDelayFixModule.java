package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import org.lwjgl.opengl.Display;

/**
 * Mouse delay fix. 1.8.9 polls the mouse once per frame, at the very end of it ({@code Minecraft.updateDisplay}: the
 * buffer swap, then {@code Display.update} pumps the window messages and polls the devices), and the camera turns by
 * that reading at the start of the NEXT frame — after the frame limiter's sleep, the scheduled network tasks and the
 * client ticks. Mouse motion inside that gap waits a whole extra frame. Pumping the window messages right before the
 * camera reads the mouse ({@code Display.processMessages} polls the mouse and keyboard too) turns every frame by the
 * newest input; {@link #takeAverageSavedMs} reports how old the input would otherwise have been.
 *
 * <p>Input latency only: LWJGL adds the deltas of every poll until they are read, so no motion is lost or counted
 * twice; button and key events are queued as before and still handled by the next client tick. Nothing changes what
 * is sent to the server.
 */
public final class MouseDelayFixModule extends Module {

    private static MouseDelayFixModule instance;
    /** Time of the last end-of-frame input poll, for the latency statistic. */
    private static long lastFramePollNs;
    private static long ageSumNs;
    private static int ageSamples;
    private static boolean failed;

    public MouseDelayFixModule() {
        super("MouseDelayFix", "Combat");
        this.enabled = true;   // a pure fix: on unless the player turns it off
        instance = this;
    }

    /** End of {@code Minecraft.updateDisplay}: the frame's own input poll has just happened. */
    public static void afterDisplayUpdate() {
        lastFramePollNs = System.nanoTime();
    }

    /** Right before {@code GameRenderer} turns the camera by the mouse delta. */
    public static void beforeMouseRead() {
        MouseDelayFixModule m = instance;
        if (m == null || !m.enabled || failed) return;
        long t = lastFramePollNs;
        if (t != 0L) {
            ageSumNs += System.nanoTime() - t;
            ageSamples++;
        }
        try {
            Display.processMessages();
        } catch (Throwable e) {
            failed = true;   // never break the frame; the camera simply reads the end-of-frame poll as vanilla does
            System.out.println("[S1mp1e] mouse delay fix disabled: " + e);
        }
    }

    /** Average age (ms) of the input the camera would have used without the fix, since the last call; -1 if none. */
    public static double takeAverageSavedMs() {
        if (ageSamples == 0) return -1.0;
        double ms = ageSumNs / 1e6 / ageSamples;
        ageSumNs = 0L;
        ageSamples = 0;
        return ms;
    }
}
