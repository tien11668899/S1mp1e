# S1mp1e Glass — 1.12.2 Forge → 1.16.5 Fabric animation-suite mapping

Maps every 1.12.2 Forge animation handler to its MC 1.16.5 Fabric mixin injection
point. **Only the plumbing changes** (Forge `@SubscribeEvent` → Fabric `@Inject`
mixin). Geometry, spring parameters, fade timings and easing are byte-for-byte the
1.12.2 handlers, which are byte-for-byte LiquidGlass26. Every mixin **calls the
already-ported render core** (`GlassRenderer`, `GlassProgram`, `SceneCapture`,
`ScreenFade`, `MenuBackdrop`, `PanelGhost`) and `anim` (`Fade`, `Spring`) — none of
those are rewritten.

- Loader: **Fabric**, `compatibilityLevel JAVA_8`. Injected private mixin methods
  use the `s1mp1e$` prefix.
- 1.16.5 GUI render methods take a **`MatrixStack` first arg** (except
  `renderHotbar`, where it is second — see below).
- Every target below was verified against the yarn 1.16.5 mappings tiny at
  `~/.gradle/caches/fabric-loom/1.16.5/net.fabricmc.yarn.1_16_5.1.16.5+build.10-v2/mappings.jar`
  (`mappings/mappings.tiny`, v2 `official intermediary named`). Descriptors are
  given in **named** form (what a dev-env mixin uses); the intermediary id is the
  runtime identity Loom remaps to.
- **The new mixins are NOT registered in `s1mp1e.mixins.json`.** That file is left
  untouched; the human enables them one at a time to verify. Each is a standalone
  `.java` under `dev/s1mp1e/glass/mixin/`.

## Naming corrections found during verification

| Task wording | Actual yarn 1.16.5 name | Intermediary | Note |
|---|---|---|---|
| `MinecraftClient.setScreen` | **`openScreen`** | `method_1507` | `setScreen` is the later-mappings name; 1.16.5 yarn is `openScreen`. |
| `Slot.isEnabled()` (1.12.2) | **`doDrawHoveringEffect()`** | `method_7682` | 1.16.5 has no `isEnabled`; this is the hover-eligibility gate. |
| container drag fields | **`cursorDragging` / `cursorDragSlots`** | `field_2794` / `field_2793` | 1.12.2 `dragSplitting` / `dragSplittingSlots`, now on `HandledScreen` (shadowable, no reflection). |
| `Slot.xPos/yPos` | **`Slot.x` / `Slot.y`** | `field_7873` / `field_7872` | |

## Type shorthand (official → named), used in the descriptors below

`dfm`=`net.minecraft.client.util.math.MatrixStack` (`class_4587`) ·
`nr`=`net.minecraft.text.Text` (`class_2561`) ·
`afa`=`net.minecraft.text.OrderedText` (`class_5481`) ·
`bmb`=`net.minecraft.item.ItemStack` (`class_1799`) ·
`bjr`=`net.minecraft.screen.slot.Slot` (`class_1735`) ·
`dot`=`net.minecraft.client.gui.screen.Screen` (`class_437`).

---

## 1 — Screen open/close dissolve

**Forge:** `hook/GlassScreenFadeHandler` — `onGuiOpen` (trigger), `onScreenPost`
(draw+capture on a screen), `onOverlayPost` (draw+capture in-world). Calls
`ScreenFade.trigger()`, `ScreenFade.draw()`, `ScreenFade.captureFrame()`.

| Sub-hook | Fabric mixin | Target class | Method (yarn / intermediary) | Descriptor (named) | `@At` |
|---|---|---|---|---|---|
| trigger on screen CHANGE | `MinecraftClientFadeMixin` | `MinecraftClient` (`class_310`) | `openScreen` / `method_1507` | `(Lnet/minecraft/client/gui/screen/Screen;)V` | `HEAD` |
| draw+capture (screen up) | `ScreenFadeMixin` | `Screen` (`class_437`) | `render` / `method_25394` | `(Lnet/minecraft/client/util/math/MatrixStack;IIF)V` | `TAIL` |
| draw+capture (no screen) | `InGameHudFadeMixin` | `InGameHud` (`class_329`) | `render` / `method_1753` | `(Lnet/minecraft/client/util/math/MatrixStack;F)V` | `TAIL` |

Notes:
- `render` (`method_25394`) is declared on the `Drawable` interface (`class_4068`);
  `Screen` carries the override, so the `TAIL` lands at the end of whichever screen
  is on top.
