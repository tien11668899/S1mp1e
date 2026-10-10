package dev.s1mp1e.client.knife;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Glove skins: CS2's {@code csgo_customglove} compositor (decompiled to GLSL by the user's own pack build) run on
 * the GPU, once per hand, in UV space. Fully data-driven by the skin entry: the pack supplies the vertex+fragment
 * pair, every uniform already resolved (GL name, type, value), the samplers (file, sRGB, wrap, filter) and the
 * per-hand overrides — nothing CS2-specific is hard-coded here. Same GL hygiene as {@link SkinCompositor}.
 */
final class GloveCompositor {

    private GloveCompositor() {}

    private static final Map<String, Integer> PROGRAMS = new HashMap<>();

    /**
     * Render thread only. Composites every hand of the entry (inputs uploaded once) and returns hand -> colour
     * texture (RGBA, sRGB-encoded when the entry says so). {@code pre}: inputs already decoded off-thread, by file.
     */
    static Map<String, NativeImage> compositeAll(Path root, JsonObject entry, float wear,
                                                 Map<String, SkinCompositor.Decoded> pre) throws Exception {
        int prog = program(root, entry.get("vert").getAsString(), entry.get("frag").getAsString());
        int size = Math.min(2048, entry.has("size") ? entry.get("size").getAsInt() : 2048);
        boolean srgbOut = !entry.has("output_srgb") || entry.get("output_srgb").getAsBoolean();

        SkinCompositor.GlState saved = SkinCompositor.GlState.capture();
        int fbo = 0, target = 0, vao = 0;
        Map<String, Integer> textures = new LinkedHashMap<>();
        Map<String, NativeImage> result = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, JsonElement> s : entry.getAsJsonObject("samplers").entrySet()) {
                JsonObject d = s.getValue().getAsJsonObject();
                String file = d.get("file").getAsString();
                boolean srgb = d.has("srgb") && d.get("srgb").getAsBoolean();
                SkinCompositor.Decoded dec = pre != null ? pre.get(file) : null;
                int tex = dec != null ? SkinCompositor.upload(dec, srgb) : SkinCompositor.upload(root.resolve(file), srgb);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
                String w = d.has("wrap") ? d.get("wrap").getAsString() : "repeat";
                int wrap = wrap(w);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, wrap);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, wrap);
                boolean nearest = d.has("filter") && "nearest".equals(d.get("filter").getAsString());
                int minF = nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
                if (!nearest && !"clamp".equals(w)) {          // tiling layers are sampled with a LOD bias
                    GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                    minF = GL11C.GL_LINEAR_MIPMAP_LINEAR;
                }
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, minF);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, nearest ? GL11C.GL_NEAREST : GL11C.GL_LINEAR);
                textures.put(s.getKey(), tex);
            }

            target = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, target);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, srgbOut ? GL21C.GL_SRGB8_ALPHA8 : GL11C.GL_RGBA8, size, size, 0,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            fbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, target, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("glove FBO incomplete");
            }
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glViewport(0, 0, size, size);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glColorMask(true, true, true, true);
            vao = GL30C.glGenVertexArrays();
            GL30C.glBindVertexArray(vao);
            GL20C.glUseProgram(prog);

            for (Map.Entry<String, JsonElement> he : entry.getAsJsonObject("hands").entrySet()) {
                JsonObject h = he.getValue().getAsJsonObject();
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
                if (srgbOut) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB); else GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
                setAll(prog, entry.getAsJsonObject("uniforms"));
                if (h.has("uniforms")) setAll(prog, h.getAsJsonObject("uniforms"));
                if (entry.has("wear_uniform") && !entry.get("wear_uniform").isJsonNull()) {
                    int loc = GL20C.glGetUniformLocation(prog, entry.get("wear_uniform").getAsString());
                    // g_fWearProgress = wear ^ wear_exponent (per skin)
                    float exp = entry.has("wear_exponent") ? entry.get("wear_exponent").getAsFloat() : 1f;
                    if (loc >= 0) GL20C.glUniform1f(loc, (float) Math.pow(Math.max(0f, wear), exp));
                }
                int off = GL20C.glGetUniformLocation(prog, "s1_uvOffset");
                if (off >= 0) {
                    JsonArray o = h.has("uvOffset") ? h.getAsJsonArray("uvOffset") : null;
                    GL20C.glUniform2f(off, o != null ? o.get(0).getAsFloat() : 0f, o != null ? o.get(1).getAsFloat() : 0f);
                }
                int unit = 0;
                for (Map.Entry<String, Integer> t : textures.entrySet()) {
                    int loc = GL20C.glGetUniformLocation(prog, t.getKey());
                    if (loc < 0) continue;
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t.getValue());
                    GL33C.glBindSampler(unit, 0);
                    GL20C.glUniform1i(loc, unit);
                    unit++;
                }
                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

                GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
                NativeImage out = new NativeImage(NativeImage.Format.RGBA, size, size, false);
                result.put(he.getKey(), out);
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
                GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
                GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
                GL11C.nglReadPixels(0, 0, size, size, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, out.getPointer());
            }
            return result;
        } catch (Throwable t) {
            for (NativeImage im : result.values()) im.close();
            throw t;
        } finally {
            if (vao != 0) GL30C.glDeleteVertexArrays(vao);
            if (fbo != 0) GL30C.glDeleteFramebuffers(fbo);
            if (target != 0) GL11C.glDeleteTextures(target);
            for (int t : textures.values()) GL11C.glDeleteTextures(t);
            saved.restore();
        }
    }

    private static void setAll(int prog, JsonObject uniforms) {
        if (uniforms == null) return;
        for (Map.Entry<String, JsonElement> e : uniforms.entrySet()) {
            int loc = GL20C.glGetUniformLocation(prog, e.getKey());
            if (loc < 0) continue;
            JsonObject d = e.getValue().getAsJsonObject();
            String type = d.has("type") ? d.get("type").getAsString() : "vec4";
            JsonArray a = d.getAsJsonArray("value");
            float[] v = new float[4];
            for (int i = 0; i < 4 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
            switch (type) {
                case "int": case "uint": case "bool": GL20C.glUniform1i(loc, Math.round(v[0])); break;
                case "float": GL20C.glUniform1f(loc, v[0]); break;
                case "vec2": GL20C.glUniform2f(loc, v[0], v[1]); break;
                case "vec3": GL20C.glUniform3f(loc, v[0], v[1], v[2]); break;
                default: GL20C.glUniform4f(loc, v[0], v[1], v[2], v[3]); break;
            }
        }
    }

    private static int wrap(String w) {
        switch (w) {
            case "clamp": return GL12C.GL_CLAMP_TO_EDGE;
            case "mirror": return GL14C.GL_MIRRORED_REPEAT;
            default: return GL11C.GL_REPEAT;
        }
    }

    private static int program(Path root, String vert, String frag) throws Exception {
        String key = vert + "|" + frag;
        Integer p = PROGRAMS.get(key);
        if (p != null) return p;
        int vs = SkinCompositor.compile(GL20C.GL_VERTEX_SHADER, Files.readString(root.resolve(vert)));
        int fs = SkinCompositor.compile(GL20C.GL_FRAGMENT_SHADER, Files.readString(root.resolve(frag)));
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("glove program " + key + " link: " + log);
        }
        PROGRAMS.put(key, prog);
        return prog;
    }
}
