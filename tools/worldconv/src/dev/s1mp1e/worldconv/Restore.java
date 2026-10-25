package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.References;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Putting back what a downgrade kept aside, on data the game's DataFixerUpper already brought to {@link #target}: the
 * sidecar's blocks, biomes, block entities, entities, structures and fields where their replacement is still in
 * place, every replaced item stack, and the field diffs. The Upgrader runs it on the whole world; the Downgrader runs
 * it on each chunk it just converted (with that chunk's own records) to see what the way back would not restore.
 */
final class Restore {
    final int target;
    private final Registry reg;
    /** null while simulating (nothing is reported). */
    private final Report report;
    private final Map<String, List<CompoundTag>> blocksByChunk = new HashMap<>();
    private final Map<String, List<CompoundTag>> biomesByChunk = new HashMap<>();
    private final Map<String, CompoundTag> blockEntitiesByPos = new HashMap<>();
    private final Map<String, CompoundTag> entitiesByUuid = new HashMap<>();
    private final Map<String, List<CompoundTag>> extras = new HashMap<>();
    private final Map<String, List<CompoundTag>> structuresByChunk = new HashMap<>();
    private final Map<String, List<CompoundTag>> fieldsByChunk = new HashMap<>();
    /** Diffs of sidecars made from exactly {@link #target}: chunk / section / block entity records by chunk, entities by UUID, files by path. */
    private final Map<String, List<CompoundTag>> diffsByChunk = new HashMap<>();
    private final Map<String, CompoundTag> entityDiffs = new HashMap<>();
    private final Map<String, List<CompoundTag>> entityAddsByChunk = new HashMap<>();
    final Map<String, CompoundTag> fileDiffs = new LinkedHashMap<>();
    private final Pass itemRestore, compact, obsolete;
    int applied;

    /**
     * The passes of a target version, made once: a DFU rewrite is built (slowly) and cached per rule, and the Downgrader
     * makes a Restore per chunk. They act on the Restore that runs them ({@link #CURRENT}).
     */
    private record Passes(Pass item, Pass compact, Pass obsolete) {}

    private static final Map<Integer, Passes> PASSES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Restore> CURRENT = new ThreadLocal<>();

    Restore(int target, Report report) {
        this.target = target;
        this.reg = Registry.of(Version.nameOf(target).startsWith("data version") ? "26.3" : Version.nameOf(target));
        this.report = report;
        Passes p = PASSES.computeIfAbsent(target, t -> new Passes(
                new Pass(Dfu.schema(t), References.ITEM_STACK, d -> CURRENT.get().restoreItem(d)),
                t >= 5006 ? new Pass(Dfu.schema(t), References.BLOCK_STATE, d -> CURRENT.get().compactState(d)) : null,
                new Pass(Dfu.schema(t), References.ENTITY, d -> {
                    Tag tag = d.convert(NbtOps.INSTANCE).getValue();
                    if (tag instanceof CompoundTag e) Obsolete.entity(e, t);
                    return new Dynamic<>(NbtOps.INSTANCE, tag);
                })));
        this.itemRestore = p.item();
        this.compact = p.compact();
        this.obsolete = p.obsolete();
    }

    /** {@code pass} over {@code tag} on behalf of this Restore. */
    private Tag run(Pass pass, DSL.TypeReference ref, Tag tag) {
        Restore was = CURRENT.get();
        CURRENT.set(this);
        try {
            return Dfu.apply(Dfu.schema(target), ref, pass.getRule(), tag);
        } finally {
            CURRENT.set(was);
        }
    }

    /** Index {@code s}'s records (their originals upgraded to {@link #target} when the sidecar is from an older version). */
    void add(Sidecar s) {
        for (CompoundTag b : s.blocks) index(blocksByChunk, chunkKey(b), upgradeRecord(b, s, References.BLOCK_STATE));
        for (CompoundTag b : s.biomes) index(biomesByChunk, chunkKey(b), b);
        for (CompoundTag b : s.blockEntities) blockEntitiesByPos.put(posKey(b), upgradeRecord(b, s, References.BLOCK_ENTITY));
        for (CompoundTag e : s.entities) entitiesByUuid.put(Upgrader.uuidKey(e.getIntArray("uuid").orElse(new int[0])), upgradeRecord(e, s, References.ENTITY));
        for (CompoundTag x : s.extras) index(extras, x.getStringOr("kind", "") + " " + x.getStringOr("player", ""), x);
        for (CompoundTag x : s.structures) index(structuresByChunk, chunkKey(x), x);
        for (CompoundTag x : s.fields) index(fieldsByChunk, x.getStringOr("dim", "") + " " + Math.floorDiv(x.getIntOr("x", 0), 16) + " " + Math.floorDiv(x.getIntOr("z", 0), 16), x);
        if (s.sourceVersion != target) return;   // field diffs hold source-version values: only for the version they came from
        for (CompoundTag d : s.diffs) {
            String kind = d.getStringOr("kind", ""), dim = d.getStringOr("dim", ""), key = d.getStringOr("key", "");
            switch (kind) {
                case "chunk", "section", "block_entity" -> index(diffsByChunk, dim + " " + chunkOf(kind, key), d);
                case "entity" -> {
                    if (d.getCompoundOrEmpty("fields").contains(Diffs.WHOLE) && d.getCompoundOrEmpty("fields").getCompoundOrEmpty(Diffs.WHOLE).contains("v")) {
                        index(entityAddsByChunk, dim + " " + d.getStringOr("chunk", ""), d);
                    } else entityDiffs.put(key, d);
                }
                case "file" -> fileDiffs.put(key, d);
                default -> { }
            }
        }
    }

    private static String chunkOf(String kind, String key) {
        String[] p = key.split(" ");
        return switch (kind) {
            case "block_entity" -> Math.floorDiv(Integer.parseInt(p[0]), 16) + " " + Math.floorDiv(Integer.parseInt(p[2]), 16);
            default -> p[0] + " " + p[1];
        };
    }

    private CompoundTag upgradeRecord(CompoundTag r, Sidecar s, DSL.TypeReference ref) {
        if (s.sourceVersion == target) return r;
        CompoundTag out = r.copy();
        out.put("original", Dfu.update(ref, r.getCompoundOrEmpty("original"), s.sourceVersion, target));
        if (ref == References.BLOCK_STATE) out.put("replacement", Dfu.update(ref, r.getCompoundOrEmpty("replacement"), s.sourceVersion, target));
        return out;
    }

    private void rep(String kind, String from, String to, long n, String where) {
        if (report != null) report.add(kind, from, to, n, where);
    }

    // ---- chunks -----------------------------------------------------------------------------------------------------

    /** {@code t}: a region chunk already brought to {@link #target} by the DataFixerUpper. */
    CompoundTag chunk(CompoundTag t, String dim) {
        int cx = t.getIntOr("xPos", 0), cz = t.getIntOr("zPos", 0);
        String ck = dim + " " + cx + " " + cz;
        for (CompoundTag r : structuresByChunk.getOrDefault(ck, List.of())) {
            CompoundTag structs = t.getCompoundOrEmpty("structures");
            CompoundTag removed = r.getCompoundOrEmpty("removed");
            for (String group : removed.keySet()) {
                CompoundTag g = structs.getCompoundOrEmpty(group);
                for (String id : removed.getCompoundOrEmpty(group).keySet()) {
                    if (!g.contains(id)) g.put(id, removed.getCompoundOrEmpty(group).get(id).copy());
                    if (!group.equalsIgnoreCase("references")) rep("restored_structure", id, "", 1, ck);
                }
                structs.put(group, g);
            }
            t.put("structures", structs);
        }
        List<CompoundTag> blocks = blocksByChunk.get(ck);
        List<CompoundTag> biomes = biomesByChunk.get(ck);
        if (blocks != null || biomes != null) {
            for (Tag s : t.getListOrEmpty("sections")) {
                if (s instanceof CompoundTag section) restoreSection(section, dim, cx, cz, blocks, biomes);
            }
        }
        ListTag bes = t.getListOrEmpty("block_entities");
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < bes.size(); i++) {
            if (bes.get(i) instanceof CompoundTag be) at.put(dim + " " + Diffs.POSITION.of(be), i);
        }
        for (var e : blockEntitiesByPos.entrySet()) {
            CompoundTag r = e.getValue();
            if (!r.getStringOr("dim", "").equals(dim) || Math.floorDiv(r.getIntOr("x", 0), 16) != cx || Math.floorDiv(r.getIntOr("z", 0), 16) != cz) continue;
            Integer i = at.get(e.getKey());
            String repl = r.getStringOr("replacement", "");
            CompoundTag orig = r.getCompoundOrEmpty("original");
            if (i == null && repl.isEmpty()) {
                bes.add(orig.copy());
                rep("restored_block_entity", orig.getStringOr("id", ""), "", 1, e.getKey());
            } else if (i != null && bes.get(i) instanceof CompoundTag cur && cur.getStringOr("id", "").equals(repl)) {
                bes.set(i, orig.copy());
                rep("restored_block_entity", orig.getStringOr("id", ""), "", 1, e.getKey());
            }
        }
        for (CompoundTag f : fieldsByChunk.getOrDefault(ck, List.of())) {
            Integer i = at.get(dim + " " + f.getIntOr("x", 0) + " " + f.getIntOr("y", 0) + " " + f.getIntOr("z", 0));
            if (i == null || !(bes.get(i) instanceof CompoundTag cur) || !cur.getStringOr("id", "").equals(f.getStringOr("id", ""))) continue;
            Tag v = f.get("value");
            if (v == null) cur.remove(f.getStringOr("field", ""));
            else cur.put(f.getStringOr("field", ""), v.copy());
        }
        if (!bes.isEmpty()) t.put("block_entities", items(References.BLOCK_ENTITY, bes));
        t = (CompoundTag) run(obsolete, References.CHUNK, t);
        t = compactAll(References.CHUNK, t);
        chunkDiffs(t, dim, cx, cz);
        return t;
    }

    private void chunkDiffs(CompoundTag t, String dim, int cx, int cz) {
        List<CompoundTag> recs = diffsByChunk.get(dim + " " + cx + " " + cz);
        if (recs == null) return;
        ListTag sections = t.getListOrEmpty("sections"), bes = t.getListOrEmpty("block_entities");
        for (CompoundTag r : recs) {
            CompoundTag fields = r.getCompoundOrEmpty("fields");
            String key = r.getStringOr("key", "");
            switch (r.getStringOr("kind", "")) {
                case "chunk" -> applied += Diffs.apply(t, fields);
                case "section" -> listDiff(sections, Diffs.SECTION, key.substring(key.lastIndexOf(' ') + 1), fields, null);
                case "block_entity" -> listDiff(bes, Diffs.POSITION, key, fields, r.getStringOr("id", ""));
                default -> { }
            }
        }
        if (t.contains("sections") || !sections.isEmpty()) t.put("sections", sections);
        if (t.contains("block_entities") || !bes.isEmpty()) t.put("block_entities", bes);
    }

    /** One diff record on the element of {@code list} with key {@code key}: fields, or the whole element added / removed. */
    private void listDiff(ListTag list, Diffs.Key k, String key, CompoundTag fields, String id) {
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof CompoundTag c && k.of(c).equals(key)) {
                idx = i;
                break;
            }
        }
        if (fields.contains(Diffs.WHOLE)) {
            CompoundTag f = fields.getCompoundOrEmpty(Diffs.WHOLE);
            Tag cur = idx < 0 ? null : list.get(idx);
            if (!Diffs.unchanged(f, cur)) return;
            if (f.contains("v")) list.add(f.get("v").copy());
            else list.remove(idx);
            applied++;
            return;
        }
        if (idx < 0 || !(list.get(idx) instanceof CompoundTag c)) return;
        if (id != null && !id.isEmpty() && !c.getStringOr("id", "").equals(id)) return;
        applied += Diffs.apply(c, fields);
    }

    private void restoreSection(CompoundTag section, String dim, int cx, int cz, List<CompoundTag> blocks, List<CompoundTag> biomes) {
        int sy = section.getByteOr("Y", (byte) 0);
        if (blocks != null) {
            CompoundTag states = section.getCompoundOrEmpty("block_states");
            ListTag palette = states.getListOrEmpty("palette");
            List<CompoundTag> here = blocks.stream().filter(b -> b.getIntOr("sy", 0) == sy).toList();
            if (!here.isEmpty() && !palette.isEmpty()) {
                int[] idx = PackedArray.decode(states.getLongArray("data").orElse(null), 4096, palette.size(), 4);
                // as compounds: a bare name and its compound would be the same state twice in one palette
                List<Tag> pal = new ArrayList<>();
                for (Tag p : palette) pal.add(Downgrader.asState(p));
                for (CompoundTag r : here) {
                    CompoundTag repl = normalize(r.getCompoundOrEmpty("replacement"));
                    CompoundTag orig = r.getCompoundOrEmpty("original");
                    long[] cells = r.getLongArray("cells").orElse(new long[64]);
                    int origIdx = -1;
                    long n = 0;
                    for (int c = 0; c < 4096; c++) {
                        if ((cells[c >> 6] & (1L << (c & 63))) == 0) continue;
                        if (!normalize(Downgrader.asState(pal.get(idx[c]))).equals(repl)) continue;
                        if (origIdx < 0) {
                            origIdx = pal.indexOf(orig);
                            if (origIdx < 0) {
                                pal.add(orig.copy());
                                origIdx = pal.size() - 1;
                            }
                        }
                        idx[c] = origIdx;
                        n++;
                    }
                    if (n > 0) rep("restored_block", Downgrader.stateString(orig), "", n, dim + " chunk " + cx + "," + cz);
                    long kept = Arrays.stream(cells).map(Long::bitCount).sum() - n;
                    if (kept > 0) rep("changed_in_old_version", Upgrader.Downgrade_NOTE, Downgrader.stateString(orig), kept, dim + " chunk " + cx + "," + cz);
                }
                repack(states, pal, idx, 4096, 4);
            }
        }
        if (biomes != null) {
            CompoundTag b = section.getCompoundOrEmpty("biomes");
            ListTag bp = b.getListOrEmpty("palette");
            List<CompoundTag> here = biomes.stream().filter(x -> x.getIntOr("sy", 0) == sy).toList();
            if (!here.isEmpty() && !bp.isEmpty()) {
                int[] idx = PackedArray.decode(b.getLongArray("data").orElse(null), 64, bp.size(), 1);
                List<Tag> pal = new ArrayList<>(bp);
                for (CompoundTag r : here) {
                    StringTag orig = StringTag.valueOf(r.getStringOr("original", ""));
                    String repl = r.getStringOr("replacement", "");
                    long cells = r.getLongOr("cells", 0);
                    int origIdx = pal.indexOf(orig);
                    if (origIdx < 0) {
                        pal.add(orig);
                        origIdx = pal.size() - 1;
                    }
                    long n = 0;
                    for (int c = 0; c < 64; c++) {
                        if ((cells & (1L << c)) != 0 && pal.get(idx[c]).asString().orElse("").equals(repl)) {
                            idx[c] = origIdx;
                            n++;
                        }
                    }
                    if (n > 0) rep("restored_biome", orig.value(), "", n, dim + " chunk " + cx + "," + cz);
                }
                repack(b, pal, idx, 64, 1);
            }
        }
    }

    private static void repack(CompoundTag container, List<Tag> pal, int[] idx, int size, int minBits) {
        Map<Tag, Integer> merged = new LinkedHashMap<>();
        boolean[] used = new boolean[pal.size()];
        for (int c = 0; c < size; c++) used[idx[c]] = true;
        int[] remap = new int[pal.size()];
        for (int i = 0; i < pal.size(); i++) {
            if (!used[i]) continue;
            remap[i] = merged.computeIfAbsent(pal.get(i), k -> merged.size());
        }
        ListTag np = new ListTag();
        np.addAll(merged.keySet());
        for (int c = 0; c < size; c++) idx[c] = remap[idx[c]];
        container.put("palette", np);
        long[] packed = PackedArray.encode(idx, np.size(), minBits);
        if (packed == null) container.remove("data");
        else container.putLongArray("data", packed);
    }

    /** A block state with every property the target's block has, defaults filled in, for comparing. */
    private CompoundTag normalize(CompoundTag s) {
        boolean modern = s.contains("id");
        String name = s.getStringOr(modern ? "id" : "Name", "");
        CompoundTag props = s.getCompoundOrEmpty(modern ? "properties" : "Properties").copy();
        Registry.Block b = reg.blocks.get(name);
        if (b != null) b.defaults().forEach((k, v) -> { if (!props.contains(k)) props.putString(k, v); });
        CompoundTag out = new CompoundTag();
        out.putString("n", name);
        out.put("p", props);
        return out;
    }

    // ---- entities ---------------------------------------------------------------------------------------------------

    /** {@code t}: an entity chunk already brought to {@link #target}. */
    CompoundTag entityChunk(CompoundTag t, String dim) {
        ListTag out = new ListTag();
        for (Tag e : t.getListOrEmpty("Entities")) {
            if (e instanceof CompoundTag c) out.add(restoreEntity(c, dim));
        }
        t.put("Entities", items(References.ENTITY_TREE, out));
        t = (CompoundTag) run(obsolete, References.ENTITY_CHUNK, t);
        t = compactAll(References.ENTITY_CHUNK, t);
        if (!entityDiffs.isEmpty() || !entityAddsByChunk.isEmpty()) {
            ListTag list = t.getListOrEmpty("Entities");
            entityDiffs(list);
            int[] pos = t.getIntArray("Position").orElse(new int[2]);
            for (CompoundTag r : entityAddsByChunk.getOrDefault(dim + " " + pos[0] + " " + pos[1], List.of())) {
                CompoundTag f = r.getCompoundOrEmpty("fields").getCompoundOrEmpty(Diffs.WHOLE);
                if (!contains(list, r.getStringOr("key", ""))) {
                    list.add(f.get("v").copy());
                    applied++;
                }
            }
            t.put("Entities", list);
        }
        return t;
    }

    private static boolean contains(ListTag trees, String uuid) {
        java.util.Map<String, CompoundTag> all = new java.util.HashMap<>();
        Diffs.flatten(trees, all);
        return all.containsKey(uuid);
    }

    /** Field diffs on every entity of {@code trees} (passengers too); whole entities the way back added are removed. */
    private void entityDiffs(ListTag trees) {
        for (int i = trees.size() - 1; i >= 0; i--) {
            if (!(trees.get(i) instanceof CompoundTag e)) continue;
            String u = Diffs.uuid(e);
            CompoundTag r = u == null ? null : entityDiffs.get(u);
            if (r != null) {
                CompoundTag fields = r.getCompoundOrEmpty("fields");
                if (fields.contains(Diffs.WHOLE)) {
                    CompoundTag self = e.copy();
                    self.remove("Passengers");
                    if (Diffs.unchanged(fields.getCompoundOrEmpty(Diffs.WHOLE), self)) {
                        trees.remove(i);
                        applied++;
                        continue;
                    }
                } else if (e.getStringOr("id", "").equals(r.getStringOr("id", ""))) {
                    applied += Diffs.apply(e, fields);
                }
            }
            ListTag passengers = e.getListOrEmpty("Passengers");
            if (!passengers.isEmpty()) {
                entityDiffs(passengers);
                e.put("Passengers", passengers);
            }
        }
    }

    private CompoundTag restoreEntity(CompoundTag e, String dim) {
        ListTag passengers = e.getListOrEmpty("Passengers");
        if (!passengers.isEmpty()) {
            ListTag np = new ListTag();
            for (Tag p : passengers) if (p instanceof CompoundTag c) np.add(restoreEntity(c, dim));
            e.put("Passengers", np);
        }
        int[] uuid = e.getIntArray("UUID").orElse(null);
        if (uuid == null) return e;
        CompoundTag r = entitiesByUuid.get(Upgrader.uuidKey(uuid));
        if (r == null || !r.getStringOr("replacement", "").equals(e.getStringOr("id", ""))) return e;
        CompoundTag orig = r.getCompoundOrEmpty("original").copy();
        // what lived on in the older version: where it is, how it moves, what it carries
        for (String k : new String[]{"Pos", "Motion", "Rotation", "OnGround", "fall_distance", "Fire", "Air", "PortalCooldown",
                "Passengers", "Items", "CustomName", "CustomNameVisible", "Tags"}) {
            Tag v = e.get(k);
            if (v != null) orig.put(k, v.copy());
            else orig.remove(k);
        }
        rep("restored_entity", orig.getStringOr("id", ""), "", 1, dim);
        return orig;
    }

    // ---- players ----------------------------------------------------------------------------------------------------

    CompoundTag player(CompoundTag p, String name) {
        p = (CompoundTag) items(References.PLAYER, p);
        for (CompoundTag x : extras.getOrDefault("recipes " + name, List.of())) {
            CompoundTag book = p.getCompoundOrEmpty("recipeBook");
            CompoundTag back = x.getCompoundOrEmpty("data").getCompoundOrEmpty("removed");
            CompoundTag orig = x.getCompoundOrEmpty("data").getCompoundOrEmpty("original");
            for (String list : back.keySet()) {
                // the original order first (what is still unlocked, plus what comes back), then what was unlocked meanwhile
                ListTag cur = book.getListOrEmpty(list);
                ListTag gone = back.getListOrEmpty(list);
                ListTag rebuilt = new ListTag();
                for (Tag r : orig.getListOrEmpty(list)) if (cur.contains(r) || gone.contains(r)) rebuilt.add(r);
                for (Tag r : cur) if (!rebuilt.contains(r)) rebuilt.add(r);
                book.put(list, rebuilt);
            }
            p.put("recipeBook", book);
            rep("restored_recipes", name, "", back.getListOrEmpty("recipes").size(), null);
        }
        p = (CompoundTag) run(obsolete, References.PLAYER, p);
        Obsolete.player(p, target);
        return compactAll(References.PLAYER, p);
    }

    com.google.gson.JsonObject advancements(com.google.gson.JsonObject up, String player) {
        for (CompoundTag x : extras.getOrDefault("advancements " + player, List.of())) {
            com.google.gson.JsonObject back = com.google.gson.JsonParser.parseString(x.getStringOr("data", "{}")).getAsJsonObject();
            for (String k : back.keySet()) if (!up.has(k)) up.add(k, back.get(k));
            rep("restored_advancements", player, "", back.size(), null);
        }
        return up;
    }

    com.google.gson.JsonObject stats(com.google.gson.JsonObject up, String player) {
        com.google.gson.JsonObject stats = up.has("stats") ? up.getAsJsonObject("stats") : new com.google.gson.JsonObject();
        for (CompoundTag x : extras.getOrDefault("stats " + player, List.of())) {
            com.google.gson.JsonObject back = com.google.gson.JsonParser.parseString(x.getStringOr("data", "{}")).getAsJsonObject();
            for (String cat : back.keySet()) {
                com.google.gson.JsonObject cur = stats.has(cat) ? stats.getAsJsonObject(cat) : new com.google.gson.JsonObject();
                for (var e : back.getAsJsonObject(cat).entrySet()) {
                    long v = e.getValue().getAsLong() + (cur.has(e.getKey()) ? cur.get(e.getKey()).getAsLong() : 0);
                    cur.addProperty(e.getKey(), v);
                }
                stats.add(cat, cur);
            }
            rep("restored_statistics", player, "", back.size(), null);
        }
        up.add("stats", stats);
        return up;
    }

    // ---- items and block states -------------------------------------------------------------------------------------

    private CompoundTag compactAll(DSL.TypeReference ref, CompoundTag t) {
        return compact == null ? t : (CompoundTag) run(compact, ref, t);
    }

    private Dynamic<?> compactState(Dynamic<?> d) {
        String id = d.get("id").asString(null);
        if (id == null) return d;
        Registry.Block b = reg.blocks.get(id);
        if (b == null) return d;
        var props = d.get("properties").asMap(k -> k.asString(""), v -> v.asString(""));
        for (var e : props.entrySet()) if (!e.getValue().equals(b.defaults().get(e.getKey()))) return d;
        return d.createString(id);
    }

    ListTag items(DSL.TypeReference ref, ListTag list) {
        ListTag out = new ListTag();
        for (Tag t : list) out.add(items(ref, t));
        return out;
    }

    /** Every replaced stack inside {@code t} back to its original (until none is left: a restored shulker box may hold more). */
    Tag items(DSL.TypeReference ref, Tag t) {
        for (int i = 0; i < 4; i++) {
            if (!t.toString().contains(Downgrader.ITEM_KEY)) return t;
            Tag next = run(itemRestore, ref, t);
            if (next.equals(t)) return next;
            t = next;
        }
        return t;
    }

    private Dynamic<?> restoreItem(Dynamic<?> d) {
        CompoundTag stack = (CompoundTag) d.convert(NbtOps.INSTANCE).getValue();
        CompoundTag comps = stack.getCompoundOrEmpty("components");
        CompoundTag cd = comps.getCompoundOrEmpty("minecraft:custom_data");
        CompoundTag s1 = cd.getCompoundOrEmpty(Downgrader.ITEM_KEY);
        if (s1.isEmpty()) return d;
        int from = s1.getIntOr("data_version", target);
        if (from > target) return d;   // carried from a newer version: restored when the world gets there
        CompoundTag orig = s1.getCompoundOrEmpty("original");
        if (!orig.isEmpty()) {
            CompoundTag up = (CompoundTag) Dfu.update(References.ITEM_STACK, orig, from, target);
            if (stack.contains("count")) up.putInt("count", stack.getIntOr("count", up.getIntOr("count", 1)));
            if (stack.contains("Slot")) up.put("Slot", stack.get("Slot").copy());
            rep("restored_item", up.getStringOr("id", ""), "", up.getIntOr("count", 1), null);
            return new Dynamic<>(NbtOps.INSTANCE, up);
        }
        // older converter versions: only the components the target lacked were kept
        CompoundTag aside = s1.getCompoundOrEmpty("components");
        if (!aside.isEmpty()) {
            CompoundTag upComps = ((CompoundTag) Dfu.update(References.DATA_COMPONENTS, aside, from, target));
            for (String k : upComps.keySet()) comps.put(k, upComps.get(k).copy());
            rep("restored_component", String.join(",", upComps.keySet()), "", 1, stack.getStringOr("id", ""));
        }
        for (Tag a : s1.getListOrEmpty("absent")) comps.remove(a.asString().orElse(""));
        cd.remove(Downgrader.ITEM_KEY);
        if (cd.isEmpty()) comps.remove("minecraft:custom_data");
        else comps.put("minecraft:custom_data", cd);
        stack.put("components", comps);
        return new Dynamic<>(NbtOps.INSTANCE, stack);
    }

    // ---- keys -------------------------------------------------------------------------------------------------------

    private static void index(Map<String, List<CompoundTag>> m, String k, CompoundTag v) {
        m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
    }

    private static String chunkKey(CompoundTag r) {
        return r.getStringOr("dim", "") + " " + r.getIntOr("cx", 0) + " " + r.getIntOr("cz", 0);
    }

    private static String posKey(CompoundTag r) {
        return r.getStringOr("dim", "") + " " + r.getIntOr("x", 0) + " " + r.getIntOr("y", 0) + " " + r.getIntOr("z", 0);
    }

    static final Set<String> NOT_COMPARED = Set.of("DataVersion");
}
