# Delta port: settings-row slider + value roll (2026-10-02)

The owner tested the settings pages and asked for two corrections. They are done and verified in `versions/mc1211`
(1.21.1) and `versions/mc262`; every already-ported line (1.20.1, 1.19.2, 1.18.2, 1.17.1, …) needs the same change.
This is a small, well-defined delta — do not re-port anything else. The hard rules of
`versions/PORT_SPEC_FROM_1211.md` apply in full (work only in your version directory, no deploy, no commit, never the
user's game or launcher, one dev client at a time, verify by compile + screenshots you look at).

## What changes

1. **The slider on a settings row is the config-menu slider** (`client/gui/widget/SliderWidget` of the S1mp1e
   feature menu), no longer a small slider that slides out on hover:
   - always visible; layout `label | track | value` — the value right-aligned at the row's right edge, the track ends
     14 px left of a value column that is the same for every slider on the page (so the tracks line up);
   - the pill is 18×12 on a 4 px track, its centre travels the whole track;
   - while held it rides the pointer 1:1 and unquantised (a stepped option still drags smoothly), keeps the grab
     offset when pressed on the pill, glides to the pointer when pressed on bare track, rubber-bands past the ends,
     turns into the glass lens and stretches with speed; on release it settles on the committed value with the
     0.5 s spring. All of that is `VanillaSliderSkin.paintRow / rowPress / rowValueAt` in mc1211 — the same springs
     the skin already uses for vanilla sliders.
2. **A cycle row's value rolls when it changes** (the old value leaves upward, the new one rises in — the roll the
   glass cycle buttons already have): `TypingAnim.extractLabel`, right-aligned, see `rollValue(...)`.
3. Small: the blue focus ring on a row is drawn only for keyboard focus (not while the row is hovered / dragged).

## Where (mc1211 — diff these against your line's versions)

- `client/gui/VanillaSliderSkin.java` — the whole "row form" block (`devMouseDown`, `ROW_*`, `rowPress`,
  `rowValueAt`, `paintRow`).
- `glass/mixin/SliderGlassMixin.java` — `s1mp1e$rowMode`, `s1mp1e$paintRow(...)`, `rowPress` in the click hook,
  the `rowMode` branch in the `setValueFromMouse` hook, `rowMode = false` when the normal skin paints, the
  `devMouseDown` escape in the button-state checks. (In lines whose slider class has no `setValueFromMouse`, hook
  the equivalent place where the pointer is mapped to the value.)
- `client/gui/SettingsShell.java` — interface `SliderAccess.s1mp1e$paintRow`, `Row.roll`, `State.valueCol /
  valueShown` and the pre-pass in `drawRows` that computes them, the `K_SLIDER` case, `rollValue(...)` used by the
  `K_CYCLE` case, the focus-ring condition. `drawSlider`, `Row.reveal`, `Row.knob` are gone.
- `glass/compat/SodiumGlass.java` — `SliderRow.s1mp1e$bounds()`, `SliderInfo.s1mp1e$interval()`, `sliders` /
  `valueCols` / `rolls` maps, `pageCol` computed once per frame for the page, the slider branch of `drawRow`
  (Sodium's own `sliderBounds` rectangle is moved onto our track every frame so Sodium's mouse mapping matches the
  drawn track; the skin is painted with `held` from Sodium's `sliderHeld`), `widest(...)`, `rollValue(...)`, the
  focus-ring condition. `drawSlider` and `reveal` are gone.
- `glass/compat/sodium/SodiumSliderMixin.java` (`sliderBounds` shadow + `s1mp1e$bounds()`),
  `SodiumSliderControlMixin.java` (`interval` shadow + `s1mp1e$interval()`). Check with `javap` on your Sodium jar
  that the element class and field names are the same (older Sodium may name them differently).
- `client/DevShotVerify.java` — the `drag(...)` scene and the `st-drag`, `st-drag-back`, `st-roll`, `sd-drag`,
  `sd-roll` scenes in `buildSettings()` / `buildSodium()`.

## Verification to deliver

- `compileJava`, then run the `settings` and `sodium` modes (Sodium jar copied into `run/mods`, removed afterwards).
  Look at: a sound/controls page (tracks aligned, values right), the `st-drag` burst (pill becomes a lens, follows
  the pointer every frame, value text changes, release settles), the `st-roll` burst (value rolls), `sd-drag`,
  `sd-roll`, `sd-open`. Build a contact sheet of each burst if that helps.
- Run the line's other modes once (regression) — vanilla sliders outside the settings pages must look and drag as
  before.
- `gradlew build`; report the jar path, size, time.

Final message: what you changed (files), what you looked at (screenshot names), anything not verified or that looks
wrong, jar path/size/time. Leave no dev client or Gradle JVM of yours running; `run/mods` clean.
