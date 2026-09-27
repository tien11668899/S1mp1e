<!-- Derived 2026-09-25 from two iOS 26 home-screen context-menu recordings that add the HOVER-DRAG selection behaviour
     (dark 1184x1444 / 677 f / 11.29 s; light 1178x1226 / 723 f / 12.05 s; both 60 fps, one long-press each).
     Sources: four frame-accurate analyses (motion_dark, look_dark, motion_light, look_light) + two independent
     re-measurements (reverify_dark_opus, reverify-light). Where they disagreed the re-measurement won.
     I (final author) independently re-extracted key frames from both clips and confirmed by eye: full-stadium pill,
     rigid-pill-straddling-the-gap (dark f136), lighter-grey in dark / darker-grey in light, text/icons unchanged,
     red "Remove App" preserved under the pill, NO check marks, and the collapse-to-icon on dismiss (light f674).
     Verify frames: .../scratchpad/vid2/final_verify/{dark_f120,dark_f136,dark_f330,dark_f596,light_f120,light_f135,light_f674}.png
     Companion doc: IOS26_CONTEXT_MENU_SPEC.md (open/close of the SAME menu). This doc covers ONLY the selection capsule
     and the release-to-select behaviour; it does not re-derive the open/close, which that doc already fixes. -->

# iOS 26 Context Menu — Selection Capsule & Release-to-Select — Final Implementable Spec for S1mp1e

Companion to `IOS26_CONTEXT_MENU_SPEC.md` (which fixes the **open/close** of this exact menu). This doc adds the one thing those clips did not show: the **hover-drag selection capsule** and what happens **between release-on-a-row and the menu being gone**.

Two new 60 fps recordings of the iOS 26 Settings-icon context menu (rows: Bluetooth, Wi-Fi, Cellular, Battery, Edit Home Screen, **Remove App** in red). In each, the finger presses, the menu opens, then **the finger stays down and drags across the rows**: a rounded **selection capsule** follows the finger from row to row, and on lift the menu collapses back into the icon.

**Reconciliation rule:** where an analyst and a re-measurement disagree, the **re-measurement wins** unless the data shows otherwise. Every such case is flagged **[RECONCILED]** with the reason. §13 is the full ledger.

**The one correction over the companion doc:** the companion's open/close is unchanged. The new material's only surprise vs. the casual "stretches like liquid" brief is that **the capsule does NOT physically stretch** — see §5.

---

## 0. One-line model
**A single rigid, achromatic, flat-fill rounded *stadium* pill — one row tall, inset from the menu's side edges, corner radius = half its height — appears (opacity only) under the pointer, springs vertically from row-centre to row-centre as the pointer moves (never resting between rows), and fades in place when the pointer leaves the rows.** It is the **only** selection indicator: no check mark, and the row's text/icons never change. In dark mode it **lightens** the menu body (~+26 L, additive); in light mode it **darkens** it (~−32 L). On click, the whole menu runs the companion doc's plain collapse back into the anchor — there is no commit flash.

---

## 1. Geometry

All two clips are different resolutions from each other and from the companion clips, so **port in relative terms**; px and pt (@ working scale s≈2.4 px/pt, i.e. 1 row pitch 107 px = 44 pt) are given for reference only. `menuW` = menu interior width; `rowPitch` = row-to-row spacing.

| Quantity | Dark px | Light px | **Relative (use this)** | pt @ s≈2.4 |
|---|---|---|---|---|
| Capsule height | 106.5 (std 0.16) | 106.0 (std 0.13) | **= 1.00 × rowPitch** | ≈ 44 pt |
| Capsule width | 689–690 | 675–677 | **≈ 0.90–0.92 × menuW** | — |
| Side inset (each) | 29.5 (L30/R29) | 37–39 | **≈ 0.04–0.05 × menuW** | ≈ 12–13 pt |
| Vertical inset | ~0 (0.5 px gap) | ~0–1 px | **≈ 0 (fills the row cell)** | ≈ 0 pt |
| Corner radius | 52.1 (circle fit) | 52.5 (circle fit) | **= 0.50 × capsuleHeight** | ≈ 22 pt |
| Horizontal position | fixed x[303,992] | fixed x[304,980] | **fixed; only Y tracks** | — |

