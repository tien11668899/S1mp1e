package dev.s1mp1e.worldconv;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.References;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse steps from 26.2 (4903) down to 1.21.1 (3955), newest first: each undoes one forward fix of 26.3's
 * DataFixers between data versions 4054 and 4899 (docs/WORLD_CONVERT.md lists them). Fixes that only add new
 * entity or block entity types need none (the registry pass replaces what the older version lacks); the 4772 file
 * layout and level.dat split are undone by {@link FileLayout}.
 */
final class Steps262 {
    private Steps262() {}

    /** Ids a reverse step renames (newer → older), for the registry pass's "known after reversal". */
    static final Map<String, String> ITEM_RENAMES = Map.of("minecraft:iron_chain", "minecraft:chain");
    static final Map<String, String> BLOCK_RENAMES = Map.of("minecraft:iron_chain", "minecraft:chain");

    /** 4067: the per-wood boat entities, merged back into boat / chest_boat with a Type. */
    static final Map<String, String[]> BOATS = new LinkedHashMap<>();

    static {
        String[] woods = {"oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "mangrove"};
        for (String w : woods) {
            BOATS.put("minecraft:" + w + "_boat", new String[]{"minecraft:boat", w});
            BOATS.put("minecraft:" + w + "_chest_boat", new String[]{"minecraft:chest_boat", w});
        }
        BOATS.put("minecraft:bamboo_raft", new String[]{"minecraft:boat", "bamboo"});
        BOATS.put("minecraft:bamboo_chest_raft", new String[]{"minecraft:chest_boat", "bamboo"});
    }

    /** Entity ids the reverse steps rename (boats 4067, thrown potions 4306). */
    static String entityAfterReversal(String id) {
        String[] boat = BOATS.get(id);
        if (boat != null) return boat[0];
        if (id.equals("minecraft:splash_potion") || id.equals("minecraft:lingering_potion")) return "minecraft:potion";
        return id;
    }

    static final Map<String, String> RECIPE_RENAMES = inverse(Map.ofEntries(
            Map.entry("minecraft:chiseled_stone_bricks_stone_from_stonecutting", "minecraft:chiseled_stone_bricks_from_stone_stonecutting"),
            Map.entry("minecraft:end_stone_brick_slab_from_end_stone_brick_stonecutting", "minecraft:end_stone_brick_slab_from_end_stone_bricks_stonecutting"),
            Map.entry("minecraft:end_stone_brick_stairs_from_end_stone_brick_stonecutting", "minecraft:end_stone_brick_stairs_from_end_stone_bricks_stonecutting"),
            Map.entry("minecraft:end_stone_brick_wall_from_end_stone_brick_stonecutting", "minecraft:end_stone_brick_wall_from_end_stone_bricks_stonecutting"),
            Map.entry("minecraft:mossy_stone_brick_slab_from_mossy_stone_brick_stonecutting", "minecraft:mossy_stone_brick_slab_from_mossy_stone_bricks_stonecutting"),
            Map.entry("minecraft:mossy_stone_brick_stairs_from_mossy_stone_brick_stonecutting", "minecraft:mossy_stone_brick_stairs_from_mossy_stone_bricks_stonecutting"),
            Map.entry("minecraft:mossy_stone_brick_wall_from_mossy_stone_brick_stonecutting", "minecraft:mossy_stone_brick_wall_from_mossy_stone_bricks_stonecutting"),
            Map.entry("minecraft:prismarine_brick_slab_from_prismarine_stonecutting", "minecraft:prismarine_brick_slab_from_prismarine_bricks_stonecutting"),
            Map.entry("minecraft:prismarine_brick_stairs_from_prismarine_stonecutting", "minecraft:prismarine_brick_stairs_from_prismarine_bricks_stonecutting"),
            Map.entry("minecraft:quartz_slab_from_stonecutting", "minecraft:quartz_slab_from_quartz_block_stonecutting"),
            Map.entry("minecraft:stone_brick_walls_from_stone_stonecutting", "minecraft:stone_brick_wall_from_stone_stonecutting")));

    static final Map<String, String> ADVANCEMENT_RENAMES;

    static {
        Map<String, String> m = new HashMap<>(inverse(Map.ofEntries(
                Map.entry("minecraft:recipes/building_blocks/chiseled_stone_bricks_stone_from_stonecutting", "minecraft:recipes/building_blocks/chiseled_stone_bricks_from_stone_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/end_stone_brick_slab_from_end_stone_brick_stonecutting", "minecraft:recipes/building_blocks/end_stone_brick_slab_from_end_stone_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/end_stone_brick_stairs_from_end_stone_brick_stonecutting", "minecraft:recipes/building_blocks/end_stone_brick_stairs_from_end_stone_bricks_stonecutting"),
                Map.entry("minecraft:recipes/decorations/end_stone_brick_wall_from_end_stone_brick_stonecutting", "minecraft:recipes/decorations/end_stone_brick_wall_from_end_stone_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/mossy_stone_brick_slab_from_mossy_stone_brick_stonecutting", "minecraft:recipes/building_blocks/mossy_stone_brick_slab_from_mossy_stone_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/mossy_stone_brick_stairs_from_mossy_stone_brick_stonecutting", "minecraft:recipes/building_blocks/mossy_stone_brick_stairs_from_mossy_stone_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/mossy_stone_brick_wall_from_mossy_stone_brick_stonecutting", "minecraft:recipes/decorations/mossy_stone_brick_wall_from_mossy_stone_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/prismarine_brick_slab_from_prismarine_stonecutting", "minecraft:recipes/building_blocks/prismarine_brick_slab_from_prismarine_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/prismarine_brick_stairs_from_prismarine_stonecutting", "minecraft:recipes/building_blocks/prismarine_brick_stairs_from_prismarine_bricks_stonecutting"),
                Map.entry("minecraft:recipes/building_blocks/quartz_slab_from_stonecutting", "minecraft:recipes/building_blocks/quartz_slab_from_quartz_block_stonecutting"),
                Map.entry("minecraft:recipes/decorations/stone_brick_walls_from_stone_stonecutting", "minecraft:recipes/decorations/stone_brick_wall_from_stone_stonecutting"))));
        m.put("minecraft:adventure/use_lodestone", "minecraft:nether/use_lodestone");   // 4311
        ADVANCEMENT_RENAMES = Map.copyOf(m);
    }

    private static Map<String, String> inverse(Map<String, String> m) {
        Map<String, String> r = new HashMap<>();
        m.forEach((k, v) -> r.put(v, k));
        return Map.copyOf(r);
    }

    static List<Reverse> list() {
        List<Reverse> l = new ArrayList<>();
        l.add(Reverse.of("RenameNameplateToNameTag", 4888, r -> TypeRewriteRule.seq(
                r.compound(References.ENTITY, e -> attributeIds(e, "id", NAME_TAG)),
                r.compound(References.PLAYER, e -> attributeIds(e, "id", NAME_TAG)),
                r.compound(References.DATA_COMPONENTS, c -> modifierTypes(c, NAME_TAG, false)))));
        l.add(Reverse.of("LevelDatDifficultyAndSavedDataPreparation", 4771, r -> r.compound(References.LEVEL, Steps262::levelPreparation)));
        l.add(Reverse.of("DayTimeToClock", 4766, r -> r.compound(References.LEVEL, d -> {
            Tag clocks = d.remove("world_clocks");
            if (clocks instanceof CompoundTag c && c.get("minecraft:overworld") instanceof CompoundTag o) {
                d.putLong("DayTime", o.getLongOr("total_ticks", d.getLongOr("Time", 0)));
            }
        })));
        l.add(Reverse.of("VillagerDataFinalized", 4763, r -> r.named(References.ENTITY, Set.of("minecraft:villager", "minecraft:zombie_villager"),
                e -> e.remove("VillagerDataFinalized"))));
        l.add(Reverse.of("GameRuleRegistry", 4658, r -> r.compound(References.LEVEL, d -> {
            if (d.get("game_rules") instanceof CompoundTag rules) {
                d.remove("game_rules");
                d.put("GameRules", gameRulesDown(rules));
            }
        })));
        l.add(Reverse.of("WorldBorderWarningTime", 4657, r -> r.compound(References.LEVEL, d -> {
            // the forward fix only touches the saved data, so a 1.21.1 world never got its seconds turned into ticks; a
            // 26.x border is in ticks
            if (d.get("world_border") instanceof CompoundTag b && b.contains("warning_time")) {
                b.putInt("warning_time", b.getIntOr("warning_time", 300) / 20);
            }
        })));
        l.add(Reverse.of("WorldSpawnAndPlayerRespawnData", 4548, r -> TypeRewriteRule.seq(
                r.compound(References.LEVEL, d -> {
                    if (d.get("spawn") instanceof CompoundTag s) {
                        d.remove("spawn");
                        int[] pos = s.getIntArray("pos").orElse(new int[3]);
                        d.putInt("SpawnX", pos.length > 0 ? pos[0] : 0);
                        d.putInt("SpawnY", pos.length > 1 ? pos[1] : 0);
                        d.putInt("SpawnZ", pos.length > 2 ? pos[2] : 0);
                        d.putFloat("SpawnAngle", s.getFloatOr("yaw", 0));
                    }
                }),
                r.compound(References.PLAYER, p -> {
                    if (p.get("respawn") instanceof CompoundTag rs) {
                        rs.putFloat("angle", rs.getFloatOr("yaw", 0));
                        rs.remove("yaw");
                        rs.remove("pitch");
                    }
                }))));
        l.add(Reverse.of("LegacyWorldBorder", 4544, r -> r.compound(References.LEVEL, d -> {
            if (!(d.get("world_border") instanceof CompoundTag b)) return;
            d.remove("world_border");
            d.putDouble("BorderCenterX", b.getDoubleOr("center_x", 0));
            d.putDouble("BorderCenterZ", b.getDoubleOr("center_z", 0));
            d.putDouble("BorderSize", b.getDoubleOr("size", 5.999997E7F));
            d.putLong("BorderSizeLerpTime", b.getLongOr("lerp_time", 0));
            d.putDouble("BorderSizeLerpTarget", b.getDoubleOr("lerp_target", 0));
            d.putDouble("BorderSafeZone", b.getDoubleOr("safe_zone", 5));
            d.putDouble("BorderDamagePerBlock", b.getDoubleOr("damage_per_block", 0.2));
            d.putDouble("BorderWarningBlocks", b.getIntOr("warning_blocks", 5));
            d.putDouble("BorderWarningTime", b.getIntOr("warning_time", 15));
        })));
        l.add(Reverse.of("IronChainRename", 4541, r -> TypeRewriteRule.seq(
                r.whole(References.BLOCK_NAME, s -> rename(s, BLOCK_RENAMES)),
                r.compound(References.BLOCK_STATE, s -> {
                    String n = s.getStringOr("Name", "");
                    if (BLOCK_RENAMES.containsKey(n)) s.putString("Name", BLOCK_RENAMES.get(n));
                }),
                r.whole(References.FLAT_BLOCK_STATE, s -> {
                    String v = s.asString().result().orElse("");
                    for (var e : BLOCK_RENAMES.entrySet()) {
                        if (v.equals(e.getKey()) || v.startsWith(e.getKey() + "[") || v.startsWith(e.getKey() + "{")) {
                            return new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(e.getValue() + v.substring(e.getKey().length())));
                        }
                    }
                    return s;
                }),
                r.whole(References.ITEM_NAME, s -> rename(s, ITEM_RENAMES)))));
        l.add(Reverse.of("LocatorBarIcon", 4424, r -> TypeRewriteRule.seq(
                r.compound(References.ENTITY, e -> e.remove("locator_bar_icon")),
                r.compound(References.PLAYER, e -> e.remove("locator_bar_icon")))));
        l.add(Reverse.of("AreaEffectCloudCustomParticle", 4420, r -> r.named(References.ENTITY, Set.of("minecraft:area_effect_cloud"),
                e -> move(e, "custom_particle", "Particle"))));
        l.add(Reverse.of("InlineBlockPosFormat", 4314, r -> TypeRewriteRule.seq(
                r.compound(References.PLAYER, Steps262::inlinePlayer),
                r.compound(References.ENTITY, Steps262::inlineEntity))));
        l.add(Reverse.of("PlayerEquipment", 4312, r -> r.compound(References.PLAYER, p -> {
            if (!(p.remove("equipment") instanceof CompoundTag eq)) return;
            ListTag inv = p.getListOrEmpty("Inventory");
            Map<String, Integer> slots = Map.of("feet", 100, "legs", 101, "chest", 102, "head", 103, "offhand", -106);
            for (String k : new String[]{"feet", "legs", "chest", "head", "offhand"}) {
                if (eq.get(k) instanceof CompoundTag item) {
                    CompoundTag withSlot = item.copy();
                    withSlot.putByte("Slot", (byte) (int) slots.get(k));
                    inv.add(withSlot);
                }
            }
            p.put("Inventory", inv);
        })));
        l.add(Reverse.of("RaidRenamesAndTicketPos", 4309, r -> TypeRewriteRule.seq(
                r.compound(References.SAVED_DATA_RAIDS, s -> raidsDown(s.getCompoundOrEmpty("data"))),
                r.compound(References.SAVED_DATA_TICKETS, s -> {
                    for (Tag t : s.getCompoundOrEmpty("data").getListOrEmpty("tickets")) {
                        if (t instanceof CompoundTag ticket && ticket.getIntArray("chunk_pos").isPresent()) {
                            int[] p = ticket.getIntArray("chunk_pos").get();
                            ticket.putLong("chunk_pos", ((long) p[0] & 0xFFFFFFFFL) | ((long) p[1] & 0xFFFFFFFFL) << 32);
                        }
                    }
                }))));
        l.add(Reverse.of("TooltipDisplayComponent", 4307, r -> r.compound(References.DATA_COMPONENTS, Steps262::tooltipDown)));
        // entity ids the newer schema does not know: renamed on the NBT itself (DFU would have to read them as the newer type)
        l.add(Reverse.nbt("ThrownPotionSplit", 4306, t -> entities(t, e -> {
            String id = e.getStringOr("id", "");
            if (id.equals("minecraft:splash_potion") || id.equals("minecraft:lingering_potion")) e.putString("id", "minecraft:potion");
        }, "")));
        l.add(Reverse.of("EntityFallDistanceToFloat", 4303, r -> TypeRewriteRule.seq(
                r.compound(References.ENTITY, Steps262::fallDistance),
                r.compound(References.PLAYER, Steps262::fallDistance))));
        // on the whole entity, not its ENTITY_EQUIPMENT part: DFU keeps a copy of every field in the entity's remainder,
        // so a field removed from the part alone would stay
        l.add(Reverse.of("EquipmentFormat", 4301, r -> r.compound(References.ENTITY, Steps262::equipmentDown)));
        l.add(Reverse.of("SaddleEquipmentSlot", 4300, r -> r.compound(References.ENTITY, Steps262::saddleDown)));
        l.add(Reverse.of("EntitySpawnerItemVariantComponent", 4299, r -> r.compound(References.ITEM_STACK, Steps262::spawnerVariantsDown)));
        l.add(Reverse.of("ForcedChunkToTicket", 4297, r -> r.compound(References.SAVED_DATA_TICKETS, s -> {
            CompoundTag data = s.getCompoundOrEmpty("data");
            if (!(data.remove("tickets") instanceof ListTag tickets)) return;
            List<Long> forced = new ArrayList<>();
            for (Tag t : tickets) {
                if (t instanceof CompoundTag ticket && ticket.getStringOr("type", "").equals("minecraft:forced")) {
                    forced.add(ticket.getLongOr("chunk_pos", 0));
                }
            }
            data.put("Forced", new LongArrayTag(forced.stream().mapToLong(Long::longValue).toArray()));
            s.put("data", data);
        })));
        l.add(Reverse.of("AreaEffectCloudDurationScale", 4296, r -> r.named(References.ENTITY, Set.of("minecraft:area_effect_cloud"),
                e -> e.remove("potion_duration_scale"))));
        l.add(Reverse.of("DropChancesFormat", 4293, r -> r.compound(References.ENTITY, Steps262::dropChancesDown)));
        l.add(Reverse.of("TextComponentHoverAndClickEvent", 4292, r -> r.sameType(r.newer().getType(References.TEXT_COMPONENT),
                d -> new Dynamic<>(NbtOps.INSTANCE, Text.hoverClickDown(d.convert(NbtOps.INSTANCE).getValue())))));
        l.add(Reverse.of("UnflattenTextComponent", 4290, r -> TypeRewriteRule.seq(
                // text components are a recursive type: keep the newer one (a JSON string reads as a literal there)
                // and let the next step read it as the older string type
                r.sameType(r.newer().getType(References.TEXT_COMPONENT), Text::toJson),
                r.sameType(r.newer().getType(References.TEXT_COMPONENT), Text::unmark))));
        l.add(Reverse.of("FollowRangeBaseValues", 4187, r -> r.compound(References.ENTITY, e -> {
            String id = e.getStringOr("id", "");
            double[] fromTo = switch (id) {
                case "minecraft:villager", "minecraft:bee", "minecraft:allay" -> new double[]{16, 48};
                case "minecraft:llama" -> new double[]{16, 40};
                case "minecraft:piglin_brute" -> new double[]{12, 16};
                case "minecraft:warden" -> new double[]{24, 16};
                default -> null;
            };
            if (fromTo == null) return;
            for (Tag t : e.getListOrEmpty("attributes")) {
                if (t instanceof CompoundTag a && a.getStringOr("id", "").equals("minecraft:follow_range") && a.getDoubleOr("base", -1) == fromTo[0]) {
                    a.putDouble("base", fromTo[1]);
                }
            }
        })));
        l.add(Reverse.of("FurnaceBurnTime", 4181, r -> r.named(References.BLOCK_ENTITY, Set.of("minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace"),
                be -> {
                    move(be, "cooking_time_spent", "CookTime");
                    move(be, "cooking_total_time", "CookTimeTotal");
                    move(be, "lit_time_remaining", "BurnTime");
                    be.remove("lit_total_time");
                })));
        l.add(Reverse.of("CustomModelDataExpand", 4175, r -> r.compound(References.DATA_COMPONENTS, c -> {
            if (!(c.get("minecraft:custom_model_data") instanceof CompoundTag cmd)) return;
            ListTag floats = cmd.getListOrEmpty("floats");
            if (cmd.size() == 1 && floats.size() == 1) c.putInt("minecraft:custom_model_data", (int) floats.getFloatOr(0, 0));
            else c.remove("minecraft:custom_model_data");
        })));
        l.add(Reverse.of("TntMinecartFuse", 4173, r -> r.named(References.ENTITY, Set.of("minecraft:tnt_minecart"), e -> move(e, "fuse", "TNTFuse"))));
        l.add(Reverse.of("EntitySalmonSize", 4081, r -> r.named(References.ENTITY, Set.of("minecraft:salmon"), e -> e.remove("type"))));
        l.add(Reverse.of("LockComponentPredicate", 4068, r -> TypeRewriteRule.seq(
                r.compound(References.DATA_COMPONENTS, c -> {
                    if (!c.contains("minecraft:lock")) return;
                    String name = lockName(c.remove("minecraft:lock"));
                    if (name != null) c.putString("minecraft:lock", name);
                }),
                r.compound(References.BLOCK_ENTITY, be -> {
                    if (!be.contains("lock")) return;
                    String name = lockName(be.remove("lock"));
                    if (name != null) be.putString("Lock", name);
                }))));
        l.add(Reverse.of("BundleFeatureFlag", 4067, r -> r.compound(References.LEVEL, d -> restoreFeatureFlag(d, "minecraft:bundle"))));
        l.add(Reverse.nbt("BoatSplit", 4067, t -> entities(t, e -> {
            String[] boat = BOATS.get(e.getStringOr("id", ""));
            if (boat == null) return;
            e.putString("id", boat[0]);
            e.putString("Type", boat[1]);
        }, "")));
        l.add(Reverse.of("FireResistantToDamageResistant", 4064, r -> r.compound(References.DATA_COMPONENTS, c -> {
            if (!(c.remove("minecraft:damage_resistant") instanceof CompoundTag dr)) return;
            if (dr.getStringOr("types", "").equals("#minecraft:is_fire")) c.put("minecraft:fire_resistant", new CompoundTag());
        })));
        l.add(Reverse.of("TrialSpawnerConfigInRegistry", 4061, r -> r.named(References.BLOCK_ENTITY, Set.of("minecraft:trial_spawner"), be -> {
            String normal = be.getStringOr("normal_config", null), ominous = be.getStringOr("ominous_config", null);
            if (normal == null || ominous == null) return;
            Pair<Tag, Tag> inline = TrialConfigs.inline(normal);
            be.remove("normal_config");
            be.remove("ominous_config");
            if (inline != null && ominous.equals(normal.replaceAll("/normal$", "/ominous"))) {
                be.put("normal_config", inline.getFirst().copy());
                be.put("ominous_config", inline.getSecond().copy());
            }
        })));
        l.add(Reverse.of("FoodToConsumable", 4059, r -> r.compound(References.DATA_COMPONENTS, Steps262::foodDown)));
        l.add(Reverse.of("AttributeIdPrefix", 4055, r -> TypeRewriteRule.seq(
                r.compound(References.DATA_COMPONENTS, c -> modifierTypes(c, Steps262::prefixed, true)),
                r.compound(References.ENTITY, e -> attributeIds(e, "id", Steps262::prefixed)),
                r.compound(References.PLAYER, e -> attributeIds(e, "id", Steps262::prefixed)))));
        l.add(Reverse.of("OminousBannerRarity", 4054, r -> TypeRewriteRule.seq(
                r.named(References.BLOCK_ENTITY, Set.of("minecraft:banner"), be -> ominousBanner(be.getCompoundOrEmpty("components"))),
                r.compound(References.ITEM_STACK, s -> {
                    if (s.getStringOr("id", "").equals("minecraft:white_banner")) ominousBanner(s.getCompoundOrEmpty("components"));
                }))));
        return l;
    }

