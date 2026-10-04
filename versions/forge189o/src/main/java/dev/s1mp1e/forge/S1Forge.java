package dev.s1mp1e.forge;

import dev.s1mp1e.forge.remap.SrgMap;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;

/** S1mp1e 的 Forge 1.8.9 相容層：共用狀態（命名空間、SRG 對照、遊戲目錄）。 */
public final class S1Forge {
    public static final Logger LOG = LogManager.getLogger("S1Forge");
    private static String namespace;
    private static SrgMap srg;

    private S1Forge() {
    }

    /** 執行期命名空間：dev＝named、正式＝intermediary */
    public static String namespace() {
        if (namespace == null) namespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();
        return namespace;
    }

    public static File gameDir() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    public static synchronized SrgMap srg() {
        if (srg == null) {
            try {
                srg = SrgMap.load(namespace());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            srg.setRuntimeHierarchy(S1Forge::runtimeSupers);
        }
        return srg;
    }

    /** 用類別檔資源（不載入類別）讀出 super＋介面，執行期名稱 */
    public static String[] runtimeSupers(String internalName) {
        try (InputStream in = S1Forge.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            ClassReader r = new ClassReader(in);
            String[] itf = r.getInterfaces();
            String[] out = new String[itf.length + 1];
            out[0] = r.getSuperName();
            System.arraycopy(itf, 0, out, 1, itf.length);
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
