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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs CS2's own weapon-finish compositing on the GPU: the decompiled {@code csgo_customweapon} fragment variant
 * for the paint's static combo (shipped in the user-built knife pack, never in this jar) is drawn over the knife's
 * UV space with the knife's composite inputs, the paint's pattern and the resolved constants, into an sRGB target —
 * exactly what CS2 generates into the weapon's colour texture. Raw GL on the render thread with the touched state
 * saved and restored, so blaze3d's own state caches stay valid.
 */
final class SkinCompositor {

    private SkinCompositor() {}

    private static final String VS = String.join("\n",
            "#version 330",
            "uniform vec4 s1_px0, s1_px1, s1_wx0, s1_wx1, s1_gx0, s1_gx1;",
            "out vec4 _5448;",   // uv.xy + pattern uv.zw   (names match the decompiled fragment inputs)
            "out vec4 _5838;",   // wear uv.xy + grunge uv.zw
            "void main() {",
            "  vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);",
            "  gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);",
            "  _5448 = vec4(uv, dot(uv, s1_px0.xy) + s1_px0.w, dot(uv, s1_px1.xy) + s1_px1.w);",
            "  _5838 = vec4(dot(uv, s1_wx0.xy) + s1_wx0.w, dot(uv, s1_wx1.xy) + s1_wx1.w,",
            "               dot(uv, s1_gx0.xy) + s1_gx0.w, dot(uv, s1_gx1.xy) + s1_gx1.w);",
            "}");

    /** combo id -> linked program */
    private static final Map<Integer, Integer> PROGRAMS = new HashMap<>();
    /** combo id -> member name -> GLSL type of the 'cb' struct */
    private static final Map<Integer, Map<String, String>> MEMBERS = new HashMap<>();

    /** sampler names whose source texture is sRGB-encoded (shader declares sRGB read) */
    private static boolean srgb(String sampler, int paintStyle) {
        switch (sampler) {
            case "g_tAmbientOcclusion": case "g_tColor": case "g_tGrunge": case "g_tOverlay":
            case "g_tCaseHardeningColorRamp": case "g_tPatinaAgeColorRamp":
                return true;
            case "g_tPattern":
                return paintStyle == 6 || paintStyle == 7 || paintStyle == 8;   // colour art, not a weight mask
            default:
                return false;
        }
    }

