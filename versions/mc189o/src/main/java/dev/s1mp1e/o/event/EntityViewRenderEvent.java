package dev.s1mp1e.o.event;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;

/** Forge EntityViewRenderEvent 子集：只做 FOVModifier（GameRenderer.getFov 回傳前）。 */
public class EntityViewRenderEvent extends Event {
    public final GameRenderer renderer;
    public final Entity entity;
    public final double renderPartialTicks;

    public EntityViewRenderEvent(GameRenderer renderer, Entity entity, double renderPartialTicks) {
        this.renderer = renderer;
        this.entity = entity;
        this.renderPartialTicks = renderPartialTicks;
    }

    public static class FOVModifier extends EntityViewRenderEvent {
        private float fov;

        public FOVModifier(GameRenderer renderer, Entity entity, double renderPartialTicks, float fov) {
            super(renderer, entity, renderPartialTicks);
            this.fov = fov;
        }

        public float getFOV() { return fov; }

        public void setFOV(float fov) { this.fov = fov; }
    }
}
