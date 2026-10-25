package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.References;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/**
 * The world folder layout of data version 4772 (26.1) and 4773, both ways. Up mirrors the game's own file fixes
 * (DimensionStorageFileFix, PlayerStorageFileFix, LevelDatToSavedDataFileFix, RemoveObsoleteFilesFileFix,
 * ResourcePackLocationFileFix, GeneratedStructuresRenameFileFix, and 4899's hardcore game rule); down puts every
 * file back where a 1.21 world keeps it and the saved data 26.1 split off level.dat back into it.
 */
final class FileLayout {
    private FileLayout() {}

    static final int VERSION = 4772;
    private static final String[][] VANILLA = {{"minecraft:overworld", "", "dimensions/minecraft/overworld"},
            {"minecraft:the_nether", "DIM-1", "dimensions/minecraft/the_nether"}, {"minecraft:the_end", "DIM1", "dimensions/minecraft/the_end"}};

    // ---- down -------------------------------------------------------------------------------------------------------

    /** level.dat parts 26.1 moved to saved data: {file, level.dat key}. */
    private static final String[][] LEVEL_PARTS = {
            {"data/minecraft/game_rules.dat", "game_rules"},
            {"data/minecraft/weather.dat", "weather_data"},
            {"data/minecraft/world_clocks.dat", "world_clocks"},
            {"data/minecraft/world_gen_settings.dat", "world_gen_settings"},
            {"data/minecraft/scheduled_events.dat", "scheduled_events"},
            {"data/minecraft/wandering_trader.dat", "wandering_trader_migration_data"},
            {"data/minecraft/custom_boss_events.dat", "CustomBossEvents"},
            {"dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat", "dragon_fight"},
            {"dimensions/minecraft/overworld/data/minecraft/world_border.dat", "world_border"}};

    /**
     * The world back in the 1.21 layout; {@code data} (level.dat's Data, at 4772) gets the split-off parts back, each as
     * the reverse steps of its own type leave it at {@code from}.
     */
    static void down(Path w, CompoundTag data, int from) throws IOException {
        for (String[] part : LEVEL_PARTS) {
            Path p = w.resolve(part[0]);
            if (!Files.exists(p)) continue;
            CompoundTag file = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
            DSL.TypeReference type = SavedFiles.type(part[0]);
            file = (CompoundTag) Steps.reverse(type, file, file.getIntOr("DataVersion", from), VERSION);
            data.put(part[1], file.getCompoundOrEmpty("data"));
            retry(() -> Files.delete(p));
        }
        // the nether's and the end's own borders: the overworld's was taken (1.21 has one border for all)
        retry(() -> Files.deleteIfExists(w.resolve("dimensions/minecraft/the_nether/data/minecraft/world_border.dat")));
        retry(() -> Files.deleteIfExists(w.resolve("dimensions/minecraft/the_end/data/minecraft/world_border.dat")));

        for (String[] v : VANILLA) {
            Path dim = w.resolve(v[2]), legacy = v[1].isEmpty() ? w : w.resolve(v[1]);
            for (String sub : new String[]{"region", "entities", "poi"}) move(dim.resolve(sub), legacy.resolve(sub));
            Path data26 = dim.resolve("data/minecraft");
            move(data26.resolve("chunk_tickets.dat"), legacy.resolve("data/chunks.dat"));
            move(data26.resolve("raids.dat"), legacy.resolve(v[1].equals("DIM1") ? "data/raids_end.dat" : "data/raids.dat"));
        }
        // other dimensions keep their folder; their saved data moves up out of data/minecraft
        for (Path n : entries(w.resolve("dimensions"))) {
            for (Path p : entries(n)) {
                if (!Files.isDirectory(p) || isVanilla(w, p)) continue;
                Path d = p.resolve("data");
                move(d.resolve("minecraft/chunk_tickets.dat"), d.resolve("chunks.dat"));
                move(d.resolve("minecraft/raids.dat"), d.resolve("raids.dat"));
                move(d.resolve("minecraft/world_border.dat"), d.resolve("world_border.dat"));
                deleteIfEmpty(d.resolve("minecraft"));
            }
        }
        Path dd = w.resolve("data");
        move(dd.resolve("minecraft/scoreboard.dat"), dd.resolve("scoreboard.dat"));
        move(dd.resolve("minecraft/stopwatches.dat"), dd.resolve("stopwatches.dat"));
        move(dd.resolve("minecraft/random_sequences.dat"), dd.resolve("random_sequences.dat"));
        move(dd.resolve("minecraft/maps/last_id.dat"), dd.resolve("idcounts.dat"));
        // listings are read (and closed) before anything moves: a move out of a folder still being listed fails on
        // Windows with "used by another process" (seen in the saves folder)
        for (Path p : entries(dd.resolve("minecraft/maps"))) {
            if (p.getFileName().toString().matches("\\d+\\.dat")) move(p, dd.resolve("map_" + p.getFileName()));
        }
        for (Path ns : entries(dd)) {
            if (Files.isDirectory(ns)) move(ns.resolve("command_storage.dat"), dd.resolve("command_storage_" + ns.getFileName() + ".dat"));
        }
        move(w.resolve("players/data"), w.resolve("playerdata"));
        move(w.resolve("players/advancements"), w.resolve("advancements"));
        move(w.resolve("players/stats"), w.resolve("stats"));
        for (Path ns : entries(w.resolve("generated"))) {
            if (Files.isDirectory(ns)) move(ns.resolve("structure"), ns.resolve("structures"));
        }
        move(w.resolve("resourcepacks/resources.zip"), w.resolve("resources.zip"));
        for (String e : new String[]{"data/minecraft/maps", "data/minecraft", "players", "resourcepacks"}) deleteIfEmpty(w.resolve(e));
        for (String[] v : VANILLA) {
            for (String e : new String[]{"data/minecraft", "data", ""}) deleteIfEmpty(w.resolve(v[2]).resolve(e));
        }
        deleteIfEmpty(w.resolve("dimensions/minecraft"));
        deleteIfEmpty(w.resolve("dimensions"));
    }

