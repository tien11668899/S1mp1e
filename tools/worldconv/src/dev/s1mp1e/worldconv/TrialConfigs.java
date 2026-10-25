package dev.s1mp1e.worldconv;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;

/**
 * The vanilla trial spawner configs 4061 moved into a registry, read from the game's own forward fix
 * (TrialSpawnerConfigInRegistryFix.VanillaTrialChambers.CONFIGS_TO_KEY) so the way down writes them inline again.
 */
final class TrialConfigs {
    private TrialConfigs() {}

    private static Map<String, Pair<Tag, Tag>> byKey;

    /** The inline {normal, ominous} configs for registry key {@code normalKey} ("minecraft:trial_chamber/breeze/normal"), or null. */
    static synchronized Pair<Tag, Tag> inline(String normalKey) {
        if (byKey == null) byKey = load();
        return byKey.get(normalKey);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Pair<Tag, Tag>> load() {
        Map<String, Pair<Tag, Tag>> out = new HashMap<>();
        try {
            Class<?> c = Class.forName("net.minecraft.util.datafix.fixes.TrialSpawnerConfigInRegistryFix$VanillaTrialChambers");
            var f = c.getDeclaredField("CONFIGS_TO_KEY");
            f.setAccessible(true);
            Map<Pair<Dynamic<Tag>, Dynamic<Tag>>, Object> m = (Map<Pair<Dynamic<Tag>, Dynamic<Tag>>, Object>) f.get(null);
            for (var e : m.entrySet()) {
                String key = e.getValue() + "/normal";
                Tag normal = e.getKey().getFirst().getValue(), ominous = e.getKey().getSecond().getValue();
                // three entries per spawner (ominous as written, merged with normal, merged without defaults): the
                // complete merged one is what the older game needs to spawn the same mobs
                Pair<Tag, Tag> have = out.get(key);
                if (have == null || size(ominous) > size(have.getSecond())) out.put(key, Pair.of(normal, ominous));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("trial spawner configs unavailable: " + e);
        }
        return out;
    }

    private static int size(Tag t) {
        return t instanceof CompoundTag c ? c.size() : 0;
    }
}
