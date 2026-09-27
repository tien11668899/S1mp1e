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
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Vanilla volume sliders (Music &amp; Sounds screen) → liquid glass. The same pattern as
 * {@link OptionSliderGlassMixin}, on 1.13.2's own volume widget.
 *
 * <p><b>1.13.2 shape (javap-verified, legacy yarn 1.13.2+build.604-v2).</b> The volume slider is the
 * PACKAGE-PRIVATE {@code net.minecraft.client.gui.screen.SoundsScreen$SoundButtonWidget extends ButtonWidget}, so it
 * is targeted by name ({@code targets = "..."}) and never referenced as a type. It does not override
 * {@code method_891}. Its value is {@code public double field_20319} (0..1) and its drag flag is
 * {@code public boolean mouseButtonPressed}. {@code renderBg} (legacy-yarn {@code mouseDragged(MinecraftClient,int,int)V})
 * holds, while the flag is set, the mouse→value mapping with ONE {@code MathHelper.clamp(DDD)D}, the volume write
 * ({@code GameOptions.setSoundVolume}), {@code options.save()} (every frame while dragging — vanilla behaviour,
 * left unchanged) and the label refresh; then TWO {@code drawTexture(IIIIII)V} knob blits (owner = this class).
 * {@code method_18374(DD)V} (onClick, not final here) has the same mapping with ONE clamp, the write and save, and
 * sets the flag.
 *
 * <p>{@link #s1mp1e$paintSkin} runs vanilla {@code renderBg} with the knob blits suppressed, then paints the glass
 * skin; while the skin is drawn both clamps are fed {@link VanillaSliderSkin#valueAt} (the skin's knob travel).
 * The knob-blit wrapper's receiver is declared as {@link ButtonWidget} with {@link Coerce} (the real receiver type
 * is not accessible from here); Mixin's coercion accepts a supertype of the invoked owner.
 *
 * <p>Extends {@link ButtonWidget} (the target's superclass) so the inherited members and the protected
 * {@code renderBg} resolve at compile time; the constructor never runs.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.SoundsScreen$SoundButtonWidget")
public abstract class SoundSliderGlassMixin extends ButtonWidget implements SliderGlassHost {

    @Shadow public double field_20319;
    @Shadow public boolean mouseButtonPressed;

    @Unique private VanillaSliderSkin s1mp1e$skin;
    /** The skin painted on the last frame: the clamp remap is live. */
    @Unique private boolean s1mp1e$skinned;
    /** Inside our own renderBg call: suppress vanilla's knob blits. */
    @Unique private boolean s1mp1e$painting;
    @Unique private static boolean s1mp1e$errorLogged;

    /** Never runs; present only so this mixin can extend the target's superclass. */
    private SoundSliderGlassMixin() { super(0, 0, 0, ""); }

    /** onClick: mouse → value on the skin's knob travel while the skin is drawn. */
    @WrapOperation(method = "method_18374(DD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(DDD)D"))
    private double s1mp1e$clickToSkin(double value, double min, double max, Operation<Double> original,
                                      @Local(argsOnly = true, ordinal = 0) double mouseX) {
        if (this.s1mp1e$skinned) value = VanillaSliderSkin.valueAt(mouseX, this.x, this.width);
        return original.call(value, min, max);
    }

    /** renderBg drag update (runs only while the button is held): the same remap with the int render mouse. */
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
                     target = "Lnet/minecraft/client/gui/screen/SoundsScreen$SoundButtonWidget;drawTexture(IIIIII)V"))
    private void s1mp1e$skipKnob(@Coerce ButtonWidget instance, int x, int y, int u, int v, int w, int h,
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

            // Vanilla renderBg (virtual: the volume widget's override): drag value, volume write, save and label
            // refresh all run as shipped; only the two knob blits are suppressed.
            this.s1mp1e$painting = true;
            try {
                this.mouseDragged(mc, mouseX, mouseY);
            } finally {
                this.s1mp1e$painting = false;
            }

            // "Held" only while vanilla is dragging AND the left button is genuinely down AND this slider has been
            // painted continuously (paintGap() is read BEFORE paint() refreshes it).
            boolean held = this.mouseButtonPressed && Mc1132.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    && !this.s1mp1e$skin.paintGap();

            double pointerX = Mc1132.scaledMouseX();
            float alpha = ScreenOpenFade.value(mc.currentScreen);

            this.s1mp1e$skin.paint(x, y, w, h, this.field_20319, held, pointerX, this.hovered, this.active, alpha);

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
                LogManager.getLogger("S1mp1e").error("[S1mp1e] glass volume slider failed, drawing vanilla", t);
            }
            return false;
        }
    }
}
