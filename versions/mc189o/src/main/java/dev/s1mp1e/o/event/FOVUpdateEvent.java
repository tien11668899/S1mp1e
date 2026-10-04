package dev.s1mp1e.o.event;

import net.minecraft.entity.living.player.PlayerEntity;

/** ClientPlayerEntity.getFovMultiplier 回傳前發送；監聽者改 {@link #newfov}。 */
public class FOVUpdateEvent extends Event {
    public final PlayerEntity entity;
    public final float fov;
    public float newfov;

    public FOVUpdateEvent(PlayerEntity entity, float fov) {
        this.entity = entity;
        this.fov = fov;
        this.newfov = fov;
    }
}
