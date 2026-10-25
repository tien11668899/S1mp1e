package dev.s1mp1e.client;

import java.util.List;

/**
 * A module that exposes SEVERAL independently-draggable {@link HudBounds} sub-elements instead of one
 * whole-module box — e.g. {@code KeystrokesHudModule} returns one per key so each key can be positioned
 * separately in the same HUD editor. The editor prefers this over a module's own {@link HudBounds}.
 */
public interface HudBoundsProvider {
    /** The draggable sub-elements to show in the HUD editor (only the currently-visible ones). */
    List<HudBounds> hudBoundsElements();
}
