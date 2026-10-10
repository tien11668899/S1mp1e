package dev.s1mp1e.client.knife;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decodes skin composite inputs off the render thread (one daemon worker, so a burst of picks in the locker never
 * holds more than one skin's worth of decoded pixels). The render thread only uploads + draws when it's ready, so
 * picking a skin no longer freezes the game for the PNG decode.
 */
final class SkinLoader {

    private SkinLoader() {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "S1mp1e skin decode");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** file (relative to {@code root}) -> decoded; on failure everything decoded so far is freed. */
    static CompletableFuture<Map<String, SkinCompositor.Decoded>> decodeAll(Path root, Collection<String> files) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(files);
        return CompletableFuture.supplyAsync(() -> {
            Map<String, SkinCompositor.Decoded> out = new HashMap<>();
            try {
                for (String f : unique) out.put(f, SkinCompositor.decode(root.resolve(f)));
                return out;
            } catch (Throwable t) {
                free(out);
                throw new RuntimeException(t);
            }
        }, WORKER);
    }

    static void free(Map<String, SkinCompositor.Decoded> m) {
        if (m == null) return;
        for (SkinCompositor.Decoded d : m.values()) d.close();
        m.clear();
    }
}
