package dev.s1mp1e.o.util;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 反射用的成員名稱：開發環境（Loom runClient）執行期是 Feather 名，正式版（玩家的遊戲）是 Ornithe gen2 intermediary 名。
 * 字串不會被 Loom 重新對應，所以移植器把每個 SRG 字串換成 {@code Names.of(feather, intermediary)}。
 */
public final class Names {
    private Names() {}

    private static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment();

    public static String of(String named, String intermediary) {
        return DEV ? named : intermediary;
    }
}
