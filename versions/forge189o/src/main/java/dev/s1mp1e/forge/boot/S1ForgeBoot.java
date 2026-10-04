package dev.s1mp1e.forge.boot;

import dev.s1mp1e.forge.ForgeMods;
import dev.s1mp1e.forge.S1Forge;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Properties;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.FMLRelaunchLog;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 取代 LaunchWrapper＋FMLTweaker 的前置工作：填好 FML 需要的靜態資料（端別、版本、遊戲目錄、注入容器），
 * 真正的模組載入照 Forge 的時機在 Minecraft.init 裡（見 MinecraftMixin）。
 */
public class S1ForgeBoot implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        File game = S1Forge.gameDir();
        // FML 的 LoadController 用 Guava EventBus，例外只寫進 java.util.logging：轉到 log4j，附完整堆疊
        java.util.logging.Logger.getLogger("").addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getThrown() != null) S1Forge.LOG.error("[JUL {}] {}", r.getLoggerName(), r.getMessage(), r.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Launch.minecraftHome = game;
        Launch.assetsDir = new File(game, "assets");
        Launch.blackboard.put("fml.deobfuscatedEnvironment", Boolean.FALSE);
        Launch.blackboard.put("tweakClasses", new ArrayList<String>());
        Launch.blackboard.put("ArgumentList", new ArrayList<String>());
        Launch.classLoader = new LaunchClassLoader(new URL[0], S1ForgeBoot.class.getClassLoader());
        try {
            setStatic(FMLLaunchHandler.class, "side", Side.CLIENT);
            setStatic(FMLRelaunchLog.class, "side", Side.CLIENT);
            setStatic(FMLRelaunchLog.class, "minecraftHome", game);
            Method build = FMLInjectionData.class.getDeclaredMethod("build", File.class, LaunchClassLoader.class);
            build.setAccessible(true);
            build.invoke(null, game, Launch.classLoader);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("FML 初始化失敗", e);
        }
        FMLInjectionData.containers.add("net.minecraftforge.common.ForgeModContainer");
        // FML／Forge 容器的「來源」原本是 coremod 告訴它的 jar 位置（拿來當資源包）：改成解壓出來的資料夾
        File res = extractForgeResources(game);
        try {
            setStatic(net.minecraftforge.fml.common.asm.FMLSanityChecker.class, "fmlLocation", res);
            setStatic(net.minecraftforge.classloading.FMLForgePlugin.class, "forgeLocation", res);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        Loader.injectData(FMLInjectionData.data());
        disableSplash(game);
        ForgeMods.prepareMixinMods();
        // FML 會用 ASM5 解析 mods 裡每個 jar 找 @Mod；Fabric 模組（Java 17+ 類別）會讓它噴錯又拖慢：加進它的忽略清單
        try {
            Field f = net.minecraftforge.fml.relauncher.CoreModManager.class.getDeclaredField("ignoredModFiles");
            f.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<String> ignored = (java.util.List<String>) f.get(null);
            ignored.addAll(ForgeMods.NON_FORGE);
        } catch (ReflectiveOperationException e) {
            S1Forge.LOG.warn("設定 FML 忽略清單失敗", e);
        }
        S1Forge.LOG.info("S1mp1e Forge 相容層就緒（命名空間 {}）", S1Forge.namespace());
    }

    static File extractForgeResources(File game) {
        File dir = new File(game, ".s1forge/forgeres");
        ClassLoader cl = S1ForgeBoot.class.getClassLoader();
        try (var idx = S1ForgeBoot.class.getResourceAsStream("/s1forge/forgeres.txt")) {
            for (String path : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (path.isBlank()) continue;
                File out = new File(dir, path);
                if (out.isFile()) continue;
                try (var in = cl.getResourceAsStream(path)) {
                    if (in == null) continue;
                    out.getParentFile().mkdirs();
                    Files.write(out.toPath(), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            S1Forge.LOG.warn("解壓 Forge 資源失敗", e);
        }
        return dir;
    }

    static void setStatic(Class<?> c, String name, Object v) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    /** Forge 的載入畫面另開 GL 執行緒（共用情境），在 Pylon/LWJGL3 底下不可靠：關掉，用原版載入畫面 */
    static void disableSplash(File game) {
        File cfg = new File(game, "config/splash.properties");
        try {
            Properties p = new Properties();
            if (cfg.isFile()) try (var r = Files.newBufferedReader(cfg.toPath(), StandardCharsets.ISO_8859_1)) { p.load(r); }
            if ("false".equals(p.getProperty("enabled"))) return;
            p.setProperty("enabled", "false");
            cfg.getParentFile().mkdirs();
            try (var w = Files.newBufferedWriter(cfg.toPath(), StandardCharsets.ISO_8859_1)) { p.store(w, "S1mp1e: Forge splash disabled"); }
        } catch (IOException e) {
            S1Forge.LOG.warn("寫不了 splash.properties", e);
        }
    }
}
