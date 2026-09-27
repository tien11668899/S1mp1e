package dev.s1mp1e.glass.ui;

/**
 * Duck interface implemented by {@code SliderGlassMixin} on {@code SliderWidget}, so
 * {@code ButtonGlassMixin} (which injects {@code AbstractButtonWidget.renderButton}) can ask a
 * vanilla option slider to paint its liquid-glass skin.
 *
 * <p><b>Why a duck and not an inject.</b> On 1.14.4 {@code SliderWidget} does NOT declare
 * {@code renderButton} — it only overrides {@code renderBg(MinecraftClient,int,int)} (the white
 * 8&nbsp;px knob), which the inherited {@code renderButton} calls. An
 * {@code @Inject(method = "renderButton")} on {@code @Mixin(SliderWidget.class)} would therefore
 * have nothing to target and fail under {@code defaultRequire 1}. Worse, {@code ButtonGlassMixin}
 * already HEAD-cancels {@code AbstractButtonWidget.renderButton} for every widget, which swallows
 * {@code renderBg} too — that is why option sliders used to render as a knob-less capsule. So the
 * slider draw is driven from that same hook: it casts the widget to this interface and calls
 * {@link #s1mp1e$paintSkin}, cancelling vanilla only when the skin actually painted.
 *
 * <p>Lives outside {@code dev.s1mp1e.glass.mixin} on purpose: that package is the mixin
 * configuration's {@code package}, and every class in it is treated as a mixin.
 */
public interface SliderGlassHost {

    /**
     * Paint the liquid-glass slider skin for this frame.
     *
     * @return {@code true} if the skin painted (the caller cancels the vanilla draw); {@code false}
     *         when the glass pipeline is unusable, the row is too small for a label above a track,
     *         or the paint threw — the caller then lets vanilla draw its own texture + knob.
     */
    boolean s1mp1e$paintSkin(int mouseX, int mouseY);
}
