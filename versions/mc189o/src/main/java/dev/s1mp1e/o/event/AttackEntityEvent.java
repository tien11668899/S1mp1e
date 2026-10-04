package dev.s1mp1e.o.event;

import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;

/** PlayerEntity.attack 開頭發送（Forge 的 onPlayerAttackTarget），可取消。 */
public class AttackEntityEvent extends Event {
    public final PlayerEntity entityPlayer;
    public final Entity target;

    public AttackEntityEvent(PlayerEntity player, Entity target) {
        this.entityPlayer = player;
        this.target = target;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
