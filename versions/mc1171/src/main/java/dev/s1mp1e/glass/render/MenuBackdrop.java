package dev.s1mp1e.glass.render;

/**
 * Menu-blur backdrop — replaces vanilla's tiled dirt on world-less screens with
 * the title panorama, blurred (the 1.8.9 equivalent of 26.2's native menu blur).
 *
 * <p><b>1.17.1 core-profile status: STUBBED (no-op).</b> The original
 * implementation drew a full-screen quad with immediate mode
 * ({@code glBegin(GL_QUADS)} / {@code glVertex2f} / {@code glColor4f}) inside a
 * {@code glPushAttrib}/{@code glPopAttrib} block and toggled fixed-function
 * enables ({@code GL_TEXTURE_2D}, {@code GL_ALPHA_TEST}, {@code GL_LIGHTING}) —
 * every one of those is <em>illegal</em> under 1.17.1's OpenGL 3.2 forward-
 * compatible core profile and raises {@code GL_INVALID_OPERATION}/{@code ENUM}.
 *
 * <p>The core-legal replacement is fully specified in {@code CORE_PROFILE_SPEC.md}
 * §7: draw the full-screen quad as two triangles through the shared VAO/VBO,
 * bind program {@code BLUR} ({@code menu_blur.fsh}) and set {@code Radius}/{@code
 * Dim}, driving state via {@code RenderSystem} instead of {@code glPushAttrib}.
 *
 * <p><b>Why it is still stubbed (updated — the old note here was stale).</b> The
 * core port it once waited on is now DONE: {@link GlassProgram} compiles core
 * GLSL-150 shaders, links and validates the {@code BLUR} program, and exposes the
 * {@code ProjMat}/{@code ModelViewMat} uniforms, and {@link GlassRenderer} owns a
 * shared VAO/VBO + a full-screen two-triangle quad. So the blur COULD be wired per
 * spec §7. It is deliberately NOT, because the user-approved look (the 1.20.1
 * reference) shows world-less screens — the settings page, Options — over
 * <em>vanilla's own</em> tiled background, not a blurred title panorama. Enabling
 * the menu blur here would make 1.17.1 diverge from that approved look, so this
 * line intentionally keeps parity with the Fabric references and vanilla.
 *
 * <p>This class is therefore inert stubs (signatures preserved): {@link #draw}
 * returns {@code false} so callers fall back to vanilla's background, and
 * {@link #capture}/{@link #ready} do nothing. There are NO call sites for
 * MenuBackdrop today ({@code TitleScreenBackdropCaptureMixin} only feeds the no-op
 * {@link #capture()}), so nothing regresses; the glass hotbar + capsule-button
 * paths do not touch it. To actually enable the blur later, implement §7 here AND
 * add a draw call site — but only if the approved look is changed to want it.
 */
public final class MenuBackdrop {

    /**
     * Intended tuning for the eventual core port (spec §7): blur radius in
     * physical px and how far the blurred result is darkened. Retained as the
     * contract of record; unused while the class is stubbed.
     */
    private static final float RADIUS = 14f;
    private static final float DIM    = 0.35f;

    private MenuBackdrop() {}

    /**
     * TODO(1.17.1 core, spec §7): true once a title-screen frame is captured AND
     * the BLUR program is usable. Stubbed to {@code false} — the backdrop is not
     * drawn under core profile yet, so callers must use the vanilla background.
     */
    public static boolean ready() {
        return false;
    }

    /**
     * TODO(1.17.1 core, spec §7): snapshot the panorama with
     * {@code glCopyTexSubImage2D} (that call itself IS core-legal, cf.
     * {@link SceneCapture}) so the blur has a source. No-op while stubbed,
     * because nothing consumes the capture until {@link #draw} is restored.
     */
    public static void capture() {
        // intentionally empty — stubbed for 1.17.1 core profile (spec §7)
    }

    /**
     * TODO(1.17.1 core, spec §7): draw the blurred panorama full-screen via the
     * shared VAO/VBO + the BLUR program. Stubbed to {@code false} so the caller
     * draws vanilla's dirt background instead. Draws nothing.
     *
     * @return {@code false} — backdrop not drawn under core profile yet.
     */
    public static boolean draw() {
        return false;
    }
}