    // ---- attributes -------------------------------------------------------------------------------------------------

    private static final java.util.function.UnaryOperator<String> NAME_TAG =
            id -> id.equals("minecraft:name_tag_distance") ? "minecraft:nameplate_distance" : id;

    private static Map<String, String> prefixes;

    /** 4055: the 1.21.1 id (generic./player./zombie. prefix) of a 26.x attribute, from 1.21.1's own registry. */
    static synchronized String prefixed(String id) {
        if (prefixes == null) {
            Map<String, String> m = new HashMap<>();
            for (String a : Registry.of("1.21.1").list("attribute")) {
                String path = a.substring(a.indexOf(':') + 1);
                int dot = path.indexOf('.');
                if (dot > 0) m.put("minecraft:" + path.substring(dot + 1), a);
            }
            prefixes = m;
        }
        return prefixes.getOrDefault(id, id);
    }

    private static void attributeIds(CompoundTag e, String field, java.util.function.UnaryOperator<String> f) {
        for (Tag t : e.getListOrEmpty("attributes")) {
            if (t instanceof CompoundTag a && a.contains(field)) a.putString(field, f.apply(a.getStringOr(field, "")));
        }
    }

    /** Attribute modifier components: a list (4307 and later) or {modifiers: [...]} (before). */
    private static void modifierTypes(CompoundTag c, java.util.function.UnaryOperator<String> f, boolean wrapped) {
        Tag am = c.get("minecraft:attribute_modifiers");
        ListTag list = wrapped && am instanceof CompoundTag w ? w.getListOrEmpty("modifiers") : am instanceof ListTag l ? l : null;
        if (list == null) return;
        for (Tag t : list) {
            if (t instanceof CompoundTag m && m.contains("type")) m.putString("type", f.apply(m.getStringOr("type", "")));
        }
    }

