package dev.s1mp1e.worldconv;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a downgrade replaced, with its original, kept inside the converted world
 * ({@code s1mp1e-conversion/sidecar-<from>-<to>.dat}), so converting the world back up restores it. Item stacks are not
 * here: a replaced stack carries its original in its own {@code minecraft:custom_data} and follows the item.
 * Originals are stored as the source version wrote them ({@link #sourceVersion}); a restore first upgrades them.
 */
final class Sidecar {
    static final String DIR = "s1mp1e-conversion";

    final int sourceVersion, targetVersion;
    final List<CompoundTag> blocks = new ArrayList<>();
    final List<CompoundTag> biomes = new ArrayList<>();
    final List<CompoundTag> blockEntities = new ArrayList<>();
    final List<CompoundTag> entities = new ArrayList<>();
    /** Per-player records the target drops on load: unknown recipes, advancement progress, statistics. */
    final List<CompoundTag> extras = new ArrayList<>();
    /** Structure starts and references (per chunk) of structures the target does not have. */
    final List<CompoundTag> structures = new ArrayList<>();
    /** Single block entity fields a reverse step drops or a forward fix would set differently (value, or absent). */
    final List<CompoundTag> fields = new ArrayList<>();
    /**
     * What the way back would not bring back by itself, field by field: the value the source version had, and a hash of
     * the value converting back produces when nothing changed in between (restored only then). Kinds: chunk, section,
     * block_entity, entity (by UUID), file (level.dat, saved data, players, advancements, statistics).
     */
    final List<CompoundTag> diffs = new ArrayList<>();

    Sidecar(int sourceVersion, int targetVersion) {
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
    }

    /** Positions (bit set over the section's 4096 cells, y-z-x order) that held {@code original} and now hold {@code replacement}. */
    synchronized void block(String dim, int cx, int cz, int sy, long[] cells, CompoundTag original, CompoundTag replacement) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.putInt("cx", cx);
        t.putInt("cz", cz);
        t.putInt("sy", sy);
        t.put("cells", new LongArrayTag(cells));
        t.put("original", original);
        t.put("replacement", replacement);
        blocks.add(t);
    }

    /** Biome cells (bit set over the section's 64 cells) that held {@code original}. */
    synchronized void biome(String dim, int cx, int cz, int sy, long cells, String original, String replacement) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.putInt("cx", cx);
        t.putInt("cz", cz);
        t.putInt("sy", sy);
        t.putLong("cells", cells);
        t.putString("original", original);
        t.putString("replacement", replacement);
        biomes.add(t);
    }

    synchronized void blockEntity(String dim, int x, int y, int z, CompoundTag original, String replacementId) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        t.put("original", original);
        t.putString("replacement", replacementId == null ? "" : replacementId);
        blockEntities.add(t);
    }

    synchronized void entity(String dim, int[] uuid, CompoundTag original, String replacementId) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.put("uuid", new IntArrayTag(uuid));
        t.put("original", original);
        t.putString("replacement", replacementId);
        entities.add(t);
    }

    synchronized void field(String dim, int x, int y, int z, String id, String field, Tag value) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        t.putString("id", id);
        t.putString("field", field);
        if (value != null) t.put("value", value.copy());
        fields.add(t);
    }

    synchronized void structures(String dim, int cx, int cz, CompoundTag removed) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.putInt("cx", cx);
        t.putInt("cz", cz);
        t.put("removed", removed);
        structures.add(t);
    }

    /** {@code fields}: name → {v: original value (absent: the field was absent), h: hash of the expected value, a: 1b when it is expected absent}. */
    synchronized void diff(String kind, String dim, String key, String id, CompoundTag fields) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putString("dim", dim);
        t.putString("key", key);
        t.putString("id", id == null ? "" : id);
        t.put("fields", fields);
        diffs.add(t);
    }

    synchronized void extra(String kind, String player, Tag data) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        t.putString("player", player);
        t.put("data", data);
        extras.add(t);
    }

    boolean isEmpty() {
        return blocks.isEmpty() && biomes.isEmpty() && blockEntities.isEmpty() && entities.isEmpty() && extras.isEmpty() && structures.isEmpty() && fields.isEmpty()
                && diffs.isEmpty();
    }

    Path file(Path world) {
        return world.resolve(DIR).resolve("sidecar-" + sourceVersion + "-" + targetVersion + ".dat");
    }

    void write(Path world) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("SourceDataVersion", sourceVersion);
        root.putInt("TargetDataVersion", targetVersion);
        root.putLong("Created", System.currentTimeMillis());
        root.put("Blocks", list(blocks));
        root.put("Biomes", list(biomes));
        root.put("BlockEntities", list(blockEntities));
        root.put("Entities", list(entities));
        root.put("Extras", list(extras));
        root.put("Structures", list(structures));
        root.put("Fields", list(fields));
        root.put("Diffs", list(diffs));
        Files.createDirectories(world.resolve(DIR));
        NbtIo.writeCompressed(root, file(world));
    }

    static Sidecar read(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        Sidecar s = new Sidecar(root.getIntOr("SourceDataVersion", 0), root.getIntOr("TargetDataVersion", 0));
        fill(root, "Blocks", s.blocks);
        fill(root, "Biomes", s.biomes);
        fill(root, "BlockEntities", s.blockEntities);
        fill(root, "Entities", s.entities);
        fill(root, "Extras", s.extras);
        fill(root, "Structures", s.structures);
        fill(root, "Fields", s.fields);
        fill(root, "Diffs", s.diffs);
        return s;
    }

    /** Every sidecar in {@code world}, oldest conversion first (the one closest to the world's current version). */
    static List<Path> all(Path world) throws IOException {
        Path dir = world.resolve(DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("sidecar-\\d+-\\d+\\.dat"))
                    .sorted((a, b) -> Integer.compare(target(a), target(b)))
                    .toList();
        }
    }

    private static int target(Path p) {
        String[] parts = p.getFileName().toString().replace(".dat", "").split("-");
        return Integer.parseInt(parts[2]);
    }

    private static ListTag list(List<CompoundTag> l) {
        ListTag t = new ListTag();
        t.addAll(l);
        return t;
    }

    private static void fill(CompoundTag root, String key, List<CompoundTag> into) {
        for (Tag t : root.getListOrEmpty(key)) {
            if (t instanceof CompoundTag c) into.add(c);
        }
    }
}
