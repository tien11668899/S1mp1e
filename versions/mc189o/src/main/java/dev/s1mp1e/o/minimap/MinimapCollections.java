package dev.s1mp1e.o.minimap;

import java.util.Arrays;

/**
 * The few primitive collections the minimap data layer needs. 26.x and 1.21.x use fastutil, which 1.8.9 does not ship,
 * so these are small stand-ins with the same semantics: FIFO queues of longs and ints, an open-addressing long set and
 * a long-to-int counter map. Nothing boxes, so queuing and draining work allocates nothing once the arrays have grown
 * to the working size.
 *
 * <p>Keys are packed (x, z) pairs; {@link #FREE} (x = Integer.MIN_VALUE, z = 0) marks an empty hash cell and never
 * occurs, as it lies far outside any world.
 */
final class MinimapCollections {
    private MinimapCollections() {}

    static final long FREE = Long.MIN_VALUE;

    private static int mix(long x) {
        long h = x * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) (h ^ (h >>> 16));
    }

    private static long[] freeTable(int size) {
        long[] t = new long[size];
        Arrays.fill(t, FREE);
        return t;
    }

    /** Growable ring buffer of longs. */
    static final class LongQueue {
        private long[] a = new long[64];
        private int head, size;

        void enqueue(long v) {
            if (size == a.length) {
                long[] b = new long[a.length * 2];
                for (int i = 0; i < size; i++) b[i] = a[(head + i) & (a.length - 1)];
                a = b;
                head = 0;
            }
            a[(head + size) & (a.length - 1)] = v;
            size++;
        }

        long dequeue() {
            long v = a[head];
            head = (head + 1) & (a.length - 1);
            size--;
            return v;
        }

        boolean isEmpty() { return size == 0; }

        void clear() { head = 0; size = 0; }
    }

    /** Growable ring buffer of ints. */
    static final class IntQueue {
        private int[] a = new int[64];
        private int head, size;

        void enqueue(int v) {
            if (size == a.length) {
                int[] b = new int[a.length * 2];
                for (int i = 0; i < size; i++) b[i] = a[(head + i) & (a.length - 1)];
                a = b;
                head = 0;
            }
            a[(head + size) & (a.length - 1)] = v;
            size++;
        }

        int dequeue() {
            int v = a[head];
            head = (head + 1) & (a.length - 1);
            size--;
            return v;
        }

        boolean isEmpty() { return size == 0; }

        int size() { return size; }

        void clear() { head = 0; size = 0; }
    }

    /** Open-addressing set of longs: linear probing, backward-shift deletion (no tombstones), load at most 1/2. */
    static final class LongSet {
        private long[] keys = freeTable(64);
        private int size;

        int size() { return size; }

        boolean contains(long k) {
            int m = keys.length - 1;
            for (int i = mix(k) & m; ; i = (i + 1) & m) {
                long c = keys[i];
                if (c == k) return true;
                if (c == FREE) return false;
            }
        }

        boolean add(long k) {
            int m = keys.length - 1;
            int i = mix(k) & m;
            for (long c; (c = keys[i]) != FREE; i = (i + 1) & m) if (c == k) return false;
            keys[i] = k;
            if (++size * 2 > keys.length) rehash();
            return true;
        }

        boolean remove(long k) {
            int m = keys.length - 1;
            int i = mix(k) & m;
            for (long c; (c = keys[i]) != k; i = (i + 1) & m) if (c == FREE) return false;
            // pull later members of the probe run back into the hole, so lookups never stop early
            int hole = i;
            for (int j = (hole + 1) & m; ; j = (j + 1) & m) {
                long c = keys[j];
                if (c == FREE) break;
                if (((j - (mix(c) & m)) & m) >= ((j - hole) & m)) {
                    keys[hole] = c;
                    hole = j;
                }
            }
            keys[hole] = FREE;
            size--;
            return true;
        }

        void clear() {
            if (size == 0) return;
            Arrays.fill(keys, FREE);
            size = 0;
        }

        private void rehash() {
            long[] old = keys;
            keys = freeTable(old.length * 2);
            int m = keys.length - 1;
            for (long c : old) {
                if (c == FREE) continue;
                int i = mix(c) & m;
                while (keys[i] != FREE) i = (i + 1) & m;
                keys[i] = c;
            }
        }
    }

    /**
     * Open-addressing int-to-long map, get / put / clear only (absent = 0): the radar's entity id → first-seen time.
     * Integer.MIN_VALUE marks a free cell; entity ids count up from 0.
     */
    static final class IntLongMap {
        private static final int FREE_KEY = Integer.MIN_VALUE;
        private int[] keys = freeInts(64);
        private long[] values = new long[64];
        private int size;

        private static int[] freeInts(int n) {
            int[] t = new int[n];
            Arrays.fill(t, FREE_KEY);
            return t;
        }

        long get(int k) {
            int m = keys.length - 1;
            for (int i = mix(k) & m; ; i = (i + 1) & m) {
                int c = keys[i];
                if (c == k) return values[i];
                if (c == FREE_KEY) return 0L;
            }
        }

        void put(int k, long v) {
            int m = keys.length - 1;
            int i = mix(k) & m;
            for (int c; (c = keys[i]) != FREE_KEY; i = (i + 1) & m) {
                if (c == k) { values[i] = v; return; }
            }
            keys[i] = k;
            values[i] = v;
            if (++size * 2 > keys.length) {
                int[] oldK = keys;
                long[] oldV = values;
                keys = freeInts(oldK.length * 2);
                values = new long[oldK.length * 2];
                int mm = keys.length - 1;
                for (int o = 0; o < oldK.length; o++) {
                    if (oldK[o] == FREE_KEY) continue;
                    int j = mix(oldK[o]) & mm;
                    while (keys[j] != FREE_KEY) j = (j + 1) & mm;
                    keys[j] = oldK[o];
                    values[j] = oldV[o];
                }
            }
        }

        void clear() {
            if (size == 0) return;
            Arrays.fill(keys, FREE_KEY);
            size = 0;
        }
    }

    /** Open-addressing long-to-int map with fastutil's {@code addTo} / {@code remove} semantics (absent = 0). */
    static final class LongIntMap {
        private long[] keys = freeTable(64);
        private int[] values = new int[64];
        private int size;

        /** Adds {@code delta} to the value of {@code k} (inserting it at 0 first) and returns the value before. */
        int addTo(long k, int delta) {
            int m = keys.length - 1;
            int i = mix(k) & m;
            for (long c; (c = keys[i]) != FREE; i = (i + 1) & m) {
                if (c == k) {
                    int old = values[i];
                    values[i] = old + delta;
                    return old;
                }
            }
            keys[i] = k;
            values[i] = delta;
            if (++size * 2 > keys.length) rehash();
            return 0;
        }

        void remove(long k) {
            int m = keys.length - 1;
            int i = mix(k) & m;
            for (long c; (c = keys[i]) != k; i = (i + 1) & m) if (c == FREE) return;
            int hole = i;
            for (int j = (hole + 1) & m; ; j = (j + 1) & m) {
                long c = keys[j];
                if (c == FREE) break;
                if (((j - (mix(c) & m)) & m) >= ((j - hole) & m)) {
                    keys[hole] = c;
                    values[hole] = values[j];
                    hole = j;
                }
            }
            keys[hole] = FREE;
            size--;
        }

        void clear() {
            if (size == 0) return;
            Arrays.fill(keys, FREE);
            size = 0;
        }

        private void rehash() {
            long[] oldK = keys;
            int[] oldV = values;
            keys = freeTable(oldK.length * 2);
            values = new int[oldK.length * 2];
            int m = keys.length - 1;
            for (int o = 0; o < oldK.length; o++) {
                long c = oldK[o];
                if (c == FREE) continue;
                int i = mix(c) & m;
                while (keys[i] != FREE) i = (i + 1) & m;
                keys[i] = c;
                values[i] = oldV[o];
            }
        }
    }
}
