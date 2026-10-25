package dev.s1mp1e.worldconv;

/**
 * The bit-packed index arrays of paletted containers (block states: 4096 entries, at least 4 bits; biomes: 64 entries,
 * at least 1 bit). Since 1.16 an entry never spans two longs: each long holds floor(64 / bits) entries.
 */
final class PackedArray {
    private PackedArray() {}

    static int bitsFor(int paletteSize, int minBits) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(paletteSize - 1, 1));
        return Math.max(minBits, bits);
    }

    /** Indices into the palette; all zeros when {@code data} is absent (single-entry palette). */
    static int[] decode(long[] data, int size, int paletteSize, int minBits) {
        int[] out = new int[size];
        if (data == null || data.length == 0 || paletteSize <= 1) return out;
        int bits = bitsFor(paletteSize, minBits);
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < size; i++) {
            int li = i / perLong;
            if (li >= data.length) break;
            out[i] = (int) ((data[li] >>> ((i % perLong) * bits)) & mask);
        }
        return out;
    }

    /** Packs {@code indices}; null for a single-entry palette (the game then stores no data array). */
    static long[] encode(int[] indices, int paletteSize, int minBits) {
        if (paletteSize <= 1) return null;
        int bits = bitsFor(paletteSize, minBits);
        int perLong = 64 / bits;
        long[] out = new long[(indices.length + perLong - 1) / perLong];
        for (int i = 0; i < indices.length; i++) {
            out[i / perLong] |= ((long) indices[i]) << ((i % perLong) * bits);
        }
        return out;
    }
}
