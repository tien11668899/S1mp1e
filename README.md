# S1mp1e

Apple-style liquid-glass Minecraft launcher & client mod for Fabric.

**Supported versions:** 1.8.9 · 1.12.2 (Forge) · 1.13.2 (Legacy Fabric) · 1.14.4 · 1.15.2 · 1.16.5 · 1.17.1 · 1.18.2 · 1.19.2 · 1.20.1 · 1.21.1 · 26.2 (dev preview)

## Download

Grab the latest zip from the [Releases](https://github.com/tien11668899/S1mp1e/releases) page. Unzip anywhere; run `S1mp1e.exe`.

Windows 10 21H2 / Windows 11 recommended. .NET 8 desktop runtime bundled.

## Features

- Microsoft account login (device-code, no dev registration needed)
- Auto-install MC + Fabric loader for any supported version
- Modrinth mod search + one-click install (per-MC subfolders, no cross-version clashes)
- Local mod browser with enable/disable toggle + Chinese description translation
- Skin picker (import PNG)
- Liquid-glass hotbar, item name popup, screen transitions, tooltip glass — every UI surface

## Build from source

```bash
# UI (Avalonia, .NET 8)
cd avalonia
dotnet publish -c Release -r win-x64 --self-contained

# Backend CLI (Rust)
cd src-tauri
cargo build --release --bin itest

# Glass client mods (per MC version)
cd versions/mc<VER>
./gradlew build
```

## License

MIT. See [LICENSE](LICENSE).

## Credits

- Apple's Liquid Glass — visual language reference
- Fabric loader, yarn mappings, Sodium/Iris ecosystem
- Modrinth API, mineskin.org, mc-heads.net for launcher data
