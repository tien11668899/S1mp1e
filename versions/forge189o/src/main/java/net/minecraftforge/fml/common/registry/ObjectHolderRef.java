/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * S1mp1e 修改：原版用 Java 8 的 sun.reflect.ReflectionFactory 改寫 static final 欄位，Java 25 已不存在；
 * 改成 sun.misc.Unsafe 直接寫靜態欄位（遊戲啟動參數已允許 Unsafe 記憶體存取）。
 */
package net.minecraftforge.fml.common.registry;

import com.google.common.base.Throwables;
import java.lang.reflect.Field;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.resource.Identifier;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

@SuppressWarnings({"deprecation", "removal"})
class ObjectHolderRef {
    private static final sun.misc.Unsafe UNSAFE;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Field field;
    private Identifier injectedObject;
    private boolean isBlock;
    private boolean isItem;

    ObjectHolderRef(Field field, Identifier injectedObject, boolean extractFromExistingValues)
    {
        this.field = field;
        this.isBlock = Block.class.isAssignableFrom(field.getType());
        this.isItem = Item.class.isAssignableFrom(field.getType());
        if (extractFromExistingValues)
        {
            try
            {
                field.setAccessible(true);
                Object existing = field.get(null);
                // nothing is ever allowed to replace AIR
                if (existing == null || existing == GameData.getBlockRegistry().getDefaultValue())
                {
                    this.injectedObject = null;
                    this.field = null;
                    this.isBlock = false;
                    this.isItem = false;
                    return;
                }
                else
                {
                    this.injectedObject = isBlock ? GameData.getBlockRegistry().getNameForObject((Block)existing) :
                        isItem ? GameData.getItemRegistry().getNameForObject((Item)existing) : null;
                }
            } catch (Exception e)
            {
                throw Throwables.propagate(e);
            }
        }
        else
        {
            this.injectedObject = injectedObject;
        }

        if (this.injectedObject == null || !isValid())
        {
            throw new IllegalStateException(String.format("The ObjectHolder annotation cannot apply to a field that is not an Item or Block (found : %s at %s.%s)", field.getType().getName(), field.getClass().getName(), field.getName()));
        }
    }

    public boolean isValid()
    {
        return isBlock || isItem;
    }

    public void apply()
    {
        Object thing;
        if (isBlock)
        {
            thing = GameData.getBlockRegistry().getObject(injectedObject);
            if (thing == Blocks.AIR)
            {
                thing = null;
            }
        }
        else if (isItem)
        {
            thing = GameData.getItemRegistry().getObject(injectedObject);
        }
        else
        {
            thing = null;
        }

        if (thing == null)
        {
            FMLLog.getLogger().log(Level.DEBUG, "Unable to lookup {} for {}. This means the object wasn't registered. It's likely just mod options.", injectedObject, field);
            return;
        }
        try
        {
            field.setAccessible(true);
            if (field.get(null) == thing) return;   // 沒被替換：不必寫
            UNSAFE.putObjectVolatile(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), thing);
        }
        catch (Exception e)
        {
            FMLLog.log(Level.WARN, e, "Unable to set %s with value %s (%s)", this.field, thing, this.injectedObject);
        }
    }
}
