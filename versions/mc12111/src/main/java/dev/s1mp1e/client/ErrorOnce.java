package dev.s1mp1e.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs a render-path failure ONCE per call site, then stays quiet.
 *
 * <p>HUD overlays run every frame, and a bug in one must never crash the game or take the rest of the HUD with
 * it, so those paths catch {@link Throwable}. Catching silently, though, turns a real bug into an overlay that
 * just "doesn't show up" with nothing in the log. This keeps both properties: the first failure at each
 * {@code where} is printed with its stack trace (it lands in {@code logs/latest.log} as [STDOUT]), and every
 * later one from the same place is dropped so a per-frame error can't flood the log.
 */
public final class ErrorOnce {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private ErrorOnce() {}

    public static void report(String where, Throwable t) {
        if (!SEEN.add(where)) return;
        System.out.println("[S1mp1e] error in " + where + " (further errors from here are suppressed): " + t);
        t.printStackTrace(System.out);
    }
}
