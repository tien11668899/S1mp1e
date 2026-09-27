<!-- Derived 2026-09-25 from two iOS 26 Settings > Search "Suggest Apps" pull-down recordings (the value picker
     4 Apps / 8 Apps / [check] Don't Suggest): dark 下拉深.mp4 (1288x568, 791 f, 13.29 s) and light 下拉淺.mp4
     (1288x572, 800 f, 13.34 s). Both are VFR-encoded but land on clean 16.667 ms PTS steps => 60 fps, 1 frame = 16.7 ms.
     Sources: one deep analysis per clip (dark, light) + one independent re-measurement per clip. Where they disagreed the
     re-measurement won unless the data showed otherwise; every such case is flagged [RECONCILED] with the reason.
     Companion docs: IOS26_CONTEXT_MENU_SPEC.md (the account-chip context menu open/close) and
     IOS26_MENU_SELECTION_SPEC.md (the hover capsule). This doc covers ONLY the GlassSelect PULL-DOWN open and close. -->

# iOS 26 Pull-Down (value picker) — Open & Close — Final Implementable Spec for S1mp1e

Reconciled from two frame-accurate analyses (dark, light) and two independent re-measurements of two 60 fps iPhone screen recordings of the iOS 26 Settings **"Suggest Apps"** pop-up menu (a pull-down value picker with three rows: *4 Apps / 8 Apps / ✓ Don't Suggest*). In each clip the menu opens and closes **five times** (dark) / **six times** (light); every cycle is near-identical (spring params vary < 5 %), so the numbers below are stable, deterministic values.

**This is a different animation from the context menu** (`IOS26_CONTEXT_MENU_SPEC.md`). A context menu is *born beside* its anchor as a uniform scale under a modal scrim. A pull-down is **born on top of its trigger, covers the value, uses NO scrim, and grows on TWO different springs — a fast deadbeat "drop" in height, then a slow springy "widen" — while its glass stays a near-transparent lens until the last moment.** Keep them separate; the launcher already gates them on `_anchorFades = anchor is GlassSelect`.

**Rule applied:** where an analyst and a re-measurement disagree, the re-measurement wins unless the data shows otherwise. Every such case is flagged **[RECONCILED]**.

**What the clips cannot show (carried as design decisions, not measurements):** the value never changes and no row ever gains a hover capsule, so **all closes are dismiss-type (tap-outside)**; pick-value and tap-outside are therefore the *same* close here (any distinct "select" flourish must come from the drag-select recordings — see `IOS26_MENU_SELECTION_SPEC.md`). There is no value-picker "current value" cue to measure either.

---

## 0. The one-line model

**The grey value text (and its chevron) on the trigger fade OUT together in ~2 frames the instant you tap; a glass nub is born on top of the value, balloons into a full-height bulging ellipse that then squares into a rounded rect, and its grey tint fills in LATE (only in the last ~80 ms). To close, the glass de-tints first, deforms back through an ellipse and collapses to the trigger in ~90 ms; then the grey value text re-forms from the anchor side and springs back into place, and the chevron returns LAST (~7 frames after the text).** No scrim. The transform origin is the trigger (top-right); the menu covers the value.

---

## 1. Timeline (ms)

Onset t = 0 = the open spring's t0 (the tap frame). "First-visible" = the first frame the low-opacity nub is clearly readable. Frame numbers are from the dark clip's cycle 2 (open f147, close f200) unless noted.

### OPEN (~350–420 ms perceived; two coupled phases)
| t (ms) | Event |
|---|---|
| −33…0 | **Tap-highlight:** the trigger value text + chevron **brighten ~+35–37 % for ~2 frames** (dark f147–148, matched-alpha 1.37). |
| 0 | Spring t0. A glass nub is born on the value-text rectangle (dark centre ≈ (946,173); light ≈ (943,160)). |
| 0…+33 | **Value text AND chevron fade OUT together, ~2 frames (~33–40 ms)** to near-zero (§3). Text is gone before the glass reaches half size. |
| 0…+120 | **Height "drop":** height reaches full fast and **deadbeat — 0 % overshoot** (§4). By ~+180 ms the shape is a full-height, fat, bulging ellipse. |
| 0…+360 | **Width "widen":** the slow, springy, visible axis — grows left, overshoots ~+3.6 %, settles (§4). |
| +75…+180 | **Content lenses in:** rows appear magnified ~1.2× and blurred, peaking at the fat-ellipse frame, then de-magnify/sharpen to rest (§6). Readable in ~6 frames (~100 ms). |
| +150…+230 | **Fill ramps in LATE:** the grey tint is ~0 (pure lens) until ~150–170 ms, then ramps to full over ~67–83 ms (§7). Corners square off (ellipse → squircle, §5) as the fill arrives. |
| +360…+420 | Settled: rounded-rect squircle, full grey tint, rows sharp at rest. |

### CLOSE (~90 ms shape; NOT a mirror — the shape is ~3–4× faster than open, then a slow text return)
| t (ms) | Event |
|---|---|
| 0 | Close start (menu still full). |
| 0…+33 | **Fill/tint collapses FIRST** (~22 → 0 L in ~33–50 ms), leading the shape (§7). |
| 0…+90 | **Liquid collapse:** squircle → ellipse → nearly-pointed (n falls 5→4→1.3), de-tints to a transparent lens (behind-glass toggles refract through it again), then shrinks toward the trigger; **near-critically damped, NO rebound of the glass** (§5). Retracts to the top-right anchor. |
| ~+90 | Glass gone. |
| +50…+420 (dark) / +50…+240 (light) | **Trigger text return (§3):** the grey value re-forms from the anchor side and **springs back** (a high-damping spring; blurry bright first, then sharpens). Dark legibility recovery ~344–368 ms; light ~174–189 ms. |
| +160…+520 (dark) | **Chevron returns LAST:** it starts ~7 frames (~115 ms) after the text begins and finishes with it (§3). |

---

## 2. Placement, anchor & seed (transform origin)

- **The menu covers the trigger value.** [Both clips.] Its **transform origin is effectively the trigger, top-right corner** (dark: right edge moves only ~80 px and top only ~29 px, while the left edge travels ~385 px and the bottom ~312 px). It **grows LEFT and DOWN** (light: down first, then left). The nub is born on the value-text rectangle.
- **Settled geometry (dark):** box x = 418–1170 (w = 752), y = 126–506 (h = 380). Top-right corner sits **~42 px (≈ 17 pt) right of the chevron** and **~29 px (≈ 12 pt) above the trigger row top**. Row pitch 107 px. Aspect ~1.85 (wide). [Light within ~2 %.]
- **Seed (birth):** a small rounded rect **on the value-text rectangle** — dark ≈ 210 × 40+ px on x[803,1090] y[150,196]; light the trigger value cell ≈ 164 × 40 px. The glass is born **as an ellipse over the value** (§5) and balloons out; the top and right barely move.
- **No scrim** (§8). The pull-down is non-modal.

The launcher already implements this shape of placement (`_seedR/_seedL/_seedT/_seedB` for the `fades` path, origin top-right, `right = anchorRight + 12`, `topIfDown = anchorCY − 13`). See §11 for the small placement deltas.

---

## 3. The trigger value text + chevron — the user's core ask ("下拉要跟 Apple 一樣淡入淡出")

**This is the load-bearing behaviour the user called out. It is strongly ASYMMETRIC and the chevron is treated differently from the text on close.**

### 3a. OPEN — snap-out with a tap flash (text + chevron together)
- **Tap-highlight:** for ~2 frames before the fade, the value text and chevron **brighten together ~+35–37 %** (dark f147–148: text matched-alpha 1.37, chevron 1.38). [Both analyst and re-measure agree.]
- **Fade-out:** the value text **and** the chevron then **fade out together, very fast — ~2 frames / ~33–40 ms** to near-zero (dark C2: alpha 1.30 → 0.48 → 0.02 across f148–150; light mean 34 ms). The text is gone before the glass nub reaches half size.
- **Formula:** `alpha_out(t) = 1 − smoothstep(0, 0.033, t)` on the value **and** chevron together (a tap flash `1 → 1.36 → …` may precede it but is optional polish). This matches the launcher's current open fade of the whole anchor — see §11, this part is already close.

### 3b. CLOSE — gentle spring-in return, with a positional rebound, chevron LAST
Sequence per close:
1. Menu-content text fades out with the glass (~first 2–3 close frames).
2. Glass gone ~+90 ms.
3. The grey trigger value text **re-forms and fades in**. It is a **high-damping SPRING, not a plain ease** (light: step-response RMSE 0.010–0.040 vs exponential-ease RMSE 0.059–0.144 — the S-shape is unambiguously spring-like).
   - **Duration:** dark **legibility recovery ~344–368 ms** (raw brightness comes up faster, ~167 ms — the text appears as a blurry bright blob first, then **sharpens**); light **~174–189 ms** (10→90 %).
   - **Spring params** [RECONCILED — response is t0-sensitive; both readings agree on the feel]: **response ≈ 0.45–0.50 s, dampingFraction ≈ 0.78–0.81, overshoot 1–3 %.** The raw **opacity is monotonic** (no visible opacity bounce, max overshoot ~2.4 %). Implement the "回彈" as the **spring easing curve + the liquid-glass collapse**, plus one of the two positional/scale cues below — **not** a literal opacity bounce.
4. **Positional rebound (the visible "回彈"):** [dark, approximate — measured in a low-alpha region] the text re-coalesces **FROM THE RIGHT (anchor) side** and slides left into place — its brightness-weighted centroid **overshoots right ~+15–18 px** (peak ≈ 961–966 px vs rest ≈ 946–947 px) then settles left over **~10 frames (~170 ms)**, with a tiny left undershoot before rest. Its horizontal extent grows 148 → 283 px **without** width overshoot (it is not scaling wider; it is re-forming and translating). To match Apple feel on a desktop label, reproduce this as **either** a small horizontal reform from the anchor side (~+12–15 DIP overshoot decaying over ~170 ms) **or** a subtle **scale 0.97 → 1.0** on the same high-damping spring — the light clip shows no geometric width overshoot, so a scale spring is the safe cross-mode choice; the dark clip shows the horizontal reform, so add it if the launcher renders the value glyph-by-glyph cheaply.
5. **Chevron returns LAST** [RECONCILED — re-measure over analyst]: the analyst read +17…33 ms (1–2 frames); both re-measurements (raw-brightness onset and a visual strip) show the chevron onset lags the text onset by **~7 frames (~110–120 ms)** — at dark f212 the full "Don't Suggest" is already readable (blurry) with **no chevron**, and the chevron only fades in at f213–214, then both reach full at about the same time. **Implement the chevron return as a ~100–120 ms delay after the text begins, finishing with it.** (Direction — chevron last — is unanimous; only the magnitude was corrected upward.)

**Edge sharpness recovers faster than brightness** (the text looks blurred first, then brightens/firms) — reproduce with the launcher's existing anchor-blur clear (`SetAnchorBlur`), but on the spring clock, and drive the chevron on its own delayed clock.

---

## 4. Size springs (open) — a fast deadbeat "drop" + a slow springy "widen"

**The pull-down grows on TWO different springs, and this is the whole character of the motion.** The launcher's split-spring architecture ("drop-then-widen") is correct; the parameter values need updating.

### Height — the "drop" [RECONCILED — DEADBEAT, corrects the old "+11 % height overshoot" belief]
- **response ≈ 0.12 s, dampingFraction ≈ 1.0 (critically damped), overshoot 0 % (deadbeat).** Height reaches full early (dark by ~f158 ≈ +180 ms) and **does not overshoot.**
- Evidence: analyst dark "reaches full ~380 by ~f158, effectively deadbeat, NO overshoot"; light re-measure per-cycle fits **0.101–0.124 s, ζ ≈ 1.0–1.1, 0 % overshoot** (and the analyst's own per-cycle data 0.105–0.126 s agrees — the "0.20 s headline" was overstated). **The companion context spec's remark that "an iOS pull-down overshoots height ~+11 %" is contradicted by these dedicated pull-down clips — height is deadbeat.**
- Launcher form: `Spring(0.12, x0).Tune(0.12, 0.0)`.

### Width — the "widen" (the visible spring)
- [RECONCILED — response ≈ 0.36 s, not 0.25 s.] The analyst's 250 ms holds only on a truncated post-birth window; fitting the full left-edge expansion across all five dark cycles gives **response 356–406 ms (mean ~374 ms), ζ ≈ 0.63** — which matches the analyst's own caveat/recommendation of SwiftUI response 0.30–0.40 s.
- **overshoot ≈ +3.6–3.9 % of settled width** [RECONCILED — re-measure ~3.7 %, analyst 2.2 %]; the left edge overshoots inward ~24 px (~6 % of the 385 px travel), edge-detection-sensitive, treat as approximate. Settles ~f172–176 (~+420 ms).
- **dampingFraction ≈ 0.65–0.73** (bbox-width overshoot 3.6–3.9 % ⇒ ζ ≈ 0.70; the launcher's current 0.73 is within tolerance for the *overshoot*, the gap is the *response*).
- Launcher form: `Spring(0.36, x0).Tune(0.36, 0.30)` (response 0.36 s, ζ ≈ 0.70, overshoot ~4.6 % — or `Tune(0.36, 0.27)` to land ~3.5 %). Per-mode: dark ~0.37 s, light a touch faster (~0.26–0.30 s, low confidence — light under-reads width over the white card, so prefer the dark value as the working number).

### Vertical rise (secondary)
- The whole box is **born ~34–38 px LOW and rises** during settle (dark centre-y 350 → 315), near-critically damped, < 2 px below-settle overshoot. This is coupled to the content being born low (§6). Model as part of the height/position settle, not a separate bounce.

### Summary
| Axis | response | dampingFraction ζ | bounce (=1−ζ) | overshoot | note |
|---|---|---|---|---|---|
| **Height (drop)** | **0.12 s** | **≈ 1.0** | **0.0** | **0 %** | deadbeat, lands first (~120–180 ms) |
| **Width (widen)** | **0.36 s** | **≈ 0.70** | **0.30** | **~3.6–3.9 %** | the visible spring, settles ~420 ms |

---

## 5. The liquid-glass morph (shape over time, bulge, corner)

**The glass is a superellipse whose corner exponent `n` ramps ELLIPSE → SQUIRCLE on open and runs it in reverse (and further, to a point) on close.** This is the "液態玻璃形變" the user describes on both open and close.

### Open (ellipse → squircle)
- `n` climbs monotonically from **~2.3–2.6 (a fat ellipse) at the peak-liquid frame** up to the settled squircle over ~f156–168 (~200 ms). [Peak-liquid n agrees across analyst and re-measure.]
- **Settled `n` ≈ 5** [RECONCILED — re-measure ~4.3 (likely ~5 with a sharper edge extractor), NOT the analyst's 6.8; the absolute value is highly sensitive to edge softness, so do not over-trust any single number — the *morph* ellipse→squircle is certain, the settled exponent is ~4–6].
- **Fill-fraction cross-check (light):** the mask fills **~0.79–0.84 of its bbox at emergence** (a mathematical ellipse is π/4 = 0.785) and rises to **~0.94 settled** (a rounded rect with a moderate corner radius) over ~250 ms. Born ellipse, squares to rounded rect — confirmed by eye.
- While it is an ellipse it **bulges on all sides** (biggest bulges on the sides and bottom over the card): half-height stays ~190 px while half-width grows 316 → 382 then relaxes to ~375. A faint bright specular rides the upper edge during the morph; a soft drop shadow sits under the right/bottom edge; no hard border.

### Close (squircle → ellipse → point)
- `n` falls **~5 (settled) → 4 → ~1.3 (heavily deformed/collapsing)** over the ~90 ms collapse: the rounded rect goes soft/elliptical, **de-tints to a transparent lens** (behind-glass toggles refract through it again mid-collapse), then shrinks toward the top-right anchor. Height half-axis stays ~190 until the final collapse; the box mostly **loses WIDTH and drops its top back toward the trigger row.** **Near-critically damped, monotonic, NO rebound of the glass** (only the *text* rebounds afterward, §3b).

### Corner mapping for the implementer
The launcher already draws the panel as a superellipse `a = 1.31·R`, `n = 2 + 0.5·k` (settled n ≈ 2.5) with `k = cornerK`. To hit iOS's pull-down morph:
- Drive `cornerK` so **`n` starts at ~2.0–2.3 (ellipse) while small and rises to a settled ~4–5** during the last third of the open (raise the settled exponent for the *pull-down* path specifically — the current `n = 2 + 0.5·k` tops out at 2.5, which reads too soft/round for the pull-down's squarer settled corner). On close, let `cornerK` fall back through the ellipse the same way (the launcher's `cornerK: pw` on close already does this direction).
- Keep `a` a fat radius while small so the birth frame reads as an ellipse (the launcher's `capsule = min(w,h)/2` seed already gives a near-elliptical birth — good).

---

## 6. Content lensing during the morph (a displacement model)

During the oval/bulge phase the rows are **genuinely lensed**, not merely faded:
- **Interior magnification:** the row text is magnified **~1.19–1.21×** at the fattest-ellipse frame (light: "Don't Suggest" width 344–352 px vs ~290 px settled = 1.19–1.21×; body-ink ratio 1.38×), **peaking at the oval phase and relaxing monotonically to 1.0× by ~f168** (~250 ms). The content ink is therefore **non-monotonic** [RECONCILED — light re-measure over analyst's "clean ~183 ms ease"]: it appears/peaks fast (readable in ~6 frames ≈ 100 ms) as the lens magnifies it, then **relaxes** as the glass de-magnifies into place, full settle ~250 ms.
- **Edge refraction:** the behind-glass content (the two toggle switches to the right) is **magnified and warped through the bulging edge, strongest where the ellipse bulges most** — i.e. displacement grows with proximity to the boundary, consistent with SDF/edge-refraction, not a uniform blur or a uniform scale.
- **Vertical displacement:** content is **born ~+60–64 px LOW** (with the box, §4) and converges up to rest by ~f170.

### Implementable displacement model (uses the launcher's own glass/lens renderer)
Two coupled terms, both keyed to the shape's "ellipse-ness" `e(t)` (where `e = 1` at the fattest ellipse, `e = 0` at the settled squircle — derive it from the same knob that drives `n`, e.g. `e = clamp((n_settled − n(t)) / (n_settled − n_peak), 0, 1)`):

1. **Interior magnification (whole-content scale about the panel centre):**
   `m(t) = 1 + 0.20 · e(t)` → peaks ~1.20× at the fat ellipse, relaxes to 1.0×. Drive the launcher's `labelScale` with this instead of a fixed time-window decay, so the magnification tracks the *shape* (peaks mid-open at the oval, not at t = 0).
2. **Edge refraction (behind-glass warp):** keep the launcher's existing Snell / circle-map edge band **active throughout the morph**, sharing the *deforming* outline (`MenuClip.Clip = outline`, already shared in `ApplyMenuFrame`). Because the outline is the fat ellipse during the morph, the edge band naturally warps the behind-glass content most where curvature is highest (the bulging sides/bottom) — this reproduces the toggle-warp for free. No new refraction term is needed; the fix is to make the band follow the ellipse and to keep it on (not fade it to the settled panel too early).
3. **Vertical birth-low:** offset the content down by `+Δy · (1 − scale_settle)` where `Δy ≈ 60 px @ s≈2.4 ⇒ ~25 DIP` at birth, converging to 0 as the box rises (§4 vertical rise). Optional but it is the clearest "content is inside a growing lens" cue.

The rows' **opacity** rides this: a fast rise to readable in ~100 ms (a low floor gives their earliest faint presence inside the small nub), then the sharpen is the blur clearing as `e → 0`. Do **not** model content as a slow linear opacity ramp — it is fast-in + lens-relax.

---

## 7. Fill, tint & alpha ramps (decoupled from the shape)

**The fill/tint is DECOUPLED from the geometry and behaves oppositely on open vs close.**
- **OPEN — LATE and fast:** the shape expands **almost fully transparent (a pure lens)** for the first ~150–170 ms, then the grey tint ramps **0 → full over ~67–83 ms** (dark C2 fill L 0 → 22 across ~f157–164), finishing as the corners square off (§5). Model: `fill(t) = smoothstep(0.15, 0.23, t)` (start ~150 ms after open-start, done ~230 ms), ease, no overshoot. **This is decoupled from the width/height springs — it is a time ramp gated late.**
- **CLOSE — FIRST and fast:** the tint collapses **before** the shape (dark C2 fill L 20 → 12 → 0 across f200–202, ~33–50 ms), leading the size collapse. Model: `fill_close(t) = 1 − smoothstep(0, 0.04, t)`.
- **Settled tint** [both agree]: **dark** interior L* ≈ 20 over black (+10.5 L* over the card beneath), gray ≈ RGB 49, **neutral, very slightly cool**; **light** interior L* ≈ 212 over the bright card.

**[DESIGN-INTENT NOTE — needs the same user sign-off as the context spec §10/§13.7]** iOS's pull-down body is a real ~grey tint (dark RGB ~49 / L* 20; light L* 212), **not** a clear sheet. The launcher pull-down currently uses the near-clear `SurfaceColor` per the user's 2026-09-19 "只是一個透明片" decision (`dark #552A2A2C`, `light #9AFCFCFE`). **The measurements say iOS tints the body; the user's standing decision says keep it clear for the pull-down.** Keep the clear sheet unless the user asks for iOS-faithful tint — but the **late/fast fill *ramp timing* above is a motion property and should be adopted regardless of the final tint alpha** (i.e. whatever opacity the pull-down body ends at, it should arrive late on open and leave first on close, not ramp linearly from t = 0).

---

## 8. Scrim / backdrop

**None.** [Both clips, both analyst and re-measure.] Out-of-menu luminance is unchanged rest-vs-hold (deltas ~0 at every probe). The pull-down is non-modal — no dimming layer, no wallpaper zoom, no parallax, no added backdrop blur beyond the panel's own edge lens. The launcher is already correct here (`_scrimLevel = fades ? 0 : …`).

---

## 9. Outside-tap vs select

**Indistinguishable in these clips.** The value never changes, the checkmark never moves, and no row ever gains a hover/press capsule during any hold, so **all closes are dismiss-type** and pick-value and tap-outside run the **same** close animation here. Treat them as identical in the launcher. If a distinct "select" flourish is wanted, take it from the drag-select recordings per `IOS26_MENU_SELECTION_SPEC.md` (§9–§10.7: keep the pill lit through the click, then the plain collapse, no dead time, no flash) — do **not** invent one from this footage.

---

## 10. Reconciliation ledger (who I used where)

- **Height overshoot:** used **both (agree) → deadbeat, 0 %, response ~0.12 s**, and explicitly *corrected* the companion context spec's "+11 % height overshoot" remark, which these dedicated pull-down clips contradict.
- **Width response:** used **re-measurements** (~0.36–0.37 s) over the analyst's 0.25 s (truncated-window artifact; the analyst's own caveat recommended 0.30–0.40 s).
- **Width / left-edge overshoot:** used **re-measurements** (~3.6–3.9 % width, ~6 % left-edge inward, approximate) over the analyst's 2.2 % / 3.4 %.
- **Settled superellipse exponent:** used the **re-measurement** (~4–5) over the analyst's 6.8 (edge-softness-sensitive); the morph itself (ellipse ↔ squircle, both directions) is unanimous.
- **Chevron return delay:** used the **re-measurement** (~7 frames / ~110–120 ms after the text) over the analyst's 1–2 frames; direction (chevron last) is unanimous.
- **Content fade-in shape:** used the **light re-measurement** (non-monotonic: fast-in + lens-relax, readable ~100 ms, settle ~250 ms) over the analyst's "clean ~183 ms ease."
- **Fill emergence fill-fraction:** noted both (analyst 0.79, re-measure 0.84); both confirm born-ellipse → 0.94 rounded rect.
- **Everything else** (transform origin/top-right, covers-the-value, grows left+down, no scrim, tap-highlight +35–37 %, fade-out ~33–40 ms text+chevron together, fill decoupled/late-open/first-close, settled tint, close ~90 ms near-critical no-rebound, text-return is a high-damping spring with a positional/scale rebound, five/six near-identical cycles) **agreed across analyst and re-measure within ~10 %** and is carried through directly.

---

## 11. Concrete gaps against the launcher's current GlassSelect pull-down

Repo: `C:/Users/Administrator/source/S1mp1e/avalonia`. Line numbers in `Views/MainWindow.axaml.cs` unless noted. The pull-down path is `_anchorFades == true` (`anchor is GlassSelect`). **These are tunings + two behavioural additions, not a rewrite.** Ordered by how visible each is to the user's complaints.

**G1 — [BIGGEST, the user's "縮回去…文字淡出後回彈" ask] The trigger return uses an ease, not a spring, fades the chevron WITH the text, and has no rebound.**
`StartAnchorReturn` (:1726–1745) drives `a.Opacity = MenuSmooth(0.10, 0.30, t)` (a smoothstep ease) on the **whole anchor** (value + chevron together) plus a blur clear, and never overshoots.
Fixes: (a) drive the value opacity/scale on a **high-damping spring** (`Spring(~0.47).Tune(~0.47, 0.20)`, §3b) instead of `MenuSmooth`; (b) add a small **positional reform / scale rebound** (0.97 → 1.0, or ~+12 DIP from the anchor side decaying ~170 ms); (c) return the **chevron on its own clock, delayed ~110 ms** after the text and finishing with it — split the GlassSelect content so the value `TextBlock` and the chevron `Path` animate independently (today they are one `StackPanel` faded as one `Control` in `GlassSelect.BuildContent`, `Controls/GlassSelect.cs:92–95`); (d) dark legibility recovery should run **~344–368 ms**, not the current ~200 ms window (light ~174–189 ms is fine).

**G2 — Height spring is underdamped; iOS height is deadbeat.**
`SetupMenuAnim` :1181 builds `_mSh = Spring(0.150).Tune(0.150, 0.46)` → ζ ≈ 0.54, ~13 % height overshoot. iOS height is **response 0.12 s, ζ ≈ 1.0, 0 % overshoot** (§4). Change to `Spring(0.12, open?0:1).Tune(0.12, 0.0)`.

**G3 — Width spring is too fast.**
`SetupMenuAnim` :1180 builds `_mSw = Spring(0.27).Tune(0.27, 0.27)` → response 0.27 s. iOS width is **~0.36 s, ζ ≈ 0.70** (§4). Change to `Spring(0.36, open?0:1).Tune(0.36, 0.30)` (keep the bounce; the response is the gap). Result: height lands ~120–180 ms, width settles ~420 ms — the measured drop-then-widen.

**G4 — Fill ramps in too early on open (not decoupled/late).**
`MenuTick` pull-down open :1251 sets `glassA: MenuSmooth(0.0, 0.15, t)` — the tint ramps 0 → full over 0–150 ms. iOS keeps the body a **pure lens until ~150–170 ms**, then fills over ~67–83 ms (§7). Change to `MenuSmooth(0.15, 0.23, t)`. (Independent of the clear-sheet vs tinted-body design decision — it is the *timing* that is wrong.)

**G5 — No tap-highlight flash before the open fade-out.**
`MenuTick` :1263 fades `_mFadeAnchor.Opacity = 1 − MenuSmooth(0.0, 0.05, t)` (value+chevron together, ~50 ms) — the *direction* and *togetherness* are correct for OPEN (§3a), but there is no **+35–37 % / 2-frame brighten** immediately before it. Optional polish: flash the anchor to ~1.36× brightness for ~2 frames at t0, then fade. Low priority vs G1–G4.

**G6 — Content lens is a fixed time-window decay, not shape-keyed; and it does not model the born-low rise or keep the edge band on the deforming ellipse.**
`MenuTick` :1248 uses `lens = 1 − MenuSmooth(0.07, 0.30, t)`, `labelScale: 1 + 0.18·lens` — magnitude (~1.18×) matches iOS ~1.2×, but it **peaks at t = 0 and decays**, whereas iOS **peaks at the fat-ellipse frame mid-open** and relaxes (§6). Key `labelScale` to the shape's ellipse-ness `e(t)` (`m = 1 + 0.20·e`), keep the Snell/refraction edge band active on the deforming outline through the whole morph (it already shares `MenuClip.Clip`), and optionally add the ~25 DIP born-low vertical offset converging to 0.

**G7 — Settled corner reads too round for the pull-down's squarer squircle.**
`ApplyMenuFrame` :1766 uses `n = 2 + 0.5·k` (settled n ≈ 2.5) shared by both menu types. iOS pull-down settles at **n ≈ 4–5** (§5). Give the pull-down path a higher settled exponent (e.g. `n = 2 + 2.5·k` on `_anchorFades`) while leaving the context menu at ~2.5. Keep the *birth* near-elliptical (n ≈ 2).

**G8 — Close shape is slightly slow; tint should lead harder.**
`SetupMenuAnim` :1216 sets `_mCloseW = 0.124 (dark) / 0.105 (light)`, `_mCloseH = 0.112 / 0.095`; :1217 `_mFadeT = 0.085`. iOS collapses the shape in **~90 ms** and drops the tint **first, in ~33–50 ms** (§5/§7). Minor: shorten the shape to ~0.09 s and the glass fade to ~0.04–0.05 s so the tint clearly leads. Low priority (current is close).

**G9 — Placement deltas (minor).** :655 `right = anchorRight + 12` and :659 `topIfDown = anchorCY − 13`. iOS sits the top-right corner **~17 pt right of the chevron** and **~12 pt above the trigger row top** (§2). At s ≈ 2.4 the current +12 DIP / −13 DIP are within ~1 frame of iOS; nudge only if a side-by-side shows the corner too tight to the chevron. Origin (top-right) and growth (left+down) are already correct (:1173).

**G10 — [Account switcher, out of the pull-down path but the user reported it: "帳號展開時會有兩個重複疊層"] The lit anchor clone duplicates the chip.**
The account switcher is a **context menu** (`ShowAccountSwitcher` → `ShowMenuFor(AccountChip, …)`, `fades == false`), so it takes the `SetupAnchorClone` path, **not** the pull-down. `SetupAnchorClone` (:736–769) snapshots the chip to a lifted (1.06×, ±5 px) bitmap clone and hides the live chip with **`anchor.Opacity = 0`** (:765). But `SetAccountView` (:3040–3065) runs the sign-in crossfade with **`FillMode = FillMode.Forward`** and `await fadeIn.RunAsync(AccountChip)`, which leaves an **Animation-priority `Opacity = 1` committed on `AccountChip`**. A later plain CLR `anchor.Opacity = 0` (LocalValue priority) **cannot override the still-active Forward-filled animation**, so the live chip stays visible *and* the lifted clone renders on top — the two overlapping copies, offset vertically by the 1.06 lift + the ±5 px nudge, exactly as the user sees. The `DUPLICATE-LAYER FIX` comment at :728–733 assumed the hide would take — it does not while the Forward animation is live.
Fix options (any one): in `SetupAnchorClone`, hide the live anchor by **clearing the animation first** (`AccountChip.SetValue(OpacityProperty, 0, BindingPriority.Animation)` or `Animation`-level set) rather than the LocalValue setter; **or** stop using `FillMode.Forward` on the chip crossfade and commit the final opacity as a plain set after `RunAsync`; **or** hide the live anchor with a property that has no competing animation. This is a **context-menu** bug and is outside the pull-down edits — flagged here because the user raised it; do not conflate it with the pull-down work.

---

## 12. Open questions the recordings cannot answer

- **No value-changing close and no hover capsule** in either clip, so pick-value vs tap-outside cannot be differenced — treated as the same close (§9). Any "select" flourish is a design decision, sourced from the drag-select spec.
- **No value-picker "current value" cue.** This is an iOS *action-style* picker held on one value; whether iOS pre-marks a current value in a launcher-style multi-value pull-down is not shown. (The launcher's own choice is the position-based capsule pre-placement in `IOS26_MENU_SELECTION_SPEC.md` §10.8, not a check mark.)
- **Return-spring "response" is t0-sensitive** (0.27 s pure-rise vs ~0.51–0.55 s dip-included). The *feel* (high-damping, ~1–3 % overshoot, spring ≫ ease) is solid; the exact response carries ±~0.05 s. Use ~0.47 s.
- **Text positional rebound magnitude (~15–18 px)** is measured in a low-alpha region — approximate. The light clip shows a monotonic opacity spring with no geometric width overshoot, so the safe cross-mode implementation is a small **scale** rebound; add the horizontal reform only if the dark-clip behaviour is wanted specifically.
- **Settled superellipse exponent (~4–5)** is edge-softness-sensitive; the ellipse↔squircle *morph* is certain, the absolute settled `n` is not.
- **Width response in light (~0.26–0.30 s vs dark ~0.37 s)** — light under-reads width over the white card; the dark value is the working number, but the two modes may genuinely differ slightly.
- **Body tint vs clear sheet** (§7) is a standing user design decision the videos cannot settle: iOS tints; the launcher currently keeps it clear. Only the fill *timing* is prescribed here regardless.
