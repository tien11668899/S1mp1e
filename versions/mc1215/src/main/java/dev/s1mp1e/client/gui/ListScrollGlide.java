package dev.s1mp1e.client.gui;

/**
 * The smooth-scroll half of {@code ListMotionMixin}, reachable from {@code ScrollableWidgetGlideMixin}. 1.21.2 moved
 * the scroll amount, its setter and the wheel handler from {@code EntryListWidget} up into {@code ScrollableWidget}, so
 * the wheel and "someone else set the scroll" hooks now sit on the superclass and hand lists over through this.
 */
public interface ListScrollGlide {
    /** Aim the list's scroll at {@code requested} (the amount vanilla would have set) instead of jumping there. */
    void s1mp1e$glideTo(double requested);

    /** A {@code setScrollY} is about to run; unless it is the glide's own step, it cancels the glide. */
    void s1mp1e$beforeSet();
}
