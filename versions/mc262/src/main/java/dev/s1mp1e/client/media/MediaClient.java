package dev.s1mp1e.client.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 靈動島的「正在播放」來源：S1mp1e 啟動器（itest）在遊戲執行期間開的本機服務，讀 Windows 系統媒體控制
 * （Spotify、YouTube、Apple Music…）。位址由 {@code -Ds1mp1e.media=127.0.0.1:<port>/<token>} 傳入
 * （開發時也可用環境變數 {@code S1MP1E_MEDIA}）；沒有就整個不啟動，靈動島不出現。
 *
 * <p>背景執行緒每 500 ms 問一次 {@code /np}，封面識別碼變了才抓 {@code /art}（已是圓角 PNG）。
 * 渲染執行緒只讀 {@link #snapshot()} 這個不可變快照，不會被網路卡住。
 */
public final class MediaClient {
    private MediaClient() {}

    /** 一次輪詢的結果。positionMs 是取樣當下的位置；顯示時用 {@link #positionNow()} 外推。 */
    public record Snapshot(boolean active, String app, String title, String artist, String album, String status,
                           long positionMs, long durationMs, long artId, int accent,
                           boolean canPrev, boolean canNext, boolean canToggle, long sampledNanos) {
        public boolean playing() { return active && "playing".equals(status); }
    }

    public static final Snapshot EMPTY = new Snapshot(false, "", "", "", "", "stopped", 0, 0, 0, 0, false, false, false, 0);

    private static volatile Snapshot snap = EMPTY;
    private static volatile byte[] artPng;
    private static volatile long artPngId;
    private static String base;   // http://127.0.0.1:port/token/
    private static boolean started;

    /** 有媒體服務可用（啟動器有傳位址）；第一次呼叫時啟動輪詢執行緒。 */
    public static synchronized boolean available() {
        if (!started) {
            started = true;
            String ep = System.getProperty("s1mp1e.media");
            if (ep == null || ep.isEmpty()) ep = System.getenv("S1MP1E_MEDIA");
            if (ep != null && ep.startsWith("-Ds1mp1e.media=")) ep = ep.substring("-Ds1mp1e.media=".length());
            if (ep != null && ep.matches("127\\.0\\.0\\.1:\\d+/[0-9a-f]+")) {
                base = "http://" + ep + "/";
                Thread t = new Thread(MediaClient::loop, "s1mp1e-media");
                t.setDaemon(true);
                t.start();
            }
        }
        return base != null;
    }

    public static Snapshot snapshot() {
        return snap;
    }

    /** 外推後的目前播放位置（毫秒）。 */
    public static long positionNow() {
        Snapshot s = snap;
        if (!s.playing()) return s.positionMs();
        long p = s.positionMs() + (System.nanoTime() - s.sampledNanos()) / 1_000_000L;
        return s.durationMs() > 0 ? Math.min(p, s.durationMs()) : p;
    }

    /** 最新的封面 PNG 與它的識別碼（還沒抓到時 png 為 null）。 */
    public static byte[] artPng() { return artPng; }
    public static long artPngId() { return artPngId; }

    /** 播放控制："toggle" | "next" | "prev"。非同步送出，送完立刻重新輪詢一次。 */
    public static void command(String which) {
        if (base == null) return;
        Thread t = new Thread(() -> {
            get("cmd/" + which, 1500);
            poll();
        }, "s1mp1e-media-cmd");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            poll();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static synchronized void poll() {
        byte[] body = get("np", 1500);
        if (body == null) {
            snap = EMPTY;   // 服務沒回應：當作沒在播
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            Snapshot s = new Snapshot(o.get("active").getAsBoolean(), str(o, "app"), str(o, "title"), str(o, "artist"),
                    str(o, "album"), str(o, "status"), lng(o, "positionMs"), lng(o, "durationMs"), lng(o, "artId"),
                    (int) lng(o, "accent"), bool(o, "canPrev"), bool(o, "canNext"), bool(o, "canToggle"), System.nanoTime());
            if (s.active() && s.artId() != 0 && s.artId() != artPngId) {
                byte[] png = get("art", 3000);
                if (png != null) {
                    artPng = png;
                    artPngId = s.artId();
                }
            }
            snap = s;
        } catch (RuntimeException e) {
            snap = EMPTY;
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static long lng(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsLong() : 0L; }
    private static boolean bool(JsonObject o, String k) { return o.has(k) && o.get(k).getAsBoolean(); }

    private static byte[] get(String path, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                in.transferTo(bo);
                return bo.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
