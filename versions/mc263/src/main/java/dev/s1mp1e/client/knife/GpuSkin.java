package dev.s1mp1e.client.knife;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * GPU skinning for the CS2 viewmodel. Each mesh is uploaded once (bind-pose position, uv, normal, 4 joint
 * indices + weights); per frame the CPU only writes the joint matrices + hand pose into a small uniform block and
 * issues one render pass in the hand phase (depth already cleared, hand projection set — the exact state vanilla's
 * first-person items draw in). Shading = vanilla {@code core/entity} fragment shader (cutout, per-face lighting,
 * lightmap, fog), so it looks identical to the CPU path. Falls back to the CPU path if the pipeline can't compile.
 */
final class GpuSkin {

    private GpuSkin() {}

    static final int MAX_JOINTS = 128;
    private static final int UBO_SIZE = 144 + MAX_JOINTS * 64;     // ModelPose, NormalPose, LightUV, Joints[]
    private static final int STRIDE = 32;

    static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
            .addAttribute("JointIdx", GpuFormat.RGBA8_UNORM)
            .addAttribute("JointW", GpuFormat.RGBA8_UNORM)
            .build();

    private static RenderPipeline pipeline;
    private static CompiledRenderPipeline compiled;
    private static int state;     // 0 untried, 1 ok, -1 unavailable
    static final boolean DISABLED = System.getenv("S1MP1E_KNIFE_CPU") != null;

