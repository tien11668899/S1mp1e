package dev.s1mp1e.client.module;

import com.seagull.liquidglass.client.render.SfIcons;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eHudCtx;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.media.AlbumArt;
import dev.s1mp1e.client.media.MediaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 「正在播放」靈動島：照 Apple Human Interface Guidelines 的 Live Activities／Dynamic Island 規格做。
 *
 * <ul>
 *   <li>收合（compact）：左邊封面、右邊專輯主色的聲波，中間留空（iPhone 那段是鏡頭）。</li>
 *   <li>展開（expanded）：封面＋曲名／歌手、聲波、進度條（已播／剩餘）、上一首／播放暫停／下一首。
 *       換歌時自動展開幾秒；有游標的畫面（聊天等）時滑鼠移上去就展開，按鈕可以點。</li>
 *   <li>外觀：不透明純黑、深色模式的細 key line、內容與外框同心等距；形狀變化用彈簧
 *       （response 0.45 s、damping 0.75 —— 逆向量測的靈動島數值，約 3% 過衝）。</li>
 * </ul>
 *
 * <p>尺寸以官方點數（iPhone 17 Pro：收合 230×36.67 pt、展開 371×84–160 pt、展開圓角 44 pt）× 0.5 換成 GUI px，
 * 再乘上「縮放」設定。資料來自 {@link MediaClient}（啟動器讀 Windows 系統媒體控制），沒有就不顯示。
 */
public final class DynamicIslandModule extends Module implements HudRenderer {

    public final Setting scale       = add(Setting.number("Scale", 1.0D, 0.6D, 1.6D));
    public final Setting offsetY     = add(Setting.integer("Y offset", 4, 0, 60));
    public final Setting autoExpand  = add(Setting.bool("Expand on track change", true));
    public final Setting hidePaused  = add(Setting.bool("Hide when paused", true));

    // 官方點數 × 0.5（GUI px）
    private static final float CW = 115f, CH = 18.33f;          // 收合 230×36.67 pt
    private static final float EW = 185.5f, EH = 80f;            // 展開 371 寬；高取 160 pt
    private static final float ER = 22f;                         // 展開圓角 44 pt
    private static final float SPRING_RESPONSE = 0.45f, SPRING_DAMPING = 0.75f;
    private static final float EXPAND_SECONDS = 3.2f, PAUSE_HIDE_SECONDS = 45f;

    private static DynamicIslandModule instance;

    // 彈簧狀態：寬、高、出現程度
    private final Spring w = new Spring(CW, SPRING_RESPONSE, SPRING_DAMPING);
    private final Spring h = new Spring(CH, SPRING_RESPONSE, SPRING_DAMPING);
    private final Spring vis = new Spring(0f, 0.38f, 1.0f);
    private final float[] bars = new float[4];
    private long lastNanos;
    private String lastTrack = "";
    private long trackChangedNanos;
    private long pausedSinceNanos;
    private float titleFade = 1f;
    private boolean expandedTarget;

    // 最近一次畫出來的外框與按鈕位置（GUI px），給點擊用
    private float bx0, by0, bx1, by1;
    private final float[][] btn = new float[3][4];
    private boolean buttonsLive;

    // DevShot 專用測試鉤：強制展開／收合、把彈簧瞬間收到目標（截圖用，正式玩法完全不動用）
    private static Boolean testExpand;   // null=正常；TRUE 強制展開；FALSE 強制收合
    private static boolean testSnap;
    public static void testForceExpand(Boolean b) { testExpand = b; }
    public static void testSnapNow() { testSnap = true; }

    public DynamicIslandModule() {
        super("DynamicIsland", "HUD");
        this.enabled = true;
        instance = this;
    }