- At `openScreen` HEAD the `currentScreen` shadow field still holds the OUTGOING
  screen — so `this.currentScreen == screen` is the exact equivalent of the Forge
  guard `mc.currentScreen == e.getGui()` (skip the resize re-set, no white flash).
- The two draw halves are mutually exclusive via `currentScreen == null`, exactly
  like the two Forge `Post` handlers.

## 2 — Container glass + open/close ghost

**Forge:** `hook/GlassContainerHandler` — `onBackgroundDrawn`
(panel+lattice+drag+hover), `onGuiOpen` (`PanelGhost.trigger`), `onOverlayPost`
(`PanelGhost.drawGhosts`). Uses `GlassRenderer.panel`, `beginBatch(LINE)`/
`batchQuad`/`endBatch`, `GlassRenderer.glass`, `SceneCapture.grab`, `Fade`,
`Spring`, `PanelGhost`.

| Element | Fabric mixin | Target class | Method (yarn / intermediary) | Descriptor (named) | `@At` |
|---|---|---|---|---|---|
| panel + lattice + drag + hover | `HandledScreenGlassMixin` | `HandledScreen` (`class_465`) | `render` / `method_25394` | `(Lnet/minecraft/client/util/math/MatrixStack;IIF)V` | `INVOKE` `drawBackground` **shift BEFORE** |
| close-ghost trigger | `MinecraftClientContainerGhostMixin` | `MinecraftClient` (`class_310`) | `openScreen` / `method_1507` | `(Lnet/minecraft/client/gui/screen/Screen;)V` | `HEAD` |
| close-ghost draw | `InGameHudPanelGhostMixin` | `InGameHud` (`class_329`) | `render` / `method_1753` | `(Lnet/minecraft/client/util/math/MatrixStack;F)V` | `TAIL` |

The panel/lattice/drag/hover injection is at the INVOKE of the container texture
call inside `render`:

```
@At(value = "INVOKE",
    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/util/math/MatrixStack;FII)V",
    shift  = At.Shift.BEFORE)
```

Why not `drawBackground` / `drawForeground` / `drawSlot` directly:

- `drawBackground` (`method_2389`, `(Ldfm;FII)V`) is **abstract** on
  `HandledScreen` — each subclass implements it, so you cannot `@Inject` its body.
  The Forge `BackgroundDrawnEvent` fired *after the world-dim, before the container
  texture*; the 1.16.5 equivalent is **`render`, just before the `drawBackground`
  invoke** (`renderBackground` — the dim — has already run; no `translate(x,y)` is
  active yet, so the absolute `this.x/this.y` panel origin is correct). The vanilla
  texture then paints over the glass, exactly as in the accepted 1.12.2 behavior.
- `drawForeground` (`method_2388`, `(Ldfm;II)V`) is the item-label pass — the
  no-op `DrawScreenEvent.Post` marker in the Forge handler; nothing to draw there.
- `drawSlot` (`method_2385`, `(Ldfm;Lbjr;)V`) is the per-slot surface; the Forge
  port **batches** the whole lattice in one pass rather than per slot, so we keep
  the batch and do not inject `drawSlot`.
- The slot-hover highlight is **computed by us** (own spring rig + mouse test), not
  vanilla's `fillGradient` highlight, so no vanilla-highlight method is targeted.

Shadow mapping on `HandledScreen` (all `protected`, no reflection needed):

| Forge (reflected) | yarn shadow | Intermediary |
|---|---|---|
| `guiLeft` | `x` | `field_2776` |
| `guiTop` | `y` | `field_2800` |
| `xSize` | `backgroundWidth` | `field_2792` |
| `ySize` | `backgroundHeight` | `field_2779` |
| `inventorySlots.inventorySlots` | `handler.slots` | `field_2797` . `field_7761` |
| `dragSplitting` | `cursorDragging` | `field_2794` |
| `dragSplittingSlots` | `cursorDragSlots` | `field_2793` |

Per-screen state (open fade, hover springs, drag map) lives as **mixin instance
fields** on the `HandledScreen` — each opened container gets a fresh rig, which
reproduces the Forge singleton's `curScreen != screen` reset for free. The fresh
instance's first draw calls `PanelGhost.cancel()` (the reopen-before-ghost-finishes
branch). `MinecraftClientContainerGhostMixin` fires `PanelGhost.trigger()` whenever
`currentScreen instanceof HandledScreen` at `openScreen` (leaving a container to
anything), matching `if (!(old instanceof GuiContainer)) return; PanelGhost.trigger()`.
`InGameHudPanelGhostMixin.drawGhosts` gates internally on a captured backdrop, so it
depends on `InGameHudMixin`'s render-HEAD grab exactly as the Forge port depended on
`GlassHudHandler`'s `Pre(ALL)` grab.

