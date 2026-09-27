package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.SliderGlassHost;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla option sliders (FOV, render distance, volume, brightness, sensitivity…) → liquid glass:
 * a glass capsule row like the glass buttons, the label lifted above a thin track, and the
 * white-pill → glass-lens knob. The 1.14.4 counterpart of 1.16.5's {@code SliderGlassMixin} and
 * of the 1.8.9 {@code ButtonHook} slider branch.
 *
 * <p><b>1.14.4 delta — no {@code renderButton} to inject.</b> {@code SliderWidget} does not declare
 * {@code renderButton}; it only overrides {@code renderBg(MinecraftClient,int,int)}, which the
 * inherited {@code renderButton} calls. So an {@code @Inject(method = "renderButton")} here would
 * have no target and fail {@code defaultRequire 1}, and {@code ButtonGlassMixin}'s HEAD cancel of
 * {@code AbstractButtonWidget.renderButton} swallows {@code renderBg} along with it — which is
 * exactly why option sliders previously drew as a knob-less capsule. Instead this mixin implements
 * the {@link SliderGlassHost} duck and {@code ButtonGlassMixin} calls {@link #s1mp1e$paintSkin}
 * from that one hook, cancelling vanilla only when the skin actually painted.
 *
 * <p>Value, stepping, keyboard control and narration stay vanilla. The single input change: while
 * the skin is drawn, {@code setValueFromMouse} maps the pointer onto the skin's knob travel
 * ({@link VanillaSliderSkin#valueAt}) instead of vanilla's 8&nbsp;px handle travel, so pressing the
 * knob doesn't nudge the value and the knob stays exactly under the pointer. {@code onClick} /
 * {@code onRelease} are observed only, to know when the slider is held. With no glass programs
 * nothing is painted and nothing is remapped, so vanilla draws and behaves as shipped.
 *
 * <p>Extends {@link AbstractButtonWidget} (the target's superclass) so the inherited
 * {@code x}/{@code y}/{@code width}/{@code height}/{@code alpha}/{@code active}/{@code visible}/
 * {@code isHovered()}/{@code isFocused()}/{@code getMessage()} members resolve at compile time; the
 * constructor is never executed.
 */
@Mixin(SliderWidget.class)
public abstract class SliderGlassMixin extends AbstractButtonWidget implements SliderGlassHost {

    @Shadow protected double value;
    @Shadow private void setValue(double value) {}

    @Unique private VanillaSliderSkin s1mp1e$skin;
    @Unique private boolean s1mp1e$held;
    @Unique private boolean s1mp1e$skinned;
    @Unique private static boolean s1mp1e$errorLogged;

    /** Never runs; present only so this mixin can extend the target's superclass. */
    private SliderGlassMixin() { super(0, 0, ""); }

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

    @Override
    public boolean s1mp1e$paintSkin(int mouseX, int mouseY) {
        this.s1mp1e$skinned = false;
        int x = this.x, y = this.y, w = this.width, h = this.height;
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable() || !VanillaSliderSkin.fits(w, h)) {
            return false;
        }
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (this.s1mp1e$skin == null) this.s1mp1e$skin = new VanillaSliderSkin();

            // "Held" only counts while the button is genuinely down AND this slider has been painted
            // continuously: a release that never reached onRelease (screen swap, another widget
            // grabbing focus), or a stale flag from before this slider was last on screen, would
            // otherwise lift the knob on an unrelated press.
            boolean buttonDown = GLFW.glfwGetMouseButton(mc.window.getHandle(),
                    GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (!buttonDown || this.s1mp1e$skin.paintGap()) this.s1mp1e$held = false;

            // GUI-scaled sub-pixel pointer x (mc.mouse is in physical pixels).
            double pointerX = mc.mouse.getX() * mc.window.getScaledWidth()
                    / Math.max(1, mc.window.getWidth());
            float alpha = this.alpha * ScreenOpenFade.value(mc.currentScreen);

            this.s1mp1e$skin.paint(x, y, w, h, this.value, this.s1mp1e$held, pointerX,
                    this.isHovered() || this.isFocused(), this.active, alpha);

            // Label lifted into the row's upper box [y, y + h - 8], centred in [x + 2, x + w - 2].
            int a = Math.round(alpha * 255f);
            if (a > 255) a = 255;
            if (a >= 8) {
                String label = this.getMessage();
                if (label != null) {
                    int rgb = this.active ? 0xFFFFFF : 0xA0A0A0;
                    float lx = x + 2f, rx = x + w - 2f;
                    float tx = (lx + rx) / 2f - mc.textRenderer.getStringWidth(label) / 2f;
                    float ty = y + ((h - 8) - 9) / 2f + 1f;
                    mc.textRenderer.drawWithShadow(label, tx, ty, (a << 24) | rgb);
                }
            }
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
