package dev.s1mp1e.client.gui;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GuiFlush;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * A scoped opacity for arbitrary GUI drawing: everything drawn between {@link #push} and {@link #pop} (text, fills,
 * texture blits, item models) has its alpha multiplied by the pushed value. Pushes nest (multiply). Render thread
 * only.
 *
 * <p>The 1.15.2 counterpart of the newer lines' {@code GuiAlpha}. Those multiply the shader colour modulator
 * ({@code RenderSystem.setShaderColor}); 1.15.2 is the fixed-function pipeline, where the equivalent "current colour"
 * ({@code glColor}) is NOT a choke point — text and fills carry their colour per vertex, which replaces it. What every
 * fixed-function fragment does go through is the texture-environment chain, so the scope is one extra stage at its
 * end:
 *
 * <pre>
 *   texture unit 3 (vanilla uses 0 = the texture, 1 = the entity overlay, 2 = the lightmap; 3 is never touched):
 *     a 1x1 white texture, enabled, GL_COMBINE
 *       RGB   = PREVIOUS                (colour passes through)
 *       ALPHA = PREVIOUS * CONSTANT     (constant = TEXTURE_ENV_COLOR.a = the scope's opacity)
 * </pre>
 *
 * It applies to textured and untextured draws alike, whatever vertex format, colour array or {@code glColor} vanilla
 * uses, and survives every {@code RenderSystem.color4f(1, 1, 1, 1)} vanilla issues half-way through an element — so
 * the {@code ShaderColorScopeMixin} the core-profile lines need does not exist here. The raw-GL glass programs are
 * shaders (no texture environment) and take their opacity as an argument, as on every line. All state is set with raw
 * GL on unit 3 and the active unit is put back, so {@code RenderSystem}'s cache (which tracks units 0..2 as vanilla
 * uses them) never notices. If the driver has fewer than four fixed-function texture units the scope is a no-op
 * (things then appear without the fade).
 *
 * <p>Note the alpha test: vanilla GUI layers discard fragments with alpha &lt;= 0.1, so a faded element becomes
 * visible once {@code texel alpha * scope} passes 0.1 — a few milliseconds into a 150 ms fade.
 */
public final class GuiAlpha {
    private GuiAlpha() {}

    private static final float[] STACK = new float[32];
    private static int depth;
    private static float current = 1.0f;

    /** The texture unit of the scope stage; 0 = not resolved yet, -1 = unavailable. */
    private static int unit;
    private static int whiteTex;
    private static boolean stageOn;
    private static final FloatBuffer ENV = BufferUtils.createFloatBuffer(4);

    public static void push(float alpha) {
        GuiFlush.flush();
        if (depth < STACK.length) STACK[depth] = current;
        depth++;
        current *= Math.max(0.0f, Math.min(1.0f, alpha));
        RenderSystem.enableBlend();
        apply();
    }

    public static void pop() {
        GuiFlush.flush();
        if (depth <= 0) { current = 1.0f; depth = 0; }
        else {
            depth--;
            current = depth < STACK.length ? STACK[depth] : current;
            if (depth == 0) current = 1.0f;
        }
        apply();
    }

    /** Safety net: drop anything left pushed by an interrupted draw. */
    public static void reset() {
        if (depth != 0 || current != 1.0f || stageOn) {
            depth = 0;
            current = 1.0f;
            apply();
        }
    }

    public static float current() { return current; }

    /** True while at least one scope is open. */
    public static boolean active() { return depth > 0; }

    /**
     * Source compatibility with the newer lines (there: "set the shader colour, bypassing the scope"). Here the
     * scope does not live in the colour at all, so this is a plain colour set.
     */
    public static void setRaw(float r, float g, float b, float a) {
        RenderSystem.color4f(r, g, b, a);
    }

    /** Source compatibility with the newer lines: the scope is not part of the colour here. */
    public static float scoped(float alpha) {
        return alpha;
    }

    private static void apply() {
        if (unit == 0) {
            int max = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
            unit = max >= 4 ? GL13.GL_TEXTURE3 : -1;
            if (unit < 0) System.out.println("[S1mp1e] GuiAlpha: only " + max + " fixed-function texture units, fades disabled");
        }
        if (unit < 0) return;
        boolean on = depth > 0 && current < 0.999f;
        if (!on && !stageOn) return;
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(unit);
        if (on) {
            if (whiteTex == 0) {
                whiteTex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex);
                ByteBuffer px = BufferUtils.createByteBuffer(4);
                px.put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1).flip();
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            } else {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTex);
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_REPLACE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_PREVIOUS);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL13.GL_PREVIOUS);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_ALPHA, GL13.GL_CONSTANT);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND1_ALPHA, GL11.GL_SRC_ALPHA);
            ENV.clear();
            ENV.put(1f).put(1f).put(1f).put(current).flip();
            GL11.glTexEnvfv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ENV);
        } else {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        stageOn = on;
        GL13.glActiveTexture(prevActive);
    }
}
