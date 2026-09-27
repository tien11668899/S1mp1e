package dev.s1mp1e.client.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * Local-variable liveness from a method's own stack-map frames, for the splices that READ a
 * local somewhere OTHER than method entry: {@code ALOAD 0} before the {@code IRETURN}s of the
 * two {@code KeyBinding} methods, and the {@code ALOAD 4} that feeds the hand offset after
 * {@code pushMatrix}. (Splices that capture at method ENTRY need no check — there the locals
 * are the method descriptor by definition — which is why the swing capture and the
 * {@code GameSettings.isKeyDown} key capture were moved to the head rather than gated here.)
 *
 * <p>Why: a v52 class's frames only have to describe the locals the compiler/optimizer
 * considers live. javac lists every in-scope variable (so parameters are always present),
 * but optimised bytecode (ProGuard-style preverification, or another coremod's rewrite) may
 * mark a parameter that is dead at that point as {@code TOP}, or reuse its slot. Loading such
 * a slot is a {@code VerifyError} that kills the class. So before emitting a load at a site,
 * the transformer asks here whether the slot provably holds the right kind of value there, and
 * skips just that site (logged) when it does not.
 *
 * <p>Pure ASM, no Minecraft types: safe to load while a transformer is running.
 */
final class AsmFrames {

    private AsmFrames() {}

    /** Kind marker for "any reference" (object type or null). */
    static final Object REF = new Object();

    /** An EXPAND_FRAMES copy of method {@code name desc} from {@code basic}, or null. Its instruction
     *  list lines up 1:1 with a normal (compressed) read of the same bytes. */
    static MethodNode expanded(byte[] basic, String name, String desc) {
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(basic).accept(cn, ClassReader.EXPAND_FRAMES);
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) return m;
            }
        } catch (Throwable t) {
            // fall through: no analysis possible -> callers treat every site as unsafe
        }
        return null;
    }

    /**
     * @param m      the (unmodified so far) method node the splice will go into
     * @param x      {@link #expanded} copy of the same method from the same bytes
     * @param site   the instruction our code will execute in front of (state = just before it)
     * @param slot   the local index the splice loads
     * @param kind   {@link #REF} or {@code Integer.valueOf(Opcodes.FLOAT)}
     * @return true only if the frames prove {@code slot} holds {@code kind} at {@code site}
     */
    static boolean holds(MethodNode m, MethodNode x, AbstractInsnNode site, int slot, Object kind) {
        try {
            if (x == null) return false;
            if (x.instructions.size() != m.instructions.size()) return false;
            int idx = m.instructions.indexOf(site);
            if (idx < 0) return false;
            AbstractInsnNode xs = x.instructions.get(idx);
            if (xs.getOpcode() != site.getOpcode()) return false;   // lists do not line up: be safe

            for (int i = idx - 1; i >= 0; i--) {
                AbstractInsnNode n = x.instructions.get(i);
                if (n instanceof FrameNode) {
                    return matches(typeAt(((FrameNode) n).local, slot), kind);
                }
                if (n instanceof VarInsnNode) {
                    VarInsnNode v = (VarInsnNode) n;
                    int op = v.getOpcode();
                    if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) {
                        boolean wide = op == Opcodes.LSTORE || op == Opcodes.DSTORE;
                        if (v.var == slot) {
                            if (op == Opcodes.ASTORE) return kind == REF;
                            if (op == Opcodes.FSTORE) return Integer.valueOf(Opcodes.FLOAT).equals(kind);
                            return false;
                        }
                        if (wide && v.var + 1 == slot) return false;
                    }
                }
                if (n instanceof IincInsnNode && ((IincInsnNode) n).var == slot) return false;
            }

            // Reached the method entry on a straight path. Without any frame in a method that
            // branches (pre-v50 bytecode) that reasoning is unsound, so require the slot is never
            // stored anywhere before trusting the descriptor.
            boolean anyFrame = false;
            for (AbstractInsnNode n = x.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof FrameNode) { anyFrame = true; break; }
            }
            if (!anyFrame) {
                for (AbstractInsnNode n = x.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (n instanceof VarInsnNode) {
                        int op = n.getOpcode();
                        int var = ((VarInsnNode) n).var;
                        if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE && (var == slot || var + 1 == slot)) return false;
                    }
                    if (n instanceof IincInsnNode && ((IincInsnNode) n).var == slot) return false;
                }
            }
            return matches(entryType(x, slot), kind);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean matches(Object type, Object kind) {
        if (type == null) return false;
        if (kind == REF) {
            return type instanceof String || Integer.valueOf(Opcodes.NULL).equals(type);
        }
        return kind.equals(type);
    }

    /** The verifier type an expanded frame's local list gives {@code slot}; TOP when absent. */
    private static Object typeAt(List<Object> locals, int slot) {
        if (locals == null) return Integer.valueOf(Opcodes.TOP);
        int s = 0;
        for (int i = 0; i < locals.size(); i++) {
            Object e = locals.get(i);
            if (s == slot) return e;
            boolean wide = Integer.valueOf(Opcodes.LONG).equals(e) || Integer.valueOf(Opcodes.DOUBLE).equals(e);
            s += wide ? 2 : 1;
            if (s > slot) return Integer.valueOf(Opcodes.TOP);   // slot is the upper half of a wide value
        }
        return Integer.valueOf(Opcodes.TOP);
    }

    /** The verifier type of {@code slot} at method entry, from the descriptor. */
    private static Object entryType(MethodNode m, int slot) {
        int s = 0;
        if ((m.access & Opcodes.ACC_STATIC) == 0) {
            if (slot == 0) return "this";
            s = 1;
        }
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int i = 0; i < args.length; i++) {
            Type t = args[i];
            if (s == slot) {
                switch (t.getSort()) {
                    case Type.OBJECT:
                    case Type.ARRAY:  return t.getInternalName();
                    case Type.FLOAT:  return Integer.valueOf(Opcodes.FLOAT);
                    case Type.LONG:   return Integer.valueOf(Opcodes.LONG);
                    case Type.DOUBLE: return Integer.valueOf(Opcodes.DOUBLE);
                    default:          return Integer.valueOf(Opcodes.INTEGER);
                }
            }
            s += t.getSize();
            if (s > slot) return Integer.valueOf(Opcodes.TOP);
        }
        return Integer.valueOf(Opcodes.TOP);
    }
}