    // ---- level.dat --------------------------------------------------------------------------------------------------

    private static void levelPreparation(CompoundTag d) {
        if (d.remove("difficulty_settings") instanceof CompoundTag ds) {
            d.putByte("Difficulty", (byte) switch (ds.getStringOr("difficulty", "normal")) {
                case "peaceful" -> 0;
                case "easy" -> 1;
                case "hard" -> 3;
                default -> 2;
            });
            d.putBoolean("hardcore", ds.getBooleanOr("hardcore", false));
            d.putBoolean("DifficultyLocked", ds.getBooleanOr("locked", false));
        }
        if (d.remove("dragon_fight") instanceof CompoundTag f) {
            CompoundTag o = new CompoundTag();
            for (String k : f.keySet()) o.put(k, f.get(k));
            move(o, "needs_state_scanning", "NeedsStateScanning");
            move(o, "dragon_killed", "DragonKilled");
            move(o, "previously_killed", "PreviouslyKilled");
            move(o, "dragon_uuid", "Dragon");
            move(o, "exit_portal_location", "ExitPortalLocation");
            move(o, "gateways", "Gateways");
            boolean respawning = o.remove("respawn_stage") != null;
            o.remove("respawn_time");
            if (respawning) o.putBoolean("IsRespawning", true);
            d.put("DragonFight", o);
        }
        if (d.remove("wandering_trader_migration_data") instanceof CompoundTag w) {
            d.putInt("WanderingTraderSpawnDelay", w.getIntOr("spawn_delay", 24000));
            d.putInt("WanderingTraderSpawnChance", w.getIntOr("spawn_chance", 25));
        }
        if (d.remove("weather_data") instanceof CompoundTag w) {
            d.putInt("clearWeatherTime", w.getIntOr("clear_weather_time", 0));
            d.putInt("rainTime", w.getIntOr("rain_time", 0));
            d.putInt("thunderTime", w.getIntOr("thunder_time", 0));
            d.putBoolean("raining", w.getBooleanOr("raining", false));
            d.putBoolean("thundering", w.getBooleanOr("thundering", false));
        }
        if (d.remove("scheduled_events") instanceof CompoundTag s) {
            ListTag out = new ListTag();
            for (Tag t : s.getListOrEmpty("events")) {
                if (!(t instanceof CompoundTag e)) continue;
                CompoundTag o = e.copy();
                move(o, "id", "Name");
                move(o, "trigger_time", "TriggerTime");
                if (o.remove("callback") instanceof CompoundTag cb) {
                    move(cb, "type", "Type");
                    move(cb, "id", "Name");
                    o.put("Callback", cb);
                }
                out.add(o);
            }
            d.put("ScheduledEvents", out);
        }
        if (d.remove("world_gen_settings") instanceof CompoundTag wgs) {
            move(wgs, "generate_structures", "generate_features");
            d.put("WorldGenSettings", wgs);
        }
    }

