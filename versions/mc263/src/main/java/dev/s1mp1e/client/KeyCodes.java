package dev.s1mp1e.client;

/**
 * GLFW key codes ↔ SDL scancodes. 26.3 reads keys through SDL, every other version (and the launcher) through GLFW; the
 * shared {@code s1mp1e-mods/modules.json} is written by all of them, so it keeps GLFW codes and 26.3 converts on the way in
 * and out. Without this the launcher's "Right Shift" (GLFW 344) meant nothing to 26.3 and the menu key did not open the menu.
 */
public final class KeyCodes {
    private KeyCodes() {}

    private static final int[][] PAIRS = buildPairs();

    private static int[][] buildPairs() {
        java.util.ArrayList<int[]> p = new java.util.ArrayList<>();
        for (int i = 0; i < 26; i++) p.add(new int[] {65 + i, 4 + i});          // A..Z
        for (int i = 1; i <= 9; i++) p.add(new int[] {48 + i, 29 + i});         // 1..9
        p.add(new int[] {48, 39});                                               // 0
        for (int i = 0; i < 12; i++) p.add(new int[] {290 + i, 58 + i});        // F1..F12
        for (int i = 0; i < 12; i++) p.add(new int[] {302 + i, 104 + i});       // F13..F24
        int[][] fixed = {
            {32, 44}, {39, 52}, {44, 54}, {45, 45}, {46, 55}, {47, 56}, {59, 51}, {61, 46},
            {91, 47}, {92, 49}, {93, 48}, {96, 53},
            {256, 41}, {257, 40}, {258, 43}, {259, 42}, {260, 73}, {261, 76},
            {262, 79}, {263, 80}, {264, 81}, {265, 82}, {266, 75}, {267, 78}, {268, 74}, {269, 77},
            {280, 57}, {281, 71}, {282, 83}, {283, 70}, {284, 72},
            {320, 98}, {321, 89}, {322, 90}, {323, 91}, {324, 92}, {325, 93}, {326, 94}, {327, 95}, {328, 96}, {329, 97},
            {330, 99}, {331, 84}, {332, 85}, {333, 86}, {334, 87}, {335, 88}, {336, 103},
            {340, 225}, {341, 224}, {342, 226}, {343, 227}, {344, 229}, {345, 228}, {346, 230}, {347, 231}, {348, 101},
        };
        for (int[] f : fixed) p.add(f);
        return p.toArray(new int[0][]);
    }

    /** A GLFW key code from the shared config → this version's SDL scancode (unknown codes pass through). */
    public static int glfwToSdl(int glfw) {
        for (int[] p : PAIRS) if (p[0] == glfw) return p[1];
        return glfw;
    }

    /**
     * 26.3 SDL mouse button (left 1, middle 2, right 3) → the GLFW numbering (left 0, right 1, middle 2) our shared widget
     * code compares against. Without it every "btn != 0 → not a left click" check rejected real left clicks on 26.3.
     */
    public static int legacyButton(int sdl) {
        return sdl == 1 ? 0 : sdl == 3 ? 1 : sdl == 2 ? 2 : sdl;
    }

    /** This version's SDL scancode → the GLFW code the shared config keeps (unknown codes pass through). */
    public static int sdlToGlfw(int sdl) {
        for (int[] p : PAIRS) if (p[1] == sdl) return p[0];
        return sdl;
    }
}
