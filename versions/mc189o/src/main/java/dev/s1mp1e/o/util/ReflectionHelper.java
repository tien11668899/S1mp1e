package dev.s1mp1e.o.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Forge ReflectionHelper 的替代品。依序嘗試每個名稱（移植器會把 SRG 名展開成「Feather 名, intermediary 名」：
 * 開發期執行期是 Feather 名、正式版是 intermediary 名），也往父類別找。找不到就丟 {@link RuntimeException}，
 * 和 Forge 的 UnableToFindFieldException 一樣讓呼叫端自己 catch。
 */
public final class ReflectionHelper {
    private ReflectionHelper() {}

    public static Field findField(Class<?> clazz, String... names) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        throw new RuntimeException("Unable to find field " + String.join("/", names) + " in " + clazz.getName());
    }

    public static <E> Method findMethod(Class<? super E> clazz, E instance, String[] names, Class<?>... params) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Method m = c.getDeclaredMethod(n, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        throw new RuntimeException("Unable to find method " + String.join("/", names) + " in " + clazz.getName());
    }
}
