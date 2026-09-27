package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.compat.Mc1132;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.SliderGlassHost;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.OptionSliderWidget;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla option sliders (FOV, render distance, brightness, sensitivity, GUI scale…) → liquid glass: a glass
 * capsule row like the glass buttons, the label lifted above a thin track, and the white-pill → glass-lens knob.
 * The 1.13.2 counterpart of mc1144's {@code SliderGlassMixin} (1.14.4 {@code SliderWidget}).
 *
 * <p><b>1.13.2 shape (javap-verified, legacy yarn 1.13.2+build.604-v2).</b> There is no {@code SliderWidget}:
 * {@code OptionSliderWidget extends ButtonWidget} directly and does NOT override {@code method_891} (the button
 * render). Its value is {@code private double field_20084} (0..1) and the drag flag is {@code public boolean
 * dragging}. Everything else lives in two methods:
 * <ul>
 *   <li>{@code renderBg} — legacy-yarn {@code mouseDragged(MinecraftClient,int,int)V}: while {@code dragging}
 *       it recomputes the value from the mouse ({@code (mouseX - (x + 4)) / (width - 8)} then ONE
 *       {@code MathHelper.clamp(DDD)D}), then (dragging or FULLSCREEN_RESOLUTION) writes the option
 *       ({@code method_18257}), snaps the value ({@code method_18261}) and refreshes {@code message}, and finally
 *       draws the knob with TWO {@code drawTexture(IIIIII)V} calls (owner = this class).</li>
 *   <li>{@code method_18374(DD)V} — the FINAL onClick: the same mouse→value mapping with ONE clamp, the option
 *       write, {@code dragging = true}.</li>
 * </ul>
 * {@code ButtonGlassMixin} HEAD-cancels {@code method_891} for every button, which used to swallow
 * {@code renderBg} — the knob AND the drag update — so 1.13.2 sliders were knob-less capsules that only reacted
 * to clicks. Now {@code ButtonGlassMixin} hands them here through the {@link SliderGlassHost} duck.
 *
 * <p><b>What changes.</b> {@link #s1mp1e$paintSkin} runs vanilla {@code renderBg} itself (virtual dispatch, so
 * the drag update, option write, value snap and label refresh are all vanilla) with the two knob
 * {@code drawTexture} calls suppressed, then paints the glass skin. While the skin is drawn, the two
 * {@code MathHelper.clamp} calls are fed {@link VanillaSliderSkin#valueAt} (the skin's knob travel) instead of
 * vanilla's 8&nbsp;px handle travel, so pressing the knob doesn't nudge the value and the knob stays exactly under
 * the pointer; the clamp itself still runs on the remapped value. Two separate handlers because the mouse local
 * is a {@code double} in onClick and an {@code int} in {@code renderBg}. With no glass programs nothing is
 * remapped and nothing is suppressed, so vanilla draws and behaves as shipped.
 *
 * <p>Extends {@link ButtonWidget} (the target's superclass) so the inherited {@code x}/{@code y}/{@code width}/
 * {@code height}/{@code active}/{@code hovered}/{@code message} members and the protected {@code renderBg} resolve
 * at compile time; the constructor never runs. {@code class_4122}'s methods are defaults, so the abstract mixin
 * compiles without stubs.
 */
@Mixin(OptionSliderWidget.class)
public abstract class OptionSliderGlassMixin extends ButtonWidget implements SliderGlassHost {

    @Shadow private double field_20084;
    @Shadow public boolean dragging;

    @Unique private VanillaSliderSkin s1mp1e$skin;
    /** The skin painted on the last frame: the clamp remap is live. */
    @Unique private boolean s1mp1e$skinned;
    /** Inside our own renderBg call: suppress vanilla's knob blits. */
    @Unique private boolean s1mp1e$painting;
    @Unique private static boolean s1mp1e$errorLogged;

    /** Never runs; present only so this mixin can extend the target's superclass. */
    private OptionSliderGlassMixin() { super(0, 0, 0, ""); }

    /** onClick (final): mouse → value on the skin's knob travel while the skin is drawn. */
    @WrapOperation(method = "method_18374(DD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(DDD)D"))
    private double s1mp1e$clickToSkin(double value, double min, double max, Operation<Double> original,
                                      @Local(argsOnly = true, ordinal = 0) double mouseX) {
        if (this.s1mp1e$skinned) value = VanillaSliderSkin.valueAt(mouseX, this.x, this.width);
        return original.call(value, min, max);
    }

    /** renderBg drag update (runs only while dragging): the same remap with the int render mouse. */
    @WrapOperation(method = "mouseDragged(Lnet/minecraft/client/MinecraftClient;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(DDD)D"))
    private double s1mp1e$dragToSkin(double value, double min, double max, Operation<Double> original,
                                     @Local(argsOnly = true, ordinal = 0) int mouseX) {
        if (this.s1mp1e$skinned) value = VanillaSliderSkin.valueAt(mouseX, this.x, this.width);
        return original.call(value, min, max);
    }

    /** The two knob blits in renderBg: skipped while our paint drives renderBg. */
    @WrapOperation(method = "mouseDragged(Lnet/minecraft/client/MinecraftClient;II)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/OptionSliderWidget;drawTexture(IIIIII)V"))
    private void s1mp1e$skipKnob(OptionSliderWidget instance, int x, int y, int u, int v, int w, int h,
                                 Operation<Void> original) {
        if (this.s1mp1e$painting) return;
        original.call(instance, x, y, u, v, w, h);
    }

    @Override
    public boolean s1mp1e$paintSkin(int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = this.width, h = this.height;
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable() || !VanillaSliderSkin.fits(w, h)) {
            this.s1mp1e$skinned = false;
            return false;
        }
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (this.s1mp1e$skin == null) this.s1mp1e$skin = new VanillaSliderSkin();
            // The skin draws this frame, so the drag update below maps onto its knob travel.
            this.s1mp1e$skinned = true;

            // Vanilla renderBg (virtual: this slider's override): drag value, option write, value snap and
            // label refresh all run as shipped; only the two knob blits are suppressed.
            this.s1mp1e$painting = true;
            try {
                this.mouseDragged(mc, mouseX, mouseY);
            } finally {
                this.s1mp1e$painting = false;
            }

            // "Held" only while vanilla is dragging AND the left button is genuinely down AND this slider has been
            // painted continuously: a stale flag from before a paint gap (screen swap, scrolled away) must not lift
            // the knob on an unrelated press. paintGap() is read BEFORE paint() refreshes it.
            boolean held = this.dragging && Mc1132.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    && !this.s1mp1e$skin.paintGap();

            // GUI-scaled sub-pixel pointer x (the Mouse bridge is in window pixels).
            double pointerX = Mc1132.scaledMouseX();
            // No widget alpha on 1.13.2: the shared screen-open fade alone (the buttons read the same one).
            float alpha = ScreenOpenFade.value(mc.currentScreen);

            this.s1mp1e$skin.paint(x, y, w, h, this.field_20084, held, pointerX, this.hovered, this.active, alpha);

            // Label lifted into the row's upper box [y, y + h - 8], centred in [x + 2, x + w - 2].
            int a = Math.round(alpha * 255f);
            if (a > 255) a = 255;
            if (a >= 8) {
                String label = this.message;
                if (label != null && mc.textRenderer != null) {
                    int rgb = this.active ? 0xFFFFFF : 0xA0A0A0;
                    float lx = x + 2f, rx = x + w - 2f;
                    float tx = (lx + rx) / 2f - mc.textRenderer.getStringWidth(label) / 2f;
                    float ty = y + ((h - 8) - 9) / 2f + 1f;
                    mc.textRenderer.drawWithShadow(label, tx, ty, (a << 24) | rgb);
                }
            }
            return true;
        } catch (Throwable t) {
            this.s1mp1e$skinned = false;
            this.s1mp1e$painting = false;
            if (!s1mp1e$errorLogged) {
                s1mp1e$errorLogged = true;
                LogManager.getLogger("S1mp1e").error("[S1mp1e] glass option slider failed, drawing vanilla", t);
            }
            return false;
        }
    }
}
