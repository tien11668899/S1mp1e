package net.minecraft.launchwrapper;

import java.io.File;
import java.util.List;

/** LaunchWrapper 替身：Fabric 底下不會真的跑 tweaker。 */
public interface ITweaker {
    void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile);

    void injectIntoClassLoader(LaunchClassLoader classLoader);

    String getLaunchTarget();

    String[] getLaunchArguments();
}
