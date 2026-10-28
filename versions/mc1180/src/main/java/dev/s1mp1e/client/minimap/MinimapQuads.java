package dev.s1mp1e.client.minimap;

import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;

/**
 * A growable, reusable vertex list for the minimap program (4 vertices per quad, A-B-C-D: top-left, bottom-left,
 * bottom-right, top-right), drawn in one call by {@link #draw}. 1.21.1 port of 26.x's MinimapRenderState.Quads: there
 * the lists became GUI render-state elements; here they go straight to {@link GlassRenderer#texturedQuads}.
 */
final class MinimapQuads {
    float[] xyuv;
    int[] colour;
    int vertices;

    MinimapQuads(int quadCapacity) {
        xyuv = new float[quadCapacity * 16];
        colour = new int[quadCapacity * 4];
    }

    MinimapQuads reset() {
        vertices = 0;
        return this;
    }

    boolean isEmpty() { return vertices == 0; }

    void vertex(float x, float y, float u, float v, int argb) {
        if (vertices * 4 + 4 > xyuv.length) {
            xyuv = java.util.Arrays.copyOf(xyuv, xyuv.length * 2);
            colour = java.util.Arrays.copyOf(colour, colour.length * 2);
        }
        int o = vertices * 4;
        xyuv[o] = x;
        xyuv[o + 1] = y;
        xyuv[o + 2] = u;
        xyuv[o + 3] = v;
        colour[vertices++] = argb;
    }

    /** Flush the DrawContext (so the quads land above everything drawn before) and draw these quads with the map texture. */
    void draw(MatrixStack matrices) {
        if (vertices == 0) return;
        GuiFlush.flush();
        GlassRenderer.texturedQuads(GlassProgram.MINIMAP, MinimapData.glId(), xyuv, colour, vertices,
                matrices.peek().getPositionMatrix());
    }
}
