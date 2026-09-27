<!-- Derived 2026-09-24 from two iOS 26 home-screen context-menu recordings (dark 1024x1230, light 928x1230, 60 fps, 3 open/close cycles each):
     four frame-accurate analyses + two independent re-measurements (wf_1c2e0e9d-b4a). Where they disagreed the re-measurement won. -->

# iOS 26 Home-Screen Context Menu — "Emerge from the Anchor" — Final Implementable Spec for S1mp1e

Reconciled from four analyses (motion_dark, material_dark, motion_light, material_light) and two independent re-measurements (dark, light) of two 60 fps iPhone screen recordings of the iOS 26 Settings-icon long-press menu. All three open/close cycles in each clip are frame-identical (geometry spread ≤1.5 px, timing ≤1 frame), so every number is a stable, deterministic value, not an average with wide spread.

**Rule applied:** where an analyst and a re-measurement disagree, the re-measurement wins unless the data shows otherwise. Every such case is flagged inline with **[RECONCILED]** and the reason.

I independently confirmed the load-bearing qualitative findings by eye on the strips: separate blob emerges from the icon (not icon morphing), content-cut-before-shape on close, continuous squircle corner, top-weighted rim, icon stays lit + label hidden. Consolidated evidence strip: `…/scratchpad/vid/final_spec/OVERVIEW_reconciled.png`.

---

## 0. The one-line model
A **separate rounded-rect Liquid Glass panel is born at the icon's bottom edge and grows down-and-right into the full menu on a single spring; a global backdrop scrim dims the whole screen behind it.** The icon never morphs and is never covered. Close is a faster, spring-less, accelerating collapse back into the icon, with the row content cut a frame *before* the shape moves and the scrim clearing *after* it.

---

## 1. Timeline (ms)

Onset t=0 = the spring's `t0` (the panel is at ~0 scale and invisible here). "First-visible" = the first frame the low-opacity blob is clearly readable.

### OPEN
| t (ms) | Event |
|---|---|
| 0 | Spring t0. Panel scale ≈ 0 under the icon. Backdrop scrim begins to dim (slightly *leads* the shape). |
| +30…50 | Blob first clearly visible: ~210×140 px ≈ 0.29 w × 0.19 h of final, rounded (corner proportionally *rounder* than final, near-capsule), top pinned to the icon's bottom edge. |
| +30…220 | Uniform scale spring grows both axes together (see §3). |
| +~80 | Rows cross-fade in, 10→90 % over ~65–100 ms **[RECONCILED: faster than the 100 ms originally claimed — dark re-measure 90 % at f47.5 (~67 ms), light re-measure 50 % ~f52.5 / full ~f55]**. Slight top-first positional stagger; the ✓ lands **with** its row. |
| +~190 | Scrim reaches its floor (dark ~48–50 %, light ~−24 %). Ease-out, no overshoot. |
| +~200 | Spring overshoot peak, +2.2–2.4 % on both axes. |
| +~400…430 | Settled (≈ 0.26 s of decay after the peak). Corner has sharpened from near-capsule to the settled squircle. |

### CLOSE (not a mirror; ~2–3× faster)
| t (ms) | Event |
|---|---|
| 0 | Row content **cut in ≤1 frame (≤17 ms)** — text contrast 100 %→~4–7 %, *before* the shape moves. |
| 0…85…100 | Shape collapses monotonically toward the icon / top-left. **Ease-IN (accelerating), NO overshoot.** Corners round **out** (toward a blob) as it shrinks. |
| ~85…100 | Shape fully reabsorbed into the icon. |
| 0…~250 | Backdrop scrim un-dims (dark ~250 ms; slower than the open dim) — **outlasts** the shape collapse by ~150 ms. Icon returns to rest brightness; the "Settings" label reappears as the scrim clears. |

---

## 2. Start shape & morph origin
- A **separate glass object**, not the icon morphing. Confirmed by eye (dark `blob_f40.png`, light `STRIP5_anchor.png`): the gear icon stays intact and the "Settings" label shows *through* the emerging blob.
- **Start size:** ≈ 210–217 × 135–143 px (dark re-measure ~210×143 @ x[170,380] y[317,460], centre ~(275,388)); ≈ 0.29 w × 0.19 h of the final panel.
- **Start corner:** proportionally rounder than the settled panel (near-capsule); sharpens into the squircle as it grows.
- **Origin/anchor of the scale:** the menu's **top-left corner**. Top edge stays pinned at the icon's bottom (~y346 dark / ~y358 light); left edge drifts outward, right + bottom sweep far down-right.

