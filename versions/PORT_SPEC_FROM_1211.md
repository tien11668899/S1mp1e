# Porting the 1.21.1 feature set to an older S1mp1e version line

`versions/mc1211` (Minecraft 1.21.1, Fabric, yarn) is the **reference implementation** for this port. Every class
there carries a long doc comment that says what it does, why, and which trap it works around — read those, they are the
real spec. This file is the map and the rules.

The user's order (2026-10-01): *port ALL new features and looks to every version, one version at a time:
1.20.1 → 1.19.2 → 1.18.2 → 1.17.1 → 1.16.5 → 1.15.2 → 1.14.4 → 1.13.2.* You are given exactly ONE of these.

## Hard rules (not negotiable)

- Work ONLY in your version's directory (`versions/mc<ver>`). `mc1211` and `mc262` are read-only references. Do not
  touch any other version directory, the launcher (`avalonia/`, `src-tauri/`), or `glass-mods/`.
- **No git commits, no pushes, no deployment.** Do not copy anything into `%APPDATA%\.minecraft`. The lead deploys
  after reviewing your screenshots.
- **Never start the S1mp1e launcher, never touch the user's own game.** Never read or print
  `%APPDATA%\S1mp1e\config.json` (account tokens). Never print a game process's full command line.
- The only processes you may stop are **your own dev client / Gradle JVMs**: java.exe whose command line contains
  `versions\mc<ver>` (e.g. a dev client stuck on Fabric's "incompatible mods" dialog). Classify by a wildcard match,
  do not print command lines. Anything else is not yours.
- Never close a dev client while a world is saving. The DevShot harness exits by itself (it leaves the world
  properly); let it. No exclusive fullscreen.
- One dev client at a time. Do not run two `runClient` at once.
- Fair-play visuals only: nothing that changes gameplay, reach, hitboxes, information the vanilla client does not show.
- Do not change system settings, do not install software. Missing build inputs may be fetched by Gradle itself
  (a first `remapJar`/`runClient` needs the network for LWJGL natives — then drop `--offline` once).
- **Verify by a real compile and by real screenshots, not by reading code.** A feature you could not run in the dev
  client is reported as "not verified", never as done.

## Build / run recipe (PowerShell)

```powershell
$env:TEMP="C:\Temp"; $env:TMP="C:\Temp"
$env:GRADLE_OPTS="-Djdk.net.unixdomain.tmpdir=C:\Temp -Djava.io.tmpdir=C:\Temp"
$env:JAVA_HOME="<JDK>"            # see table
Set-Location C:\Users\Administrator\source\S1mp1e\versions\mc<ver>
& .\gradlew.bat compileJava --offline --console=plain *> C:\Temp\s1port\<ver>\build.log
# screenshots: the dev client drives itself and quits
$env:S1MP1E_SHOT="C:\Temp\s1port\<ver>\<tag>"; $env:S1MP1E_SHOT_MODE="<modes>"
& .\gradlew.bat runClient --no-daemon --console=plain *> C:\Temp\s1port\<ver>\run_<tag>.log
```

| line | MC | dir | JAVA_HOME for gradlew | game JDK |
|---|---|---|---|---|
| 1.20.1 | 1.20.1 | mc1201 | `C:\Temp\jdk17dl\jdk-17.0.20.1+1` (runClient needs 17) | 17 |
| 1.19.2 | 1.19.2 | mc1190 | same | 17 |
| 1.18.2 | 1.18.2 | mc1180 | same | 17 |
| 1.17.1 | 1.17.1 | mc1171 | same | 17 (16+) |
| 1.16.5 | 1.16.5 | mc1165 | `C:\Program Files\Java\jdk-21.0.11`; toolchain = `org.gradle.java.installations.paths` in gradle.properties → `C:/Temp/jdk8dl/jdk8u504-b01` | 8 |
| 1.15.2 | 1.15.2 | mc1152 | same as 1.16.5 | 8 |
| 1.14.4 | 1.14.4 | mc1144 | same; **gradle.properties points at a JDK8 path that no longer exists — fix it to `C:/Temp/jdk8dl/jdk8u504-b01`** | 8 |
| 1.13.2 | 1.13.2 | mc1132 | Legacy Fabric; see its gradle.properties | 8 |

Notes: Bash heredocs on this machine eat backslashes and break on quotes — write patch scripts / Java files with the
file-writing tool, run `gradlew.bat` from PowerShell. Use `javap` on the Loom-remapped Minecraft jar under
`versions/mc<ver>/.gradle/loom-cache/minecraftMaven/...` to check a vanilla signature instead of guessing.
1.15.2's dev client has a known GPU white-screen on this machine (not the mod): if it still cannot render, do the
port, compile it, say so, and mirror 1.16.5's verified code as closely as possible.

## What to port

Start with an inventory: diff the file lists and the contents of `mc1211/src/main` against your line
(`comm` on sorted file lists, `diff -rq` for same-named files, compare `s1mp1e.mixins.json` and the resources:
shaders, `textures/gui/sf`, `textures/gui/intro*`). For every item decide: **port**, **adapt** (the vanilla class /
method differs), or **N/A** (the vanilla feature does not exist in this Minecraft version — say which and why).
Do not port an item blind: read the 1.21.1 class doc first, then check the vanilla code of YOUR version with javap.

Priority order (the user is waiting for the first group; if you run out of road, the later groups are what slips —
report exactly what slipped):

1. **Settings pages** — `client/gui/SettingsShell` + `ScreenShellMixin`, `GameOptionsShellMixin`,
   `OptionEntryShellMixin`, `CyclingButtonShellMixin`, `KeyEntryShellMixin`, `KeyCategoryShellMixin`,
   `LanguageEntryShellMixin`, the `suppresses(...)` hooks in the button and slider skins. Every vanilla settings page
   (Options, Skin, Music & Sounds, Controls, Mouse, Key Binds, Language, Chat, Accessibility, Online, vanilla Video
   when Sodium is absent) gets ONE layout: glass sidebar of categories with a sliding selection capsule, option rows in
   grouped glass cards (label left; iOS switch / value / slide-out slider right), page title top-left, the page's own
   buttons as capsules bottom-right. The shell is deliberately text-based (label/value split on the widget message,
   switch = value reads On/Off) so it survives older option widgets: in old versions the option screens are plain
   button grids, not an option list — harvest the screen's own buttons/sliders as rows the same way. Vanilla behaviour
   (what a click does, saving, key capture) must stay vanilla.
2. **Sodium's Video Settings page** — `glass/compat/SodiumGlass` + `glass/compat/sodium/*` + `SodiumMixinPlugin` +
   `s1mp1e.sodium.mixins.json`. Same layout as the settings pages (it is where that layout came from). The launcher
   ships a Sodium jar for your version in `%APPDATA%\.minecraft\s1mp1e-mods\<mc version>\` (0.5.13 on 1.20.1, 0.4.x on
   1.19.2/1.18.2, 0.3.4 on 1.17.1, 0.2.0 on 1.16.5; none on 1.15.2 and older → skip this item there, the vanilla Video
   page is then covered by item 1). Old Sodium lives in `me.jellysquid.mods.sodium.client.gui`. Compile against it
   with `modCompileOnly files(...)` on a copy in `libs/compat/` (if Loom refuses the jar as "built with a newer Loom",
   rewrite `Fabric-Loom-Version` in the copy's MANIFEST and drop its nested `META-INF/jars` + the `jars` entry of its
   fabric.mod.json — the copy is compile-only). To RUN it in the dev client put the ORIGINAL jar in `run/mods` and make
   sure `loader_version` in gradle.properties satisfies its `depends` (otherwise the dev client stops on a Fabric error
   dialog and never exits: that is how the first, unverified attempt on 1.21.1 shipped broken). The whole config is
   `required:false` and gated on Sodium being loaded.
3. **No text shadow** — `TextShadowMixin`: no drop shadow under any text anywhere (user request). Find the single
   point all shadowed draws pass through in your version's `TextRenderer`. Hide the CPS module's now-dead "Shadow"
   setting with `.hide()`.
4. **Attack ring smoothness** — `AttackRingModule`: read the cooldown with the partial tick
   (`getAttackCooldownProgress(tickDelta)`), not `0f` (20 Hz steps = visible stutter). Applies to 1.20.1…1.14.4.
5. **Screen transitions** — `ScreenDissolve` (snapshot cross-dissolve on `setScreen`, 220 ms), its hook in
   `MinecraftClientFadeMixin`, `ScreenOpenFade.holdUntil/held`, the tab-switch dissolves (`TabSwitchGlassMixin`,
   creative / advancements tab hooks), the draw point above toasts and tooltips (`GameRendererTooltipLayerMixin`).
   **Trap: never call `GlassProgram.ensureReady()` from the setScreen hook** (it wrecks all glass); see the memory
   note below.
6. **Lists and text** — `ListMotionMixin` (smooth wheel scroll + new-entry cascade), `ServerEntryPingFadeMixin`,
   `TypingAnim` + `EditBoxTypingMixin` + `EditBoxGlass` + `SignTypingMixin` + `BookEditTypingMixin`,
   `CommandSuggestionsFadeMixin`, `SuggestionsListGlideMixin`.
7. **HUD motion** — `ChatArrival`, `ChatCloseFade`, `TabListFade` (+ gate), `ScoreboardFade`, `BossGhost`,
   `HealthTrail` (+ `HeartTrailMixin`: mind which argument is the CURRENT health and which the displayed one — the
   first 1.21.1 port had them swapped), `ItemFlights`, `InGameHudFoodMixin`.
8. **Containers** — `RecipeBookSlide` (book slides out from behind the inventory), `RecipeCascade`,
   `RecipeBookButtonGlassMixin`, `RecipeBookInvGlideMixin`, `LoomGlassMixin`, `MerchantGlassMixin` + `MerchantGlide`,
   `StonecutterGlassMixin`, `ContainerGlass`, `TabButtonGlassMixin`.
9. **Icons and buttons** — `SfIcons` + `SfIconMixin` (SF Symbols for vanilla icon sprites; before 1.20.2 there is no
   `drawGuiTexture`, icons are regions of `widgets.png`-style atlases — hook accordingly or mark N/A per icon),
   `PressPulse` + `ButtonPressMixin`, cycle-button value roll, icon buttons that must not become empty capsules.
10. **Loading / brand** — `LoadingCard`, `LiquidLoader`, `ConnectScreenGlassMixin`, `ProgressScreenGlassMixin`,
    `LevelLoadingScreenGlassMixin`, `TaskScreenGlassMixin`, `WorldEntryScrimMixin`, `BrandIntro` + `IntroPipeline` +
    `SplashOverlayIntroMixin` + `s1mp1e_intro.fsh` + `textures/gui/intro_sdf.png`, `intro_head_sdf.png` (pure black
    background, PingFang; the shader body is version-independent, only the GLSL header differs per GL profile).

## Design rules that apply to anything you draw

- Glass corner radius for NEW pieces: the hotbar radius (`GlassCorners`). Do not change radii of pieces that already
  exist in your version.
- The hover tooltip glass card must be the top layer (above toasts, items, everything). Check it with a screenshot.
- Do not put a big glass slab behind content that is already glass.
- Motion: Apple-style springs from `Motion` (no bounce), nothing pops; 8 px grid; PingFang; brand screens pure black.
- Mixins must not use reflection with yarn names (the production jar is intermediary) — use `@Shadow` / `@Accessor` /
  a duck interface. Protected/private nested vanilla types cannot be named in a signature: use `@Mixin(targets=...)`.
- A mixin whose target is not a Minecraft class (Sodium): `remap = false`, and a method inherited from a Minecraft
  class is listed under both names, e.g. `method = {"render", "method_25394"}` (check the intermediary name in the
  mod's jar with javap).

## Verification you must deliver

- `compileJava` clean, then `build` (remapped jar) clean. Check the jar: your new classes are in it, the refmap has
  entries for the new vanilla-target mixins, `javap` shows shadowed members renamed to intermediary.
- Extend your line's DevShot harness (1.21.1 has `client/DevShotVerify.java`: a scene framework with `action`,
  `waitMs`, `shot`, `burst`, a virtual cursor, modes `settings`, `sodium`, `trans`, `gap`, `combat`, … — port the
  framework if your line lacks it) so that EVERY ported item has at least one screenshot, animations a burst of
  consecutive frames. Open every screenshot yourself and look at it. Typical defects that only show there: text
  clipped by a scissor, glass drawn over its own label, a widget drawn twice, a card without rows, a control at the old
  position, a missing first frame, something popping instead of easing.
- Run the line's existing sweep modes again afterwards (regression): the old screens must look as before, apart from
  the missing text shadow.
- Settings pages: screenshots of Options (in world AND over the title screen), Music & Sounds, Controls, Key Binds,
  Language, Chat, Accessibility (scrolled), one page in a small window (854×480), a slider being hovered, a switch
  being flipped (burst), a category switch (burst).

## Final report (your last message — it is all the lead sees)

1. Table of every inventory item: ported+verified (screenshot file names) / ported, not verified (why) / N/A (why).
2. Pre-existing bugs you found and fixed.
3. Anything that looks wrong and that you could not fix.
4. The screenshot directory, the log file names, the built jar path + size + timestamp.
5. Exact files added / changed.

## Background reading (memory notes of earlier rounds — facts, traps, measurements)

`C:\Users\Administrator\.claude\projects\C--Users-Administrator\memory\`:
`project_s1mp1e_mc1211_gap_pass.md` (the 1.21.1 round this port mirrors — traps list), `project_s1mp1e_glass_versions.md`
(per-version toolchain / yarn deltas / core-profile notes), `project_s1mp1e_appear_anims.md`,
`project_s1mp1e_anim_round2.md`, `project_s1mp1e_screen_glass_coverage.md`, `project_s1mp1e_combat_port.md`,
`project_s1mp1e_sodium_glass.md`, `reference_s1mp1e_jdk_toolchain_recovery.md`, `reference_s1mp1e_font_clarity.md`,
`feedback_mc_safe_exit.md`, `feedback_s1mp1e_tooltip_top_layer.md`, `feedback_s1mp1e_uniform_corners.md`,
`feedback_s1mp1e_no_text_shadow.md`.
