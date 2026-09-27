package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.compat.Mc1132;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Compiles and owns the glass programs, mirroring LiquidGlass26's GlassPipeline:
 * {@code glass} (backdrop-sampling refraction), {@code line} (slot-separator
 * lattice), {@code btn} (translucent capsule, no sampler), {@code menu_blur}
 * (full-screen gaussian backdrop), plus the config-GUI trio {@code round}
 * (solid AA rounded rect), {@code edge} (scroll-edge progressive blur) and
 * {@code glass_lens} (refracting capsule for the switch knob / slider thumb).
 * Each is validated independently so a failure in one only disables that
 * surface — every draw site checks its own {@code *Usable()} gate and falls
 * back to vanilla.
 *
 * <h3>1.13.2 (Legacy Fabric) deltas</h3>
 * <ul>
 *   <li>{@link #read} loads {@code /assets/<ns>/<path>} straight off the
 *       CLASSPATH ({@code GlassProgram.class.getResourceAsStream}), not through
 *       MC's {@code ResourceManager} — there is no fabric-api resource loader on
 *       this version, so the mod's assets are not registered as a resource pack
 *       at shader-compile time.</li>
 *   <li>{@code ScreenSize} comes from the {@link Mc1132} bridge
 *       ({@code fbW()/fbH()}, which fall back to the GL viewport before the
 *       Window exists), NOT from {@code mc.window.getFramebufferWidth()}.</li>
 *   <li>{@code glGetShaderInfoLog(id)} / {@code glGetProgramInfoLog(id)} use the
 *       1-arg forms, present in both LWJGL 3.1.6 (compile / production) and
 *       3.3.2 (dev {@code runClient}).</li>
 * </ul>
 */
public final class GlassProgram {

    /** Program kinds, in the same order 26.2 builds them. */
    public static final int GLASS = 0;
    public static final int LINE  = 1;
    public static final int BTN   = 2;
    /** Full-screen gaussian for the menu backdrop. */
    public static final int BLUR  = 3;
    /** Solid coloured rounded rect (AA SDF, no backdrop) — the config-GUI fill primitive. */
    public static final int ROUND = 4;
    /** iOS-26 scroll-edge: progressive backdrop blur + dark fade, ramped by vLocal.y. */
    public static final int EDGE  = 5;
    /** Refracting LENS (glass_lens.fsh): glass + full-capsule corner, for the switch knob / slider thumb. */
    public static final int LENS  = 6;

    /** Liquid-glass RING (glass_ring.fsh): the glass material on an annulus - the attack-cooldown track. */
    public static final int RING  = 7;
    /** Flat anti-aliased round-capped ARC (ring_arc.fsh, no backdrop) - the attack-cooldown progress. */
    public static final int ARC   = 8;

    private static final int COUNT = 9;
    private static final String[] FSH_NAME = { "glass", "glass_line", "glass_btn", "menu_blur", "round", "edge", "glass_lens", "glass_ring", "ring_arc" };

    private static final int[] program   = new int[COUNT];
    private static final int[] uSampler0 = new int[COUNT];
    private static final int[] uScreen   = new int[COUNT];
    private static final int[] uModulate = new int[COUNT];
    private static final int[] uRadius   = new int[COUNT];
    private static final int[] uDim      = new int[COUNT];
    private static final int[] uEdgeMode = new int[COUNT];
    private static final int[] uCorner   = new int[COUNT];
    private static final int[] uShadow   = new int[COUNT];
    private static final int[] uArcP     = new int[COUNT];
    private static final int[] uArcT     = new int[COUNT];
    private static final int[] uArcS     = new int[COUNT];
    private static final int[] uArcR     = new int[COUNT];
    private static final int[] uArcG     = new int[COUNT];

    /** Drop-shadow multiplier for the GLASS program (1 = normal, 0 = suppressed).
     *  Persistent: applied on every GLASS bind; the hotbar drops it to 0 while a
     *  screen darkens the background, then restores it, so no other surface is
     *  affected. */
    private static float shadowScale = 1f;

    /** 0 = untried, 1 = ready, -1 = failed (never retry) */
    private static int state = 0;

    private GlassProgram() {}

    public static boolean usable()     { return state == 1 && program[GLASS] != 0; }
    public static boolean ringUsable() { return state == 1 && program[RING] != 0; }
    public static boolean arcUsable()  { return state == 1 && program[ARC]  != 0; }
    public static boolean lineUsable() { return state == 1 && program[LINE]  != 0; }
    public static boolean btnUsable()  { return state == 1 && program[BTN]   != 0; }
    public static boolean blurUsable() { return state == 1 && program[BLUR]  != 0; }
    public static boolean roundUsable(){ return state == 1 && program[ROUND] != 0; }
    public static boolean edgeUsable() { return state == 1 && program[EDGE]  != 0; }
    public static boolean lensUsable() { return state == 1 && program[LENS]  != 0; }

    /** Build all programs once. Returns false if this GPU can't run the glass path. */
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
                + " blur="   + (program[BLUR]  != 0)
                + " round="  + (program[ROUND] != 0)
                + " edge="   + (program[EDGE]  != 0)
                + " lens="   + (program[LENS]  != 0) + ")");
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
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.out.println("[S1mp1e] " + fshName + " link failed: "
                    + GL20.glGetProgramInfoLog(p));
                return 0;
            }
            uSampler0[kind] = GL20.glGetUniformLocation(p, "Sampler0");
            uScreen[kind]   = GL20.glGetUniformLocation(p, "ScreenSize");
            uModulate[kind] = GL20.glGetUniformLocation(p, "ColorModulator");
            uRadius[kind]   = GL20.glGetUniformLocation(p, "Radius");
            uDim[kind]      = GL20.glGetUniformLocation(p, "Dim");
            uEdgeMode[kind] = GL20.glGetUniformLocation(p, "EdgeMode");
            uCorner[kind]   = GL20.glGetUniformLocation(p, "Corner");
            uShadow[kind]   = GL20.glGetUniformLocation(p, "ShadowScale");
            uArcP[kind]      = GL20.glGetUniformLocation(p, "ArcProgress");
            uArcT[kind]      = GL20.glGetUniformLocation(p, "ArcThick");
            uArcS[kind]      = GL20.glGetUniformLocation(p, "ArcShape");
            uArcR[kind]      = GL20.glGetUniformLocation(p, "ArcRatio");
            uArcG[kind]      = GL20.glGetUniformLocation(p, "ArcGap");
            return p;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] " + fshName + " unavailable: " + t);
            return 0;
        }
    }

    /** Bind one program and set its frame-constant uniforms. */
    public static void bind(int kind) {
        GL20.glUseProgram(program[kind]);
        if (uSampler0[kind] >= 0) GL20.glUniform1i(uSampler0[kind], 0);
        // 1.13.2: mc.window is unmapped -> the bridge (framebuffer size, GL-viewport
        // fallback before the Window exists).
        if (uScreen[kind]   >= 0) GL20.glUniform2f(uScreen[kind], Mc1132.fbW(), Mc1132.fbH());
        if (uModulate[kind] >= 0) GL20.glUniform4f(uModulate[kind], 1f, 1f, 1f, 1f);
        // GLASS drop-shadow multiplier — persistent, set every bind so a value the
        // hotbar leaves at 0 can never leak into the panel or inventory glass.
        if ((kind == GLASS || kind == RING) && uShadow[kind] >= 0) GL20.glUniform1f(uShadow[kind], shadowScale);
    }

    public static void unbind() { GL20.glUseProgram(0); }

    /** True when this program samples the captured backdrop. */
    public static boolean needsBackdrop(int kind) { return kind == RING || kind == GLASS || kind == EDGE || kind == LENS; }

    /** Blur-specific uniforms; call right after {@link #bind}. Opaque full-screen backdrop. */
    public static void setBlur(float radiusPx, float dim) {
        if (uRadius[BLUR]   >= 0) GL20.glUniform1f(uRadius[BLUR], radiusPx);
        if (uDim[BLUR]      >= 0) GL20.glUniform1f(uDim[BLUR],    dim);
        if (uEdgeMode[BLUR] >= 0) GL20.glUniform1f(uEdgeMode[BLUR], 0f);
    }

    /** Scroll-edge blur: progressive blur + dim that ramps by vLocal.y, blended over content. */
    public static void setEdgeBlur(float radiusPx, float dim) {
        if (uRadius[BLUR]   >= 0) GL20.glUniform1f(uRadius[BLUR], radiusPx);
        if (uDim[BLUR]      >= 0) GL20.glUniform1f(uDim[BLUR],    dim);
        if (uEdgeMode[BLUR] >= 0) GL20.glUniform1f(uEdgeMode[BLUR], 1f);
    }

    /** Corner scale (0..1 of the half-size) for the ROUND program; call right after {@link #bind}. */
    public static void setCorner(float corner) {
        if (uCorner[ROUND] >= 0) GL20.glUniform1f(uCorner[ROUND], corner);
    }

    /** Scroll-edge uniforms (max blur radius px + max dim) for the dedicated EDGE program
     *  (edge.fsh); call right after {@link #bind}. */
    public static void setEdge(float radiusPx, float dim) {
        if (uRadius[EDGE] >= 0) GL20.glUniform1f(uRadius[EDGE], radiusPx);
        if (uDim[EDGE]    >= 0) GL20.glUniform1f(uDim[EDGE],    dim);
    }

    /** ARC uniforms: progress 0..1 (12 o'clock clockwise) and stroke width / outer radius; call right after
     *  {@link #bind}(ARC). */
    public static void setArc(float progress, float thickFrac, int shape, float ratio) {
        setArc(progress, thickFrac, shape, ratio, 0f);
    }

    public static void setArc(float progress, float thickFrac, int shape, float ratio, float gap) {
        if (uArcP[ARC] >= 0) GL20.glUniform1f(uArcP[ARC], progress);
        if (uArcT[ARC] >= 0) GL20.glUniform1f(uArcT[ARC], thickFrac);
        if (uArcS[ARC] >= 0) GL20.glUniform1f(uArcS[ARC], shape);
        if (uArcR[ARC] >= 0) GL20.glUniform1f(uArcR[ARC], ratio);
        if (uArcG[ARC] >= 0) GL20.glUniform1f(uArcG[ARC], gap);
    }

    /** GLASS drop-shadow multiplier. Persists until changed; the hotbar sets 0
     *  while a screen is open and restores 1 immediately after its own draws. */
    public static void setShadowScale(float s) { shadowScale = s; }

    // ---- helpers ----------------------------------------------------------

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
        // 1.13.2 (Legacy Fabric, no fabric-api resource loader): read the shader
        // straight off the classpath instead of MC's ResourceManager. assets/s1mp1e
        // is unique on the classpath, so this never collides with a vanilla asset.
        String cp = "/assets/" + domain + "/" + path;
        InputStream in = GlassProgram.class.getResourceAsStream(cp);
        if (in == null) throw new java.io.FileNotFoundException(cp);
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
