package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.References;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A world one or more versions down (docs/WORLD_CONVERT.md). Per chunk / entity chunk / player: the registry pass on
 * the source data (whatever the target lacks → the closest thing it has, original kept), the reverse steps newest
 * first, a clean-up of what the target cannot read; then the way back is simulated on the result (the game's own
 * upgrade plus {@link Restore}) and whatever would not come back is kept as field diffs. level.dat, saved data and
 * players are checked the same way on a copy of those files at the end. Works on a copy; the caller made it.
 */
final class Downgrader extends Converter {
    final Registry target;
    final Replacements repl;
    final Sidecar sidecar;
    final Schema srcSchema;
    /** The source version's registries, when the converter ships them (block defaults for compact states). */
    final Registry sourceReg;
    /** The world as it was (read only): level.dat, saved data and players are compared against it. */
    final Path original;
    final int tdv;
    private final Pass itemPass, entityPass, statePass, cleanItems, cleanEntities;
    /** The records of the chunk being converted on this thread (so its way back can be simulated with them alone). */
    private final ThreadLocal<Sidecar> local = new ThreadLocal<>();
    private final Map<CompoundTag, Boolean> lossless = new ConcurrentHashMap<>();
    private final Restore itemSim;
    /** What the target itself writes per entity / block entity type and for the player (null: no dump shipped). */
    private final KnownKeys known;

    Downgrader(Path world, int src, Version to, Path original) {
        super(world, src, to, new Report("down", Version.nameOf(src), to.name()));
        this.target = Registry.of(to.name());
        this.repl = Replacements.forTarget(to.name());
        this.tdv = to.dataVersion();
        this.sidecar = new Sidecar(src, tdv);
        this.srcSchema = Dfu.schema(src);
        this.original = original;
        Registry sr = null;
        try { sr = Registry.of(Version.nameOf(src)); } catch (RuntimeException e) { /* not a shipped version */ }
        this.sourceReg = sr;
        this.itemPass = new Pass(srcSchema, References.ITEM_STACK, this::item);
        this.entityPass = new Pass(srcSchema, References.ENTITY, this::entity);
        this.statePass = new Pass(srcSchema, References.BLOCK_STATE, this::nestedBlockState);
        this.cleanItems = new Pass(Dfu.schema(tdv), References.ITEM_STACK, d -> tagged(d, this::cleanItem));
        this.cleanEntities = new Pass(Dfu.schema(tdv), References.ENTITY, d -> tagged(d, this::cleanEntity));
        this.itemSim = new Restore(src, null);
        this.known = KnownKeys.load(to, src);
    }

    /** Fields of {@code c} the target does not know, taken out (the way back finds them missing and keeps them). */
    private void stripUnknown(CompoundTag c, java.util.Set<String> keys, String what, String where) {
        if (known == null || keys == null) return;
        List<String> gone = KnownKeys.strip(c, keys);
        if (!gone.isEmpty()) report.add("detail", what + " " + gone, "(kept for the way back)", 1, where);
    }

    private Sidecar sc() {
        Sidecar s = local.get();
        return s != null ? s : sidecar;
    }

    @Override
    void finish() throws Exception {
        sidecar.write(world);
        fileDiffs();
        sidecar.write(world);
        report.note("原本的東西都保存在世界的 " + Sidecar.DIR + " 資料夾裡（方塊 " + sidecar.blocks.size() + " 組、生態域 " + sidecar.biomes.size()
                + " 組、方塊資料 " + sidecar.blockEntities.size() + " 個、生物 " + sidecar.entities.size() + " 隻、細節 " + sidecar.diffs.size()
                + " 筆）；被替換或舊版存不下細節的物品把原物存在自己身上。之後用啟動器把這個世界轉回新版，會把還在原位的東西全部還原。");
        report.note("在舊版裡被你改掉的替代品（例如拆掉的方塊）會照你改過的樣子保留，不會被還原蓋掉。");
        report.write(world.resolve(Sidecar.DIR));
    }

    // ---- per file kind -----------------------------------------------------------------------------------------------

    @Override
    CompoundTag chunk(CompoundTag chunk, String dim) {
        CompoundTag before = chunk.copy();
        Sidecar mine = new Sidecar(src, tdv);
        local.set(mine);
        try {
            int cdv = chunk.getIntOr("DataVersion", src);
            int cx = chunk.getIntOr("xPos", 0), cz = chunk.getIntOr("zPos", 0);
            for (Tag t : chunk.getListOrEmpty("sections")) {
                if (t instanceof CompoundTag section) section(section, dim, cx, cz);
            }
            ListTag bes = chunk.getListOrEmpty("block_entities");
            ListTag keep = new ListTag();
            for (Tag t : bes) {
                if (t instanceof CompoundTag be) {
                    CompoundTag out = blockEntity(be, dim);
                    if (out != null) keep.add(out);
                }
            }
            if (chunk.contains("block_entities")) chunk.put("block_entities", keep);
            structures(chunk, dim, cx, cz);
            blockTicks(chunk);
            CompoundTag d = (CompoundTag) Steps.reverse(References.CHUNK, chunk, cdv, tdv);
            if (src >= 4885 && tdv < 4885) bedBlockEntities(d, cx, cz);
            d = (CompoundTag) clean(References.CHUNK, d);
            verifyChunk(before, d, dim, cx, cz, mine);
            return d;
        } finally {
            local.remove();
            merge(mine);
        }
    }

    @Override
    CompoundTag entityChunk(CompoundTag tag, String dim) {
        CompoundTag before = tag.copy();
        Sidecar mine = new Sidecar(src, tdv);
        local.set(mine);
        try {
            int cdv = tag.getIntOr("DataVersion", src);
            ListTag out = new ListTag();
            for (Tag t : tag.getListOrEmpty("Entities")) {
                if (t instanceof CompoundTag e) out.add(entityTree(e, dim));
            }
            tag.put("Entities", out);
            CompoundTag d = (CompoundTag) Steps.reverse(References.ENTITY_CHUNK, tag, cdv, tdv);
            d = (CompoundTag) clean(References.ENTITY_CHUNK, d);
            verifyEntities(before, d, dim, mine);
            return d;
        } finally {
            local.remove();
            merge(mine);
        }
    }