    /**
     * 26.3: pipelines are compiled by the global pipeline cache from the global shader source. The vertex shader
     * lives under the liquidglass namespace, which the glass shader-source hooks already serve from this jar
     * (no Fabric API resource pack); the fragment shader is vanilla's core/entity.
     */
    static boolean available() {
        if (state == 0) {
            state = -1;
            if (DISABLED) return false;
            try {
                BindGroupLayout skinLayout = BindGroupLayout.builder().withUniform("KnifeSkin", UniformType.UNIFORM_BUFFER).build();
                RenderPipeline p = RenderPipeline.builder()
                        .withLocation(Identifier.fromNamespaceAndPath("liquidglass", "pipeline/knife_skinned"))
                        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                        .withBindGroupLayout(BindGroupLayouts.FOG)
                        .withBindGroupLayout(BindGroupLayouts.LIGHTING)
                        .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                        .withBindGroupLayout(skinLayout)
                        .withVertexShader(Identifier.fromNamespaceAndPath("liquidglass", "core/knife_skinned"))
                        .withFragmentShader(Identifier.withDefaultNamespace("core/entity"))
                        .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                        .withShaderDefine("PER_FACE_LIGHTING")
                        .withShaderDefine("NO_OVERLAY")
                        .withVertexBinding(0, FORMAT)
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                        .withDepthStencilState(DepthStencilState.DEFAULT)
                        // one color target to match the single color attachment of the hand-pass render pass (main target).
                        // ALPHA_CUTOUT discards transparent texels; opaque blade texels have alpha 1.0 so TRANSLUCENT = overwrite.
                        .withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(
                                com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT))
                        .withCull(false)
                        .build();
                CompiledRenderPipeline c = RenderSystem.getCompiledPipeline(p);
                if (c == null || c.isClosed()) throw new IllegalStateException("knife pipeline did not compile");
                pipeline = p;
                compiled = c;
                state = 1;
                System.out.println("[S1mp1e] CS2 knife: GPU skinning pipeline ready");
            } catch (Throwable t) {
                System.out.println("[S1mp1e] CS2 knife: GPU skinning unavailable, using CPU path: " + t);
            }
        }
        return state == 1;
    }

    /** Dominant-joint side of a mesh vertex: 'L', 'R', or 0 for a shared/other bone. */
    static char vertexSide(KMesh m, int v) {
        int best = 0; float bw = -1f;
        for (int c = 0; c < 4; c++) { float w = m.weight[v * 4 + c]; if (w > bw) { bw = w; best = m.joint[v * 4 + c]; } }
        if (best < 0 || best >= m.jointNode.length) return 0;
        String n = m.nodeName[m.jointNode[best]];
        if (n == null) return 0;
        // side token anywhere: hand_R, arm_lower_R_TWIST1, finger_index_0_L ... (the twist bones don't END in _R)
        for (int i = n.length() - 2; i >= 0; i--) {
            if (n.charAt(i) != '_') continue;
            char c = n.charAt(i + 1);
            if (c != 'L' && c != 'R') continue;
            int j = i + 2;
            if (j == n.length() || n.charAt(j) == '_' || n.charAt(j) == ' ' || Character.isDigit(n.charAt(j))) return c;
        }
        return 0;
    }

    /** One uploaded mesh + its per-frame uniform block. */
    static final class Mesh implements AutoCloseable {
        final GpuBuffer vbuf, ibuf, ibufMirror, ubo;
        /** When non-null (side-filtered mesh), per-part index ranges into ibuf; else use the KMesh's own partStart/partCount. */
        final int[] sStart, sCount;
        final ByteBuffer uboData;

        Mesh(KMesh m) { this(m, (char) 0); }

        /** {@code side} 'L'/'R' keeps only triangles whose first vertex is dominated by a bone of that side; 0 keeps all. */
        Mesh(KMesh m, char side) {
            if (m.jointNode.length > MAX_JOINTS) throw new IllegalStateException("too many joints: " + m.jointNode.length);
            GpuDevice dev = RenderSystem.getDevice();
            ByteBuffer v = MemoryUtil.memAlloc(m.vertexCount * STRIDE);
            ByteBuffer i0 = MemoryUtil.memAlloc(m.index.length * 4), i1 = MemoryUtil.memAlloc(m.index.length * 4);
            int[] ss = null, sc = null;
            try {
                for (int k = 0; k < m.vertexCount; k++) {
                    v.putFloat(m.pos[k * 3]).putFloat(m.pos[k * 3 + 1]).putFloat(m.pos[k * 3 + 2]);
                    v.putFloat(m.uv[k * 2]).putFloat(m.uv[k * 2 + 1]);
                    v.put(snorm(m.nrm[k * 3])).put(snorm(m.nrm[k * 3 + 1])).put(snorm(m.nrm[k * 3 + 2])).put((byte) 0);
                    for (int c = 0; c < 4; c++) v.put((byte) Math.min(255, m.joint[k * 4 + c]));
                    for (int c = 0; c < 4; c++) v.put((byte) Math.round(Math.max(0f, Math.min(1f, m.weight[k * 4 + c])) * 255f));
                }
                v.flip();
                int[] idx = m.index;
                if (side == 0) {
                    for (int t = 0; t + 2 < idx.length; t += 3) {
                        i0.putInt(idx[t]).putInt(idx[t + 1]).putInt(idx[t + 2]);
                        i1.putInt(idx[t]).putInt(idx[t + 2]).putInt(idx[t + 1]);   // mirrored winding (left-handed)
                    }
                } else {
                    // rebuild per part, keeping only this side's triangles contiguously; record new ranges
                    ss = new int[m.partStart.length]; sc = new int[m.partStart.length];
                    int off = 0;
                    for (int p = 0; p < m.partStart.length; p++) {
                        ss[p] = off;
                        int start = m.partStart[p], end = start + m.partCount[p];
                        for (int t = start; t + 2 < end; t += 3) {
                            if (vertexSide(m, idx[t]) != side) continue;
                            i0.putInt(idx[t]).putInt(idx[t + 1]).putInt(idx[t + 2]);
                            i1.putInt(idx[t]).putInt(idx[t + 2]).putInt(idx[t + 1]);
                            off += 3;
                        }
                        sc[p] = off - ss[p];
                    }
                }
                i0.flip();
                i1.flip();
                vbuf = dev.createBuffer(() -> "s1mp1e knife vertices", GpuBuffer.USAGE_VERTEX, v);
                ibuf = dev.createBuffer(() -> "s1mp1e knife indices", GpuBuffer.USAGE_INDEX, i0);
                ibufMirror = dev.createBuffer(() -> "s1mp1e knife indices (mirrored)", GpuBuffer.USAGE_INDEX, i1);
                ubo = dev.createBuffer(() -> "s1mp1e knife skin", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
            } finally {
                MemoryUtil.memFree(v);
                MemoryUtil.memFree(i0);
                MemoryUtil.memFree(i1);
            }
            sStart = ss; sCount = sc;
            uboData = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());
        }

        private static byte snorm(float f) {
            return (byte) Math.round(Math.max(-1f, Math.min(1f, f)) * 127f);
        }

        @Override public void close() {
            vbuf.close();
            ibuf.close();
            ibufMirror.close();
            ubo.close();
        }
    }

    /** Part texture lookup for {@link #draw}. */
    interface PartTexture { Identifier get(int part); }

    /**
     * Draw one attached mesh now (render thread, hand phase). {@code joints} = rig-space skin matrices
     * ({@link KnifeRig.Attached#poseJoints}); {@code M}/{@code N} the hand pose and its normal matrix.
     */
    static void draw(KnifeRig.Attached a, float[] joints, Matrix4f M, Matrix3f N, int light, boolean mirrored,
                     PartTexture tex) {
        if (a.gpu == null) a.gpu = new Mesh(a.mesh);
        draw(a.gpu, a.mesh, joints, M, N, light, mirrored, tex);
    }

    /** Core draw: {@code g} may be a full or side-filtered {@link Mesh} of {@code m}. */
    static void draw(Mesh g, KMesh m, float[] joints, Matrix4f M, Matrix3f N, int light, boolean mirrored,
                     PartTexture tex) {

        ByteBuffer u = g.uboData;
        u.clear();
        M.get(0, u);
        u.position(64);
        u.putFloat(N.m00()).putFloat(N.m01()).putFloat(N.m02()).putFloat(0f);
        u.putFloat(N.m10()).putFloat(N.m11()).putFloat(N.m12()).putFloat(0f);
        u.putFloat(N.m20()).putFloat(N.m21()).putFloat(N.m22()).putFloat(0f);
        u.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f);
        u.putInt(light & 0xFFFF).putInt((light >>> 16) & 0xFFFF).putInt(0).putInt(0);
        int jn = Math.min(MAX_JOINTS, m.jointNode.length);
        for (int k = 0; k < jn * 16; k++) u.putFloat(joints[k]);
        u.position(0);
        u.limit(144 + jn * 64);
        var enc = RenderSystem.getDevice().createCommandEncoder();
        enc.writeToBuffer(g.ubo.slice(0, 144 + (long) jn * 64), u);

        Minecraft mc = Minecraft.getInstance();
        // the hand pass's own targets: main colour + the depth GameRenderer cleared and handed to renderItemInHand
        RenderTarget rt = mc.gameRenderer.mainRenderTarget();
        GpuTextureView color = rt.getColorTextureView();
        GpuTextureView depth = KnifeRenderer.handDepth != null ? KnifeRenderer.handDepth : rt.getDepthTextureView();
        GpuBufferSlice dyn = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
        // 26.3: TextureManager.getTexture lazily UPLOADS an unresolved texture, and the device refuses an upload while a
        // render pass is open ("Close the existing render pass..."). Resolve every part texture BEFORE opening our pass
        // (this submit-phase point has no pass open), then the pass only binds already-uploaded views.
        AbstractTexture[] parts = new AbstractTexture[m.partStart.length];
        for (int p = 0; p < m.partStart.length; p++) {
            Identifier id = tex.get(p);
            if (id != null) parts[p] = mc.getTextureManager().getTexture(id);
        }
        try (RenderPass pass = enc.createRenderPass(() -> "s1mp1e CS2 knife", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dyn);
            pass.setUniform("KnifeSkin", g.ubo);
            pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setVertexBuffer(0, g.vbuf.slice());
            pass.setIndexBuffer(mirrored ? g.ibufMirror : g.ibuf, IndexType.INT);
            for (int p = 0; p < m.partStart.length; p++) {
                AbstractTexture t = parts[p];
                if (t == null) continue;
                int start = g.sStart != null ? g.sStart[p] : m.partStart[p];
                int count = g.sCount != null ? g.sCount[p] : m.partCount[p];
                count -= count % 3;
                if (count <= 0) continue;
                pass.setUniform("Sampler0", t.getTextureView(), t.getSampler());
                pass.drawIndexed(count, 1, start, 0, 0);
            }
        }
    }
}
