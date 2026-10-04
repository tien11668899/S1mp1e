import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.commons.Remapper;

/**
 * 由「Forge 打過補丁的 jar」與「原版 jar」（同一命名空間）產生：
 *   lib.jar   ：Forge 自己的類別（net/minecraftforge/** 等）＋原版沒有的新類別（Forge 加的內部類別、改號的匿名類別改名後）
 *   donor/    ：每個原版類別一個「捐贈」類別檔，只含 Forge 新增的方法／欄位／介面，外加
 *               s1f$init()V（實例欄位初始化片段）、s1f$clinit()V（靜態欄位初始化片段）
 *   access.txt：Forge 放寬的存取（類別／方法／欄位）
 * 用法：java DonorGen patched.jar vanilla.jar outDir
 */
public class DonorGen {
    static Map<String, ClassNode> pat, van;
    static Map<String, byte[]> patBytes = new HashMap<>();
    static Map<String, byte[]> resources = new LinkedHashMap<>();
    static PrintWriter report;
    static int nM, nF, nI, nInit, nClinit, nSkipInit, nAcc;
    /** 補丁版裡和原版同名、內容卻不同（改號）的匿名類別 → 新名字 */
    static Map<String, String> shifted = new HashMap<>();

    public static void main(String[] a) throws Exception {
        pat = load(a[0], true); van = load(a[1], false);
        try (InputStream in = new FileInputStream(a[3])) { srg = dev.s1mp1e.forge.remap.SrgMap.read(in); }
        Path out = Paths.get(a[2]);
        Files.createDirectories(out);
        report = new PrintWriter(Files.newBufferedWriter(out.resolve("report.txt")));
        PrintWriter acc = new PrintWriter(Files.newBufferedWriter(out.resolve("access.txt")));

        // 1) 重編譯造成的合成欄位改名（this$0、val$x ↔ 原版混淆名）：同類別、同型別、各剩一個 → 視為同一欄位
        for (ClassNode p : pat.values()) {
            ClassNode v = van.get(p.name);
            if (!isMc(p.name) || v == null || isAnon(p.name)) continue;
            Set<String> vn = new HashSet<>(); for (FieldNode f : v.fields) vn.add(f.name);
            Set<String> pn = new HashSet<>(); for (FieldNode f : p.fields) pn.add(f.name);
            for (FieldNode f : p.fields) {
                if (vn.contains(f.name) || (f.access & Opcodes.ACC_SYNTHETIC) == 0 && !f.name.startsWith("this$") && !f.name.startsWith("val$")) continue;
                List<FieldNode> cand = new ArrayList<>();
                for (FieldNode o : v.fields) if (!pn.contains(o.name) && o.desc.equals(f.desc) && (o.access & Opcodes.ACC_SYNTHETIC) != 0) cand.add(o);
                if (cand.size() == 1) fieldAlias.computeIfAbsent(p.name, k -> new HashMap<>()).put(f.name, cand.get(0).name);
            }
        }
        // 2) 捐贈類別
        Map<String, ClassNode> donors = new LinkedHashMap<>();
        List<ClassNode> newClasses = new ArrayList<>();
        for (ClassNode p : pat.values()) {
            if (!isMc(p.name) || isAnon(p.name)) continue;
            ClassNode v = van.get(p.name);
            if (v == null) { newClasses.add(p); report.println("NEWCLASS " + p.name); continue; }
            ClassNode d = diff(p, v, acc);
            if (d != null) donors.put(p.name, d);
        }
        // 3) 捐贈成員／新類別用到的匿名類別：補丁版的編號和原版對不上，一律複製一份改名（遞迴）
        Deque<ClassNode> q = new ArrayDeque<>(donors.values()); q.addAll(newClasses);
        while (!q.isEmpty()) {
            for (String r : refs(q.poll())) {
                if (isMc(r) && isAnon(r) && pat.containsKey(r) && !shifted.containsKey(r)) {
                    String o = r.substring(0, r.lastIndexOf('$')), num = r.substring(r.lastIndexOf('$') + 1);
                    shifted.put(r, o + "$s1f_" + num);
                    q.add(pat.get(r));
                }
            }
        }
        report.println("copied anon classes: " + shifted.size());
        Remapper ren = new Remapper() {
            @Override public String map(String n) { return shifted.getOrDefault(n, n); }
            @Override public Object mapValue(Object v) {
                if (v instanceof String s) { String r = srg.mapConstant(s); if (!r.equals(s)) { nConst++; report.println("CONST " + s + " -> " + r); } return r; }
                return super.mapValue(v);
            }
            @Override public String mapFieldName(String owner, String name, String desc) {
                Map<String, String> m = fieldAlias.get(owner);
                return m != null && m.containsKey(name) ? m.get(name) : name;
            }
        };

        try (ZipOutputStream lib = new ZipOutputStream(Files.newOutputStream(out.resolve("lib.jar")));
             ZipOutputStream donor = new ZipOutputStream(Files.newOutputStream(out.resolve("donor.jar")))) {
            Set<String> written = new HashSet<>();
            for (ClassNode p : pat.values()) if (!isMc(p.name)) putClass(lib, p.name, fixLib(rename(patBytes.get(p.name), ren, false)), written);
            for (ClassNode p : newClasses) putClass(lib, p.name, fixLib(rename(patBytes.get(p.name), ren, false)), written);
            for (String a0 : shifted.keySet()) putClass(lib, shifted.get(a0), rename(patBytes.get(a0), ren, true), written);
            for (ClassNode d : donors.values()) {
                ClassWriter cw = new ClassWriter(0);
                ClassNode remapped = new ClassNode();
                d.accept(new org.objectweb.asm.commons.ClassRemapper(remapped, ren));
                dev.s1mp1e.forge.remap.ClassFixer.stripServer(remapped);
                remapped.accept(cw);
                donor.putNextEntry(new ZipEntry(d.name + ".class")); donor.write(cw.toByteArray()); donor.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : resources.entrySet()) { lib.putNextEntry(new ZipEntry(e.getKey())); lib.write(e.getValue()); lib.closeEntry(); }
            report.println("donor classes: " + donors.size() + " new classes: " + newClasses.size());
        }
        acc.close();
        int al = 0; for (Map<String, String> m : fieldAlias.values()) al += m.size();
        String sum = String.format("donors=%d added methods=%d fields=%d itf=%d initSnippets=%d clinitSnippets=%d skippedInit=%d access=%d anonCopies=%d newClasses=%d fieldAlias=%d", donors.size(), nM, nF, nI, nInit, nClinit, nSkipInit, nAcc, shifted.size(), newClasses.size(), al);
        report.println(sum); report.close();
        System.out.println(sum + " fixedLibClasses=" + nFixed + " constStrings=" + nConst);
    }

