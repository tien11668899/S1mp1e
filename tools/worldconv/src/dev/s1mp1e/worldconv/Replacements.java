package dev.s1mp1e.worldconv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The closest equivalents a target version has for what it lacks (resources/replace/<target>.txt: {@code kind source
 * target} per line; kinds block, item, entity, biome, decoration, particle).
 */
final class Replacements {
    private final Map<String, String> map = new HashMap<>();

    private Replacements() {}

    static Replacements forTarget(String version) {
        Replacements r = new Replacements();
        InputStream in = Replacements.class.getResourceAsStream("/replace/" + version + ".txt");
        if (in == null) return r;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            br.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).forEach(line -> {
                String[] p = line.split("\\s+");
                if (p.length >= 3) r.map.put(p[0] + " " + p[1], p[2]);
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return r;
    }

    /** The replacement for {@code id} of {@code kind}, or null when the table has none. */
    String get(String kind, String id) {
        return map.get(kind + " " + id);
    }
}
