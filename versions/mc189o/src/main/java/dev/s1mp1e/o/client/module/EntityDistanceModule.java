package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.living.ArmorStandEntity;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.living.player.PlayerEntity;

/**
 * Entity render distance per kind: armour stands (lobby holograms), dropped items and XP orbs, item frames, mobs and
 * players. Past its limit an entity is not drawn at all, name tag included; 0 = no limit. Mobs and players default to
 * no limit, so nothing a fight depends on disappears unless the player asks for it. Applied by
 * {@link dev.s1mp1e.o.cull.EntityCull}.
 */
public final class EntityDistanceModule extends Module {

    public final Setting armorStands = add(Setting.integer("Armor stands", 48, 0, 256));
    public final Setting items       = add(Setting.integer("Items and XP", 48, 0, 256));
    public final Setting itemFrames  = add(Setting.integer("Item frames", 48, 0, 256));
    public final Setting mobs        = add(Setting.integer("Mobs", 0, 0, 256));
    public final Setting players     = add(Setting.integer("Players", 0, 0, 256));

    private static EntityDistanceModule instance;

    public EntityDistanceModule() {
        super("EntityDistance", "Visual");
        this.enabled = true;
        instance = this;
    }

    public static boolean on() {
        EntityDistanceModule m = instance;
        return m != null && m.enabled;
    }

    /** The limit in blocks for {@code e}'s kind; 0 = none. */
    public static int limitFor(Entity e) {
        EntityDistanceModule m = instance;
        if (m == null || !m.enabled) return 0;
        if (e instanceof ArmorStandEntity) return m.armorStands.intValue;
        if (e instanceof ItemEntity || e instanceof ExperienceOrbEntity) return m.items.intValue;
        if (e instanceof ItemFrameEntity) return m.itemFrames.intValue;
        if (e instanceof PlayerEntity) return m.players.intValue;
        if (e instanceof LivingEntity) return m.mobs.intValue;
        return 0;
    }
}
