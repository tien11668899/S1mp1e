package dev.s1mp1e.client.perf;

/**
 * "Fast sync" for the Vulkan backend with vsync off, as NVIDIA's driver option of that name does it: when the display
 * path is the bottleneck, render the next frame instead of waiting to hand one over.
 *
 * <p>Measured on 26.3 at 4K (RTX 5060, Windows 10): in a ~20 ms stretch of every 50 ms game tick, vkAcquireNextImageKHR
 * blocked ~1.05 ms per frame (whatever the timeout, image count or present mode), holding those stretches to ~650
 * frames a second while the GPU needed ~0.8 ms per frame and the render thread ~0.45 ms. So for 25 ms after an acquire
 * that blocked (over {@value #BLOCKED_NS} ns), acquires are spaced 1.6 ms apart and the frames in between render without
 * acquiring, blitting or presenting (Minecraft's frame loop already skips those when the surface is not acquired).
 * Outside those stretches every frame is presented as before; inside them the display still gets ~600 frames a second,
 * far above the monitor's refresh, and the newest frame it takes is newer than it would have been. Vsync on (FIFO) and
 * OpenGL are untouched. {@code S1MP1E_FAST_SYNC=0} turns it off; {@code S1MP1E_FAST_SYNC_SPACING_US} sets the spacing.
 *
 * <p>26.2: the same surface and frame loop as 26.3 under the older package names (com.mojang.blaze3d.vulkan /
 * com.mojang.blaze3d.systems); the numbers above are 26.3's. It only ever acts after an acquire that blocked, so a
 * display path that never stalls is left alone.
 */
public final class FastSync {
    public static final boolean ENABLED = !"0".equals(System.getenv("S1MP1E_FAST_SYNC"));
    public static final long BLOCKED_NS = 300_000L;
    /** VkPresentModeKHR of the current swapchain (set when it is created); -1 = not a Vulkan swapchain. */
    public static volatile int presentMode = -1;
    /** Time the last vkAcquireNextImageKHR took. */
    public static long lastAcquireNs;
    private static long lastBlockedNs = Long.MIN_VALUE / 2, lastAcquireEndNs;
    /** In a throttled stretch the display took about one frame per 1.5 ms: acquire at most every this many ns
     *  (interleaved 4K runs: 1.2 ms 1145 FPS, 1.4 ms 1145, 1.6 ms 1219/1220; vanilla 895-908). */
    private static final long SPACING_NS = spacing();

    private static long spacing() {
        try {
            String s = System.getenv("S1MP1E_FAST_SYNC_SPACING_US");
            return s == null ? 1_600_000L : Math.max(0L, Math.min(5_000L, Long.parseLong(s.trim()))) * 1_000L;
        } catch (NumberFormatException e) {
            return 1_600_000L;
        }
    }
    /** A throttled stretch lasted ~20 ms of every 50 ms tick: keep the spacing this long after the last blocked acquire. */
    private static final long HOLD_NS = 25_000_000L;
    private static long presented, skipped, lastLog = System.nanoTime();

    private FastSync() {}

    /** Only without vsync: VK_PRESENT_MODE_IMMEDIATE_KHR (0) or VK_PRESENT_MODE_MAILBOX_KHR (1). */
    public static boolean active() {
        return ENABLED && (presentMode == 0 || presentMode == 1);
    }

    /** Before the frame acquires a swapchain image: false = render this frame without presenting it. */
    public static boolean shouldAcquire() {
        if (!active()) return true;
        long now = System.nanoTime();
        if (now - lastBlockedNs < HOLD_NS && now - lastAcquireEndNs < SPACING_NS) {
            skipped++;
            log();
            return false;
        }
        return true;
    }

    /**
     * After an acquire. A blocked one marks a throttled stretch: for {@link #HOLD_NS} after it, acquires are spaced
     * {@link #SPACING_NS} apart (frames in between render without presenting), so they find an image free. With no
     * blocked acquire for that long, every frame is presented again.
     */
    public static void acquired() {
        presented++;
        long now = System.nanoTime();
        lastAcquireEndNs = now;
        if (lastAcquireNs > BLOCKED_NS) lastBlockedNs = now;
        log();
    }

    private static void log() {
        if (!"1".equals(System.getenv("S1MP1E_GPUPROF"))) return;
        long now = System.nanoTime();
        if (now - lastLog > 5_000_000_000L) {
            System.out.printf("[S1mp1e] fast sync: %d frames presented, %d rendered without presenting%n", presented, skipped);
            presented = skipped = 0;
            lastLog = now;
        }
    }
}