    private void merge(Sidecar mine) {
        synchronized (sidecar) {
            sidecar.blocks.addAll(mine.blocks);
            sidecar.biomes.addAll(mine.biomes);
            sidecar.blockEntities.addAll(mine.blockEntities);
            sidecar.entities.addAll(mine.entities);
            sidecar.extras.addAll(mine.extras);
            sidecar.structures.addAll(mine.structures);
            sidecar.fields.addAll(mine.fields);
            sidecar.diffs.addAll(mine.diffs);
        }
    }

    @Override
    CompoundTag poiChunk(CompoundTag tag, String dim) {
        int cdv = tag.getIntOr("DataVersion", src);
        CompoundTag sections = tag.getCompoundOrEmpty("Sections");
        for (String y : sections.keySet()) {
            CompoundTag s = sections.getCompoundOrEmpty(y);
            ListTag keep = new ListTag();
            for (Tag r : s.getListOrEmpty("Records")) {
                if (r instanceof CompoundTag rec && !target.has("point_of_interest_type", rec.getStringOr("type", ""))) {
                    report.add("poi", rec.getStringOr("type", ""), "(rebuilt by the game)", 1, dim);
                    continue;
                }
                keep.add(r);
            }
            s.put("Records", keep);
        }
        return (CompoundTag) Steps.reverse(References.POI_CHUNK, tag, cdv, tdv);
    }

    @Override
    com.google.gson.JsonObject advancements(com.google.gson.JsonObject json, String player) {
        if (src >= 4311 && tdv < 4311) renameKeys(json, Steps262.ADVANCEMENT_RENAMES);
        com.google.gson.JsonObject removed = new com.google.gson.JsonObject();
        for (String k : new ArrayList<>(json.keySet())) {
            if (k.equals("DataVersion") || target.has("data_advancement", k)) continue;
            removed.add(k, json.remove(k));
            report.add("advancement", k, "(kept for the way back)", 1, "player " + player);
        }
        if (!removed.isEmpty()) sidecar.extra("advancements", player, StringTag.valueOf(removed.toString()));
        return json;
    }

    private static void renameKeys(com.google.gson.JsonObject json, Map<String, String> renames) {
        for (String k : new ArrayList<>(json.keySet())) {
            String to = renames.get(k);
            if (to != null && !json.has(to)) json.add(to, json.remove(k));
        }
    }

    @Override
    com.google.gson.JsonObject stats(com.google.gson.JsonObject json, String player) {
        com.google.gson.JsonObject stats = json.has("stats") ? json.getAsJsonObject("stats") : new com.google.gson.JsonObject();
        com.google.gson.JsonObject removed = new com.google.gson.JsonObject();
        for (String cat : new ArrayList<>(stats.keySet())) {
            com.google.gson.JsonObject entries = stats.getAsJsonObject(cat);
            if (!target.has("stat_type", cat)) {
                removed.add(cat, stats.remove(cat));
                report.add("statistic", cat, "(kept for the way back)", entries.size(), "player " + player);
                continue;
            }
            com.google.gson.JsonObject gone = new com.google.gson.JsonObject();
            for (String k : new ArrayList<>(entries.keySet())) {
                String renamed = statAfter(cat, k);
                if (!renamed.equals(k) && !entries.has(renamed)) entries.add(renamed, entries.remove(k));
                if (statKnown(cat, renamed)) continue;
                gone.add(renamed, entries.remove(renamed));
            }
            if (!gone.isEmpty()) {
                removed.add(cat, gone);
                report.add("statistic", cat + " " + String.join(",", gone.keySet()), "(kept for the way back)", gone.size(), "player " + player);
            }
        }
        if (!removed.isEmpty()) sidecar.extra("stats", player, StringTag.valueOf(removed.toString()));
        return json;
    }

    private String statAfter(String category, String key) {
        return switch (category) {
            case "minecraft:mined" -> Steps.blockAfter(key, src, tdv);
            case "minecraft:killed", "minecraft:killed_by" -> Steps.entityAfter(key, src, tdv);
            case "minecraft:custom" -> key;
            default -> Steps.itemAfter(key, src, tdv);
        };
    }

    private boolean statKnown(String category, String key) {
        return switch (category) {
            case "minecraft:mined" -> target.blocks.containsKey(key);
            case "minecraft:killed", "minecraft:killed_by" -> target.has("entity_type", key);
            case "minecraft:custom" -> target.has("custom_stat", key);
            default -> target.has("item", key);
        };
    }

    /** Recipes the target does not have leave the recipe book (the game would drop them) for the sidecar. */
    private void recipeBook(CompoundTag player, String name) {
        CompoundTag originalBook = player.getCompoundOrEmpty("recipeBook").copy();
        CompoundTag book = originalBook.copy();
        CompoundTag removed = new CompoundTag();
        for (String list : new String[]{"recipes", "toBeDisplayed"}) {
            ListTag keep = new ListTag(), gone = new ListTag();
            for (Tag t : book.getListOrEmpty(list)) {
                String id = t.asString().orElse("");
                if (src >= 4769 && tdv < 4769) id = Steps262.RECIPE_RENAMES.getOrDefault(id, id);
                if (target.has("data_recipe", id)) keep.add(StringTag.valueOf(id));
                else gone.add(t);
            }
            if (!gone.isEmpty()) {
                removed.put(list, gone);
                if (list.equals("recipes")) report.add("recipe", gone.size() + " recipes", "(kept for the way back)", gone.size(), "player " + name);
            }
            if (book.contains(list)) book.put(list, keep);
        }
        if (!removed.isEmpty()) {
            CompoundTag rec = new CompoundTag();
            rec.put("removed", removed);
            rec.put("original", originalBook);
            sidecar.extra("recipes", name, rec);
        }
        if (!originalBook.isEmpty()) player.put("recipeBook", book);
    }

