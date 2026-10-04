package dev.s1mp1e.o.client.gui;

/**
 * Liquid-glass skin for the vanilla option sliders (FOV, render distance, volume, brightness, sensitivity…):
 * a glass capsule row like the glass buttons, the label lifted into the upper part, and a thin track along the
 * bottom with the same white-pill → glass-lens knob as the S1mp1e config slider. The 1.8.9 immediate-mode port of
 * mc1211's {@code client/gui/VanillaSliderSkin}: byte-identical constants and motion, only the {@code DrawContext}
 * plumbing dropped ({@link GlassWidgets} draws immediately here).
 *
 * <p>EntityRenderer-side. The vanilla value, stepping, keyboard control and narration are untouched; the skin reads
 * {@code value} and whether the slider is held, and draws. The one input change: while the skin is drawn, the mouse
 * maps onto the skin's knob travel ({@link #valueAt}) instead of vanilla's 8 px handle travel, so pressing the knob
 * doesn't nudge the value and dragging stays exactly under the pointer.
 *
 * <p>Motion ({@link Motion}): while held the knob follows the pointer, unquantised, so stepped options (render
 * distance) still drag smoothly, and it rubber-bands past the ends. Everything else — a click that jumps the value,
 * release onto a stepped value — glides on a critically damped spring driven by real frame time.
 */
public final class VanillaSliderSkin {
    private static final float KNOB_HW = 6.5f, KNOB_HH = 4.3f;   // rest pill 13x8.6 (iOS 111x72, 1.54 : 1)
    private static final float TRACK_HALF = 1.25f;
    private static final float INSET = 10f;                       // knob-centre travel inset from the row ends
    private static final float LIFT_ON = 0.81f;                   // glass-button hover lift

    private final Motion.Clock clock = new Motion.Clock();
    private final Motion.Spring lift = new Motion.Spring(Motion.MORPH_IN_S, 0f);
    private final Motion.Spring glide = new Motion.Spring(Motion.JUMP_S, 0f);
    private final Motion.Spring hover = new Motion.Spring(Motion.HOVER_S, 0f);
    private final Motion.Spring stretch = new Motion.Spring(Motion.STRETCH_S, 1f).tune(Motion.STRETCH_S, Motion.STRETCH_BOUNCE);
    private final float[] lens = new float[3];
    private float lastBase = Float.NaN, lastKnobX = Float.NaN, speed;
    private boolean wasHeld, lifted, fresh = true;
    private long pressNano, holdUntilNano, lastPaintNano;

    /** Too small to hold a label above a track: leave it vanilla. */
    public static boolean fits(int w, int h) { return w >= 40 && h >= 16; }

    /** Bottom of the label box; vanilla centres the label between the row top and this. */
    public static int labelBottom(int y, int h) { return y + h - 8; }

    /**
     * True when this slider hasn't been painted for a while (screen changed, scrolled out of view). A "held" flag from
     * before that gap is stale (its release went elsewhere), so the hook drops it instead of lifting the knob on the
     * next unrelated press. Paints are continuous while a slider is really being dragged.
     */
    public boolean paintGap() {
        return lastPaintNano == 0L || System.nanoTime() - lastPaintNano > 400_000_000L;
    }

    /** Slider value (unclamped) for a GUI-scaled pointer x: the knob-centre travel {@code [x + INSET, x + w - INSET]}. */
    public static double valueAt(double pointerX, int x, int w) {
        return (pointerX - (x + INSET)) / Math.max(1.0, w - 2.0 * INSET);
    }

