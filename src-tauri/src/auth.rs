//! L4 — Microsoft device-code sign-in for genuine Minecraft accounts.
//! Flow: device code → poll MSA token → Xbox Live → XSTS → Minecraft token →
//! profile. The user authenticates on Microsoft's real page in their browser;
//! the launcher never sees the password (only the resulting OAuth tokens).

use crate::config::{now_secs, Account};
use crate::download::client;
use anyhow::{anyhow, bail, Result};
use serde::Deserialize;
use serde_json::json;

const SCOPE: &str = "XboxLive.signin offline_access";

/// Bundled Azure Public-Client ID used when the user hasn't registered their own.
/// This is the ID **PrismLauncher publishes in their source tree** — the most widely
/// shared MC OAuth client in the third-party launcher community; using it means Zero
/// setup for the end user. Before shipping S1mp1e widely, replace this with a
/// client_id from your own Azure app registration so quota is attributed to you.
pub const FALLBACK_CLIENT_ID: &str = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";

/// Pick either the user's own configured client_id or the bundled fallback.
pub fn effective_client_id(user_supplied: &str) -> &str {
    let t = user_supplied.trim();
    if t.is_empty() { FALLBACK_CLIENT_ID } else { user_supplied }
}
const DEVICECODE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";

#[derive(Deserialize, Clone)]
pub struct DeviceCode {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub interval: u64,
    pub expires_in: u64,
}

#[derive(Deserialize)]
struct MsaToken {
    access_token: String,
    refresh_token: String,
}

/// Step 1 — request a device code to show the user.
pub async fn start_device_code(client_id: &str) -> Result<DeviceCode> {
    let client_id = effective_client_id(client_id);
    let cl = client();
    let resp = cl
        .post(DEVICECODE_URL)
        .form(&[("client_id", client_id), ("scope", SCOPE)])
        .send()
        .await?;
    if !resp.status().is_success() {
        bail!("device code 請求失敗：{}", resp.text().await.unwrap_or_default());
    }
    Ok(resp.json::<DeviceCode>().await?)
}

/// Step 2 — poll until the user finishes signing in. Returns (access, refresh).
async fn poll_token(client_id: &str, dc: &DeviceCode) -> Result<MsaToken> {
    let cl = client();
    let deadline = now_secs() + dc.expires_in;
    let mut interval = dc.interval.max(1);
    loop {
        if now_secs() >= deadline {
            bail!("登入逾時，請重試");
        }
        tokio::time::sleep(std::time::Duration::from_secs(interval)).await;
        let resp = cl
            .post(TOKEN_URL)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", &dc.device_code),
            ])
            .send()
            .await?;
        if resp.status().is_success() {
            return Ok(resp.json::<MsaToken>().await?);
        }
        // pending / slow_down / declined / expired
        let body: serde_json::Value = resp.json().await.unwrap_or(json!({}));
        match body.get("error").and_then(|e| e.as_str()) {
            Some("authorization_pending") => {}
            Some("slow_down") => interval += 5,
            Some("authorization_declined") => bail!("使用者拒絕授權"),
            Some("expired_token") => bail!("裝置碼過期，請重試"),
            other => bail!("登入錯誤：{}", other.unwrap_or("unknown")),
        }
    }
}

/// Steps 3–6 — MSA access token → Xbox → XSTS → Minecraft token.
async fn ms_access_to_mc(cl: &reqwest::Client, ms_access: &str) -> Result<String> {
    // Xbox Live
    let xbl: serde_json::Value = cl
        .post(XBL_URL)
        .json(&json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={ms_access}")
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT"
        }))
        .send().await?.error_for_status()?.json().await?;
    let xbl_token = xbl["Token"].as_str().ok_or_else(|| anyhow!("no Xbox token"))?.to_string();
    let uhs = xbl["DisplayClaims"]["xui"][0]["uhs"].as_str()
        .ok_or_else(|| anyhow!("no userhash"))?.to_string();

    // XSTS
    let xsts_resp = cl
        .post(XSTS_URL)
        .json(&json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }))
        .send().await?;
    if xsts_resp.status() == reqwest::StatusCode::UNAUTHORIZED {
        let body: serde_json::Value = xsts_resp.json().await.unwrap_or(json!({}));
        let xerr = body.get("XErr").and_then(|x| x.as_u64()).unwrap_or(0);
        let msg = match xerr {
            2148916233 => "此 Microsoft 帳號沒有 Xbox 檔案，請先建立",
            2148916238 => "未成年帳號需由家長加入家庭群組",
            _ => "XSTS 授權失敗",
        };
        bail!("{msg}");
    }
    let xsts: serde_json::Value = xsts_resp.error_for_status()?.json().await?;
    let xsts_token = xsts["Token"].as_str().ok_or_else(|| anyhow!("no XSTS token"))?.to_string();

    // Minecraft login
    let mc: serde_json::Value = cl
        .post(MC_LOGIN_URL)
        .json(&json!({ "identityToken": format!("XBL3.0 x={uhs};{xsts_token}") }))
        .send().await?.error_for_status()?.json().await?;
    mc["access_token"].as_str().map(|s| s.to_string())
        .ok_or_else(|| anyhow!("no Minecraft token"))
}

