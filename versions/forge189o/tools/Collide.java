import java.io.*; import java.util.*; import java.util.zip.*; import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
/** 找 named 命名空間的撞名：某類別宣告的方法，在 named 下和祖先（原版）某方法同名同描述子，但 intermediary 下名字不同（＝本來是兩個不同的方法）。 */
public class Collide {
  static Map<String, ClassNode> load(String j) throws IOException { Map<String, ClassNode> m = new LinkedHashMap<>();
    try (ZipInputStream z = new ZipInputStream(new FileInputStream(j))) { ZipEntry e; while ((e = z.getNextEntry()) != null) { if (!e.getName().endsWith(".class")) continue;
      ClassNode c = new ClassNode(); new ClassReader(z.readAllBytes()).accept(c, ClassReader.SKIP_CODE); m.put(c.name, c); } } return m; }
  public static void main(String[] a) throws Exception {
    Map<String, ClassNode> pn = load(a[0]), pi = load(a[1]);
    List<ClassNode> ln = new ArrayList<>(pn.values());
    Map<String, String> cm = new HashMap<>();
    try (BufferedReader r = new BufferedReader(new FileReader(a[2]))) { String l; while ((l = r.readLine()) != null) { String[] p = l.split("	"); if (p[0].equals("c")) cm.put(p[3], p[2]); } }
    Map<String, ClassNode> n2i = new HashMap<>();
    for (ClassNode c : ln) { ClassNode x = pi.get(cm.getOrDefault(c.name, c.name)); if (x != null && x.methods.size() == c.methods.size()) n2i.put(c.name, x); else System.err.println("unaligned " + c.name); }
    ln.removeIf(c -> !n2i.containsKey(c.name));
    Set<String> out = new TreeSet<>();
    for (ClassNode c : ln) {
      ClassNode ci = n2i.get(c.name);
      for (int i = 0; i < c.methods.size(); i++) {
        MethodNode m = c.methods.get(i); if (m.name.startsWith("<") || (m.access & Opcodes.ACC_STATIC) != 0 && false) continue;
        String mi = ci.methods.get(i).name;
        Deque<String> q = new ArrayDeque<>(); if (c.superName != null) q.add(c.superName); q.addAll(c.interfaces); Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) { String s = q.poll(); if (!seen.add(s)) continue; ClassNode an = pn.get(s); if (an == null) continue; ClassNode ai = n2i.get(s);
          for (int j = 0; j < an.methods.size(); j++) { MethodNode am = an.methods.get(j);
            if (am.name.equals(m.name) && am.desc.equals(m.desc) && !ai.methods.get(j).name.equals(mi) && (am.access & Opcodes.ACC_PRIVATE) == 0)
              out.add("M " + ai.name + " " + ai.methods.get(j).name + " " + ai.methods.get(j).desc + " " + am.name + "   <- " + c.name + " (" + mi + ")"); }
          if (an.superName != null) q.add(an.superName); q.addAll(an.interfaces); }
      }
    }
    out.forEach(System.out::println); System.out.println(out.size() + " collisions");
  }
}
