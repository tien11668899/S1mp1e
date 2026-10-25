package dev.s1mp1e.client.gui;

/**
 * Shared flag: while a {@code TabNavigationWidget} is drawing itself as ONE segmented glass control (a single capsule with a
 * sliding selection pill, like the S1mp1e config menu's 戰鬥 / HUD / 視覺), the per-tab glass ({@code TabButtonGlassMixin})
 * must not draw its own capsule — the pill is the selection. A plain class (not a mixin) so both can reference it.
 */
public final class SegmentedTabs {
   private SegmentedTabs() {}

   /** Set by the segmented tab bar for the span of its render; read by the per-tab glass. Render thread only. */
   public static boolean active;
}