    static Map<String, Map<String, String>> fieldAlias = new HashMap<>();
    static dev.s1mp1e.forge.remap.SrgMap srg;
    static int nConst;

    /** Forge 函式庫類別先做 FML 的執行期轉換（事件類別補方法、SERVER 成員剝除、@SubscribeEvent 公開） */
    static int nFixed;
    static byte[] fixLib(byte[] b) {
        ClassNode n = new ClassNode();
        new ClassReader(b).accept(n, 0);
        if (!dev.s1mp1e.forge.remap.ClassFixer.fix(n, DonorGen::isEvent)) return b;
        nFixed++;
        ClassWriter cw = new ClassWriter(0);
        n.accept(cw);
        return cw.toByteArray();
    }

    static boolean isEvent(String name) {
        while (name != null) {
            if (name.equals("net/minecraftforge/fml/common/eventhandler/Event")) return true;
            ClassNode c = pat.get(name);
            name = c == null ? null : c.superName;
        }
        return false;
    }

    static boolean isAnon(String n) {
        int i = n.lastIndexOf('$');
        if (i < 0 || i == n.length() - 1) return false;
        for (int k = i + 1; k < n.length(); k++) if (!Character.isDigit(n.charAt(k))) return false;
        return true;
    }

    /** 類別節點引用到的所有類別名 */
    static Set<String> refs(ClassNode c) {
        Set<String> s = new HashSet<>();
        Remapper rec = new Remapper() { @Override public String map(String n) { s.add(n); return n; } };
        c.accept(new org.objectweb.asm.commons.ClassRemapper(new ClassNode(), rec));
        return s;
    }

    static boolean isMc(String n) { return n.startsWith("net/minecraft/") && !n.startsWith("net/minecraftforge/"); }

