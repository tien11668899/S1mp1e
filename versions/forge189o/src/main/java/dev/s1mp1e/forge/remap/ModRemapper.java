package dev.s1mp1e.forge.remap;

import dev.s1mp1e.forge.S1Forge;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

/**
 * 把 Forge 1.8.9 模組 jar（MCP 類名＋SRG 成員名）轉成執行期名稱，存到 .s1forge/remapped/ 快取。
 * 同時做 FML 原本在載入時做的類別轉換（ClassFixer），並把字串常數裡的 SRG 名稱換掉（反射用）。
 */
public final class ModRemapper {
    /** 改了重映射邏輯就加一，舊快取自動失效 */
    private static final int VERSION = 3;

    private ModRemapper() {
    }

    public static File remapped(File in) throws IOException {
        byte[] src = Files.readAllBytes(in.toPath());
        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(src);
            md.update((S1Forge.namespace() + "/" + VERSION).getBytes());
            md.update(mapsDigest());   // 對照表一變，快取就失效
            hash = HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new IOException(e);
        }
        File dir = new File(S1Forge.gameDir(), ".s1forge/remapped");
        dir.mkdirs();
        String base = in.getName().replaceAll("\\.(jar|zip)$", "");
        File out = new File(dir, base + "-" + hash + ".jar");
        if (out.isFile()) return out;
        long t0 = System.nanoTime();
        File tmp = new File(dir, out.getName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp.toPath())) {
            remap(src, os);
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        S1Forge.LOG.info("重映射 Forge 模組 {} → {}（{} ms）", in.getName(), out.getName(), (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    static void remap(byte[] jar, OutputStream os) throws IOException {
        SrgMap m = S1Forge.srg();
        // 先讀進來：類別（建模組內部繼承表）＋資源
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, byte[]> res = new LinkedHashMap<>();
        Map<String, String[]> supers = new HashMap<>();   // 模組內類別：MCP 名 → super＋介面（MCP 名）
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // 模組自己打包的 Mixin（0.7）一律丟掉：Fabric 已經有 Mixin，兩套會互相打架
                if (e.getName().startsWith("org/spongepowered/") || e.getName().startsWith("META-INF/services/org.spongepowered")) continue;
                byte[] b = z.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    ClassReader r = new ClassReader(b);
                    String[] itf = r.getInterfaces();
                    String[] s = new String[itf.length + 1];
                    s[0] = r.getSuperName();
                    System.arraycopy(itf, 0, s, 1, itf.length);
                    String[] targets = mixinTargets(r);
                    if (targets.length > 0) {   // mixin 類別：@Shadow 成員真正的擁有者是 @Mixin 目標，歧義名稱要照它查
                        String[] t = new String[s.length + targets.length];
                        System.arraycopy(s, 0, t, 0, s.length);
                        System.arraycopy(targets, 0, t, s.length, targets.length);
                        s = t;
                    }
                    supers.put(r.getClassName(), s);
                    classes.put(e.getName(), b);
                } else {
                    res.put(e.getName(), b);
                }
            }
        }
        Function<String, String[]> local = supers::get;
        java.util.Set<String> refmaps = refmapNames(res);
        Remapper rm = new Remapper() {
            @Override
            public String map(String internalName) {
                return m.mapClass(internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return m.isSrg(name) ? m.mapMember(owner, name, local) : m.mapSpecial(owner, name, descriptor, local);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return m.isSrg(name) ? m.mapMember(owner, name, local) : m.mapSpecial(owner, name, descriptor, local);
            }

            @Override
            public Object mapValue(Object value) {
                if (value instanceof String s) return m.mapConstant(s);
                return super.mapValue(value);
            }
        };
        // 重映射後的繼承表（給 ClassFixer 判斷事件類別）
        Map<String, String> rtSuper = new HashMap<>();
        for (Map.Entry<String, String[]> e : supers.entrySet()) rtSuper.put(m.mapClass(e.getKey()), e.getValue()[0] == null ? null : m.mapClass(e.getValue()[0]));
        UnaryOperator<String> superOf = n -> rtSuper.containsKey(n) ? rtSuper.get(n) : externalSuper(n);

        try (ZipOutputStream z = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                ClassNode cn = new ClassNode();
                new ClassReader(e.getValue()).accept(new ClassRemapper(cn, rm), 0);
                ClassFixer.fix(cn, n -> isEvent(n, superOf));
                ClassWriter cw = new ClassWriter(0);
                cn.accept(cw);
                z.putNextEntry(new ZipEntry(cn.name + ".class"));
                z.write(cw.toByteArray());
                z.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : res.entrySet()) {
                String n = e.getKey();
                String up = n.toUpperCase();
                if (up.startsWith("META-INF/") && (up.endsWith(".SF") || up.endsWith(".RSA") || up.endsWith(".DSA") || up.endsWith(".EC"))) continue;   // 簽章：改過類別就不成立了
                byte[] b = e.getValue();
                if (up.equals("META-INF/MANIFEST.MF")) b = stripDigests(b);
                else if (refmaps.contains(n) || n.endsWith("refmap.json")) b = remapRefmap(b, m);
                z.putNextEntry(new ZipEntry(n));
                z.write(b);
                z.closeEntry();
            }
        }
    }

    /** 類別上的 @Mixin(value=…, targets=…) 目標（模組原本的 MCP 名稱） */
    static String[] mixinTargets(ClassReader r) {
        ClassNode cn = new ClassNode();
        r.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<org.objectweb.asm.tree.AnnotationNode> anns = new java.util.ArrayList<>();
        if (cn.invisibleAnnotations != null) anns.addAll(cn.invisibleAnnotations);
        if (cn.visibleAnnotations != null) anns.addAll(cn.visibleAnnotations);
        for (org.objectweb.asm.tree.AnnotationNode a : anns) {
            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                Object v = a.values.get(i + 1);
                if (!(v instanceof java.util.List<?> l)) continue;
                for (Object o : l) {
                    if (o instanceof org.objectweb.asm.Type t) out.add(t.getInternalName());
                    else if (o instanceof String str) out.add(str.replace('.', '/'));
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /** manifest 的 MixinConfigs 指到的設定檔裡寫的 refmap 檔名 */
    static java.util.Set<String> refmapNames(Map<String, byte[]> res) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            byte[] mf = res.get("META-INF/MANIFEST.MF");
            String cfgs = mf == null ? null : new Manifest(new java.io.ByteArrayInputStream(mf)).getMainAttributes().getValue("MixinConfigs");
            if (cfgs == null) return out;
            for (String c : cfgs.split(",")) {
                byte[] j = res.get(c.trim());
                if (j == null) continue;
                com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(new String(j, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("refmap")) out.add(o.get("refmap").getAsString());
            }
        } catch (Exception e) {
            S1Forge.LOG.warn("讀 mixin 設定失敗", e);
        }
        return out;
    }

    /**
     * mixin refmap：鍵是 mixin 註解裡的原字串（類別碼裡的註解字串會被 mapConstant 轉過，鍵也要同樣轉才對得上），
     * 值是 SRG 目標 → 換成執行期名稱。「mappings」與 data 底下每個情境都處理。
     */
    static byte[] remapRefmap(byte[] b, SrgMap m) {
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(new String(b, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("mappings")) root.add("mappings", remapRefTable(root.getAsJsonObject("mappings"), m));
            if (root.has("data")) {
                com.google.gson.JsonObject data = root.getAsJsonObject("data");
                com.google.gson.JsonObject nd = new com.google.gson.JsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> ctx : data.entrySet()) nd.add(ctx.getKey(), remapRefTable(ctx.getValue().getAsJsonObject(), m));
                root.add("data", nd);
            }
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            S1Forge.LOG.warn("refmap 重映射失敗，改用字串替換", e);
            return m.mapSrgInString(new String(b, java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static com.google.gson.JsonObject remapRefTable(com.google.gson.JsonObject t, SrgMap m) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> cls : t.entrySet()) {
            com.google.gson.JsonObject entries = new com.google.gson.JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : cls.getValue().getAsJsonObject().entrySet()) {
                entries.addProperty(m.mapConstant(e.getKey()), m.mapReference(e.getValue().getAsString()));
            }
            out.add(cls.getKey(), entries);
        }
        return out;
    }

    private static byte[] mapsDigest;

    static synchronized byte[] mapsDigest() {
        if (mapsDigest != null) return mapsDigest;
        try (InputStream in = ModRemapper.class.getResourceAsStream("/s1forge/srg2rt-" + S1Forge.namespace() + ".txt")) {
            mapsDigest = MessageDigest.getInstance("SHA-1").digest(in.readAllBytes());
        } catch (Exception e) {
            mapsDigest = new byte[0];
        }
        return mapsDigest;
    }

    static boolean isEvent(String name, UnaryOperator<String> superOf) {
        for (int guard = 0; name != null && guard < 64; guard++) {
            if (name.equals("net/minecraftforge/fml/common/eventhandler/Event")) return true;
            name = superOf.apply(name);
        }
        return false;
    }

    /** 模組外的類別（Forge／原版／其他函式庫）：讀類別檔資源，不觸發載入 */
    static String externalSuper(String name) {
        if (!name.startsWith("net/minecraftforge/")) return null;   // 只有 Forge 類別可能是 Event 子類
        String[] s = S1Forge.runtimeSupers(name);
        return s == null ? null : s[0];
    }

    static byte[] stripDigests(byte[] mf) throws IOException {
        Manifest in = new Manifest(new java.io.ByteArrayInputStream(mf));
        Manifest out = new Manifest();
        out.getMainAttributes().putAll(in.getMainAttributes());
        if (out.getMainAttributes().get(Attributes.Name.MANIFEST_VERSION) == null) out.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.write(bo);
        return bo.toByteArray();
    }

    static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