    @Override
    CompoundTag player(CompoundTag tag, String name) {
        int cdv = tag.getIntOr("DataVersion", src);
        recipeBook(tag, name);
        if (known != null) stripUnknown(tag, known.player(), "player", "player " + name);
        Pass.Ctx c = ctx("player", "player " + name);
        Tag t = Dfu.apply(srcSchema, References.PLAYER, itemPass.getRule(), tag);
        t = Dfu.apply(srcSchema, References.PLAYER, entityPass.getRule(), t);
        c.changed = false;
        t = Steps.reverse(References.PLAYER, t, cdv, tdv);
        CompoundTag p = (CompoundTag) clean(References.PLAYER, t);
        cleanEntity(p);
        return p;
    }

    @Override
    void levelAndSavedData() throws Exception {
        CompoundTag level = readLevel();
        CompoundTag data = level.getCompoundOrEmpty("Data");
        int ldv = data.getIntOr("DataVersion", src);
        if (ldv >= FileLayout.VERSION && tdv < FileLayout.VERSION) {
            data = (CompoundTag) Steps.reverse(References.LEVEL, data, ldv, FileLayout.VERSION);
            FileLayout.down(world, data, src);
            eachSaved((f, tag) -> savedForTarget(f, (CompoundTag) Steps.reverse(f.type(), tag, tag.getIntOr("DataVersion", src), tdv)));
            data = (CompoundTag) Steps.reverse(References.LEVEL, data, FileLayout.VERSION, tdv);
            levelForTarget(data);
            FileLayout.putPlayerBack(world, data);
            report.note("世界資料夾改回 " + to.name() + " 的結構（region、playerdata、data 等），level.dat 合併回遊戲規則、天氣、時間、世界邊界等設定。");
        } else {
            data = (CompoundTag) Steps.reverse(References.LEVEL, data, ldv, tdv);
            eachSaved((f, tag) -> (CompoundTag) Steps.reverse(f.type(), tag, tag.getIntOr("DataVersion", src), tdv));
        }
        writeLevel(level, data);
    }

    /**
     * Saved data as 1.21 reads it: 26.x leaves out fields at their default, and 1.21 takes a missing field as 0 / empty
     * where the default is something else (an objective without CriteriaName is "unknown criteria").
     */
    private CompoundTag savedForTarget(SavedFiles.F f, CompoundTag tag) {
        if (f.type() == References.SAVED_DATA_SCOREBOARD) {
            for (Tag t : tag.getCompoundOrEmpty("data").getListOrEmpty("Objectives")) {
                if (t instanceof CompoundTag o && !o.contains("CriteriaName")) o.putString("CriteriaName", "dummy");
            }
        }
        return tag;
    }

    /** What the target's level.dat cannot hold: game rules it does not know (kept by the file diffs). */
    private void levelForTarget(CompoundTag data) {
        // boss bars: 26.x leaves out what is at its default, 1.21 reads a missing maximum as 0
        for (Tag t : data.getCompoundOrEmpty("CustomBossEvents").values()) {
            if (!(t instanceof CompoundTag b)) continue;
            if (!b.contains("Max")) b.putInt("Max", 100);
            if (!b.contains("Color")) b.putString("Color", "white");
            if (!b.contains("Overlay")) b.putString("Overlay", "progress");
        }
        Set<String> rules = target.list("gamerule");
        if (!rules.isEmpty() && data.get("GameRules") instanceof CompoundTag gr) {
            for (String k : new ArrayList<>(gr.keySet())) {
                if (!rules.contains(k)) {
                    gr.remove(k);
                    report.add("gamerule", k, "(kept for the way back)", 1, "level.dat");
                }
            }
        }
    }

    /** Starts and references of structures the target does not have (the game would drop them) go to the sidecar. */
    private void structures(CompoundTag chunk, String dim, int cx, int cz) {
        CompoundTag structs = chunk.getCompoundOrEmpty("structures");
        CompoundTag removed = new CompoundTag();
        for (String group : structs.keySet()) {
            CompoundTag g = structs.getCompoundOrEmpty(group);
            CompoundTag gone = new CompoundTag();
            for (String id : new ArrayList<>(g.keySet())) {
                if (target.has("data_worldgen_structure", id)) continue;
                gone.put(id, g.get(id).copy());
                g.remove(id);
                if (!group.equalsIgnoreCase("references")) report.add("structure", id, "(kept for the way back)", 1, dim + " chunk " + cx + "," + cz);
            }
            if (!gone.isEmpty()) removed.put(group, gone);
        }
        if (!removed.isEmpty()) sc().structures(dim, cx, cz, removed);
    }

    /** Scheduled ticks of replaced blocks tick the replacement instead (ticks are transient: nothing to restore). */
    private void blockTicks(CompoundTag chunk) {
        for (Tag t : chunk.getListOrEmpty("block_ticks")) {
            if (t instanceof CompoundTag tick) {
                String id = tick.getStringOr("i", "");
                if (!id.isEmpty() && !target.blocks.containsKey(Steps.blockAfter(id, src, tdv))) tick.putString("i", repl(id, "block", "minecraft:stone"));
            }
        }
    }

    /** 4885 backwards: beds had a block entity (their colour) until 26.1; 1.21 draws a bed without one invisibly. */
    private void bedBlockEntities(CompoundTag chunk, int cx, int cz) {
        Set<String> have = new java.util.HashSet<>();
        ListTag bes = chunk.getListOrEmpty("block_entities");
        for (Tag t : bes) if (t instanceof CompoundTag be) have.add(Diffs.POSITION.of(be));
        boolean added = false;
        for (Tag t : chunk.getListOrEmpty("sections")) {
            if (!(t instanceof CompoundTag section)) continue;
            int sy = section.getByteOr("Y", (byte) 0);
            CompoundTag states = section.getCompoundOrEmpty("block_states");
            ListTag palette = states.getListOrEmpty("palette");
            boolean[] bed = new boolean[palette.size()];
            boolean any = false;
            for (int i = 0; i < palette.size(); i++) {
                String n = asState(palette.get(i)).getStringOr("Name", "");
                bed[i] = n.endsWith("_bed") && target.has("block_entity_type", "minecraft:bed");
                any |= bed[i];
            }
            if (!any) continue;
            int[] idx = PackedArray.decode(states.getLongArray("data").orElse(null), 4096, palette.size(), 4);
            for (int c = 0; c < 4096; c++) {
                if (!bed[idx[c]]) continue;
                int x = cx * 16 + (c & 15), z = cz * 16 + ((c >> 4) & 15), y = sy * 16 + (c >> 8);
                if (!have.add(x + " " + y + " " + z)) continue;
                CompoundTag be = new CompoundTag();
                be.putString("id", "minecraft:bed");
                be.putInt("x", x);
                be.putInt("y", y);
                be.putInt("z", z);
                bes.add(be);
                added = true;
            }
        }
        if (added) chunk.put("block_entities", bes);
    }

