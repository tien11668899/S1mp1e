package dev.s1mp1e.forge.mixin;

import dev.s1mp1e.forge.S1Forge;
import dev.s1mp1e.forge.remap.AccessTable;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 把 Forge 加在原版類別上的成員（方法／欄位／介面＋欄位初始化）移植進原版類別，並套用 Forge 的存取放寬。
 * 原版本體保持不動，所以 Argentum 與 S1mp1e 玻璃的 mixin 照常運作。
 * 捐贈資料由 forgetool/DonorGen 從 Forge 1.8.9 binpatched jar 產生（s1forge/donor-&lt;ns&gt;.zip）。
 */
public class DonorPlugin implements IMixinConfigPlugin {
    private static final Map<String, byte[]> DONORS = new HashMap<>();
    private String suffix;

    @Override
    public void onLoad(String mixinPackage) {
        String ns = S1Forge.namespace();
        suffix = ns.equals("named") ? "Named" : "Intermediary";
        try (InputStream in = DonorPlugin.class.getResourceAsStream("/s1forge/donor-" + ns + ".zip");
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().endsWith(".class")) DONORS.put(e.getName().substring(0, e.getName().length() - 6), z.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException("讀不到 Forge 捐贈資料", e);
        }
        S1Forge.LOG.info("Forge 捐贈類別 {} 個（{}）", DONORS.size(), ns);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.contains(".donor.") || mixinClassName.endsWith(suffix);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return List.of("donor.Donor" + suffix, "donor.DonorItf" + suffix);
    }

    @Override
    public void preApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo mixinInfo) {
        String name = target.name;
        byte[] b = DONORS.get(name);
        if (b == null) return;
        ClassNode d = new ClassNode();
        new ClassReader(b).accept(d, 0);
        merge(target, d);
    }

    static void merge(ClassNode t, ClassNode d) {
        for (String i : d.interfaces) if (!t.interfaces.contains(i)) t.interfaces.add(i);
        Set<String> fields = new HashSet<>();
        for (FieldNode f : t.fields) fields.add(f.name);
        for (FieldNode f : d.fields) if (fields.add(f.name)) t.fields.add(f);
        Set<String> methods = new HashSet<>();
        for (MethodNode m : t.methods) methods.add(m.name + m.desc);
        for (MethodNode m : d.methods) {
            if (m.name.equals("s1f$init")) {
                for (MethodNode c : t.methods) if (c.name.equals("<init>")) insertAfterSuper(t, c, m);
            } else if (m.name.equals("s1f$clinit")) {
                MethodNode cl = null;
                for (MethodNode c : t.methods) if (c.name.equals("<clinit>")) cl = c;
                if (cl == null) {
                    cl = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                    cl.instructions.add(new InsnNode(Opcodes.RETURN));
                    t.methods.add(cl);
                }
                for (AbstractInsnNode in : cl.instructions.toArray()) {
                    if (in.getOpcode() == Opcodes.RETURN) cl.instructions.insertBefore(in, copy(m));
                }
                cl.maxStack = Math.max(cl.maxStack, m.maxStack);
            } else if (methods.add(m.name + m.desc)) {
                t.methods.add(m);
            }
        }
    }

    /** 建構子裡第一個 super(...) 之後插入實例欄位初始化（this(...) 委派的建構子跳過，避免重複） */
    static void insertAfterSuper(ClassNode t, MethodNode ctor, MethodNode snippet) {
        for (AbstractInsnNode in : ctor.instructions) {
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")) {
                if (mi.owner.equals(t.name)) return;   // this(...)
                if (!mi.owner.equals(t.superName)) continue;   // 參數裡 new 出來的別的物件
                ctor.instructions.insert(in, copy(snippet));
                ctor.maxStack = Math.max(ctor.maxStack, snippet.maxStack);
                return;
            }
        }
    }

    static InsnList copy(MethodNode m) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode in : m.instructions) if (in instanceof LabelNode l) labels.put(l, new LabelNode());
        InsnList out = new InsnList();
        for (ListIterator<AbstractInsnNode> it = m.instructions.iterator(); it.hasNext(); ) {
            AbstractInsnNode in = it.next();
            if (in instanceof FrameNode || in instanceof LineNumberNode) continue;
            if (in.getOpcode() == Opcodes.RETURN && !it.hasNext()) break;
            out.add(in.clone(labels));
        }
        return out;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 存取放寬要等所有 mixin（玻璃、Argentum…）都套完才做：Mixin 的類別資訊快取是照原本的存取旗標建的，
        // 先改會讓別人的注入找不到方法（例：GameGui 的 private 方法被 Forge AT 改成 protected）
        AccessTable.apply(targetClass);
    }
}