- **Height = one full row pitch.** [RECONCILED] The light *look* analyst's 100 px / 0.93·pitch is the lone outlier; both re-measurements and the light *motion* analyst read 106 px = 1.0·pitch. The capsule fills the row cell top-to-bottom; the grey gap you see to a neighbouring row's text is **that row's own text padding**, not a capsule inset.
- **Corner radius = height/2 → a FULL STADIUM (pill), not a rounded-rect.** [RECONCILED] The two *motion* analysts' 41–48 px is an **underestimate** (they fit only the tight apex). Both independent re-measurements and both *look* analysts give **r ≈ 52–53 px = height/2** via least-squares circle fits on the rounded end. Confirmed by eye (dark_f120, light_f120: the ends are clean semicircles). This is a **different shape from the menu panel**, which is a large squircle (~n 2.5, r ≈ 0.12·width). Use `CornerRadius = capsuleHeight / 2`.
- **Horizontal is fixed.** x, width and height never change during any move — only the vertical position tracks the pointer (§4).

---

## 2. Shape & edge
- **Shape:** pure **stadium / pill** — two semicircular ends of radius = height/2 joined by straight top/bottom sides. Not a superellipse; not the panel's squircle.
- **Edge:** a **crisp ~1–4 px step** from body to fill, anti-aliasing only. **No rim highlight, no specular, no inner glow, no drop shadow, no dark contact line, no refraction/lens band.** The capsule is a flat lightening/darkening layer painted onto the menu's glass — it is **not its own piece of glass**. (The only rim/shadow in frame belongs to the menu *panel*.) Do **not** apply the S1mp1e Liquid-Glass edge-refraction curve `r(d)` to the capsule.

---

## 3. Fill, per theme

Both themes: **achromatic, R=G=B, zero hue cast** — including on the red "Remove App" row (the fill is neutral there too; see §6).

### DARK — additive lightening
- Raises the menu body by **ΔL ≈ +26** (peak +28 on bright upper rows, +24 on the darkest row). The lift **decreases** on darker rows — the signature of an **additive** layer, the opposite of an alpha-over-bright-tint overlay.
- **A normal "colour + alpha over the glass" blend cannot reproduce this** — fitting `cap = (1−a)·body + a·tint` gives **a ≈ −0.06** (impossible) because a bright overlay would lift the dark rows *more*. [RECONCILED: both re-measurements confirm the negative-alpha result independently.]
- **Implement as:** white in **Plus-Lighter / Screen / Additive** blend at **~10 %** (≈ +26/255 ≈ 0.102), clamped. If only normal blend is available and the launcher menu body is roughly uniform (L≈100–106), a **flat opaque grey ≈ rgb(130,130,130)** reads correctly — but do **not** use a fixed alpha over a bright tint, or dark rows over-brighten.

### LIGHT — neutral darkening
- Lowers the menu body by **ΔL ≈ −32** (range −30 to −35). Per-channel fit: **`cap = 0.96·base − 26`** (rmse ≈ 0.2–0.6), neutral.
- Same modelling caveat: a single normal-blend alpha-tint is non-physical on the glass's own vertical gradient (fit forces tint L ≈ −671). For a roughly-uniform launcher menu (L≈205 glass) use **`rgba(0,0,0,0.16)`** or **`grey(128) @ 0.40`** → lands ~L174, i.e. ~−34 vs the glass. Neutral, no warm/cool tint.

### Unified
The capsule is a **flat neutral luminance shift of the menu body** — *lighten* in dark, *darken* in light — of magnitude **|ΔL| ≈ 26–34**, no gradient across the fill, no self-rim.

---

## 4. Motion: appear → follow → disappear

Times = frame/60. Both clips agree the capsule moves **only vertically**; x, width, height are constant throughout (§5). All plateaus are on exact row centres.

