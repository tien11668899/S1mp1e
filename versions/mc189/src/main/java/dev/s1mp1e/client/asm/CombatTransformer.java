package dev.s1mp1e.client.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Iterator;

/**
 * Render-only combat-feel patches. Three surgical rewrites, all against RENDERING
 * classes only (EntityRenderer, RendererLivingEntity, ItemRenderer) so the coremod
 * stays auditable and nothing here can touch movement, attacks, or the network.
 *
 * <ol>
 *   <li><b>{@code EntityRenderer.hurtCameraEffect}</b> — a
 *       {@code if (CombatHooks.noHurtCam()) return;} guard is spliced onto the
 *       method head. Both call sites (the two camera passes) go through this one
 *       method, so a single splice removes the whole hurt shake.</li>
 *   <li><b>{@code RendererLivingEntity.setBrightness}</b> — a head guard makes the
 *       method return {@code false} when the hook says the current frame is a
 *       hurt/death flash, which suppresses the red tint while leaving the normal
 *       colour-multiplier path (charged-creeper flash, etc.) untouched. The caller
 *       keys its {@code unsetBrightness} off the same false return, so the GL state
 *       stays balanced.</li>
 *   <li><b>{@code ItemRenderer.transformFirstPersonItem}</b> — every call to it
 *       inside {@code renderItemInFirstPerson} is retargeted to
 *       {@link CombatHooks#transformFirstPersonItem}. The receiver is already first
 *       on the stack, so this is a pure opcode/owner/desc swap, exactly like the
 *       glass transformer's container-background retarget. The hook reproduces the
 *       vanilla transform when the module is off.</li>
 * </ol>
 *
 * <p>Name-tolerant (MCP names in a dev workspace, SRG in production) and defensive:
 * every patch is skipped loudly rather than throwing, so a name mismatch degrades
 * to vanilla behaviour instead of a crash on startup.
 */
public final class CombatTransformer implements IClassTransformer {

    // ---- targets ----------------------------------------------------------

    private static final String ENTITY_RENDERER = "net.minecraft.client.renderer.EntityRenderer";
    private static final String LIVING_RENDERER  = "net.minecraft.client.renderer.entity.RendererLivingEntity";
    private static final String ITEM_RENDERER    = "net.minecraft.client.renderer.ItemRenderer";

    // hurtCameraEffect(float)V
    private static final String HURT_CAM_MCP  = "hurtCameraEffect";
    private static final String HURT_CAM_SRG  = "func_78482_e";
    private static final String HURT_CAM_DESC = "(F)V";

    // setBrightness(EntityLivingBase, float, boolean)Z  (T erased to EntityLivingBase)
    private static final String BRIGHT_MCP  = "setBrightness";
    private static final String BRIGHT_SRG  = "func_177092_a";
    private static final String BRIGHT_DESC = "(Lnet/minecraft/entity/EntityLivingBase;FZ)Z";

    // transformFirstPersonItem(float, float)V
    private static final String XFORM_MCP  = "transformFirstPersonItem";
    private static final String XFORM_SRG  = "func_178096_b";
    private static final String XFORM_DESC = "(FF)V";

    private static final String HOOKS = "dev/s1mp1e/client/asm/CombatHooks";

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
            System.out.println("[S1mp1e/ASM] patch of " + transformedName + " failed: " + t);
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
            System.out.println("[S1mp1e/ASM] EntityRenderer.hurtCameraEffect not found, skipping");
            return basic;
        }
        // if (CombatHooks.noHurtCam()) return;
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "noHurtCam", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(pass);
        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched EntityRenderer.hurtCameraEffect");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 2) RendererLivingEntity.setBrightness -> return false on a hurt/death frame
    // -----------------------------------------------------------------------
    private static byte[] patchHurtFlash(byte[] basic) {
        ClassNode cn = read(basic);
        MethodNode m = find(cn, BRIGHT_MCP, BRIGHT_SRG, BRIGHT_DESC);
        if (m == null) {
            System.out.println("[S1mp1e/ASM] RendererLivingEntity.setBrightness not found, skipping");
            return basic;
        }
        // if (CombatHooks.suppressHurtFlash(entitylivingbaseIn)) return false;
        // entitylivingbaseIn is the first (index 1) argument of this instance method.
        LabelNode pass = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "suppressHurtFlash",
                "(Lnet/minecraft/entity/EntityLivingBase;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        pre.add(new InsnNode(Opcodes.ICONST_0));   // false
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(pass);
        m.instructions.insert(pre);
        System.out.println("[S1mp1e/ASM] patched RendererLivingEntity.setBrightness");
        return write(cn);
    }

    // -----------------------------------------------------------------------
    // 3) ItemRenderer.transformFirstPersonItem -> retarget every call to our hook
    // -----------------------------------------------------------------------
    private static byte[] patchOldAnimations(byte[] basic) {
        ClassNode cn = read(basic);
        int patched = 0;
        // The calls live in renderItemInFirstPerson; iterate every method so we
        // catch them all regardless of which method holds them.
        for (MethodNode m : cn.methods) {
            Iterator<AbstractInsnNode> it = m.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                int op = insn.getOpcode();
                // The target is a private method, so javac emits INVOKESPECIAL;
                // accept INVOKEVIRTUAL too in case a remap changed the linkage.
                if (op != Opcodes.INVOKESPECIAL && op != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                boolean isXform = (XFORM_MCP.equals(call.name) || XFORM_SRG.equals(call.name))
                                  && XFORM_DESC.equals(call.desc);
                if (!isXform) continue;
                // Receiver (the ItemRenderer) is already first on the stack, so
                // swapping to an INVOKESTATIC whose first param is the ItemRenderer
                // is a drop-in substitution — the two float args follow unchanged.
                m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                        "transformFirstPersonItem",
                        "(Lnet/minecraft/client/renderer/ItemRenderer;FF)V", false));
                patched++;
            }
        }
        if (patched == 0) {
            System.out.println("[S1mp1e/ASM] transformFirstPersonItem call not found, skipping");
            return basic;
        }
        System.out.println("[S1mp1e/ASM] patched ItemRenderer.transformFirstPersonItem (call sites: " + patched + ")");
        return write(cn);
    }

    // ---- helpers ----------------------------------------------------------

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
        // COMPUTE_MAXS only. Our splices are simple branches over Java-6 (v50)
        // bytecode, whose lenient verifier does not need stack-map frames, and
        // COMPUTE_FRAMES would make ASM load MC classes mid-transform via
        // getCommonSuperClass and can deadlock LaunchClassLoader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
