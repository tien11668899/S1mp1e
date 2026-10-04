/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * S1mp1e 修改：Forge 的載入畫面會另開 GL 共用情境的執行緒，並依賴 coremod 給的 FML jar 位置；
 * 在 Ornithe/Pylon 底下兩者都不成立，所以整個換成不做事的版本（照用原版載入畫面）。API 與原版相同。
 */
package net.minecraftforge.fml.client;

import java.util.concurrent.Semaphore;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.resource.Identifier;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.GL11;

public class SplashProgress
{
    static final Semaphore mutex = new Semaphore(1);

    public static void start()
    {
    }

    public static int getMaxTextureSize()
    {
        return GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
    }

    public static void pause()
    {
    }

    public static void resume()
    {
    }

    public static void finish()
    {
    }

    public static void drawVanillaScreen(TextureManager renderEngine) throws LWJGLException
    {
    }

    public static void clearVanillaResources(TextureManager renderEngine, Identifier mojangLogo)
    {
    }

    public static void checkGLError(String where)
    {
    }
}
