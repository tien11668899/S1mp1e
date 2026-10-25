package dev.s1mp1e.client.gui;

/** Render-thread flag: a menu tab bar is being drawn as one segmented glass capsule (SegmentedTabBarMixin), so the
 *  per-tab capsules (TabGlassMixin) step back to a hover glow only. */
public final class SegmentedTabs {
    private SegmentedTabs() {}

    public static boolean active;
}
