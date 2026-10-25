package dev.s1mp1e.worldconv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One game version's registries, from that version's own data generator (resources/registries/<version>/, made by
 * the regpack script): block states with their properties and defaults, and the id lists of every registry a save
 * refers to (items, entity types, block entity types, data components, biomes, enchantments, ...).
 */
final class Registry {
    record Block(Map<String, List<String>> properties, Map<String, String> defaults) {}

    final String version;
    final Map<String, Block> blocks = new HashMap<>();
    private final Map<String, Set<String>> lists = new HashMap<>();

    private Registry(String version) {
        this.version = version;
    }

    private static final Map<String, Registry> CACHE = new HashMap<>();

    static synchronized Registry of(String version) {
        return CACHE.computeIfAbsent(version, v -> {
            try {
                return load(v);
            } catch (IOException e) {
                throw new IllegalStateException("registries for " + v + ": " + e, e);
            }
        });
    }

    private static Registry load(String v) throws IOException {
        Registry r = new Registry(v);
        for (String line : lines("/registries/" + v + "/blocks.txt")) {
            String[] p = line.split("\\|", -1);
            Map<String, List<String>> props = new LinkedHashMap<>();
            if (!p[1].isEmpty()) {
                for (String prop : p[1].split(";")) {
                    String[] kv = prop.split("=", 2);
                    props.put(kv[0], List.of(kv[1].split(",")));
                }
            }
            Map<String, String> defs = new LinkedHashMap<>();
            if (p.length > 2 && !p[2].isEmpty()) {
                for (String d : p[2].split(",")) {
                    String[] kv = d.split("=", 2);
                    defs.put(kv[0], kv[1]);
                }
            }
            r.blocks.put(p[0], new Block(props, defs));
        }
        return r;
    }

    /** The ids of registry {@code name} (e.g. "item", "entity_type", "data_worldgen_biome"); empty when not shipped. */
    synchronized Set<String> list(String name) {
        return lists.computeIfAbsent(name, n -> {
            try {
                return new HashSet<>(lines("/registries/" + version + "/" + n + ".txt"));
            } catch (IOException e) {
                return Set.of();
            }
        });
    }

    boolean has(String list, String id) {
        return list(list).contains(id);
    }

    private static List<String> lines(String resource) throws IOException {
        InputStream in = Registry.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("missing resource " + resource);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        }
    }
}