    // ---- the way back, simulated -------------------------------------------------------------------------------------

    private void verifyChunk(CompoundTag x, CompoundTag d, String dim, int cx, int cz, Sidecar mine) {
        Restore sim = new Restore(src, null);
        sim.add(mine);
        CompoundTag u = sim.chunk((CompoundTag) Dfu.update(References.CHUNK, d.copy(), tdv, src), dim);
        String ck = cx + " " + cz;
        // light: the game works it out again for a chunk without it
        CompoundTag top = Diffs.between(x, u, Set.of("sections", "block_entities", "DataVersion", "isLightOn"));
        if (top != null) mine.diff("chunk", dim, ck, null, top);
        listDiffs("section", dim, ck + " ", x.getListOrEmpty("sections"), u.getListOrEmpty("sections"), Diffs.SECTION, mine);
        listDiffs("block_entity", dim, "", x.getListOrEmpty("block_entities"), u.getListOrEmpty("block_entities"), Diffs.POSITION, mine);
    }

    private void listDiffs(String kind, String dim, String prefix, ListTag xs, ListTag us, Diffs.Key key, Sidecar mine) {
        Map<String, CompoundTag> a = Diffs.byKey(xs, key), b = Diffs.byKey(us, key);
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            CompoundTag x = a.get(k), u = b.get(k);
            CompoundTag fields = x != null && u != null ? Diffs.between(x, u, kind.equals("section") ? LIGHT : Set.of()) : Diffs.whole(x, u);
            if (fields != null) mine.diff(kind, dim, prefix + k, x != null ? x.getStringOr("id", "") : u.getStringOr("id", ""), fields);
        }
    }

    private static final Set<String> LIGHT = Set.of("BlockLight", "SkyLight");

    private void verifyEntities(CompoundTag x, CompoundTag d, String dim, Sidecar mine) {
        Restore sim = new Restore(src, null);
        sim.add(mine);
        CompoundTag u = sim.entityChunk((CompoundTag) Dfu.update(References.ENTITY_CHUNK, d.copy(), tdv, src), dim);
        Map<String, CompoundTag> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        Diffs.flatten(x.getListOrEmpty("Entities"), a);
        Diffs.flatten(u.getListOrEmpty("Entities"), b);
        int[] pos = x.getIntArray("Position").orElse(new int[2]);
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            CompoundTag xe = a.get(k), ue = b.get(k);
            if (xe != null && ue == null) {
                // came back missing: the whole entity tree again (a passenger comes back with its vehicle)
                CompoundTag tree = treeOf(x.getListOrEmpty("Entities"), k);
                if (tree == null) continue;
                CompoundTag fields = Diffs.whole(tree, null);
                mine.diff("entity", dim, k, xe.getStringOr("id", ""), fields);
                mine.diffs.getLast().putString("chunk", pos[0] + " " + pos[1]);
                continue;
            }
            CompoundTag fields = xe != null ? Diffs.between(xe, ue, Set.of()) : Diffs.whole(null, ue);
            if (fields != null) mine.diff("entity", dim, k, (xe != null ? xe : ue).getStringOr("id", ""), fields);
        }
    }

    private static CompoundTag treeOf(ListTag trees, String uuid) {
        for (Tag t : trees) {
            if (t instanceof CompoundTag e && uuid.equals(Diffs.uuid(e))) return e;
        }
        return null;
    }

    /**
     * level.dat, saved data, players, advancements and statistics: the converted copies go up again in a scratch
     * folder (the same Upgrader a real conversion back uses) and are compared with the original world's.
     */
    private void fileDiffs() throws Exception {
        if (original == null) return;
        Path tmp = Files.createTempDirectory("s1mp1e-sim");
        try {
            for (String dir : new String[]{"data", "DIM-1/data", "DIM1/data", "playerdata", "advancements", "stats", "players", Sidecar.DIR}) {
                copyTree(world.resolve(dir), tmp.resolve(dir));
            }
            Path dims = world.resolve("dimensions");
            if (Files.isDirectory(dims)) {
                try (var s = Files.walk(dims)) {
                    for (Path p : s.filter(Files::isRegularFile).filter(p -> world.relativize(p).toString().replace('\\', '/').contains("/data/")).toList()) {
                        Path dst = tmp.resolve(world.relativize(p).toString());
                        Files.createDirectories(dst.getParent());
                        Files.copy(p, dst);
                    }
                }
            }
            Files.copy(world.resolve("level.dat"), tmp.resolve("level.dat"));
            new Upgrader(tmp, tdv, new Version(Version.nameOf(src), src), new Report("sim", "", "")).run();
            compareFiles(tmp);
        } finally {
            FileLayout.deleteTree(tmp);
        }
    }

    private void compareFiles(Path sim) throws Exception {
        Set<String> rels = new LinkedHashSet<>();
        rels.add("level.dat");
        for (SavedFiles.F f : SavedFiles.list(original)) rels.add(f.rel());
        for (SavedFiles.F f : SavedFiles.list(sim)) rels.add(f.rel());
        WorldLayout lo = WorldLayout.detect(original);
        String players = lo.kind().equals("dimensions") ? "players/" : "";
        for (String dir : new String[]{players + (players.isEmpty() ? "playerdata" : "data"), players + "advancements", players + "stats"}) {
            for (Path root : new Path[]{original, sim}) {
                Path d = root.resolve(dir);
                if (!Files.isDirectory(d)) continue;
                try (var s = Files.list(d)) {
                    for (Path p : s.filter(x -> x.toString().endsWith(".dat") || x.toString().endsWith(".json")).toList()) rels.add(dir + "/" + p.getFileName());
                }
            }
        }
        for (String rel : rels) {
            Path a = original.resolve(rel), b = sim.resolve(rel);
            if (!Files.exists(a) && !Files.exists(b)) continue;
            if (!Files.exists(a) || !Files.exists(b)) {
                CompoundTag fields = Diffs.whole(Files.exists(a) ? Upgrader.wholeOf(a) : null, Files.exists(b) ? Upgrader.wholeOf(b) : null);
                sidecar.diff("file", "", rel, null, fields);
                continue;
            }
            CompoundTag x, u;
            if (rel.endsWith(".json")) {
                x = jsonFields(a);
                u = jsonFields(b);
            } else {
                x = Upgrader.partOf(rel, NbtIo.readCompressed(a, NbtAccounter.unlimitedHeap()));
                u = Upgrader.partOf(rel, NbtIo.readCompressed(b, NbtAccounter.unlimitedHeap()));
            }
            CompoundTag fields = Diffs.between(x, u, Set.of("DataVersion", "Version", "LastPlayed"));
            if (fields != null) sidecar.diff("file", "", rel, null, fields);
        }
    }

    private static CompoundTag jsonFields(Path p) throws Exception {
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        CompoundTag t = new CompoundTag();
        for (String k : json.keySet()) t.putString(k, json.get(k).toString());
        return t;
    }

    private static void copyTree(Path from, Path to) throws Exception {
        if (!Files.exists(from)) return;
        try (var s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(d);
                else Files.copy(p, d);
            }
        }
    }

    // ---- what the target cannot read, after the reverse steps --------------------------------------------------------

    Tag clean(DSL.TypeReference ref, Tag t) {
        t = Dfu.apply(Dfu.schema(tdv), ref, cleanItems.getRule(), t);
        return Dfu.apply(Dfu.schema(tdv), ref, cleanEntities.getRule(), t);
    }

    private static Dynamic<?> tagged(Dynamic<?> d, java.util.function.Consumer<CompoundTag> f) {
        Tag t = d.convert(NbtOps.INSTANCE).getValue();
        if (t instanceof CompoundTag c) f.accept(c);
        return new Dynamic<>(NbtOps.INSTANCE, t);
    }

    /** A stack as the target holds it: components and registry entries it lacks removed (the stack carries its original). */
    private void cleanItem(CompoundTag stack) {
        if (!(stack.get("components") instanceof CompoundTag c)) return;
        for (String k : new ArrayList<>(c.keySet())) {
            String type = k.startsWith("!") ? k.substring(1) : k;
            if (!target.has("data_component_type", type)) c.remove(k);
        }
        for (String id : new String[]{"minecraft:enchantments", "minecraft:stored_enchantments"}) {
            if (c.get(id) instanceof CompoundTag e) {
                CompoundTag levels = e.contains("levels") ? e.getCompoundOrEmpty("levels") : e;
                for (String k : new ArrayList<>(levels.keySet())) if (!target.has("data_enchantment", k)) levels.remove(k);
            }
        }
        if (c.get("minecraft:potion_contents") instanceof CompoundTag pc) {
            if (pc.contains("potion") && !target.has("potion", pc.getStringOr("potion", ""))) pc.remove("potion");
            effects(pc.getListOrEmpty("custom_effects"), "id");
        } else if (c.get("minecraft:potion_contents") instanceof StringTag s && !target.has("potion", s.value())) {
            c.remove("minecraft:potion_contents");
        }
        if (c.get("minecraft:suspicious_stew_effects") instanceof ListTag l) effects(l, "id");
        if (c.get("minecraft:food") instanceof CompoundTag food) {
            ListTag l = food.getListOrEmpty("effects");
            l.removeIf(t -> t instanceof CompoundTag e && !(e.get("effect") instanceof CompoundTag eff && target.has("mob_effect", eff.getStringOr("id", ""))));
        }
        removeIfUnknown(c, "minecraft:jukebox_playable", "song", "data_jukebox_song");
        if (c.get("minecraft:trim") instanceof CompoundTag trim
                && (!known(trim.get("material"), "data_trim_material") || !known(trim.get("pattern"), "data_trim_pattern"))) {
            c.remove("minecraft:trim");
        }
        if (c.get("minecraft:banner_patterns") instanceof ListTag bp) {
            bp.removeIf(t -> t instanceof CompoundTag p && !known(p.get("pattern"), "data_banner_pattern"));
        }
        if (c.get("minecraft:instrument") instanceof StringTag s && !target.has("instrument", s.value())) c.remove("minecraft:instrument");
        if (c.get("minecraft:attribute_modifiers") instanceof CompoundTag am) {
            ListTag mods = am.getListOrEmpty("modifiers");
            mods.removeIf(t -> t instanceof CompoundTag m && !target.has("attribute", m.getStringOr("type", "")));
            for (Tag t : mods) {
                if (t instanceof CompoundTag m) {
                    for (String k : new ArrayList<>(m.keySet())) {
                        if (!Set.of("type", "id", "amount", "operation", "slot").contains(k)) m.remove(k);
                    }
                }
            }
        }
        if (c.get("minecraft:pot_decorations") instanceof ListTag pd) {
            for (int i = 0; i < pd.size(); i++) if (!target.has("item", pd.getStringOr(i, ""))) pd.set(i, StringTag.valueOf("minecraft:brick"));
        }
        if (c.get("minecraft:block_entity_data") instanceof CompoundTag bed && !target.has("block_entity_type", bed.getStringOr("id", ""))) {
            c.remove("minecraft:block_entity_data");
        }
        if (c.get("minecraft:entity_data") instanceof CompoundTag ed && !target.has("entity_type", ed.getStringOr("id", ""))) {
            c.remove("minecraft:entity_data");
        }
    }

    private boolean known(Tag ref, String registry) {
        // an inline definition (a compound) is the older game's own business; only references are checked
        return !(ref instanceof StringTag s) || target.has(registry, s.value());
    }

    private void removeIfUnknown(CompoundTag c, String component, String field, String registry) {
        if (c.get(component) instanceof CompoundTag v && !known(v.get(field), registry)) c.remove(component);
    }

    private void effects(ListTag l, String field) {
        l.removeIf(t -> t instanceof CompoundTag e && !target.has("mob_effect", e.getStringOr(field, "")));
    }

    /** An entity as the target holds it: attributes, effects and memories it lacks removed (kept by the entity diffs). */
    private void cleanEntity(CompoundTag e) {
        e.getListOrEmpty("attributes").removeIf(t -> t instanceof CompoundTag a && !target.has("attribute", a.getStringOr("id", "")));
        for (String k : new String[]{"active_effects", "ActiveEffects"}) {
            if (e.get(k) instanceof ListTag l) effects(l, "id");
        }
        if (e.get("Brain") instanceof CompoundTag brain && brain.get("memories") instanceof CompoundTag mem) {
            for (String k : new ArrayList<>(mem.keySet())) if (!target.has("memory_module_type", k)) mem.remove(k);
        }
        if (e.get("Particle") instanceof CompoundTag p && !target.has("particle_type", p.getStringOr("type", ""))) {
            p.putString("type", replQuiet(p.getStringOr("type", ""), "particle", "minecraft:effect"));
        }
        if (e.getStringOr("id", "").equals("minecraft:painting") && e.get("variant") instanceof StringTag v && !target.has("data_painting_variant", v.value())) {
            e.putString("variant", replQuiet(v.value(), "painting", "minecraft:kebab"));
        }
    }

    // ---- blocks and biomes ---------------------------------------------------------------------------------------------

    private void section(CompoundTag section, String dim, int cx, int cz) {
        int sy = section.getByteOr("Y", (byte) 0);
        CompoundTag states = section.getCompoundOrEmpty("block_states");
        ListTag palette = states.getListOrEmpty("palette");
        if (!palette.isEmpty()) {
            CompoundTag[] repl = new CompoundTag[palette.size()];
            boolean any = false;
            for (int i = 0; i < palette.size(); i++) {
                repl[i] = blockState(palette.get(i));
                any |= repl[i] != null;
            }
            if (any) rewritePalette(states, palette, repl, 4096, 4, (i, cells) -> {
                CompoundTag orig = asState(palette.get(i));
                sc().block(dim, cx, cz, sy, cells, orig, repl[i].copy());
                long n = 0;
                for (long w : cells) n += Long.bitCount(w);
                report.add("block", stateString(orig), stateString(repl[i]), n, firstCell(dim, cx, sy, cz, cells));
            });
        }
        CompoundTag biomes = section.getCompoundOrEmpty("biomes");
        ListTag bp = biomes.getListOrEmpty("palette");
        if (!bp.isEmpty()) {
            Tag[] repl = new Tag[bp.size()];
            boolean any = false;
            for (int i = 0; i < bp.size(); i++) {
                String b = bp.get(i).asString().orElse("");
                if (!target.has("data_worldgen_biome", b)) {
                    String to = repl(b, "biome", "minecraft:plains");
                    repl[i] = StringTag.valueOf(to);
                    any = true;
                }
            }
            if (any) rewritePalette(biomes, bp, repl, 64, 1, (i, cells) -> {
                String orig = bp.get(i).asString().orElse("");
                sc().biome(dim, cx, cz, sy, cells[0], orig, repl[i].asString().orElse(""));
                report.add("biome", orig, repl[i].asString().orElse(""), Long.bitCount(cells[0]), dim + " chunk " + cx + "," + cz);
            });
        }
    }

    /** A palette entry as a compound (26.3 writes a property-less state as its bare name). */
    static CompoundTag asState(Tag t) {
        if (t instanceof CompoundTag c) return c.copy();
        CompoundTag c = new CompoundTag();
        c.putString("id", t.asString().orElse("minecraft:air"));
        return c;
    }

    /** The state to write instead of {@code entryTag} (a palette block state), or null when the target has it as it is. */
    CompoundTag blockState(Tag entryTag) {
        CompoundTag entry = asState(entryTag);
        if (!(entryTag instanceof CompoundTag)) {
            // a bare name is the block's default state (26.3's compact form): spell its properties out before mapping
            Registry.Block sb = sourceReg == null ? null : sourceReg.blocks.get(entry.getStringOr("id", ""));
            if (sb != null && !sb.defaults().isEmpty()) {
                CompoundTag p = new CompoundTag();
                sb.defaults().forEach(p::putString);
                entry.put("properties", p);
            }
        }
        boolean modern = entry.contains("id");
        String name = entry.getStringOr(modern ? "id" : "Name", "minecraft:air");
        CompoundTag props = entry.getCompoundOrEmpty(modern ? "properties" : "Properties");
        Registry.Block known = target.blocks.get(Steps.blockAfter(name, src, tdv));
        if (known != null && validProps(known, props)) return null;
        String toName = known != null ? name : repl(name, "block", "minecraft:stone");
        Registry.Block tb = target.blocks.get(Steps.blockAfter(toName, src, tdv));
        CompoundTag np = new CompoundTag();
        if (tb != null) {
            for (String k : props.keySet()) {
                String v = props.getStringOr(k, "");
                List<String> allowed = tb.properties().get(k);
                if (allowed != null && allowed.contains(v)) np.putString(k, v);
            }
        }
        CompoundTag out = new CompoundTag();
        out.putString(modern ? "id" : "Name", toName);
        if (!np.isEmpty()) out.put(modern ? "properties" : "Properties", np);
        return out;
    }

    private static boolean validProps(Registry.Block b, CompoundTag props) {
        for (String k : props.keySet()) {
            List<String> allowed = b.properties().get(k);
            if (allowed == null || !allowed.contains(props.getStringOr(k, ""))) return false;
        }
        return true;
    }

    interface CellsSink {
        void accept(int paletteIndex, long[] cells);
    }

    /**
     * Replace palette entries ({@code repl[i]} non-null) in a paletted container, merging duplicates, and report for each
     * replaced entry which cells held it.
     */
    private static void rewritePalette(CompoundTag container, ListTag palette, Tag[] repl, int size, int minBits, CellsSink sink) {
        long[] data = container.getLongArray("data").orElse(null);
        int[] idx = PackedArray.decode(data, size, palette.size(), minBits);
        for (int i = 0; i < repl.length; i++) {
            if (repl[i] == null) continue;
            long[] cells = new long[(size + 63) / 64];
            boolean present = false;
            for (int c = 0; c < size; c++) {
                if (idx[c] == i) {
                    cells[c >> 6] |= 1L << (c & 63);
                    present = true;
                }
            }
            if (present) sink.accept(i, cells);
        }
        Map<Tag, Integer> merged = new LinkedHashMap<>();
        int[] remap = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            Tag t = repl[i] != null ? repl[i] : palette.get(i);
            remap[i] = merged.computeIfAbsent(t, k -> merged.size());
        }
        ListTag np = new ListTag();
        np.addAll(merged.keySet());
        for (int c = 0; c < size; c++) idx[c] = remap[idx[c]];
        container.put("palette", np);
        long[] packed = PackedArray.encode(idx, np.size(), minBits);
        if (packed == null) container.remove("data");
        else container.putLongArray("data", packed);
    }

    // ---- block entities, entities, items --------------------------------------------------------------------------------

    private CompoundTag blockEntity(CompoundTag be, String dim) {
        String id = be.getStringOr("id", "");
        int x = be.getIntOr("x", 0), y = be.getIntOr("y", 0), z = be.getIntOr("z", 0);
        String where = dim + " " + x + "," + y + "," + z;
        if (!target.has("block_entity_type", id)) {
            sc().blockEntity(dim, x, y, z, be.copy(), null);
            report.add("block_entity", id, "(kept in sidecar)", 1, where);
            return null;
        }
        CompoundTag original = be.copy();
        if (known != null) stripUnknown(be, known.blockEntity(id), id, where);
        Pass.Ctx c = ctx(dim, id + " at " + where);
        Tag t = Dfu.apply(srcSchema, References.BLOCK_ENTITY, itemPass.getRule(), be);
        t = Dfu.apply(srcSchema, References.BLOCK_ENTITY, entityPass.getRule(), t);
        t = Dfu.apply(srcSchema, References.BLOCK_ENTITY, statePass.getRule(), t);
        if (c.changed) sc().blockEntity(dim, x, y, z, original, id);
        c.changed = false;
        return (CompoundTag) t;
    }

    private CompoundTag entityTree(CompoundTag e, String dim) {
        Pass.Ctx c = ctx(dim, entityWhere(e, dim));
        CompoundTag original = e.copy();
        Tag t = Dfu.apply(srcSchema, References.ENTITY_TREE, itemPass.getRule(), e);
        t = Dfu.apply(srcSchema, References.ENTITY_TREE, entityPass.getRule(), t);
        t = Dfu.apply(srcSchema, References.ENTITY_TREE, statePass.getRule(), t);
        String id = original.getStringOr("id", "");
        int[] uuid = original.getIntArray("UUID").orElse(null);
        // a known entity with something replaced inside (a block it carries or shows): kept whole for the way back
        if (c.changed && uuid != null && target.has("entity_type", Steps.entityAfter(id, src, tdv))) sc().entity(dim, uuid, original, id);
        c.changed = false;
        return (CompoundTag) t;
    }

    /** A block state inside an entity or block entity (not a chunk palette): like a palette entry, recorded via its container. */
    private Dynamic<?> nestedBlockState(Dynamic<?> d) {
        Tag tag = d.convert(NbtOps.INSTANCE).getValue();
        CompoundTag repl = blockState(tag);
        if (repl == null) return d;
        Pass.Ctx c = Pass.CTX.get();
        c.changed = true;
        report.add("block", stateString(asState(tag)), stateString(repl), 1, c.where);
        return new Dynamic<>(NbtOps.INSTANCE, repl);
    }

    /** One entity (passengers are separate values of the same type, visited on their own). */
    private Dynamic<?> entity(Dynamic<?> d) {
        String id = d.get("id").asString("");
        if (id.isEmpty()) return d;
        if (target.has("entity_type", Steps.entityAfter(id, src, tdv))) {
            if (known == null || known.entity(id) == null) return d;
            CompoundTag e = (CompoundTag) d.convert(NbtOps.INSTANCE).getValue();
            stripUnknown(e, known.entity(id), id, Pass.CTX.get().where);
            return new Dynamic<>(NbtOps.INSTANCE, e);
        }
        Pass.Ctx c = Pass.CTX.get();
        String to = repl(id, "entity", "minecraft:marker");
        CompoundTag original = (CompoundTag) d.convert(NbtOps.INSTANCE).getValue();
        CompoundTag out = new CompoundTag();
        for (String k : new String[]{"UUID", "Pos", "Motion", "Rotation", "CustomName", "CustomNameVisible", "Invulnerable", "Silent",
                "NoGravity", "Glowing", "Tags", "Passengers", "Fire", "Air", "OnGround", "fall_distance", "PortalCooldown", "HasVisualFire",
                "Items", "Invisible"}) {
            Tag v = original.get(k);
            if (v != null) out.put(k, v.copy());
        }
        out.putString("id", to);
        if (original.contains("Health")) {
            // a mob in place of one the target lacks: it stays where it is, never despawns and cannot die (a squid for a
            // nautilus on land would suffocate), so the original comes back
            out.putBoolean("NoAI", true);
            out.putBoolean("PersistenceRequired", true);
            out.putBoolean("Invulnerable", true);
        }
        if (id.equals("minecraft:cushion")) {
            CompoundTag state = new CompoundTag();
            state.putString(srcSchemaModernStates() ? "id" : "Name", "minecraft:" + original.getStringOr("color", "white") + "_carpet");
            out.put("block_state", state);
            out.put("transformation", displayOffset(-0.5f, 0f, -0.5f));
        }
        int[] uuid = original.getIntArray("UUID").orElse(null);
        if (uuid != null) sc().entity(c.dim, uuid, original, to);
        else c.changed = true;   // no UUID (a spawner's or bee nest's entity): the containing block entity is kept whole
        report.add("entity", id, to, 1, c.where);
        return new Dynamic<>(NbtOps.INSTANCE, out);
    }

    private boolean srcSchemaModernStates() {
        return src >= 5006;
    }

    private static CompoundTag displayOffset(float x, float y, float z) {
        CompoundTag t = new CompoundTag();
        ListTag tr = new ListTag();
        tr.add(net.minecraft.nbt.FloatTag.valueOf(x));
        tr.add(net.minecraft.nbt.FloatTag.valueOf(y));
        tr.add(net.minecraft.nbt.FloatTag.valueOf(z));
        t.put("translation", tr);
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("scale", floats(1, 1, 1));
        return t;
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) l.add(net.minecraft.nbt.FloatTag.valueOf(f));
        return l;
    }

    /**
     * One item stack: an item the target lacks becomes its closest equivalent carrying the original; one the target has
     * carries its original when the way down and back would not give it back exactly (a component or detail the target
     * cannot hold).
     */
    private Dynamic<?> item(Dynamic<?> d) {
        String id = d.get("id").asString("");
        if (id.isEmpty() || id.equals("minecraft:air")) return d;
        Pass.Ctx c = Pass.CTX.get();
        int count = d.get("count").asInt(1);
        CompoundTag stack = (CompoundTag) d.convert(NbtOps.INSTANCE).getValue();
        String after = Steps.itemAfter(id, src, tdv);
        if (!target.has("item", after)) {
            String to = repl(id, "item", "minecraft:paper");
            CompoundTag out = new CompoundTag();
            out.putString("id", to);
            out.putInt("count", count);
            if (stack.contains("Slot")) out.put("Slot", stack.get("Slot").copy());
            CompoundTag comps = new CompoundTag();
            CompoundTag orig = stack.getCompoundOrEmpty("components");
            Tag name = orig.get("minecraft:custom_name");
            if (name != null) comps.put("minecraft:custom_name", name.copy());
            comps.put("minecraft:custom_data", carry(stack, new CompoundTag()));
            ListTag lore = new ListTag();
            lore.add(text("原本是 " + id + "（轉回新版會還原）"));
            comps.put("minecraft:lore", lore);
            out.put("components", comps);
            report.add("item", id, to, count, c.where);
            return new Dynamic<>(NbtOps.INSTANCE, out);
        }
        if (lossless(stack)) return d;
        CompoundTag comps = stack.getCompoundOrEmpty("components");
        comps.put("minecraft:custom_data", carry(stack, comps.getCompoundOrEmpty("minecraft:custom_data")));
        stack.put("components", comps);
        report.add(after.equals(id) ? "component" : "item", id, after.equals(id) ? "(kept in the item)" : after, count, c.where);
        return new Dynamic<>(NbtOps.INSTANCE, stack);
    }

    /** {@code customData} with the stack's original in it (an older conversion's original stays inside this one's). */
    private CompoundTag carry(CompoundTag stack, CompoundTag customData) {
        CompoundTag cd = customData.copy();
        CompoundTag s1 = new CompoundTag();
        s1.put("original", stack.copy());
        s1.putInt("data_version", src);
        cd.put(ITEM_KEY, s1);
        return cd;
    }

    /** Whether the stack comes back exactly from the target (down, cleaned, the game's own way up, restored). */
    private boolean lossless(CompoundTag stack) {
        Boolean known = lossless.get(stack);
        if (known != null) return known;
        CompoundTag key = stack.copy();
        boolean ok;
        try {
            Tag down = Steps.reverse(References.ITEM_STACK, stack.copy(), src, tdv);
            down = Dfu.apply(Dfu.schema(tdv), References.ITEM_STACK, cleanItems.getRule(), down);
            Tag up = Dfu.update(References.ITEM_STACK, down, tdv, src);
            ok = itemSim.items(References.ITEM_STACK, up).equals(itemSim.items(References.ITEM_STACK, stack.copy()));
        } catch (RuntimeException e) {
            ok = false;
        }
        if (lossless.size() > 200_000) lossless.clear();
        lossless.put(key, ok);
        return ok;
    }

    static final String ITEM_KEY = "s1mp1e_conversion";

    private static CompoundTag text(String s) {
        CompoundTag t = new CompoundTag();
        t.putString("text", s);
        t.putBoolean("italic", false);
        t.putString("color", "gray");
        return t;
    }

    private String repl(String id, String kind, String fallback) {
        String to = repl.get(kind, id);
        if (to != null) return to;
        report.add("unmapped_" + kind, id, fallback, 1, null);
        return fallback;
    }

    /** {@link #repl} for a detail inside something already kept whole (no report line of its own). */
    private String replQuiet(String id, String kind, String fallback) {
        String to = repl.get(kind, id);
        return to != null ? to : fallback;
    }

    private static Pass.Ctx ctx(String dim, String where) {
        Pass.Ctx c = Pass.CTX.get();
        c.dim = dim;
        c.where = where;
        c.changed = false;
        return c;
    }

    private static String entityWhere(CompoundTag e, String dim) {
        ListTag pos = e.getListOrEmpty("Pos");
        if (pos.size() < 3) return dim;
        return String.format("%s %s at %.0f,%.0f,%.0f", dim, e.getStringOr("id", "?"),
                pos.get(0).asDouble().orElse(0d), pos.get(1).asDouble().orElse(0d), pos.get(2).asDouble().orElse(0d));
    }

    static String stateString(CompoundTag s) {
        String name = s.getStringOr(s.contains("id") ? "id" : "Name", "?");
        CompoundTag p = s.getCompoundOrEmpty(s.contains("id") ? "properties" : "Properties");
        if (p.isEmpty()) return name;
        Map<String, String> m = new HashMap<>();
        for (String k : p.keySet()) m.put(k, p.getStringOr(k, ""));
        return name + new java.util.TreeMap<>(m);
    }

    private static String firstCell(String dim, int cx, int sy, int cz, long[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0) {
                int c = i * 64 + Long.numberOfTrailingZeros(cells[i]);
                int x = cx * 16 + (c & 15), z = cz * 16 + ((c >> 4) & 15), y = sy * 16 + (c >> 8);
                return dim + " " + x + "," + y + "," + z;
            }
        }
        return dim;
    }

    static IntArrayTag uuidTag(int[] u) {
        return new IntArrayTag(u);
    }
}