## 3 — Held-item name popup crossfade

**Forge:** `hook/GlassItemNameHandler` — suppressed vanilla `renderSelectedItem`
by stealing/zeroing `remainingHighlightTicks` at `Pre(ALL)`, restored + drew the
capsule at `Post(ALL)`. Uses `GlassRenderer.glass`, `Spring` (omega 30), two 150 ms
`Fade`s, `SceneCapture`.

| Fabric mixin | Target class | Method (yarn / intermediary) | Descriptor (named) | `@At` |
|---|---|---|---|---|
| `InGameHudItemNameMixin` | `InGameHud` (`class_329`) | `renderHeldItemTooltip` / `method_1749` | `(Lnet/minecraft/client/util/math/MatrixStack;)V` | `HEAD`, `cancellable = true` |

1.16.5 exposes the popup as its own method, so we **cancel it and draw our capsule**
— no suppression trick. Shadow `heldItemTooltipFade` (`field_2040`, int) and
`currentStack` (`field_2031`, `ItemStack`); when the shader is down, the setting is
off, or the player is a spectator, we `return` without cancelling so vanilla draws.
Field mapping: `remainingHighlightTicks → heldItemTooltipFade`,
`highlightingItemStack → currentStack`; the creative/adventure `+14` shift uses
`ClientPlayerInteractionManager.hasStatusBars()` (`method_2908`), vanilla's own
branch. Spectator gate via `getCurrentGameMode()` (`method_2920`) `== GameMode.SPECTATOR`.

## 4 — Glass tooltip morph + crossfade

**Forge:** `hook/GlassTooltipHandler` drove `GlassTooltip.ghostPass()` once per
frame; the real draw replaced `GuiUtils.drawHoveringText`. Ported helper
`ui/GlassTooltip` (position/size springs omega 27, 150 ms panel/text fades, alpha-8
floor) calls `GlassRenderer.glass` + `SceneCapture`.

| Sub-hook | Fabric mixin | Target class | Method (yarn / intermediary) | Descriptor (named) | `@At` |
|---|---|---|---|---|---|
| draw | `ScreenTooltipMixin` | `Screen` (`class_437`) | `renderOrderedTooltip` / `method_25417` | `(Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/List;II)V` | `HEAD`, `cancellable = true` |
| per-frame ghost | `InGameHudTooltipGhostMixin` | `InGameHud` (`class_329`) | `render` / `method_1753` | `(Lnet/minecraft/client/util/math/MatrixStack;F)V` | `TAIL` |

`renderOrderedTooltip` is the single choke point every 1.16.5 tooltip funnels
through (`renderTooltip(Text)` = `method_25424`, `renderTooltip(List<Text>)` =
`method_30901`, and `drawMouseoverTooltip` = `method_2380` all delegate to it), so
one HEAD-cancel catches them all. A ported `ui/GlassTooltip` (operating on
`List<? extends OrderedText>` instead of `List<String>`, using
`TextRenderer.getWidth(OrderedText)` = `method_30880` and
`drawWithShadow(MatrixStack, OrderedText, float, float, int)` = `method_27517`) was
written because the tooltip orchestration must live in a real class both mixins can
call (a mixin class is not a runtime type). `ghostPass` runs from `InGameHud.render`
TAIL — once per frame, before the screen draws its tooltips, the same ordering the
Forge overlay `Post` had, which is why the active-flag ping-pong is correct. If the
pipeline is down, `GlassTooltip.draw` returns `false` and the mixin does not cancel,
so vanilla's flat tooltip shows.

## 5 — Menu-backdrop blur

**Forge:** `render/MenuBackdrop` — `draw()` replaced the tiled dirt with the blurred
title panorama; `capture()` was driven from an ASM hook right after
`GuiMainMenu.renderSkybox`.

| Sub-hook | Fabric mixin | Target class | Method (yarn / intermediary) | Descriptor (named) | `@At` |
|---|---|---|---|---|---|
| draw (replace dirt) | `ScreenMenuBackdropMixin` | `Screen` (`class_437`) | `renderBackgroundTexture` / `method_25434` | `(I)V` | `HEAD`, `cancellable = true` |
| panorama capture | `TitleScreenBackdropCaptureMixin` | `TitleScreen` (`class_442`) | `render` / `method_25394` | `(Lnet/minecraft/client/util/math/MatrixStack;IIF)V` | `INVOKE` `RotatingCubeMapRenderer.render(FF)V` **shift AFTER** |

