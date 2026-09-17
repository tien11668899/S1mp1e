# In-game design-language GUI → 1.21.1 (Fabric, core-profile) — execution spec

Companion to the approved plan `~/.claude/plans/forge-1-21-1-polymorphic-thacker.md`, Workstream B.
This pins the CONCRETE technical decisions for porting the 1.8.9 config GUI (`versions/mc189/.../client/**`)
to 1.21.1 core-profile, and the pattern the other versions follow. **Every render piece must be verified
in-game (RightShift) — do not ship a static-audited build (repo history: "shipped broken builds").**

mc1211 today has ONLY the glass rendering (`glass/render/*`, `glass/mixin/*`, 5 shaders). There is **no**
`client/` package — no config screen, no widgets, no modules. All of it is new work here.

## What copies verbatim (MC/GL-independent pure Java — do first, cheap, compile-safe)
From `mc189/.../client/`: `Setting.java`, `Lang.java`, `HudBounds.java`, `ModuleManager.java` (logic),
`Module.java`, and `gui/Anim.java`. `glass/anim/Fade.java` + `Spring.java` already exist in mc1211.
These have no MC or GL imports and compile as-is. Widget/screen *logic* (`ToggleWidget` math, `S1mp1eConfigScreen`
layout, `easeScroll`, `edgeFade` call structure) also copies — only its GL backend changes.

## The one hard piece — core-profile widget/font backend (replaces `GlassWidgets` + `GlassFont`)
1.8.9's `GlassWidgets.fillRoundSmooth/gradient/drawRect` and all of `GlassFont` use `glBegin/glColor/
glVertex/glTexCoord` — **forbidden in core** (see `CORE_PROFILE_SPEC.md` §Forbidden). Rebuild them as:

- **Rounded/gradient fills** (toggle track+knob, slider track+accent, chips, swatches, dividers, dropShadow):
  do NOT re-implement a triangle-fan. Use MC 1.21.1 `DrawContext` (`class_332`, yarn `net.minecraft.client.gui.DrawContext`)
  which the Screen already hands `render(DrawContext, mouseX, mouseY, delta)`:
  - flat rects → `context.fill(x0,y0,x1,y1,argb)` (`method_25294`).
  - gradients → `context.fillGradient(...)` (`method_25296`).
  - rounded corners: 1.21.1 has no rounded-rect primitive, so keep a small **software rounded fill** built
    from `context.fill` spans + the existing glass `BTN` capsule for the WHITE glass parts (toggle knob,
    slider thumb) via `GlassRenderer.button(...)` (already core-profile). Colored rounded fills (green track,
    blue accent) → approximate with `context.fill` stadium (centre band + inset side bands, as the old cheap
    `fillRound` did) — acceptable; the AA `fillRoundSmooth` look was only needed because 1.8.9 had no scissor
    AA, DrawContext rects are already crisp.
- **Text (PingFang)** — two options, pick per verification:
  - (A) **Reuse MC's TextRenderer** via `context.drawText(...)` for a first working pass (NOT PingFang, but
    proves layout). Ship this only as a stepping stone.
  - (B) **Core-profile GlassFont** (the real deliverable): bundle `assets/s1mp1e/fonts/PingFangTC-Semibold.otf`,
    rasterise glyphs to GL textures with `java.awt` exactly as `mc189/GlassFont` does (that half is GL-era-independent),
    but DRAW each glyph quad through a new **textured-quad core program** — add a `FADE`/`TEXT` program to
    `GlassProgram` per `CORE_PROFILE_SPEC.md` §7 (vertex = shared `glass.vsh`; fragment `tex.fsh`:
    `fragColor = texture(Sampler0, vLocal) * vColor;`), and emit glyph quads through the existing
    `GlassRenderer` VAO/VBO batch (UV0 = glyph texcoords, Color = tint). This is the single most reusable new
    piece — every core-profile version (1.17.1–1.21.8) shares it.
- **Scroll-edge blur** (`menu_blur.fsh` EdgeMode + `edgeFade`): the EdgeMode shader edit + `setEdgeBlur` port
  1:1 (menu_blur is already core here); `edgeFade` must drop `glPushAttrib`/`glBegin` and emit its strip
  through the batch with `RenderSystem` state (mirror `MenuBackdrop`'s core draw).

## Screen registration + open key (Fabric 1.21.1, NOT the 1.8.9 Forge coremod path)
- `S1mp1eConfigScreen extends net.minecraft.client.gui.screen.Screen` (`class_437`); `render(DrawContext,int,int,float)`,
  `mouseClicked(double,double,int)`, `keyPressed(int,int,int)`, `mouseDragged`, `mouseScrolled(double,double,double,double)`.
- Open key: a `ClientTickEvents.END_CLIENT_TICK` (fabric-api) polling the menu KeyBinding edge — mc1211 already
  depends on fabric-api. Register the KeyBinding via `KeyBindingHelper.registerKeyBinding`. Open with `mc.setScreen(new S1mp1eConfigScreen())`.
- `SceneCapture.forceGrab()` before glass panels (same recipe); the Screen's own `renderBackground` gives the dim.

## Modules (needed or the config screen is empty)
Port `Cps/Crosshair/ArmorHud/PotionHud/OldAnimations/NoHurtCam` from `mc189/.../client/module/`. HUD text/shapes
draw via `DrawContext` in a **`HudRenderCallback`** (fabric-api) instead of the 1.8.9 Forge `RenderGameOverlayEvent`.
`implements HudBounds` unchanged; positions via the same `Setting`s. Crosshair replaces the vanilla crosshair
layer (cancel `VanillaHudElements`/the crosshair draw) — verify fair-play (function of settings + screen size only).

## Files to add under `mc1211/src/main/java/dev/s1mp1e/client/`
`Setting, Lang, HudBounds, Module, ModuleManager, S1mp1eConfig, KeybindHandler` + `gui/{Anim, GlassWidgets(core),
GlassFont(core), SettingWidgets, S1mp1eConfigScreen, S1mp1eHudEditScreen, Lang}` + `gui/widget/{Widget, ToggleWidget,
SliderWidget, ModeWidget, ColorWidget}` + `module/{Cps,Crosshair,ArmorHud,PotionHud,OldAnimations,NoHurtCam}` +
bundle the OTF + (if font option B) `shaders/tex.fsh` and a `GlassProgram` TEXT program.
Register the fabric-api tick + HudRenderCallback in `glass/S1mp1eClient.java` (ClientModInitializer, already present).

## Verify (every step, in-game)
Build `versions/mc1211` (`JAVA_HOME=<jdk21>; ./gradlew build`), deploy reobf jar to
`%APPDATA%/.minecraft/s1mp1e-mods/glass-1.21.1.jar` **only after a clean build**, launch 1.21.1 via the launcher,
press **RightShift**: confirm the config GUI renders (glass panels, jelly toggles, PingFang 繁中, sliding tabs,
scroll-edge blur, slider click-to-type) with ZERO GL errors and no white-screen. Then replicate to 1.20.1/1.19.2/
1.18.2/1.17.1/1.21.8 (mapping deltas only), then Forge 1.12.2 + Fabric 1.16.5/1.15.2/1.14.4/1.13.2 (immediate-mode —
the 1.8.9 `GlassWidgets`/`GlassFont` copy verbatim there). Adversarially review each version's render bridge before shipping.