### 4a. Appear (opacity fade-in, at full size, in place)
- **Trigger:** the pointer/finger **arrives on / starts moving over** the rows. (In dark, the menu sat open ~0.77 s with no capsule while the finger was stationary; the capsule faded in the instant the finger began to move, on whatever row it was over.)
- **Motion:** **pure opacity ramp** at the **final 106 px size** — no grow, no scale, no slide. Top and bottom edges are pinned at their final Y from the first visible frame.
- **Duration:** **0→90 % over ~150–170 ms** (dark 10→90 % in ~9.9 f / 165 ms; light 0→90 % in ~150 ms), fully saturated by **~330 ms**. Roughly linear in luminance.

### 4b. Follow (row-to-row spring)
The capsule **centre springs to the centre of the hovered row.** The target is **quantised to whole rows** — the capsule dwells only at exact row centres and **never stops between rows**.

- **Model:** SwiftUI `Spring(response, dampingFraction)`, mass = 1, released **from rest** onto a stepped target (step response, not an ease-out — it accelerates from a standstill).
- **Parameters** [RECONCILED — prefer re-measurements; both essentially critically damped, zero overshoot]:

  | Mode | response | dampingFraction ζ | bounce (=1−ζ) | overshoot | settle |
  |---|---|---|---|---|---|
  | **Dark** | 0.118 s (0.104–0.131) | 0.93 (0.885–0.995) | 0.07 | 0 % | ~150–167 ms |
  | **Light** | 0.105 s (0.104–0.105, tight) | ≈ 1.00 (critically damped) | 0.00 | 0 % | ~117 ms |
  | **Unified (use)** | **0.11 s** | **≈ 0.95–1.0** | **0–0.05** | **0 %** | ~120–160 ms |

- **Launcher `GlassMotion` form:** `Spring(0.11).Tune(0.11, 0.05)` (or per-mode: dark `Spring(0.118).Tune(0.118, 0.07)`, light `Spring(0.105).Tune(0.105, 0.0)`). Treat as **no visible bounce** — the approach is monotonic in every measured move (dark, light, up and down all identical).
- **Stiffness/damping (mass 1):** ω_n = 2π/0.11 ≈ 57 rad/s → k ≈ 3250, c ≈ 108–114 (at ζ 0.95–1.0). Peak vertical speed in the clips ≈ 30–33 px/frame.

### 4c. Disappear (opacity fade-out, in place)
- **Trigger:** the pointer/finger **leaves the rows** (drags off the menu). The **menu stays fully open** — this is not the dismiss.
- **Motion:** **pure opacity decay** at the same row, same 106 px size — **no shrink, no slide, no flash, no pulse, no brightening.**
- **Duration:** **90→10 % over ~175 ms** (dark 10.4 f / 174 ms; light 100→50 % ~83 ms, ~10 % ~200 ms). Near-symmetric with the fade-in.

---

## 5. Stretch model — **it does NOT stretch (rigid pill)**

**HEADLINE [RECONCILED, HIGH confidence, both clips, both re-measurements, and confirmed by eye].** The casual brief said the capsule "looks stretched across two rows like a liquid." **It does not physically stretch.** It is a **perfectly rigid 106 px pill that translates on one spring.**

Evidence:
- Height is **constant every frame** across the whole lifetime — dark min 105.9 / max 106.7 (std 0.16), light std 0.13 — including the **fastest move (33 px/frame)** and the one **2-row move**. Max stretch = **1.00×**.
- Fitting the **top edge** and the **bottom edge** as **separate** springs gives **byte-identical** parameters every move (e.g. dark BT→WiFi top 0.130/0.88, bottom 0.130/0.89; light top/bottom response differ by 0.0009 s). One shared spring ⇒ zero leading/trailing differential ⇒ zero elongation.
- **Why it "looks stretched":** during a move the rigid pill passes **through the inter-row gap**, so at the transition midpoint it sits centred between two rows and covers the **lower half of the upper row + upper half of the lower row** at once — reading as if it spans two rows while its geometry is unchanged. **Confirmed by eye at dark_f136** (pill straddling Bluetooth/Wi-Fi, same height as at rest).

