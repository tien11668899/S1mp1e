package dev.s1mp1e.o.cull;

/** Per-entity occlusion state, added to every Entity by {@code EntityCullStateMixin} (no side map, no boxing). */
public interface CullState {
    /** Frame number of the last occlusion test; 0 = never tested. */
    long s1mp1e$cullTested();

    void s1mp1e$setCullTested(long frame);

    /** True when the last tests found every ray to the entity blocked. */
    boolean s1mp1e$culled();

    void s1mp1e$setCulled(boolean culled);

    /** {@code System.nanoTime()} of the last test that found a clear ray. */
    long s1mp1e$seenAt();

    void s1mp1e$setSeenAt(long ns);
}
