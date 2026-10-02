package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GuiFlush;
import net.minecraft.client.util.math.MatrixStack;

/**
 * A scoped opacity for arbitrary GUI drawing: everything drawn between {@link #push} and {@link #pop} (text, fills,
 * texture blits) has its alpha multiplied by the pushed value. Pushes nest (multiply). Render thread only.
 *
 * <p>The 1.18.2 counterpart of 26.2's {@code GuiAlpha} + {@code GuiAlphaRenderStateMixin}. 26.2 multiplied the colour
 * arguments of the deferred GUI render-state constructors; this line has no such choke point, but every GUI shader
 * here multiplies by {@code ColorModulator} ({@code RenderSystem.setShaderColor}), which is read when a draw is issued
 * — and 1.18.2 GUI draws are immediate.
 *
 * <p><b>1.18.2 difference to the 1.20.1 / 1.21.1 lines.</b> There, blits go through {@code DrawContext}, which never
 * touches the shader colour. Here vanilla code sets it by hand before nearly every textured blit
 * ({@code RenderSystem.setShaderColor(1, 1, 1, 1)} — list entry icons, widget sprites, the tab list's ping bars …),
 * which would silently end the scope half-way through an element. So while a scope is open,
 * {@code ShaderColorScopeMixin} routes every {@code setShaderColor} through {@link #scoped}: whatever alpha vanilla
 * asks for is multiplied by the scope. Code that re-applies a colour it READ from {@code RenderSystem.getShaderColor()}
 * (which already contains the scope) must use {@link #setRaw} instead, or the scope would be applied twice.
 * Items (3D models, which bring their own lighting) and the raw-GL glass programs are not affected.
 */
public final class GuiAlpha {
    private GuiAlpha() {}

    private static final float[] STACK = new float[32];
    private static int depth;
    private static float current = 1.0f;
    /** True while this class sets the modulator itself: {@link #scoped} must then leave the value alone. */
    private static boolean raw;

    public static void push(MatrixStack matrices, float alpha) {
        GuiFlush.flush();
        if (depth < STACK.length) STACK[depth] = current;
        depth++;
        current *= Math.max(0.0f, Math.min(1.0f, alpha));
        RenderSystem.enableBlend();
        setRaw(1f, 1f, 1f, current);
    }

    public static void pop(MatrixStack matrices) {
        GuiFlush.flush();
        if (depth <= 0) { current = 1.0f; depth = 0; }
        else {
            depth--;
            current = depth < STACK.length ? STACK[depth] : current;
            if (depth == 0) current = 1.0f;
        }
        setRaw(1f, 1f, 1f, current);
    }

    /** Safety net: drop anything left pushed by an interrupted draw. */
    public static void reset() {
        if (depth != 0 || current != 1.0f) {
            depth = 0;
            current = 1.0f;
            setRaw(1f, 1f, 1f, 1f);
        }
    }

    public static float current() { return current; }

    /** True while at least one scope is open. */
    public static boolean active() { return depth > 0; }

    /** Set the shader colour exactly as given, bypassing the scope (for values read back from RenderSystem). */
    public static void setRaw(float r, float g, float b, float a) {
        boolean prev = raw;
        raw = true;
        try {
            RenderSystem.setShaderColor(r, g, b, a);
        } finally {
            raw = prev;
        }
    }

    /** {@code ShaderColorScopeMixin}: the alpha some code is setting, multiplied by the open scope (if any). */
    public static float scoped(float alpha) {
        return depth > 0 && !raw ? alpha * current : alpha;
    }
}
