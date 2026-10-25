# Every 1.21.x release

The owner asked (2026-10-25) for S1mp1e on every 1.21 release: 1.21 through 1.21.11 (twelve versions, Mojang's
manifest). Minecraft's rendering changed several times inside 1.21, so they fall into groups that each need their own
build; hotfix releases share their group's jar.

| group | versions | status | port from |
|---|---|---|---|
| A | 1.21, 1.21.1 | **done**: `mc1211` declares `>=1.21 <1.21.2`; checked on 1.21 in game (mixin audit + full DevShot sweep) | — |
| E | 1.21.6, 1.21.7, 1.21.8 | **done**: `mc1218` (Mojang mappings) is the 26.2 client — glass UI and every S1mp1e module but the CS2 knife (queued with 1.21.1's); one jar declares `>=1.21.6 <1.21.9`, 1.21.6 and 1.21.7 run it through `GLASS_SHARED`; each checked in game (mixin audit + `SmokeShot`) | 26.2 (GUI render-state model like 26.x) |
| F | 1.21.9, 1.21.10 | **done**: `mc12110` (Mojang mappings), built from `mc1218` with the 26.2 input, entity, list and loading-screen code; one jar declares `>=1.21.9 <1.21.11`, 1.21.9 runs it through `GLASS_SHARED`; each checked in game (mixin audit + `SmokeShot`) | `mc1218` + 26.2 (submit-based entity rendering, input event records) |
| G | 1.21.11 | **done**: `mc12111` (Mojang mappings), built from `mc12110` with 26.2's button, chat, tab and container-glide code; declares `~1.21.11`; checked in game (mixin audit + `SmokeShot`) | 26.2 / group F |
| C | 1.21.4 | **done**: `mc1214` (Yarn), ported from `mc1211` (1.21.2–1.21.5 keep 1.21.1's DrawContext GUI, so the Yarn port is the closer base, not the Mojang-mapped 26.x one); declares `~1.21.4`; checked in game (mixin audit + full DevShot sweep, compared shot by shot with 1.21) | `mc1211` |
| B | 1.21.2, 1.21.3 | **done**: `mc1213` (Yarn) is `mc1214` with 1.21.1's list scrolling, item-model lookup, fire overlay and server-list text; one jar declares `>=1.21.2 <1.21.4`, 1.21.2 runs it through `GLASS_SHARED`; each checked in game (mixin audit + full DevShot sweep) | `mc1214` |
| D | 1.21.5 | **done**: `mc1215` (Yarn), from `mc1214`; the immediate-GL glass runs on the new GPU device through `GlCompat`; declares `~1.21.5`; checked in game (mixin audit + full DevShot sweep) | `mc1214` |

Order of work: A → E → F → G → C → B → D. The per-version API notes above are from memory of the release notes; the
compiler has the last word.

## How a version gets its jar

- The launcher loads `s1mp1e-mods/glass-<mc>.jar` for the exact game version (`launch::pick_glass_jar`). A version that
  runs another version's jar is listed in `GLASS_SHARED` (`src-tauri/src/install.rs`); `install::glass_key` maps it for
  both the download (`ensure_glass`) and the launch. Add a version there only after it was checked in game.
- The launcher's version picker is `SupportedVersions` in `avalonia/Views/MainWindow.axaml.cs`.
- Sodium is pinned per version in `src-tauri/src/default_mods.rs` (`PINS`) where the glass restyle of its options screen
  targets one Sodium line.
- 1.21.6+ builds use Mojang's official mappings (`loom.officialMojangMappings()`): their render-state GUI is 26.x's, so
  26.x code ports almost name for name (`mc1218`, `mc12110`, `mc12111`). 1.21–1.21.5 keep the older `DrawContext` GUI
  and build with Yarn from `mc1211` (`mc1213`, `mc1214`, `mc1215`).

## Checking a version in game

`itest install fabric <mc> <mcdir>` installs the game, Fabric and the default mods. Then
`itest play <mc> fabric <mcdir> Player` with `S1MP1E_SHOT=<dir>`, `S1MP1E_SHOT_MODE=all` and `S1MP1E_MIXIN_AUDIT=1`:
the audit applies every mixin at start-up (log line `[S1mp1e] mixin audit: every target loaded`), the sweep shoots the
screens, HUD and modules. Outside a dev client the harness cannot move the cursor (its reflection uses Yarn field names),
so the `[CLICKS]` checks fail there on every version; that is the harness, not the port. Delete the `devshot` world from
the shared saves afterwards.

## Before a release

Every 1.21.x jar in `glass-mods/` goes out together with the launcher that lists its version in `SupportedVersions`,
maps the shared versions in `GLASS_SHARED` (1.21 → 1.21.1, 1.21.2 → 1.21.3, 1.21.6 / 1.21.7 → 1.21.8, 1.21.9 → 1.21.10)
and pins Sodium 0.6.13 on 1.21.2–1.21.5 (`default_mods.rs`).

`glass-mods/glass-1.21.1.jar` must be rebuilt from `mc1211` in the same release as the launcher that lists 1.21: the jar
published before 2026-10-25 declares `~1.21.1`, which Fabric refuses on 1.21.

## Lessons from group E (apply them to F, G, C, B, D)

- **One jar, several versions: prove it per version.** Copy the port's sources into a throw-away Loom project per
  extra version (only `minecraft_version` changes) and compile: every compile error is an API difference. Then run the
  mixin checker (targets, handler signatures, `@At` call sites and ordinals, `@Shadow` field types) against each
  version's Mojang-named jar. For 1.21.6–1.21.8 the only difference was the text-selection highlight
  (`GuiGraphics.textHighlight` arrived in 1.21.7; 1.21.6 has `EditBox.renderHighlight`): `EditBoxTypingMixin` gates
  vanilla's highlight off instead of redirecting a call that differs, and `TypingAnim` falls back to 1.21.6's fill on
  `NoSuchMethodError`.
- **1.21.x runs on intermediary names.** 26.x is unobfuscated, so its sources look members up by Mojang name with
  reflection; on 1.21.x that throws at runtime (it compiles and works in a dev client). Use `@Accessor` / `@Invoker`
  instead (`MultilineStringViewAccessor` replaced `getDeclaredMethod("beginIndex")`).
- **Render loops differ.** 26.x decides things outside rendering that 1.21.x decides inside `render()` (the loading
  overlay's fade-out, the level-loading narration): a 26.x HEAD cancel can stall 1.21.x forever. Check what the rest of
  a cancelled method does before porting the cancel.
- **`SmokeShot`** (`mc1218`, env `S1MP1E_SHOT=<dir>`, plus `S1MP1E_MIXIN_AUDIT=1`) is the compact in-game check that
  replaces the 26.x DevShot: title, settings, options, language and world lists, a create-world cycle button (slow
  motion), the world-loading screen, HUD, inventory, creative tabs, chat typing and selection, stonecutter / loom /
  trader lists mid-glide, sign and book typing, a name tag — about 30 PNGs in under a minute. Delete the `devshot` world
  afterwards.

## Before releasing group E

`glass-mods/glass-1.21.8.jar` (published since early on) is an old 1.17.1-era copy; the rebuilt `mc1218` jar must go
out in the same release as the launcher that lists 1.21.6–1.21.8 in `SupportedVersions` and maps 1.21.6 / 1.21.7 in
`GLASS_SHARED`, and as `launch.rs`'s library merge (a Fabric profile's library replaces the vanilla one with the same
group:artifact[:classifier] — 1.21.6+ vanilla lists ASM 9.6 next to Fabric's newer ASM).

## Notes from group F (1.21.9–1.21.10)

- Pick the base by compiling both candidates against the new version: `mc1218` gave 113 errors, `mc262` 1,632. 1.21.9
  keeps 1.21.x's GUI names (`render*`, `GuiGraphics`, `ResourceLocation`) but takes 26.x's structure in places: input
  records (`MouseButtonEvent` / `KeyEvent` / `CharacterEvent`, so `mc1218`'s `dev.s1mp1e.client.input` look-alike is
  gone), `AvatarRenderer` + `SubmitNodeCollector` for players and held items, `renderItem(g, …, entry)` /
  `renderSelection(g, entry, outline)` / entry `renderContent` for lists, `LevelLoadTracker`, `KeyMapping.Category`,
  `LoadingOverlay.isReadyToFadeOut` (the fade-out is decided in `tick()` again, as on 26.x), the loom pattern tooltip.
- `AbstractSelectionList.Entry` is protected: the list glide draws its own glass box and tells `SelectionGlassMixin`
  (via `SelectionGlideHost`) to skip the row's box, instead of calling the protected method by reflection as 26.x does.
- Name tags: `SubmitNodeCollection.submitNameTag` carries an extra distance argument, the plate colour is the 7th
  argument of `SubmitNodeStorage.NameTagSubmit`'s constructor (`NameTagPlateMixin`), and the camera matrices still come
  from `LevelRenderer.renderLevel` (`CameraRenderState` has no projection before 26.x).

## Notes from group G (1.21.11)

- 1.21.11 is closer to 26.x than to 1.21.10: `Identifier`, `net.minecraft.util.Util`, `RenderTypes`, the
  `gamerules` package, `AbstractButton.renderContents` + `ActiveTextCollector` labels, `TabButton.renderLabel`,
  `MultiLineLabel.visitLines`, chat drawn through `ChatGraphicsAccess`, sampler objects (`GpuSampler` /
  `SamplerCache`; `TextureSetup.singleTexture(view, sampler)`), `ShaderSource`. 26.2 sources convert with a name map
  (`GuiGraphicsExtractor` → `GuiGraphics`, `extract*` → `render*` in mixin strings); the rest came from `mc12110`.
  Mouse coordinates are back in several container `render*` signatures (`renderSlots`, `renderTabButton`, `renderBg`
  captures) — the checker's handler-signature pass found each one.
- **Shader uniform blocks must match vanilla's layout byte for byte.** 1.21.11's `Globals` starts with
  `ivec3 CameraBlockPos; vec3 CameraOffset;` before `ScreenSize`, and `DynamicTransforms` dropped `LineWidth`. With
  the 1.21.10 blocks every glass panel read a wrong `ScreenSize` and showed one flat colour (the backdrop sampled at the
  wrong place) and the intro was black — no error anywhere, only the pictures show it. Diff the vanilla
  `shaders/include/*.glsl` of each new version against the previous one before testing.
- The mixin checker now checks nested mixins (`Outer$Inner`) separately; filtering them out had hidden
  `GuiAlphaRenderStateMixin$Rect`'s `Matrix3x2f` → `Matrix3x2fc` change until it crashed in game.
- Block outline width: 1.21.11 passes the width as an argument of `renderHitOutline`, so `BlockOutlineMixin` sets it
  there and `OutlineLineWidthMixin` is gone.

## Notes from group C (1.21.4)

- **Base.** 1.21.2–1.21.5 still draw the GUI through `DrawContext` (the render-state GUI came with 1.21.6), so `mc1214`
  is `mc1211` (Yarn) moved forward: about 100 compile errors and 96 mixin-checker problems, against thousands from a
  26.x base. Loom 1.17 + Gradle 9.7 build Yarn projects fine; the first build has to be online.
- **Draw calls.** `drawTexture` / `drawGuiTexture` take a leading `Function<Identifier, RenderLayer>` (the old 256×256
  blit is now `drawTexture(layers, id, x, y, (float) u, (float) v, w, h, 256, 256)`), there is no `z` and no
  `setShaderColor` on `DrawContext` (the colour is an ARGB argument), `drawItemInSlot` is `drawStackOverlay`, sprites
  go through `drawGuiTexture(layers, id, x, y, w, h, colour)` and every textured blit ends in the private
  `drawTexturedQuad`. Most redirects were converted by a one-off script; the rest by hand.
- **The GUI is batched.** 1.21.1 flushed each `fill` / blit as it came (`tryDraw`); 1.21.2+ queues everything until
  `DrawContext.draw()` and reads the RenderSystem model-view and shader colour when the batch is flushed. Code that
  mixed vanilla draws with immediate-GL glass therefore drew the glass under things queued earlier (the settings colour
  swatch vanished under its checkerboard). `GlassRenderer` now flushes the GUI's consumers before every immediate-GL
  quad, the HUD lift flushes at the start and end of the lifted region, the hotbar flushes before its scale, and the
  item-tooltip card flushes the slots before it grabs the backdrop.
- **Moved APIs.** Status effects in inventories: `StatusEffectsDisplay` (was `AbstractInventoryScreen`). Lists and
  text areas share `ScrollableWidget` (`getScrollY` / `setScrollY` / `drawScrollbar`), so one scroller mixin serves
  both and the list glide reaches the wheel through `ListScrollGlide`. Container slots: `drawSlots`,
  `drawSlotHighlightBack/Front`, `getSlotAt`. Tooltips: every overload funnels into the one taking a tooltip-style
  `Identifier`. Entities: features and labels get render states (`PlayerEntityRenderState.id` picks out the local
  player), `Model` takes its root part and `render` is final. Recipe tabs: a `RecipeBookWidget.Tab` record. Item
  models: `ItemModelManager` + `ItemRenderState`. Shaders compile through `CompiledShader.compile(id, type, source)`.
- **Config bookkeeping.** Each port records its own `VERSION_ID` in `S1mp1eConfig` (`_migrated` in modules.json). The
  Mojang-mapped ports had inherited 26.2's id; `mc1218` / `mc12110` / `mc12111` now say 1.21.8 / 1.21.10 / 1.21.11.
  A version's first run adds its id to `_migrated`; on the owner's file (`_seedRank` at its maximum, seeded before this
  bookkeeping) it only fills settings the file lacks and overwrites none.
- **Line endings: text files only.** Normalising CRLF with `sed` over every file that contains `\r` also rewrote the
  PNG signatures and the PingFang TTF (`\r\n` bytes inside binaries): every TTF glyph came out empty — the game showed
  no text at all — and the SF icons and intro textures broke. Restore binaries from the source port and only touch
  `*.java` / `*.json` / shader files.

## Notes from group B (1.21.2–1.21.3)

- `mc1214`'s sources compile against 1.21.3 with 11 errors (the fixed `mc1213` also compiles against 1.21.2); what
  1.21.4 changed and these two do not have: `ScrollableWidget` as the base of lists (lists still own `getScrollAmount` /
  `setScrollAmount` / `mouseScrolled`, so `mc1213` uses 1.21.1's `ListMotionMixin` and `EntryListScrollerMixin`),
  `ItemModelManager` (`ItemRenderer.getModel` still exists), `NativeImage.upload` without the filter flags,
  `InGameOverlayRenderer.renderFireOverlay(client, matrices)` drawn immediately, `WorldRenderer.render` without the
  lightmap argument, and the shadowed server-list texts.
- The 1.21.3 jar runs 1.21.2: the mixin checker passes against both versions' Yarn jars and both were swept in game.
- Sodium is pinned to 0.6.13 on 1.21.2–1.21.4 (`default_mods.rs`), the line whose options screen the glass restyle
  targets; Modrinth's 1.21.3 build lists 1.21.2 too.

## Notes from group D (1.21.5)

- 1.21.5 moved rendering onto `GpuDevice` / `RenderPipeline` but kept the `DrawContext` GUI, so `mc1215` is `mc1214`
  plus about 150 compile fixes. The glass still draws with its own GLSL programs and raw GL; `GlCompat` supplies what
  the GPU device took away: the GL state setters (`RenderSystem.enableBlend` / `depthMask` / `bindTexture` … are gone,
  `GlStateManager` still has them and the backend reads its caches) and the render target — **every vanilla render pass
  ends by binding framebuffer 0** (the window), so each immediate-GL draw and backdrop copy binds the main target's
  FBO (`GlTexture.getOrCreateFramebuffer`) and restores the previous binding.
- Textures are `GpuTexture`s: `NativeImageBackedTexture` takes a debug label, a texture's GL id is
  `((GlTexture) getGlTexture()).getGlId()`, and partial uploads (the minimap) go through
  `CommandEncoder.writeToTexture`. Shaders compile in `GlBackend.compileShader`, whose source function the TTF
  coverage fix wraps. `ScreenshotRecorder.takeScreenshot` is asynchronous (DevShot writes in its callback).
- Renames: `Entity.prevX…` → `lastX…`, `getTickDelta` → `getTickProgress`, `ModelTransform.pivot` → `origin`,
  `ModelPart.rotate(MatrixStack)` → `applyTransform`, `GameModeSelectionScreen` → `GameModeSwitcherScreen`,
  `ModelTransformationMode` → `ItemDisplayContext` (the held-item `renderItem` lost its left-hand flag), armour is
  equipment (`getEquippedStack`), `PlayerInventory.main` / `selectedSlot` are private (`getMainStacks()`,
  `get/setSelectedSlot`).
- Sodium on 1.21.5 has only beta builds; 0.6.13 is pinned like 1.21.2–1.21.4.
