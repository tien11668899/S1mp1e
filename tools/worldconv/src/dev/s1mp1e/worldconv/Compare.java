package dev.s1mp1e.worldconv;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Two worlds of the same version compared cell by cell (block states, biomes), block entity by position, entity by
 * UUID, player by file: proves a down-and-up round trip brought everything back. Prints DIFF lines and a SUMMARY.
 */
final class Compare {
    private Compare() {}

    private static final Map<String, Long> counts = new TreeMap<>();
    private static final Map<String, String> samples = new HashMap<>();

    static void run(Path a, Path b) throws Exception {
        WorldLayout la = WorldLayout.detect(a), lb = WorldLayout.detect(b);
        for (WorldLayout.Dimension da : la.dimensions()) {
            WorldLayout.Dimension db = lb.dimensions().stream().filter(d -> d.id().equals(da.id())).findFirst().orElse(null);
            if (db == null) {
                diff("dimension missing", da.id());
                continue;
            }
            for (Path pa : Main.regionFiles(da.region())) {
                Map<Long, CompoundTag> ca = chunks(pa), cb = chunks(db.region().resolve(pa.getFileName()));
                for (var e : ca.entrySet()) {
                    CompoundTag x = e.getValue(), y = cb.get(e.getKey());
                    if (y == null) {
                        diff("chunk missing", da.id() + " " + pa.getFileName());
                        continue;
                    }
                    chunk(da.id(), x, y);
                }
            }
            for (Path pa : Main.regionFiles(da.entities())) {
                Map<String, CompoundTag> ea = new HashMap<>(), eb = new HashMap<>();
                for (CompoundTag c : chunks(pa).values()) collect(c.getListOrEmpty("Entities"), ea);
                for (CompoundTag c : chunks(db.entities().resolve(pa.getFileName())).values()) collect(c.getListOrEmpty("Entities"), eb);
                for (var e : ea.entrySet()) {
                    CompoundTag y = eb.get(e.getKey());
                    if (y == null) diff("entity missing", e.getValue().getStringOr("id", "?") + " " + e.getKey());
                    else nbt("entity " + e.getValue().getStringOr("id", "?"), e.getValue(), y, e.getKey());
                }
            }
        }
        Path pa = la.kind().equals("dimensions") ? a.resolve("players").resolve("data") : a.resolve("playerdata");
        Path pb = lb.kind().equals("dimensions") ? b.resolve("players").resolve("data") : b.resolve("playerdata");
        if (Files.isDirectory(pa)) {
            try (var s = Files.list(pa)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".dat")).toList()) {
                    Path q = pb.resolve(p.getFileName());
                    if (!Files.exists(q)) {
                        diff("player missing", p.getFileName().toString());
                        continue;
                    }
                    CompoundTag x = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap()), y = NbtIo.readCompressed(q, NbtAccounter.unlimitedHeap());
                    nbt("player", x, y, p.getFileName().toString());
                }
            }
        }
        files(a, b, la);
        for (var e : counts.entrySet()) Out.line("DIFF " + e.getValue() + " " + e.getKey() + "   e.g. " + samples.get(e.getKey()));
        Out.line("SUMMARY " + counts.values().stream().mapToLong(Long::longValue).sum() + " differences");
    }

    private static void chunk(String dim, CompoundTag x, CompoundTag y) {
        Map<Integer, CompoundTag> sa = sections(x), sb = sections(y);
        int cx = x.getIntOr("xPos", 0), cz = x.getIntOr("zPos", 0);
        for (var e : sa.entrySet()) {
            CompoundTag s2 = sb.get(e.getKey());
            String[] p = cells(e.getValue().getCompoundOrEmpty("block_states"), 4096, 4, true);
            String[] q = s2 == null ? new String[4096] : cells(s2.getCompoundOrEmpty("block_states"), 4096, 4, true);
            for (int c = 0; c < 4096; c++) {
                if (p[c] != null && !p[c].equals(q[c])) {
                    int bx = cx * 16 + (c & 15), bz = cz * 16 + ((c >> 4) & 15), by = e.getKey() * 16 + (c >> 8);
                    diff("block " + p[c] + " -> " + q[c], dim + " " + bx + "," + by + "," + bz);
                }
            }
            String[] bp = cells(e.getValue().getCompoundOrEmpty("biomes"), 64, 1, false);
            String[] bq = s2 == null ? new String[64] : cells(s2.getCompoundOrEmpty("biomes"), 64, 1, false);
            for (int c = 0; c < 64; c++) {
                if (bp[c] != null && !bp[c].equals(bq[c])) diff("biome " + bp[c] + " -> " + bq[c], dim + " chunk " + cx + "," + cz);
            }
        }
        // everything else a chunk carries (structures, ticks, heightmaps, status ...) compared field by field
        CompoundTag restA = x.copy(), restB = y.copy();
        for (String k : new String[]{"sections", "block_entities", "isLightOn"}) {
            restA.remove(k);
            restB.remove(k);
        }
        nbt("chunk", restA, restB, dim + " chunk " + cx + "," + cz);
        Map<String, CompoundTag> ba = new HashMap<>(), bb = new HashMap<>();
        for (Tag t : x.getListOrEmpty("block_entities")) if (t instanceof CompoundTag be) ba.put(pos(be), withoutEmpty(be));
        for (Tag t : y.getListOrEmpty("block_entities")) if (t instanceof CompoundTag be) bb.put(pos(be), withoutEmpty(be));
        for (var e : ba.entrySet()) {
            CompoundTag o = bb.get(e.getKey());
            if (o == null) diff("block entity missing " + e.getValue().getStringOr("id", "?"), dim + " " + e.getKey());
            else nbt("block entity " + e.getValue().getStringOr("id", "?"), e.getValue(), o, dim + " " + e.getKey());
        }
    }

    /** level.dat (but when it was last played), every saved data file, advancements and statistics. */
    private static void files(Path a, Path b, WorldLayout la) throws Exception {
        CompoundTag x = NbtIo.readCompressed(a.resolve("level.dat"), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data").copy();
        CompoundTag y = NbtIo.readCompressed(b.resolve("level.dat"), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data").copy();
        for (String k : new String[]{"LastPlayed", "Version", "DataVersion"}) {
            x.remove(k);
            y.remove(k);
        }
        nbt("level.dat", x, y, "level.dat");
        for (SavedFiles.F f : SavedFiles.list(a)) {
            Path q = b.resolve(f.rel());
            if (!Files.exists(q)) {
                diff("saved data missing", f.rel());
                continue;
            }
            nbt("saved data " + f.rel(), NbtIo.readCompressed(f.path(), NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data"),
                    NbtIo.readCompressed(q, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data"), f.rel());
        }
        for (SavedFiles.F f : SavedFiles.list(b)) if (!Files.exists(a.resolve(f.rel()))) diff("saved data added", f.rel());
        String prefix = la.kind().equals("dimensions") ? "players/" : "";
        for (String dir : new String[]{prefix + "advancements", prefix + "stats"}) {
            Path d = a.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (var s = Files.list(d)) {
                for (Path p : s.filter(z -> z.toString().endsWith(".json")).toList()) {
                    Path q = b.resolve(dir).resolve(p.getFileName().toString());
                    if (!Files.exists(q)) {
                        diff("json missing", dir + "/" + p.getFileName());
                        continue;
                    }
                    com.google.gson.JsonObject ja = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                    com.google.gson.JsonObject jb = com.google.gson.JsonParser.parseString(Files.readString(q)).getAsJsonObject();
                    ja.remove("DataVersion");
                    jb.remove("DataVersion");
                    TreeSet<String> keys = new TreeSet<>(ja.keySet());
                    keys.addAll(jb.keySet());
                    for (String k : keys) {
                        if (!java.util.Objects.equals(ja.get(k), jb.get(k))) diff(dir + " ." + k, p.getFileName() + "  " + ja.get(k) + " -> " + jb.get(k));
                    }
                }
            }
        }
    }

    /** Field-level differences between two compounds (one DIFF per differing top-level key). */
    private static void nbt(String what, CompoundTag x, CompoundTag y, String where) {
        TreeSet<String> keys = new TreeSet<>(x.keySet());
        keys.addAll(y.keySet());
        for (String k : keys) {
            Tag a = x.get(k), b = y.get(k);
            if (a == null ? b != null : !a.equals(b)) diff(what + " ." + k, where + "  " + abbrev(a) + " -> " + abbrev(b));
        }
    }

    private static String abbrev(Tag t) {
        String s = String.valueOf(t);
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static String[] cells(CompoundTag container, int size, int minBits, boolean blocks) {
        ListTag pal = container.getListOrEmpty("palette");
        String[] out = new String[size];
        if (pal.isEmpty()) return out;
        int[] idx = PackedArray.decode(container.getLongArray("data").orElse(null), size, pal.size(), minBits);
        String[] names = new String[pal.size()];
        for (int i = 0; i < pal.size(); i++) {
            names[i] = blocks && pal.get(i) instanceof CompoundTag s ? Downgrader.stateString(s) : pal.get(i).asString().orElse("?");
        }
        for (int c = 0; c < size; c++) out[c] = names[idx[c]];
        return out;
    }

    private static Map<Integer, CompoundTag> sections(CompoundTag chunk) {
        Map<Integer, CompoundTag> m = new HashMap<>();
        for (Tag t : chunk.getListOrEmpty("sections")) if (t instanceof CompoundTag s) m.put((int) s.getByteOr("Y", (byte) 0), s);
        return m;
    }

    private static void collect(ListTag entities, Map<String, CompoundTag> into) {
        for (Tag t : entities) {
            if (!(t instanceof CompoundTag e)) continue;
            into.put(Upgrader.uuidKey(e.getIntArray("UUID").orElse(new int[0])), e);
            collect(e.getListOrEmpty("Passengers"), into);
        }
    }

    /** An empty component map is the same as none (26.x writes one, 1.21 does not). */
    private static CompoundTag withoutEmpty(CompoundTag be) {
        if (!(be.get("components") instanceof CompoundTag c) || !c.isEmpty()) return be;
        CompoundTag copy = be.copy();
        copy.remove("components");
        return copy;
    }

    private static String pos(CompoundTag be) {
        return be.getIntOr("x", 0) + "," + be.getIntOr("y", 0) + "," + be.getIntOr("z", 0);
    }

    private static Map<Long, CompoundTag> chunks(Path region) throws Exception {
        Map<Long, CompoundTag> m = new HashMap<>();
        if (!Files.exists(region)) return m;
        try (Regions.Reader r = new Regions.Reader(region)) {
            for (Regions.Chunk c : r.chunks()) {
                try (DataInputStream in = c.open()) {
                    m.put(((long) c.x() << 32) ^ (c.z() & 0xffffffffL), NbtIo.read(in));
                }
            }
        }
        return m;
    }

    private static void diff(String key, String where) {
        counts.merge(key, 1L, Long::sum);
        samples.putIfAbsent(key, where);
    }
}