    static ClassNode diff(ClassNode p, ClassNode v, PrintWriter acc) throws AnalyzerException {
        ClassNode d = new ClassNode();
        d.version = p.version; d.access = p.access; d.name = p.name; d.superName = p.superName;
        boolean any = false;
        if (rank(p.access) > rank(v.access)) { acc.println("C " + p.name + " " + p.access); nAcc++; }
        for (String i : p.interfaces) if (!v.interfaces.contains(i)) { d.interfaces.add(i); nI++; any = true; }
        Map<String, MethodNode> vm = new HashMap<>();
        for (MethodNode m : v.methods) vm.put(m.name + m.desc, m);
        Map<String, FieldNode> vf = new HashMap<>();
        for (FieldNode f : v.fields) vf.put(f.name, f);
        Set<String> addedFields = new HashSet<>();
        for (FieldNode f : p.fields) {
            FieldNode o = vf.get(f.name);
            Map<String, String> al = fieldAlias.get(p.name);
            if (o == null && al != null && al.containsKey(f.name)) continue;
            if (o == null) { d.fields.add(f); addedFields.add(f.name); nF++; any = true; }
            else if (widened(o.access, f.access)) { acc.println("F " + p.name + " " + f.name + " " + f.desc + " " + f.access); nAcc++; }
        }
        for (MethodNode m : p.methods) {
            if (m.name.equals("<init>") || m.name.equals("<clinit>")) continue;
            MethodNode o = vm.get(m.name + m.desc);
            if (o == null) { d.methods.add(m); nM++; any = true; }
            else if (widened(o.access, m.access)) { acc.println("M " + p.name + " " + m.name + " " + m.desc + " " + m.access); nAcc++; }
        }
        // 新建構子（原版沒有的簽章）也要搬
        for (MethodNode m : p.methods) if (m.name.equals("<init>") && !vm.containsKey(m.name + m.desc)) { d.methods.add(m); nM++; any = true; report.println("NEWCTOR " + p.name + " " + m.desc); }
        if (!addedFields.isEmpty()) {
            MethodNode init = snippets(p, addedFields, false);
            if (init != null) { d.methods.add(init); any = true; }
            MethodNode clinit = snippets(p, addedFields, true);
            if (clinit != null) { d.methods.add(clinit); any = true; }
        }
        return any ? d : null;
    }