**Implementation:** a **single rigid stadium pill that springs its centre between row centres reproduces both clips exactly.** The "liquid straddle" is free — it happens automatically whenever the pill is mid-slide. **Do NOT build a two-spring liquid stretch** to match the reference.

> **Optional (design add-on, NOT in the reference):** if the team wants a *more pronounced* liquid stretch than the natural straddle, give the **leading edge** a slightly **faster / lower-damped** spring than the **trailing edge** (a few-ms lead + lower ζ on the leading edge), so the pill elongates in the direction of travel and snaps back on settle. This is a deliberate departure — the iOS reference is rigid.

---

## 6. Row content under the capsule
- **Nothing on the row changes.** Text keeps its colour, weight, size and position (dark: white rgb≈250; light: near-black rgb≈20). Icons unchanged. Verified: on/off-capsule text differs by ≤1–2 L (noise).
- **Destructive "Remove App" stays red** under the capsule (dark ≈ rgb(250,120,122); light ≈ rgb(212,41,46)). The capsule fill over it is the **same neutral grey** (no red tint). Confirmed by eye at dark_f330. **Do not** tint the capsule red on the destructive row and **do not** recolour the text.
- **There are NO check marks anywhere in this menu.** The **capsule is the sole selection indicator** — exactly the model the launcher wants. It is a pure background highlight behind unchanged content.

---

## 7. Multi-row jumps
- The one ~2-row move in the clips (dark, Remove→Battery, upward) behaved as **two chained single-row springs**: it briefly decelerated/touched the intermediate Edit row (f346–348, cy≈1003 vs Edit 999) then continued — **not one long stretch**, and height stayed 106 px throughout. This is because the *finger physically passed through* the intermediate row.
- **For the launcher:** a direct target jump (keyboard Home/End, or a fast pointer flick that skips rows) should be modelled as **one spring to the destination row centre** (same params), height constant. The chained-touch behaviour is only what a finger sliding *through* rows produces; you do not need to reproduce it for a discrete jump. No fast multi-row *flick* exists in the clips, so large-jump lag/overshoot is untested — the from-rest spring extrapolates cleanly (it is monotonic and critically damped).

---

## 8. Rest of the menu's reaction
**None.** The panel does not scale, tilt, stretch toward the finger, or parallax; the scrim, wallpaper, rows, text, icons and the pressed icon all hold still. Only the capsule moves during the hover-drag. (Proven by pixel-wise-min body references that isolate only the moving pill with no panel-edge residuals; confirmed by eye — the panel is frame-identical whether the pill is on the top or bottom row.)

---

## 9. Release-to-select — the full sequence

**CRITICAL FINDING:** **neither clip contains a select-on-release *commit*.** In both, the sequence was: finger **leaves the rows** → capsule **fades out in place** (~175 ms) → menu **sits fully open and static ~1.3–1.5 s** → a **plain dismiss**. There is **no confirm flash, no pulse, and no highlighted row at dismiss** (all rows dim uniformly). The **~1.3–1.5 s open-with-no-capsule gap is a recording artifact** (the clip was held), **not intended UX**.

The plain dismiss / collapse (measured; matches `IOS26_CONTEXT_MENU_SPEC.md` §5, confirmed by eye at dark_f596 → light_f674):
1. **Content cut:** text + icons vanish in **≤1 frame** (dark f593→594; light f670), **before** the shape moves.
2. **Shape collapse:** panel shrinks + fades **toward the icon anchor** over **~80–100 ms** (dark ~4–5 f/~80 ms; light ~100 ms). Corners round **out** as it shrinks. **Ease-IN (accelerating), monotonic, NO overshoot.** It collapses into a **small empty rounded square at the icon** (confirmed light_f674 — a tiny empty pill on the Settings icon, label reappearing).
3. **Scrim clears:** **~150–233 ms**, **outlasting** the shape collapse by ~85–150 ms. The "Settings" label reappears as the scrim clears.

