package dev.s1mp1e.glass.ui;

/**
 * Duck interface implemented by {@code OptionSliderGlassMixin} (on {@code OptionSliderWidget}) and
 * {@code SoundSliderGlassMixin} (on the package-private {@code SoundsScreen$SoundButtonWidget}), so
 * {@code ButtonGlassMixin} (which injects {@code ButtonWidget.method_891}, the 1.13.2 button render) can ask a
 * vanilla option/volume slider to paint its liquid-glass skin. The 1.13.2 copy of mc1144's interface.
 *
 * <p><b>Why a duck and not an inject.</b> On 1.13.2 neither slider declares {@code method_891}; each only
 * overrides {@code renderBg} (legacy-yarn {@code mouseDragged(MinecraftClient,int,int)}), which the inherited
 * {@code method_891} calls — and which also applies the drag value and draws the 8&nbsp;px knob. An
 * {@code @Inject(method = "method_891")} on the slider class would therefore have nothing to target and fail
 * under {@code defaultRequire 1}. Worse, {@code ButtonGlassMixin} already HEAD-cancels {@code method_891} for
 * every button, which swallowed {@code renderBg} too — that is why the 1.13.2 sliders used to render as a
 * knob-less capsule that could not be dragged. So the slider draw is driven from that same hook: it casts the
 * widget to this interface and calls {@link #s1mp1e$paintSkin}, cancelling vanilla only when the skin actually
 * painted.
 *
 * <p>Lives outside {@code dev.s1mp1e.glass.mixin} on purpose: that package is the mixin configuration's
 * {@code package}, and every class in it is treated as a mixin.
 */
public interface SliderGlassHost {

    /**
     * Paint the liquid-glass slider skin for this frame (the vanilla drag update still runs inside).
     *
     * @return {@code true} if the skin painted (the caller cancels the vanilla draw); {@code false}
     *         when the glass pipeline is unusable, the row is too small for a label above a track,
     *         or the paint threw — the caller then lets vanilla draw its own texture + knob.
     */
    boolean s1mp1e$paintSkin(int mouseX, int mouseY);
}
