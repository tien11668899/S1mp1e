package dev.s1mp1e.client.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * GPU time per stage of a frame, for finding what the GPU spends a 4K frame on ({@code S1MP1E_GPUPROF=1}; inert
 * otherwise). Timestamps go through renderpearl's own query pool, so the same marks work on OpenGL and Vulkan. Each
 * {@link #mark} closes the stage that ended there; the results are read back {@value #FRAMES} frames later (no stall),
 * averaged, and printed every 5 s as {@code [GPUPROF] frames=… total=…ms | stage=ms …}.
 */
public final class GpuProfiler {
    public static final boolean ON = "1".equals(System.getenv("S1MP1E_GPUPROF"));
    private static final int FRAMES = 4;
    private static final int MARKS = 40;

    private static GpuQueryPool pool;
    private static boolean broken;
    private static final String[][] names = new String[FRAMES][MARKS];
    private static final int[] count = new int[FRAMES];
    private static long frame;
    private static final Map<String, double[]> acc = new LinkedHashMap<>();
    private static double totalMs;
    private static int samples;
    private static long lastPrint = System.nanoTime();
    // per frame, for telling slow frames apart: wall time, render-thread CPU time, GPU time (the GPU value arrives
    // FRAMES frames late, so the three are kept by frame number)
    private static final int RING = 8192;
    private static final double[] wallMs = new double[RING], cpuMs = new double[RING], gpuMs = new double[RING];
    // Vulkan: render-thread time in swapchain acquire / present and in waits for earlier submissions (mixins add to these)
    public static long acquireNs, presentNs, waitNs;
    private static final double[] acqMs = new double[RING], presMs = new double[RING], waitMs = new double[RING];
    private static final java.lang.management.ThreadMXBean MX = java.lang.management.ManagementFactory.getThreadMXBean();
    private static long lastWall, lastCpu;
    private static int perFrame;

    private GpuProfiler() {}

    /** A timestamp: the GPU time since the previous mark is booked under {@code stage}. */
    public static void mark(String stage) {
        if (!ON || broken) return;
        try {
            if (pool == null) pool = RenderSystem.getDevice().createTimestampQueryPool(FRAMES * MARKS);
            int slot = (int) (frame % FRAMES), i = count[slot];
            if (i >= MARKS) return;
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * MARKS + i);
            names[slot][i] = stage;
            count[slot] = i + 1;
        } catch (Throwable t) {
            broken = true;
            System.out.println("[GPUPROF] disabled: " + t);
        }
    }

    /** After the frame's last mark: read the slot written {@value #FRAMES} frames ago, then reuse it. */
    public static void endFrame() {
        if (!ON || broken || pool == null) return;
        long wallNow = System.nanoTime(), cpuNow = MX.getCurrentThreadCpuTime();
        int fi = (int) (frame % RING);
        wallMs[fi] = lastWall == 0 ? 0 : (wallNow - lastWall) / 1e6;
        cpuMs[fi] = lastCpu == 0 ? 0 : (cpuNow - lastCpu) / 1e6;
        acqMs[fi] = acquireNs / 1e6; presMs[fi] = presentNs / 1e6; waitMs[fi] = waitNs / 1e6;
        acquireNs = 0; presentNs = 0; waitNs = 0;
        lastWall = wallNow;
        lastCpu = cpuNow;
        frame++;
        int slot = (int) (frame % FRAMES), n = count[slot];
        if (n >= 2) {
            try {
                OptionalLong[] v = pool.getValues(slot * MARKS, n);
                boolean ready = true;
                for (OptionalLong x : v) ready &= x.isPresent();
                if (ready) {
                    for (int k = 1; k < n; k++) {
                        double[] a = acc.computeIfAbsent(names[slot][k], x -> new double[2]);
                        a[0] += (v[k].getAsLong() - v[k - 1].getAsLong()) / 1e6;
                        a[1]++;
                    }
                    double g = (v[n - 1].getAsLong() - v[0].getAsLong()) / 1e6;
                    totalMs += g;
                    samples++;
                    gpuMs[(int) ((frame - FRAMES) % RING)] = g;   // this slot was written FRAMES frames ago
                    perFrame++;
                }
            } catch (Throwable t) {
                broken = true;
                System.out.println("[GPUPROF] disabled: " + t);
            }
        }
        count[slot] = 0;
        long now = System.nanoTime();
        if (now - lastPrint > 5_000_000_000L && samples > 0) {
            StringBuilder sb = new StringBuilder(String.format("[GPUPROF] frames=%d total=%.3fms |", samples, totalMs / samples));
            for (Map.Entry<String, double[]> e : acc.entrySet()) {
                sb.append(String.format(" %s=%.3f", e.getKey(), e.getValue()[0] / samples));
            }
            System.out.println(sb);
            // slow vs fast frames (wall > 1.2 ms): where did the extra time go?
            int m = (int) Math.min(Math.min(RING - FRAMES - 1, perFrame), frame - FRAMES - 1);
            double[] s = new double[12];
            int ns = 0, nf = 0;
            for (int k = 0; k < m; k++) {
                int i = (int) ((frame - FRAMES - 1 - k) % RING);
                if (i < 0 || wallMs[i] <= 0) continue;
                int o = wallMs[i] > 1.2 ? 0 : 6;
                s[o] += wallMs[i]; s[o + 1] += cpuMs[i]; s[o + 2] += gpuMs[i];
                s[o + 3] += acqMs[i]; s[o + 4] += presMs[i]; s[o + 5] += waitMs[i];
                if (o == 0) ns++; else nf++;
            }
            if (ns + nf > 0) {
                double a = Math.max(1, ns), b = Math.max(1, nf);
                System.out.println(String.format("[GPUPROF] frames: slow %.0f%% wall/cpu/gpu/acquire/present/wait %.3f/%.3f/%.3f/%.3f/%.3f/%.3f ms"
                                + " | fast %.3f/%.3f/%.3f/%.3f/%.3f/%.3f ms",
                        100.0 * ns / (ns + nf), s[0] / a, s[1] / a, s[2] / a, s[3] / a, s[4] / a, s[5] / a,
                        s[6] / b, s[7] / b, s[8] / b, s[9] / b, s[10] / b, s[11] / b));
            }
            perFrame = 0;
            acc.clear();
            totalMs = 0;
            samples = 0;
            lastPrint = now;
        }
    }
}
