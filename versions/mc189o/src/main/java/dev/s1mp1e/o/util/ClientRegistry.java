package dev.s1mp1e.o.util;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;

/** Forge ClientRegistry.registerKeyBinding 的替代：把快捷鍵接到 GameOptions.keyBindings 尾端（控制設定頁就看得到）。 */
public final class ClientRegistry {
    private ClientRegistry() {}

    public static void registerKeyBinding(KeyBinding kb) {
        GameOptions o = Minecraft.getInstance().options;
        if (o == null) return;
        for (KeyBinding k : o.keyBindings) if (k == kb) return;
        KeyBinding[] next = Arrays.copyOf(o.keyBindings, o.keyBindings.length + 1);
        next[next.length - 1] = kb;
        o.keyBindings = next;
        // 已存檔的按鍵設定在 options 載入時就讀過了，新加的鍵要再讀一次才吃得到玩家改過的值
        try {
            o.load();
        } catch (Throwable ignored) {
        }
    }
}
