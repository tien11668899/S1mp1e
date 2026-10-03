package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.ui.GlassSliderPainter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DelegatingRealmsButtonWidget;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every standard button becomes a translucent liquid-glass capsule — the 1.13.2
 * Fabric counterpart of the 1.8.9/1.12.2 GlassButtonPainter and 26.2's
 * ButtonGlassMixin. The BTN program is the capsule shader that samples NO
 * backdrop, so this works on the title screen with no grab. Vanilla's widget
 * sprite is cancelled; the label is redrawn on top, unchanged.
 *
 * <p>Knobs (measured off 26.2): corner 1.0 = full capsule, lift 0.81 when
 * hovered/focused (26.2's G=0x30 → 1-0x30/255) else 0, opacity = the widget's
 * own alpha, and {@code enabled=false} lets the shader dim to 0.4.
 *
 * <p>System 6 (button fade-in/out) has two eased channels, both adding animation
 * the Forge {@code GlassButtonHandler}/{@code GlassButtonPainter} line lacked while
 * leaving every settled endpoint byte-for-byte identical to it:
 *
 * <ul>
 *   <li><b>Hover lift.</b> The Forge line snapped the lift 0&rarr;0.81 on hover.
 *       Here a per-widget {@link Fade} eases the lift over {@link #HOVER_FADE_MS}
 *       (100 ms, the same duration the container hover pill uses), so hover-in and
 *       hover-out ramp instead of popping. Endpoints unchanged (0 resting, 0.81
 *       hovered). The rig is keyed per widget in a {@link WeakHashMap} so dead
 *       widgets evict themselves and coexisting buttons never cross-wire.</li>
 *   <li><b>Screen open/close opacity.</b> The Forge line snapped buttons in with
 *       the screen (opacity = the widget's own alpha, no ramp). Here the shared
 *       {@link ScreenOpenFade} (a linear 0&rarr;1 ramp over 150 ms, keyed to the
 *       current screen instance) eases the capsule opacity, so a newly opened
 *       screen's buttons fade in and the sliders on the same screen fade with them
 *       (they read the same shared fade). The endpoint is 1.0, so a settled
 *       button's opacity is still exactly the widget's alpha — only the transition
 *       eases. A window resize reuses the same {@code Screen} instance, so the
 *       identity-keyed fade does not restart (no flash).</li>
 * </ul>
 *
 * <p><b>1.13.2 (old widget system).</b> There is one button class: {@code ButtonWidget.method_891(IIF)} is the whole
 * render (visibility test, hover test, sprite, {@code renderBg}, label) and every button with behaviour is an
 * anonymous subclass overriding {@code method_18374} (the press). So this HEAD hook runs vanilla's own visibility
 * and hover tests first (vanilla only computes {@code hovered} after this point), then: a settings-page row
 * ({@link SettingsShell}) paints nothing; the option / volume sliders paint through the {@link GlassSliderPainter}
 * duck (they do not override {@code method_891}; their knob and drag update live in {@code renderBg}); buttons
 * inside a {@link HandledScreen} and the Realms delegate stay vanilla; everything else is the glass capsule.
 * Widgets that override {@code method_891} outright (textured buttons, the recipe-book widgets, the difficulty lock)
 * never reach this hook. Buttons have no alpha and no keyboard focus before 1.14.
 */
@Mixin(ButtonWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 → 1-0x30/255 ≈ 0.81). */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<ButtonWidget, Fade> s1mp1e$hoverFades =
            new WeakHashMap<ButtonWidget, Fade>();

    @Unique private static boolean s1mp1e$errorLogged;

    @Shadow public boolean active;
    @Shadow protected boolean hovered;
    @Shadow protected int width;
    @Shadow protected int height;
    @Shadow public int x;
    @Shadow public int y;
    @Shadow public boolean visible;
    @Shadow public String message;

    @Inject(method = "method_891", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // 1. vanilla's own first test
        if (!this.visible) return;

        // 2. vanilla's exact hover test, stored BEFORE any early return or cancel (vanilla only computes it after
        //    this HEAD — without this the flag is always stale)
        this.hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;

        ButtonWidget self = (ButtonWidget) (Object) this;
        // 3. a settings-page row stands in for this widget: it paints nothing (the hover flag above stays alive)
        if (SettingsShell.suppresses(self)) { ci.cancel(); return; }

        try {
            // 4. no glass pipeline -> vanilla
            if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;

            // 5. inside a container screen the glass panel owns the look -> vanilla widgets
            if (mc.currentScreen instanceof HandledScreen) return;

            // 6. vanilla sliders paint their own glass skin (or fall back to fully vanilla)
            if (self instanceof GlassSliderPainter) {
                if (((GlassSliderPainter) self).s1mp1e$paintGlass(mouseX, mouseY, delta)) ci.cancel();
                return;
            }

            // 7. the Realms delegate overrides renderBg, which must run -> vanilla
            if (self instanceof DelegatingRealmsButtonWidget) return;

            // 8. glass capsule
            int x = this.x, y = this.y, w = this.width, h = this.height;
            boolean over = this.hovered;

            // Per-widget hover-lift ease (fresh entry starts settled to avoid a flash).
            Fade fade = s1mp1e$hoverFades.get(self);
            if (fade == null) {
                fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
                s1mp1e$hoverFades.put(self, fade);
            }
            fade.to(over ? 1f : 0f);
            float lift = LIFT_ON * fade.value();

            // Shared screen-open ramp (no widget alpha on 1.13.2, so the base is 1.0).
            float opacity = ScreenOpenFade.value(mc.currentScreen);

            // Tap-feedback pulse (26.2's ButtonPressPulseMixin): on activation the whole button — glass capsule AND
            // label — dips to ~95% around its centre and springs back over ~0.25 s. ButtonPressMixin stamps the press
            // time; here the scale is 1.0 (no-op) unless recently pressed. The capsule is raw-GL absolute coords
            // (scaled by hand about the centre); the label follows the same scale through the GL model-view.
            float s = dev.s1mp1e.glass.anim.PressPulse.scale(self);
            float cx = x + w / 2f, cy = y + h / 2f;
            if (s != 1f) {
                float bx0 = cx + (x - cx) * s, by0 = cy + (y - cy) * s;
                float bx1 = cx + (x + w - cx) * s, by1 = cy + (y + h - cy) * s;
                GlassRenderer.button(bx0, by0, bx1, by1, 1.0f, lift, opacity, this.active);
            } else {
                GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, this.active);
            }

            // label on top, vanilla colouring (scaled with the capsule during a press dip)
            String label = this.message;
            if (label != null && mc.textRenderer != null) {
                int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
                int a = Math.round(Math.min(1f, Math.max(0f, opacity)) * 255f);
                if (a >= 8) {
                    int color = textColor | (a << 24);
                    if (s != 1f) {
                        GlStateManager.pushMatrix();
                        GlStateManager.translate(cx, cy, 0f);
                        GlStateManager.scale(s, s, 1f);
                        GlStateManager.translate(-cx, -cy, 0f);
                    }
                    try {
                        if (!s1mp1e$rollLabel(mc, self, x, y, w, h, color)) {
                            int tw = mc.textRenderer.getStringWidth(label);
                            mc.textRenderer.method_18355(label, x + w / 2f - tw / 2f, y + (h - 8) / 2f, color);
                        }
                    } finally {
                        if (s != 1f) GlStateManager.popMatrix();
                    }
                }
            }
            ci.cancel();
        } catch (Throwable t) {
            // not cancelled -> vanilla draws this button
            if (!s1mp1e$errorLogged) {
                s1mp1e$errorLogged = true;
                LogManager.getLogger("S1mp1e").error("[S1mp1e] glass button failed, drawing vanilla", t);
            }
        }
    }

    // ---- cycle buttons roll their value (26.2's CycleButtonRollMixin) ---------------------------------------------

    /** One label animator per cycle button (weak: dies with the widget). */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.WeakHashMap<ButtonWidget, dev.s1mp1e.glass.render.TypingAnim> s1mp1e$rollers =
            new java.util.WeakHashMap<ButtonWidget, dev.s1mp1e.glass.render.TypingAnim>();

    /**
     * Cycle buttons ("Difficulty: Normal", "Clouds: Fancy", on/off toggles …) roll their value like an odometer when
     * it changes instead of swapping the label in one frame: the unchanged "Name: " stays put, the old value floats up
     * and fades, the new one rises in glyph by glyph, and the label re-centres by gliding. Drawn at exactly the
     * position/colour of the plain centred draw above; a label too wide for the button, an invisible button or any
     * failure falls back to it (returns false).
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean s1mp1e$rollLabel(MinecraftClient mc, ButtonWidget self,
                                            int x, int y, int w, int h, int color) {
        // 1.13.2 has no CyclingButtonWidget: a cycle button is an OptionButtonWidget, or a plain button whose message
        // reads "Name: Value" (the skin parts, the difficulty) — the same text test the settings shell uses.
        if (!(self instanceof net.minecraft.client.gui.widget.OptionButtonWidget)) {
            String s1mp1e$m = self.message;
            if (s1mp1e$m.indexOf(':') < 0 && s1mp1e$m.indexOf('：') < 0) return false;
        }
        if ((color >>> 24) <= 1) return false;
        dev.s1mp1e.glass.render.TypingAnim label = s1mp1e$rollers.get(self);
        if (label == null) { label = new dev.s1mp1e.glass.render.TypingAnim(); s1mp1e$rollers.put(self, label); }
        if (label.broken) return false;
        String msg = self.message;
        String seq = msg;
        int x0 = x + 2, x1 = x + w - 2;
        if (mc.textRenderer.getStringWidth(seq) > x1 - x0) return false;
        try {
            label.extractLabel(mc.textRenderer, msg, seq, x + w / 2, y + (h - 8) / 2, x0, x1, color, true);
            return true;
        } catch (Throwable t) {
            label.broken = true;
            System.out.println("[S1mp1e] cycle-button roll disabled: " + t);
            return false;
        }
    }
}
