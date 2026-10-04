package net.minecraft.launchwrapper;

import dev.s1mp1e.forge.S1Forge;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

/**
 * LaunchWrapper 替身：真正載入類別的是 Fabric 的 Knot（parent）。
 * 只保留模組常呼叫的方法；註冊 transformer 不會生效（印警告）。
 */
public class LaunchClassLoader extends URLClassLoader {
    private final ClassLoader parent;
    private final List<URL> sources = new ArrayList<>();
    private final List<IClassTransformer> transformers = new ArrayList<>();

    public LaunchClassLoader(URL[] sources) {
        this(sources, LaunchClassLoader.class.getClassLoader());
    }

    public LaunchClassLoader(URL[] sources, ClassLoader parent) {
        super(sources, null);
        this.parent = parent;
        Collections.addAll(this.sources, sources);
    }

    public void registerTransformer(String transformerClassName) {
        S1Forge.LOG.warn("LaunchClassLoader.registerTransformer({})：Fabric 底下不支援類別轉換器，已略過", transformerClassName);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return parent.loadClass(name);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return parent.loadClass(name);
    }

    @Override
    public URL getResource(String name) {
        return parent.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return parent.getResourceAsStream(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return parent.getResources(name);
    }

    @Override
    public void addURL(URL url) {
        sources.add(url);
        try {
            FabricLauncherBase.getLauncher().addToClassPath(Paths.get(url.toURI()));
        } catch (Exception e) {
            S1Forge.LOG.warn("addURL {}", url, e);
        }
    }

    public List<URL> getSources() {
        return sources;
    }

    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    public void addClassLoaderExclusion(String toExclude) {
    }

    public void addTransformerExclusion(String toExclude) {
    }

    public void clearNegativeEntries(Set<String> entriesToClear) {
    }

    public byte[] getClassBytes(String name) throws IOException {
        try (InputStream in = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
