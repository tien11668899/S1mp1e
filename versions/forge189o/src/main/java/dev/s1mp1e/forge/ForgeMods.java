package dev.s1mp1e.forge;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** mods 資料夾（含 mods/1.8.9）裡的 Forge 模組 jar：沒有 fabric.mod.json、有 mcmod.info 或 Forge 標記的。 */
public final class ForgeMods {
    private static List<File> jars;
    /** 已在 preLaunch 加進 classpath 的模組：原始 jar → 重映射後的 jar（FML 之後再加就略過） */
    public static final java.util.Map<File, File> ADDED = new java.util.concurrent.ConcurrentHashMap<>();

    private ForgeMods() {
    }

    public static synchronized List<File> jars() {
        if (jars != null) return jars;
        jars = new ArrayList<>();
        File mods = new File(S1Forge.gameDir(), "mods");
        scan(mods);
        scan(new File(mods, "1.8.9"));
        S1Forge.LOG.info("找到 {} 個 Forge 模組：{}", jars.size(), jars);
        return jars;
    }

    /** mods 資料夾裡不是 Forge 模組的 jar（Fabric/Ornithe 模組）：FML 掃描時要略過 */
    public static final List<String> NON_FORGE = new ArrayList<>();

    static void scan(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".jar") || n.endsWith(".zip"));
        if (fs == null) return;
        for (File f : fs) {
            if (isForgeMod(f)) jars.add(canon(f));
            else NON_FORGE.add(f.getName());
        }
    }

    /**
     * 用 Mixin 的 Forge 模組（manifest 有 MixinConfigs／MixinConnector，原本靠 MixinTweaker）：
     * Mixin 設定必須在遊戲類別載入前註冊，所以在 preLaunch 就重映射、加進 classpath、交給 Fabric 的 Mixin。
     */
    public static void prepareMixinMods() {
        for (File f : jars()) {
            java.util.jar.Attributes a;
            try (JarFile jf = new JarFile(f)) {
                var mf = jf.getManifest();
                if (mf == null) continue;
                a = mf.getMainAttributes();
            } catch (IOException e) {
                continue;
            }
            String cfgs = a.getValue("MixinConfigs"), connector = a.getValue("MixinConnector"), core = a.getValue("FMLCorePlugin");
            if (core != null) S1Forge.LOG.warn("{} 是 coremod（{}）：ASM 類別轉換器在 Fabric 底下不支援，只載入它的 Mixin／@Mod 部分", f.getName(), core);
            if (cfgs == null && connector == null) continue;
            try {
                File rt = dev.s1mp1e.forge.remap.ModRemapper.remapped(f);
                net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(rt.toPath());
                ADDED.put(f, rt);
                if (cfgs != null) {
                    for (String c : cfgs.split(",")) {
                        c = c.trim();
                        if (c.isEmpty()) continue;
                        org.spongepowered.asm.mixin.Mixins.addConfiguration(c);
                        S1Forge.LOG.info("註冊 Forge 模組 {} 的 Mixin 設定 {}", f.getName(), c);
                    }
                }
                if (connector != null) {
                    Object con = Class.forName(connector.trim(), true, ForgeMods.class.getClassLoader()).getDeclaredConstructor().newInstance();
                    con.getClass().getMethod("connect").invoke(con);
                    S1Forge.LOG.info("執行 {} 的 MixinConnector {}", f.getName(), connector);
                }
            } catch (Throwable t) {
                S1Forge.LOG.error("準備 Mixin 型 Forge 模組 {} 失敗", f.getName(), t);
            }
        }
    }

    /** FML 用 canonical 路徑列模組；兩邊用同一種路徑才比對得上 */
    public static File canon(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    public static boolean isForgeMod(File f) {
        try (JarFile jf = new JarFile(f)) {
            if (jf.getEntry("fabric.mod.json") != null || jf.getEntry("quilt.mod.json") != null) return false;
            if (jf.getEntry("mcmod.info") != null) return true;
            var mf = jf.getManifest();
            if (mf == null) return false;
            var a = mf.getMainAttributes();
            return a.getValue("FMLCorePlugin") != null || a.getValue("TweakClass") != null || a.getValue("FMLAT") != null || a.getValue("MixinConfigs") != null;
        } catch (IOException e) {
            return false;
        }
    }
}