    /** 4658 backwards: registry game rules (typed values) → 1.21.1's camelCase string rules. */
    static CompoundTag gameRulesDown(CompoundTag rules) {
        CompoundTag out = new CompoundTag();
        for (String k : rules.keySet()) {
            String[] to = GAME_RULES.get(k);
            Tag v = rules.get(k);
            if (k.equals("minecraft:fire_spread_radius_around_player")) {
                int radius = v.asInt().orElse(128);
                if (radius == 0) out.putString("doFireTick", "false");
                else if (radius < 0) {
                    out.putString("doFireTick", "true");
                    out.putString("allowFireTicksAwayFromPlayer", "true");
                }
                continue;
            }
            if (to == null) continue;
            String s = switch (to[1]) {
                case "bool" -> String.valueOf(v.asBoolean().orElse(false));
                case "inv" -> String.valueOf(!v.asBoolean().orElse(false));
                default -> String.valueOf(v.asInt().orElse(0));
            };
            out.putString(to[0], s);
        }
        return out;
    }

    /** 26.x game rule → {1.21.1 name, bool | inv (inverted boolean) | int}. */
    static final Map<String, String[]> GAME_RULES = new HashMap<>();

    static {
        String[][] rows = {
                {"allow_entering_nether_using_portals", "allowEnteringNetherUsingPortals", "bool"},
                {"show_advancement_messages", "announceAdvancements", "bool"},
                {"block_explosion_drop_decay", "blockExplosionDropDecay", "bool"},
                {"command_block_output", "commandBlockOutput", "bool"},
                {"command_blocks_work", "commandBlocksEnabled", "bool"},
                {"max_block_modifications", "commandModificationBlockLimit", "int"},
                {"elytra_movement_check", "disableElytraMovementCheck", "inv"},
                {"player_movement_check", "disablePlayerMovementCheck", "inv"},
                {"raids", "disableRaids", "inv"},
                {"advance_time", "doDaylightCycle", "bool"},
                {"entity_drops", "doEntityDrops", "bool"},
                {"immediate_respawn", "doImmediateRespawn", "bool"},
                {"spawn_phantoms", "doInsomnia", "bool"},
                {"limited_crafting", "doLimitedCrafting", "bool"},
                {"mob_drops", "doMobLoot", "bool"},
                {"spawn_mobs", "doMobSpawning", "bool"},
                {"spawn_patrols", "doPatrolSpawning", "bool"},
                {"block_drops", "doTileDrops", "bool"},
                {"spawn_wandering_traders", "doTraderSpawning", "bool"},
                {"spread_vines", "doVinesSpread", "bool"},
                {"spawn_wardens", "doWardenSpawning", "bool"},
                {"advance_weather", "doWeatherCycle", "bool"},
                {"drowning_damage", "drowningDamage", "bool"},
                {"ender_pearls_vanish_on_death", "enderPearlsVanishOnDeath", "bool"},
                {"fall_damage", "fallDamage", "bool"},
                {"fire_damage", "fireDamage", "bool"},
                {"forgive_dead_players", "forgiveDeadPlayers", "bool"},
                {"freeze_damage", "freezeDamage", "bool"},
                {"global_sound_events", "globalSoundEvents", "bool"},
                {"keep_inventory", "keepInventory", "bool"},
                {"lava_source_conversion", "lavaSourceConversion", "bool"},
                {"locator_bar", "locatorBar", "bool"},
                {"log_admin_commands", "logAdminCommands", "bool"},
                {"max_command_sequence_length", "maxCommandChainLength", "int"},
                {"max_command_forks", "maxCommandForkCount", "int"},
                {"max_entity_cramming", "maxEntityCramming", "int"},
                {"max_minecart_speed", "minecartMaxSpeed", "int"},
                {"mob_explosion_drop_decay", "mobExplosionDropDecay", "bool"},
                {"mob_griefing", "mobGriefing", "bool"},
                {"natural_health_regeneration", "naturalRegeneration", "bool"},
                {"players_nether_portal_creative_delay", "playersNetherPortalCreativeDelay", "int"},
                {"players_nether_portal_default_delay", "playersNetherPortalDefaultDelay", "int"},
                {"players_sleeping_percentage", "playersSleepingPercentage", "int"},
                {"projectiles_can_break_blocks", "projectilesCanBreakBlocks", "bool"},
                {"pvp", "pvp", "bool"},
                {"random_tick_speed", "randomTickSpeed", "int"},
                {"reduced_debug_info", "reducedDebugInfo", "bool"},
                {"send_command_feedback", "sendCommandFeedback", "bool"},
                {"show_death_messages", "showDeathMessages", "bool"},
                {"max_snow_accumulation_height", "snowAccumulationHeight", "int"},
                {"spawn_monsters", "spawnMonsters", "bool"},
                {"respawn_radius", "spawnRadius", "int"},
                {"spawner_blocks_work", "spawnerBlocksEnabled", "bool"},
                {"spectators_generate_chunks", "spectatorsGenerateChunks", "bool"},
                {"tnt_explodes", "tntExplodes", "bool"},
                {"tnt_explosion_drop_decay", "tntExplosionDropDecay", "bool"},
                {"universal_anger", "universalAnger", "bool"},
                {"water_source_conversion", "waterSourceConversion", "bool"},
        };
        for (String[] r : rows) GAME_RULES.put("minecraft:" + r[0], new String[]{r[1], r[2]});
    }

