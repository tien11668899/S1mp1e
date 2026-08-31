//! S1mp1e core as a library, so BOTH the Tauri GUI (main.rs) and the standalone
//! `itest` CLI (bin/itest.rs — what the Avalonia UI actually drives) share the same
//! install / launch / Microsoft-auth logic. Adding this lib is what wires the real
//! account into launch: the CLI's `login` runs the genuine MSA device-code flow and
//! saves the account, and `play` resolves that account for online launch instead of
//! the offline placeholder.

pub mod paths;
pub mod meta;
pub mod download;
pub mod install;
pub mod launch;
pub mod config;
pub mod auth;
