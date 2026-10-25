package dev.s1mp1e.worldconv;

import net.minecraft.nbt.CompoundTag;

import java.util.Set;

/**
 * Fields the game's own forward fixes rewrite into new ones but leave in place: DFU writes a fixed part of a value over
 * a copy of the whole (EquipmentFormatFix turns ArmorItems into equipment and the old list stays next to it). The
 * game never reads them again and drops them on its next save; an upgraded world should not carry them at all.
 */
final class Obsolete {
    private Obsolete() {}

    private static final Set<String> BOATS = Steps262.BOATS.keySet();

    static void entity(CompoundTag e, int target) {
        if (target >= 4293) {
            e.remove("ArmorDropChances");
            e.remove("HandDropChances");
            e.remove("body_armor_drop_chance");
        }
        if (target >= 4300) {
            e.remove("SaddleItem");
            if (Steps262.SADDLE_FLAG.contains(e.getStringOr("id", ""))) e.remove("Saddle");
        }
        if (target >= 4301) {
            e.remove("ArmorItems");
            e.remove("HandItems");
            e.remove("body_armor_item");
            e.remove("saddle");
        }
        if (target >= 4303) e.remove("FallDistance");
        if (target >= 4067 && BOATS.contains(e.getStringOr("id", ""))) e.remove("Type");
        if (target >= 4173 && e.getStringOr("id", "").equals("minecraft:tnt_minecart")) e.remove("TNTFuse");
        if (target >= 4903) {
            // 1.21 fields 26.x dropped without a fix (it keeps the anger end as a time of its own)
            e.remove("HurtByTimestamp");
            e.remove("AngerTime");
            if (e.getStringOr("id", "").equals("minecraft:allay")) e.remove("CanDuplicate");
        }
    }

    static void player(CompoundTag p, int target) {
        if (target >= 4303) p.remove("FallDistance");
        if (target >= 4903) p.remove("HurtByTimestamp");
        if (target >= 4301) {
            p.remove("ArmorItems");
            p.remove("HandItems");
        }
    }
}
