package dev.s1mp1e.client.minimap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GuiAmbient;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Any number of quads through the minimap pipeline ({@code minimap.fsh}), given vertex by vertex: position (GUI px,
 * under {@code pose}), UV (map coordinates or local 0..1, plus the integer parameter slots) and colour. One element per
 * draw role keeps the GUI batcher's ordering (map, dots, markers) while consecutive elements still batch into one draw.
 *
 * <p>The vertex arrays are owned by the caller ({@link Quads}); they must not change until the frame's GUI pass has
 * built the vertices, which is always the case: elements are built in the same frame they are submitted, and
 * {@link Quads} buffers are refilled only in the next frame's HUD pass.
 */
final class MinimapRenderState implements GuiElementRenderState {

    private final RenderPipeline pipeline;
    private final TextureSetup textureSetup;
    private final Matrix3x2fc pose;
    private final float[] xyuv;
    private final int[] colour;
    private final int vertices;
    @Nullable private final ScreenRectangle scissor;
    @Nullable private final ScreenRectangle bounds;

    MinimapRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, Quads q) {
        this.scissor = GuiAmbient.scissor(null);
        this.pipeline = pipeline;
        this.textureSetup = textureSetup;
        this.pose = new Matrix3x2f(pose);
        this.xyuv = q.xyuv;
        this.colour = q.colour;
        this.vertices = q.vertices;
        ScreenRectangle b = new ScreenRectangle((int) Math.floor(q.minX), (int) Math.floor(q.minY),
                Math.max(1, (int) Math.ceil(q.maxX) - (int) Math.floor(q.minX)),
                Math.max(1, (int) Math.ceil(q.maxY) - (int) Math.floor(q.minY))).transformMaxBounds(this.pose);
        this.bounds = scissor != null ? scissor.intersection(b) : b;
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < vertices; i++) {
            int o = i * 4;
            vc.addVertexWith2DPose(pose, xyuv[o], xyuv[o + 1]).setUv(xyuv[o + 2], xyuv[o + 3]).setColor(colour[i]);
        }
    }

    @Override public RenderPipeline pipeline() { return pipeline; }
    @Override public TextureSetup textureSetup() { return textureSetup; }
    @Override @Nullable public ScreenRectangle scissorArea() { return scissor; }
    @Override @Nullable public ScreenRectangle bounds() { return bounds; }

    /** A growable, reusable vertex list (4 vertices per quad, in the order the GUI QUADS topology expects). */
    static final class Quads {
        float[] xyuv;
        int[] colour;
        int vertices;
        float minX, minY, maxX, maxY;

        Quads(int quadCapacity) {
            xyuv = new float[quadCapacity * 16];
            colour = new int[quadCapacity * 4];
        }

        Quads reset() {
            vertices = 0;
            minX = minY = Float.POSITIVE_INFINITY;
            maxX = maxY = Float.NEGATIVE_INFINITY;
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
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
    }
}
