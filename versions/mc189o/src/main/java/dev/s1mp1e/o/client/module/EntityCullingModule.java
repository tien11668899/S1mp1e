package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;

/**
 * Entity culling: entities fully hidden behind opaque blocks are not drawn (their name tags still are). A frame-rate
 * feature only — what is hidden is behind a wall either way. The work is in {@link dev.s1mp1e.o.cull.EntityCull}.
 */
public final class EntityCullingModule extends Module {

    private static EntityCullingModule instance;

    public EntityCullingModule() {
        super("EntityCulling", "Visual");
        this.enabled = true;
        instance = this;
    }

    public static boolean on() {
        EntityCullingModule m = instance;
        return m != null && m.enabled;
    }
}
