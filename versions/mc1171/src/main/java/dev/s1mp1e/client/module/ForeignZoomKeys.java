package dev.s1mp1e.client.module;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a foreign key mapping is another mod's CAMERA (FOV) zoom key, for {@link ZoomModule}'s
 * "Block other zoom" option. Pure string logic on the mapping's translation key plus its category id, with no
 * Minecraft types, so this one file is shared unchanged by 26.2 / 1.21.1 / 1.20.1. The key-mapping mixin
 * runs it at most ONCE per mapping (the result is cached on the mapping), and only on the first real press
 * while blocking is on.
 *
 * <p>Order of decisions (first match wins):
 * <ol>
 *   <li>our own mappings ({@code s1mp1e}) → never blocked;</li>
 *   <li>{@link #NEVER}: exact keys that contain "zoom" but zoom a MAP (Xaero, JourneyMap, VoxelMap) → never;</li>
 *   <li>{@link #KNOWN_CAMERA_ZOOM}: exact camera-zoom keys → blocked;</li>
 *   <li>non-camera hints in the key OR category (maps, replay/timeline editors, third-person distance, GUI/chat
 *       zoom) → never;</li>
 *   <li>a stand-alone "zoom" word in the key itself (not the category, not the mod id: {@code zoomify.key.gui}
 *       is NOT a zoom key) → blocked.</li>
 * </ol>
 * Unknown / ambiguous keys fall through to "not blocked": a false negative just leaves that mod's zoom
 * working, a false positive would silently break an unrelated key, so every tie goes to "don't block".
 */
public final class ForeignZoomKeys {

    private ForeignZoomKeys() {}

    /** Verified from the mods' jars (Essential 1.4.0.3/1.4.1.1, Zoomify 2.15.2/2.16.1, OK Zoomer 10.0.0-beta.13);
     *  the last two (WI Zoom, Logical Zoom) are from memory. Case-sensitive, exactly as the mods build them. */
    private static final Set<String> KNOWN_CAMERA_ZOOM = set(
            "keybind.name.ZOOM",                                   // Essential: "keybind.name." + "ZOOM", default C
            "zoomify.key.zoom", "zoomify.key.zoom.secondary",      // Zoomify: C / F6
            "zoomify.key.zoom.in", "zoomify.key.zoom.out",         // Zoomify scroll-zoom keys (unbound)
            "key.ok_zoomer.zoom", "key.ok_zoomer.decrease_zoom",   // OK Zoomer: C / unbound
            "key.ok_zoomer.increase_zoom", "key.ok_zoomer.reset_zoom",
            "key.wi_zoom.zoom",                                    // WI Zoom (from memory)
            "key.logical_zoom.zoom");                              // Logical Zoom (from memory)

    /** Keys that contain "zoom" but zoom a map, never the camera. Verified: Xaero minimap / world map;
     *  from memory: JourneyMap, VoxelMap ({@code key.minimap.zoom}, lang value just "Zoom"). */
    private static final Set<String> NEVER = set(
            "gui.xaero_zoom_in", "gui.xaero_zoom_out",
            "gui.xaero_map_zoom_in", "gui.xaero_map_zoom_out",
            "key.journeymap.zoom_in", "key.journeymap.zoom_out",
            "key.minimap.zoom");

    /** Lower-case substrings, checked in key AND category, that mark a non-camera zoom domain. Glued words
     *  like minimap / worldmap / journeymap / voxelmap are why "map" is a substring test, not a word test. */
    private static final String[] NON_CAMERA_SUBSTRINGS = {
            "map", "xaero", "atlas", "replay", "flashback", "thirdperson", "third_person", "shouldersurfing"
    };

    /** Lower-case whole words (split on non-alphanumerics), checked in key AND category. Word tests so that
     *  short words ("gui", "hud") don't match inside unrelated ids. */
    private static final Set<String> NON_CAMERA_WORDS = set(
            "gui", "screen", "menu", "chat", "hud", "timeline", "editor", "keyframe", "distance",
            "inventory", "recipe", "book", "schematic");

    /** Words in the key that mean "this is a zoom key". Lower-cased, so camelCase {@code zoomIn} is "zoomin". */
    private static final Set<String> ZOOM_WORDS = set(
            "zoom", "zoomin", "zoomout", "zoomkey", "zoomtoggle", "togglezoom", "holdzoom", "zoomhold", "zooming");

    /**
     * @param name     the mapping's translation key (26.2 {@code KeyMapping.getName()}, yarn
     *                 {@code KeyBinding.getTranslationKey()})
     * @param category the category id as text (26.2 {@code "essential:general"}, yarn {@code "Essential"} or
     *                 {@code "key.category.ok_zoomer.zoom"}); may be null
     * @return true only for another mod's camera-zoom key
     */
    public static boolean isCameraZoom(String name, String category) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);

        if (n.contains("s1mp1e") || c.contains("s1mp1e")) return false;   // never our own
        if (NEVER.contains(name)) return false;
        if (KNOWN_CAMERA_ZOOM.contains(name)) return true;

        for (int i = 0; i < NON_CAMERA_SUBSTRINGS.length; i++) {
            String s = NON_CAMERA_SUBSTRINGS[i];
            if (n.contains(s) || c.contains(s)) return false;
        }
        String[] nameWords = n.split("[^a-z0-9]+");
        if (anyIn(nameWords, NON_CAMERA_WORDS) || anyIn(c.split("[^a-z0-9]+"), NON_CAMERA_WORDS)) return false;
        return anyIn(nameWords, ZOOM_WORDS);
    }

    private static boolean anyIn(String[] words, Set<String> set) {
        for (int i = 0; i < words.length; i++) {
            if (set.contains(words[i])) return true;
        }
        return false;
    }

    private static Set<String> set(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }
}