    private static boolean isVanilla(Path w, Path dimDir) {
        for (String[] v : VANILLA) if (w.resolve(v[2]).equals(dimDir)) return true;
        return false;
    }

    /** level.dat's Player again (1.21 keeps the single player's data there as well as in playerdata/). */
    static void putPlayerBack(Path w, CompoundTag data) throws IOException {
        Tag u = data.remove("singleplayer_uuid");
        if (!(u instanceof IntArrayTag ia) || ia.getAsIntArray().length != 4) return;
        UUID uuid = UUIDUtil.uuidFromIntArray(ia.getAsIntArray());
        Path p = w.resolve("playerdata").resolve(uuid + ".dat");
        if (Files.exists(p)) data.put("Player", NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap()));
    }

    // ---- up ---------------------------------------------------------------------------------------------------------

    /** A 1.21-layout world (level.dat at {@code from}) in the 4773 layout, level.dat left at 4772 for the DFU to go on from. */
    static void up(Path w, int from) throws IOException {
        // DimensionStorageFileFix
        for (Path n : entries(w.resolve("dimensions"))) {
            for (Path p : entries(n)) {
                if (!Files.isDirectory(p)) continue;
                Path d = p.resolve("data");
                move(d.resolve("chunks.dat"), d.resolve("minecraft/chunk_tickets.dat"));
                move(d.resolve("raids.dat"), d.resolve("minecraft/raids.dat"));
                move(d.resolve("world_border.dat"), d.resolve("minecraft/world_border.dat"));
            }
        }
        for (String[] v : VANILLA) {
            Path legacyData = (v[1].isEmpty() ? w : w.resolve(v[1])).resolve("data");
            Path to = w.resolve(v[2]).resolve("data/minecraft");
            move(legacyData.resolve("chunks.dat"), to.resolve("chunk_tickets.dat"));
            move(legacyData.resolve("raids.dat"), to.resolve("raids.dat"));
            move(legacyData.resolve("raids_end.dat"), to.resolve("raids.dat"));
            move(legacyData.resolve("world_border.dat"), to.resolve("world_border.dat"));
        }
        deleteIfEmpty(w.resolve("DIM1/data"));
        deleteIfEmpty(w.resolve("DIM-1/data"));
        Path data = w.resolve("data");
        move(data.resolve("scoreboard.dat"), data.resolve("minecraft/scoreboard.dat"));
        move(data.resolve("stopwatches.dat"), data.resolve("minecraft/stopwatches.dat"));
        move(data.resolve("idcounts.dat"), data.resolve("minecraft/maps/last_id.dat"));
        move(data.resolve("random_sequences.dat"), data.resolve("minecraft/random_sequences.dat"));
        for (Path p : entries(data)) {
            if (!Files.isRegularFile(p)) continue;
            String n = p.getFileName().toString();
            if (n.matches("command_storage_([a-z0-9_.-]+)\\.dat")) {
                move(p, data.resolve(n.substring("command_storage_".length(), n.length() - 4)).resolve("command_storage.dat"));
            } else if (n.matches("map_(\\d+)\\.dat")) {
                move(p, data.resolve("minecraft/maps").resolve(n.substring(4)));
            }
        }
        for (String[] v : VANILLA) {
            Path legacy = v[1].isEmpty() ? w : w.resolve(v[1]);
            for (String sub : new String[]{"region", "entities", "poi"}) move(legacy.resolve(sub), w.resolve(v[2]).resolve(sub));
        }
        deleteIfEmpty(w.resolve("DIM-1"));
        deleteIfEmpty(w.resolve("DIM1"));
        // PlayerStorageFileFix
        move(w.resolve("advancements"), w.resolve("players/advancements"));
        move(w.resolve("playerdata"), w.resolve("players/data"));
        move(w.resolve("stats"), w.resolve("players/stats"));
        // LevelDatToSavedDataFileFix
        Path levelPath = w.resolve("level.dat");
        CompoundTag root = NbtIo.readCompressed(levelPath, NbtAccounter.unlimitedHeap());
        CompoundTag raw = root.getCompoundOrEmpty("Data");
        int dv = raw.getIntOr("DataVersion", from);
        CompoundTag c = (CompoundTag) Dfu.update(References.LEVEL, raw, dv, VERSION);
        extract(c, "dragon_fight", w.resolve("dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat"));
        if (c.remove("Player") instanceof CompoundTag player) {
            CompoundTag fixed = (CompoundTag) Dfu.update(References.PLAYER, player, player.getIntOr("DataVersion", dv), VERSION);
            if (fixed.getIntArray("UUID").isPresent()) c.put("singleplayer_uuid", new IntArrayTag(fixed.getIntArray("UUID").get()));
            else {
                fixed.putInt("DataVersion", VERSION);
                Path p = w.resolve("players/data/00000000-0000-0000-0000-000000000000.dat");
                Files.createDirectories(p.getParent());
                NbtIo.writeCompressed(fixed, p);
                c.put("singleplayer_uuid", new IntArrayTag(new int[4]));
            }
        }
        extract(c, "wandering_trader_migration_data", w.resolve("data/minecraft/wandering_trader.dat"));
        extract(c, "CustomBossEvents", w.resolve("data/minecraft/custom_boss_events.dat"));
        extract(c, "weather_data", w.resolve("data/minecraft/weather.dat"));
        extract(c, "scheduled_events", w.resolve("data/minecraft/scheduled_events.dat"));
        if (c.remove("world_border") instanceof CompoundTag border) {
            for (String[] v : VANILLA) {
                CompoundTag b = border.copy();
                double div = v[0].equals("minecraft:the_nether") ? 8.0 : 1.0;
                b.putDouble("center_x", b.getDoubleOr("center_x", 0) / div);
                b.putDouble("center_z", b.getDoubleOr("center_z", 0) / div);
                writeSaved(w.resolve(v[2]).resolve("data/minecraft/world_border.dat"), b, VERSION);
            }
        }
        extract(c, "game_rules", w.resolve("data/minecraft/game_rules.dat"));
        if (c.remove("world_gen_settings") instanceof CompoundTag wgs) {
            Tag fixed = Dfu.update(References.WORLD_GEN_SETTINGS, wgs, c.getIntOr("DataVersion", dv), VERSION);
            writeSaved(w.resolve("data/minecraft/world_gen_settings.dat"), fixed, VERSION);
        }
        extract(c, "world_clocks", w.resolve("data/minecraft/world_clocks.dat"));
        c.putInt("DataVersion", VERSION);
        root.put("Data", c);
        NbtIo.writeCompressed(root, levelPath);
        // RemoveObsoleteFilesFileFix, ResourcePackLocationFileFix
        for (String f : new String[]{"data/villages.dat", "data/villages_end.dat", "data/villages_nether.dat"}) Files.deleteIfExists(w.resolve(f));
        move(w.resolve("resources.zip"), w.resolve("resourcepacks/resources.zip"));
        // GeneratedStructuresRenameFileFix (4773)
        for (Path ns : entries(w.resolve("generated"))) {
            if (Files.isDirectory(ns)) move(ns.resolve("structures"), ns.resolve("structure"));
        }
        // ReenableSpectatorsGenerateChunksInHardcoreWorldsFileFix (4899)
        boolean single = c.contains("singleplayer_uuid");
        boolean hardcore = c.getCompoundOrEmpty("difficulty_settings").getBooleanOr("hardcore", false);
        Path rules = w.resolve("data/minecraft/game_rules.dat");
        if (single && hardcore && Files.exists(rules)) {
            CompoundTag file = NbtIo.readCompressed(rules, NbtAccounter.unlimitedHeap());
            CompoundTag d = ((CompoundTag) Dfu.update(References.SAVED_DATA_GAME_RULES, file, file.getIntOr("DataVersion", VERSION), 4899)).getCompoundOrEmpty("data");
            d.putBoolean("minecraft:spectators_generate_chunks", true);
            writeSaved(rules, d, 4899);
        }
    }

    private static void extract(CompoundTag level, String key, Path to) throws IOException {
        Tag t = level.remove(key);
        if (t != null) writeSaved(to, t, VERSION);
    }

    static void writeSaved(Path p, Tag data, int version) throws IOException {
        CompoundTag file = new CompoundTag();
        file.put("data", data);
        file.putInt("DataVersion", version);
        Files.createDirectories(p.getParent());
        NbtIo.writeCompressed(file, p);
    }

    // ---- files ------------------------------------------------------------------------------------------------------

    static void move(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        Files.createDirectories(to.getParent());
        if (Files.isDirectory(from) && Files.isDirectory(to)) {
            // merge into an existing folder (a dimension folder that already holds data/)
            for (Path p : entries(from)) move(p, to.resolve(p.getFileName().toString()));
            deleteIfEmpty(from);
            return;
        }
        retry(() -> Files.move(from, to));
    }

    interface Io {
        void run() throws IOException;
    }

    /**
     * A file operation tried again for a few seconds: a file just copied into the saves folder is often still open in a
     * virus scanner or the launcher's world list ("being used by another process").
     */
    static void retry(Io op) throws IOException {
        for (int i = 0; ; i++) {
            try {
                op.run();
                return;
            } catch (java.nio.file.FileSystemException e) {
                if (i >= 40 || e instanceof java.nio.file.NoSuchFileException || e instanceof java.nio.file.FileAlreadyExistsException) throw e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** The entries of {@code dir} (none when it is missing), read and closed before anything in it moves. */
    static java.util.List<Path> entries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return java.util.List.of();
        try (var s = Files.list(dir)) {
            return s.toList();
        }
    }

    static void deleteIfEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            if (s.findAny().isPresent()) return;
        }
        retry(() -> Files.delete(dir));
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
