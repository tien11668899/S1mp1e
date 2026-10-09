# S1mp1e mc1211 → mc262 Port: Concrete Implementation Plan

Verified against the real trees: mc1211 has 18 module files + 33 glass mixins + `HudRenderer`/`S1mp1eClient` driver; mc262 already ships the working glass layer (`com.seagull.liquidglass.client.render.{GlassPainter,GlassPipeline,GlassRectRenderState,GlassPanels,PanelGhost,TooltipGlass}` + 14 mixins registered in `liquidglass.mixins.json`, entrypoint `LiquidGlassClient`). mc262 mod id is already `s1mp1e`.

## 1. Scaffold changes to versions/mc262

**Packaging decision — DO NOT rebrand `com.seagull.liquidglass`. Keep it; layer `dev.s1mp1e.*` on top.**
- The seagull package is the one thing that compiles and passes `runClient`. Rebranding costs: renaming 20 `.java` files + the `package` field in `liquidglass.mixins.json` + `fabric.mod.json` entrypoint + every fully-qualified `com.seagull.liquidglass.client.render.*` reference inside the mixins, with zero functional gain and high regression risk against the recovered render-state pipeline (the memory note "port from 26.2, not 1.8.9" makes this layer the source of truth). The user-facing brand is already `s1mp1e` (mod id, name). So: **glass layer stays `com.seagull.liquidglass`; ported client goes under `dev.s1mp1e.client.*` and simply `import`s the seagull render package.** Cost of this choice: two root packages coexist — acceptable and already the de-facto state.