**Because there is no reference commit animation, the click→collapse mapping is a design decision.** Recommendation (below in §10.7): on click, keep the pill lit through the click, then run the plain collapse with **no dead time** and **no flash**.

---

## 10. Desktop-launcher translation (Avalonia, S1mp1e)

Mapping the finger to the mouse, with **no check marks**. The launcher already has: per-row `Border.menurow` with a `:pointerover` background swap to `MenuHover` (140 ms `BrushTransition`, `MainWindow.axaml:334–343`); a per-row **✓ check** in `BuildMenuRow` (`MainWindow.axaml.cs:1439–1450`, `Opacity = selected ? 1 : 0`); a **single sliding `HoverPill`** on the sidebar (`MainWindow.axaml:464`, `MainWindow.axaml.cs:129–134` — a `Border` on a `Canvas` that slides `Canvas.Top` with a 200 ms `CubicEaseOut` + 140 ms opacity fade); an **already-added `MenuScrim`** (`MainWindow.axaml:1055`); and the menu itself (`MenuItems` `StackPanel` in `MenuClip`→`MenuRoot`, `MainWindow.axaml:1063–1090`). Context menus vs. value-picker dropdowns are distinguished by `_anchorFades = anchor is GlassSelect` (`MainWindow.axaml.cs:697`). **This is one new element + two deletions, not a rewrite.**

**10.1 — Pointer = finger.** Wire `PointerEntered` / `PointerMoved` on each menu row (or on `MenuItems` with hit-testing to the row under the pointer) to set the capsule's **target row**. The capsule is a **single `Border`** (not per-row) living **behind the rows** inside `MenuClip`, on a `Canvas` (or with a `TranslateTransform`), exactly like the sidebar `HoverPill`. Its `CornerRadius = capsuleHeight/2`, height = row height, width = menu-interior width − 2·inset (inset ≈ 12–13 DIP ≈ `CtxRowPadH`), horizontal position fixed.

**10.2 — Follow spring.** Do **not** reuse the sidebar's 200 ms `CubicEaseOut`; drive the capsule's Y with the **measured spring** (`Spring(0.11).Tune(0.11, 0.05)`, §4b) on each `_menuAnchor`/target change. Because the pill is rigid, the "liquid straddle" appears for free mid-slide (§5). Keep width/height constant.

**10.3 — Fill.** Dark: additive white ~10 % (or flat `rgb(130,130,130)` if additive blend is unavailable). Light: `rgba(0,0,0,0.16)`. No rim/shadow/refraction (§2–3). Neutral on the destructive row.

**10.4 — Appear / disappear.** Fade the capsule **in** (~150 ms, at full size, in place) when the pointer **first enters any row**; keep it alive and spring it between rows as the pointer moves; fade it **out** (~150–200 ms, in place, **no flash**) when the pointer **leaves the menu rows**. Do not re-fade during a move.

**10.5 — Remove the check marks.** Delete the ✓ path / gutter from context-menu rows in `BuildMenuRow` (`:1439–1452`) — the capsule is now the only selection cue. Keep the leading gutter width (`CtxCheckGutter`) if you use it for a **row icon** (Bluetooth/Wi-Fi glyphs); otherwise reclaim it. **Also remove the per-row `Border.menurow:pointerover` background swap** (`MainWindow.axaml:341–343`) for menus that use the following capsule, so you don't get a double highlight.

**10.6 — Keyboard (launcher addition; not in the reference).** **Up/Down** move the capsule one row (same spring, target = next/prev row centre); if the capsule is not visible, the first key press **fades it in** on the current-value row (value picker) or the first row (action menu). **Home/End** jump to first/last (one spring to the destination, §7). **Enter/Space** = click = **commit** (§10.7). **Esc** = leave = fade the capsule out + run the plain collapse (cancel). **No wrap** at the ends (clamp) — matching the finger drag, which clamped at Bluetooth and Remove App.

