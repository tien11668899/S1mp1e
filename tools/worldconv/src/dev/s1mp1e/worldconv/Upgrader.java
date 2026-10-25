package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.References;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A world up to a newer version: the game's own DataFixerUpper for every piece of data (and, from a 1.21 world, the
 * 26.1 folder layout), then every sidecar the world carries is restored wherever its replacement is still in place
 * ({@link Restore}), and every replaced item stack anywhere gets its original back.
 */
final class Upgrader extends Converter {
    private final int target;
    private final List<Sidecar> sidecars = new ArrayList<>();
    private final Restore restore;

    Upgrader(Path world, int src, Version to) throws Exception {
        this(world, src, to, new Report("up", Version.nameOf(src), to.name()));
    }

    Upgrader(Path world, int src, Version to, Report report) throws Exception {
        super(world, src, to, report);
        this.target = to.dataVersion();
        this.restore = new Restore(target, report);
        for (Path p : Sidecar.all(world)) {
            Sidecar s = Sidecar.read(p);
            if (s.sourceVersion > target) {
                report.note(p.getFileName() + " 來自比 " + to.name() + " 更新的版本，保留給之後再升版時還原。");
                continue;
            }
            sidecars.add(s);
            restore.add(s);
        }
    }

    @Override
    void prepare() throws Exception {
        if (src < FileLayout.VERSION && target >= FileLayout.VERSION) FileLayout.up(world, src);
    }

    @Override
    void finish() throws Exception {
        applyFileDiffs();
        report.note("用 " + to.name() + " 遊戲本身的升級程式（遊戲開啟舊世界時用的同一套）轉換。");
        if (!sidecars.isEmpty()) report.note("已還原 " + sidecars.size() + " 次之前降版時保存的原物（替代品還在原位的部分）。");
        report.write(world.resolve(Sidecar.DIR));
        // a fully restored world keeps no sidecar: the next downgrade writes its own
        for (Path p : Sidecar.all(world)) {
            if (Sidecar.read(p).sourceVersion <= target) Files.delete(p);
        }
    }

    @Override
    CompoundTag chunk(CompoundTag chunk, String dim) {
        int cdv = chunk.getIntOr("DataVersion", src);
        return restore.chunk((CompoundTag) Dfu.update(References.CHUNK, chunk, cdv, target), dim);
    }

    @Override
    CompoundTag entityChunk(CompoundTag tag, String dim) {
        int cdv = tag.getIntOr("DataVersion", src);
        return restore.entityChunk((CompoundTag) Dfu.update(References.ENTITY_CHUNK, tag, cdv, target), dim);
    }

    @Override
    CompoundTag poiChunk(CompoundTag tag, String dim) {
        return (CompoundTag) Dfu.update(References.POI_CHUNK, tag, tag.getIntOr("DataVersion", src), target);
    }

    @Override
    CompoundTag player(CompoundTag tag, String name) {
        Tag t = Dfu.update(References.PLAYER, tag, tag.getIntOr("DataVersion", src), target);
        return restore.player((CompoundTag) t, name);
    }

    @Override
    com.google.gson.JsonObject advancements(com.google.gson.JsonObject json, String player) {
        return restore.advancements(updateJson(References.ADVANCEMENTS, json), player);
    }

    @Override
    com.google.gson.JsonObject stats(com.google.gson.JsonObject json, String player) {
        return restore.stats(updateJson(References.STATS, json), player);
    }

    private com.google.gson.JsonObject updateJson(DSL.TypeReference ref, com.google.gson.JsonObject json) {
        int from = json.has("DataVersion") ? json.get("DataVersion").getAsInt() : src;
        if (from >= target) return json;
        return Dfu.fixer().update(ref, new Dynamic<>(com.mojang.serialization.JsonOps.INSTANCE, (com.google.gson.JsonElement) json), from, target)
                .getValue().getAsJsonObject();
    }

    @Override
    void levelAndSavedData() throws Exception {
        CompoundTag level = readLevel();
        CompoundTag data = level.getCompoundOrEmpty("Data");
        data = (CompoundTag) Dfu.update(References.LEVEL, data, data.getIntOr("DataVersion", src), target);
        writeLevel(level, data);
        eachSaved((f, tag) -> (CompoundTag) Dfu.update(f.type(), tag, tag.getIntOr("DataVersion", src), target));
    }

    /** The file diffs of a sidecar from exactly this version: level.dat, saved data, players, advancements, statistics. */
    private void applyFileDiffs() throws Exception {
        for (var e : restore.fileDiffs.entrySet()) {
            String rel = e.getKey();
            CompoundTag fields = e.getValue().getCompoundOrEmpty("fields");
            Path p = world.resolve(rel);
            if (fields.contains(Diffs.WHOLE)) {
                CompoundTag f = fields.getCompoundOrEmpty(Diffs.WHOLE);
                if (f.contains("v") && !Files.exists(p)) {
                    writeWhole(p, f.get("v"));
                    restore.applied++;
                } else if (!f.contains("v") && Files.exists(p) && Diffs.unchanged(f, wholeOf(p))) {
                    Files.delete(p);
                    restore.applied++;
                }
                continue;
            }
            if (!Files.exists(p)) continue;
            if (rel.endsWith(".json")) {
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                CompoundTag asTags = new CompoundTag();
                for (String k : json.keySet()) asTags.putString(k, json.get(k).toString());
                restore.applied += Diffs.apply(asTags, fields);
                com.google.gson.JsonObject out = new com.google.gson.JsonObject();
                for (String k : asTags.keySet()) out.add(k, com.google.gson.JsonParser.parseString(asTags.getStringOr(k, "null")));
                Files.writeString(p, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(out));
                continue;
            }
            CompoundTag file = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
            CompoundTag part = partOf(rel, file);
            restore.applied += Diffs.apply(part, fields);
            NbtIo.writeCompressed(file, p);
        }
        if (restore.applied > 0) report.add("restored_details", restore.applied + " fields", "", restore.applied, null);
    }

    /** The compound a file diff is about: level.dat's Data, a saved data file's data, a player file itself. */
    static CompoundTag partOf(String rel, CompoundTag file) {
        if (rel.equals("level.dat")) return file.getCompoundOrEmpty("Data");
        if (SavedFiles.type(rel) != null && file.contains("data")) return file.getCompoundOrEmpty("data");
        return file;
    }

    /** A whole file as one tag (for a file the way back adds or that only one side has). */
    static Tag wholeOf(Path p) throws Exception {
        if (p.toString().endsWith(".json")) return StringTag.valueOf(Files.readString(p));
        return NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
    }

    private static void writeWhole(Path p, Tag v) throws Exception {
        Files.createDirectories(p.getParent());
        if (p.toString().endsWith(".json")) Files.writeString(p, v.asString().orElse("{}"));
        else NbtIo.writeCompressed((CompoundTag) v, p);
    }

    static final String Downgrade_NOTE = "(changed in the older version: kept as it is)";

    static String uuidKey(int[] u) {
        return Arrays.toString(u);
    }
}
