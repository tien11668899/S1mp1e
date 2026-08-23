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
 * That path cannot land here yet because it depends on the rest of the core port
 * that is NOT done in this module: {@link GlassProgram} still compiles GLSL-120
 * shaders (so {@code BLUR} won't link on a core context) and does not yet expose
 * the {@code ProjMat}/{@code ModelViewMat} uniforms, and {@link GlassRenderer}
 * still owns no shared VAO/VBO. Wiring a half-ported blur here would only produce
 * GL errors.
 *
 * <p>So this class is gutted to inert stubs (signatures preserved): {@link #draw}
 * returns {@code false} so callers fall back to vanilla's dirt background, and
 * {@link #capture}/{@link #ready} do nothing. This module has NO call sites for
 * MenuBackdrop today, so nothing regresses; the glass hotbar + capsule-button
 * paths do not touch it. Restore the real behaviour per spec §7 once
 * {@link GlassProgram}/{@link GlassRenderer}/the shaders are on core profile.
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
