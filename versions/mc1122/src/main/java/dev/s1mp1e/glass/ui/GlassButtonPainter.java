package dev.s1mp1e.glass.ui;

import java.util.IdentityHashMap;

import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiSlider;

/**
 * The 1.8.9 counterpart of LiquidGlass26's {@code ButtonGlassMixin} +
 * {@code SliderGlassMixin}, expressed as immediate-mode paint calls instead of
 * sprite redirects (1.8.9 Forge has no button-render event, so the companion
 * {@code GlassButtonHandler} drives this from a screen-draw event).
 *
 * <h3>Buttons</h3>
 * Every standard button becomes a translucent capsule:
 * {@code GlassRenderer.button(x, y, x+w, y+h, corner, lift, opacity, enabled)}
 * with <b>corner = 1.0</b> (full capsule), <b>lift = 0.81</b> when
 * hovered/focused else 0 (26.2 encoded G=0x30 hovered vs 0xFF resting, i.e.
 * {@code lift = 1 - 0x30/255 = 0.81}), <b>opacity = the widget's alpha</b>, and
 * <b>enabled</b> false letting the shader dim to 0.4. The label text stays
 * vanilla and is left untouched.
 *
 * <h3>Sliders</h3>
 * Per the iOS Brightness reference 26.2 measured: a 4&nbsp;px rounded track
 * inset 2&nbsp;px each side (dim unfilled, full-opacity/full-lift filled up to
 * the sprung thumb centre) plus a glass thumb capsule stretched across two
 * critically-damped springs (leading {@link Spring#OMEGA_SNAP} 55, trailing
 * {@link Spring#OMEGA_MED} 30) so a fast drag stretches it. The springs are
 * <b>per slider instance</b> — 26.2 learned that static springs cross-wire when
 * several sliders coexist on the options screen — kept in an
 * {@link IdentityHashMap} that is evicted when it grows past 64 entries.
 */
public final class GlassButtonPainter {

    private GlassButtonPainter() {}

    /** Full capsule. */
    private static final float CORNER_CAPSULE = 1.0f;
    /** Lifted state: 26.2's G=0x30 hovered → 1 - 0x30/255 ≈ 0.81. */
    private static final float LIFT_ON  = 0.81f;
    /** Resting state: 26.2's G=0xFF → lift 0. */
    private static final float LIFT_OFF = 0f;

    /** 26.2's slider track: 4 px tall, inset 2 px, unfilled at half opacity. */
    private static final float TRACK_H       = 4f;
    private static final float TRACK_INSET   = 2f;
    private static final float TRACK_UNFILL_A = 0.5f;

    /** Thumb capsule half-overhang beyond the spring endpoints. */
    private static final float THUMB_OVERHANG = 8f;
    /** Thumb vertical margin inside the widget. */
    private static final float THUMB_MARGIN   = 2f;
    /** value → centre uses this end margin: centre = x + 4 + value*(w-8). */
    private static final float VALUE_MARGIN   = 4f;

    // ---- per-slider spring rig (evict when it exceeds 64) ------------------
    private static final int MAX_SLIDERS = 64;
    private static final IdentityHashMap<Object, Spring[]> SLIDERS =
            new IdentityHashMap<Object, Spring[]>();
    private static final IdentityHashMap<Object, long[]> STAMPS =
            new IdentityHashMap<Object, long[]>();

    // -----------------------------------------------------------------------
    // Buttons
    // -----------------------------------------------------------------------

    /**
     * Paint one standard button as a glass capsule. Sliders are delegated to
     * {@link #paintSlider} so the entry point is safe whatever the caller
     * hands it.
     */
    public static void paint(GuiButton b, boolean hovered) {
        if (b == null || !b.visible) return;
        if (b instanceof GuiSlider) {
            paintSlider((GuiSlider) b);
            return;
        }
        float x0 = b.x;
        float y0 = b.y;
        float x1 = x0 + b.width;
        float y1 = y0 + b.height;
        float lift = hovered ? LIFT_ON : LIFT_OFF;
        GlassRenderer.button(x0, y0, x1, y1, CORNER_CAPSULE, lift, widgetAlpha(b), b.enabled);
    }

    // -----------------------------------------------------------------------
    // Sliders
    // -----------------------------------------------------------------------

