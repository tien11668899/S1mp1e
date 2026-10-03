package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.compat.Mc1132;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassSliderPainter;
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
 * <p>{@link #s1mp1e$paintGlass} runs vanilla {@code renderBg} with the knob blits suppressed, then paints the glass
 * skin; while the skin is drawn both clamps are fed {@link VanillaSliderSkin#valueAt} (the skin's knob travel).
 * The knob-blit wrapper's receiver is declared as {@link ButtonWidget} with {@link Coerce} (the real receiver type
 * is not accessible from here); Mixin's coercion accepts a supertype of the invoked owner.
 *
 * <p>Extends {@link ButtonWidget} (the target's superclass) so the inherited members and the protected
 * {@code renderBg} resolve at compile time; the constructor never runs.
 *
 * <p><b>Settings-page row</b> ({@link SettingsShell.SliderAccess}, PORT_DELTA_SLIDER_ROLL): the row is the feature-menu
 * slider ({@code label | track | value}) painted by {@link VanillaSliderSkin#paintRow}; the widget itself is not
 * rendered. {@link #s1mp1e$tick} runs vanilla's {@code renderBg} once per frame (drag value, option write, label
 * refresh) without the knob blits, and while the row form is armed both clamps are fed
 * {@link VanillaSliderSkin#rowValueAt} (the row's own track and grab offset) — a press first records the grab
 * ({@link VanillaSliderSkin#rowPress}). A release that never reaches the widget (1.13.2 screens hand releases to
 * their focused child only) is caught by the button state.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.SoundsScreen$SoundButtonWidget")
public abstract class SoundSliderGlassMixin extends ButtonWidget implements GlassSliderPainter, SettingsShell.SliderAccess {

    @Shadow public double field_20319;
    @Shadow public boolean mouseButtonPressed;

    @Unique private VanillaSliderSkin s1mp1e$skin;
    /** The skin painted on the last frame: the clamp remap is live. */
    @Unique private boolean s1mp1e$skinned;
    /** A settings-page row painted last: the clamp remap uses the row's track. */
    @Unique private boolean s1mp1e$rowMode;
    /** Inside our own renderBg call: suppress vanilla's knob blits. */
    @Unique private boolean s1mp1e$painting;
    @Unique private static boolean s1mp1e$errorLogged;

    /** Never runs; present only so this mixin can extend the target's superclass. */
    private SoundSliderGlassMixin() { super(0, 0, 0, ""); }

    /** onClick: mouse → value on the skin's knob travel (or the row's track) while the skin is drawn. */
    @WrapOperation(method = "method_18374(DD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(DDD)D"))
    private double s1mp1e$clickToSkin(double value, double min, double max, Operation<Double> original,
                                      @Local(argsOnly = true, ordinal = 0) double mouseX) {
        if (this.s1mp1e$rowMode && this.s1mp1e$skin != null) {
            this.s1mp1e$skin.rowPress(mouseX);
            value = this.s1mp1e$skin.rowValueAt(mouseX);
        } else if (this.s1mp1e$skinned) {
            value = VanillaSliderSkin.valueAt(mouseX, this.x, this.width);
        }
        return original.call(value, min, max);
    }

    /** renderBg drag update (runs only while dragging): the same remap; a row follows the sub-pixel pointer. */
    @WrapOperation(method = "mouseDragged(Lnet/minecraft/client/MinecraftClient;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/MathHelper;clamp(DDD)D"))
    private double s1mp1e$dragToSkin(double value, double min, double max, Operation<Double> original,
                                     @Local(argsOnly = true, ordinal = 0) int mouseX) {
        if (this.s1mp1e$rowMode && this.s1mp1e$skin != null) {
            value = this.s1mp1e$skin.rowValueAt(Mc1132.scaledMouseX());
        } else if (this.s1mp1e$skinned) {
            value = VanillaSliderSkin.valueAt(mouseX, this.x, this.width);
        }
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

    @Unique
    private boolean s1mp1e$buttonDown() {
        return Mc1132.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) || VanillaSliderSkin.devMouseDown;
    }

    // ---- settings-page row ----

    @Override
    public double s1mp1e$value() {
        return this.field_20319;
    }

    @Override
    public boolean s1mp1e$held() {
        return this.mouseButtonPressed && this.s1mp1e$buttonDown();
    }

    @Override
    public void s1mp1e$tick(int mouseX, int mouseY) {
        if (this.mouseButtonPressed && !this.s1mp1e$buttonDown()) this.mouseButtonPressed = false;   // the release went elsewhere
        this.s1mp1e$painting = true;
        try {
            this.mouseDragged(MinecraftClient.getInstance(), mouseX, mouseY);
        } finally {
            this.s1mp1e$painting = false;
        }
    }

    @Override
    public void s1mp1e$paintRow(float tx0, float tx1, float cy, float alpha) {
        if (this.s1mp1e$skin == null) this.s1mp1e$skin = new VanillaSliderSkin();
        this.s1mp1e$skin.paintRow(tx0, tx1, cy, this.field_20319, this.s1mp1e$held(), Mc1132.scaledMouseX(),
                this.active, alpha);
        this.s1mp1e$skinned = false;
        this.s1mp1e$rowMode = true;
    }

    // ---- the normal skin (any other screen) ----

    @Override
    public boolean s1mp1e$paintGlass(int mouseX, int mouseY, float delta) {
        this.s1mp1e$rowMode = false;                 // the normal skin paints (a row's own painting never gets here)
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
            boolean held = this.mouseButtonPressed && this.s1mp1e$buttonDown() && !this.s1mp1e$skin.paintGap();
            // GUI-scaled sub-pixel pointer x (the Mouse bridge is in window pixels).
            double pointerX = Mc1132.scaledMouseX();
            // No widget alpha on 1.13.2: the shared screen-open fade alone (the buttons read the same one).
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
                    mc.textRenderer.method_18355(label, tx, ty, (a << 24) | rgb);
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