    @Override
    public void renderHud(S1mp1eHudCtx c) {
        if (!MediaClient.available()) return;
        GuiGraphicsExtractor g = c.g();
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        MediaClient.Snapshot s = MediaClient.snapshot();
        AlbumArt.update();

        // ---- 狀態 ----
        String track = s.title() + "\u0001" + s.artist();
        if (s.active() && !track.equals(lastTrack)) {
            if (!lastTrack.isEmpty()) trackChangedNanos = now;
            else trackChangedNanos = now;   // 第一次出現也展開一下，讓人知道有在播
            lastTrack = track;
            titleFade = 0f;
        }
        if (s.playing() || !s.active()) pausedSinceNanos = now;
        boolean paused = s.active() && !s.playing();
        boolean show = s.active() && !(hidePaused.boolValue && paused && (now - pausedSinceNanos) / 1e9f > PAUSE_HIDE_SECONDS);

        float sc = (float) scale.doubleValue;
        int sw = mc.getWindow().getGuiScaledWidth();
        float mx = Float.NaN, my = Float.NaN;
        boolean cursor = mc.gui.screen() != null;
        if (cursor) {
            mx = (float) (mc.mouseHandler.xpos() * sw / Math.max(1, mc.getWindow().getScreenWidth()));
            my = (float) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / Math.max(1, mc.getWindow().getScreenHeight()));
        }
        boolean hover = cursor && show && mx >= bx0 - 2 && mx <= bx1 + 2 && my >= by0 - 2 && my <= by1 + 4;
        boolean recent = autoExpand.boolValue && (now - trackChangedNanos) / 1e9f < EXPAND_SECONDS;
        expandedTarget = show && (hover || recent);
        if (testExpand != null) expandedTarget = show && testExpand;

        vis.target = show ? 1f : 0f;
        w.target = expandedTarget ? EW : CW;
        h.target = expandedTarget ? EH : CH;
        if (testSnap) { testSnap = false; vis.snap(); w.snap(); h.snap(); }
        vis.step(dt);
        w.step(dt);
        h.step(dt);
        titleFade = Math.min(1f, titleFade + dt / 0.25f);
        float v = clamp01(vis.x);
        if (v <= 0.004f) {
            buttonsLive = false;
            bx0 = by0 = bx1 = by1 = -1000;
            return;
        }

        // ---- 外形 ----
        float p = clamp01((h.x - CH) / (EH - CH));            // 0 收合 → 1 展開（可微超過 → 彈簧過衝）
        float pw = Math.max(0f, w.x), ph = Math.max(0f, h.x);
        // 出現／消失：從小膠囊長出來（寬高一起縮放），透明度跟著
        float appear = 0.55f + 0.45f * v;
        float W = pw * appear * sc, H = ph * (0.6f + 0.4f * v) * sc;
        float cx = sw / 2f;
        float y0 = offsetY.intValue;
        float x0 = cx - W / 2f, x1 = cx + W / 2f, y1 = y0 + H;
        float R = Math.min(H / 2f, lerp(CH / 2f, ER, clamp01(p)) * sc);
        bx0 = x0; by0 = y0; bx1 = x1; by1 = y1;

        int accent = s.accent() != 0 ? (0xFF000000 | s.accent()) : 0xFFFFFFFF;
        // key line（深色環境下讓靈動島和背景分開；用一點主色）：外擴 0.6 px 的淡環
        int key = mix(0xFF2C2C2E, accent, 0.18f);
        GlassWidgets.fillRound(g, x0 - 0.6f, y0 - 0.6f, x1 + 0.6f, y1 + 0.6f, alpha(key, 0.55f * v), R + 0.6f);
        GlassWidgets.fillRound(g, x0, y0, x1, y1, alpha(0xFF000000, v), R);

        // ---- 聲波（兩種版面共用）----
        updateBars(dt, s.playing());

        // ---- 收合內容：左封面、右聲波 ----
        float ca = v * clamp01(1f - p / 0.35f);
        if (ca > 0.01f) {
            float m = (CH * sc - 12f * sc) / 2f;              // 同心：封面上下左右等距
            float art = 12f * sc;
            drawArt(g, x0 + m, y0 + m, art, ca);
            drawBars(g, x1 - m - 9.5f * sc, y0 + H / 2f, 9.5f * sc, 8.5f * sc, accent, ca);
        }

