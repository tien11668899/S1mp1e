package dev.s1mp1e.glass.render;

/**
 * Cross-dissolve between GUI screens.
 *
 * <p><b>1.17.1 core-profile status: STUBBED (no-op).</b> The original
 * implementation blitted the captured outgoing frame as a full-screen quad with
 * immediate mode ({@code glBegin(GL_QUADS)} / {@code glVertex2f} /
 * {@code glColor4f}) inside a {@code glPushAttrib}/{@code glPopAttrib} block, and
 * scaled the texel by the fade alpha using the fixed-function texture combiner
 * ({@code glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE)}). All of
 * that is <em>illegal</em> under 1.17.1's OpenGL 3.2 forward-compatible core
 * profile: there is no {@code glBegin}, no {@code glPushAttrib}, and no fixed-
 * function combiner — {@code GL_MODULATE} does not exist.
 *
 * <p>The core-legal replacement is specified in {@code CORE_PROFILE_SPEC.md} §7:
 * add a minimal {@code FADE} program (shared {@code glass.vsh} + a new
 * {@code fade.fsh} that computes {@code texture(Sampler0, vLocal) * vColor}, i.e.
 * the exact {@code MODULATE} of texel × {@code (1,1,1,a)}), and draw the full-
 * screen quad as two triangles through the shared VAO/VBO with
 * {@code Color = (1,1,1,a)}. That cannot land here yet: {@link GlassProgram}
 * neither defines a {@code FADE} program nor the {@code ProjMat}/{@code
 * ModelViewMat} uniforms, still compiles GLSL-120 shaders, and {@link
 * GlassRenderer} owns no shared VAO/VBO — the whole surrounding pipeline is not
 * on core profile in this module yet.
 *
 * <p>So this class is gutted to inert stubs (signatures preserved): {@link
 * #trigger}, {@link #draw} and {@link #captureFrame} do nothing, so a screen
 * change is a hard cut with no flash. This module has NO call sites for
 * ScreenFade today, so nothing regresses; the glass hotbar + capsule-button
 * paths do not touch it. Restore the real dissolve per spec §7 once the {@code
 * FADE} program, the shared VAO/VBO and the core shaders exist.
 */
public final class ScreenFade {

    /**
     * Same 150 ms every other fade in the mod uses. Retained as public API /
     * the contract of record for the eventual core port; unused while stubbed.
     */
    public static final float FADE_MS = 150f;

    private ScreenFade() {}

    /**
     * TODO(1.17.1 core, spec §7): begin dissolving out the last captured frame.
     * No-op while stubbed — a screen change is a hard cut, no flash.
     */
    public static void trigger() {
        // intentionally empty — stubbed for 1.17.1 core profile (spec §7)
    }

    /**
     * TODO(1.17.1 core, spec §7): draw the outgoing frame over the incoming
     * screen via the FADE program + shared VAO/VBO. No-op while stubbed; draws
     * nothing.
     */
    public static void draw() {
        // intentionally empty — stubbed for 1.17.1 core profile (spec §7)
    }

    /**
     * TODO(1.17.1 core, spec §7): copy the composited frame at end-of-frame so a
     * snapshot is ready when a screen changes. The copy itself
     * ({@code glCopyTexSubImage2D}) IS core-legal (cf. {@link SceneCapture}), but
     * it is pointless until {@link #draw} can consume it, so it is a no-op here.
     */
    public static void captureFrame() {
        // intentionally empty — stubbed for 1.17.1 core profile (spec §7)
    }
}
