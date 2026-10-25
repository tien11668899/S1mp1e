package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;

/**
 * A scoped opacity for arbitrary GUI drawing: everything drawn between {@link #push} and {@link #pop} (text, fills,
 * texture / sprite blits) has its alpha multiplied by the pushed value. Pushes nest (multiply). Render thread only.
 *
 * <p>The 1.21.1 counterpart of 26.2's {@code GuiAlpha} + {@code GuiAlphaRenderStateMixin}. 26.2 multiplied the colour
 * arguments of the deferred GUI render-state constructors; this line has no such choke point, but every GUI shader here
 * multiplies by {@code ColorModulator} ({@code RenderSystem.setShaderColor}), which is read when a batch is FLUSHED. So
 * the scope is bracketed by flushes: {@code push} flushes what was queued before it (at the old colour) and sets the
 * modulator; {@code pop} flushes what was queued inside the scope (still at the scoped colour) and restores it.
 * Immediate draws inside the scope ({@code drawTexture}, {@code drawGuiTexture}) pick the modulator up directly.
 * Items (3D models, which bring their own lighting) and the raw-GL glass programs are not affected.
 */
public final class GuiAlpha {
    private GuiAlpha() {}

    private static final float[] STACK = new float[32];
    private static int depth;
    private static float current = 1.0f;

    public static void push(DrawContext ctx, float alpha) {
        if (ctx != null) ctx.draw();
        if (depth < STACK.length) STACK[depth] = current;
        depth++;
        current *= Math.max(0.0f, Math.min(1.0f, alpha));
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, current);
    }

    public static void pop(DrawContext ctx) {
        if (ctx != null) ctx.draw();
        if (depth <= 0) { current = 1.0f; depth = 0; }
        else {
            depth--;
            current = depth < STACK.length ? STACK[depth] : current;
            if (depth == 0) current = 1.0f;
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, current);
    }

    /** Safety net: drop anything left pushed by an interrupted draw. */
    public static void reset() {
        if (depth != 0 || current != 1.0f) {
            depth = 0;
            current = 1.0f;
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    public static float current() { return current; }
}
