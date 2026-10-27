package dev.s1mp1e.o.minimap;

import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;

/**
 * A growable, reusable vertex list for the minimap program (4 vertices per quad, A-B-C-D: top-left, bottom-left,
 * bottom-right, top-right), drawn in one call by {@link #draw}. 1.8.9 port of mc1211's MinimapQuads: the HUD pass is
 * immediate mode, so the quads go straight to {@link GlassRenderer#texturedQuads} at absolute GUI coordinates.
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

    /** Draw these quads with the map texture, over everything drawn before. */
    void draw() {
        if (vertices == 0) return;
        GlassRenderer.texturedQuads(GlassProgram.MINIMAP, MinimapData.glId(), MinimapData.size(), xyuv, colour, vertices);
    }
}
