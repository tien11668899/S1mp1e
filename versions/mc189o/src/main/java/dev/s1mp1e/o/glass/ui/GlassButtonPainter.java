package dev.s1mp1e.o.glass.ui;

import java.util.WeakHashMap;

import dev.s1mp1e.o.glass.anim.Fade;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * The 1.8.9 counterpart of mc1211's {@code ButtonGlassMixin} + mc262's
 * {@code ButtonGlassMixin}: every standard button becomes a translucent
 * liquid-glass capsule. 1.8.9 Forge has no button-render event, so the ASM
 * {@link dev.s1mp1e.o.glass.asm.ButtonHook} drives this from the head of
 * {@code ButtonWidget.render}.
 *
 * <h3>Knobs (measured off 26.2, byte-identical to mc1211)</h3>
 * {@code GlassRenderer.button(x, y, x+w, y+h, corner, lift, opacity, enabled)}
 * with <b>corner = 1.0</b> (full capsule), <b>lift = 0.81 × hoverFade</b> (26.2
 * encoded G=0x30 hovered vs 0xFF resting, i.e. {@code lift = 1 - 0x30/255 =
 * 0.81}), <b>opacity</b> = the screen-open fade the caller passes in, and
 * <b>enabled = false</b> letting the shader dim to 0.4.
 *
 * <h3>Hover-lift ease (100 ms per-widget {@link Fade})</h3>
 * The old Forge line snapped the lift 0&rarr;0.81 on hover. Here a per-widget
 * {@link Fade} eases it over {@link #HOVER_FADE_MS} (100 ms, the same duration
 * the container hover pill uses), so hover-in and hover-out ramp instead of
 * popping. Endpoints unchanged (0 resting, 0.81 hovered). The rig is keyed per
 * widget in a {@link WeakHashMap} so dead widgets evict themselves and
 * coexisting buttons never cross-wire — the exact mc1211 design.
 */
public final class GlassButtonPainter {

    private GlassButtonPainter() {}

    /** Full capsule. */
    private static final float CORNER_CAPSULE = 1.0f;
    /** Lifted state: 26.2's G=0x30 hovered → 1 - 0x30/255 ≈ 0.81. */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<ButtonWidget, Fade> HOVER =
            new WeakHashMap<ButtonWidget, Fade>();

    /**
     * Paint one standard button as a glass capsule.
     *
     * @param b       the button
     * @param hovered hovered/focused this frame
     * @param opacity the screen-open fade (0..1), shared with the option sliders
     */
    public static void paint(ButtonWidget b, boolean hovered, float opacity) {
        if (b == null || !b.visible) return;

        // Per-widget hover-lift ease; a fresh entry starts settled (no flash).
        Fade fade = HOVER.get(b);
        if (fade == null) {
            fade = new Fade(hovered ? 1f : 0f, HOVER_FADE_MS);
            HOVER.put(b, fade);
        }
        fade.to(hovered ? 1f : 0f);
        float lift = LIFT_ON * fade.value();

        float x0 = b.x;
        float y0 = b.y;
        GlassRenderer.button(x0, y0, x0 + b.width, y0 + b.height,
                             CORNER_CAPSULE, lift, opacity, b.active);
    }
}
