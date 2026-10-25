package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * S1mp1e world converter (docs/WORLD_CONVERT.md). Runs on the 26.3 game jar's classpath: it uses the game's own DFU
 * schemas, data fixers, region files and NBT code. Prints machine-readable lines for the launcher:
 * {@code WORLD/DIM/PARSE} (info), {@code PROGRESS done total}, {@code DONE <report dir>}, {@code ERR <message>}.
 *
 * <pre>
 * worldconv info    &lt;world&gt;
 * worldconv convert &lt;world&gt; &lt;out&gt; &lt;version&gt;   copy the world to out and convert the copy (up or down)
 * worldconv compare &lt;worldA&gt; &lt;worldB&gt;          what differs block by block, block entity and entity
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Out.capture();
        if (args.length == 0) {
            Out.line("usage: worldconv info <world> | convert <world> <out> <version> | compare <a> <b>");
            System.exit(2);
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            switch (args[0]) {
                case "info" -> info(Path.of(args[1]));
                case "convert" -> convert(Path.of(args[1]), Path.of(args[2]), Version.byName(args[3]));
                case "compare" -> Compare.run(Path.of(args[1]), Path.of(args[2]));
                case "selftest" -> SelfTest.run();
                case "items" -> {
                    // the Items of the block entity at x y z in two worlds, slot by slot
                    java.util.function.Function<Path, java.util.Map<Integer, String>> load = w -> {
                        java.util.Map<Integer, String> m = new java.util.TreeMap<>();
                        try {
                            WorldLayout.Dimension d = WorldLayout.detect(w).dimensions().stream().filter(v -> v.id().equals("minecraft:overworld")).findFirst().orElseThrow();
                            int x = Integer.parseInt(args[3]), y = Integer.parseInt(args[4]), z = Integer.parseInt(args[5]);
                            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                            try (Regions.Reader r = new Regions.Reader(d.region().resolve("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca"))) {
                                for (Regions.Chunk c : r.chunks()) {
                                    if (c.x() != cx || c.z() != cz) continue;
                                    CompoundTag ch;
                                    try (DataInputStream in = c.open()) { ch = NbtIo.read(in); }
                                    for (Tag t : ch.getListOrEmpty("block_entities")) {
                                        if (t instanceof CompoundTag be && be.getIntOr("x", 0) == x && be.getIntOr("y", 0) == y && be.getIntOr("z", 0) == z) {
                                            for (Tag it : be.getListOrEmpty("Items")) if (it instanceof CompoundTag s) m.put((int) s.getByteOr("Slot", (byte) 0), s.toString());
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) { throw new RuntimeException(e); }
                        return m;
                    };
                    var a = load.apply(Path.of(args[1])); var b = load.apply(Path.of(args[2]));
                    for (int s : a.keySet()) if (!a.get(s).equals(b.get(s))) Out.line("SLOT " + s + " a " + a.get(s) + " | b " + b.get(s));
                }
                case "nbt" -> {
                    CompoundTag t = NbtIo.readCompressed(Path.of(args[1]), NbtAccounter.unlimitedHeap());
                    Tag v = args.length > 2 ? t.getCompoundOrEmpty("Data").get(args[2]) != null ? t.getCompoundOrEmpty("Data").get(args[2]) : t.getCompoundOrEmpty("data").get(args[2]) : t;
                    Out.line(String.valueOf(v));
                }
                case "sidecar" -> {
                    java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
                    java.util.Map<String, String> sample = new java.util.TreeMap<>();
                    for (Path p : Sidecar.all(Path.of(args[1]))) {
                        Sidecar s = Sidecar.read(p);
                        Out.line(p.getFileName() + " blocks=" + s.blocks.size() + " biomes=" + s.biomes.size() + " be=" + s.blockEntities.size() + " entities=" + s.entities.size() + " extras=" + s.extras.size() + " diffs=" + s.diffs.size());
                        for (CompoundTag d : s.diffs) {
                            for (String f : d.getCompoundOrEmpty("fields").keySet()) {
                                String k = d.getStringOr("kind", "") + " " + d.getStringOr("id", "") + " ." + f;
                                kinds.merge(k, 1, Integer::sum);
                                sample.putIfAbsent(k, d.getStringOr("key", "") + " " + d.getCompoundOrEmpty("fields").get(f));
                            }
                        }
                    }
                    kinds.forEach((k, n) -> Out.line(n + "  " + k + "   e.g. " + sample.get(k).substring(0, Math.min(400, sample.get(k).length()))));
                }
                case "dump" -> dump(Path.of(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]));
                case "player" -> {
                    WorldLayout l = WorldLayout.detect(Path.of(args[1]));
                    Path dir = l.kind().equals("dimensions") ? Path.of(args[1], "players", "data") : Path.of(args[1], "playerdata");
                    try (var s = Files.list(dir)) {
                        for (Path p : s.toList()) Out.line(p.getFileName() + " " + NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap()).get(args.length > 2 ? args[2] : "Inventory"));
                    }
                }
                default -> {
                    Out.line("ERR unknown command " + args[0]);
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Out.line("ERR " + e);
            System.exit(1);
        }
        System.exit(0);
    }

    static int dataVersion(Path world) throws Exception {
        CompoundTag level = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        return level.getCompoundOrEmpty("Data").getIntOr("DataVersion", -1);
    }

    static void convert(Path world, Path out, Version to) throws Exception {
        int src = dataVersion(world);
        if (src < 0) throw new IllegalStateException("no DataVersion in level.dat (a world from before 1.9 is not supported yet)");
        if (Files.exists(out)) throw new IllegalStateException(out + " already exists");
        copy(world, out);
        Out.line("CONVERT " + Version.nameOf(src) + " -> " + to.name() + (to.dataVersion() < src ? " (down)" : " (up)"));
        if (to.dataVersion() < src) {
            new Downgrader(out, src, to, world).run();
        } else {
            // a stage per earlier downgrade this world went through (oldest first): each sidecar is restored at the version
            // it was made from, where its field diffs apply exactly
            Report report = new Report("up", Version.nameOf(src), to.name());
            int cur = src;
            for (Path p : Sidecar.all(out)) {
                int s = Sidecar.read(p).sourceVersion;
                if (s > cur && s < to.dataVersion()) {
                    new Upgrader(out, cur, new Version(Version.nameOf(s), s), report).run();
                    cur = s;
                }
            }
            new Upgrader(out, cur, to, report).run();
        }
        Out.line("DONE " + out.resolve(Sidecar.DIR));
    }

    private static void copy(Path from, Path to) throws Exception {
        try (var s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path rel = from.relativize(p);
                if (rel.toString().equals("session.lock")) continue;
                Path d = to.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(d);
                else Files.copy(p, d, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static void info(Path world) throws Exception {
        CompoundTag level = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag data = level.getCompoundOrEmpty("Data");
        int dv = data.getIntOr("DataVersion", -1);
        String name = data.getCompoundOrEmpty("Version").getStringOr("Name", "?");
        WorldLayout layout = WorldLayout.detect(world);
        Out.line("WORLD " + world.getFileName() + " dataVersion=" + dv + " version=" + name + " layout=" + layout.kind());
        for (WorldLayout.Dimension d : layout.dimensions()) {
            Out.line("DIM " + d.id() + " region=" + regionFiles(d.region()).length + " entities=" + regionFiles(d.entities()).length
                    + " poi=" + regionFiles(d.poi()).length);
        }
        DataFixer fixer = DataFixers.getDataFixer();
        for (WorldLayout.Dimension d : layout.dimensions()) {
            Path[] files = regionFiles(d.region());
            if (files.length == 0) continue;
            try (Regions.Reader r = new Regions.Reader(files[0])) {
                for (Regions.Chunk c : r.chunks()) {
                    CompoundTag chunk;
                    try (DataInputStream in = c.open()) {
                        chunk = NbtIo.read(in);
                    }
                    int cdv = chunk.getIntOr("DataVersion", dv);
                    Schema schema = fixer.getSchema(DataFixUtils.makeKey(cdv));
                    Type<?> type = schema.getType(References.CHUNK);
                    var parsed = type.readTyped(new Dynamic<>(NbtOps.INSTANCE, chunk));
                    Out.line("PARSE " + d.id() + " chunk " + c.x() + "," + c.z() + " dataVersion=" + cdv + " -> "
                            + (parsed.result().isPresent() ? "ok" : "FAILED " + parsed.error().map(Object::toString).orElse("")));
                    break;
                }
            }
            break;
        }
    }

    /** The block entity at x,y,z and the chunk's palette there (debugging). */
    static void dump(Path world, String dim, int x, int y, int z) throws Exception {
        WorldLayout.Dimension d = WorldLayout.detect(world).dimensions().stream().filter(v -> v.id().equals(dim)).findFirst().orElseThrow();
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        Path region = d.region().resolve("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca");
        try (Regions.Reader r = new Regions.Reader(region)) {
            for (Regions.Chunk c : r.chunks()) {
                if (c.x() != cx || c.z() != cz) continue;
                CompoundTag chunk;
                try (DataInputStream in = c.open()) {
                    chunk = NbtIo.read(in);
                }
                for (var t : chunk.getListOrEmpty("block_entities")) {
                    if (t instanceof CompoundTag be && be.getIntOr("x", 0) == x && be.getIntOr("y", 0) == y && be.getIntOr("z", 0) == z) Out.line("BE " + be);
                }
                for (var t : chunk.getListOrEmpty("sections")) {
                    if (t instanceof CompoundTag s && s.getByteOr("Y", (byte) 0) == Math.floorDiv(y, 16)) {
                        Out.line("PALETTE " + s.getCompoundOrEmpty("block_states").get("palette"));
                    }
                }
            }
        }
    }

    static Path[] regionFiles(Path dir) throws Exception {
        if (dir == null || !Files.isDirectory(dir)) return new Path[0];
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toArray(Path[]::new);
        }
    }
}
