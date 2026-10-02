package dev.s1mp1e.glass.mixin;

import com.mojang.logging.LogUtils;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla option sliders (FOV, render distance, volume, brightness, sensitivity…) → liquid glass, the 1.21.1
 * counterpart of the 26.2 client's slider skin ({@link VanillaSliderSkin}): glass row like the glass buttons,
 * label lifted above a thin track, white-pill → glass-lens knob.
 *
 * <p>{@code ButtonGlassMixin} only glasses {@code PressableWidget}s, so sliders were plain textures until now.
 * {@code SliderWidget.renderWidget} is replaced for drawing; value, stepping, keyboard control and narration stay
 * vanilla. While the skin is drawn, {@code setValueFromMouse} maps the pointer onto the skin's knob travel instead of
 * vanilla's 8 px handle travel, so pressing the knob doesn't nudge the value. {@code onClick} / {@code onRelease} are
 * only observed, to know when the slider is held. Without the glass programs it draws (and maps) vanilla.
 *
 * <p>Extends {@link ClickableWidget} (the target's superclass) only so the protected static
 * {@code drawScrollableText} overload with an explicit box compiles; the constructor never runs.
 */
@Mixin(SliderWidget.class)
public abstract class SliderGlassMixin extends ClickableWidget implements dev.s1mp1e.client.gui.SettingsShell.SliderAccess {

    @Shadow protected double value;
    @Shadow private void setValue(double value) {}

    @Unique private VanillaSliderSkin s1mp1e$skin;
    @Unique private boolean s1mp1e$held;
    @Unique private boolean s1mp1e$skinned;
    @Unique private boolean s1mp1e$rowMode;
    @Unique private static boolean s1mp1e$errorLogged;

    private SliderGlassMixin() { super(0, 0, 0, 0, Text.empty()); }

    @Override
    public double s1mp1e$value() {
        return this.value;
    }

    @Override
    public boolean s1mp1e$held() {
        if (!this.s1mp1e$held) return false;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                && !VanillaSliderSkin.devMouseDown) this.s1mp1e$held = false;
        return this.s1mp1e$held;
    }

    /** A settings-page row paints this slider as the config-menu slider on the given track. */
    @Override
    public void s1mp1e$paintRow(DrawContext context, float tx0, float tx1, float cy, float alpha) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (this.s1mp1e$skin == null) this.s1mp1e$skin = new VanillaSliderSkin();
        long window = mc.getWindow().getHandle();
        boolean buttonDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
                || VanillaSliderSkin.devMouseDown;
        if (!buttonDown || (!this.s1mp1e$rowMode && this.s1mp1e$skin.paintGap())) this.s1mp1e$held = false;
        double pointerX = mc.mouse.getX() * mc.getWindow().getScaledWidth() / Math.max(1, mc.getWindow().getWidth());
        this.s1mp1e$skin.paintRow(context, tx0, tx1, cy, this.value, this.s1mp1e$held, pointerX, this.active, alpha);
        this.s1mp1e$rowMode = true;
    }

    @Inject(method = "onClick", at = @At("HEAD"))
    private void s1mp1e$press(double mouseX, double mouseY, CallbackInfo ci) {
        this.s1mp1e$held = this.active;
        if (this.s1mp1e$rowMode && this.s1mp1e$skin != null) this.s1mp1e$skin.rowPress(mouseX);
    }

    @Inject(method = "onRelease", at = @At("HEAD"))
    private void s1mp1e$release(double mouseX, double mouseY, CallbackInfo ci) {
        this.s1mp1e$held = false;
    }

    /** Mouse → value on the skin's knob travel (vanilla maps onto its own 8 px handle travel). */
    @Inject(method = "setValueFromMouse", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$mapToSkin(double mouseX, CallbackInfo ci) {
        if (this.s1mp1e$rowMode && this.s1mp1e$skin != null) {     // a settings-page row: its own track and grab offset
            this.setValue(this.s1mp1e$skin.rowValueAt(mouseX));
            ci.cancel();
        } else if (this.s1mp1e$skinned) {
            this.setValue(VanillaSliderSkin.valueAt(mouseX, this.getX(), this.width));
            ci.cancel();
        }
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassSlider(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$skinned = false;
        if (dev.s1mp1e.client.gui.SettingsShell.suppresses(this)) {      // a settings-page row draws this slider
            ci.cancel();
            return;
        }
        this.s1mp1e$rowMode = false;
        int x = this.getX(), y = this.getY(), w = this.width, h = this.height;
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable() || !VanillaSliderSkin.fits(w, h)) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            long window = mc.getWindow().getHandle();
            boolean buttonDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (this.s1mp1e$skin == null) this.s1mp1e$skin = new VanillaSliderSkin();
            // released where no onRelease reached us, or a leftover from before this slider was last painted
            if (!buttonDown || this.s1mp1e$skin.paintGap()) this.s1mp1e$held = false;
            double pointerX = mc.mouse.getX() * mc.getWindow().getScaledWidth() / Math.max(1, mc.getWindow().getWidth());
            float alpha = this.alpha * ScreenOpenFade.value(mc.currentScreen);
            this.s1mp1e$skin.paint(context, x, y, w, h, this.value, this.s1mp1e$held, pointerX,
                    this.hovered || this.isFocused(), this.active, alpha);

            int color = (this.active ? 0xFFFFFF : 0xA0A0A0) | (Math.round(Math.min(1f, Math.max(0f, this.alpha)) * 255f) << 24);
            drawScrollableText(context, mc.textRenderer, this.getMessage(), x + 2, y, x + w - 2,
                    VanillaSliderSkin.labelBottom(y, h), color);
            this.s1mp1e$skinned = true;
            ci.cancel();
        } catch (Throwable t) {
            if (!s1mp1e$errorLogged) {
                s1mp1e$errorLogged = true;
                LogUtils.getLogger().error("[S1mp1e] glass slider failed, drawing vanilla", t);
            }
        }
    }
}