    /**
     * @param value    vanilla slider value 0..1
     * @param held     the slider is being dragged with the left button down
     * @param pointerX GUI-scaled pointer x (sub-pixel)
     * @param hot      hovered or keyboard-focused
     * @param alpha    widget alpha × {@link ScreenOpenFade}
     */
    public void paint(int x, int y, int w, int h, double value, boolean held, double pointerX,
                      boolean hot, boolean active, float alpha) {
        float dt = clock.tick();
        long now = System.nanoTime();
        lastPaintNano = now;
        float kx0 = x + INSET, kx1 = x + w - INSET, range = Math.max(1f, kx1 - kx0);

        // ---- knob position ----
        float base;
        if (held) {
            double raw = valueAt(pointerX, x, w);                    // the same mapping the hook feeds vanilla
            float clamped = Motion.clamp01((float) raw);
            float overPx = (float) ((raw - clamped) * range);
            base = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), KNOB_HW * 2f) / range;
        } else {
            base = (float) Math.max(0.0, Math.min(1.0, value));
        }
        boolean pressed = held && !wasHeld, released = !held && wasHeld;
        wasHeld = held;
        if (pressed) {
            pressNano = now;
            lifted = true;
            lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
        }
        if (released) {
            holdUntilNano = (now - pressNano) / 1.0e9f < Motion.TAP_S ? now + (long) (Motion.TAP_HOLD_S * 1.0e9f) : now;
        }
        if (!Float.isNaN(lastBase) && base != lastBase && (!held || pressed)) {
            if (released) glide.tune(Motion.SETTLE_S, 0f);
            else if (pressed || (glide.x == 0f && glide.v == 0f)) glide.tune(Motion.JUMP_S, 0f);
            glide.x += lastBase - base;
            glide.retarget(0f);
            if (released) glide.settleMonotonic();
            else glide.capOvershoot();
        }
        lastBase = base;
        glide.update(dt);
        glide.settle(0.0002f);
        float knobX = kx0 + range * (base + glide.x);

        // ---- lens ----
        if (!held && lifted && now >= holdUntilNano) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);
        float L = Motion.clamp01(lift.x);
        if (!Float.isNaN(lastKnobX) && dt > 0f) {
            float inst = Math.abs(knobX - lastKnobX) / dt;
            speed += (inst - speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        }
        lastKnobX = knobX;
        stretch.retarget(Motion.stretchTarget(speed, KNOB_HW * 2f)).update(dt);
        Motion.lensShape(stretch.x, lens);

        if (fresh) { hover.snap(hot ? 1f : 0f); fresh = false; }
        hover.retarget(hot ? 1f : 0f).update(dt);
        hover.settle(0.002f);

        // ---- paint: row capsule, track, fill, knob ----
        GlassWidgets.capsule(x, y, x + w, y + h, 1f, LIFT_ON * Motion.clamp01(hover.x), alpha, active);
        float cy = y + h - 5.5f;
        float tx0 = kx0 - KNOB_HW, tx1 = kx1 + KNOB_HW;
        float ta = alpha * (active ? 1f : 0.5f);
        int fillRgb = active ? 0x0A84FF : 0x8E8E93;
        GlassWidgets.fillRound(tx0, cy - TRACK_HALF, tx1, cy + TRACK_HALF, (byteOf(ta * 0.30f) << 24) | 0xFFFFFF, TRACK_HALF);
        // rest: the round end tucks under the pill's left edge; held: it reaches the lens centre; none at the start
        float fillEnd = Math.min(tx1, knobX - (KNOB_HW - 2f * TRACK_HALF) * (1f - L));
        if (fillEnd > tx0 + 2f * TRACK_HALF)
            GlassWidgets.fillRound(tx0, cy - TRACK_HALF, Math.max(tx0 + 2f * TRACK_HALF, fillEnd), cy + TRACK_HALF,
                    (byteOf(ta) << 24) | fillRgb, TRACK_HALF);
        float restA = ta * (1f - L);
        if (restA > 0.004f)   // faint contact shadow so the white pill reads on the frosted row
            GlassWidgets.fillRound(knobX - KNOB_HW - 0.8f, cy - KNOB_HH - 0.4f, knobX + KNOB_HW + 0.8f, cy + KNOB_HH + 1.2f,
                    (byteOf(restA * 0.22f) << 24), KNOB_HH + 1f);
        GlassWidgets.knobLens(knobX, cy, KNOB_HW, KNOB_HH, L, lens[0], lens[1], lens[2], tx0, tx1, TRACK_HALF,
                Math.min(tx1, knobX), 0xFF000000 | fillRgb, 0x4DFFFFFF, ta);
    }

    // ---- 列形式（設定頁）：S1mp1e 功能選單的滑桿，裸的 —— 只有軌道＋藥丸，沒有膠囊、沒有標籤 ----

    /** 只給截圖工具用：代替實體左鍵，因為腳本化的拖曳沒辦法真的按住滑鼠。 */
    public static boolean devMouseDown;

    private static final float ROW_HW = 9f, ROW_HH = 6f, ROW_TRACK = 4f;   // = widget/SliderWidget（18x12 藥丸、4 px 軌道）
    private float rowX0, rowX1 = 1f, rowGrab;

    /**
     * 按在列滑桿上：按在藥丸上就保留抓取偏移（數值不會跳），按在裸軌道上藥丸就滑過去 ——
     * 和功能選單的滑桿一模一樣。要在把按下點經 {@link #rowValueAt} 映射之前呼叫。
     */
    public void rowPress(double pointerX) {
        float knob = lastKnobX;
        boolean onPill = !Float.isNaN(knob) && Math.abs(pointerX - knob) <= ROW_HW + 3f;
        rowGrab = onPill ? (float) (pointerX - Math.max(rowX0, Math.min(rowX1, knob))) : 0f;
    }

    /** 給上一幀畫出來的列軌道：指標 x 對應的滑桿值（未夾限），已算進抓取偏移。 */
    public double rowValueAt(double pointerX) {
        return (pointerX - rowGrab - rowX0) / Math.max(1f, rowX1 - rowX0);
    }

    /**
     * 設定頁列上的功能選單滑桿：藥丸中心會走完整條軌道 {@code tx0..tx1}。按住時 1:1 跟著指標、
     * 不量化（有級距的選項照樣拖得順），超出兩端會橡皮筋回彈，並變成玻璃透鏡、隨速度拉長；
     * 放開／點在軌道上時用的是和 {@link #paint} 一樣的彈簧。移植自 mc1144/mc1211 的
     * {@code VanillaSliderSkin.paintRow}。
     */
    public void paintRow(float tx0, float tx1, float cy, double value, boolean held, double pointerX,
                         boolean active, float alpha) {
        float dt = clock.tick();
        long now = System.nanoTime();
        lastPaintNano = now;
        rowX0 = tx0;
        rowX1 = tx1;
        float range = Math.max(1f, tx1 - tx0);

        float base;
        if (held) {
            double raw = rowValueAt(pointerX);
            float clamped = Motion.clamp01((float) raw);
            float overPx = (float) ((raw - clamped) * range);
            base = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), ROW_HW * 2f) / range;
        } else {
            base = (float) Math.max(0.0, Math.min(1.0, value));
        }
        boolean pressed = held && !wasHeld, released = !held && wasHeld;
        wasHeld = held;
        if (pressed) {
            pressNano = now;
            lifted = true;
            lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
        }
        if (released) {
            holdUntilNano = (now - pressNano) / 1.0e9f < Motion.TAP_S ? now + (long) (Motion.TAP_HOLD_S * 1.0e9f) : now;
        }
        if (!Float.isNaN(lastBase) && base != lastBase && (!held || pressed)) {
            if (released) glide.tune(Motion.SETTLE_S, 0f);
            else if (pressed || (glide.x == 0f && glide.v == 0f)) glide.tune(Motion.JUMP_S, 0f);
            glide.x += lastBase - base;
            glide.retarget(0f);
            if (released) glide.settleMonotonic();
            else glide.capOvershoot();
        }
        lastBase = base;
        glide.update(dt);
        glide.settle(0.0002f);
        float knobX = tx0 + range * (base + glide.x);

        if (!held && lifted && now >= holdUntilNano) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);
        float L = Motion.clamp01(lift.x);
        if (!Float.isNaN(lastKnobX) && dt > 0f) {
            float inst = Math.abs(knobX - lastKnobX) / dt;
            speed += (inst - speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        }
        lastKnobX = knobX;
        stretch.retarget(Motion.stretchTarget(speed, ROW_HW * 2f)).update(dt);
        Motion.lensShape(stretch.x, lens);

        float ta = alpha * (active ? 1f : 0.5f);
        int fillRgb = active ? 0x0A84FF : 0x8E8E93;
        float ty0 = cy - ROW_TRACK / 2f, ty1 = cy + ROW_TRACK / 2f;
        GlassWidgets.fillRound(tx0, ty0, tx1, ty1, (byteOf(ta * 0.30f) << 24) | 0xFFFFFF, ROW_TRACK / 2f);
        float fillEnd = Math.min(tx1, knobX - (ROW_HW - ROW_TRACK) * (1f - L));
        if (fillEnd > tx0 + ROW_TRACK)
            GlassWidgets.fillRound(tx0, ty0, Math.max(tx0 + ROW_TRACK, fillEnd), ty1, (byteOf(ta) << 24) | fillRgb, ROW_TRACK / 2f);
        GlassWidgets.knobLens(knobX, cy, ROW_HW, ROW_HH, L, lens[0], lens[1], lens[2], tx0, tx1, ROW_TRACK / 2f,
                Math.min(tx1, knobX), 0xFF000000 | fillRgb, 0x4DFFFFFF, ta);
    }

    private static int byteOf(float a) {
        int v = Math.round(a * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