`renderBackgroundTexture(int)` draws the tiled `OPTIONS_BACKGROUND_TEXTURE` (dirt)
for world-less screens; `renderBackground(MatrixStack)` (`method_25420`) and
`renderBackground(MatrixStack,int)` (`method_25433`) delegate here only when there
is no world, so this one HEAD-cancel covers the main menu, options, server list,
etc., without touching the in-world dim. `MenuBackdrop.draw()` returns `false`
(→ vanilla dirt) until a panorama frame is captured and the blur program is usable.
The capture point is the yarn equivalent of "just after the skybox": inject at
`TitleScreen.render` at the INVOKE of the panorama renderer
(`RotatingCubeMapRenderer.render(float,float)` = `method_3317`, `(FF)V`), shift
AFTER — the framebuffer holds only the panorama there, before the logo/splash/
buttons. (`TitleScreen.backgroundRenderer` = `field_2585`.)

## 6 — Button fade-in/out

**Forge:** `hook/GlassButtonHandler` drove `ui/GlassButtonPainter`, which snapped
the hover lift `0 → 0.81` instantly. The 1.16.5 line already replaces the widget
sprite in `ButtonGlassMixin`; this **extends it with `anim/Fade`** so the lift eases.

| Fabric mixin | Target class | Method (yarn / intermediary) | Descriptor (named) | `@At` |
|---|---|---|---|---|
| `ButtonGlassMixin` (extended) | `ClickableWidget` (`class_339`) | `renderButton` / `method_25359` | `(Lnet/minecraft/client/util/math/MatrixStack;IIF)V` | `HEAD`, `cancellable = true` |

A per-widget `Fade` (keyed in a `WeakHashMap<ClickableWidget, Fade>`, so dead
widgets self-evict and coexisting buttons never cross-wire) eases the lift over
**100 ms** — the same duration the container hover pill uses (`HOVER_FADE_S 0.10`).
The endpoints are unchanged (0 resting, 0.81 hovered), so a settled button matches
the Forge port exactly; only the transition is eased. A fresh entry starts settled
(`over ? 1 : 0`) to avoid a first-frame flash. This is the single documented place
the Fabric line adds motion the Forge port lacked — it is the system the task names
"Button fade-in/out."

---

## File inventory (all under `mc1165/src/main/java/dev/s1mp1e/glass/`)

New mixins (`mixin/`), none registered in `s1mp1e.mixins.json`:

1. `MinecraftClientFadeMixin.java` — system 1 trigger
2. `ScreenFadeMixin.java` — system 1 draw (screen)
3. `InGameHudFadeMixin.java` — system 1 draw (in-world)
4. `HandledScreenGlassMixin.java` — system 2 panel/lattice/drag/hover
5. `MinecraftClientContainerGhostMixin.java` — system 2 ghost trigger
6. `InGameHudPanelGhostMixin.java` — system 2 ghost draw
7. `InGameHudItemNameMixin.java` — system 3
8. `ScreenTooltipMixin.java` — system 4 draw
9. `InGameHudTooltipGhostMixin.java` — system 4 ghost driver
10. `ScreenMenuBackdropMixin.java` — system 5 draw
11. `TitleScreenBackdropCaptureMixin.java` — system 5 capture

Edited: `mixin/ButtonGlassMixin.java` — system 6 (added per-widget `Fade`).

New helper: `ui/GlassTooltip.java` — 1.16.5 port of the tooltip morph orchestration
(real class; called by mixins 8 & 9). Calls only render core + `anim`.

Reused unchanged: `render/{GlassRenderer,GlassProgram,SceneCapture,ScreenFade,
MenuBackdrop,PanelGhost}`, `anim/{Fade,Spring}`, and the existing working
`mixin/{InGameHudMixin,ButtonGlassMixin,TitleScreenMixin}` (the last two extended /
coexisting).

## Enable order suggestion (one at a time, each independently verifiable)

`ButtonGlassMixin` (already on) → `MinecraftClientFadeMixin` + `ScreenFadeMixin` +
`InGameHudFadeMixin` (system 1) → `ScreenTooltipMixin` + `InGameHudTooltipGhostMixin`
(system 4) → `InGameHudItemNameMixin` (system 3) → `HandledScreenGlassMixin` +
`MinecraftClientContainerGhostMixin` + `InGameHudPanelGhostMixin` (system 2) →
`TitleScreenBackdropCaptureMixin` + `ScreenMenuBackdropMixin` (system 5). Each
mixin gates on `GlassProgram.usable()` and falls back to vanilla, so a half-enabled
set never hard-breaks a screen.
