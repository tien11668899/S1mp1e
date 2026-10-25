package dev.s1mp1e.worldconv;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.fixes.References;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fields an older version itself writes for each entity type, block entity type and the player
 * (resources/known/<version>/dump.txt, made in that game by DevShot's dump mode), named as the newer source version
 * names them (the dump goes through the game's own upgrade). A field the older version does not know (a pig's
 * variant, a wolf's sound) would be dropped the first time it saves; the Downgrader takes such fields out itself, so
 * the way back sees them missing and keeps them in the sidecar.
 */
final class KnownKeys {
    private final Map<String, Set<String>> entities = new HashMap<>(), blockEntities = new HashMap<>();
    private Set<String> player;

    /** Fields every entity / living entity / mob of 1.21 may write but a freshly made one does not (26.x names). */
    private static final List<String> ENTITY = List.of("id", "UUID", "Pos", "Motion", "Rotation", "CustomName", "CustomNameVisible", "Silent",
            "NoGravity", "Glowing", "HasVisualFire", "Tags", "Passengers", "Invulnerable", "PortalCooldown", "TicksFrozen", "active_effects",
            "sleeping_pos", "leash", "DeathLootTable", "DeathLootTableSeed", "NoAI", "PersistenceRequired", "equipment", "drop_chances",
            "attributes", "Owner", "LoveCause", "AngryAt", "data", "Thrower", "Items", "Item", "LeftOwner", "HasBeenShot");
    private static final Map<String, List<String>> ENTITY_EXTRA = Map.ofEntries(
            Map.entry("minecraft:vex", List.of("bound_pos", "life_ticks")),
            Map.entry("minecraft:area_effect_cloud", List.of("custom_particle", "potion_contents")),
            Map.entry("minecraft:item_display", List.of("item")),
            Map.entry("minecraft:block_display", List.of("block_state")),
            Map.entry("minecraft:text_display", List.of("text")),
            Map.entry("minecraft:interaction", List.of("attack", "interaction")),
            Map.entry("minecraft:mooshroom", List.of("stew_effects")),
            Map.entry("minecraft:enderman", List.of("carriedBlockState")),
            Map.entry("minecraft:armor_stand", List.of("Pose")),
            Map.entry("minecraft:villager", List.of("Offers", "Gossips")),
            Map.entry("minecraft:wandering_trader", List.of("Offers", "wander_target")),
            Map.entry("minecraft:zombie_villager", List.of("Offers", "ConversionPlayer", "Gossips")),
            Map.entry("minecraft:falling_block", List.of("TileEntityData")),
            Map.entry("minecraft:bee", List.of("hive_pos", "flower_pos")),
            Map.entry("minecraft:creeper", List.of("powered")),
            Map.entry("minecraft:end_crystal", List.of("beam_target")),
            Map.entry("minecraft:item_frame", List.of("Item")),
            Map.entry("minecraft:glow_item_frame", List.of("Item")));
    private static final List<String> BLOCK_ENTITY = List.of("id", "x", "y", "z", "keepPacked", "components", "CustomName", "lock", "LootTable",
            "LootTableSeed", "Items", "custom_name");
    private static final Map<String, List<String>> BLOCK_ENTITY_EXTRA = Map.ofEntries(
            Map.entry("minecraft:banner", List.of("patterns")),
            Map.entry("minecraft:beacon", List.of("primary_effect", "secondary_effect")),
            Map.entry("minecraft:trial_spawner", List.of("normal_config", "ominous_config", "spawn_data", "registered_players", "current_mobs",
                    "cooldown_end_at", "next_mob_spawns_at", "total_mobs_spawned", "ejecting_loot_table", "required_player_range", "target_cooldown_length")),
            Map.entry("minecraft:vault", List.of("config", "server_data", "shared_data")),
            Map.entry("minecraft:beehive", List.of("flower_pos")),
            Map.entry("minecraft:bee_nest", List.of("flower_pos")),
            Map.entry("minecraft:lectern", List.of("Book")),
            Map.entry("minecraft:jukebox", List.of("RecordItem")),
            Map.entry("minecraft:end_gateway", List.of("exit_portal", "ExitPortal")),
            Map.entry("minecraft:command_block", List.of("LastOutput")),
            Map.entry("minecraft:skull", List.of("profile", "note_block_sound")),
            Map.entry("minecraft:decorated_pot", List.of("item")),
            Map.entry("minecraft:brushable_block", List.of("item")),
            Map.entry("minecraft:conduit", List.of("Target", "target")));
    private static final List<String> PLAYER = List.of("respawn", "entered_nether_pos", "ShoulderEntityLeft", "ShoulderEntityRight", "RootVehicle",
            "LastDeathLocation", "last_death_location", "ender_pearls");

    private KnownKeys() {}

    /** The known fields of {@code target} as data version {@code src} names them, or null when no dump is shipped for it. */
    static KnownKeys load(Version target, int src) {
        InputStream in = KnownKeys.class.getResourceAsStream("/known/" + target.name() + "/dump.txt");
        if (in == null) return null;
        KnownKeys k = new KnownKeys();
        int tdv = target.dataVersion();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                CompoundTag t;
                try {
                    t = TagParser.parseCompoundFully(p[2]);
                } catch (Exception e) {
                    continue;
                }
                try {
                switch (p[0]) {
                    case "entity" -> {
                        if (!t.contains("id")) t.putString("id", p[1]);
                        CompoundTag up = (CompoundTag) Dfu.update(References.ENTITY, t, tdv, src);
                        add(k.entities, up.getStringOr("id", p[1]), up.keySet());
                    }
                    case "block_entity" -> {
                        CompoundTag up = (CompoundTag) Dfu.update(References.BLOCK_ENTITY, t, tdv, src);
                        add(k.blockEntities, up.getStringOr("id", ""), up.keySet());
                    }
                    case "player" -> {
                        CompoundTag up = (CompoundTag) Dfu.update(References.PLAYER, t, tdv, src);
                        k.player = new HashSet<>(up.keySet());
                        k.player.addAll(ENTITY);
                        k.player.addAll(PLAYER);
                    }
                    default -> { }
                }
                } catch (RuntimeException e) {
                    // a type the newer version no longer has (a bed's block entity): nothing of it to keep
                }
            }
        } catch (Exception e) {
            System.err.println("known fields of " + target.name() + " unavailable: " + e);
            return null;
        }
        // one boat and one thrown potion were dumped; the others of their kind write the same fields
        for (var e : Steps262.BOATS.entrySet()) {
            Set<String> same = k.entities.get(e.getValue()[0].equals("minecraft:chest_boat") ? "minecraft:oak_chest_boat" : "minecraft:oak_boat");
            if (same != null) k.entities.putIfAbsent(e.getKey(), same);
        }
        Set<String> potion = k.entities.get("minecraft:splash_potion");
        if (potion != null) k.entities.putIfAbsent("minecraft:lingering_potion", potion);
        for (Set<String> s : k.entities.values()) s.addAll(ENTITY);
        ENTITY_EXTRA.forEach((id, extra) -> { if (k.entities.containsKey(id)) k.entities.get(id).addAll(extra); });
        for (Set<String> s : k.blockEntities.values()) s.addAll(BLOCK_ENTITY);
        BLOCK_ENTITY_EXTRA.forEach((id, extra) -> { if (k.blockEntities.containsKey(id)) k.blockEntities.get(id).addAll(extra); });
        return k;
    }

    private static void add(Map<String, Set<String>> m, String id, Set<String> keys) {
        m.computeIfAbsent(id, x -> new HashSet<>()).addAll(keys);
    }

    /** The fields of {@code c} (an entity, block entity or player) the older version does not know, removed; their names. */
    static List<String> strip(CompoundTag c, Set<String> known) {
        if (known == null) return List.of();
        List<String> gone = new java.util.ArrayList<>();
        for (String k : new java.util.ArrayList<>(c.keySet())) {
            if (!known.contains(k)) {
                c.remove(k);
                gone.add(k);
            }
        }
        return gone;
    }

    Set<String> entity(String id) {
        return entities.get(id);
    }

    Set<String> blockEntity(String id) {
        return blockEntities.get(id);
    }

    Set<String> player() {
        return player;
    }
}
