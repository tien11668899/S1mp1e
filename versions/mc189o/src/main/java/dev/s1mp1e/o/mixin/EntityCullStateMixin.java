package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.cull.CullState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives every entity the few fields {@link dev.s1mp1e.o.cull.EntityCull} keeps per entity. */
@Mixin(Entity.class)
public abstract class EntityCullStateMixin implements CullState {

    @Unique private long s1mp1e$cullTested;
    @Unique private boolean s1mp1e$culled;
    @Unique private long s1mp1e$seenAt;

    @Override public long s1mp1e$cullTested() { return s1mp1e$cullTested; }
    @Override public void s1mp1e$setCullTested(long frame) { s1mp1e$cullTested = frame; }
    @Override public boolean s1mp1e$culled() { return s1mp1e$culled; }
    @Override public void s1mp1e$setCulled(boolean culled) { s1mp1e$culled = culled; }
    @Override public long s1mp1e$seenAt() { return s1mp1e$seenAt; }
    @Override public void s1mp1e$setSeenAt(long ns) { s1mp1e$seenAt = ns; }
}
