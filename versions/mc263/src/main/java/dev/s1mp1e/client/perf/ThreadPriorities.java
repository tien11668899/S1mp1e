package dev.s1mp1e.client.perf;

import java.util.Locale;

/**
 * Background game threads yield to the render thread. On 26.3 at 4K (Vulkan, RTX 5060, 6-core Ryzen) every game tick
 * set off a burst — the integrated server loading chunks, Sodium rebuilding the sections it sent — that filled every
 * core for ~20 ms; in those bursts the driver could not keep up with presenting and vkAcquireNextImageKHR blocked ~1 ms
 * per frame (slow-frame runs starting every 50 ms). The render thread is raised to the highest priority and the
 * background pools (server, world workers, IO, Sodium's builders and cull thread, networking) lowered, so the work still
 * gets done on the other cores but never ahead of the frame. Java thread priorities are OS thread priorities on
 * Windows. Re-applied every 2 s for pools that start threads later. Off by default (no measurable effect);
 * {@code S1MP1E_BG_PRIORITY=1} turns it on.
 */
public final class ThreadPriorities {
    private static final String[] BACKGROUND = {
        "server thread", "worker-main", "io-worker", "chunk render task executor", "sodium async cull",
        "netty", "download-", "c2me", "lithium", "bootstrap", "chunk",
    };

    private ThreadPriorities() {}

    public static void start() {
        // measured no different (26.3 Vulkan 4K, interleaved: 886/906 vs 889/909 FPS): the stall was in the display
        // path, not CPU contention (FastSync). Kept as an opt-in experiment.
        if (!"1".equals(System.getenv("S1MP1E_BG_PRIORITY"))) return;
        final Thread render = Thread.currentThread();
        try { render.setPriority(Thread.MAX_PRIORITY); } catch (Throwable ignored) { }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    for (Thread th : Thread.getAllStackTraces().keySet()) {
                        if (th == render || th.getPriority() == Thread.MIN_PRIORITY) continue;
                        String n = th.getName().toLowerCase(Locale.ROOT);
                        for (String b : BACKGROUND) {
                            if (n.contains(b)) { th.setPriority(Thread.MIN_PRIORITY); break; }
                        }
                    }
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    // a thread that finished or refuses: try again next round
                }
            }
        }, "S1mp1e-ThreadPriorities");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        System.out.println("[S1mp1e] render thread first: background game threads lowered");
    }
}
