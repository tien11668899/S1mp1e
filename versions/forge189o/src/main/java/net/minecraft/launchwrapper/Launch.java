package net.minecraft.launchwrapper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** LaunchWrapper 替身：blackboard 與 classLoader 由 S1Forge 啟動時填入。 */
public class Launch {
    public static File minecraftHome;
    public static File assetsDir;
    public static Map<String, Object> blackboard = new HashMap<>();
    public static LaunchClassLoader classLoader;
}