        // ---- 展開內容 ----
        float ea = v * clamp01((p - 0.55f) / 0.45f);
        buttonsLive = ea > 0.6f && cursor;
        if (ea > 0.01f) {
            float k = sc * (0.94f + 0.06f * clamp01(p));      // 內容跟著外形一起「綻放」
            float m = 9f * k;                                 // 邊距（14 pt 附近）
            float art = 30f * k;
            float ax = x0 + m, ay = y0 + m;
            drawArt(g, ax, ay, art, ea);
            // 曲名／歌手
            float tx = ax + art + 7f * k;
            float wave = 11f * k;
            float maxTw = (x1 - m - wave - 6f * k) - tx;
            float ta = ea * titleFade;
            text(g, ellipsize(s.title(), maxTw / (0.95f * k)), tx, ay + 4f * k, 0xFFFFFF, ta, 0.95f * k);
            text(g, ellipsize(s.artist(), maxTw / (0.82f * k)), tx, ay + 15.5f * k, 0x9A9A9F, ta, 0.82f * k);
            drawBars(g, x1 - m - wave, ay + 9f * k, wave, 9f * k, accent, ea);

            // 進度條：已播 ─── 剩餘
            long dur = s.durationMs(), pos = MediaClient.positionNow();
            float rowY = ay + art + 8f * k;
            float ts = 0.62f * k;
            String el = fmt(pos), rem = dur > 0 ? "-" + fmt(Math.max(0, dur - pos)) : "";
            float elW = GlassFont.width(el) * ts, remW = GlassFont.width(rem) * ts;
            float barX0 = ax + Math.max(elW, 14f * k) + 5f * k, barX1 = x1 - m - Math.max(remW, 14f * k) - 5f * k;
            float barY = rowY + 2f * k;
            text(g, el, ax, rowY, 0x9A9A9F, ea, ts);
            text(g, rem, x1 - m - remW, rowY, 0x9A9A9F, ea, ts);
            float bh = 3f * k;
            GlassWidgets.fillRound(g, barX0, barY, barX1, barY + bh, alpha(0x4DFFFFFF, ea), bh / 2f);
            if (dur > 0) {
                float f = clamp01(pos / (float) dur);
                GlassWidgets.fillRound(g, barX0, barY, barX0 + Math.max(bh, (barX1 - barX0) * f), barY + bh, alpha(0xFFFFFFFF, ea), bh / 2f);
            }

            // 控制鈕：上一首／播放暫停／下一首
            float by = rowY + 10f * k, bsz = 11f * k;
            float[] cxs = {cx - 30f * k, cx, cx + 30f * k};
            String[] glyph = {"backward.fill", s.playing() ? "pause.fill" : "play.fill", "forward.fill"};
            boolean[] en = {s.canPrev(), s.canToggle(), s.canNext()};
            for (int i = 0; i < 3; i++) {
                float gw = (i == 1 ? 11f : 15f) * k, gh = (i == 1 ? 11f : 8.5f) * k;
                float bxc = cxs[i], byc = by + bsz / 2f;
                btn[i][0] = bxc - 12f * k; btn[i][1] = byc - 9f * k; btn[i][2] = bxc + 12f * k; btn[i][3] = byc + 9f * k;
                boolean hv = buttonsLive && en[i] && mx >= btn[i][0] && mx <= btn[i][2] && my >= btn[i][1] && my <= btn[i][3];
                if (hv) GlassWidgets.fillRound(g, btn[i][0], btn[i][1], btn[i][2], btn[i][3], alpha(0x26FFFFFF, ea), 9f * k);
                int col = alpha(en[i] ? 0xFFFFFFFF : 0x66FFFFFF, ea);
                SfIcons.drawGlyph(g, glyph[i], bxc - gw / 2f, byc - gh / 2f, bxc + gw / 2f, byc + gh / 2f, col);
            }
        }
    }

    /** 有游標的畫面裡點到靈動島的按鈕：送出控制並吃掉這次點擊。 */
    public static boolean click(double mx, double my) {
        DynamicIslandModule m = instance;
        if (m == null || !m.enabled || !m.buttonsLive) return false;
        if (mx < m.bx0 || mx > m.bx1 || my < m.by0 || my > m.by1) return false;
        String[] cmd = {"prev", "toggle", "next"};
        for (int i = 0; i < 3; i++) {
            float[] b = m.btn[i];
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                MediaClient.command(cmd[i]);
                return true;
            }
        }
        return true;   // 點在靈動島上但不在按鈕：吃掉，避免點穿到後面的畫面
    }

    // ---- 繪製小工具 ----

    private static void drawArt(GuiGraphicsExtractor g, float x, float y, float size, float a) {
        if (!AlbumArt.ready()) {
            GlassWidgets.fillRound(g, x, y, x + size, y + size, alpha(0xFF2C2C2E, a), size * 0.23f);
            return;
        }
        float t = clamp01(AlbumArt.sinceSwap() / 0.28f);
        if (t < 1f) AlbumArt.draw(g, x, y, size, a * (1f - t), true);
        AlbumArt.draw(g, x, y, size, a * t, false);
    }

    /** 4 根聲波柱：播放時依多個正弦起伏，暫停時收成圓點 */
    private void updateBars(float dt, boolean playing) {
        double t = System.nanoTime() / 1e9;
        for (int i = 0; i < bars.length; i++) {
            float target = playing
                    ? (float) (0.35 + 0.65 * Math.abs(Math.sin(t * (5.1 + i * 1.7) + i * 1.3) * (0.6 + 0.4 * Math.sin(t * (2.3 + i * 0.9) + i))))
                    : 0f;
            bars[i] += (target - bars[i]) * Math.min(1f, dt * (playing ? 14f : 8f));
        }
    }

    private void drawBars(GuiGraphicsExtractor g, float x, float cy, float width, float maxH, int argb, float a) {
        int n = bars.length;
        float bw = width / (n * 2f - 1f);
        for (int i = 0; i < n; i++) {
            float bh = Math.max(bw, maxH * bars[i]);
            float bx = x + i * bw * 2f;
            GlassWidgets.fillRound(g, bx, cy - bh / 2f, bx + bw, cy + bh / 2f, alpha(argb, a), bw / 2f);
        }
    }

    private static void text(GuiGraphicsExtractor g, String s, float x, float y, int rgb, float a, float size) {
        if (s == null || s.isEmpty() || a <= 0.02f) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size, size);
        GlassFont.draw(g, s, 0, 0, rgb, a, false);
        g.pose().popMatrix();
    }

    private static String ellipsize(String s, float maxW) {
        if (s == null) return "";
        if (GlassFont.width(s) <= maxW) return s;
        String e = "…";
        int n = s.length();
        while (n > 0 && GlassFont.width(s.substring(0, n) + e) > maxW) n--;
        return n <= 0 ? e : s.substring(0, n).trim() + e;
    }

    private static String fmt(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private static float clamp01(float x) { return x < 0f ? 0f : (x > 1f ? 1f : x); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static int alpha(int argb, float k) {
        int a = Math.round(((argb >>> 24) & 0xFF) * clamp01(k));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int gg = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bb = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bb;
    }

    /**
     * 阻尼彈簧，參數用 SwiftUI 的 response（週期，秒）＋ dampingFraction。
     * 以 1/240 s 子步長半隱式積分，任何幀率下都同樣的曲線。
     */
    private static final class Spring {
        float x, vel, target;
        final float k, c;

        Spring(float start, float response, float damping) {
            x = target = start;
            float omega = (float) (2 * Math.PI / response);
            k = omega * omega;
            c = 2f * damping * omega;
        }

        void snap() { x = target; vel = 0f; }

        void step(float dt) {
            float h = 1f / 240f;
            while (dt > 0f) {
                float s = Math.min(h, dt);
                vel += (-k * (x - target) - c * vel) * s;
                x += vel * s;
                dt -= s;
            }
            if (Math.abs(x - target) < 0.001f && Math.abs(vel) < 0.001f) {
                x = target;
                vel = 0f;
            }
        }
    }
}