/// Fetch the Minecraft profile (uuid + name); errors if the account owns no MC.
async fn fetch_profile(cl: &reqwest::Client, mc_token: &str) -> Result<(String, String)> {
    let resp = cl.get(MC_PROFILE_URL).bearer_auth(mc_token).send().await?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        bail!("此帳號未擁有 Minecraft（無 profile）");
    }
    let p: serde_json::Value = resp.error_for_status()?.json().await?;
    let id = p["id"].as_str().ok_or_else(|| anyhow!("no uuid"))?.to_string();
    let name = p["name"].as_str().ok_or_else(|| anyhow!("no name"))?.to_string();
    // dashify the 32-char uuid
    let u = format!("{}-{}-{}-{}-{}", &id[0..8], &id[8..12], &id[12..16], &id[16..20], &id[20..32]);
    Ok((u, name))
}

/// Build a full Account from a fresh MSA token pair.
async fn account_from_msa(msa: MsaToken) -> Result<Account> {
    let cl = client();
    let mc_token = ms_access_to_mc(&cl, &msa.access_token).await?;
    let (uuid, name) = fetch_profile(&cl, &mc_token).await?;
    Ok(Account {
        uuid, name, mc_token,
        mc_expires_at: now_secs() + 23 * 3600, // MC tokens last ~24h; refresh early
        msa_refresh: msa.refresh_token,
    })
}

/// Full interactive sign-in. `on_code` is called once with the device code so
/// the UI can show it while this awaits the user finishing in their browser.
pub async fn sign_in(client_id: &str, on_code: impl Fn(DeviceCode)) -> Result<Account> {
    let client_id = effective_client_id(client_id);
    let dc = start_device_code(client_id).await?;
    on_code(dc.clone());
    let msa = poll_token(client_id, &dc).await?;
    account_from_msa(msa).await
}