---

## 3. Size springs (open)
**[RECONCILED — single uniform spring, both axes together, NO lead/lag.]** The dark motion analyst's "width leads height by ~2 frames" is **not reproducible**: the dark re-measure found right and bottom edges rise in lock-step and peak together at f51–52 (f48: 830.5/1047.5, f50: 843.5/1063.5, f52: 844.5/1065.5), and the light clip was uniform in both the analysis and its re-measure (h/w constant ≈ 0.94 throughout growth).

Reconciled parameters:
- **response (SwiftUI `Spring(response:dampingFraction:)`, ω_n = 2π/response): ≈ 0.28 s.** Dark re-measure ~0.25–0.27 s; light re-measure 0.31 s (0.309–0.313). Both within tolerance; 0.28 s is the working midpoint. If you want per-mode fidelity: dark ~0.26 s, light ~0.30 s.
- **dampingFraction ζ ≈ 0.78** (dark ~0.77, light ~0.78).
- **Overshoot ≈ +2.2–2.4 %** (dark: width 2.4 %, height 2.2 %; light: 2.27 %/2.14 %, peak both axes f59).
- **Settle:** ~0.26 s after the peak (light re-measure notes settle-to-2 % is ~0.33 s from t0, i.e. total ~400–430 ms — matches the dark settle window f62–64).

**Stiffness/damping form (mass = 1):** k = ω_n² and c = 2ζω_n with ω_n = 2π/0.28 ≈ 22.4 rad/s → **k ≈ 502, c ≈ 35**. (Analyst point fits: dark k≈585/c≈38 at 0.26 s; light k≈435/c≈32 at 0.30 s — bracket this.)

**In the launcher's own parametrisation** (`GlassMotion.Spring(durationS).Tune(durationS, bounce)`, where ζ = 1 − bounce): use `Spring(0.28, x0).Tune(0.28, 0.22)` for BOTH width and height.

---

## 4. Content timing
- **Open:** rows opacity 10→90 % over ~65–100 ms **[RECONCILED faster]**, beginning essentially with the shape growth (dark: ~90 ms after spring-t0 onset, i.e. right as the blob becomes visible), completing at/just before the overshoot peak. Slight top-first positional reveal (rows anchored to the panel top, revealed as it extends). The selected-row **✓ arrives with its row**, not last.
- **Close:** content is a **near-instant cut** — one frame (≤17 ms dark; light text energy 28→2 in one frame), ~50–70 ms **ahead** of the shape finishing. (A ~30–60 % one-frame "spike" seen mid-collapse is the near-transparent rows sliding through the sampling band — a motion artifact, not real opacity.)

---

