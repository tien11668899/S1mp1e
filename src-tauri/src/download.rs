//! HTTP fetch + streamed file download with sha1 verification.

use anyhow::{anyhow, Result};
use futures_util::StreamExt;
use std::path::Path;
use tokio::io::AsyncWriteExt;

pub fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent("S1mp1e/0.1 (Minecraft launcher)")
        .build()
        .expect("http client")
}

pub async fn get_text(cl: &reqwest::Client, url: &str) -> Result<String> {
    let r = cl.get(url).send().await?.error_for_status()?;
    Ok(r.text().await?)
}

pub async fn get_json<T: serde::de::DeserializeOwned>(cl: &reqwest::Client, url: &str) -> Result<T> {
    let r = cl.get(url).send().await?.error_for_status()?;
    Ok(r.json::<T>().await?)
}

/// sha1 of a file on disk, lowercase hex — or None if the file is missing.
pub fn file_sha1(path: &Path) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    let mut h = sha1_smol::Sha1::new();
    h.update(&bytes);
    Some(h.digest().to_string())
}

/// True if the file exists and (when `sha1` is given) matches.
pub fn already_have(path: &Path, sha1: Option<&str>) -> bool {
    if !path.exists() {
        return false;
    }
    match sha1 {
        None => true,
        Some(want) => file_sha1(path).map(|got| got.eq_ignore_ascii_case(want)).unwrap_or(false),
    }
}

/// Download `url` to `dest` (creating parents), streaming. Verifies sha1 if
/// supplied. Skips the network entirely when the file already matches.
/// `on_bytes` is called with each chunk length for progress accounting.
pub async fn download_file(
    cl: &reqwest::Client,
    url: &str,
    dest: &Path,
    sha1: Option<&str>,
    mut on_bytes: impl FnMut(u64),
) -> Result<()> {
    if already_have(dest, sha1) {
        // still report the size so progress totals stay honest
        if let Ok(meta) = std::fs::metadata(dest) {
            on_bytes(meta.len());
        }
        return Ok(());
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let resp = cl.get(url).send().await?.error_for_status()?;
    let tmp = dest.with_extension("s1part");
    let mut file = tokio::fs::File::create(&tmp).await?;
    let mut hasher = sha1_smol::Sha1::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
        on_bytes(chunk.len() as u64);
    }
    file.flush().await?;
    drop(file);

    if let Some(want) = sha1 {
        let got = hasher.digest().to_string();
        if !got.eq_ignore_ascii_case(want) {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(anyhow!("sha1 mismatch for {url}: want {want}, got {got}"));
        }
    }
    tokio::fs::rename(&tmp, dest).await?;
    Ok(())
}
