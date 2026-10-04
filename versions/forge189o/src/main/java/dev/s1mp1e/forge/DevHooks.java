package dev.s1mp1e.forge;

import net.minecraft.client.Minecraft;

/** 開發用：S1FORGE_AUTOQUIT=秒數 時，標題畫面出現後過幾秒自動正常結束（沒有進世界，不會卡存檔）。 */
public final class DevHooks {
    private DevHooks() {
    }

    public static void onStarted(Minecraft mc) {
        DevTest.install();
        String s = System.getenv("S1FORGE_AUTOQUIT");
        if (s == null) return;
        long ms = (long) (Double.parseDouble(s) * 1000);
        if (ms <= 0) return;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
            S1Forge.LOG.info("S1FORGE_AUTOQUIT：結束");
            mc.stop();
        }, "s1forge-autoquit");
        t.setDaemon(true);
        t.start();
    }
}
