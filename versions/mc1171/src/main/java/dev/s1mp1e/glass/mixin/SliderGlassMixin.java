package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassSliderHook;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla option sliders (FOV, render distance, volume, brightness, sensitivity…) → liquid glass, the 1.17.1
 * counterpart of the 26.2 / 1.20.1 client's slider skin ({@link VanillaSliderSkin}): glass row like the glass
 * buttons, label lifted above a thin track, white-pill → glass-lens knob.
 *
 * <p>1.17.1 (as 1.19.2) delta vs the 1.20.1 SliderGlassMixin: there {@code renderButton} is abstract on
 * {@code ClickableWidget} and overridden on {@code SliderWidget}, so it injected {@code renderButton} directly.
 * On 1.17.1 {@code renderButton} is concrete on {@code ClickableWidget} and {@code SliderWidget} does NOT override
 * it (it only overrides {@code renderBackground}, the knob). Injecting the inherited method from a subclass mixin is
 * fragile, and the project keeps exactly ONE injector on {@code ClickableWidget.renderButton} (in
 * {@code ButtonGlassMixin}). So this mixin exposes the paint as the {@link GlassSliderHook} duck instead, and
 * {@code ButtonGlassMixin} dispatches to it before its own capsule path.
 *
 * <p>Value, stepping, keyboard control and narration stay vanilla. While the skin is drawn,
 * {@code setValueFromMouse} maps the pointer onto the skin's knob travel instead of vanilla's 8 px handle travel,
 * so pressing the knob doesn't nudge the value. {@code onClick} / {@code onRelease} are only observed, to know when
 * the slider is held.
 *
 * <p>1.17.1 deltas vs the 1.19.2 mixin: the never-run constructor passes {@code LiteralText.EMPTY}
 * ({@code Text.empty()} does not exist yet), and the one-shot error log uses log4j's {@link LogManager}
 * ({@code com.mojang.logging.LogUtils} does not exist on 1.17.1).
 *
 * <p>Extends {@link ClickableWidget} (the target's superclass) so the inherited protected fields
 * ({@code alpha}, {@code hovered}, {@code width}, {@code height}) resolve; the constructor never runs.
 */
@Mixin(SliderWidget.class)
public abstract class SliderGlassMixin extends ClickableWidget
        implements GlassSliderHook, dev.s1mp1e.client.gui.SettingsShell.SliderAccess {

    @Shadow protected double value;
    @Shadow private void setValue(double value) {}

    @Unique private VanillaSliderSkin s1mp1e$skin;
    @Unique private boolean s1mp1e$held;
    @Unique private boolean s1mp1e$skinned;
    @Unique private static boolean s1mp1e$errorLogged;

    private SliderGlassMixin() { super(0, 0, 0, 0, LiteralText.EMPTY); }

    @Override
    public double s1mp1e$value() {
        return this.value;
    }

    @Override
    public boolean s1mp1e$held() {
        if (!this.s1mp1e$held) return false;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) this.s1mp1e$held = false;
        return this.s1mp1e$held;
    }

    /** A settings-page row draws this slider: the widget is the track, so the pointer maps the vanilla way. */
    @Override
    public void s1mp1e$rowDrawn() {
        this.s1mp1e$skinned = false;
    }

    @Inject(method = "onClick", at = @At("HEAD"))
    private void s1mp1e$press(double mouseX, double mouseY, CallbackInfo ci) {
        this.s1mp1e$held = this.active;
    }

    @Inject(method = "onRelease", at = @At("HEAD"))
    private void s1mp1e$release(double mouseX, double mouseY, CallbackInfo ci) {
        this.s1mp1e$held = false;
    }

    /** Mouse → value on the skin's knob travel (vanilla maps onto its own 8 px handle travel). */
    @Inject(method = "setValueFromMouse", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$mapToSkin(double mouseX, CallbackInfo ci) {
        if (this.s1mp1e$skinned) {
            this.setValue(VanillaSliderSkin.valueAt(mouseX, this.x, this.width));
            ci.cancel();
        }
    }

    /**
     * Draw the glass slider. Returns true only when the skin was actually drawn (so the caller cancels the vanilla
     * knob); on any failure it logs once and returns false so the regular button path draws.
     */
    @Override
    public boolean s1mp1e$renderGlass(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.s1mp1e$skinned = false;
        int x = this.x, y = this.y, w = this.width, h = this.height;
        if (!VanillaSliderSkin.fits(w, h)) return false;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            long window = mc.getWindow().getHandle();
            boolean buttonDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (this.s1mp1e$skin == null) this.s1mp1e$skin = new VanillaSliderSkin();
            // released where no onRelease reached us, or a leftover from before this slider was last painted
            if (!buttonDown || this.s1mp1e$skin.paintGap()) this.s1mp1e$held = false;
            double pointerX = mc.mouse.getX() * mc.getWindow().getScaledWidth() / Math.max(1, mc.getWindow().getWidth());
            float alpha = this.alpha * ScreenOpenFade.value(mc.currentScreen);
            this.s1mp1e$skin.paint(matrices, x, y, w, h, this.value, this.s1mp1e$held, pointerX,
                    this.hovered || this.isFocused(), this.active, alpha);

            int color = (this.active ? 0xFFFFFF : 0xA0A0A0) | (Math.round(Math.min(1f, Math.max(0f, this.alpha)) * 255f) << 24);
            // 1.17.1 has no ClickableWidget.drawScrollableText: centre the label in the same [y, labelBottom] box.
            DrawableHelper.drawCenteredText(matrices, mc.textRenderer, this.getMessage(),
                    x + w / 2, (y + VanillaSliderSkin.labelBottom(y, h) - 9) / 2 + 1, color);
            this.s1mp1e$skinned = true;
            return true;
        } catch (Throwable t) {
            if (!s1mp1e$errorLogged) {
                s1mp1e$errorLogged = true;
                LogManager.getLogger("S1mp1e").error("[S1mp1e] glass slider failed, drawing vanilla", t);
            }
            return false;
        }
    }
}
