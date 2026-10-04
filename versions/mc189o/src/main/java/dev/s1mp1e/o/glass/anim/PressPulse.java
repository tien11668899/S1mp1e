package dev.s1mp1e.o.glass.anim;

import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.WeakHashMap;

/**
 * 按鈕的按下回饋（第 9 組）—— 1.8.9 版本的 {@code PressPulse}，對應 mc1144 的同名類別與 26.2 的
 * {@code ButtonPressPulseMixin}。按下時按鈕以自己的中心為軸在約 50 ms 內縮到約 95 %，再用約 0.25 s
 * 彈回來，就像 iOS 上的輕點。這個類別只管時鐘：{@link #press} 由 {@code ButtonWidget.playClickSound}
 * 的方法開頭蓋時間戳（1.8.9 每一次點擊都會播這個音效；1.8.9 的按鈕沒有鍵盤啟動），
 * {@link #scale} 給出目前的縮放係數，由繪製端（{@code ButtonHook}、設定頁外殼）自己套用。
 * 只能在 render thread 上用。
 */
public final class PressPulse {

    private PressPulse() {}

    private static final float DEPTH = 0.05f;
    private static final float DOWN_S = 0.05f;
    private static final float BACK_TAU = 0.075f;

    private static final WeakHashMap<ButtonWidget, Long> PRESS = new WeakHashMap<ButtonWidget, Long>();

    public static void press(ButtonWidget w) {
        if (w != null) PRESS.put(w, Long.valueOf(System.nanoTime()));
    }

    public static float scale(ButtonWidget w) {
        Long ns = PRESS.get(w);
        if (ns == null) return 1.0f;
        float t = (System.nanoTime() - ns.longValue()) / 1.0e9f;
        float dip;
        if (t < DOWN_S) {
            float u = t / DOWN_S;
            dip = u * (2.0f - u);
        } else {
            dip = (float) Math.exp(-(t - DOWN_S) / BACK_TAU);
            if (dip < 0.004f) { PRESS.remove(w); return 1.0f; }
        }
        return 1.0f - DEPTH * dip;
    }
}
