package dev.s1mp1e.client.gui;

/**
 * Tiny duck interface the list-screen glass mixins (creative / stonecutter / loom / merchant) implement so dev tooling
 * ({@code DevShotVerify}'s glide / clicks sweeps) can ask whether the list content is mid-glide this frame (feature D)
 * without reflecting into mixin-merged fields. No behaviour; never called in a normal run.
 */
public interface GlideProbe {
    /** True while the list content is drawn by the eased sub-pixel glide overlay (not at its row-aligned rest). */
    boolean s1mp1e$probeGliding();

    /** The eased content offset in GUI px this frame (0 = top); equals the row-aligned rest offset when not gliding. */
    float s1mp1e$probeOffsetPx();

    /** The glass thumb's lens morph 0 (white pill) .. 1 (refracting lens). */
    float s1mp1e$probeLift();
}
