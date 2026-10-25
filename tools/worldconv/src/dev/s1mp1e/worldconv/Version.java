package dev.s1mp1e.worldconv;

import java.util.List;

/** The versions the converter knows (name, data version); registries and replacement tables exist for these. */
record Version(String name, int dataVersion) {
    static final List<Version> KNOWN = List.of(new Version("26.3", 5023), new Version("26.2", 4903), new Version("1.21.1", 3955));

    static Version byName(String name) {
        for (Version v : KNOWN) if (v.name.equals(name)) return v;
        throw new IllegalArgumentException("unknown version " + name + " (known: 26.3, 26.2, 1.21.1)");
    }

    static String nameOf(int dataVersion) {
        for (Version v : KNOWN) if (v.dataVersion == dataVersion) return v.name;
        return "data version " + dataVersion;
    }
}
