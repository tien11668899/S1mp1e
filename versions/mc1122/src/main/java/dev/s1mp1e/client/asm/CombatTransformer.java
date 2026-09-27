package dev.s1mp1e.client.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Render-only combat-feel patches (1.12.2 line). Three surgical rewrites, all against
 * RENDERING classes only (EntityRenderer, RenderLivingBase, ItemRenderer) so the
 * coremod stays auditable and nothing here can touch movement, attacks, blocking
 * timing or the network.
 *
 * <ol>
 *   <li><b>{@code EntityRenderer.hurtCameraEffect}</b> — a
 *       {@code if (CombatHooks.noHurtCam()) return;} guard on the method head. Both
 *       camera passes call this one method, so one splice removes the hurt shake.</li>
 *   <li><b>{@code RenderLivingBase.setBrightness}</b> (1.8.9's RendererLivingEntity) — a
 *       head guard makes the method return {@code false} on a hurt/death flash frame,
 *       which suppresses the red tint; every caller keys its {@code unsetBrightness}
 *       off the same false return, so the GL state stays balanced.</li>
 *   <li><b>{@code ItemRenderer.renderItemInFirstPerson(AbstractClientPlayer,F,F,EnumHand,F,ItemStack,F)}</b>
 *       ({@code func_187457_a}) — a PAIR of stack-neutral splices: at the method head
 *       {@code ALOAD 1; ALOAD 4; FLOAD 5; INVOKESTATIC CombatHooks.beginFirstPerson} captures
 *       the arguments, and immediately before the {@code renderItemSide} call a NO-ARG
 *       {@code INVOKESTATIC CombatHooks.swingWhileUsing} consumes them. The hook adds vanilla's
 *       swing transform in exactly the frames 1.12.2 drops it (main hand, item in use). 1.8.9's
 *       {@code transformFirstPersonItem} retarget has no 1.12.2 counterpart
 *       ({@code func_178096_b} does not exist) and is not ported.</li>
 * </ol>
 *
 * <p><b>Why the swing hook is split in two.</b> The obvious shape — read the locals straight
 * off the frame at the {@code renderItemSide} call — is not viable, and that was measured
 * rather than assumed. In the SRG form of this class (the obfuscator's own minimal stack-map
 * frames, which FML's deobfuscating remapper hands through unchanged) the frame covering that
 * call declares NO live locals: the parameters are dead by then. The reference ALOADs there
 * are a hard {@code VerifyError: Bad local variable type ... Type top is not assignable to
 * reference type} that kills {@code ItemRenderer} at class load. At METHOD ENTRY the locals
 * are the method descriptor by definition, in every bytecode shape, so the capture goes there
 * and the call site reads no local at all.
 *
 * <p><b>Stack-map frames.</b> 1.12.2 classes are v52, whose verifier REQUIRES a frame at
 * every branch target. The two head guards add an {@code IFEQ} to a fresh label, so each
 * gets a hand-authored {@code FrameNode(F_SAME)} right after that label (locals = the
 * method's arguments, empty stack — exactly the entry state). If the original method
 * already has a frame at its entry offset, that one is kept and no second frame is added
 * at the same offset. Neither swing splice adds a branch target, so no frame is authored
 * for them and the class's own frames stay valid.
 *
 * <p>Name-tolerant (MCP in dev, SRG in production) and defensive: each patch is skipped
 * loudly rather than thrown, so a mismatch degrades to vanilla instead of a crash.
 */
public final class CombatTransformer implements IClassTransformer {

    // ---- targets ----------------------------------------------------------

    private static final String ENTITY_RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String LIVING_RENDERER = "net.minecraft.client.renderer.entity.RenderLivingBase";
    private static final String ITEM_RENDERER   = "net.minecraft.client.renderer.ItemRenderer";

    // hurtCameraEffect(float)V
    private static final String HURT_CAM_MCP  = "hurtCameraEffect";
    private static final String HURT_CAM_SRG  = "func_78482_e";
    private static final String HURT_CAM_DESC = "(F)V";

    // setBrightness(EntityLivingBase, float, boolean)Z  (T erased to EntityLivingBase)
    private static final String BRIGHT_MCP  = "setBrightness";
    private static final String BRIGHT_SRG  = "func_177092_a";
    private static final String BRIGHT_DESC = "(Lnet/minecraft/entity/EntityLivingBase;FZ)Z";

    // renderItemInFirstPerson(AbstractClientPlayer, float, float, EnumHand, float, ItemStack, float)V
    // locals: this 0, player 1, partialTicks 2, pitch 3, hand 4, swingProgress 5, stack 6, equipProgress 7
    private static final String HAND_MCP  = "renderItemInFirstPerson";
    private static final String HAND_SRG  = "func_187457_a";
    private static final String HAND_DESC =
            "(Lnet/minecraft/client/entity/AbstractClientPlayer;FFLnet/minecraft/util/EnumHand;FLnet/minecraft/item/ItemStack;F)V";
    private static final int PLAYER_LOCAL = 1;
    private static final int HAND_LOCAL   = 4;
    private static final int SWING_LOCAL  = 5;

    // renderItemSide(EntityLivingBase, ItemStack, TransformType, boolean)V
    private static final String ITEM_RENDERER_OWNER = "net/minecraft/client/renderer/ItemRenderer";
    private static final String SIDE_MCP  = "renderItemSide";
    private static final String SIDE_SRG  = "func_187462_a";
    private static final String SIDE_DESC =
            "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;Z)V";

    private static final String HOOKS = "dev/s1mp1e/client/asm/CombatHooks";

    /** Hook descriptors, next to the names they are emitted with. */
    static final String NO_HURT_CAM_DESC = "()Z";
    static final String HURT_FLASH_DESC  = "(Lnet/minecraft/entity/EntityLivingBase;)Z";
    /** Entry capture: (player, hand, swingProgress) — locals 1, 4, 5 at method entry. */
    static final String BEGIN_FP_DESC =
            "(Lnet/minecraft/client/entity/AbstractClientPlayer;Lnet/minecraft/util/EnumHand;F)V";
    /** Call-site hook: no arguments, so it needs no local to be live there. */
    static final String SWING_DESC = "()V";

    // Per-patch success flags. Kept on the transformer (never on the hook class) so the
    // transform path class-loads nothing new — see CameraTransformer for the hazard.

    /** True once the {@code hurtCameraEffect} head guard went in. */
    public static volatile boolean hurtCamPatched = false;
    /** True once the {@code RenderLivingBase.setBrightness} head guard went in. */
    public static volatile boolean hurtFlashPatched = false;
    /** True once the swing-while-using splice went in. */
    public static volatile boolean swingPatched = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (ENTITY_RENDERER.equals(transformedName)) {
                return patchHurtCam(basicClass);
            }
            if (LIVING_RENDERER.equals(transformedName)) {
                return patchHurtFlash(basicClass);
            }
            if (ITEM_RENDERER.equals(transformedName)) {
                return patchOldAnimations(basicClass);
            }
        } catch (Throwable t) {
            // Never take the game down over a failed patch — fall back to vanilla.
            System.out.println("[S1mp1e/ASM] combat patch of " + transformedName + " failed: " + t);
        }
        return basicClass;
    }

    // -----------------------------------------------------------------------
    // 1) EntityRenderer.hurtCameraEffect -> early return when suppressed
    // -----------------------------------------------------------------------
    private static byte[] patchHurtCam(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, HURT_CAM_MCP, HURT_CAM_SRG, HURT_CAM_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] EntityRenderer.hurtCameraEffect not found, skipping NoHurtCam shake");
            return basic;
        }
        // if (CombatHooks.noHurtCam()) return;
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "noHurtCam", NO_HURT_CAM_DESC, false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        insertHeadGuard(m, pre, pass);
        byte[] out;
        try {
            out = write(cn);
        } catch (Throwable t) {
            System.out.println("[S1mp1e/ASM] EntityRenderer rewrite (hurtCameraEffect) failed, left vanilla: " + t);
            return basic;
        }
        hurtCamPatched = true;
        System.out.println("[S1mp1e/ASM] patched EntityRenderer." + m.name + " (hurtCameraEffect head guard)");
        return out;
    }

    // -----------------------------------------------------------------------
    // 2) RenderLivingBase.setBrightness -> return false on a hurt/death frame
    // -----------------------------------------------------------------------
    private static byte[] patchHurtFlash(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, BRIGHT_MCP, BRIGHT_SRG, BRIGHT_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] RenderLivingBase.setBrightness not found, skipping NoHurtCam flash");
            return basic;
        }
        // if (CombatHooks.suppressHurtFlash(entitylivingbaseIn)) return false;
        // entitylivingbaseIn is the first (index 1) argument of this instance method.
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "suppressHurtFlash", HURT_FLASH_DESC, false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.ICONST_0));   // false
        pre.add(new InsnNode(Opcodes.IRETURN));
        insertHeadGuard(m, pre, pass);
        byte[] out;
        try {
            out = write(cn);
        } catch (Throwable t) {
            System.out.println("[S1mp1e/ASM] RenderLivingBase rewrite failed, left vanilla: " + t);
            return basic;
        }
        hurtFlashPatched = true;
        System.out.println("[S1mp1e/ASM] patched RenderLivingBase." + m.name + " (setBrightness head guard)");
        return out;
    }

    // -----------------------------------------------------------------------
    // 3) ItemRenderer.renderItemInFirstPerson (per-hand): swing-while-using
    //    call right before renderItemSide
    // -----------------------------------------------------------------------
    private static byte[] patchOldAnimations(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, HAND_MCP, HAND_SRG, HAND_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] per-hand ItemRenderer.renderItemInFirstPerson not found (swing sites: 0) — OldAnimations disabled");
            return basic;
        }
        List<AbstractInsnNode> sites = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!ITEM_RENDERER_OWNER.equals(call.owner)) continue;
            if (!(SIDE_MCP.equals(call.name) || SIDE_SRG.equals(call.name))) continue;
            if (!SIDE_DESC.equals(call.desc)) continue;
            sites.add(call);
        }
        if (sites.isEmpty()) {
            System.out.println("[S1mp1e/ASM] renderItemSide call not found in renderItemInFirstPerson"
                    + " (swing sites: 0) — OldAnimations disabled");
            return basic;
        }

        // Two stack-neutral splices, neither of which reads a local at the call site.
        //
        // MEASURED, not assumed: in the SRG form of this class (the obfuscator's own minimal
        // stack-map frames, which FML's remapper passes through unchanged) the frame covering
        // the renderItemSide call declares NO live locals. Emitting ALOAD 0/1/4 + FLOAD 5 there
        // — the shape the port spec sketches — is a hard "VerifyError: Bad local variable type
        // ... Type top is not assignable to reference type" that kills ItemRenderer at class
        // load, which was confirmed against the real class bytes. So:
        //
        //   1) at METHOD ENTRY, where the locals are the descriptor by definition in every
        //      bytecode shape, capture (player, hand, swingProgress) into CombatHooks;
        //   2) before renderItemSide, call the NO-ARG hook, which reads what (1) captured.
        //
        // Neither adds a branch target, so the existing frames stay valid and none is authored.
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, PLAYER_LOCAL));
        head.add(new VarInsnNode(Opcodes.ALOAD, HAND_LOCAL));
        head.add(new VarInsnNode(Opcodes.FLOAD, SWING_LOCAL));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beginFirstPerson", BEGIN_FP_DESC, false));
        m.instructions.insert(head);

        for (int i = 0; i < sites.size(); i++) {
            InsnList pre = new InsnList();
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "swingWhileUsing", SWING_DESC, false));
            m.instructions.insertBefore(sites.get(i), pre);
        }
        byte[] out;
        try {
            out = write(cn);
        } catch (Throwable t) {
            System.out.println("[S1mp1e/ASM] ItemRenderer rewrite (swing) failed, left vanilla: " + t);
            return basic;
        }
        swingPatched = true;
        System.out.println("[S1mp1e/ASM] patched ItemRenderer." + m.name + " (swing sites: " + sites.size() + ")");
        return out;
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Insert {@code guard} (which ends with its early return) followed by {@code pass} and, unless
     * the method already carries a frame at its entry offset, an {@code F_SAME} frame — the state
     * at the pass label is exactly the method-entry state (arguments only, empty stack).
     */
    private static void insertHeadGuard(MethodNode m, InsnList guard, LabelNode pass) {
        boolean entryFrame = false;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            int type = n.getType();
            if (type == AbstractInsnNode.FRAME) { entryFrame = true; break; }
            if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE) continue;
            break;   // first real instruction reached without a frame
        }
        guard.add(pass);
        if (!entryFrame) guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(guard);
    }

    private static MethodNode find(ClassNode cn, String mcp, String srg, String desc) {
        for (MethodNode m : cn.methods) {
            if (!desc.equals(m.desc)) continue;
            if (mcp.equals(m.name) || srg.equals(m.name)) return m;
        }
        return null;
    }

    private static ClassNode read(byte[] basic) {
        ClassNode cn = new ClassNode();
        new ClassReader(basic).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        // COMPUTE_MAXS only, never COMPUTE_FRAMES: the two head guards carry their own
        // hand-authored F_SAME frame, the swing splice needs none, and COMPUTE_FRAMES
        // would make ASM load MC classes mid-transform via getCommonSuperClass and can
        // deadlock LaunchClassLoader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
