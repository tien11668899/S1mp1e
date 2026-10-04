import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * 掃 forge-named.jar 對 net/minecraft 類別成員的引用，在 named 原版 jar（含繼承階層）裡找不到的就列出來＝Forge 加在原版上的成員。
 * 用法：java LinkCheck forge-named.jar mc-named.jar
 */
public class LinkCheck {
    static Map<String, ClassNode> mc = new HashMap<>();

    public static void main(String[] a) throws Exception {
        load(a[1], mc);
        Map<String, ClassNode> forge = new HashMap<>();
        load(a[0], forge);
        Map<String, Set<String>> missing = new TreeMap<>();
        Map<String, Integer> uses = new HashMap<>();
        for (ClassNode cn : forge.values()) {
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions) {
                    String owner = null, name = null, desc = null; boolean field = false;
                    if (in instanceof MethodInsnNode) { MethodInsnNode x = (MethodInsnNode) in; owner = x.owner; name = x.name; desc = x.desc; }
                    else if (in instanceof FieldInsnNode) { FieldInsnNode x = (FieldInsnNode) in; owner = x.owner; name = x.name; desc = x.desc; field = true; }
                    if (owner == null || !owner.startsWith("net/minecraft/") || owner.startsWith("net/minecraftforge")) continue;
                    if (!mc.containsKey(owner)) { add(missing, uses, "[class] " + owner, ""); continue; }
                    if (!has(owner, name, desc, field)) add(missing, uses, owner, (field ? "F " : "M ") + name + " " + desc);
                }
            }
            // 覆寫/實作：Forge 類別繼承原版、宣告原版上不存在的方法不算錯，略過
        }
        int total = 0;
        for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
            System.out.println(e.getKey());
            for (String s : e.getValue()) { System.out.println("    " + s + "   x" + uses.get(e.getKey() + "#" + s)); total++; }
        }
        System.out.println("TOTAL missing members: " + total + " on " + missing.size() + " classes");
    }

    static void add(Map<String, Set<String>> m, Map<String, Integer> u, String k, String v) {
        m.computeIfAbsent(k, x -> new TreeSet<>()).add(v);
        u.merge(k + "#" + v, 1, Integer::sum);
    }

    static boolean has(String owner, String name, String desc, boolean field) {
        Deque<String> q = new ArrayDeque<>(); q.add(owner); Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll(); if (!seen.add(c)) continue;
            ClassNode cn = mc.get(c);
            if (cn == null) {   // JDK 類別等：用反射實際檢查
                if (!c.startsWith("net/minecraft/")) {
                    try {
                        Class<?> k = Class.forName(c.replace('/', '.'), false, LinkCheck.class.getClassLoader());
                        if (field) { for (java.lang.reflect.Field f : k.getDeclaredFields()) if (f.getName().equals(name)) return true; }
                        else { for (java.lang.reflect.Method mm : k.getDeclaredMethods()) if (mm.getName().equals(name)) return true; }
                        if (k.getSuperclass() != null) q.add(k.getSuperclass().getName().replace('.', '/'));
                        for (Class<?> i : k.getInterfaces()) q.add(i.getName().replace('.', '/'));
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            if (field) { for (FieldNode f : cn.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true; }
            else { for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true; }
            if (cn.superName != null) q.add(cn.superName);
            if (cn.interfaces != null) q.addAll(cn.interfaces);
        }
        return false;
    }

    static void load(String jar, Map<String, ClassNode> out) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class")) continue;
                ClassReader r = new ClassReader(z.readAllBytes());
                ClassNode cn = new ClassNode();
                r.accept(cn, ClassReader.SKIP_FRAMES);
                out.put(cn.name, cn);
            }
        }
    }
}