    /** Paint one slider: dim/filled track + two-spring stretchy thumb. */
    public static void paintSlider(GuiSlider s) {
        if (s == null || !s.visible) return;

        float x = s.x;
        float y = s.y;
        float w = s.width;
        float h = s.height;
        float alpha = widgetAlpha(s);

        // advance this instance's own two springs
        Spring[] sp = springsFor(s);
        Spring lead = sp[0], trail = sp[1];
        long[] stamp = STAMPS.get(s);

        float value  = clamp01(sliderPosition(s));
        float target = x + VALUE_MARGIN + value * (w - 2f * VALUE_MARGIN);

        long now = System.nanoTime();
        float dt = (now - stamp[0]) / 1_000_000_000f;
        stamp[0] = now;

        lead.setTarget(target);
        trail.setTarget(target);
        lead.advance(dt);
        trail.advance(dt);

        float lo = Math.min(lead.value(), trail.value());
        float hi = Math.max(lead.value(), trail.value());
        float centre = (lo + hi) * 0.5f;

        // ---- track: 4 px rounded bar, inset 2 px, vertically centred ------
        float tx0 = x + TRACK_INSET;
        float tx1 = x + w - TRACK_INSET;
        float ty  = y + h / 2f - TRACK_H / 2f;
        float tyb = ty + TRACK_H;
        float fill = clamp(centre, tx0, tx1);

        // filled part: left edge → sprung thumb centre, full opacity + full lift
        if (fill > tx0 + 0.5f) {
            GlassRenderer.button(tx0, ty, fill, tyb, CORNER_CAPSULE, LIFT_ON, alpha, true);
        }
        // unfilled part: remainder to the right edge, dim
        if (tx1 > fill + 0.5f) {
            GlassRenderer.button(fill, ty, tx1, tyb,
                                 CORNER_CAPSULE, LIFT_OFF, alpha * TRACK_UNFILL_A, true);
        }

        // ---- thumb: capsule spanning both springs so a fast drag stretches -
        float thx0 = lo - THUMB_OVERHANG;
        float thx1 = hi + THUMB_OVERHANG;
        float thy0 = y + THUMB_MARGIN;
        float thy1 = y + h - THUMB_MARGIN;
        GlassRenderer.button(thx0, thy0, thx1, thy1, CORNER_CAPSULE, LIFT_ON, alpha, true);
    }

    // -----------------------------------------------------------------------
    // Per-instance spring bookkeeping
    // -----------------------------------------------------------------------

    private static Spring[] springsFor(GuiSlider s) {
        Spring[] sp = SLIDERS.get(s);
        if (sp == null) {
            // evict wholesale once the rig grows past the cap
            if (SLIDERS.size() >= MAX_SLIDERS) {
                SLIDERS.clear();
                STAMPS.clear();
            }
            float init = s.x + VALUE_MARGIN
                       + clamp01(sliderPosition(s)) * (s.width - 2f * VALUE_MARGIN);
            sp = new Spring[] {
                new Spring(init, Spring.OMEGA_SNAP, Spring.DAMPING), // leading
                new Spring(init, Spring.OMEGA_MED,  Spring.DAMPING)  // trailing
            };
            SLIDERS.put(s, sp);
            STAMPS.put(s, new long[] { System.nanoTime() });
        }
        return sp;
    }

    /**
     * 1.8.9's {@code GuiButton}/{@code GuiSlider} carry no per-widget alpha, so
     * 26.2's "opacity = the widget's alpha" resolves to a solid 1.0 here.
     */
    private static float widgetAlpha(GuiButton b) {
        return 1.0f;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------
    // 1.8.9's GuiSlider keeps its 0..1 position in a PRIVATE field named
    // `sliderPosition` (not `sliderValue`, which is the later-version name),
    // so read it reflectively once and cache the Field.
    // ------------------------------------------------------------------
    private static java.lang.reflect.Field SLIDER_POS;
    private static boolean sliderPosResolved;

    private static float sliderPosition(net.minecraft.client.gui.GuiSlider s) {
        if (!sliderPosResolved) {
            sliderPosResolved = true;
            String[] names = { "sliderPosition", "field_146134_p", "sliderValue" };
            for (int i = 0; i < names.length && SLIDER_POS == null; i++) {
                try {
                    java.lang.reflect.Field f =
                        net.minecraft.client.gui.GuiSlider.class.getDeclaredField(names[i]);
                    f.setAccessible(true);
                    SLIDER_POS = f;
                } catch (NoSuchFieldException ignored) { }
            }
        }
        if (SLIDER_POS == null) return 0f;
        try {
            return SLIDER_POS.getFloat(s);
        } catch (IllegalAccessException e) {
            return 0f;
        }
    }
}
