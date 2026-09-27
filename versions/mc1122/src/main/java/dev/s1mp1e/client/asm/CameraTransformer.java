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
 * Camera / view render patches that no Forge event covers (1.12.2 line). Every
 * splice is STACK-NEUTRAL with no new branch target — an {@code INVOKESTATIC}
 * after a {@code GETFIELD}/{@code I2F}, after a {@code pushMatrix}, or a value
 * wrap in front of an existing {@code IRETURN} — so the existing stack-map frames
 * of these v52 classes stay valid, no frame has to be authored, and
 * {@code COMPUTE_MAXS} alone is enough.
 *
 * <ol>
 *   <li><b>{@code EntityRenderer.updateLightmap}</b> — after every read of
 *       {@code GameSettings.gammaSetting} the float passes through
 *       {@link CameraHooks#gamma(float)} (Fullbright). Identity when off.</li>
 *   <li><b>{@code EntityRenderer.updateCameraAndRender}</b> — after the {@code I2F}
 *       of each {@code MouseHelper.deltaX/deltaY} read, {@link CameraHooks#scaleLook(float)}
 *       scales the player's own look delta while zoomed. Identity otherwise.</li>
 *   <li><b>{@code ItemRenderer.renderItemInFirstPerson(AbstractClientPlayer,F,F,EnumHand,F,ItemStack,F)}</b>
 *       ({@code func_187457_a}, the PER-HAND renderer — not the {@code (F)V} wrapper
 *       {@code func_78440_a}) — after its first {@code GlStateManager.pushMatrix},
 *       {@code ALOAD 4; INVOKESTATIC CameraHooks.handOffset(EnumHand)} translates by the
 *       main- or off-hand offset INSIDE vanilla's per-hand push/pop, so both hands are
 *       live and an offset can never leak across hands.</li>
 *   <li><b>{@code KeyBinding.isKeyDown/isPressed}</b> — "Block other zoom" return-value wraps:
 *       before every {@code IRETURN}, {@code ALOAD 0; INVOKESTATIC
 *       CameraHooks.filterHeld/filterClick(ZKeyBinding)Z}.</li>
 *   <li>the static <b>{@code GameSettings.isKeyDown(KeyBinding)}</b> (OptiFine's zoom funnel) —
 *       the same idea, but its key CANNOT be read at the return: in the SRG form of that class
 *       the frame covering the returns types local 0 as {@code TOP}, and the {@code ALOAD 0}
 *       would be a hard {@code VerifyError: Bad local variable type}. So the key is captured at
 *       METHOD ENTRY ({@code ALOAD 0; INVOKESTATIC CameraHooks.beginKeyQuery}) and each return is
 *       wrapped with the no-local {@code CameraHooks.filterHeldCurrent(Z)Z}.</li>
 * </ol>
 *
 * <p>Splices that LOAD a local at a point other than method entry (the {@code EnumHand} for the
 * hand offset, {@code this} for the two {@code KeyBinding} wraps) are emitted only where the
 * method's own stack-map frames prove that slot still holds the value ({@link AsmFrames});
 * optimised or foreign bytecode may mark a dead parameter {@code TOP}, and loading it would be a
 * VerifyError. Such a site is skipped and logged. Entry captures need no such check: there the
 * locals are the method descriptor by definition.
 *
 * <p>Name-tolerant (MCP names in a dev workspace, SRG after FML's deobf transformer
 * in production) and defensive: each patch is its own try/catch and is skipped
 * loudly rather than thrown, so a mismatch (another coremod or OptiFine rewrote the
 * method) degrades to vanilla behaviour and the event fallbacks, never a crash.
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

    // ItemRenderer.renderItemInFirstPerson(AbstractClientPlayer, float, float, EnumHand, float, ItemStack, float)V
    // locals: this 0, player 1, partialTicks 2, pitch 3, hand 4, swingProgress 5, stack 6, equipProgress 7
    private static final String HAND_MCP  = "renderItemInFirstPerson";
    private static final String HAND_SRG  = "func_187457_a";
    private static final String HAND_DESC =
            "(Lnet/minecraft/client/entity/AbstractClientPlayer;FFLnet/minecraft/util/EnumHand;FLnet/minecraft/item/ItemStack;F)V";
    private static final int HAND_LOCAL = 4;

    // KeyBinding.isKeyDown()Z  (held) / isPressed()Z (drains one queued press)
    private static final String KB_HELD_MCP  = "isKeyDown";
    private static final String KB_HELD_SRG  = "func_151470_d";
    private static final String KB_PRESS_MCP = "isPressed";
    private static final String KB_PRESS_SRG = "func_151468_f";
    private static final String KB_BOOL_DESC = "()Z";
    private static final String KB_TYPE      = "Lnet/minecraft/client/settings/KeyBinding;";

    // GameSettings.isKeyDown(KeyBinding)Z  (static; OptiFine's zoom key funnels through it)
    private static final String GS_ISKEYDOWN_MCP  = "isKeyDown";
    private static final String GS_ISKEYDOWN_SRG  = "func_100015_a";
    private static final String GS_ISKEYDOWN_DESC = "(Lnet/minecraft/client/settings/KeyBinding;)Z";

    // GlStateManager.pushMatrix()V
    private static final String GLSM_OWNER = "net/minecraft/client/renderer/GlStateManager";
    private static final String PUSH_MCP   = "pushMatrix";
    private static final String PUSH_SRG   = "func_179094_E";
    private static final String PUSH_DESC  = "()V";

    private static final String HOOKS = "dev/s1mp1e/client/asm/CameraHooks";

    /** Descriptors of the hook methods, kept next to the names they are emitted with. */
    static final String GAMMA_HOOK_DESC  = "(F)F";
    static final String LOOK_HOOK_DESC   = "(F)F";
    static final String HAND_HOOK_DESC   = "(Lnet/minecraft/util/EnumHand;)V";
    static final String FILTER_HOOK_DESC = "(Z" + KB_TYPE + ")Z";
    /** Entry capture for the static GameSettings.isKeyDown funnel. */
    static final String BEGIN_KEY_DESC   = "(" + KB_TYPE + ")V";
    /** Its return wrap: boolean in, boolean out — reads no local. */
    static final String FILTER_CUR_DESC  = "(Z)Z";

    // ---- per-patch success flags -------------------------------------------
    //
    // These live HERE, not on CameraHooks, on purpose. A transformer that writes a
    // static field of the hook class CLASS-LOADS that hook while it is transforming
    // KeyBinding / GameSettings / EntityRenderer. LaunchClassLoader is re-entered on
    // the same thread during a define, and any type the hook drags in (KeyBinding,
    // the modules, GlStateManager) re-enters findClass — a class-load ordering hazard
    // that, in the worst case, recurses on the very class being transformed and kills
    // the game at startup. The transformer class is already loaded (it IS the
    // transformer), so keeping the flags on it means the transform path touches
    // nothing new. Readers are ordinary runtime code (CameraEvents, CameraHooks).

    /** True once the {@code EntityRenderer.updateLightmap} gamma splice matched at least once. */
    public static volatile boolean gammaPatched = false;
    /** True once the per-hand {@code ItemRenderer.renderItemInFirstPerson} offset splice matched. */
    public static volatile boolean handPatched = false;
    /** True once the mouse-look scaling splice matched. */
    public static volatile boolean lookScalePatched = false;
    /** True once at least one foreign-zoom key filter matched. */
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
        try {
            changed |= spliceFullbrightGamma(cn);
        } catch (Throwable t) {
            gammaPatched = false;
            System.out.println("[S1mp1e/ASM] Fullbright gamma splice failed, using the tick fallback: " + t);
        }
        try {
            changed |= spliceZoomLookScale(cn);
        } catch (Throwable t) {
            lookScalePatched = false;
            System.out.println("[S1mp1e/ASM] Zoom look-scale splice failed, using the MouseHelper fallback: " + t);
        }
        if (!changed) return basic;
        try {
            return write(cn);
        } catch (Throwable t) {
            gammaPatched = false;
            lookScalePatched = false;
            System.out.println("[S1mp1e/ASM] EntityRenderer rewrite failed, left vanilla: " + t);
            return basic;
        }
    }

    /** After every read of {@code GameSettings.gammaSetting} in {@code updateLightmap}, pass the value
     *  through {@link CameraHooks#gamma(float)}. @return true if at least one site was patched. */
    private static boolean spliceFullbrightGamma(ClassNode cn) {
        MethodNode m = find(cn, LIGHTMAP_MCP, LIGHTMAP_SRG, LIGHTMAP_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] EntityRenderer.updateLightmap not found (updateLightmap sites: 0) — Fullbright will use the tick fallback");
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
            post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "gamma", GAMMA_HOOK_DESC, false));
            m.instructions.insert(targets.get(i), post);
        }
        gammaPatched = !targets.isEmpty();
        if (targets.isEmpty()) {
            System.out.println("[S1mp1e/ASM] updateLightmap gamma read not found (updateLightmap sites: 0) — Fullbright will use the tick fallback");
            return false;
        }
        System.out.println("[S1mp1e/ASM] patched EntityRenderer.updateLightmap (updateLightmap sites: " + targets.size() + ")");
        return true;
    }

    /** In {@code updateCameraAndRender}, after the {@code I2F} that converts each
     *  {@code MouseHelper.deltaX/deltaY} read, insert {@link CameraHooks#scaleLook(float)} so the look delta
     *  is scaled by the zoom factor. Expect 2 sites. @return true if at least one site was patched. */
    private static boolean spliceZoomLookScale(ClassNode cn) {
        MethodNode m = find(cn, UCAR_MCP, UCAR_SRG, UCAR_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] EntityRenderer.updateCameraAndRender not found (scaleLook sites: 0) — Zoom uses the MouseHelper fallback");
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
            post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "scaleLook", LOOK_HOOK_DESC, false));
            m.instructions.insert(i2fTargets.get(i), post);
        }
        lookScalePatched = !i2fTargets.isEmpty();
        if (i2fTargets.isEmpty()) {
            System.out.println("[S1mp1e/ASM] updateCameraAndRender mouse-delta reads not found (scaleLook sites: 0) — Zoom uses the MouseHelper fallback");
            return false;
        }
        System.out.println("[S1mp1e/ASM] patched EntityRenderer.updateCameraAndRender (scaleLook sites: " + i2fTargets.size() + ")");
        return true;
    }

    // -----------------------------------------------------------------------
    // 2) ItemRenderer.renderItemInFirstPerson (per-hand, func_187457_a):
    //    hand offset right after its first pushMatrix
    // -----------------------------------------------------------------------
    private static byte[] patchFirstPerson(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, HAND_MCP, HAND_SRG, HAND_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] per-hand ItemRenderer.renderItemInFirstPerson not found (handOffset sites: 0) — HandPosition disabled");
            return basic;
        }

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
        if (firstPush == null) {
            System.out.println("[S1mp1e/ASM] renderItemInFirstPerson first pushMatrix not found (handOffset sites: 0) — HandPosition disabled");
            return basic;
        }
        // The frames must prove local 4 still holds the EnumHand there (see AsmFrames).
        MethodNode x = AsmFrames.expanded(basic, m.name, m.desc);
        if (!AsmFrames.holds(m, x, firstPush, HAND_LOCAL, AsmFrames.REF)) {
            System.out.println("[S1mp1e/ASM] renderItemInFirstPerson: EnumHand local not provably live after pushMatrix (handOffset sites: 0) — HandPosition disabled");
            return basic;
        }

        // ALOAD 4 (the EnumHand); INVOKESTATIC CameraHooks.handOffset(EnumHand)V.
        // Pushes one reference and consumes it: stack-neutral, no branch, no frame.
        InsnList post = new InsnList();
        post.add(new VarInsnNode(Opcodes.ALOAD, HAND_LOCAL));
        post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "handOffset", HAND_HOOK_DESC, false));
        m.instructions.insert(firstPush, post);

        byte[] out;
        try {
            out = write(cn);
        } catch (Throwable t) {
            handPatched = false;
            System.out.println("[S1mp1e/ASM] ItemRenderer rewrite failed, HandPosition disabled: " + t);
            return basic;
        }
        handPatched = true;
        System.out.println("[S1mp1e/ASM] patched ItemRenderer." + m.name + " (handOffset sites: 1)");
        return out;
    }

    // -----------------------------------------------------------------------
    // 3) KeyBinding.isKeyDown / isPressed — "Block other zoom" key filters
    // -----------------------------------------------------------------------
    private static byte[] patchKeyBinding(byte[] basic) {
        ClassNode cn = read(basic);
        int held  = wrapReturns(basic, find(cn, KB_HELD_MCP,  KB_HELD_SRG,  KB_BOOL_DESC), "filterHeld");
        int click = wrapReturns(basic, find(cn, KB_PRESS_MCP, KB_PRESS_SRG, KB_BOOL_DESC), "filterClick");
        if (held == 0 && click == 0) {
            System.out.println("[S1mp1e/ASM] KeyBinding.isKeyDown/isPressed: no wrappable return site, skipping Block-other-zoom");
            return basic;
        }
        if (held == 0)  System.out.println("[S1mp1e/ASM] KeyBinding.isKeyDown: no wrappable return site (held filter not applied)");
        if (click == 0) System.out.println("[S1mp1e/ASM] KeyBinding.isPressed: no wrappable return site (click filter not applied)");
        byte[] out;
        try {
            out = write(cn);
        } catch (Throwable t) {
            System.out.println("[S1mp1e/ASM] KeyBinding rewrite failed, Block-other-zoom inactive there: " + t);
            return basic;
        }
        keyFilterPatched = true;
        System.out.println("[S1mp1e/ASM] patched KeyBinding.isKeyDown (returns: " + held
                + "), KeyBinding.isPressed (returns: " + click + ")");
        return out;
    }

    // -----------------------------------------------------------------------
    // 4) GameSettings.isKeyDown(KeyBinding) — static funnel used by OptiFine's zoom
    // -----------------------------------------------------------------------
    private static byte[] patchGameSettings(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, GS_ISKEYDOWN_MCP, GS_ISKEYDOWN_SRG, GS_ISKEYDOWN_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] GameSettings.isKeyDown not found (returns: 0), skipping Block-other-zoom funnel");
            return basic;
        }
        // Capture the key argument at METHOD ENTRY, then wrap the returns with a hook that takes
        // only the boolean. MEASURED, not assumed: in the SRG form of GameSettings the frame
        // covering these returns types local 0 as TOP (the parameter is dead by then), so the
        // ALOAD 0 the two KeyBinding methods can use here is a hard "VerifyError: Bad local
        // variable type" that kills GameSettings at class load. Entry locals are the descriptor
        // in every bytecode shape, so the capture is frame-proof. Both splices are stack-neutral
        // and add no branch target, so no stack-map frame changes.
        int sites = wrapReturnsNoArg(m, "filterHeldCurrent");
        if (sites == 0) {
            System.out.println("[S1mp1e/ASM] GameSettings.isKeyDown: no return site found (returns: 0), skipping Block-other-zoom funnel");
            return basic;
        }
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));   // the KeyBinding argument (static method)
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginKeyQuery", BEGIN_KEY_DESC, false));
        m.instructions.insert(head);
        byte[] out;
        try {
            out = write(cn);
        } catch (Throwable t) {
            System.out.println("[S1mp1e/ASM] GameSettings rewrite failed, Block-other-zoom funnel inactive: " + t);
            return basic;
        }
        keyFilterPatched = true;
        System.out.println("[S1mp1e/ASM] patched GameSettings.isKeyDown (returns: " + sites + ")");
        return out;
    }

    /**
     * Before every {@code IRETURN} of {@code m}, insert {@code ALOAD 0; INVOKESTATIC CameraHooks.<hook>(Z,
     * KeyBinding)Z}. The boolean the method was about to return is already on the stack; we push the
     * {@code KeyBinding} (local 0 — {@code this} for the two instance methods, the single argument for the
     * static {@code GameSettings.isKeyDown}) and let the hook rewrite the boolean. Inserted AFTER any frame
     * that precedes the {@code IRETURN} (a join point keeps its frame, which still describes the stack at our
     * first instruction), with no new branch target, so no stack-map frame changes.
     *
     * <p>A return site is wrapped only if the method's own frames prove local 0 still holds the key there
     * ({@link AsmFrames}); optimised bytecode may mark it dead after its last use, and loading it would be a
     * VerifyError. Such a site is left unwrapped (that return just is not filtered).
     * @return the number of return sites wrapped (0 if {@code m} is null).
     */
    private static int wrapReturns(byte[] basic, MethodNode m, String hook) {
        if (m == null) return 0;
        MethodNode x = AsmFrames.expanded(basic, m.name, m.desc);
        List<AbstractInsnNode> rets = new ArrayList<AbstractInsnNode>();
        int unsafe = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.IRETURN) continue;
            if (AsmFrames.holds(m, x, insn, 0, AsmFrames.REF)) rets.add(insn);
            else unsafe++;
        }
        if (unsafe > 0) {
            System.out.println("[S1mp1e/ASM] " + m.name + ": " + unsafe
                    + " return site(s) left unwrapped (key local not provably live there)");
        }
        for (int i = 0; i < rets.size(); i++) {
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, FILTER_HOOK_DESC, false));
            m.instructions.insertBefore(rets.get(i), pre);
        }
        return rets.size();
    }

    /**
     * Before every {@code IRETURN} of {@code m}, insert {@code INVOKESTATIC CameraHooks.<hook>(Z)Z}.
     * The boolean the method was about to return is already on the stack and the hook rewrites it,
     * reading nothing else — so, unlike {@link #wrapReturns}, this works no matter what the method's
     * frames say about its locals at that point. Stack-neutral with no new branch target, so the
     * existing stack-map frames stay valid.
     * @return the number of return sites wrapped.
     */
    private static int wrapReturnsNoArg(MethodNode m, String hook) {
        if (m == null) return 0;
        List<AbstractInsnNode> rets = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.IRETURN) rets.add(insn);
        }
        for (int i = 0; i < rets.size(); i++) {
            InsnList pre = new InsnList();
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, FILTER_CUR_DESC, false));
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

    /** Resolve a field's actual name (MCP in dev, SRG in production), or null. Kept for patches that
     *  need to emit a field access by its runtime name. */
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
        // COMPUTE_MAXS only, never COMPUTE_FRAMES. Every splice here is stack-neutral
        // with no new branch target, so the class's own stack-map frames stay valid;
        // COMPUTE_FRAMES would make ASM load MC classes mid-transform
        // (getCommonSuperClass) and can deadlock LaunchClassLoader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