    /** 從補丁版建構子／<clinit> 抽出「指定新增欄位」的初始化片段（棧深 0 起、PUTFIELD/PUTSTATIC 止）。 */
    static MethodNode snippets(ClassNode p, Set<String> added, boolean stat) throws AnalyzerException {
        MethodNode src = null;
        for (MethodNode m : p.methods) {
            if (stat ? m.name.equals("<clinit>") : (m.name.equals("<init>") && callsSuper(p, m))) { src = m; break; }
        }
        if (src == null) return null;
        Frame<BasicValue>[] fr = new Analyzer<>(new BasicInterpreter()).analyze(p.name, src);
        AbstractInsnNode[] ins = src.instructions.toArray();
        MethodNode out = new MethodNode(Opcodes.ACC_PRIVATE | (stat ? Opcodes.ACC_STATIC : 0) | Opcodes.ACC_SYNTHETIC, stat ? "s1f$clinit" : "s1f$init", "()V", null, null);
        Map<LabelNode, LabelNode> lm = new HashMap<>();
        for (AbstractInsnNode n : ins) if (n instanceof LabelNode) lm.put((LabelNode) n, new LabelNode());
        int count = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ins.length; i++) {
            AbstractInsnNode n = ins[i];
            if (!(n instanceof FieldInsnNode)) continue;
            FieldInsnNode f = (FieldInsnNode) n;
            if (f.getOpcode() != (stat ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD) || !f.owner.equals(p.name) || !added.contains(f.name)) continue;
            if (!seen.add(f.name)) continue;   // 只取第一次賦值
            // 往回找棧深為 0 的起點
            int s = i;
            while (s > 0 && (fr[s] == null || fr[s].getStackSize() != 0)) s--;
            boolean ok = true;
            for (int k = s; k <= i; k++) {
                AbstractInsnNode x = ins[k];
                if (x instanceof VarInsnNode && ((VarInsnNode) x).var != 0) ok = false;
                if (x instanceof IincInsnNode) ok = false;
                if (stat && x instanceof VarInsnNode) ok = false;
                if (x instanceof JumpInsnNode && !inRange(ins, s, i, ((JumpInsnNode) x).label)) ok = false;
            }
            for (TryCatchBlockNode t : src.tryCatchBlocks) if (overlaps(ins, s, i, t)) ok = false;
            if (!ok) { nSkipInit++; report.println("SKIPINIT " + p.name + "." + f.name); continue; }
            for (int k = s; k <= i; k++) {
                AbstractInsnNode x = ins[k];
                if (x instanceof LineNumberNode || x instanceof FrameNode) continue;
                out.instructions.add(x.clone(lm));
            }
            count++;
        }
        if (count == 0) return null;
        out.instructions.add(new InsnNode(Opcodes.RETURN));
        out.maxStack = src.maxStack; out.maxLocals = stat ? 0 : 1;
        if (stat) nClinit += count; else nInit += count;
        report.println((stat ? "CLINIT " : "INIT ") + p.name + " x" + count);
        return out;
    }

    static boolean callsSuper(ClassNode c, MethodNode m) {
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof MethodInsnNode && n.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) n).name.equals("<init>")) {
                return !((MethodInsnNode) n).owner.equals(c.name);   // 第一個 <init> 呼叫是 super(...) 而不是 this(...)
            }
        }
        return false;
    }

    static boolean inRange(AbstractInsnNode[] ins, int s, int e, LabelNode l) {
        for (int k = s; k <= e; k++) if (ins[k] == l) return true;
        return false;
    }

    static boolean overlaps(AbstractInsnNode[] ins, int s, int e, TryCatchBlockNode t) {
        int a = -1, b = -1;
        for (int k = 0; k < ins.length; k++) { if (ins[k] == t.start) a = k; if (ins[k] == t.end) b = k; }
        return a <= e && b >= s;
    }

    static int rank(int acc) { if ((acc & Opcodes.ACC_PUBLIC) != 0) return 3; if ((acc & Opcodes.ACC_PROTECTED) != 0) return 2; if ((acc & Opcodes.ACC_PRIVATE) != 0) return 0; return 1; }
    static boolean widened(int o, int n) { return rank(n) > rank(o) || ((o & Opcodes.ACC_FINAL) != 0 && (n & Opcodes.ACC_FINAL) == 0); }

    static byte[] rename(byte[] b, Remapper r, boolean detach) {
        ClassReader cr = new ClassReader(b);
        ClassNode n = new ClassNode();
        cr.accept(new org.objectweb.asm.commons.ClassRemapper(n, r), 0);
        if (detach) {   // 複製出來的匿名類別：拔掉 InnerClasses／EnclosingMethod，免得反射時和外層類別對不上
            n.outerClass = null; n.outerMethod = null; n.outerMethodDesc = null;
            n.innerClasses.removeIf(ic -> ic.name.equals(n.name));
        }
        ClassWriter cw = new ClassWriter(0);
        n.accept(cw);
        return cw.toByteArray();
    }

    static void putClass(ZipOutputStream z, String name, byte[] b, Set<String> w) throws IOException {
        if (!w.add(name)) return;
        z.putNextEntry(new ZipEntry(name + ".class")); z.write(b); z.closeEntry();
    }

    static Map<String, ClassNode> load(String jar, boolean keep) throws IOException {
        Map<String, ClassNode> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                byte[] b = z.readAllBytes();
                if (!e.getName().endsWith(".class")) {
                    if (keep && !e.isDirectory() && (e.getName().startsWith("assets/forge") || e.getName().startsWith("assets/fml") || e.getName().endsWith(".lzma") || e.getName().endsWith("_at.cfg") || e.getName().startsWith("META-INF/services") || e.getName().startsWith("forge") || e.getName().startsWith("fml") || e.getName().startsWith("deobf") || e.getName().startsWith("mcp")))
                        resources.put(e.getName(), b);
                    continue;
                }
                ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, keep ? 0 : ClassReader.SKIP_CODE);
                if (cn.name.indexOf('/') < 0) continue;   // tiny-remapper 對對照表外的內部類別會多吐一份沒映射的，丟掉
                out.put(cn.name, cn);
                if (keep) patBytes.put(cn.name, b);
            }
        }
        return out;
    }
}
