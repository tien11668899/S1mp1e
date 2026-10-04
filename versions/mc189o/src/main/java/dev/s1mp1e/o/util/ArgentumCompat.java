package dev.s1mp1e.o.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 和 Argentum（1.8.9 Ornithe 效能模組，選用）共存的小橋接。沒裝 Argentum 時全部是空操作。
 *
 * <p>Argentum 的 ScreenMixin 在 Screen.renderTooltip 開頭呼叫 {@code GradientBatch.begin()} 開始收漸層，
 * 要到工具提示畫文字時才 {@code flush()}。S1mp1e 的玻璃工具提示在同一個開頭就取消原方法，批次就永遠不會被關：
 * 之後每一幀的 fillGradient（容器背景暗化）都被收進去、再也畫不出來。所以玻璃接手工具提示時要先替它 flush。
 */
public final class ArgentumCompat {
    private ArgentumCompat() {}

    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("argentum");
    private static MethodHandle gradientFlush;
    private static boolean resolved;

    /** 結束 Argentum 正在收的 GUI 漸層批次（畫出已收的、停止再收）。 */
    public static void endGradientBatch() {
        if (!PRESENT) return;
        if (!resolved) {
            resolved = true;
            try {
                Class<?> c = Class.forName("dev.rdh.argentum.impl.render.gui.hud.GradientBatch");
                gradientFlush = MethodHandles.publicLookup().findStatic(c, "flush", MethodType.methodType(void.class));
            } catch (Throwable t) {
                System.out.println("[S1mp1e] Argentum GradientBatch not found (compat skipped): " + t);
            }
        }
        if (gradientFlush == null) return;
        try {
            gradientFlush.invokeExact();
        } catch (Throwable ignored) {
        }
    }
}
