package dev.s1mp1e.client.gui;

/**
 * 所有原生選單裡玻璃元件共用的「畫面開啟淡入」（玻璃按鈕、玻璃選項滑桿）：150 ms 的線性 0 → 1 斜坡，
 * 只要目前畫面的實例換了就重新開始。用身分比對，所以改變視窗大小（同一個畫面物件）不會重跑。
 *
 * <p>刻意共用。當按鈕和滑桿各自記「上一個畫面」時，中間沒被畫過的滑桿察覺不到又回到同一個畫面
 * （例如 選項 → 控制 → 完成），於是它會以全不透明直接跳出來，而按鈕還在淡入。
 * 每個版本都一樣（沒有 Minecraft 型別）；只能在 render thread 上用。
 */
public final class ScreenOpenFade {
    private ScreenOpenFade() {}

    private static final float DURATION_S = 0.15f;
    private static Object screen;
    private static long start;
    /** 當 {@code nanoTime < holdUntil} 時，有畫面交叉淡化正蓋著這次切換：回報為已完全開啟。 */
    private static long holdUntil;

    /** @param currentScreen 目前開著的畫面（用身分比對），在世界裡時為 null */
    public static float value(Object currentScreen) {
        long now = System.nanoTime();
        if (currentScreen != screen) { screen = currentScreen; start = now; }   // 被 hold 住時時鐘仍要走
        if (now < holdUntil) return 1f;
        float t = (now - start) / 1.0e9f / DURATION_S;
        return t <= 0f ? 0f : (t >= 1f ? 1f : t);
    }

    /**
     * 快照交叉淡化（{@code ScreenDissolve}）正把要離開的畫面疊在要進來的畫面上，直到 {@code nanoTime}：
     * 進來的畫面從第一幀起就必須在底下是完整的，所以在那之前開啟淡入一律讀 1。傳 {@code 0} 解除。
     */
    public static void holdUntil(long nanoTime) { holdUntil = nanoTime; }

    /** 畫面交叉淡化正蓋著切換時為 true。 */
    public static boolean held() { return System.nanoTime() < holdUntil; }
}
