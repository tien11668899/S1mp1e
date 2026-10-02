package dev.s1mp1e.glass.ui;


/**
 * Duck interface implemented by {@code SliderGlassMixin} on {@code SliderWidget}, so
 * {@code ButtonGlassMixin} (which injects {@code ClickableWidget.renderButton}) can ask a
 * vanilla option slider to paint its liquid-glass skin.
 *
 * <p>On 1.14.4 {@code SliderWidget} does NOT declare {@code renderButton} — it only overrides
 * {@code renderBackground} (the white knob). An {@code @Inject(method = "renderButton")} on
 * {@code @Mixin(SliderWidget)} would fail with {@code defaultRequire 1}. Instead the slider draw
 * is driven from {@code ButtonGlassMixin}'s {@code ClickableWidget.renderButton} HEAD inject,
 * which casts the widget to this interface and calls {@link #s1mp1e$paintGlass}.
 */
public interface GlassSliderPainter {

    /**
     * Paint the liquid-glass slider skin for this frame.
     *
     * @return {@code true} if the skin painted (caller cancels the vanilla draw); {@code false}
     *         when the glass pipeline is unusable or the row is too small — caller lets vanilla draw.
     */
    boolean s1mp1e$paintGlass(int mouseX, int mouseY, float delta);
}
