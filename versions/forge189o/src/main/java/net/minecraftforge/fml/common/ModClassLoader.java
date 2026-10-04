/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * S1mp1e 修改：原版依賴 LaunchWrapper 的 LaunchClassLoader；這裡改成交給 Fabric Knot 載入，
 * 加入模組時先把 Forge 模組 jar 從 SRG 名稱重映射成執行期名稱。
 */
package net.minecraftforge.fml.common;

import com.google.common.collect.ImmutableList;
import dev.s1mp1e.forge.ForgeMods;
import dev.s1mp1e.forge.S1Forge;
import dev.s1mp1e.forge.remap.ModRemapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraftforge.fml.common.asm.transformers.ModAPITransformer;
import net.minecraftforge.fml.common.discovery.ASMDataTable;

public class ModClassLoader extends URLClassLoader
{
    private static final List<String> STANDARD_LIBRARIES = ImmutableList.of("jinput.jar", "lwjgl.jar", "lwjgl_util.jar", "rt.jar");
    private final ClassLoader parent;
    private final List<File> sources = new ArrayList<File>();
    /** @Optional 介面剝除：ModAPIManager 建好表之後，重映射流程拿來用 */
    public static ModAPITransformer modApiTransformer;

    public ModClassLoader(ClassLoader parent) {
        super(new URL[0], null);
        this.parent = parent;
    }

    public void addFile(File modFile) throws MalformedURLException
    {
        if (sources.contains(modFile)) return;
        sources.add(modFile);
        if (!ForgeMods.isForgeMod(modFile))
        {
            S1Forge.LOG.info("略過非 Forge 模組檔 {}", modFile.getName());
            return;
        }
        if (ForgeMods.ADDED.containsKey(ForgeMods.canon(modFile))) return;   // preLaunch 已加（Mixin 型模組）
        try
        {
            File rt = ModRemapper.remapped(modFile);
            FabricLauncherBase.getLauncher().addToClassPath(rt.toPath());
        }
        catch (IOException e)
        {
            throw new LoaderException(e);
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        return parent.loadClass(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        return parent.loadClass(name);
    }

    @Override
    public URL getResource(String name)
    {
        return parent.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        return parent.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name)
    {
        return parent.getResourceAsStream(name);
    }

    /** Forge 會掃 classpath 上的模組；Fabric 底下 classpath 是 Fabric 模組與函式庫，不掃 */
    public File[] getParentSources() {
        return new File[0];
    }

    public List<String> getDefaultLibraries()
    {
        return STANDARD_LIBRARIES;
    }

    public boolean isDefaultLibrary(File file)
    {
        return false;
    }

    public void clearNegativeCacheFor(Set<String> classList)
    {
    }

    public ModAPITransformer addModAPITransformer(ASMDataTable dataTable)
    {
        ModAPITransformer modAPI = new ModAPITransformer();
        modAPI.initTable(dataTable);
        modApiTransformer = modAPI;
        return modAPI;
    }

    public boolean containsSource(File source)
    {
        return false;
    }
}