**New packages to create under `mc262/src/main/java/dev/s1mp1e/`:**
- `dev.s1mp1e.client` — `Module`, `ModuleManager`, `S1mp1eConfig`, `Setting`, `HudBounds`, `HudBoundsProvider`, `LayoutEditable`, and the **new** `HudRenderer` (see below). Copy the non-render logic from mc1211 largely as-is (it is pure state).
- `dev.s1mp1e.client.module` — the 16 modules (drop `HudGlass.java`/`Silhouette.java` into a `hud` subpackage as shims).
- `dev.s1mp1e.client.gui` — `S1mp1eConfigScreen`, `S1mp1eHudEditScreen`, widgets (Screen/input port, finding #3).
- `dev.s1mp1e.client.mixin` — the **S1mp1e-only** mixins (crosshair/food/effects/XP), kept SEPARATE from the seagull glass mixins so the two configs stay independently buildable.

**Entrypoint + module registration — the exact `HudRenderCallback` replacement.**
`HudRenderCallback` in 26.2 fabric-rendering hands a bare `PoseStack` with no `GuiGraphicsExtractor` into a retained pipeline — it cannot reach `GuiRenderState`, so it is useless for glass. **Replacement = a driver mixin into `Hud`, modeled exactly on the existing `HudHotbarMixin`:**

- New `dev.s1mp1e.client.mixin.HudDriverMixin` `@Mixin(net.minecraft.client.gui.Hud.class)`, `@Shadow` `getCameraPlayer()`, `getFont()`, `extractSlot(...)`; `@Inject(method="extractHotbarAndDecorations", at=@At("RETURN"))` (fires once per frame, after vanilla HUD extraction, backdrop already grabbed by `GuiRendererGrabMixin`). In the callback, build a small context and fan out to modules:
  ```
  for (Module m : ModuleManager.all())
      if (m.enabled && m instanceof HudRenderer hr)
          try { hr.renderHud(ctx); } catch (Throwable ignored) {}
  ```
- **New `HudRenderer` interface signature:** `void renderHud(S1mp1eHudCtx ctx);` where `S1mp1eHudCtx` is a holder record carrying `(GuiGraphicsExtractor g, Font font, DeltaTracker delta, Player player)`. This is the shim seam (section 2) — modules never see a `DrawContext` again.
- `LiquidGlassClient.onInitializeClient()` gains: `ModuleManager.init()` (guarded) + the `ClientTickEvents.END_CLIENT_TICK` menu-key poll, but with the 26.2 input calls: `InputConstants.isKeyDown(client.getWindow(), mk)` and `Minecraft.getInstance().setScreenAndShow(new S1mp1eConfigScreen())` (NOT `setScreen`). Keep the `menuArmed`/edge-detect logic verbatim.

**`liquidglass.mixins.json` additions** — add to the `client` array: `HudDriverMixin`, and the newly-authored S1mp1e-only mixins `CrosshairHideMixin`, `FoodCaptureMixin`, `EffectsHideMixin`, `XpLevelMixin`, plus the mechanical world/input mixins (`FovMultiplierMixin`, `GameRendererHurtTiltMixin`, `HandPositionMixin`, `HeldItemSwingMixin`, `LightmapGammaMixin`, `MouseClickMixin`, `MouseZoomSensitivityMixin`, `ZoomFovMixin`, `LivingEntityRendererFlashMixin`, `FontSmoothMixin`, `SpriteContentsAccessor`). Keep them in the same JSON (single mixin package `com.seagull.liquidglass.client.mixin`) OR add a second `s1mp1e.mixins.json` with `package dev.s1mp1e.client.mixin` and register it in `fabric.mod.json` `"mixins"` array — **recommend the second config** so the S1mp1e mixins live under `dev.s1mp1e` and the seagull glass mixins stay untouched. Add `"mixins": ["liquidglass.mixins.json", "s1mp1e.mixins.json"]`.

## 2. Thin 26.2 HudGlass shim + adapter strategy

**Do NOT synthesize a fake `DrawContext`** — `GuiGraphics`/`DrawContext` does not exist in the 26.2 jar (finding #3), so an adapter object cannot wrap a real one. Instead the churn-minimizing seam is the `S1mp1eHudCtx` holder plus a rewritten static `HudGlass`. Write ONE shared shim file:

**`dev.s1mp1e.client.hud.HudGlass` (rewrite of the keystone), exact method map:**
- `glassBox(GuiGraphicsExtractor g, int x0,int y0,int x1,int y1, float alpha)` — the load-bearing one. Body:
  ```
  if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
     GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
     TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
     int ab = Math.round(alpha*255f)&0xFF;
     rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(),
         x0,y0,x1,y1, 10, 0x80E6FF00 | ab, null));   // frost .5, corner .9, lift 0
  } else {
     GlassPainter.capsule(g, x0,y0, x1-x0,y1-y0, 3f, HudGlass.fallbackArgb(alpha));
  }
  ```
  This is byte-for-byte the recovered item-name-pill recipe from `HudHotbarMixin.lg$glassItemName` (`0x80FFFF00|ab`, pad 10) — proven to render. All the mc1211 `RenderSystem.disableDepthTest`/`ctx.draw()`/`setShadowScale`/`SceneCapture.grabNow` housekeeping is **deleted** (deferred model, gotcha table).
- `pill(g, x,y,w,h,argb)` → `GlassPainter.capsule(g, x,y,w,h, Math.min(3f,...), argb)` (honours `g.pose()`).
- `roundFill(g, x,y,w,h,r,argb)` → `GlassPainter.capsule(g, x,y,w,h, r, argb)`.
- `lerpArgb(c0,c1,t)` → delegate to `GlassPainter.lerp` per-channel (or keep the mc1211 body verbatim — it is pure int math, no MC types).

**Per-module mechanical rewrite table (apply the same 6 substitutions everywhere):**
| mc1211 | mc262 |
|---|---|
| `renderHud(DrawContext ctx)` | `renderHud(S1mp1eHudCtx c)` → local `var g=c.g(); var font=c.font();` |
| `ctx.fill(x0,y0,x1,y1,argb)` | `g.fill(x0,y0,x1,y1,argb)` (identical) |
| `ctx.drawText(tr, txt, x,y,col,shadow)` | `g.text(font, Component.literal(txt), x,y,col,shadow)` |
| `ctx.getScaledWindowWidth()/Height()` | `g.guiWidth()/g.guiHeight()` |
| `ctx.getMatrices().push()/pop()` | `g.pose().pushMatrix()/popMatrix()` |
| `.translate(x,y,z)` / `.scale(sx,sy,1)` | `g.pose().translate(x,y)` / `g.pose().scale(sx,sy)` (drop z) |
| `HudGlass.glassBox(ctx,...)` | `HudGlass.glassBox(g,...)` (signature-compatible shim) |
| `Text.literal` | `Component.literal`; `mc.textRenderer` | `c.font()` |

With the shim + holder, every text-only HUD module (Coordinates, Fps, Cps, Keystrokes text) becomes a near-mechanical find/replace.

## 3. Port ORDER

**Phase A — TRIVIAL/MECHANICAL first, to validate the toolchain end-to-end (no glass, no HUD driver risk):**
1. Copy the state layer: `Module`, `ModuleManager`, `Setting`, `S1mp1eConfig`, `HudBounds*`, `LayoutEditable`, new `HudRenderer` interface + `S1mp1eHudCtx`. Compile with an empty module set.
2. Logic-only modules (drive a world/input mixin, no rendering): **`FullbrightModule`, `HandPositionModule`, `NoHurtCamModule`, `OldAnimationsModule`, `SteadyFovModule`, `ZoomModule`** and their MECHANICAL mixins (`LightmapGammaMixin`†, `HandPositionMixin`, `GameRendererHurtTiltMixin`, `HeldItemSwingMixin`†, `FovMultiplierMixin`, `ZoomFovMixin`, `MouseZoomSensitivityMixin`, `MouseClickMixin`, `SpriteContentsAccessor`). `ZoomModule` uses `InputConstants.isKeyDown(minecraft.getWindow(), key)`. († `LightmapGammaMixin` and `HeldItemSwingMixin` are flagged HARD — descriptor/target may not survive; see risks.)
3. Wire `HudDriverMixin` + register a single dummy `HudRenderer` module that draws one `HudGlass.glassBox`. **Gate 1: run `runClient`, confirm the glass box refracts.** This validates driver + accessor + pipeline before any real HUD work.

**Phase B — HARD glass HUD modules (unblocked once the shim + driver are green):**
4. Text-pill modules via the shim: **`CoordinatesHudModule`, `FpsHudModule`, `CpsModule`, `KeystrokesHudModule`** (Keystrokes reads `KeyMapping.isDown()`; springs/`HudBounds` logic is trivial).
5. Item/silhouette modules (need `Hud.extractSlot` instead of `drawItem`): **`InventoryHudModule`, `ArmorHudModule`, `PotionHudModule`, `Silhouette`, `HungerSaturationHudModule`, `XpFlowHudModule`, `CrosshairModule`**. These need the newly-authored mixins below to feed them.

**Phase C — S1mp1e-ONLY mixins that must be NEWLY authored for 26.2** (not present in the recovered seagull set; targets verified in findings #1/#4):
- **Hotbar/status lift** — already fused into the recovered `HudHotbarMixin` (`DECO_LIFT=7`, single `g.pose()` wrap around `extractHotbarAndDecorations`). Do NOT re-port mc1211 `InGameHudMixin` 1:1; its per-method `renderStatusBars`/`renderExperienceBar` lifts are dead. Only the XP-level-relocation piece survives → separate mixin below.
- **`CrosshairHideMixin`** — `@Mixin(Hud.class)` `@Inject(method="extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at=@At("HEAD"), cancellable=true)`; cancel when `CrosshairModule` active, then module draws its own ring as render-states.
- **`FoodCaptureMixin`** — `@Mixin(Hud.class)` on `extractFood(GuiGraphicsExtractor, Player, int top, int right)` HEAD (1:1 arg shape from mc1211 `renderFood`); rect read off `g.pose()` (Matrix3x2f), not `RenderSystem.getModelViewStack()`.
- **`EffectsHideMixin`** — `@Mixin(Hud.class)` on `extractEffects(GuiGraphicsExtractor, DeltaTracker)` HEAD cancellable (was `renderStatusEffectOverlay`), so `PotionHudModule` re-issues its glass icons.
- **`XpLevelMixin`** — **the sharpest trap**: XP is NOT on `Hud` in 26.2. Target the static `net.minecraft.client.gui.contextualbar.ContextualBar.extractExperienceLevel(GuiGraphicsExtractor, Font, int)` (and/or `ExperienceBar.extractRenderState`). Both `XpFlowHudModule.DECO_LIFT` coupling and the old "XP level at status row" cancel+redraw retarget here.
- **Tooltip Z-order** — already covered by recovered `TooltipGlassMixin` (`TooltipRenderUtil.extractTooltipBackground` HEAD-cancel) + `ClientTextTooltipMixin` (`ClientTextTooltip.extractText` redirect). Adopt directly; do not re-port mc1211 `ScreenTooltipMixin`. For custom S1mp1e tooltip content, z-order is insertion order — enqueue glass before the `g.text` calls.

**Phase D — config GUI + HUD editor** (finding #3): `Screen.render(DrawContext)` → `extractRenderState(GuiGraphicsExtractor,int,int,float)`; `shouldPause→isPauseScreen`; `close→onClose`; `client→minecraft`; input methods → event records (`mouseClicked(MouseButtonEvent,boolean)`, `keyPressed(KeyEvent)` with `event.isEscape()`); `S1mp1eHudEditScreen` debug gate `getDebugHud().shouldShowDebugHud()` → `minecraft.getDebugOverlay().showDebugScreen()`; custom glass in-screen via the same `((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState().addGuiElement(...)` path.

**Adopt-directly (do NOT re-port) from the recovered seagull mixins:** `ButtonGlassMixin`, `ContainerScreensGlassMixin`+`InventoryGlassMixin`+`ContainerCloseGhostMixin`+`AbstractContainerScreenAccessor` (=mc1211 `HandledScreenGlassMixin`/`CreativeGlassMixin`), `RecipeBook/Button/TabGlassMixin`, `TooltipGlassMixin`+`ClientTextTooltipMixin`, `GuiRendererGrabMixin` (=world backdrop grab), item-name pill (=`InGameHudItemNameMixin`), `SliderGlassMixin`.

## 4. Risks / unknowns needing an in-game check

1. **`XpLevelMixin` target descriptor** — `ContextualBar.extractExperienceLevel(GuiGraphicsExtractor,Font,int)` is inferred from finding #4, not read from the jar. **Decompile `net.minecraft.client.gui.contextualbar.ExperienceBar` / `ContextualBar` from the loom cache before authoring** (`vineflower` per the context note); a wrong static-vs-instance or arg list silently no-ops the injection (`defaultRequire:1` will actually fail the build — good).
2. **`options.hudHidden` is gone** (gotcha #5, finding #3) — `PotionHudModule`/`InventoryHudModule` gate on it. No public boolean on 26.2 `Options` (only `keyToggleGui`). Need a new hidden-HUD source; verify against `Gui`/`Hud` fields in-game.
3. **`LightmapGammaMixin`** (`FullbrightModule`) — 26.2 gamma/lighting is likely a post/shader path; `LightmapTextureManager.update` target may not exist. In-game brightness check required; may need a different injection point.
4. **`HeldItemSwingMixin`** (`OldAnimations`) — deep `INVOKE` descriptor on the held-item renderer; verify the remapped method name/signature by javap before trusting the mixin.
5. **Backdrop-grab timing** — grab moved from `InGameHud.render` HEAD to `GuiRenderer.render()` HEAD (whole-GUI, later). Any HUD module that enqueues glass on a frame/path where `GlassPipeline.usable()` is false will bind a null `backdropView`; every module MUST gate on `usable()` and fall back to `GlassPainter.capsule` (the recovered mixins already do). Verify no first-frame/black-refraction flicker in-game.
6. **`extractHotbarAndDecorations` RETURN as the driver hook** — confirm it fires every frame even when the vanilla hotbar is hidden (spectator/no-hud); if not, the whole S1mp1e HUD pass dies. Fallback hook: `GuiRenderer.render()` HEAD alongside the grab, iterating modules there instead.
7. **Pose is 2D** (`Matrix3x2f`) — any mc1211 HUD editor / module depth-layering via z-translate must be dropped; visually verify layering still reads correctly (z is now pure insertion order).

Fair-play holds throughout: every ported HUD module reads only the local player's own public state (`getCameraPlayer()`/`getInventory()`/own pos/XP/hunger/effects/clicks) — render/UI only, no target-reading, packet-manip, or ESP introduced.

**Key files:** driver interface `mc262/.../dev/s1mp1e/client/HudRenderer.java` (new) + `HudDriverMixin`; shim `mc262/.../dev/s1mp1e/client/hud/HudGlass.java`; reference recipes `mc262/.../com/seagull/liquidglass/client/mixin/HudHotbarMixin.java` (proven glass-enqueue pattern) and `.../render/GlassPainter.java` (fallback fills); mixin config `mc262/src/main/resources/s1mp1e.mixins.json` (new) alongside `liquidglass.mixins.json`.