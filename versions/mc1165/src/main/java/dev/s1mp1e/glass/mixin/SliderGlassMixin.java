package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassSliderPainter;
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
 * Vanilla option sliders (FOV, render distance, volume, brightness, sensitivity…) → liquid glass, the 1.16.5
 * counterpart of the 26.2 client's slider skin ({@link VanillaSliderSkin}): glass row like the glass buttons,
 * label lifted above a thin track, white-pill → glass-lens knob.
 *
 * <p><b>1.16.5 delta.</b> {@code SliderWidget} does NOT declare {@code renderButton} — it only overrides
 * {@code renderBackground} (the white knob). So there is no {@code @Inject(method = "renderButton")} here (it would
 * fail {@code defaultRequire 1}). Instead this mixin implements {@link GlassSliderPainter}; {@code ButtonGlassMixin}
 * (on {@code ClickableWidget.renderButton} HEAD) casts a {@code SliderWidget} to that duck and calls
 * {@link #s1mp1e$paintGlass}, cancelling vanilla only when it returns {@code true}.
 *
 * <p>Value, stepping, keyboard control and narration stay vanilla. While the skin is drawn,
 * {@code setValueFromMouse} maps the pointer onto the skin's knob travel instead of vanilla's 8 px handle travel, so
 * pressing the knob doesn't nudge the value. {@code onClick} / {@code onRelease} are only observed, to know when the
 * slider is held. Without the glass programs it paints (and maps) nothing, so vanilla draws.
 *
 * <p>Extends {@link ClickableWidget} (the target's superclass) so the inherited {@code active}/{@code alpha}/
 * {@code hovered}/{@code width}/{@code height}/{@code x}/{@code y}/{@code isFocused()}/{@code getMessage()} members
 * resolve; the constructor never runs.
 */
@Mixin(SliderWidget.class)
public abstract class SliderGlassMixin extends ClickableWidget implements GlassSliderPainter {

    @Shadow protected double value;
    @Shadow private void setValue(double value) {}

    @Unique private VanillaSliderSkin s1mp1e$skin;
    @Unique private boolean s1mp1e$held;
    @Unique private boolean s1mp1e$skinned;
    @Unique private static boolean s1mp1e$errorLogged;

    private SliderGlassMixin() { super(0, 0, 0, 0, LiteralText.EMPTY); }

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
    public boolean s1mp1e$paintGlass(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.s1mp1e$skinned = false;
        int x = this.x, y = this.y, w = this.width, h = this.height;
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable() || !VanillaSliderSkin.fits(w, h)) return false;
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

            int color = (this.active ? 0xFFFFFF : 0xA0A0A0)
                    | (Math.round(Math.min(1f, Math.max(0f, this.alpha)) * 255f) << 24);
            String label = mc.textRenderer.trimToWidth(this.getMessage().getString(), w - 4);
            DrawableHelper.drawCenteredText(matrices, mc.textRenderer, label, x + w / 2,
                    VanillaSliderSkin.labelBottom(y, h) - 9, color);
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