**10.7 — Click = release = commit → collapse.** Since the reference shows no commit flash, on click of the hovered row: **keep the capsule lit through the click (1 frame), then immediately run the existing plain collapse** — content cut (≤1 frame) → **~80–100 ms** ease-in shape collapse **to the anchor** → **scrim clears ~185–250 ms**, outlasting the shape (§9; the launcher's `CloseGlassMenu` / `AnimateMenu` close path already does content-cut-before-shape + collapse-to-origin). **Couple click→collapse with no dead time** (do not reproduce the 1.5 s dwell) and **no selection flash** (a flash would be an addition, not in the reference). The chosen value is then reflected in the now-closed anchor control (`_anchorFades` hands the value text back to the `GlassSelect`), **not** by a check in the vanished menu.

**10.8 — Current-value indication for a value-picker (NO check marks).** [Design decision — see the open question in §14.] iOS's own menus normally mark the selected item with a **leading check mark**, which is exactly what the user forbids. The closest **position-based** analogue, consistent with everything measured here: **when a value-picker menu opens, pre-place the capsule — already at full opacity, at rest — on the row matching the current value.** The capsule *is* the selection state. When the pointer enters, the capsule **springs from the current-value row to the hovered row**; if the pointer leaves without clicking, it springs back to (or fades on) the current-value row; keyboard focus on open sits on that row. This makes the capsule do the check mark's job by **position**, with no tick and no text restyling (the reference never restyles row text). For a pure **action menu** (like this iOS Settings menu, which has no "current value"), open with **no capsule** and fade it in on first hover — exactly as the clips do.

**10.9 — Scope.** Apply the following capsule to the context-menu path first (`ShowAccountSwitcher`, and any menu opened via the non-`_anchorFades` path). For `GlassSelect` value pickers, apply it too (that is where "current value" matters, §10.8), replacing their per-row hover + ✓. The menu **panel** open/close, scrim, rim, squircle and material stay as the companion doc specifies — this doc changes only the **selection indicator inside** the panel.

---

## 11. Quick-reference numbers
- Capsule: **stadium**, height **= 1.0·rowPitch**, width **≈ 0.90·menuW** (side inset **≈ 0.04–0.05·menuW**, ~12–13 pt), corner **= 0.5·height**, horizontal fixed.
- Fill: dark **+26 L additive** (~10 % white / flat `rgb(130,130,130)`); light **−32 L** (`rgba(0,0,0,0.16)`). Neutral, flat, no rim/shadow/lens.
- Follow spring: **response 0.11 s, ζ ≈ 0.95–1.0, overshoot 0 %**, from rest, target = row centre, **rigid** (no stretch).
- Fade-in ~**150–170 ms**; fade-out ~**150–200 ms**; both opacity-only, at full size, in place; fade-out has **no flash**.
- Release: **no commit flash**; on click → plain collapse (content cut ≤1 f → shape ~80–100 ms ease-in to anchor → scrim ~185–250 ms).
- **No check marks. Row content never changes. Red row stays red, capsule stays grey. Menu panel does not react.**

---

## 12. px→DIP scale
Both clips are different resolutions (1184×1444 dark, 1178×1226 light) from each other and from the companion clips, so absolute pt conversions carry real uncertainty. **Row pitch is 107 px in all clips.** **Spring timings are unit-free and unaffected.** **Port geometry in the relative terms above** (fractions of `rowPitch` / `menuW`); use s≈2.4 only as a sanity check.

---

## 13. Reconciliation ledger (who I used where)
- **Corner radius:** used **re-measurements + both *look* analysts** (r ≈ 52–53 px = height/2, full stadium) over the two *motion* analysts' 41–48 px (apex-only underestimate). Two independent circle fits converge; confirmed by eye.
- **Capsule height:** used **re-measurements + *motion* analysts** (106 px = 1.0·pitch) over the light *look* analyst's 100 px / 0.93·pitch (lone outlier).
- **Stretch:** used the **unanimous null result** (rigid pill, top/bottom edges share one spring; height std ≤0.16 px) over the brief's "stretches like liquid." The straddle is the visual source of the impression; confirmed by eye at dark_f136.
- **Follow spring response:** used **re-measurements** (dark 0.118, light 0.105) over the light analyst's 0.111 (re-measure's 0.105 reproduces the analyst's own progress table better). Both agree on ζ ≈ 0.93–1.0, overshoot 0.
- **Fill blend model:** used **both re-measurements** (additive in dark, non-physical normal-alpha in both) — analysts and re-measures agree.
- **Everything else** (fixed x/width, side inset ~0.04–0.05·menuW, neutral achromatic fill, no rim/shadow/lens, text/icon invariance, red preserved, no check marks, appear/disappear timings, menu-no-reaction, release-decoupled + plain-collapse) **agreed across all four analyses and both re-measurements within ~10 %** and is carried through directly; I re-confirmed the load-bearing qualitative items by eye on my own extracted frames.

---

## 14. Open questions the recordings cannot answer
- **Select-on-release commit is not in either clip.** Both decouple deselect (fade in place) from dismiss (a plain collapse ~1.3–1.5 s later), with **no flash and no highlighted row at dismiss**. So "what happens between release-on-a-row and menu-gone" is a **reasoned recommendation** (§9–§10.7: keep pill lit → plain collapse, no dead time, no flash), not a measured commit animation. If a subtle confirm cue is wanted, it must be added deliberately.
- **Value-picker current-value indication is not shown.** This iOS menu is an *action* menu with no "current value," and iOS's normal selected-item cue (a check mark) is exactly what the user forbids. §10.8's "pre-place the capsule on the current value" is the closest position-based analogue, flagged as a design decision, not a measurement.
- **The "liquid stretch" the brief expected is not real** — the pill is rigid; the straddle only *looks* stretched. Whether a *faster* flick or a *multi-row* jump than exists in these clips would induce genuine lag/elongation is **untestable** from this footage (peak 33 px/frame, mostly single-row moves). A true stretch (§5, optional) would be an intentional S1mp1e departure.
- **Absolute px→DIP scale is ambiguous** (different device/crop per clip). Relative geometry and unit-free spring timing are solid; confirm the launcher's own DIP mapping against its actual menu width.
- **The ~1.3–1.5 s open-with-no-capsule dwell** in both clips cannot be attributed (finger held off the rows vs. a paused recording) — treat it as an artifact, not UX.
- **Fill blend fidelity in a gradient body:** the additive (dark) / contrast-plus-offset (light) models are inferred from the lift-vs-backdrop trend (a single normal-alpha tint is provably non-physical), not from blend metadata. For a near-uniform launcher menu the flat approximations in §3 suffice; on a strongly graded body, prefer the true additive/contrast model.

## 15. Key evidence frames (my independent re-extraction)
- `…/scratchpad/vid2/final_verify/dark_f120.png` — capsule at rest on Bluetooth: full stadium, lighter grey, one row tall, inset, white text on top, no check mark.
- `…/vid2/final_verify/dark_f136.png` — mid-move Bluetooth→Wi-Fi: the **rigid pill straddling the inter-row gap** (looks stretched, is not).
- `…/vid2/final_verify/dark_f330.png` — Remove App row: neutral grey capsule, **red text/icon preserved**.
- `…/vid2/final_verify/dark_f596.png`, `…/vid2/final_verify/light_f674.png` — dismiss: content cut, shape collapsing into the icon anchor (tiny empty pill on the Settings icon), scrim clearing.
- `…/vid2/final_verify/light_f120.png`, `…/vid2/final_verify/light_f135.png` — light-mode darker-grey capsule, black text preserved.
- Upstream per-frame data referenced in the analyses: `…/vid2/darkmode/capsule_full.csv`, `…/vid2/light-motion/capsule_track_final.csv`, `…/vid2/reverify_dark_opus/capsule_track.csv`, `…/vid2/reverify-light/cap.csv`.
