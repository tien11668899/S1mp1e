package dev.s1mp1e.client.gui;

/**
 * Implemented by every {@code AbstractScrollArea} (lists, scrolling text areas, scrollable option layouts) through
 * {@code ScrollAreaSmoothMixin}: the mouse wheel and keyboard scrolling set a target that the real scroll amount eases
 * toward, one step per rendered frame ({@code AbstractWidgetScrollStepMixin} calls {@link #liquidglass$stepScroll}).
 */
public interface SmoothScrollHost {
   /** Ease the scroll amount one frame toward the pending target (no-op when there is none). */
   void liquidglass$stepScroll();

   /** Glide to {@code requested} (a scroll amount computed from the CURRENT position) instead of jumping there. */
   void liquidglass$glideTo(double requested);
}
