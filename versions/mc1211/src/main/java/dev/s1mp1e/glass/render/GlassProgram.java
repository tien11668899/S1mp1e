package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.Charset;

/**
 * Compiles and owns the four glass programs, mirroring LiquidGlass26's
 * GlassPipeline: {@code glass} (backdrop-sampling refraction), {@code line}
 * (slot-separator lattice), {@code btn} (translucent capsule, no sampler) and
 * {@code menu_blur} (full-screen gaussian backdrop). Each is validated
 * independently so a failure in one only disables that surface — every draw
 * site checks its own {@code *Usable()} gate and falls back to vanilla.
 *
 * <p>Core-profile port (MC 1.17.1, GL 3.2 forward-compatible): the programs own
 * their own GL state, exactly as the 1.8.9–1.16.5 line does. The fixed-function
 * built-ins are gone, so the shared {@code glass.vsh} reads {@code Position/UV0/
 * Color} attributes (bound to locations 0/1/2 before link) and transforms with
 * explicit {@code ProjMat}/{@code ModelViewMat} uniforms instead of
 * {@code gl_ModelViewProjectionMatrix}. Those two matrices are sourced from
 * {@link RenderSystem} at {@link #bind} time and streamed column-major into the
 * uniforms. The VAO/VBO geometry is owned by {@link GlassRenderer}, not here.
 */
public final class GlassProgram {

    /** Program kinds, in the same order 26.2 builds them. */
    public static final int GLASS = 0;
    public static final int LINE  = 1;
    public static final int BTN   = 2;
    /** Full-screen gaussian for the menu backdrop. */
    public static final int BLUR  = 3;

    private static final int COUNT = 4;
    private static final String[] FSH_NAME = { "glass", "glass_line", "glass_btn", "menu_blur" };

    private static final int[] program    = new int[COUNT];
    private static final int[] uProj       = new int[COUNT];
    private static final int[] uModelView  = new int[COUNT];
    private static final int[] uSampler0   = new int[COUNT];
    private static final int[] uScreen     = new int[COUNT];
    private static final int[] uModulate   = new int[COUNT];
    private static final int[] uRadius     = new int[COUNT];
    private static final int[] uDim        = new int[COUNT];

    /** Reused 16-float scratch for streaming a {@link Matrix4f} into a mat4 uniform. */
    private static final FloatBuffer MAT16 = BufferUtils.createFloatBuffer(16);

    /** 0 = untried, 1 = ready, -1 = failed (never retry) */
    private static int state = 0;

    private GlassProgram() {}

    public static boolean usable()     { return state == 1 && program[GLASS] != 0; }
    public static boolean lineUsable() { return state == 1 && program[LINE]  != 0; }
    public static boolean btnUsable()  { return state == 1 && program[BTN]   != 0; }
    public static boolean blurUsable() { return state == 1 && program[BLUR]  != 0; }