/// Sign-in via **OAuth 2.0 Authorization Code + localhost callback** — no device
/// code typing. Binds a one-shot listener on 127.0.0.1:0, builds the Microsoft
/// authorize URL with redirect_uri=http://127.0.0.1:PORT/, hands the URL to the
/// caller (which opens it in the browser), waits for the redirect, extracts the
/// `code` query parameter, exchanges it for an MSA token, and completes the same
/// Xbox → XSTS → Minecraft chain as sign_in.
pub async fn sign_in_auth_code(client_id: &str, on_url: impl Fn(&str)) -> Result<Account> {
    use std::io::{Read, Write};
    use std::net::TcpListener;

    // Empty user config → fall back to the bundled ID so first-time users don't
    // need to register anything on Azure to sign in.
    let client_id = effective_client_id(client_id);

    let listener = TcpListener::bind("127.0.0.1:0")?;
    let port = listener.local_addr()?.port();
    let redirect = format!("http://127.0.0.1:{port}/");
    // urlencoded pieces — scope has spaces + colons, redirect has :// and slashes.
    let scope_enc = SCOPE.replace(' ', "%20").replace(':', "%3A");
    let redir_enc = redirect.replace(':', "%3A").replace('/', "%2F");
    let auth_url = format!(
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize\
         ?client_id={client_id}&response_type=code&redirect_uri={redir_enc}\
         &scope={scope_enc}&response_mode=query&prompt=select_account"
    );
    on_url(&auth_url);

    // Block one thread on the listener with a hard timeout so a user who closes
    // the browser can't leave itest running forever.
    listener.set_nonblocking(false)?;
    let (code, err_html) = tokio::task::spawn_blocking(move || -> Result<(String, Option<String>)> {
        // 5-minute cap on the whole flow.
        for _ in 0..300 {
            listener.set_nonblocking(true)?;
            match listener.accept() {
                Ok((mut stream, _)) => {
                    stream.set_read_timeout(Some(std::time::Duration::from_secs(2))).ok();
                    let mut buf = [0u8; 4096];
                    let n = stream.read(&mut buf).unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]);
                    // request line: "GET /?code=...&state=... HTTP/1.1"
                    let path = req.lines().next().and_then(|l| l.split_whitespace().nth(1)).unwrap_or("");
                    let query = path.split_once('?').map(|(_, q)| q).unwrap_or("");
                    let mut code_v: Option<String> = None;
                    let mut err_v:  Option<String> = None;
                    for pair in query.split('&') {
                        if let Some((k, v)) = pair.split_once('=') {
                            let decoded = v.replace('+', " ")
                                .replace("%20", " ").replace("%3A", ":").replace("%2F", "/");
                            match k {
                                "code" => code_v = Some(decoded),
                                "error_description" => err_v = Some(decoded),
                                _ => {}
                            }
                        }
                    }
                    let (status, body) = if code_v.is_some() {
                        (200, "<!doctype html><meta charset=utf-8><title>已登入</title>\
                         <style>body{font:14px -apple-system,Segoe UI,PingFang TC;color:#f5f5f7;\
                         background:#1c1c1e;display:flex;align-items:center;justify-content:center;\
                         height:100vh;margin:0}</style><h1 style='font-size:22px'>✓ 已登入 S1mp1e</h1>\
                         <p style='opacity:.7'>可以關閉此頁</p>")
                    } else {
                        (400, "<!doctype html><meta charset=utf-8><title>登入失敗</title>\
                         <body style='background:#1c1c1e;color:#ff453a;font:14px sans-serif;\
                         padding:40px'>登入失敗，請回到 S1mp1e 重試")
                    };
                    let resp = format!(
                        "HTTP/1.1 {status} OK\r\nContent-Type: text/html; charset=utf-8\r\n\
                         Content-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(), body);
                    let _ = stream.write_all(resp.as_bytes());
                    let _ = stream.flush();
                    return Ok((code_v.unwrap_or_default(), err_v));
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_secs(1));
                }
                Err(e) => bail!(e),
            }
        }
        bail!("登入逾時 (5 分鐘)");
    }).await??;

    if let Some(e) = err_html { bail!("Microsoft 拒絕：{e}"); }
    if code.is_empty() { bail!("未收到授權碼"); }

    // Exchange the auth code for MSA tokens. redirect_uri MUST match exactly.
    let cl = client();
    let resp = cl.post(TOKEN_URL)
        .form(&[
            ("client_id", client_id),
            ("grant_type", "authorization_code"),
            ("code", code.as_str()),
            ("redirect_uri", redirect.as_str()),
            ("scope", SCOPE),
        ])
        .send().await?;
    if !resp.status().is_success() {
        bail!("交換 token 失敗：{}", resp.text().await.unwrap_or_default());
    }
    let msa: MsaToken = resp.json().await?;
    account_from_msa(msa).await
}

/// Silent refresh via the stored MSA refresh token.
pub async fn refresh(client_id: &str, refresh_token: &str) -> Result<Account> {
    // Empty config client_id → the bundled fallback, exactly as sign_in does.
    // WITHOUT this, an empty client_id was sent verbatim to Microsoft (which
    // rejects it), so every silent refresh failed and launch fell back to the
    // offline "Player" account — the signed-in identity never reached the game.
    let client_id = effective_client_id(client_id);
    let cl = client();
    let resp = cl
        .post(TOKEN_URL)
        .form(&[
            ("grant_type", "refresh_token"),
            ("client_id", client_id),
            ("refresh_token", refresh_token),
            ("scope", SCOPE),
        ])
        .send().await?;
    if !resp.status().is_success() {
        bail!("重新整理登入失敗，請重新登入");
    }
    let msa = resp.json::<MsaToken>().await?;
    account_from_msa(msa).await
}
