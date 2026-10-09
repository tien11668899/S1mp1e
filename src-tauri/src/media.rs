//! 遊戲內靈動島的「正在播放」來源：讀 Windows 系統媒體控制（SMTC — 按鍵盤媒體鍵時跳出來的那個面板），
//! Spotify 桌面版／網頁版、YouTube、Apple Music 等播放器都會登記在這裡。不需要任何帳號或 API 金鑰。
//!
//! 遊戲（Java，各個 MC 版本）不直接碰 WinRT：`itest play` 在遊戲執行期間開一個只綁 127.0.0.1、
//! 帶隨機金鑰的迷你 HTTP 服務，把位址用 `-Ds1mp1e.media=127.0.0.1:<port>/<token>` 傳給遊戲。
//!
//!   GET  /<token>/np            → 目前曲目 JSON（沒有在播就是 {"active":false}）
//!   GET  /<token>/art           → 封面圖檔（原始位元組；`/np` 的 artId 變了才需要重抓）
//!   GET  /<token>/cmd/toggle    → 播放／暫停（另有 next、prev）
//!
//! 只讀媒體資訊、只發「播放器本來就提供的」播放控制，不碰其他程式。

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Clone, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NowPlaying {
    pub active: bool,
    /// 來源 app（例：Spotify.exe）
    pub app: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    /// "playing" | "paused" | "stopped"
    pub status: String,
    /// 目前位置（毫秒；播放中已依最後更新時間外推）與總長，0 表示播放器沒提供
    pub position_ms: u64,
    pub duration_ms: u64,
    /// 封面識別碼：曲目或封面一變就換，遊戲據此決定要不要重抓 /art
    pub art_id: u64,
    /// 從封面算出的鮮明主色 0xRRGGBB（沒有封面時 0）：靈動島的聲波與強調色
    pub accent: u32,
    pub can_prev: bool,
    pub can_next: bool,
    pub can_toggle: bool,
}

struct State {
    np: NowPlaying,
    art: Vec<u8>,
    art_type: String,
    /// 位置外推用：取樣當下的位置與時間
    pos_at: Instant,
    pos_ms: u64,
}

pub struct MediaServer {
    pub port: u16,
    pub token: String,
}