    /** Build all four once. Returns false if this GPU can't run the glass path. */
    public static boolean ensureReady() {
        if (state != 0) return state == 1;
        try {
            if (!GL.getCapabilities().OpenGL20) {
                System.out.println("[S1mp1e] no GL2.0, glass disabled");
                state = -1;
                return false;
            }
            String vsrc = read("s1mp1e", "shaders/glass.vsh");
            int vs = compile(GL20.GL_VERTEX_SHADER, vsrc, "glass.vsh");
            if (vs == 0) { state = -1; return false; }

            for (int kind = 0; kind < COUNT; kind++) {
                program[kind] = link(vs, FSH_NAME[kind], kind);
            }
            GL20.glDeleteShader(vs);

            if (program[GLASS] == 0) { state = -1; return false; }
            state = 1;
            System.out.println("[S1mp1e] glass programs ready"
                + " (glass=" + (program[GLASS] != 0)
                + " line="   + (program[LINE]  != 0)
                + " btn="    + (program[BTN]   != 0)
                + " blur="   + (program[BLUR]  != 0) + ")");
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] glass shaders unavailable: " + t);
            state = -1;
            return false;
        }
    }

    private static int link(int vs, String fshName, int kind) {
        try {
            int fs = compile(GL20.GL_FRAGMENT_SHADER,
                             read("s1mp1e", "shaders/" + fshName + ".fsh"), fshName);
            if (fs == 0) return 0;
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            // Core profile: attribute locations are fixed from Java (no layout(location=)
            // in GLSL 150). All four programs share glass.vsh, so the same 0/1/2 binding
            // applies to every one, and one VAO's attribute layout serves them all.
            GL20.glBindAttribLocation(p, 0, "Position");
            GL20.glBindAttribLocation(p, 1, "UV0");
            GL20.glBindAttribLocation(p, 2, "Color");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.out.println("[S1mp1e] " + fshName + " link failed: "
                    + GL20.glGetProgramInfoLog(p));
                return 0;
            }
            uProj[kind]      = GL20.glGetUniformLocation(p, "ProjMat");
            uModelView[kind] = GL20.glGetUniformLocation(p, "ModelViewMat");
            uSampler0[kind]  = GL20.glGetUniformLocation(p, "Sampler0");
            uScreen[kind]    = GL20.glGetUniformLocation(p, "ScreenSize");
            uModulate[kind]  = GL20.glGetUniformLocation(p, "ColorModulator");
            uRadius[kind]    = GL20.glGetUniformLocation(p, "Radius");
            uDim[kind]       = GL20.glGetUniformLocation(p, "Dim");
            return p;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] " + fshName + " unavailable: " + t);
            return 0;
        }
    }

    /** MC's shader program, saved on bind and restored on unbind so MC's
     *  own GUI draws in the same frame do not run with program 0. */
    private static int prevProg = 0;

    /** Bind one program and set its frame-constant uniforms. */
    public static void bind(int kind) {
        MinecraftClient mc = MinecraftClient.getInstance();
        prevProg = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program[kind]);
        // Core-profile matrices replace the old gl_ModelViewProjectionMatrix. Read at
        // bind time so any GUI model-view push already applied by a mixin (e.g. the
        // hotbar's 1.15x scale) is captured; all quads in one batch share these.
        setMat(uProj[kind],      RenderSystem.getProjectionMatrix());
        // 1.21: getModelViewStack() now returns an org.joml.Matrix4fStack (extends
        // Matrix4f) — it IS the current matrix, no .peek().getPositionMatrix().
        setMat(uModelView[kind], RenderSystem.getModelViewStack());
        if (uSampler0[kind] >= 0) GL20.glUniform1i(uSampler0[kind], 0);
        if (uScreen[kind]   >= 0) GL20.glUniform2f(uScreen[kind], mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());
        if (uModulate[kind] >= 0) GL20.glUniform4f(uModulate[kind], 1f, 1f, 1f, 1f);
    }

    public static void unbind() { GL20.glUseProgram(prevProg); }

    /** True when this program samples the captured backdrop. */
    public static boolean needsBackdrop(int kind) { return kind == GLASS; }

    /** Blur-specific uniforms; call right after {@link #bind}. */
    public static void setBlur(float radiusPx, float dim) {
        if (uRadius[BLUR] >= 0) GL20.glUniform1f(uRadius[BLUR], radiusPx);
        if (uDim[BLUR]    >= 0) GL20.glUniform1f(uDim[BLUR],    dim);
    }

    // ---- helpers ----------------------------------------------------------

    /** Stream a {@link Matrix4f} column-major into the shared scratch and upload as a mat4. */
    private static void setMat(int loc, Matrix4f m) {
        if (loc < 0) return;
        MAT16.clear();
        m.get(MAT16);   // org.joml Matrix4f: column-major store into the buffer
        MAT16.rewind();
        GL20.glUniformMatrix4fv(loc, false, MAT16);   // transpose = false (column-major)
    }

    private static int compile(int type, String src, String label) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.out.println("[S1mp1e] " + label + " compile failed: "
                + GL20.glGetShaderInfoLog(id));
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    private static String read(String domain, String path) throws Exception {
        // Read shaders straight off the CLASSPATH rather than the resource manager:
        // version-agnostic (the RM's getResource()/getInputStream() signatures churned
        // 1.18->1.19 to Optional<Resource>/open()), and works before the mod's assets
        // are registered as a resource pack.
        InputStream in = GlassProgram.class.getResourceAsStream("/assets/" + domain + "/" + path);
        if (in == null) throw new java.io.FileNotFoundException("/assets/" + domain + "/" + path);
        try {
            BufferedReader r = new BufferedReader(
                new InputStreamReader(in, Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            in.close();
        }
    }
}
