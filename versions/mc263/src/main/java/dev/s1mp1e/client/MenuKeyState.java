package dev.s1mp1e.client;

/**
 * Shared state for the 26.3 menu key between {@code MenuKeyEventMixin} (key events) and {@code MenuKeyMixin} (tick).
 *
 * <p>{@link S1mp1eConfig#getMenuKey()} is already an SDL scan code on 26.3 (it converts the shared file's GLFW code on
 * load via {@code KeyCodes.glfwToSdl}), so everything here works in SDL codes.
 */
public final class MenuKeyState {

    private MenuKeyState() {}

    /** Set when a key-PRESS event for the menu key arrives; consumed (always) by the next tick. Event-driven so a quick
     *  tap between two state polls is never missed. */
    public static volatile boolean menuKeyEvent;

    /** SDL key code of a non-printable key: {@code SDL_SCANCODE_TO_KEYCODE(sc) = sc | (1 << 30)}. */
    public static int sdlKeycode(int sdlScancode) {
        return sdlScancode | (1 << 30);
    }
}
