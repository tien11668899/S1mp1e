package dev.s1mp1e.client.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Camera / view render patches that no Forge event covers. All splices are stack
 * neutral (no branches added), so COMPUTE_MAXS alone is enough and no stack-map
 * frames are needed — the same discipline as {@link CombatTransformer}.
 *
 * <ol>
 *   <li><b>{@code EntityRenderer.updateLightmap}</b> — after every read of
 *       {@code GameSettings.gammaSetting} the float is passed through
 *       {@link CameraHooks#gamma(float)}, so Fullbright can raise the lightmap gamma
 *       past the option's 0..1 clamp. Identity when the module is off.</li>
 *   <li><b>{@code ItemRenderer.renderItemInFirstPerson}</b> — a head splice records
 *       the partial ticks ({@link CameraHooks#beginFirstPerson(float)}), and after the
 *       first {@code GlStateManager.pushMatrix} a {@link CameraHooks#handOffset()} call
 *       nudges the drawn hand for HandPosition. Both are no-ops when their modules are
 *       off.</li>
 * </ol>
 *
 * <p>Name-tolerant (MCP names in a dev workspace, SRG in production) and defensive:
 * every patch is skipped loudly rather than throwing, so a name mismatch degrades to
 * vanilla behaviour instead of crashing startup. Per-patch success is recorded in
 * {@link CameraHooks} so event-based fallbacks can tell whether a splice went live.
 */
public final class CameraTransformer implements IClassTransformer {

    // ---- targets ----------------------------------------------------------

    private static final String ENTITY_RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String ITEM_RENDERER   = "net.minecraft.client.renderer.ItemRenderer";
    private static final String KEY_BINDING     = "net.minecraft.client.settings.KeyBinding";
    private static final String GAME_SETTINGS   = "net.minecraft.client.settings.GameSettings";

    // EntityRenderer.updateLightmap(float)V
    private static final String LIGHTMAP_MCP  = "updateLightmap";
    private static final String LIGHTMAP_SRG  = "func_78472_g";
    private static final String LIGHTMAP_DESC = "(F)V";

    // GameSettings.gammaSetting : F
    private static final String GS_OWNER    = "net/minecraft/client/settings/GameSettings";
    private static final String GAMMA_MCP   = "gammaSetting";
    private static final String GAMMA_SRG   = "field_74333_Y";
    private static final String GAMMA_DESC  = "F";

    // EntityRenderer.updateCameraAndRender(float, long)V — where the mouse-look delta is applied
    private static final String UCAR_MCP  = "updateCameraAndRender";
    private static final String UCAR_SRG  = "func_181560_a";
    private static final String UCAR_DESC = "(FJ)V";

    // MouseHelper.deltaX / deltaY : I
    private static final String MOUSE_OWNER = "net/minecraft/util/MouseHelper";
    private static final String DELTAX_MCP  = "deltaX";
    private static final String DELTAX_SRG  = "field_74377_a";
    private static final String DELTAY_MCP  = "deltaY";
    private static final String DELTAY_SRG  = "field_74375_b";
    private static final String DELTA_DESC  = "I";

    // ItemRenderer.renderItemInFirstPerson(float)V
    private static final String FIRSTPERSON_MCP  = "renderItemInFirstPerson";
    private static final String FIRSTPERSON_SRG  = "func_78440_a";
    private static final String FIRSTPERSON_DESC = "(F)V";

    // KeyBinding.isKeyDown()Z  (held) / isPressed()Z (drains one queued press)
    private static final String KB_HELD_MCP  = "isKeyDown";
    private static final String KB_HELD_SRG  = "func_151470_d";
    private static final String KB_PRESS_MCP = "isPressed";
    private static final String KB_PRESS_SRG = "func_151468_f";
    private static final String KB_BOOL_DESC = "()Z";
    private static final String KB_TYPE      = "Lnet/minecraft/client/settings/KeyBinding;";

    // GameSettings.isKeyDown(KeyBinding)Z  (static; OptiFine / Patcher zoom funnel through it)
    private static final String GS_ISKEYDOWN_MCP  = "isKeyDown";
    private static final String GS_ISKEYDOWN_SRG  = "func_100015_a";
    private static final String GS_ISKEYDOWN_DESC = "(Lnet/minecraft/client/settings/KeyBinding;)Z";

    // GlStateManager.pushMatrix()V
    private static final String GLSM_OWNER = "net/minecraft/client/renderer/GlStateManager";
    private static final String PUSH_MCP   = "pushMatrix";
    private static final String PUSH_SRG   = "func_179094_E";
    private static final String PUSH_DESC  = "()V";

    private static final String HOOKS = "dev/s1mp1e/client/asm/CameraHooks";

    // ---- per-patch success flags -------------------------------------------
    //
    // These live HERE, not on CameraHooks, on purpose. A transformer that writes a
    // static field of the hook class CLASS-LOADS that hook while it is transforming
    // KeyBinding / GameSettings / EntityRenderer. LaunchClassLoader is re-entered on
    // the same thread during a define, and any type the hook drags in re-enters
    // findClass — a class-load ordering hazard that, in the worst case, recurses on
    // the very class being transformed and kills the game at startup. The transformer
    // class is already loaded (it IS the transformer), so keeping the flags on it
    // means the transform path touches nothing new. Readers are ordinary runtime
    // code (CameraEvents, CameraHooks) and load this class the normal way.

    /** True once the {@code EntityRenderer.updateLightmap} gamma splice matched at least once. */
    public static volatile boolean gammaPatched = false;
    /** True once the {@code ItemRenderer.renderItemInFirstPerson} hand splice matched. */
    public static volatile boolean handPatched = false;
    /** True once the mouse-look scaling splice matched (Zoom package). */
    public static volatile boolean lookScalePatched = false;
    /** True once the foreign-zoom key filters matched (Zoom package). */
    public static volatile boolean keyFilterPatched = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (ENTITY_RENDERER.equals(transformedName)) {
                return patchEntityRenderer(basicClass);
            }
            if (ITEM_RENDERER.equals(transformedName)) {
                return patchFirstPerson(basicClass);
            }
            if (KEY_BINDING.equals(transformedName)) {
                return patchKeyBinding(basicClass);
            }
            if (GAME_SETTINGS.equals(transformedName)) {
                return patchGameSettings(basicClass);
            }
        } catch (Throwable t) {
            // Never take the game down over a failed patch — fall back to vanilla.
            System.out.println("[S1mp1e/ASM] camera patch of " + transformedName + " failed: " + t);
        }
        return basicClass;
    }

    // -----------------------------------------------------------------------
    // 1) EntityRenderer: Fullbright gamma read + Zoom mouse-look scaling
    //    (both live in EntityRenderer, so read/write the class once)
    // -----------------------------------------------------------------------
    private static byte[] patchEntityRenderer(byte[] basic) {
        ClassNode cn = read(basic);
        boolean changed = false;
        changed |= spliceFullbrightGamma(cn);
        changed |= spliceZoomLookScale(cn);
        return changed ? write(cn) : basic;
    }

    /** After every read of {@code GameSettings.gammaSetting} in {@code updateLightmap}, pass the value
     *  through {@link CameraHooks#gamma(float)}. @return true if at least one site was patched. */
    private static boolean spliceFullbrightGamma(ClassNode cn) {
        MethodNode m = find(cn, LIGHTMAP_MCP, LIGHTMAP_SRG, LIGHTMAP_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] EntityRenderer.updateLightmap not found, skipping Fullbright");
            return false;
        }
        // Collect the gamma reads first, then splice, so we never trip over nodes we
        // just inserted while iterating.
        List<FieldInsnNode> targets = new ArrayList<FieldInsnNode>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.GETFIELD) continue;
            FieldInsnNode f = (FieldInsnNode) insn;
            if (!GS_OWNER.equals(f.owner)) continue;
            if (!(GAMMA_MCP.equals(f.name) || GAMMA_SRG.equals(f.name))) continue;
            if (!GAMMA_DESC.equals(f.desc)) continue;
            targets.add(f);
        }
        for (int i = 0; i < targets.size(); i++) {
            InsnList post = new InsnList();
            post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "gamma", "(F)F", false));
            m.instructions.insert(targets.get(i), post);
        }
        gammaPatched = !targets.isEmpty();
        if (targets.isEmpty()) {
            System.out.println("[S1mp1e/ASM] updateLightmap gamma read not found (sites: 0) — Fullbright will use the tick fallback");
            return false;
        }
        System.out.println("[S1mp1e/ASM] patched EntityRenderer.updateLightmap (Fullbright gamma sites: " + targets.size() + ")");
        return true;
    }

    /** In {@code updateCameraAndRender}, after the {@code I2F} that converts each
     *  {@code MouseHelper.deltaX/deltaY} read, insert {@link CameraHooks#scaleLook(float)} so the look delta
     *  is scaled by the zoom factor. Expect 2 sites. @return true if at least one site was patched. */
    private static boolean spliceZoomLookScale(ClassNode cn) {
        MethodNode m = find(cn, UCAR_MCP, UCAR_SRG, UCAR_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] EntityRenderer.updateCameraAndRender not found, skipping Zoom look-scale");
            return false;
        }
        // Collect the I2F nodes to splice after, first, then insert, so newly inserted
        // nodes never confuse the scan.
        List<AbstractInsnNode> i2fTargets = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.GETFIELD) continue;
            FieldInsnNode f = (FieldInsnNode) insn;
            if (!MOUSE_OWNER.equals(f.owner)) continue;
            boolean isDelta = (DELTAX_MCP.equals(f.name) || DELTAX_SRG.equals(f.name)
                            || DELTAY_MCP.equals(f.name) || DELTAY_SRG.equals(f.name))
                            && DELTA_DESC.equals(f.desc);
            if (!isDelta) continue;
            AbstractInsnNode nxt = nextReal(f);
            if (nxt == null || nxt.getOpcode() != Opcodes.I2F) continue;   // only the "(float)delta * f" reads
            i2fTargets.add(nxt);
        }
        for (int i = 0; i < i2fTargets.size(); i++) {
            InsnList post = new InsnList();
            post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "scaleLook", "(F)F", false));
            m.instructions.insert(i2fTargets.get(i), post);
        }
        lookScalePatched = !i2fTargets.isEmpty();
        if (i2fTargets.isEmpty()) {
            System.out.println("[S1mp1e/ASM] updateCameraAndRender mouse-delta reads not found (scaleLook sites: 0) — Zoom look-scale disabled");
            return false;
        }
        System.out.println("[S1mp1e/ASM] patched EntityRenderer.updateCameraAndRender (Zoom scaleLook sites: " + i2fTargets.size() + ")");
        return true;
    }

    // -----------------------------------------------------------------------
    // 2) ItemRenderer.renderItemInFirstPerson: head partial-ticks capture +
    //    hand offset after the first pushMatrix
    // -----------------------------------------------------------------------
    private static byte[] patchFirstPerson(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, FIRSTPERSON_MCP, FIRSTPERSON_SRG, FIRSTPERSON_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] ItemRenderer.renderItemInFirstPerson not found, skipping HandPosition");
            return basic;
        }

        // (a) HEAD: FLOAD 1; INVOKESTATIC CameraHooks.beginFirstPerson(F)V.
        // Instance method, desc (F)V -> local 0 = this, local 1 = partialTicks.
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.FLOAD, 1));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginFirstPerson", "(F)V", false));
        m.instructions.insert(head);

        // (b) After the FIRST GlStateManager.pushMatrix()V: INVOKESTATIC handOffset()V.
        MethodInsnNode firstPush = null;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!GLSM_OWNER.equals(call.owner)) continue;
            if (!(PUSH_MCP.equals(call.name) || PUSH_SRG.equals(call.name))) continue;
            if (!PUSH_DESC.equals(call.desc)) continue;
            firstPush = call;
            break;
        }
        boolean offset = false;
        if (firstPush != null) {
            InsnList post = new InsnList();
            post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "handOffset", "()V", false));
            m.instructions.insert(firstPush, post);
            offset = true;
        } else {
            System.out.println("[S1mp1e/ASM] renderItemInFirstPerson first pushMatrix not found — HandPosition offset disabled");
        }

        handPatched = offset;
        System.out.println("[S1mp1e/ASM] patched ItemRenderer.renderItemInFirstPerson (head capture + hand offset: " + offset + ", sites: 1)");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 3) KeyBinding.isKeyDown / isPressed — "Block other zoom" key filters
    // -----------------------------------------------------------------------
    private static byte[] patchKeyBinding(byte[] basic) {
        ClassNode cn = read(basic);
        int held  = wrapReturns(find(cn, KB_HELD_MCP,  KB_HELD_SRG,  KB_BOOL_DESC), "filterHeld");
        int click = wrapReturns(find(cn, KB_PRESS_MCP, KB_PRESS_SRG, KB_BOOL_DESC), "filterClick");
        if (held == 0 && click == 0) {
            System.out.println("[S1mp1e/ASM] KeyBinding.isKeyDown/isPressed not found, skipping Block-other-zoom");
            return basic;
        }
        if (held == 0)  System.out.println("[S1mp1e/ASM] KeyBinding.isKeyDown not found (held filter not applied)");
        if (click == 0) System.out.println("[S1mp1e/ASM] KeyBinding.isPressed not found (click filter not applied)");
        keyFilterPatched = true;
        System.out.println("[S1mp1e/ASM] patched KeyBinding.isKeyDown (returns: " + held
                + "), KeyBinding.isPressed (returns: " + click + ")");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 4) GameSettings.isKeyDown(KeyBinding) — static funnel used by OptiFine/Patcher zoom
    // -----------------------------------------------------------------------
    private static byte[] patchGameSettings(byte[] basic) {
        ClassNode cn = read(basic);
        int sites = wrapReturns(find(cn, GS_ISKEYDOWN_MCP, GS_ISKEYDOWN_SRG, GS_ISKEYDOWN_DESC), "filterHeld");
        if (sites == 0) {
            System.out.println("[S1mp1e/ASM] GameSettings.isKeyDown not found, skipping Block-other-zoom funnel");
            return basic;
        }
        keyFilterPatched = true;
        System.out.println("[S1mp1e/ASM] patched GameSettings.isKeyDown (returns: " + sites + ")");
        return write(cn);
    }

    /**
     * Before every {@code IRETURN} of {@code m}, insert {@code ALOAD 0; INVOKESTATIC CameraHooks.<hook>(Z,
     * KeyBinding)Z}. The boolean the method was about to return is already on the stack; we push the
     * {@code KeyBinding} (local 0 — {@code this} for the two instance methods, the single argument for the
     * static {@code GameSettings.isKeyDown}) and let the hook rewrite the boolean. Stack-neutral with no new
     * branch, so no stack-map frames are needed. @return the number of return sites wrapped (0 if {@code m} is null).
     */
    private static int wrapReturns(MethodNode m, String hook) {
        if (m == null) return 0;
        // Gather the IRETURN nodes first; inserting before a node we are iterating past is safe, but
        // collecting keeps it obvious we only touch the original returns.
        List<AbstractInsnNode> rets = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.IRETURN) rets.add(insn);
        }
        for (int i = 0; i < rets.size(); i++) {
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, "(Z" + KB_TYPE + ")Z", false));
            m.instructions.insertBefore(rets.get(i), pre);
        }
        return rets.size();
    }

    // ---- helpers ----------------------------------------------------------

    /** The next instruction that actually executes, skipping labels, line numbers and frames. */
    private static AbstractInsnNode nextReal(AbstractInsnNode n) {
        AbstractInsnNode c = n.getNext();
        while (c != null && (c.getType() == AbstractInsnNode.LABEL
                          || c.getType() == AbstractInsnNode.LINE
                          || c.getType() == AbstractInsnNode.FRAME)) {
            c = c.getNext();
        }
        return c;
    }

    private static MethodNode find(ClassNode cn, String mcp, String srg, String desc) {
        for (MethodNode m : cn.methods) {
            if (!desc.equals(m.desc)) continue;
            if (mcp.equals(m.name) || srg.equals(m.name)) return m;
        }
        return null;
    }

    /** Resolve a private field's actual name (MCP in dev, SRG in production). Used by
     *  later camera packages (e.g. the KeyBinding queue drain); kept here so the
     *  scaffolding is complete. */
    @SuppressWarnings("unused")
    private static String findField(ClassNode cn, String mcp, String srg, String desc) {
        for (FieldNode f : cn.fields) {
            if (desc != null && !desc.equals(f.desc)) continue;
            if (mcp.equals(f.name) || srg.equals(f.name)) return f.name;
        }
        return null;
    }

    private static ClassNode read(byte[] basic) {
        ClassNode cn = new ClassNode();
        new ClassReader(basic).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        // COMPUTE_MAXS only. Every splice is stack-neutral with no new branches, so
        // the lenient v50 verifier needs no stack-map frames, and COMPUTE_FRAMES would
        // make ASM load MC classes mid-transform (getCommonSuperClass) and can deadlock
        // LaunchClassLoader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
