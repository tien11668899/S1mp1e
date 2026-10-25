package dev.s1mp1e.worldconv;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Walks a world (already copied by the caller) and rewrites every file that carries versioned data: region chunks,
 * entity chunks, POI chunks (in parallel, a region file per task), players, advancements and statistics, level.dat
 * and saved data.
 */
abstract class Converter {
    final Path world;
    final int src;
    final Version to;
    final Report report;
    final AtomicInteger failed = new AtomicInteger();

    Converter(Path world, int src, Version to, Report report) {
        this.world = world;
        this.src = src;
        this.to = to;
        this.report = report;
    }

    abstract CompoundTag chunk(CompoundTag chunk, String dim);

    abstract CompoundTag entityChunk(CompoundTag tag, String dim);

    abstract CompoundTag poiChunk(CompoundTag tag, String dim);

    abstract CompoundTag player(CompoundTag tag, String name);

    /** A player's advancement progress (players/advancements/<uuid>.json) as the target should read it. */
    abstract com.google.gson.JsonObject advancements(com.google.gson.JsonObject json, String player);

    /** A player's statistics (players/stats/<uuid>.json) as the target should read it. */
    abstract com.google.gson.JsonObject stats(com.google.gson.JsonObject json, String player);

    /** level.dat and every saved data file (after chunks and players: a layout change moves them all). */
    abstract void levelAndSavedData() throws Exception;

    /** Before anything is read (the Upgrader brings an old layout to the new one here). */
    void prepare() throws Exception {}

    void finish() throws Exception {}

    void run() throws Exception {
        prepare();
        WorldLayout layout = WorldLayout.detect(world);
        record Job(Path file, String dim, String kind) {}
        List<Job> jobs = new ArrayList<>();
        for (WorldLayout.Dimension d : layout.dimensions()) {
            for (Path p : Main.regionFiles(d.region())) jobs.add(new Job(p, d.id(), "chunk"));
            for (Path p : Main.regionFiles(d.entities())) jobs.add(new Job(p, d.id(), "entities"));
            for (Path p : Main.regionFiles(d.poi())) jobs.add(new Job(p, d.id(), "poi"));
        }
        if (!jobs.isEmpty()) {
            int threads = Math.max(1, Math.min(jobs.size(), Runtime.getRuntime().availableProcessors() - 1));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger done = new AtomicInteger();
            List<Future<?>> fs = new ArrayList<>();
            for (Job j : jobs) {
                fs.add(pool.submit(() -> {
                    BiFunction<CompoundTag, String, CompoundTag> f = switch (j.kind) {
                        case "chunk" -> this::chunk;
                        case "entities" -> this::entityChunk;
                        default -> this::poiChunk;
                    };
                    rewriteRegion(j.file, j.dim, j.kind, f);
                    Out.line("PROGRESS " + done.incrementAndGet() + " " + jobs.size());
                    return null;
                }));
            }
            pool.shutdown();
            for (Future<?> f : fs) f.get();
        }
        players(layout);
        boolean modern = layout.kind().equals("dimensions");
        playerJson(modern ? world.resolve("players").resolve("advancements") : world.resolve("advancements"), true);
        playerJson(modern ? world.resolve("players").resolve("stats") : world.resolve("stats"), false);
        levelAndSavedData();
        if (failed.get() > 0) report.note(failed.get() + " 個區塊轉換失敗，保持原樣（詳見記錄檔）。");
        finish();
    }

    private void rewriteRegion(Path file, String dim, String kind, BiFunction<CompoundTag, String, CompoundTag> f) throws Exception {
        List<Regions.Chunk> chunks;
        try (Regions.Reader r = new Regions.Reader(file)) {
            chunks = r.chunks();
        }
        if (chunks.isEmpty()) return;
        List<CompoundTag> outs = new ArrayList<>(chunks.size());
        for (Regions.Chunk c : chunks) {
            CompoundTag tag;
            try (DataInputStream in = c.open()) {
                tag = NbtIo.read(in);
            }
            CompoundTag out;
            try {
                out = f.apply(tag.copy(), dim);
                out.putInt("DataVersion", to.dataVersion());
            } catch (Throwable t) {
                failed.incrementAndGet();
                System.err.println("chunk " + c.x() + "," + c.z() + " in " + file + ": " + t);
                t.printStackTrace();
                out = tag;
            }
            outs.add(out);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".s1tmp");
        Files.deleteIfExists(tmp);
        ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim));
        RegionStorageInfo info = new RegionStorageInfo(world.getFileName().toString(), key, kind);
        try (RegionFile rf = new RegionFile(info, tmp, file.getParent(), RegionFileVersion.VERSION_DEFLATE, false)) {
            for (int i = 0; i < chunks.size(); i++) {
                try (DataOutputStream o = rf.getChunkDataOutputStream(new ChunkPos(chunks.get(i).x(), chunks.get(i).z()))) {
                    NbtIo.write(outs.get(i), o);
                }
            }
        }
        FileLayout.retry(() -> Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING));
    }

    private void players(WorldLayout layout) throws Exception {
        Path dir = layout.kind().equals("dimensions") ? world.resolve("players").resolve("data") : world.resolve("playerdata");
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".dat")).toList()) {
                CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
                CompoundTag out = player(tag, p.getFileName().toString().replace(".dat", ""));
                out.putInt("DataVersion", to.dataVersion());
                FileLayout.retry(() -> NbtIo.writeCompressed(out, p));
            }
        }
    }

    private void playerJson(Path dir, boolean advancements) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".json")).toList()) {
                String player = p.getFileName().toString().replace(".json", "");
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                com.google.gson.JsonObject out = advancements ? advancements(json, player) : stats(json, player);
                out.addProperty("DataVersion", to.dataVersion());
                Files.writeString(p, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(out));
            }
        }
    }

    // ---- level.dat and saved data helpers ---------------------------------------------------------------------------

    CompoundTag readLevel() throws Exception {
        return NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap());
    }

    /** level.dat with {@code data} as its Data, stamped with the target version (level.dat_old follows). */
    void writeLevel(CompoundTag level, CompoundTag data) throws Exception {
        data.putInt("DataVersion", to.dataVersion());
        CompoundTag v = data.getCompoundOrEmpty("Version");
        v.putInt("Id", to.dataVersion());
        v.putString("Name", to.name());
        v.putString("Series", "main");
        v.putBoolean("Snapshot", false);
        data.put("Version", v);
        level.put("Data", data);
        Path p = world.resolve("level.dat");
        FileLayout.retry(() -> NbtIo.writeCompressed(level, p));
        Path old = world.resolve("level.dat_old");
        if (Files.exists(old)) Files.copy(p, old, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Every saved data file through {@code f} (the file's whole compound: DataVersion and data), stamped with the target version. */
    void eachSaved(java.util.function.BiFunction<SavedFiles.F, CompoundTag, CompoundTag> f) throws Exception {
        for (SavedFiles.F file : SavedFiles.list(world)) {
            try {
                CompoundTag tag = NbtIo.readCompressed(file.path(), NbtAccounter.unlimitedHeap());
                CompoundTag out = f.apply(file, tag);
                out.putInt("DataVersion", to.dataVersion());
                FileLayout.retry(() -> NbtIo.writeCompressed(out, file.path()));
            } catch (Exception e) {
                report.note("saved data " + file.rel() + " left as it was: " + e);
                e.printStackTrace();
            }
        }
    }

    static UnaryOperator<CompoundTag> identity() {
        return t -> t;
    }
}