    private static void restoreFeatureFlag(CompoundTag d, String flag) {
        ListTag removed = d.getListOrEmpty("removed_features");
        StringTag f = StringTag.valueOf(flag);
        if (!removed.contains(f)) return;
        removed.remove(f);
        if (removed.isEmpty()) d.remove("removed_features");
        else d.put("removed_features", removed);
        ListTag enabled = d.getListOrEmpty("enabled_features");
        if (!enabled.contains(f)) enabled.add(f);
        d.put("enabled_features", enabled);
    }

    // ---- saved data -------------------------------------------------------------------------------------------------

    private static void raidsDown(CompoundTag data) {
        if (data.remove("raids") instanceof ListTag raids) {
            for (Tag t : raids) {
                if (!(t instanceof CompoundTag raid)) continue;
                int[] c = raid.getIntArray("center").orElse(null);
                if (c != null && c.length == 3) {
                    raid.remove("center");
                    raid.putInt("CX", c[0]);
                    raid.putInt("CY", c[1]);
                    raid.putInt("CZ", c[2]);
                }
                String[][] names = {{"id", "Id"}, {"started", "Started"}, {"active", "Active"}, {"ticks_active", "TicksActive"},
                        {"raid_omen_level", "BadOmenLevel"}, {"groups_spawned", "GroupsSpawned"}, {"cooldown_ticks", "PreRaidTicks"},
                        {"post_raid_ticks", "PostRaidTicks"}, {"total_health", "TotalHealth"}, {"group_count", "NumGroups"},
                        {"status", "Status"}, {"heroes_of_the_village", "HeroesOfTheVillage"}};
                for (String[] n : names) move(raid, n[0], n[1]);
            }
            data.put("Raids", raids);
        }
        move(data, "tick", "Tick");
        move(data, "next_id", "NextAvailableID");
    }

