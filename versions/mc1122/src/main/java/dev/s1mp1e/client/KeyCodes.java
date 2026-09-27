package dev.s1mp1e.client;

import org.lwjgl.input.Keyboard;

/**
 * Translates a GLFW key code (the number every other S1mp1e version stores in
 * {@code modules.json} and shows in the "Key (GLFW)" setting) into the LWJGL2
 * key code that 1.8.9's {@link org.lwjgl.input.Keyboard} understands.
 *
 * <p>Keeping the stored/displayed number as GLFW means the config schema and the
 * value the user sees are identical across all versions; the LWJGL2 code only ever
 * exists at read time, right before {@code Keyboard.isKeyDown}. Callers (ZoomModule,
 * InventoryHUD, …) translate through {@link #glfwToLwjgl(int)} each frame.
 *
 * <p>Letters go through {@link Keyboard#getKeyIndex} (so a keyboard layout with a
 * moved key still resolves), digits and the named keys use an explicit table taken
 * from the LWJGL2 constants. Any code with no LWJGL2 equivalent maps to 0
 * ({@code KEY_NONE}), i.e. "unbound", so an unknown key is simply never down.
 */
public final class KeyCodes {

    private KeyCodes() {}

    /**
     * @param glfw a GLFW key code (e.g. 67 for 'C', 344 for right shift)
     * @return the matching LWJGL2 {@code Keyboard.KEY_*} code, or 0 if none
     */
    public static int glfwToLwjgl(int glfw) {
        // Letters: GLFW 'A'..'Z' are 65..90 (upper-case identity); accept lower-case too.
        if (glfw >= 'A' && glfw <= 'Z') {
            return safeIndex(String.valueOf((char) glfw));
        }
        if (glfw >= 'a' && glfw <= 'z') {
            return safeIndex(String.valueOf((char) (glfw - 32)));
        }
        // Digits: GLFW '0'..'9' are 48..57. LWJGL2 row is KEY_1=2 .. KEY_9=10, KEY_0=11.
        if (glfw >= '0' && glfw <= '9') {
            return glfw == '0' ? 11 : (glfw - '0' + 1);
        }
        // F-keys: GLFW F1..F12 are 290..301. LWJGL2 F1..F10 = 59..68, F11 = 87, F12 = 88.
        if (glfw >= 290 && glfw <= 301) {
            int n = glfw - 290;            // 0..11
            if (n <= 9) return 59 + n;     // F1..F10 -> 59..68
            return n == 10 ? 87 : 88;      // F11, F12
        }
        // Numpad: GLFW KP_0..KP_9 are 320..329. LWJGL2: KP0=82, KP1..KP9 = 79,80,81,75,76,77,71,72,73.
        if (glfw >= 320 && glfw <= 329) {
            switch (glfw) {
                case 320: return 82;  // KP0
                case 321: return 79;  // KP1
                case 322: return 80;  // KP2
                case 323: return 81;  // KP3
                case 324: return 75;  // KP4
                case 325: return 76;  // KP5
                case 326: return 77;  // KP6
                case 327: return 71;  // KP7
                case 328: return 72;  // KP8
                case 329: return 73;  // KP9
                default:  return 0;
            }
        }
        switch (glfw) {
            case 258: return 15;   // TAB
            case 32:  return 57;   // SPACE
            case 256: return 1;    // ESCAPE
            case 257: return 28;   // ENTER / RETURN
            case 259: return 14;   // BACKSPACE
            case 340: return 42;   // LEFT SHIFT
            case 344: return 54;   // RIGHT SHIFT
            case 341: return 29;   // LEFT CONTROL
            case 345: return 157;  // RIGHT CONTROL
            case 342: return 56;   // LEFT ALT
            case 346: return 184;  // RIGHT ALT
            case 262: return 205;  // RIGHT
            case 263: return 203;  // LEFT
            case 264: return 208;  // DOWN
            case 265: return 200;  // UP
            case 260: return 210;  // INSERT
            case 261: return 211;  // DELETE
            case 266: return 201;  // PAGE UP    (LWJGL2 KEY_PRIOR)
            case 267: return 209;  // PAGE DOWN  (LWJGL2 KEY_NEXT)
            case 268: return 199;  // HOME
            case 269: return 207;  // END
            case 96:  return 41;   // GRAVE / `
            case 45:  return 12;   // MINUS / -
            case 61:  return 13;   // EQUAL / =
            case 91:  return 26;   // LEFT BRACKET / [
            case 93:  return 27;   // RIGHT BRACKET / ]
            case 92:  return 43;   // BACKSLASH / \
            case 59:  return 39;   // SEMICOLON / ;
            case 39:  return 40;   // APOSTROPHE / '
            case 44:  return 51;   // COMMA / ,
            case 46:  return 52;   // PERIOD / .
            case 47:  return 53;   // SLASH / /
            default:  return 0;    // unmapped -> unbound
        }
    }

