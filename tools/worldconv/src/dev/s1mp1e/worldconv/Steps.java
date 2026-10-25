package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.serialization.Dynamic;
import net.minecraft.util.datafix.fixes.References;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The reverse steps, newest first, each undoing one forward fix of 26.3's DataFixers (docs/WORLD_CONVERT.md lists
 * them). {@link #between} picks the ones a conversion from one data version down to another has to run.
 */
final class Steps {
    private Steps() {}

    /** Item ids a reverse step renames: newer id → older id. Consulted by the registry pass ("known after reversal"). */
    static final Map<String, String> ITEM_RENAMES_26_3_TO_26_2 = Map.of(
            "minecraft:ocean_monument_map", "minecraft:ocean_explorer_map",
            "minecraft:swamp_hut_map", "minecraft:swamp_explorer_map",
            "minecraft:buried_trial_chambers_map", "minecraft:trial_explorer_map",
            "minecraft:woodland_mansion_map", "minecraft:woodland_explorer_map",
            "minecraft:jungle_pyramid_map", "minecraft:jungle_explorer_map");

    /** 26.2 explorer-map item ids (5008) → their map decoration and legacy name key, for turning them back into filled maps. */
    static final Map<String, String> EXPLORER_MAP_NAME_KEYS = Map.ofEntries(
            Map.entry("minecraft:woodland_explorer_map", "filled_map.mansion"),
            Map.entry("minecraft:ocean_explorer_map", "filled_map.monument"),
            Map.entry("minecraft:trial_explorer_map", "filled_map.trial_chambers"),
            Map.entry("minecraft:jungle_explorer_map", "filled_map.explorer_jungle"),
            Map.entry("minecraft:swamp_explorer_map", "filled_map.explorer_swamp"),
            Map.entry("minecraft:desert_village_map", "filled_map.village_desert"),
            Map.entry("minecraft:plains_village_map", "filled_map.village_plains"),
            Map.entry("minecraft:savanna_village_map", "filled_map.village_savanna"),
            Map.entry("minecraft:snowy_village_map", "filled_map.village_snowy"),
            Map.entry("minecraft:taiga_village_map", "filled_map.village_taiga"),
            Map.entry("minecraft:buried_treasure_map", "filled_map.buried_treasure"));

    private static List<Reverse> all;

    static synchronized List<Reverse> all() {
        if (all == null) {
            List<Reverse> l = new ArrayList<>();
            l.addAll(down26_3());
            l.addAll(Steps262.list());
            l.sort(Comparator.comparingInt((Reverse r) -> r.key).reversed());
            all = l;
        }
        return all;
    }

    /** Steps for data at {@code fromVersion} going down to {@code toVersion}, newest first. */
    static List<Reverse> between(int fromVersion, int toVersion) {
        int hi = DataFixUtils.makeKey(fromVersion, 9);
        int lo = DataFixUtils.makeKey(toVersion, 9);
        return all().stream().filter(r -> r.key <= hi && r.key > lo).toList();
    }

    /** Run the reverse steps on {@code tag} (a value of {@code ref} at data version {@code from}) down to {@code to}. */
    static net.minecraft.nbt.Tag reverse(com.mojang.datafixers.DSL.TypeReference ref, net.minecraft.nbt.Tag tag, int from, int to) {
        for (Reverse r : between(from, to)) {
            try {
                tag = r.nbt != null ? r.nbt.apply(tag) : Dfu.apply(r.newer(), r.older(), ref, r.getRule(), tag);
            } catch (RuntimeException e) {
                throw new IllegalStateException("reverse step " + r + " on " + ref.typeName() + ": " + e, e);
            }
        }
        return tag;
    }

    /** {@code id} (an item of data version {@code from}) as the reverse steps down to {@code to} name it. */
    static String itemAfter(String id, int from, int to) {
        if (from >= 5012 && to < 5012) id = ITEM_RENAMES_26_3_TO_26_2.getOrDefault(id, id);
        if (from >= 5008 && to < 5008 && EXPLORER_MAP_NAME_KEYS.containsKey(id)) id = "minecraft:filled_map";
        if (from >= 4541 && to < 4541) id = Steps262.ITEM_RENAMES.getOrDefault(id, id);
        return id;
    }

    static String blockAfter(String id, int from, int to) {
        if (from >= 4541 && to < 4541) id = Steps262.BLOCK_RENAMES.getOrDefault(id, id);
        return id;
    }

    static String entityAfter(String id, int from, int to) {
        if (from >= 4067 && to < 4067) id = Steps262.entityAfterReversal(id);
        else if (from >= 4306 && to < 4306 && (id.equals("minecraft:splash_potion") || id.equals("minecraft:lingering_potion"))) id = "minecraft:potion";
        return id;
    }

    /** An attribute id as the target names it (4888 name tag, 4055 prefixes). */
    static String attributeAfter(String id, int from, int to) {
        if (from >= 4888 && to < 4888 && id.equals("minecraft:name_tag_distance")) id = "minecraft:nameplate_distance";
        if (from >= 4055 && to < 4055) id = Steps262.prefixed(id);
        return id;
    }

    // ---- 26.3 (5023) → 26.2 (4903) ----------------------------------------------------------------------------------

    private static List<Reverse> down26_3() {
        List<Reverse> l = new ArrayList<>();
        l.add(new Reverse("UnfilledBuriedTreasureMap", DataFixUtils.makeKey(5016)) {
            @Override protected TypeRewriteRule makeRule() {
                return whole(References.ITEM_STACK, stack -> !"minecraft:map".equals(id(stack)) ? stack
                        : stack.update("components", c -> c.update("minecraft:item_name", n ->
                                "item.minecraft.buried_treasure_map".equals(n.get("translate").asString(""))
                                        ? n.set("translate", n.createString("filled_map.buried_treasure")) : n)));
            }
        });
        l.add(new Reverse("MergeTerrainChunkStatus", DataFixUtils.makeKey(5013)) {
            @Override protected TypeRewriteRule makeRule() {
                return remainder(References.CHUNK, chunk -> {
                    chunk = chunk.update("Status", s -> "minecraft:terrain".equals(s.asString("")) ? s.createString("minecraft:carvers") : s);
                    return chunk.update("below_zero_retrogen", b -> b.update("target_status",
                            s -> "minecraft:terrain".equals(s.asString("")) ? s.createString("minecraft:carvers") : s));
                });
            }
        });
        l.add(new Reverse("ExplorerMapRenames", DataFixUtils.makeKey(5012)) {
            @Override protected TypeRewriteRule makeRule() {
                return whole(References.ITEM_STACK, stack -> {
                    String to = ITEM_RENAMES_26_3_TO_26_2.get(id(stack));
                    return to == null ? stack : stack.set("id", stack.createString(to));
                });
            }
        });
        l.add(new Reverse("BlendingDataNoValueHeight", DataFixUtils.makeKey(5009)) {
            @Override protected TypeRewriteRule makeRule() {
                return remainder(References.CHUNK, chunk -> chunk.update("blending_data", b -> b.update("heights",
                        h -> h.createList(h.asStream().map(v -> {
                            float f = v.asFloat(Float.MAX_VALUE);
                            return f == Float.MAX_VALUE ? v.createDouble(Double.MAX_VALUE) : v.createDouble(f);
                        })))));
            }
        });
        l.add(new Reverse("ExplorerMapItem", DataFixUtils.makeKey(5008)) {
            @Override protected TypeRewriteRule makeRule() {
                return whole(References.ITEM_STACK, stack -> {
                    String nameKey = EXPLORER_MAP_NAME_KEYS.get(id(stack));
                    if (nameKey == null) return stack;
                    Dynamic<?> s = stack.set("id", stack.createString("minecraft:filled_map"));
                    return s.update("components", c -> c.get("minecraft:item_name").result().isPresent() ? c
                            : c.set("minecraft:item_name", c.emptyMap().set("translate", c.createString(nameKey))));
                });
            }
        });
        l.add(new Reverse("SwingAnimationComponentSplit", DataFixUtils.makeKey(5007)) {
            @Override protected TypeRewriteRule makeRule() {
                return remainder(References.DATA_COMPONENTS, c -> {
                    var attack = c.get("minecraft:attack_animation").result();
                    var interact = c.get("minecraft:interact_animation").result();
                    if (attack.isEmpty() && interact.isEmpty()) return c;
                    Dynamic<?> swing = attack.isPresent() ? attack.get() : interact.get();
                    return c.remove("minecraft:attack_animation").remove("minecraft:interact_animation").set("minecraft:swing_animation", swing);
                });
            }
        });
        l.add(new Reverse("BlockStateFieldNames", DataFixUtils.makeKey(5006)) {
            @Override protected TypeRewriteRule makeRule() {
                // 26.3 also writes a property-less state as just its name ("minecraft:tuff"); 26.2 reads only the compound
                return remainder(References.BLOCK_STATE, s -> s.asString().result().isPresent()
                        ? s.emptyMap().set("Name", s)
                        : s.renameField("id", "Name").renameField("properties", "Properties"));
            }
        });
        l.add(new Reverse("SignsOpFlag", DataFixUtils.makeKey(5002)) {
            @Override protected TypeRewriteRule makeRule() {
                return remainder(References.BLOCK_ENTITY, be -> {
                    String id = be.get("id").asString("");
                    return id.equals("minecraft:sign") || id.equals("minecraft:hanging_sign") ? be.remove("allow_op_features") : be;
                });
            }
        });
        l.add(new Reverse("PotDecorationsBlockEntityUnflattening", DataFixUtils.makeKey(4996, 1)) {
            @Override protected TypeRewriteRule makeRule() {
                return whole(References.BLOCK_ENTITY, be -> "minecraft:decorated_pot".equals(be.get("id").asString(""))
                        ? be.update("sherds", Steps::flattenSherds) : be);
            }
        });
        l.add(new Reverse("PotDecorationsComponentUnflattening", DataFixUtils.makeKey(4996)) {
            @Override protected TypeRewriteRule makeRule() {
                return whole(References.DATA_COMPONENTS, c -> c.update("minecraft:pot_decorations", Steps::flattenSherds));
            }
        });
        return l;
    }

    /** {back, left, right, front: {id}} → [back, left, right, front] (the order the forward fix unpacks). */
    static Dynamic<?> flattenSherds(Dynamic<?> d) {
        if (d.asStreamOpt().result().isPresent()) return d;   // already a list
        List<Dynamic<?>> ids = new ArrayList<>();
        for (String side : new String[]{"back", "left", "right", "front"}) {
            ids.add(d.createString(d.get(side).get("id").asString("minecraft:brick")));
        }
        return d.createList(ids.stream());
    }

    static String id(Dynamic<?> d) {
        return d.get("id").asString("");
    }
}