## 5. Close behaviour
- **~2–3× faster than open, ease-IN (accelerating), NO overshoot, monotonic.** Duration ~85–100 ms for the shape. Collapses toward the icon/top-left.
- Corners **round out** on the way down (opposite of the open's sharpen-in).
- Content cut first (§4). Scrim clears *after* the shape (§7).
- The launcher's current close curve (opacity leads; width u^1.6 / height u^1.1 over ~105–124 ms; rows cut bottom-first ≤35 ms; corners round back to capsule) already matches this well — keep it. Only tweaks: (a) make the anchor/value fade even sharper (~1 frame), (b) if a scrim is added, un-dim it over ~250 ms so it clearly outlasts the shape.

---

## 6. Anchor behaviour
- The icon **stays fully visible and separate** the whole time; never covered, never faded. Badge "1" persists through open and close (dark re-measure: red badge present in both closed and open frames). The "Settings" text label is **hidden while the menu is open** (dark: bright-pixel count 8155→0; it is occluded/ghosted by the menu's top-left corner) and reappears on close.
- **Dark:** icon holds position (centre ~(199.5,206) unchanged) and simply **dims in lock-step with the scrim** — no highlight, no measurable scale (any change within ±5 % threshold error).
- **Light:** icon **dims ~−30 % on touch-down**, then **scales up ~+10 % w / +16 % h and lifts ~18–26 px**, then **re-brightens at menu commit**, held enlarged + brighter than the dimmed backdrop. Excluded from the scrim.
- **[RECONCILED divergence, unresolved]** The press lift/scale is clearly measurable in light but not in dark (dark icon on dark ground, ±5 % measurement floor). Treat it as the standard iOS long-press lift present in both; implement a subtle press lift and keep the anchor lit above the scrim.

---

## 7. Backdrop (scrim, blur, zoom)
- **Global dimming scrim over the whole screen** is the primary depth cue — not a drop shadow.
  - **Dark:** dims to ~48–50 % brightness (dark re-measure floor **0.481**, analyst 0.484; ≈ a 50 % black overlay). Fades in over ~190 ms (floor by f50), fades out over **250 ms** (f136→f151), which **outlasts** the ~100 ms shape collapse.
  - **Light:** dims ~**−23–24 %** (to ~0.76×), uniform across the frame. Reverts symmetrically on close.
- **No wallpaper zoom, no parallax** (patch positions do not shift).
- **No detectable *added* blur** on the already-blurred wallpaper — and backdrop-blur **sigma is not recoverable** from this featureless grey/black gradient wallpaper (only a lower bound ~2–2.5 px). Treat as "moderate uniform backdrop blur present," magnitude a design choice.
- The pressed icon is **excluded** from the scrim (clear in light; consistent with dark).

---

## 8. Geometry (points)
Working scale **s ≈ 2.4 px/pt** (see §12 for the ambiguity). All figures ± the per-cycle spread noted.

| Quantity | Pixels | Points (@ s≈2.4) |
|---|---|---|
| Menu panel (settled) | 749–751 × 702 (dark), 749 × 702 (light); top-left (82–83, 347–358) | ≈ 310 × 293 pt |
| Overshoot peak | ~766 × 719 (light), ~773 × 722 (dark) | +2.2–2.4 % |
| Row pitch | 107 px (per-gap 104–108) | ≈ 44 pt |
| Top / bottom padding (symmetric) | ~30–31 px | ≈ 13 pt |
| Icon-column centre from interior-left | ~108–111 px | ≈ 45 pt |
| Text-left from panel-left | ~176–177 px | ≈ 72–74 pt |
| Icon→text gap | ~48–66 px | ≈ 20–27 pt |
| Text cap height | 29–30 px | ≈ 12.1 pt cap → **17 pt** body |
| SF Symbol box | ~43–54 px | ≈ 20–23 pt |
| Gap icon-bottom → menu-top | 34–40 px | ≈ 14–17 pt |
| Menu left edge vs icon left edge | 13–16 px LEFT of it | ≈ 5–7 pt (near-left-aligned to the icon) |

**Placement:** menu opens **below-and-right** of the icon; left edge near-aligned to the icon's left edge (biased ~5–7 pt outward/left), running far to the right; menu centre well right of the icon centre. Never covers the icon.

---

## 9. Corner shape
**[RECONCILED — the motion analysts' 61–68 px is too small; both re-measurements say ~88 px, agreeing with the material analysts' ~97 px.]** The 61–68 px reading captured only the tight apex.

- **Shape: continuous squircle (superellipse), NOT a circular arc.** Dark material fit n≈2.5 (circle residual 0.33 vs squircle ~0.00); dark re-measure n≈2.7 (cost 0.003 vs circle rmse 0.28); light material n≈2.65. (The light re-measure got a free n≈2.07 — it simply could not separate squircle from circle at this radius/edge-softness; not evidence against a squircle.) **Use n ≈ 2.5.**
- **Circular-equivalent radius ≈ 88–97 px ≈ 37–40 pt.** Controlling superellipse semi-axis a ≈ 116–121 px ≈ **49 pt**.
- **Roughly constant** regardless of panel size (iOS does not scale it with the panel); ≈ 0.12–0.13 × menu width. The start blob's corner is proportionally rounder.

---

## 10. Material, per theme

### DARK
- **Tint:** neutral grey, **no colour cast** (R=G=B). Compositing model D=(1−a)B+aT → **alpha ≈ 0.41 over tint L≈102 (rgb ~102)**. Body sits **+24 L above the card beneath it** (L49 over L25); interior mean L≈106 over mid-grey.
- **Blur:** uniform backdrop blur, present; sigma not recoverable (lower bound ~2 px).
- **Rim (directional, light-from-top):** TOP = bright specular L≈170 (+64 over interior), a ~2 px core **plus a soft inner glow decaying over ~18 px**; BOTTOM = weaker bright rim L≈126; LEFT/RIGHT = thin **dark refraction hairlines** (L≈40 / L≈30, ~2 px). Rim width ~2 px ≈ 0.8 pt.
- **Refraction:** confined to a ~2–3 px edge band (dark hairlines on verticals, bright specular on horizontals); **no measurable bulk lens displacement** (content-limited by the featureless wallpaper).
- **Shadow:** outer drop shadow **negligible / unmeasurable**; depth carried by the ~50 % scrim + rim.

### LIGHT
- **Tint:** achromatic, no hue. Per-channel fit glass = 0.46·backdrop + 111 → a **light-grey fill ~rgb(206–212) at ~0.54 alpha**; body L≈212 over bright backdrop, ~168 over dark. Classic light "Regular" translucent material — whitish but a touch grey, **not pure white**.
- **Blur:** strong, uniform; sigma not identifiable (near-featureless gradient backdrop).
- **Rim:** TOP + BOTTOM bright specular (~255 / ~248, ~3 px, decaying ~15 px into the interior); LEFT/RIGHT a thin dark contact line; faint interior lift on the verticals. Light reads top-down.
- **Refraction:** not observable on this content — recreate the edge as **specular rim + contact shadow**, not a visible lens.
- **Shadow:** a **soft, tight** panel drop shadow — ~10–15 L within 40–60 px of the edge, slightly deeper below, plus a crisp 1–2 px near-black contact line. Sits under the ~24 % global scrim.

---

## 11. Rows
- **6 rows, no separators:** Bluetooth, Wi-Fi, Cellular, Battery, Edit Home Screen, **Remove App (red)**.
- **Text:** 17 pt SF Pro Text, weight Regular. Dark = white rgb(250); Light = near-black rgb(20) ("label").
- **Row icons:** monoline SF Symbols, ~20–23 pt box, left-aligned in the icon column. Dark = white rgb(248); Light = black.
- **Destructive "Remove App":** text + `minus.circle`, systemRed attenuated by the glass — dark rendered ≈ rgb(250,120,122); light rendered ≈ rgb(212,41,46) (core) / rgb(209,77,81) (mean). Same Regular weight.
- Trailing side is empty (fixed-width panel).

---

## 12. Scale ambiguity (px→pt)
- **Dark:** **s ≈ 2.38 px/pt, HIGH confidence** (material + re-measure agree). Full width 1024/430 pt (iPhone Pro Max) = 2.381; cap 29/12.14 = 2.39; row 107/44 = 2.43. Reject motion's 2.6–3.9. iOS 26 icons render ~85 pt (larger than the classic 60 pt) — do not anchor scale on the icon.
- **Light:** **s ≈ 2.45 px/pt** favoured (material + re-measure) over motion's 3.0 (at 3.0 the rows come out 36 pt, off-spec). Genuinely ambiguous (this clip may be an iPad or a differently-cropped device).
- The two clips are **different resolutions (1024×1230 vs 928×1230) → almost certainly different devices/crops.** **Port in relative terms** and use s≈2.4 as the working value. The **spring params are unit-free in time and unaffected.**

---

## 13. Concrete launcher changes (file / function level)
Repo: `C:/Users/Administrator/source/S1mp1e/avalonia`. All line numbers in `Views/MainWindow.axaml.cs` unless noted. The launcher already has both paradigms behind one per-anchor flag `bool fades = anchor is GlassSelect` (:609). **This is tuning + one addition, not a rewrite.**

**Keep as-is (correct iOS pull-down):** every `GlassSelect` value picker (VersionBox 3-col grid, LoaderBox, AfterLaunchBox, AccentBox, ThemeBox, ModSortBox — all via `ShowGlassMenuCore` :478, `fades=true`): they cover the value and use the existing split "drop-then-widen" springs (an iOS pull-down genuinely does overshoot height ~+11 %). Do not touch their springs.

**Apply the context-menu morph to:** `ShowAccountSwitcher` (:2234, anchor = `AccountChip`) — the direct analogue of long-pressing an app icon. Secondary/cheap: the sidebar +/logout popup (:2225) and the skin 尋找/導入 button (:1845), which already run the non-`fades` path.

1. **[BIGGEST GAP] Add the backdrop scrim.** `MainWindow.axaml:1051` `MenuDismiss` is `Background="Transparent"` — no dim exists. Add a dimmer on `OverlayHost`/`MenuDismiss`, animated in `AnimateMenu`:
   - Context-menu anchors (account switcher): dark ~0.35–0.5, light ~0.20.
   - Lightweight dropdowns: keep light (~0.12–0.18) or none (not modal).
   - Fade in ~190 ms with the open; fade out ~250 ms on close so it **outlasts** the shape. Exclude the pressed anchor from the dim.

2. **Unify the open spring for context-menu anchors.** `AnimateMenu` :767–768 currently builds split springs `sw = Spring(0.27).Tune(0.27,0.27)` and `sh = Spring(0.150).Tune(0.150,0.46)`. For the non-`fades` / account-chip path, use **one** spring for both axes: `Spring(0.28,x0).Tune(0.28, 0.22)` (response 0.28 s, ζ≈0.78, overshoot ~2.3 %). Gate on `_anchorFades` so GlassSelect keeps its split springs.

3. **Seed a blob at the icon's bottom + top-left origin (context-menu path).** Placement/seed :634–646 and origin :760–762. Today the non-`fades` seed = the chip box and `MenuItems.RenderTransformOrigin` = top-**right** (1,0). For the context-menu path: seed a small blob ~0.30 w × 0.20 h pinned at the anchor's **bottom** edge, set the origin to top-**left** (0,0), grow **down+right**.

4. **Placement gap + alignment (context-menu path).** :624 `topIfDown = pr.Y + 6` and :610–621 alignment. Increase the gap **6 → ~14 DIP** and force **left-alignment to the anchor's left edge** (biased ~5–7 pt outward). Keep GlassSelect covering the value.

5. **Corner.** `MenuPanelRadius` :719 is 16; drawn as n = 2 + 0.8·k (settled n=2.8), a = 1.31·R (:922–923). Raise **MenuPanelRadius 16 → ~20–22 DIP** and lower the settled exponent **2.8 → ~2.5** (change `n = 2 + 0.8*k` to `n = 2 + 0.5*k` at :923). Keep it constant across menu sizes. Note: match the *fraction* (~0.13 × width), not the absolute iOS 40 pt, because the launcher menu is smaller.

6. **Directional rim.** `ApplyMenuGlassTheme` :965–987. Today: uniform Fresnel (HighlightOpacity .30 dark/.22 light, width 1.5, blur 1.2, angle −70) + `MenuRimPath` top-only white hairline (dark) / all-round dark hairline (light). Changes: (a) widen the **top inner glow to ~15–18 px** (raise `HighlightBlurRadius` from 1.2 to ~6–8 on the top band, or add a dedicated soft top gradient); (b) add a **bottom bright rim at ~0.7× the top**; (c) in **dark**, make LEFT/RIGHT a **dark** hairline (they read as refraction, not white Fresnel) — invert the side contribution of `MenuRimPath` in dark; keep light's all-side dark contact line and add the bright top/bottom.

7. **Dark material — verify, then decide.** `ApplyMenuGlassTheme` :963 `SurfaceColor = dark ? #552A2A2C : #9AFCFCFE`. The code comment (:961–963) claims #552A2A2C already lifts the body to ≈L67 (the measured +24 L over the L43 page). **Verify in-app that the body actually sits ~+24 L above the card behind it.** If it does, leave it. If the comparison's "no lift" observation is the live state, move toward `Color.FromArgb(0x69,0x66,0x66,0x68)` (alpha ~0.41, rgb ~102). **CAVEAT / needs user sign-off:** the fully-opaque grey partly reverses the 2026-09-19 "clear glass / 只是一個透明片" decision — the context menu is genuinely a ~41 %-opaque grey, not a clear sheet, so this is a real design-intent conflict, not a bug. Blur ~1.5–2 (BlurRadius :957) is fine.

8. **Light material (minor).** :963 light `#9AFCFCFE` (rgb 252 @0.60) is slightly whiter/more opaque than iOS. Nudge toward **~rgb(206–212) @ ~0.54** (e.g. `#8ACECED0`). Low priority; current is readable.

9. **Shadow.** :973–977 `ShadowRadius 9 / offset (0,2) / #26`. With the scrim added: in **dark** reduce toward ~0.05 or off (rely on scrim+rim); in **light** bias downward — `ShadowOffset (0,5)`, `ShadowRadius ~6`, alpha ~0.15–0.17.

10. **Anchor lift (context-menu path).** Non-`fades` anchors today just stay visible (`RestoreAnchor` :875). Add a subtle press **lift (~+6–10 % scale, a few px up)** on the account chip and keep it **brighter than the dimmed backdrop** (exclude from scrim), mirroring the iOS pressed-icon treatment. Keep GlassSelect's cover-and-fade (`StartAnchorReturn` :883).

11. **Content stagger (optional nicety).** :798–802 rows already animate together with ✓-with-row (correct). Optionally add a tiny **top-first open stagger (~2–3 frames across the 6 rows)** to mirror iOS's positional reveal.

---

## 14. Reconciliation ledger (who I used where)
- **Width-vs-height lead:** used **re-measure** (single spring, no lead) over dark motion analyst's 2-frame lag. Reason: dark re-measure shows lock-step peaks; light was uniform in all passes.
- **Corner radius:** used **re-measures + material analysts** (~88–97 px, ~40 pt) over motion analysts' 61–68 px. Reason: two independent re-measures converge on ~88 px; 61–68 px only fit the apex.
- **Corner shape (squircle vs circle):** used the **majority** (n≈2.5–2.7, continuous) — dark material, dark re-measure, both light material — over the light re-measure's "indistinguishable from circle," which is a resolution limit, not counter-evidence.
- **Open content fade duration:** used **re-measures** (~65–100 ms) over the 100 ms claim. Faster, still lags the shape and completes near the peak.
- **px/pt:** used **re-measures/material** (dark 2.38, light 2.45) over motion's 2.6–3.9 / 3.0.
- **Everything else** (blob-is-separate, start size/position, dim floors and timing, spring response/damping/overshoot, one-frame close cut, close collapse curve, panel size 749×702, rows, colours, rim structure, scrim, icon/badge/label) **agreed across analysts and re-measures within ~10 %** and is carried through directly.

## 15. Key evidence strips
- `…/scratchpad/vid/final_spec/OVERVIEW_reconciled.png` — consolidated montage (my folder).
- `…/vid/reverify_dark2/STRIP_open_dark2.png`, `STRIP_close_dark2.png`, `blob_f40.png` — dark open/close + blob emergence.
- `…/vid/verify_light2/strip_open.png` — light open with measured bbox.
- `…/vid/look_dark2/strip4_corner.png`, `…/vid/look_light2/STRIP2_corner_zoom.png` — squircle corner + rim.
- `…/vid/look_light2/STRIP5_anchor.png` — anchor/label/placement.
- Per-frame data: `…/vid/reverify_dark2/track4.csv`, `…/vid/look_dark2/master.csv` (+ `panel_all.csv` for crisp geometry), `…/vid/light2_lue/per_frame.csv`, `…/vid/verify_light2/table.npy`.

## Open questions the recordings cannot answer
- Two different devices/crops: the dark clip is 1024x1230 and the light clip is 928x1230, so they are almost certainly not the same phone (dark reads as an iPhone Pro Max at 2.38 px/pt; light is ambiguous, possibly an iPad, 2.45-3.0 px/pt). Absolute point conversions therefore carry real uncertainty - the videos cannot pin a single device scale. Spring timing is unit-free and unaffected, but confirm the target device/scale for the launcher's own DIP mapping.
- Pressed-icon lift/scale: clearly measurable in LIGHT (icon dims -30%, scales +10-16%, lifts ~18-26px, re-brightens) but NOT resolvable in DARK (dark icon on dark ground, +-5% threshold floor). The recordings cannot confirm whether the two modes behave identically here.
- Backdrop blur sigma is not recoverable in either mode - both wallpapers are near-featureless grey/black gradients, which are blur-invariant. Only a lower bound (~2-2.5 px) exists. The blur is definitely present and spatially uniform, but its magnitude is a design choice, not a measurement.
- Bulk refraction / lens displacement of the wallpaper cannot be measured (featureless backdrop). The edge lensing (rim) is confirmed; whether there is any whole-panel displacement behind the glass is undeterminable from this content - treat the edge as rim-light + contact shadow, not a visible lens.
- Whether the scrim excludes the pressed icon is explicit only in light (icon stays lit above the dim); in dark it appears excluded but the overall darkness makes it hard to confirm.
- Exact close-collapse curve shape: only ~3-4 intra-transition frames exist at 60 fps, so the close is characterised (ease-in, ~85-100ms, no overshoot, ~2-3x faster than open) but cannot be parametrically fit. A higher-fps capture would be needed to nail the exact easing.
- Dark menu-body material intent conflict (not answerable from the recordings, needs a user decision): iOS shows a ~41%-opaque neutral grey body (+24 L over the card), but that partly reverses the user's 2026-09-19 'clear glass / only a transparent sheet' decision for S1mp1e. The videos say what iOS does; they cannot say which the user wants for the launcher.
- The recordings show only the Settings icon's menu (6 fixed rows). They cannot tell us how the panel width/height, row count, or corner behave for menus with different content lengths, or whether the corner radius truly stays constant for a much taller/shorter menu.
