package dev.s1mp1e.o.glass.asm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1.8.9 Forge 版這裡是 ASM coremod；Ornithe 版改成 mixin（dev.s1mp1e.o.mixin），這個類別只剩「修補稽核表」：
 * {@code S1mp1eMixinPlugin} 在每個 mixin 套進目標類別後記一筆 OK，DevShot 的 S1MP1E_AUDIT 照舊讀這張表。
 * mixin 沒套上（找不到注入點）時 Mixin 本身會在啟動時直接報錯（defaultRequire=1），所以這裡只會有 OK。
 */
public final class S1mp1eTransformer {
    private S1mp1eTransformer() {}

    private static final List<String> AUDIT = Collections.synchronizedList(new ArrayList<String>());
    private static volatile boolean AUDIT_FAILED = false;

    public static List<String> auditSnapshot() {
        synchronized (AUDIT) {
            return new ArrayList<String>(AUDIT);
        }
    }

    public static boolean auditFailed() {
        return AUDIT_FAILED;
    }

    public static void auditOk(String site) {
        AUDIT.add("OK   " + site);
    }

    public static void auditFail(String site, String why) {
        AUDIT.add("FAIL " + site + " (" + why + ")");
        AUDIT_FAILED = true;
    }
}
