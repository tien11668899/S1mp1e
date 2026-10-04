package dev.s1mp1e.forge.remap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge 模組用的名稱（MCP 類名＋SRG 成員名）→ 執行期名稱（dev＝Feather named、正式＝intermediary）。
 * SRG 成員名（func_123_a／field_456_b）幾乎全域唯一，所以不必知道擁有者；少數在 intermediary 對到多個名字的，
 * 用擁有者沿繼承鏈往上找（資料在 srg2rt-*.txt 的 A 行）。
 */
public final class SrgMap {
    private static final Pattern SRG = Pattern.compile("(?:func|field)_\\d+_[A-Za-z_]+");
    private static final Pattern DESC_CLASS = Pattern.compile("L([^;]+);");

    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> classesBack = new HashMap<>();
    private final Map<String, String> members = new HashMap<>();
    private final Map<String, Map<String, String>> ambiguous = new HashMap<>();
    /** 不是 func_/field_ 形式、但執行期名稱不同的 SRG 名（例：GuiOverlayDebug.call）：「擁有者 名稱 描述子」→ 執行期 */
    private final Map<String, String> special = new HashMap<>();
    private static final Pattern REF = Pattern.compile("L([^;]+);([^(:]*)(:?)(.*)");
    /** 給定執行期類名，回傳它的 super＋介面（執行期名稱）；找不到回傳 null */
    private Function<String, String[]> runtimeHierarchy = n -> null;

    public static SrgMap load(String namespace) throws IOException {
        try (InputStream in = SrgMap.class.getResourceAsStream("/s1forge/srg2rt-" + namespace + ".txt")) {
            if (in == null) throw new IOException("missing /s1forge/srg2rt-" + namespace + ".txt");
            return read(in);
        }
    }

    public static SrgMap read(InputStream in) throws IOException {
        SrgMap m = new SrgMap();
        {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split(" ");
                switch (p[0]) {
                    case "C" -> { m.classes.put(p[1], p[2]); m.classesBack.put(p[2], p[1]); }
                    case "M" -> m.members.put(p[1], p[2]);
                    case "A" -> m.ambiguous.computeIfAbsent(p[1], k -> new HashMap<>()).put(p[2], p[3]);
                    case "S" -> m.special.put(p[1] + " " + p[2] + " " + p[3], p[4]);
                    default -> { }
                }
            }
        }
        return m;
    }

    public void setRuntimeHierarchy(Function<String, String[]> f) {
        this.runtimeHierarchy = f;
    }

    /** MCP 類名（net/minecraft/client/gui/GuiScreen）→ 執行期；不是原版類別就原樣 */
    public String mapClass(String mcp) {
        String r = classes.get(mcp);
        if (r != null) return r;
        int i = mcp.lastIndexOf('$');   // 對照表沒列到的內部類別（例如匿名類）跟著外層走
        if (i > 0 && mcp.startsWith("net/minecraft/")) {
            String o = classes.get(mcp.substring(0, i));
            if (o != null) return o + mcp.substring(i);
        }
        return mcp;
    }

    public String unmapClass(String rt) {
        return classesBack.getOrDefault(rt, rt);
    }

    public boolean isSrg(String name) {
        return SRG.matcher(name).matches();
    }

    /**
     * @param ownerMcp 引用端寫的擁有者（MCP 名），可為 null
     * @param localSuper 模組自己的類別：MCP 名 → super＋介面（MCP 名）；可為 null
     */
    public String mapMember(String ownerMcp, String srg, Function<String, String[]> localSuper) {
        Map<String, String> amb = ambiguous.get(srg);
        if (amb != null && ownerMcp != null) {
            Deque<String> q = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            q.add(ownerMcp);
            while (!q.isEmpty()) {
                String c = q.poll();
                if (c == null || !seen.add(c)) continue;
                String hit = amb.get(c);
                if (hit != null) return hit;
                String[] sup = localSuper == null ? null : localSuper.apply(c);
                if (sup == null) {
                    String[] rt = runtimeHierarchy.apply(mapClass(c));
                    if (rt != null) { sup = new String[rt.length]; for (int i = 0; i < rt.length; i++) sup[i] = rt[i] == null ? null : unmapClass(rt[i]); }
                }
                if (sup != null) for (String s : sup) if (s != null) q.add(s);
            }
        }
        return members.getOrDefault(srg, srg);
    }

    /** 非 SRG 形式的成員名：沿擁有者繼承鏈查特殊表，查不到原樣 */
    public String mapSpecial(String ownerMcp, String name, String descMcp, Function<String, String[]> localSuper) {
        if (special.isEmpty() || ownerMcp == null || descMcp == null) return name;
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(ownerMcp);
        while (!q.isEmpty()) {
            String c = q.poll();
            if (c == null || !seen.add(c) || !c.startsWith("net/minecraft/") && (localSuper == null || localSuper.apply(c) == null)) continue;
            String hit = special.get(c + " " + name + " " + descMcp);
            if (hit != null) return hit;
            String[] sup = localSuper == null ? null : localSuper.apply(c);
            if (sup == null) {
                String[] rt = runtimeHierarchy.apply(mapClass(c));
                if (rt != null) { sup = new String[rt.length]; for (int i = 0; i < rt.length; i++) sup[i] = rt[i] == null ? null : unmapClass(rt[i]); }
            }
            if (sup != null) for (String sc : sup) if (sc != null) q.add(sc);
        }
        return name;
    }

    /**
     * mixin refmap 的值：「Lowner;name(desc)」「Lowner;name:desc」或純類名，
     * 擁有者／描述子換類名、成員名（SRG 或特殊名）換執行期名稱。
     */
    public String mapReference(String ref) {
        Matcher m = REF.matcher(ref);
        if (m.matches()) {
            String owner = m.group(1), name = m.group(2), colon = m.group(3), desc = m.group(4);
            String rn = name.isEmpty() ? name : isSrg(name) ? mapMember(owner, name, null) : mapSpecial(owner, name, desc.isEmpty() ? null : desc, null);
            return "L" + mapClass(owner) + ";" + rn + colon + mapDesc(desc);
        }
        if (ref.startsWith("net/minecraft/") && !ref.startsWith("net/minecraftforge/") && ref.indexOf(';') < 0 && ref.indexOf('(') < 0) return mapClass(ref);
        return mapConstant(ref);
    }

    /** 方法／欄位描述子裡的類名 */
    public String mapDesc(String desc) {
        Matcher m = DESC_CLASS.matcher(desc);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("L" + mapClass(m.group(1)) + ";"));
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 程式碼裡的字串常數（反射用）：SRG 成員名、MCP 類名（點或斜線寫法）、描述子。
     * Forge 本身在 MCP 名稱的執行環境下用這些字串，換到 Ornithe 底下就要換成執行期名稱。
     */
    public String mapConstant(String s) {
        if (s.startsWith("net.minecraft.") && !s.startsWith("net.minecraftforge.")) {
            String r = classes.get(s.replace('.', '/'));
            if (r != null) return r.replace('/', '.');
        }
        if (s.startsWith("net/minecraft/") && !s.startsWith("net/minecraftforge/")) {
            String r = classes.get(s);
            if (r != null) return r;
        }
        if (s.contains("Lnet/minecraft/")) s = mapDesc(s);
        return mapSrgInString(s);
    }

    /** 任意字串裡出現的 SRG 名稱都換掉（反射用的字串常數、mixin refmap） */
    public String mapSrgInString(String s) {
        Matcher m = SRG.matcher(s);
        if (!m.find()) return s;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(members.getOrDefault(m.group(), m.group())));
        m.appendTail(sb);
        return sb.toString();
    }
}