    /**
     * Composite one knife skin. Render thread only.
     *
     * @param entry   skins/&lt;knife&gt;/&lt;paint&gt;.json
     * @param style   the paint's F_PAINT_STYLE
     * @param wear    float wear 0..1
     * @param size    output edge (px)
     */
    static NativeImage composite(Path skinsRoot, JsonObject entry, int style, float wear, int size,
                                 Map<String, Decoded> pre) throws Exception {
        int combo = entry.get("combo").getAsInt();
        int prog = program(skinsRoot, combo);
        Map<String, String> members = MEMBERS.get(combo);

        GlState saved = GlState.capture();
        int fbo = 0, target = 0, vao = 0;
        Map<String, Integer> textures = new HashMap<>();
        NativeImage out = null;
        try {
            // inputs
            JsonObject samplers = entry.getAsJsonObject("samplers");
            JsonArray addr = entry.getAsJsonArray("addr");
            for (Map.Entry<String, JsonElement> s : samplers.entrySet()) {
                String file = s.getValue().getAsString();
                Decoded d = pre != null ? pre.get(file) : null;
                int tex = d != null ? upload(d, srgb(s.getKey(), style)) : upload(skinsRoot.resolve(file), srgb(s.getKey(), style));
                if ("g_tPattern".equals(s.getKey())) {
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, wrap(addr.get(0).getAsInt()));
                    GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, wrap(addr.get(1).getAsInt()));
                }
                textures.put(s.getKey(), tex);
            }
            // target (sRGB so the linear shader output is encoded like CS2's colour texture)
            target = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, target);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL21C.GL_SRGB8_ALPHA8, size, size, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            fbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, target, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("skin FBO incomplete");
            }
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);

            // fixed-function state for a plain overwrite
            GL11C.glViewport(0, 0, size, size);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);

            GL20C.glUseProgram(prog);
            JsonObject u = entry.getAsJsonObject("uniforms");
            setVs(prog, "s1_px0", u, "g_vPatternTexCoordXform0");
            setVs(prog, "s1_px1", u, "g_vPatternTexCoordXform1");
            setVs(prog, "s1_wx0", u, "g_vWearTexCoordXform0");
            setVs(prog, "s1_wx1", u, "g_vWearTexCoordXform1");
            setVs(prog, "s1_gx0", u, "g_vGrungeTexCoordXform0");
            setVs(prog, "s1_gx1", u, "g_vGrungeTexCoordXform1");
            for (Map.Entry<String, String> m : members.entrySet()) {
                String name = m.getKey();
                float[] v = vec(u, name);
                if ("g_flWearAmount".equals(name)) v = new float[] { wear, wear, wear, wear };
                int loc = GL20C.glGetUniformLocation(prog, "cb." + name);
                if (loc < 0) continue;
                switch (m.getValue()) {
                    case "int": case "uint": case "bool": GL20C.glUniform1i(loc, Math.round(v[0])); break;
                    case "float": GL20C.glUniform1f(loc, v[0]); break;
                    case "vec2": GL20C.glUniform2f(loc, v[0], v[1]); break;
                    case "vec3": GL20C.glUniform3f(loc, v[0], v[1], v[2]); break;
                    default: GL20C.glUniform4f(loc, v[0], v[1], v[2], v[3]); break;
                }
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
            vao = GL30C.glGenVertexArrays();
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);   // read the stored sRGB bytes as-is
            out = new NativeImage(NativeImage.Format.RGBA, size, size, false);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);   // vanilla leaves this non-zero -> sheared rows
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.nglReadPixels(0, 0, size, size, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, out.getPointer());
            return out;
        } catch (Throwable t) {
            if (out != null) out.close();
            throw t;
        } finally {
            if (vao != 0) GL30C.glDeleteVertexArrays(vao);
            if (fbo != 0) GL30C.glDeleteFramebuffers(fbo);
            if (target != 0) GL11C.glDeleteTextures(target);
            for (int t : textures.values()) GL11C.glDeleteTextures(t);
            saved.restore();
        }
    }

    private static void setVs(int prog, String uniform, JsonObject u, String key) {
        float[] v = vec(u, key);
        int loc = GL20C.glGetUniformLocation(prog, uniform);
        if (loc >= 0) GL20C.glUniform4f(loc, v[0], v[1], v[2], v[3]);
    }

    private static float[] vec(JsonObject u, String key) {
        float[] v = new float[4];
        JsonArray a = u.has(key) ? u.getAsJsonArray(key) : null;
        if (a != null) for (int i = 0; i < 4 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
        return v;
    }

    private static int wrap(int sourceAddressMode) {
        switch (sourceAddressMode) {
            case 1: return GL14C.GL_MIRRORED_REPEAT;
            case 2: return GL12C.GL_CLAMP_TO_EDGE;
            default: return GL11C.GL_REPEAT;
        }
    }

    // ---- inputs -------------------------------------------------------------------------------------------

    /**
     * A composite input decoded from disk on ANY thread (PNG decode is the slow part of a composite — seconds for
     * a glove's 2048² inputs), so the render thread only does the GL upload. Owns native memory: close it.
     */
    static final class Decoded implements AutoCloseable {
        final int kind;        // 0 RGBA8 image, 1 R8, 2 RGB32F
        final int w, h;
        NativeImage img;
        ByteBuffer buf;

        private Decoded(int kind, int w, int h) { this.kind = kind; this.w = w; this.h = h; }

        @Override public void close() {
            if (img != null) { img.close(); img = null; }
            if (buf != null) { MemoryUtil.memFree(buf); buf = null; }
        }
    }

    static Decoded decode(Path file) throws Exception {
        String n = file.toString();
        if (n.endsWith(".f32z")) {                      // int32 w, int32 h, zlib(byte-shuffled float32 RGB): lossless
            ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            int w = raw.getInt(), h = raw.getInt();
            int floats = w * h * 3, bytes = floats * 4;
            byte[] sh = new byte[bytes];
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            try {
                inf.setInput(raw);
                int got = 0;
                while (got < bytes && !inf.finished()) {
                    int k = inf.inflate(sh, got, bytes - got);
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    got += k;
                }
            } finally {
                inf.end();
            }
            Decoded d = new Decoded(2, w, h);
            d.buf = MemoryUtil.memAlloc(bytes);
            for (int i = 0; i < floats; i++) {          // un-shuffle: byte b of float i sits at b * floats + i
                d.buf.put(i * 4, sh[i]);
                d.buf.put(i * 4 + 1, sh[floats + i]);
                d.buf.put(i * 4 + 2, sh[2 * floats + i]);
                d.buf.put(i * 4 + 3, sh[3 * floats + i]);
            }
            d.buf.order(ByteOrder.LITTLE_ENDIAN);
            return d;
        }
        if (n.endsWith(".r8") || n.endsWith(".f32")) {
            ByteBuffer raw = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            int w = raw.getInt(), h = raw.getInt();
            Decoded d = new Decoded(n.endsWith(".r8") ? 1 : 2, w, h);
            d.buf = MemoryUtil.memAlloc(raw.remaining());
            d.buf.put(raw).flip();
            return d;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in);
            Decoded d = new Decoded(0, img.getWidth(), img.getHeight());
            d.img = img;
            return d;
        }
    }

    static int upload(Path file, boolean srgb) throws Exception {
        try (Decoded d = decode(file)) {
            return upload(d, srgb);
        }
    }

    /** Render thread. Does not close {@code d}. */
    static int upload(Decoded d, boolean srgb) {
        int tex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        if (d.kind == 1) {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R8, d.w, d.h, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, d.buf);
        } else if (d.kind == 2) {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGB32F, d.w, d.h, 0, GL11C.GL_RGB, GL11C.GL_FLOAT, d.buf.asFloatBuffer());
        } else {
            GL11C.nglTexImage2D(GL11C.GL_TEXTURE_2D, 0, srgb ? GL21C.GL_SRGB8_ALPHA8 : GL11C.GL_RGBA8,
                    d.w, d.h, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, d.img.getPointer());
        }
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        return tex;
    }

    // ---- program ------------------------------------------------------------------------------------------

    private static final Pattern STRUCT = Pattern.compile("struct _\\d+\\s*\\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern MEMBER = Pattern.compile("^\\s*(vec[234]|float|int|uint|bool|ivec[234])\\s+(\\w+);", Pattern.MULTILINE);

    private static int program(Path skinsRoot, int combo) throws Exception {
        Integer p = PROGRAMS.get(combo);
        if (p != null) return p;
        String fs = Files.readString(skinsRoot.resolve("shaders").resolve("combo" + combo + ".glsl"));
        Map<String, String> members = new HashMap<>();
        Matcher sm = STRUCT.matcher(fs);
        if (sm.find()) {
            Matcher mm = MEMBER.matcher(sm.group(1));
            while (mm.find()) members.put(mm.group(2), mm.group(1));
        }
        int vs = compile(GL20C.GL_VERTEX_SHADER, VS);
        int ps = compile(GL20C.GL_FRAGMENT_SHADER, fs);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, ps);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(ps);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("skin program combo" + combo + " link: " + log);
        }
        PROGRAMS.put(combo, prog);
        MEMBERS.put(combo, members);
        return prog;
    }

    static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(s);
            GL20C.glDeleteShader(s);
            throw new IllegalStateException("skin shader compile: " + log);
        }
        return s;
    }

    // ---- GL state snapshot --------------------------------------------------------------------------------

    /** Everything the composite pass touches, captured from the live context and put back verbatim. */
    static final class GlState {
        static final int UNITS = 16;
        int program, drawFbo, readFbo, vao, activeTex, packAlign, unpackAlign, unpackRow, unpackSkipRows, unpackSkipPx;
        int packRow, packSkipRows, packSkipPx, packPbo, unpackPbo;
        int readBuffer;
        final int[] viewport = new int[4];
        final int[] tex2d = new int[UNITS], samplers = new int[UNITS];
        boolean blend, depth, cull, scissor, stencil, srgbFb;
        final boolean[] colorMask = new boolean[4];
        int drawBuffer0;

        static GlState capture() {
            GlState s = new GlState();
            s.program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            s.drawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            s.readFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            s.vao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            s.activeTex = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            s.packAlign = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
            s.unpackAlign = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
            s.unpackRow = GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH);
            s.unpackSkipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
            s.unpackSkipPx = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
            s.packRow = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
            s.packSkipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
            s.packSkipPx = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
            s.packPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            s.unpackPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
            s.readBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
            s.drawBuffer0 = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0);
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer vp = st.mallocInt(4);
                GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
                vp.get(s.viewport);
                ByteBuffer cm = st.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, cm);
                for (int i = 0; i < 4; i++) s.colorMask[i] = cm.get(i) != 0;
            }
            for (int i = 0; i < UNITS; i++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
                s.tex2d[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                s.samplers[i] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            }
            GL13C.glActiveTexture(s.activeTex);
            s.blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
            s.depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
            s.cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
            s.scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            s.stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
            s.srgbFb = GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
            return s;
        }

        void restore() {
            GL20C.glUseProgram(program);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
            GL30C.glBindVertexArray(vao);
            for (int i = 0; i < UNITS; i++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex2d[i]);
                GL33C.glBindSampler(i, samplers[i]);
            }
            GL13C.glActiveTexture(activeTex);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, packAlign);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, unpackAlign);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, unpackRow);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, unpackSkipRows);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, unpackSkipPx);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, packRow);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, packSkipRows);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, packSkipPx);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, packPbo);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpackPbo);
            set(GL11C.GL_BLEND, blend);
            set(GL11C.GL_DEPTH_TEST, depth);
            set(GL11C.GL_CULL_FACE, cull);
            set(GL11C.GL_SCISSOR_TEST, scissor);
            set(GL11C.GL_STENCIL_TEST, stencil);
            set(GL30C.GL_FRAMEBUFFER_SRGB, srgbFb);
            GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (drawFbo == 0 || drawBuffer0 != GL11C.GL_NONE) {
                // the default framebuffer / an FBO keep their own draw-buffer state; nothing else to undo
            }
            if (readFbo != 0 || readBuffer != 0) {
                try { GL11C.glReadBuffer(readBuffer); } catch (Throwable ignored) {}
            }
        }

        private static void set(int cap, boolean on) {
            if (on) GL11C.glEnable(cap); else GL11C.glDisable(cap);
        }
    }
}