    // ---- entities ---------------------------------------------------------------------------------------------------

    private static void inlinePlayer(CompoundTag p) {
        unpackPos(p, "sleeping_pos", "SleepingX", "SleepingY", "SleepingZ");
        if (p.remove("respawn") instanceof CompoundTag rs) {
            int[] pos = rs.getIntArray("pos").orElse(null);
            if (pos != null && pos.length == 3) {
                p.putInt("SpawnX", pos[0]);
                p.putInt("SpawnY", pos[1]);
                p.putInt("SpawnZ", pos[2]);
                if (rs.contains("angle")) p.put("SpawnAngle", rs.get("angle"));
                if (rs.contains("dimension")) p.put("SpawnDimension", rs.get("dimension"));
                if (rs.contains("forced")) p.put("SpawnForced", rs.get("forced"));
            }
        }
        if (p.remove("entered_nether_pos") instanceof ListTag l && l.size() == 3) {
            CompoundTag o = new CompoundTag();
            o.putDouble("x", l.getDoubleOr(0, 0));
            o.putDouble("y", l.getDoubleOr(1, 0));
            o.putDouble("z", l.getDoubleOr(2, 0));
            p.put("enteredNetherPosition", o);
        }
    }

    private static void inlineEntity(CompoundTag e) {
        unpackPos(e, "sleeping_pos", "SleepingX", "SleepingY", "SleepingZ");
        switch (e.getStringOr("id", "")) {
            case "minecraft:vex" -> {
                move(e, "life_ticks", "LifeTicks");
                unpackPos(e, "bound_pos", "BoundX", "BoundY", "BoundZ");
            }
            case "minecraft:phantom" -> {
                move(e, "size", "Size");
                unpackPos(e, "anchor_pos", "AX", "AY", "AZ");
            }
            case "minecraft:turtle" -> {
                unpackPos(e, "home_pos", "HomePosX", "HomePosY", "HomePosZ");
                move(e, "has_egg", "HasEgg");
            }
            case "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting", "minecraft:leash_knot" ->
                    unpackPos(e, "block_pos", "TileX", "TileY", "TileZ");
            default -> { }
        }
    }

    private static void unpackPos(CompoundTag c, String field, String x, String y, String z) {
        int[] p = c.getIntArray(field).orElse(null);
        if (p == null || p.length != 3) return;
        c.remove(field);
        c.putInt(x, p[0]);
        c.putInt(y, p[1]);
        c.putInt(z, p[2]);
    }

    private static void fallDistance(CompoundTag e) {
        if (e.remove("fall_distance") instanceof Tag t) e.putFloat("FallDistance", (float) t.asDouble().orElse(0d).doubleValue());
    }

    /** 4301 backwards: equipment {slot: stack} → ArmorItems [feet, legs, chest, head], HandItems [main, off], body, saddle. */
    private static void equipmentDown(CompoundTag t) {
        if (!(t.remove("equipment") instanceof CompoundTag eq)) return;
        String[] armor = {"feet", "legs", "chest", "head"}, hands = {"mainhand", "offhand"};
        if (anyOf(eq, armor)) t.put("ArmorItems", slots(eq, armor));
        if (anyOf(eq, hands)) t.put("HandItems", slots(eq, hands));
        if (eq.get("body") instanceof CompoundTag body) t.put("body_armor_item", body);
        if (eq.get("saddle") instanceof CompoundTag saddle) t.put("saddle", saddle);
    }

