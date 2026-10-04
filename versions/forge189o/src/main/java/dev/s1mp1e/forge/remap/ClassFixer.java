package dev.s1mp1e.forge.remap;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * FML 在 LaunchWrapper 底下對每個類別做的轉換，這裡改成可以在「打包 Forge 函式庫時」與「重映射 Forge 模組時」直接套用：
 *  - SideTransformer：拿掉 @SideOnly(SERVER) 的欄位／方法（用戶端）
 *  - EventSubscriberTransformer：@SubscribeEvent 方法與所在類別改成 public
 *  - EventSubscriptionTransformer：Event 子類別補 LISTENER_LIST／setup()／getListenerList()／isCancelable()／hasResult()
 * 邏輯照 Forge 1.8.9 原版（LGPL-2.1）寫。
 */
public final class ClassFixer {
    private static final String SIDE_ONLY = "Lnet/minecraftforge/fml/relauncher/SideOnly;";
    private static final String SUBSCRIBE = "Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;";
    private static final String EVENT = "net/minecraftforge/fml/common/eventhandler/Event";
    private static final String LIST = "net/minecraftforge/fml/common/eventhandler/ListenerList";

    private ClassFixer() {
    }

    /** @param isEvent 給一個內部類名，回答它是不是 Event（含本身）的子類別 */
    public static boolean fix(ClassNode cn, Predicate<String> isEvent) {
        boolean changed = stripServer(cn);
        changed |= publicSubscribers(cn);
        if (cn.superName != null && !cn.name.equals(EVENT) && !cn.name.startsWith("net/minecraft/") && isEvent.test(cn.superName)) {
            changed |= buildEvent(cn);
        }
        return changed;
    }

    /** 類別本身標了 @SideOnly(SERVER) 時回傳 true（呼叫端決定要不要整個丟掉）。 */
    public static boolean isServerOnly(ClassNode cn) {
        return serverOnly(cn.visibleAnnotations);
    }

    public static boolean stripServer(ClassNode cn) {
        boolean ch = false;
        for (Iterator<FieldNode> it = cn.fields.iterator(); it.hasNext(); ) if (serverOnly(it.next().visibleAnnotations)) { it.remove(); ch = true; }
        for (Iterator<MethodNode> it = cn.methods.iterator(); it.hasNext(); ) if (serverOnly(it.next().visibleAnnotations)) { it.remove(); ch = true; }
        return ch;
    }

    static boolean serverOnly(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) {
            if (!a.desc.equals(SIDE_ONLY) || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if ("value".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String[] v && !"CLIENT".equals(v[1])) return true;
            }
        }
        return false;
    }

    static boolean publicSubscribers(ClassNode cn) {
        boolean any = false;
        for (MethodNode m : cn.methods) {
            if (m.visibleAnnotations == null) continue;
            for (AnnotationNode a : m.visibleAnnotations) {
                if (a.desc.equals(SUBSCRIBE)) {
                    m.access = pub(m.access);
                    any = true;
                }
            }
        }
        if (any) cn.access = pub(cn.access);
        return any;
    }

    static int pub(int acc) {
        return acc & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
    }

    static boolean buildEvent(ClassNode cn) {
        String v = "()V", b = "()Z", ld = "L" + LIST + ";", lm = "()" + ld;
        boolean edited = false, hasSetup = false, hasGet = false, hasCtor = false, hasCancel = false, hasResult = false;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("setup") && m.desc.equals(v) && (m.access & Opcodes.ACC_PROTECTED) != 0) hasSetup = true;
            if ((m.access & Opcodes.ACC_PUBLIC) != 0) {
                if (m.name.equals("getListenerList") && m.desc.equals(lm)) hasGet = true;
                if (m.name.equals("isCancelable") && m.desc.equals(b)) hasCancel = true;
                if (m.name.equals("hasResult") && m.desc.equals(b)) hasResult = true;
            }
            if (m.name.equals("<init>") && m.desc.equals(v)) hasCtor = true;
        }
        if (cn.visibleAnnotations != null) {
            for (AnnotationNode a : cn.visibleAnnotations) {
                if (!hasResult && a.desc.equals("Lnet/minecraftforge/fml/common/eventhandler/Event$HasResult;")) {
                    cn.methods.add(constTrue("hasResult"));
                    edited = true;
                } else if (!hasCancel && a.desc.equals("Lnet/minecraftforge/fml/common/eventhandler/Cancelable;")) {
                    cn.methods.add(constTrue("isCancelable"));
                    edited = true;
                }
            }
        }
        if (hasSetup) {
            if (!hasGet) throw new RuntimeException("Event class defines setup() but does not define getListenerList! " + cn.name);
            return edited;
        }
        String sup = Type.getObjectType(cn.superName).getInternalName();
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "LISTENER_LIST", ld, null, null));
        if (!hasCtor) {
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", v, null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, sup, "<init>", v, false));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            m.maxStack = 1; m.maxLocals = 1;
            cn.methods.add(m);
        }
        MethodNode m = new MethodNode(Opcodes.ACC_PROTECTED, "setup", v, null, null);
        LabelNode init = new LabelNode();
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, sup, "setup", v, false));
        m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "LISTENER_LIST", ld));
        m.instructions.add(new JumpInsnNode(Opcodes.IFNULL, init));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(init);
        m.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.add(new TypeInsnNode(Opcodes.NEW, LIST));
        m.instructions.add(new InsnNode(Opcodes.DUP));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, sup, "getListenerList", lm, false));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LIST, "<init>", "(" + ld + ")V", false));
        m.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "LISTENER_LIST", ld));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.maxStack = 3; m.maxLocals = 1;
        cn.methods.add(m);
        m = new MethodNode(Opcodes.ACC_PUBLIC, "getListenerList", lm, null, null);
        m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "LISTENER_LIST", ld));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 1; m.maxLocals = 1;
        cn.methods.add(m);
        return true;
    }

    static MethodNode constTrue(String name) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, "()Z", null, null);
        m.instructions.add(new InsnNode(Opcodes.ICONST_1));
        m.instructions.add(new InsnNode(Opcodes.IRETURN));
        m.maxStack = 1; m.maxLocals = 1;
        return m;
    }
}
