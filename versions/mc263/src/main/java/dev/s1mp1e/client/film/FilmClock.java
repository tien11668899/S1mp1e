package dev.s1mp1e.client.film;

import net.minecraft.util.TimeSource;
import net.minecraft.util.Util;

/**
 * The film clock. Installed as vanilla's {@link Util#setTimeSource time source}, so every consumer of
 * {@link Util#getNanos()} / {@link Util#getMillis()} — the frame timer, ticks, the integrated server, and all of the
 * mod's animations (which read {@code Util.getNanos()}) — sees film time.
 *
 * <p>Two modes. REAL: film time follows the wall clock (booting, loading a world, letting chunks settle). STEP: film
 * time only moves when the director {@linkplain #advance advances} it — exactly one frame (or sub-frame) per rendered
 * frame — so a capture is perfectly paced no matter how long each 4K frame takes to render and encode.
 */
public final class FilmClock implements TimeSource.NanoTimeSource {
    private static final FilmClock INSTANCE = new FilmClock();

    private static volatile boolean stepping;
    private static volatile long frozen;
    private static volatile long realAnchor;
    private static volatile long virtAnchor;
    private static boolean installed;

    private FilmClock() {
    }

    @Override
    public long getAsLong() {
        return stepping ? frozen : virtAnchor + (System.nanoTime() - realAnchor);
    }

    /** Take over vanilla's time source, continuous with the wall clock. */
    static void install() {
        if (installed) return;
        long n = System.nanoTime();
        realAnchor = n;
        virtAnchor = n;
        stepping = false;
        Util.setTimeSource(INSTANCE);
        installed = true;
    }

    /** Freeze film time; from now on it only moves through {@link #advance}. */
    static void step() {
        if (!stepping) {
            frozen = INSTANCE.getAsLong();
            stepping = true;
        }
    }

    /** Let film time follow the wall clock again, continuing from where it is. */
    public static void real() {
        if (stepping) {
            virtAnchor = frozen;
            realAnchor = System.nanoTime();
            stepping = false;
        }
    }

    static void advance(long ns) {
        if (stepping) frozen += ns;
    }

    static boolean stepping() {
        return stepping;
    }
}