impl MediaServer {
    /// 給遊戲的 JVM 參數
    pub fn jvm_arg(&self) -> String {
        format!("-Ds1mp1e.media=127.0.0.1:{}/{}", self.port, self.token)
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn new_token() -> String {
    // 不需要密碼學強度（只綁 127.0.0.1），但要讓同機其他程式猜不到：時間＋位址＋行程 id 混出 64 位元
    let mut x = now_ms() ^ ((std::process::id() as u64) << 32);
    let probe = Box::new(0u8);
    x ^= (&*probe as *const u8 as u64).rotate_left(17);
    let mut out = String::new();
    for _ in 0..2 {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        out.push_str(&format!("{x:016x}"));
    }
    out
}

/// 開始讀 SMTC 並開 HTTP 服務（背景執行緒，跟著 itest 行程結束）。非 Windows 或 SMTC 不可用時回傳 None。
pub fn start() -> Option<MediaServer> {
    let listener = TcpListener::bind("127.0.0.1:0").ok()?;
    let port = listener.local_addr().ok()?.port();
    let token = new_token();
    let state = Arc::new(Mutex::new(State {
        np: NowPlaying::default(),
        art: Vec::new(),
        art_type: "image/png".into(),
        pos_at: Instant::now(),
        pos_ms: 0,
    }));
    #[cfg(windows)]
    {
        let st = state.clone();
        std::thread::Builder::new().name("s1mp1e-media-poll".into()).spawn(move || smtc::poll_loop(st)).ok()?;
    }
    let st = state.clone();
    let tk = token.clone();
    std::thread::Builder::new()
        .name("s1mp1e-media-http".into())
        .spawn(move || {
            for conn in listener.incoming().flatten() {
                let st = st.clone();
                let tk = tk.clone();
                std::thread::spawn(move || {
                    let _ = handle(conn, &st, &tk);
                });
            }
        })
        .ok()?;
    Some(MediaServer { port, token })
}

fn handle(mut s: TcpStream, st: &Arc<Mutex<State>>, token: &str) -> std::io::Result<()> {
    s.set_read_timeout(Some(Duration::from_secs(2)))?;
    let mut line = String::new();
    BufReader::new(&s).read_line(&mut line)?;
    let path = line.split_whitespace().nth(1).unwrap_or("");
    let prefix = format!("/{token}/");
    let Some(rest) = path.strip_prefix(&prefix) else {
        return respond(&mut s, "403 Forbidden", "text/plain", b"forbidden");
    };
    match rest {
        "np" => {
            let body = {
                let g = st.lock().unwrap();
                let mut np = g.np.clone();
                if np.status == "playing" {
                    let p = g.pos_ms + g.pos_at.elapsed().as_millis() as u64;
                    np.position_ms = if np.duration_ms > 0 { p.min(np.duration_ms) } else { p };
                }
                serde_json::to_vec(&np).unwrap_or_default()
            };
            respond(&mut s, "200 OK", "application/json; charset=utf-8", &body)
        }
        "art" => {
            let (b, t) = {
                let g = st.lock().unwrap();
                (g.art.clone(), g.art_type.clone())
            };
            if b.is_empty() {
                respond(&mut s, "404 Not Found", "text/plain", b"no art")
            } else {
                respond(&mut s, "200 OK", &t, &b)
            }
        }
        "cmd/toggle" | "cmd/next" | "cmd/prev" => {
            #[cfg(windows)]
            let ok = smtc::command(&rest[4..]);
            #[cfg(not(windows))]
            let ok = false;
            respond(&mut s, if ok { "200 OK" } else { "409 Conflict" }, "text/plain", if ok { b"ok" } else { b"no session" })
        }
        _ => respond(&mut s, "404 Not Found", "text/plain", b"not found"),
    }
}

fn respond(s: &mut TcpStream, status: &str, ctype: &str, body: &[u8]) -> std::io::Result<()> {
    write!(s, "HTTP/1.1 {status}\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n", body.len())?;
    s.write_all(body)?;
    s.flush()
}

#[cfg(windows)]
mod smtc {
    use super::State;
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession as Session,
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };
    use windows::Storage::Streams::{DataReader, InputStreamOptions};

    /// WinRT 呼叫前，這條執行緒要先進多執行緒 apartment（重複呼叫無害）
    fn init_thread() {
        unsafe {
            let _ = windows::Win32::System::WinRT::RoInitialize(windows::Win32::System::WinRT::RO_INIT_MULTITHREADED);
        }
    }

    fn manager() -> Option<Manager> {
        init_thread();
        Manager::RequestAsync().ok()?.get().ok()
    }

    /// 開發測試用：S1MP1E_MEDIA_APP=<字串> 時只看來源 app 名稱含有它的工作階段
    /// （測試只碰假播放器，絕不會對使用者正在用的瀏覽器／Spotify 送指令）
    fn app_filter_ok(s: &Session) -> bool {
        match std::env::var("S1MP1E_MEDIA_APP") {
            Ok(f) if !f.is_empty() => s
                .SourceAppUserModelId()
                .map(|h| h.to_string().to_lowercase().contains(&f.to_lowercase()))
                .unwrap_or(false),
            _ => true,
        }
    }

    /// 優先挑「正在播放」的工作階段，沒有才用系統認定的目前工作階段
    fn pick(m: &Manager) -> Option<Session> {
        let filtered = std::env::var("S1MP1E_MEDIA_APP").map(|f| !f.is_empty()).unwrap_or(false);
        if let Ok(list) = m.GetSessions() {
            for s in list.clone() {
                if !app_filter_ok(&s) { continue; }
                if let Ok(info) = s.GetPlaybackInfo() {
                    if info.PlaybackStatus().ok() == Some(Status::Playing) {
                        return Some(s);
                    }
                }
            }
            if filtered {
                return list.into_iter().find(app_filter_ok);
            }
        }
        m.GetCurrentSession().ok()
    }

    pub fn command(which: &str) -> bool {
        let Some(m) = manager() else { return false };
        let Some(s) = pick(&m) else { return false };
        let op = match which {
            "toggle" => s.TryTogglePlayPauseAsync(),
            "next" => s.TrySkipNextAsync(),
            _ => s.TrySkipPreviousAsync(),
        };
        op.and_then(|o| o.get()).unwrap_or(false)
    }

    fn ticks_ms(t: windows::Foundation::TimeSpan) -> u64 {
        (t.Duration.max(0) / 10_000) as u64
    }

    fn hash(s: &str) -> u64 {
        // FNV-1a
        let mut h: u64 = 0xcbf29ce484222325;
        for b in s.bytes() {
            h ^= b as u64;
            h = h.wrapping_mul(0x100000001b3);
        }
        h
    }

    fn read_thumb(props: &windows::Media::Control::GlobalSystemMediaTransportControlsSessionMediaProperties) -> Option<(Vec<u8>, String)> {
        let r = props.Thumbnail().ok()?;
        let stream = r.OpenReadAsync().ok()?.get().ok()?;
        let ctype = stream.ContentType().map(|h| h.to_string()).unwrap_or_else(|_| "image/png".into());
        let size = stream.Size().ok()? as u32;
        if size == 0 || size > 8 * 1024 * 1024 {
            return None;
        }
        let reader = DataReader::CreateDataReader(&stream).ok()?;
        reader.SetInputStreamOptions(InputStreamOptions::ReadAhead).ok()?;
        let n = reader.LoadAsync(size).ok()?.get().ok()?;
        let mut buf = vec![0u8; n as usize];
        reader.ReadBytes(&mut buf).ok()?;
        Some((buf, if ctype.is_empty() { "image/png".into() } else { ctype }))
    }

    pub fn poll_loop(st: Arc<Mutex<State>>) {
        init_thread();
        let mut mgr: Option<Manager> = None;
        let mut last_key = String::new();
        loop {
            if mgr.is_none() {
                mgr = manager();
            }
            let snapshot = mgr.as_ref().and_then(pick).and_then(|s| {
                let props = s.TryGetMediaPropertiesAsync().ok()?.get().ok()?;
                let info = s.GetPlaybackInfo().ok()?;
                let tl = s.GetTimelineProperties().ok();
                let app = s.SourceAppUserModelId().map(|h| h.to_string()).unwrap_or_default();
                let title = props.Title().map(|h| h.to_string()).unwrap_or_default();
                let artist = props.Artist().map(|h| h.to_string()).unwrap_or_default();
                let album = props.AlbumTitle().map(|h| h.to_string()).unwrap_or_default();
                let status = match info.PlaybackStatus().ok() {
                    Some(Status::Playing) => "playing",
                    Some(Status::Paused) => "paused",
                    _ => "stopped",
                };
                let ctl = info.Controls().ok();
                let (pos, dur) = tl
                    .as_ref()
                    .map(|t| {
                        let start = t.StartTime().map(ticks_ms).unwrap_or(0);
                        let end = t.EndTime().map(ticks_ms).unwrap_or(0);
                        let pos = t.Position().map(ticks_ms).unwrap_or(0);
                        (pos.saturating_sub(start), end.saturating_sub(start))
                    })
                    .unwrap_or((0, 0));
                Some((props, app, title, artist, album, status, pos, dur, ctl))
            });
            match snapshot {
                Some((props, app, title, artist, album, status, pos, dur, ctl)) if !title.is_empty() => {
                    let key = format!("{app}\u{1}{title}\u{1}{artist}\u{1}{album}");
                    let new_track = key != last_key;
                    let art = if new_track { read_thumb(&props) } else { None };
                    let mut g = st.lock().unwrap();
                    // 播放器只在狀態改變時更新時間軸：位置有變（或換歌、換狀態）才重設外推起點
                    if new_track || g.np.status != status || pos != g.np.position_ms {
                        g.pos_at = Instant::now();
                        g.pos_ms = pos;
                    }
                    g.np.active = true;
                    g.np.app = app;
                    g.np.title = title;
                    g.np.artist = artist;
                    g.np.album = album;
                    g.np.status = status.into();
                    g.np.position_ms = pos;
                    g.np.duration_ms = dur;
                    if let Some(c) = ctl {
                        g.np.can_prev = c.IsPreviousEnabled().unwrap_or(false);
                        g.np.can_next = c.IsNextEnabled().unwrap_or(false);
                        g.np.can_toggle = c.IsPlayPauseToggleEnabled().unwrap_or(false);
                    }
                    if new_track {
                        match art {
                            Some((b, _t)) => match super::process_art(&b) {
                                Some((png, accent)) => {
                                    g.np.art_id = super::hash_bytes(&b) ^ hash(&key);
                                    g.np.accent = accent;
                                    g.art = png;
                                    g.art_type = "image/png".into();
                                }
                                None => {
                                    g.np.art_id = 0;
                                    g.np.accent = 0;
                                    g.art.clear();
                                }
                            },
                            None => {
                                g.np.art_id = 0;
                                g.np.accent = 0;
                                g.art.clear();
                            }
                        }
                        last_key = key;
                    } else if g.art.is_empty() {
                        // 有些播放器換歌後過一下才給封面
                        drop(g);
                        if let Some((b, _t)) = read_thumb(&props) {
                            if let Some((png, accent)) = super::process_art(&b) {
                                let mut g = st.lock().unwrap();
                                g.np.art_id = super::hash_bytes(&b) ^ hash(&key);
                                g.np.accent = accent;
                                g.art = png;
                                g.art_type = "image/png".into();
                            }
                        }
                    }
                }
                _ => {
                    let mut g = st.lock().unwrap();
                    g.np = super::NowPlaying::default();
                    g.art.clear();
                    last_key.clear();
                    mgr = None; // 下次重新取得（播放器可能剛關掉／重開）
                }
            }
            std::thread::sleep(Duration::from_millis(400));
        }
    }
}

/// 封面處理：解碼（JPEG/PNG/BMP）→ 置中裁成正方形 → 160×160 → 烤 Apple 比例圓角（半徑 23%、抗鋸齒）→ PNG；
/// 另外算鮮明主色。失敗回傳 None（遊戲就不畫封面）。
pub(crate) fn process_art(raw: &[u8]) -> Option<(Vec<u8>, u32)> {
    use image::{imageops::FilterType, GenericImageView, ImageFormat, Rgba, RgbaImage};
    let img = image::load_from_memory(raw).ok()?;
    let (w, h) = img.dimensions();
    let side = w.min(h);
    let sq = img.crop_imm((w - side) / 2, (h - side) / 2, side, side);
    const N: u32 = 160;
    let mut out: RgbaImage = sq.resize_exact(N, N, FilterType::Lanczos3).to_rgba8();
    // 主色：飽和、不太暗的像素依飽和度加權平均；不夠就用整體平均；最後拉到「黑底上醒目」的亮度與飽和度
    let (mut sr, mut sg, mut sb, mut sw) = (0f64, 0f64, 0f64, 0f64);
    let (mut ar, mut ag, mut ab) = (0f64, 0f64, 0f64);
    for p in out.pixels() {
        let (r, g, b) = (p[0] as f64 / 255.0, p[1] as f64 / 255.0, p[2] as f64 / 255.0);
        ar += r; ag += g; ab += b;
        let mx = r.max(g).max(b);
        let mn = r.min(g).min(b);
        let sat = if mx > 0.0 { (mx - mn) / mx } else { 0.0 };
        if sat > 0.35 && mx > 0.3 {
            let wgt = sat * sat;
            sr += r * wgt; sg += g * wgt; sb += b * wgt; sw += wgt;
        }
    }
    let n = (N * N) as f64;
    let (mut r, mut g, mut b) = if sw > n * 0.02 { (sr / sw, sg / sw, sb / sw) } else { (ar / n, ag / n, ab / n) };
    // 轉 HSV 調整：亮度至少 0.85、飽和度至少 0.45（灰階封面維持低飽和，只提亮）
    let mx = r.max(g).max(b);
    let mn = r.min(g).min(b);
    let sat = if mx > 0.0 { (mx - mn) / mx } else { 0.0 };
    let tgt_v = mx.max(0.85);
    let tgt_s = if sat < 0.12 { sat } else { sat.max(0.45) };
    if mx > 0.0 {
        let (hr, hg, hb) = ((r - mn) / (mx - mn).max(1e-6), (g - mn) / (mx - mn).max(1e-6), (b - mn) / (mx - mn).max(1e-6));
        // 以「最暗分量＝v(1-s)、最亮＝v」重建
        let lo = tgt_v * (1.0 - tgt_s);
        r = lo + (tgt_v - lo) * hr; g = lo + (tgt_v - lo) * hg; b = lo + (tgt_v - lo) * hb;
        if (mx - mn) < 1e-6 { r = tgt_v; g = tgt_v; b = tgt_v; }
    }
    let accent = ((r.clamp(0.0, 1.0) * 255.0) as u32) << 16 | ((g.clamp(0.0, 1.0) * 255.0) as u32) << 8 | (b.clamp(0.0, 1.0) * 255.0) as u32;
    // 圓角（4×4 超取樣算覆蓋率）
    let rad = N as f64 * 0.23;
    for y in 0..N {
        for x in 0..N {
            let mut cov = 0.0;
            for sy in 0..4 {
                for sx in 0..4 {
                    let px = x as f64 + (sx as f64 + 0.5) / 4.0;
                    let py = y as f64 + (sy as f64 + 0.5) / 4.0;
                    let cx = px.clamp(rad, N as f64 - rad);
                    let cy = py.clamp(rad, N as f64 - rad);
                    let d2 = (px - cx).powi(2) + (py - cy).powi(2);
                    if d2 <= rad * rad { cov += 1.0 / 16.0; }
                }
            }
            if cov < 1.0 {
                let p = out.get_pixel(x, y);
                out.put_pixel(x, y, Rgba([p[0], p[1], p[2], (p[3] as f64 * cov) as u8]));
            }
        }
    }
    let mut png = Vec::new();
    out.write_to(&mut std::io::Cursor::new(&mut png), ImageFormat::Png).ok()?;
    Some((png, accent))
}

pub(crate) fn hash_bytes(b: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for x in b.iter().step_by(((b.len() / 4096).max(1)) as usize) {
        h ^= *x as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h ^ (b.len() as u64)
}
