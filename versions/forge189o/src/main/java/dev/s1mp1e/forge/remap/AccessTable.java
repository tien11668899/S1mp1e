package dev.s1mp1e.forge.remap;

import dev.s1mp1e.forge.ForgeMods;
import dev.s1mp1e.forge.S1Forge;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 存取放寬規則（Forge 的 AccessTransformer）：
 *  - access-&lt;ns&gt;.txt：Forge 補丁本身放寬的（DonorGen 比對出來的）
 *  - forge_at.cfg：Forge 執行期 AT
 *  - 各 Forge 模組 jar 的 FMLAT（META-INF/*_at.cfg）
 * 由 DonorPlugin 在目標類別載入時套用。
 */
public final class AccessTable {
    record Rule(char kind, String name, String desc, int rank, boolean definal) {
    }

    private static final Map<String, List<Rule>> RULES = new HashMap<>();
    private static boolean loaded;

    private AccessTable() {
    }

    public static synchronized List<Rule> rulesFor(String cls) {
        if (!loaded) {
            loaded = true;
            load();
        }
        return RULES.get(cls);
    }

    static void load() {
        String ns = S1Forge.namespace();
        try (InputStream in = AccessTable.class.getResourceAsStream("/s1forge/access-" + ns + ".txt")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(" ");
                int acc = Integer.parseInt(p[p.length - 1]);
                Rule rule = switch (p[0]) {
                    case "C" -> new Rule('C', "", "", rank(acc), (acc & Opcodes.ACC_FINAL) == 0);
                    case "F" -> new Rule('F', p[2], p[3], rank(acc), (acc & Opcodes.ACC_FINAL) == 0);
                    default -> new Rule('M', p[2], p[3], rank(acc), (acc & Opcodes.ACC_FINAL) == 0);
                };
                add(p[1], rule);
            }
        } catch (Exception e) {
            S1Forge.LOG.error("access table", e);
        }
        try (InputStream in = AccessTable.class.getResourceAsStream("/forge_at.cfg")) {
            if (in != null) parseAt(new InputStreamReader(in, StandardCharsets.UTF_8), "forge_at.cfg");
        } catch (IOException e) {
            S1Forge.LOG.error("forge_at.cfg", e);
        }
        for (File jar : ForgeMods.jars()) {
            try (JarFile jf = new JarFile(jar)) {
                Manifest mf = jf.getManifest();
                String at = mf == null ? null : mf.getMainAttributes().getValue("FMLAT");
                if (at == null) continue;
                for (String f : at.trim().split(" ")) {
                    ZipEntry e = jf.getEntry("META-INF/" + f);
                    if (e == null) continue;
                    try (InputStream in = jf.getInputStream(e)) {
                        parseAt(new StringReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)), jar.getName() + "!" + f);
                    }
                }
            } catch (IOException e) {
                S1Forge.LOG.warn("讀不到模組 AT：{}", jar, e);
            }
        }
    }

    static void add(String cls, Rule r) {
        RULES.computeIfAbsent(cls, k -> new ArrayList<>()).add(r);
    }

    /** Forge AT 格式：public[-f] net.minecraft.x.Y [field_1_a | func_1_a(desc) | &lt;init&gt;(desc) | * | *()] */
    static void parseAt(Reader src, String from) throws IOException {
        SrgMap m = S1Forge.srg();
        BufferedReader r = new BufferedReader(src);
        String l;
        int n = 0;
        while ((l = r.readLine()) != null) {
            int h = l.indexOf('#');
            if (h >= 0) l = l.substring(0, h);
            l = l.trim();
            if (l.isEmpty()) continue;
            String[] p = l.split("\\s+");
            String mod = p[0];
            boolean definal = mod.endsWith("-f");
            if (definal) mod = mod.substring(0, mod.length() - 2);
            int rank = switch (mod) { case "public" -> 3; case "protected" -> 2; case "default" -> 1; default -> -1; };
            String mcp = p[1].replace('.', '/');
            String rt = m.mapClass(mcp);
            if (p.length == 2) { add(rt, new Rule('C', "", "", rank, definal)); n++; continue; }
            String mem = p[2];
            int paren = mem.indexOf('(');
            if (mem.equals("*")) add(rt, new Rule('F', "*", "*", rank, definal));
            else if (mem.equals("*()")) add(rt, new Rule('M', "*", "*", rank, definal));
            else if (paren < 0) add(rt, new Rule('F', m.isSrg(mem) ? m.mapMember(mcp, mem, null) : mem, "*", rank, definal));
            else {
                String name = mem.substring(0, paren), desc = mem.substring(paren).replace('.', '/');
                add(rt, new Rule('M', m.isSrg(name) ? m.mapMember(mcp, name, null) : name, m.mapDesc(desc), rank, definal));
            }
            n++;
        }
        S1Forge.LOG.info("AT {}: {} 條", from, n);
    }

    static int rank(int acc) {
        if ((acc & Opcodes.ACC_PUBLIC) != 0) return 3;
        if ((acc & Opcodes.ACC_PROTECTED) != 0) return 2;
        if ((acc & Opcodes.ACC_PRIVATE) != 0) return 0;
        return 1;
    }

    static int widen(int acc, int rank, boolean definal) {
        if (rank > rank(acc)) {
            acc &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            acc |= rank == 3 ? Opcodes.ACC_PUBLIC : rank == 2 ? Opcodes.ACC_PROTECTED : 0;
        }
        if (definal) acc &= ~Opcodes.ACC_FINAL;
        return acc;
    }

    /** 套到類別上；private 實例方法被放寬時，類別內的 INVOKESPECIAL 改成 INVOKEVIRTUAL（同 Forge AT） */
    public static int apply(ClassNode cn) {
        List<Rule> rules = rulesFor(cn.name);
        if (rules == null) return 0;
        int n = 0;
        List<MethodNode> opened = new ArrayList<>();
        for (Rule r : rules) {
            if (r.rank < 0 && !r.definal) continue;
            switch (r.kind) {
                case 'C' -> {
                    cn.access = widen(cn.access, Math.max(r.rank, 1), r.definal);
                    for (InnerClassNode ic : cn.innerClasses) if (ic.name.equals(cn.name)) ic.access = widen(ic.access, Math.max(r.rank, 1), r.definal);
                    n++;
                }
                case 'F' -> {
                    for (FieldNode f : cn.fields) {
                        if ((r.name.equals("*") || f.name.equals(r.name)) && (r.desc.equals("*") || f.desc.equals(r.desc))) { f.access = widen(f.access, r.rank, r.definal); n++; }
                    }
                }
                default -> {
                    for (MethodNode mm : cn.methods) {
                        if ((r.name.equals("*") || mm.name.equals(r.name)) && (r.desc.equals("*") || mm.desc.equals(r.desc))) {
                            boolean wasPrivate = (mm.access & Opcodes.ACC_PRIVATE) != 0;
                            mm.access = widen(mm.access, r.rank, r.definal);
                            if (wasPrivate && (mm.access & Opcodes.ACC_PRIVATE) == 0 && (mm.access & Opcodes.ACC_STATIC) == 0 && !mm.name.equals("<init>")) opened.add(mm);
                            n++;
                        }
                    }
                }
            }
        }
        for (MethodNode target : opened) {
            for (MethodNode mm : cn.methods) {
                for (AbstractInsnNode in : mm.instructions) {
                    if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.owner.equals(cn.name) && mi.name.equals(target.name) && mi.desc.equals(target.desc)) {
                        mi.setOpcode(Opcodes.INVOKEVIRTUAL);
                    }
                }
            }
        }
        return n;
    }
}