    /** Letter lookup guarded so a null/exception from LWJGL never propagates. */
    private static int safeIndex(String name) {
        try {
            int idx = Keyboard.getKeyIndex(name);
            return idx == Keyboard.KEY_NONE ? 0 : idx;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The exact inverse of {@link #glfwToLwjgl(int)}: turns a 1.8.9 LWJGL2
     * {@code Keyboard.KEY_*} code into the GLFW code the shared cross-version
     * {@code modules.json} stores.
     *
     * <p>Only needed on the write side: 1.8.9 keeps LWJGL semantics in memory
     * (that is what {@code Keyboard.isKeyDown} wants) but the shared file is in
     * GLFW code space because 1.20.1/1.21.1/26.2 write it that way.
     *
     * @param lwjgl an LWJGL2 key code (e.g. 54 for right shift)
     * @return the matching GLFW key code, or 0 when there is no equivalent
     */
    public static int lwjglToGlfw(int lwjgl) {
        switch (lwjgl) {
            // named keys — inverse of the switch in glfwToLwjgl
            case 15:  return 258;  // TAB
            case 57:  return 32;   // SPACE
            case 1:   return 256;  // ESCAPE
            case 28:  return 257;  // ENTER / RETURN
            case 14:  return 259;  // BACKSPACE
            case 42:  return 340;  // LEFT SHIFT
            case 54:  return 344;  // RIGHT SHIFT
            case 29:  return 341;  // LEFT CONTROL
            case 157: return 345;  // RIGHT CONTROL
            case 56:  return 342;  // LEFT ALT
            case 184: return 346;  // RIGHT ALT
            case 205: return 262;  // RIGHT
            case 203: return 263;  // LEFT
            case 208: return 264;  // DOWN
            case 200: return 265;  // UP
            case 210: return 260;  // INSERT
            case 211: return 261;  // DELETE
            case 201: return 266;  // PAGE UP    (LWJGL2 KEY_PRIOR)
            case 209: return 267;  // PAGE DOWN  (LWJGL2 KEY_NEXT)
            case 199: return 268;  // HOME
            case 207: return 269;  // END
            case 41:  return 96;   // GRAVE / `
            case 12:  return 45;   // MINUS / -
            case 13:  return 61;   // EQUAL / =
            case 26:  return 91;   // LEFT BRACKET / [
            case 27:  return 93;   // RIGHT BRACKET / ]
            case 43:  return 92;   // BACKSLASH / \
            case 39:  return 59;   // SEMICOLON / ;
            case 40:  return 39;   // APOSTROPHE / '
            case 51:  return 44;   // COMMA / ,
            case 52:  return 46;   // PERIOD / .
            case 53:  return 47;   // SLASH / /
            // numpad — LWJGL2 KP0=82, KP1..KP9 = 79,80,81,75,76,77,71,72,73
            case 82:  return 320;
            case 79:  return 321;
            case 80:  return 322;
            case 81:  return 323;
            case 75:  return 324;
            case 76:  return 325;
            case 77:  return 326;
            case 71:  return 327;
            case 72:  return 328;
            case 73:  return 329;
            // F11 / F12 sit outside the contiguous F1..F10 block
            case 87:  return 300;
            case 88:  return 301;
            default:  break;
        }
        // digits: LWJGL2 KEY_1=2 .. KEY_9=10, KEY_0=11 -> GLFW '1'..'9', '0'
        if (lwjgl >= 2 && lwjgl <= 10) return '0' + (lwjgl - 1);
        if (lwjgl == 11) return '0';
        // F1..F10: LWJGL2 59..68 -> GLFW 290..299
        if (lwjgl >= 59 && lwjgl <= 68) return 290 + (lwjgl - 59);
        // letters: resolved by name so this stays the exact inverse of safeIndex()
        return safeLetter(lwjgl);
    }

    /** LWJGL key index -> GLFW code for single-letter keys; 0 for anything else. */
    private static int safeLetter(int lwjgl) {
        try {
            String name = Keyboard.getKeyName(lwjgl);
            if (name != null && name.length() == 1) {
                char c = Character.toUpperCase(name.charAt(0));
                if (c >= 'A' && c <= 'Z') return c;   // GLFW letters are upper-case ASCII
            }
        } catch (Throwable ignored) {
            // unknown index — fall through to "unmapped"
        }
        return 0;
    }
}