    private static boolean anyOf(CompoundTag c, String[] keys) {
        for (String k : keys) if (c.contains(k)) return true;
        return false;
    }

    private static ListTag slots(CompoundTag eq, String[] keys) {
        ListTag l = new ListTag();
        for (String k : keys) l.add(eq.get(k) instanceof CompoundTag s ? s : new CompoundTag());
        return l;
    }

    static final Set<String> SADDLE_ITEM = Set.of("minecraft:horse", "minecraft:skeleton_horse", "minecraft:zombie_horse", "minecraft:donkey",
            "minecraft:mule", "minecraft:camel", "minecraft:llama", "minecraft:trader_llama");
    static final Set<String> SADDLE_FLAG = Set.of("minecraft:pig", "minecraft:strider");

    private static void saddleDown(CompoundTag e) {
        String id = e.getStringOr("id", "");
        boolean item = SADDLE_ITEM.contains(id), flag = SADDLE_FLAG.contains(id);
        if (!item && !flag) return;
        if (!(e.get("saddle") instanceof CompoundTag saddle)) return;
        e.remove("saddle");
        if (item) e.put("SaddleItem", saddle);
        else e.putBoolean("Saddle", true);
        if (e.get("drop_chances") instanceof CompoundTag dc && dc.getFloatOr("saddle", 0) == 2.0F) {
            dc.remove("saddle");
            if (dc.isEmpty()) e.remove("drop_chances");
        }
    }

    /** 4293 backwards: drop_chances {slot: chance} → ArmorDropChances, HandDropChances, body_armor_drop_chance (default 0.085). */
    private static void dropChancesDown(CompoundTag e) {
        if (!(e.remove("drop_chances") instanceof CompoundTag dc)) return;
        e.put("ArmorDropChances", chances(dc, "feet", "legs", "chest", "head"));
        e.put("HandDropChances", chances(dc, "mainhand", "offhand"));
        if (dc.contains("body")) e.putFloat("body_armor_drop_chance", dc.getFloatOr("body", 0.085F));
    }

    private static ListTag chances(CompoundTag dc, String... slots) {
        ListTag l = new ListTag();
        for (String s : slots) l.add(FloatTag.valueOf(dc.getFloatOr(s, 0.085F)));
        return l;
    }

    // ---- items and components ---------------------------------------------------------------------------------------

    private static final List<String> ADDITIONAL_TOOLTIP = List.of("minecraft:banner_patterns", "minecraft:bees", "minecraft:block_entity_data",
            "minecraft:block_state", "minecraft:bundle_contents", "minecraft:charged_projectiles", "minecraft:container", "minecraft:container_loot",
            "minecraft:firework_explosion", "minecraft:fireworks", "minecraft:instrument", "minecraft:map_id", "minecraft:painting/variant",
            "minecraft:pot_decorations", "minecraft:potion_contents", "minecraft:tropical_fish/pattern", "minecraft:written_book_content");

    /** 4307 backwards: tooltip_display → per-component show_in_tooltip, hide_tooltip, hide_additional_tooltip; re-wrap. */
    private static void tooltipDown(CompoundTag c) {
        Set<String> hidden = new LinkedHashSet<>();
        boolean hideTooltip = false;
        if (c.remove("minecraft:tooltip_display") instanceof CompoundTag td) {
            hideTooltip = td.getBooleanOr("hide_tooltip", false);
            for (Tag t : td.getListOrEmpty("hidden_components")) hidden.add(t.asString().orElse(""));
        }
        wrap(c, "minecraft:dyed_color", "rgb", hidden);
        wrap(c, "minecraft:attribute_modifiers", "modifiers", hidden);
        wrap(c, "minecraft:enchantments", "levels", hidden);
        wrap(c, "minecraft:stored_enchantments", "levels", hidden);
        wrap(c, "minecraft:jukebox_playable", "song", hidden);
        for (String id : new String[]{"minecraft:trim", "minecraft:unbreakable"}) {
            if (c.get(id) instanceof CompoundTag v && hidden.contains(id)) v.putBoolean("show_in_tooltip", false);
        }
        for (String id : new String[]{"minecraft:can_place_on", "minecraft:can_break"}) {
            Tag v = c.get(id);
            if (v == null) continue;
            if (v instanceof ListTag || hidden.contains(id)) {
                CompoundTag w = new CompoundTag();
                ListTag preds = v instanceof ListTag l ? l : new ListTag();
                if (!(v instanceof ListTag)) preds.add(v);
                w.put("predicates", preds);
                if (hidden.contains(id)) w.putBoolean("show_in_tooltip", false);
                c.put(id, w);
            }
        }
        if (hideTooltip) c.put("minecraft:hide_tooltip", new CompoundTag());
        for (String id : ADDITIONAL_TOOLTIP) {
            if (hidden.contains(id)) {
                c.put("minecraft:hide_additional_tooltip", new CompoundTag());
                break;
            }
        }
    }

    private static void wrap(CompoundTag c, String id, String field, Set<String> hidden) {
        Tag v = c.get(id);
        if (v == null) return;
        CompoundTag w = new CompoundTag();
        w.put(field, v);
        if (hidden.contains(id)) w.putBoolean("show_in_tooltip", false);
        c.put(id, w);
    }

