# Every 1.21.x release

The owner asked (2026-10-25) for S1mp1e on every 1.21 release: 1.21 through 1.21.11 (twelve versions, Mojang's
manifest). Minecraft's rendering changed several times inside 1.21, so they fall into groups that each need their own
build; hotfix releases share their group's jar.

| group | versions | status | port from |
|---|---|---|---|
| A | 1.21, 1.21.1 | **done**: `mc1211` declares `>=1.21 <1.21.2`; checked on 1.21 in game (mixin audit + full DevShot sweep) | — |
| E | 1.21.6, 1.21.7, 1.21.8 | `mc1218` has the glass UI only, no S1mp1e modules | 26.2 (GUI render-state model like 26.x) |
| F | 1.21.9, 1.21.10 | none | 26.2 (submit-based entity rendering, input event records) |
| G | 1.21.11 | none | 26.2 / group F |
| C | 1.21.4 | none | 1.21.1 / group B (item model definitions) |
| B | 1.21.2, 1.21.3 | none | 1.21.1 (entity render states, new texture draw calls) |
| D | 1.21.5 | none | last: new GpuDevice / RenderPipeline under the old immediate GUI |

Order of work: A → E → F → G → C → B → D. The per-version API notes above are from memory of the release notes; the
compiler has the last word.

## How a version gets its jar

- The launcher loads `s1mp1e-mods/glass-<mc>.jar` for the exact game version (`launch::pick_glass_jar`). A version that
  runs another version's jar is listed in `GLASS_SHARED` (`src-tauri/src/install.rs`); `install::glass_key` maps it for
  both the download (`ensure_glass`) and the launch. Add a version there only after it was checked in game.
- The launcher's version picker is `SupportedVersions` in `avalonia/Views/MainWindow.axaml.cs`.
- Sodium is pinned per version in `src-tauri/src/default_mods.rs` (`PINS`) where the glass restyle of its options screen
  targets one Sodium line.
- New groups should build with Mojang's official mappings (`loom.officialMojangMappings()`): 26.x code then ports almost
  name for name. `mc1211` and `mc1218` use Yarn.

## Checking a version in game

`itest install fabric <mc> <mcdir>` installs the game, Fabric and the default mods. Then
`itest play <mc> fabric <mcdir> Player` with `S1MP1E_SHOT=<dir>`, `S1MP1E_SHOT_MODE=all` and `S1MP1E_MIXIN_AUDIT=1`:
the audit applies every mixin at start-up (log line `[S1mp1e] mixin audit: every target loaded`), the sweep shoots the
screens, HUD and modules. Outside a dev client the harness cannot move the cursor (its reflection uses Yarn field names),
so the `[CLICKS]` checks fail there on every version; that is the harness, not the port. Delete the `devshot` world from
the shared saves afterwards.

## Before a release

`glass-mods/glass-1.21.1.jar` must be rebuilt from `mc1211` in the same release as the launcher that lists 1.21: the jar
published before 2026-10-25 declares `~1.21.1`, which Fabric refuses on 1.21.