    /** 4299 backwards: the bucket / painting variant components back into the entity data they came from. */
    private static void spawnerVariantsDown(CompoundTag stack) {
        String id = stack.getStringOr("id", "");
        CompoundTag c = stack.getCompoundOrEmpty("components");
        switch (id) {
            case "minecraft:salmon_bucket" -> {
                if (c.contains("minecraft:salmon/size")) bucket(c).put("type", c.remove("minecraft:salmon/size"));
            }
            case "minecraft:axolotl_bucket" -> {
                if (c.remove("minecraft:axolotl/variant") instanceof Tag v) {
                    int n = switch (v.asString().orElse("lucy")) {
                        case "wild" -> 1;
                        case "gold" -> 2;
                        case "cyan" -> 3;
                        case "blue" -> 4;
                        default -> 0;
                    };
                    bucket(c).putInt("Variant", n);
                }
            }
            case "minecraft:tropical_fish_bucket" -> {
                if (!c.contains("minecraft:tropical_fish/pattern")) break;
                String pattern = c.remove("minecraft:tropical_fish/pattern").asString().orElse("kob");
                Tag base = c.remove("minecraft:tropical_fish/base_color"), patternColor = c.remove("minecraft:tropical_fish/pattern_color");
                int packed = PATTERNS.getOrDefault(pattern, 0)
                        | dye(base == null ? "white" : base.asString().orElse("white")) << 16
                        | dye(patternColor == null ? "white" : patternColor.asString().orElse("white")) << 24;
                bucket(c).putInt("BucketVariantTag", packed);
            }
            case "minecraft:painting" -> {
                if (!c.contains("minecraft:painting/variant")) break;
                CompoundTag data = c.getCompoundOrEmpty("minecraft:entity_data");
                data.putString("id", "minecraft:painting");
                data.put("variant", c.remove("minecraft:painting/variant"));
                c.put("minecraft:entity_data", data);
            }
            default -> { }
        }
        if (!c.isEmpty()) stack.put("components", c);
    }

    private static CompoundTag bucket(CompoundTag c) {
        CompoundTag b = c.getCompoundOrEmpty("minecraft:bucket_entity_data");
        c.put("minecraft:bucket_entity_data", b);
        return b;
    }

    private static final Map<String, Integer> PATTERNS = Map.ofEntries(Map.entry("kob", 0), Map.entry("flopper", 1), Map.entry("sunstreak", 256),
            Map.entry("stripey", 257), Map.entry("snooper", 512), Map.entry("glitter", 513), Map.entry("dasher", 768), Map.entry("blockfish", 769),
            Map.entry("brinely", 1024), Map.entry("betty", 1025), Map.entry("spotty", 1280), Map.entry("clayfish", 1281));

    private static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");

    private static int dye(String name) {
        int i = DYES.indexOf(name);
        return i < 0 ? 0 : i;
    }

    /** 4059 backwards: food + consumable (+ use_remainder) → the 1.21.1 food component. */
    private static void foodDown(CompoundTag c) {
        CompoundTag food = c.get("minecraft:food") instanceof CompoundTag f ? f : null;
        Tag consumable = c.remove("minecraft:consumable");
        Tag remainder = c.remove("minecraft:use_remainder");
        if (food == null) return;
        CompoundTag cons = consumable instanceof CompoundTag ct ? ct : new CompoundTag();
        food.putFloat("eat_seconds", cons.getFloatOr("consume_seconds", 1.6F));
        ListTag effects = new ListTag();
        for (Tag t : cons.getListOrEmpty("on_consume_effects")) {
            if (!(t instanceof CompoundTag e) || !e.getStringOr("type", "").equals("minecraft:apply_effects")) continue;
            for (Tag eff : e.getListOrEmpty("effects")) {
                CompoundTag o = new CompoundTag();
                o.put("effect", eff);
                o.putFloat("probability", e.getFloatOr("probability", 1.0F));
                effects.add(o);
            }
        }
        food.put("effects", effects);
        if (remainder != null) food.put("using_converts_to", remainder);
    }

    /** 4068 backwards: a lock predicate {components: {custom_name: name}} → the name; anything else cannot be a 1.21.1 lock. */
    private static String lockName(Tag lock) {
        if (!(lock instanceof CompoundTag p) || p.size() != 1) return null;
        CompoundTag comps = p.getCompoundOrEmpty("components");
        if (comps.size() != 1 || !comps.contains("minecraft:custom_name")) return null;
        Tag n = comps.get("minecraft:custom_name");
        if (n instanceof StringTag s) {
            String v = s.value();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                try {
                    return JsonParser.parseString(v).getAsString();
                } catch (RuntimeException e) {
                    return v;
                }
            }
            return v;
        }
        JsonElement j = Text.component(n);
        return j.isJsonObject() && j.getAsJsonObject().has("text") && j.getAsJsonObject().size() == 1 ? j.getAsJsonObject().get("text").getAsString() : null;
    }

    private static void ominousBanner(CompoundTag comps) {
        if (comps.isEmpty() || !comps.getStringOr("minecraft:rarity", "").equals("uncommon")) return;
        Tag name = comps.get("minecraft:item_name");
        String json = name == null ? null : name.asString().orElse(null);
        if (json == null) return;
        try {
            JsonElement j = JsonParser.parseString(json);
            if (!j.isJsonObject() || !"block.minecraft.ominous_banner".equals(j.getAsJsonObject().has("translate") ? j.getAsJsonObject().get("translate").getAsString() : "")) return;
        } catch (RuntimeException e) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("color", "gold");
        o.addProperty("translate", "block.minecraft.ominous_banner");
        comps.putString("minecraft:item_name", o.toString());
        comps.remove("minecraft:rarity");
    }

    /** Every entity compound inside {@code t} (chunk entities, passengers, spawner and spawn egg entities) through {@code f}. */
    static Tag entities(Tag t, java.util.function.Consumer<CompoundTag> f, String parentKey) {
        if (t instanceof CompoundTag c) {
            if (c.contains("id") && (c.contains("UUID") || c.contains("Pos") || parentKey.equals("entity") || parentKey.equals("minecraft:entity_data")
                    || parentKey.equals("Passengers") || parentKey.equals("Entities"))) {
                f.accept(c);
            }
            for (String k : new ArrayList<>(c.keySet())) entities(c.get(k), f, k);
        } else if (t instanceof ListTag l) {
            for (Tag e : l) entities(e, f, parentKey);
        }
        return t;
    }

    // ---- small helpers ----------------------------------------------------------------------------------------------

    private static void move(CompoundTag c, String from, String to) {
        Tag v = c.remove(from);
        if (v != null) c.put(to, v);
    }

    private static Dynamic<?> rename(Dynamic<?> s, Map<String, String> map) {
        String v = s.asString().result().orElse(null);
        return v != null && map.containsKey(v) ? new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(map.get(v))) : s;
    }

    static IntArrayTag ints(int... v) {
        return new IntArrayTag(v);
    }
}
