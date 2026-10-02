package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.WeakHashMap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.ui.GlassSliderPainter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import net.minecraft.client.gui.widget.AbstractPressableButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every standard button becomes a translucent liquid-glass capsule — the 1.15.2
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
 * <p>Guards (26.2 / 1.20.1 parity): vanilla option {@code SliderWidget}s are handed
 * to {@code SliderGlassMixin} via the {@link GlassSliderPainter} duck (1.15.2's
 * {@code SliderWidget} has no {@code renderButton} to inject); non-{@link AbstractPressableButtonWidget}
 * widgets (textured buttons, checkboxes, text fields) stay vanilla; and buttons inside a
 * {@link ContainerScreen} (container/inventory) stay vanilla so the panel owns the look.
 */
@Mixin(AbstractButtonWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 → 1-0x30/255 ≈ 0.81). */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<AbstractButtonWidget, Fade> s1mp1e$hoverFades =
            new WeakHashMap<AbstractButtonWidget, Fade>();

    @Shadow protected float alpha;
    @Shadow public boolean active;
    @Shadow protected boolean isHovered;
    @Shadow protected int width;
    @Shadow protected int height;

    /** A villager trade button: the MerchantScreen's label-less 89x20 ButtonWidgets (WidgetButtonPage — the class is
     *  package-private, so it is recognised by shape + screen, like the mc1171 sibling). */
    private static boolean s1mp1e$isTradeButton(AbstractButtonWidget w, MinecraftClient mc) {
        return w instanceof net.minecraft.client.gui.widget.ButtonWidget
                && w.getWidth() == 89 && ((ClickableWidgetAccessor) w).s1mp1e$getHeight() == 20
                && mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen;
    }

    /**
     * A settings-page row stands in for this widget ({@link SettingsShell}): its {@code render} still runs (hover
     * state, visibility) but paints nothing. Wrapping the {@code renderButton} call inside {@code render} — rather
     * than checking at {@code renderButton} HEAD below — also silences the widgets that override {@code renderButton}
     * outright (the difficulty lock draws its padlock sprite without calling super).
     */
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/AbstractButtonWidget;renderButton(IIF)V"))
    private void s1mp1e$shellRow(AbstractButtonWidget self, int mouseX, int mouseY, float delta,
                                 Operation<Void> original) {
        if (SettingsShell.suppresses(self)) return;
        original.call(self, mouseX, mouseY, delta);
    }

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(int mouseX, int mouseY,
                                    float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // Vanilla option sliders: 1.15.2 SliderWidget has no renderButton to inject, so
        // SliderGlassMixin paints via this duck. Cancel vanilla only if it painted.
        if ((Object) this instanceof SliderWidget) {
            if (((GlassSliderPainter) (Object) this).s1mp1e$paintGlass(mouseX, mouseY, delta)) {
                ci.cancel();
            }
            return;
        }
        // Villager trade buttons (the MerchantScreen's label-less 89x20 WidgetButtonPage ButtonWidgets): 26.2 draws
        // them as faint glass capsules (AbstractButton.extractDefaultSprite -> glass, even inside a container screen)
        // and slides them with the trade list mid-glide (MerchantGlide, armed by MerchantScrollGlassMixin only while
        // the list glides — feature D). The trade items draw on top later (render's trade loop, item z 100), exactly
        // as over the vanilla sprite. (mc1171 sibling port.)
        if (s1mp1e$isTradeButton((AbstractButtonWidget) (Object) this, mc)) {
            final AbstractButtonWidget tb = (AbstractButtonWidget) (Object) this;
            if (!tb.visible) return;
            final int bx = tb.x, by = tb.y, bw = this.width, bh = this.height;
            final boolean gliding = MerchantGlide.handles(tb);
            boolean over = !gliding && (this.isHovered || tb.isFocused());   // no hover lift on a row between positions
            Fade fade = s1mp1e$hoverFades.get(tb);
            if (fade == null) {
                fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
                s1mp1e$hoverFades.put(tb, fade);
            }
            fade.to(over ? 1f : 0f);
            final float lift = LIFT_ON * fade.value();
            final float opacity = this.alpha * ScreenOpenFade.value(mc.currentScreen);
            final boolean act = this.active;
            MerchantGlide.Painter painter = new MerchantGlide.Painter() {
                @Override public void paint(float dy) {
                    GlassRenderer.button(bx, by + dy, bx + bw, by + bh + dy, 1.0f, lift, opacity, act);
                }
            };
            if (gliding) MerchantGlide.render(tb, painter);
            else painter.paint(0f);
            ci.cancel();
            return;
        }
        // Only pressable widgets get the capsule; textured buttons / checkboxes / text
        // fields override renderButton and stay vanilla.
        if (!((Object) this instanceof AbstractPressableButtonWidget)) return;
        // Inside a container/inventory screen the panel owns the look; buttons stay vanilla.
        if (mc.currentScreen instanceof ContainerScreen) return;

        AbstractButtonWidget self = (AbstractButtonWidget) (Object) this;
        if (!self.visible) return;

        int x = self.x, y = self.y, w = this.width, h = this.height;
        boolean over = this.isHovered || self.isFocused();

        // Per-widget hover-lift ease (fresh entry starts settled to avoid a flash).
        Fade fade = s1mp1e$hoverFades.get(self);
        if (fade == null) {
            fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
            s1mp1e$hoverFades.put(self, fade);
        }
        fade.to(over ? 1f : 0f);
        float lift = LIFT_ON * fade.value();

        // Screen open/close opacity ramp — the shared ScreenOpenFade, keyed to the current
        // screen instance so buttons AND the sliders on the same screen fade in together and a
        // window resize (same instance) never restarts it. Endpoint 1.0 keeps a settled
        // button's opacity at the widget's own alpha, exactly the Forge port.
        float opacity = this.alpha * ScreenOpenFade.value(mc.currentScreen);

        // Tap-feedback pulse (26.2's ButtonPressPulseMixin): on activation the whole button — glass capsule AND label —
        // dips to ~95% around its centre and springs back over ~0.25 s. ButtonPressMixin stamps the press time; here the
        // scale is 1.0 (no-op) unless recently pressed. The capsule is raw-GL absolute coords (scaled by hand about the
        // centre); the label follows the same scale via the MatrixStack so the two stay locked together.
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
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        int a = Math.round(this.alpha * 255f) << 24;
        if (s != 1f) {
            RenderSystem.pushMatrix();
            RenderSystem.translated(cx, cy, 0f);
            RenderSystem.scalef(s, s, 1f);
            RenderSystem.translated(-cx, -cy, 0f);
        }
        if (!s1mp1e$rollLabel(mc, self, x, y, w, h, textColor | a)) {
            self.drawCenteredString(mc.textRenderer, self.getMessage(),
                    x + w / 2, y + (h - 8) / 2, textColor | a);
        }
        if (s != 1f) RenderSystem.popMatrix();
        ci.cancel();
    }

    // ---- cycle buttons roll their value (26.2's CycleButtonRollMixin) ---------------------------------------------

    /** One label animator per cycle button (weak: dies with the widget). */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.WeakHashMap<AbstractButtonWidget, dev.s1mp1e.glass.render.TypingAnim> s1mp1e$rollers =
            new java.util.WeakHashMap<AbstractButtonWidget, dev.s1mp1e.glass.render.TypingAnim>();

    /**
     * Cycle buttons ("Difficulty: Normal", "Clouds: Fancy", on/off toggles …) roll their value like an odometer when
     * it changes instead of swapping the label in one frame: the unchanged "Name: " stays put, the old value floats up
     * and fades, the new one rises in glyph by glyph, and the label re-centres by gliding. Drawn at exactly the
     * position/colour of the plain centred draw above; a label too wide for the button, an invisible button or any
     * failure falls back to it (returns false).
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean s1mp1e$rollLabel(MinecraftClient mc, AbstractButtonWidget self,
                                            int x, int y, int w, int h, int color) {
        // 1.15.2 has no CyclingButtonWidget: a cycle button is an OptionButtonWidget, or a plain button whose message
        // reads "Name: Value" (the skin parts, the difficulty) — the same text test the settings shell uses.
        if (!(self instanceof net.minecraft.client.gui.widget.ButtonWidget)) return false;
        if (!(self instanceof net.minecraft.client.gui.widget.OptionButtonWidget)) {
            String s1mp1e$m = self.getMessage();
            if (s1mp1e$m.indexOf(':') < 0 && s1mp1e$m.indexOf('：') < 0) return false;
        }
        if ((color >>> 24) <= 1) return false;
        dev.s1mp1e.glass.render.TypingAnim label = s1mp1e$rollers.get(self);
        if (label == null) { label = new dev.s1mp1e.glass.render.TypingAnim(); s1mp1e$rollers.put(self, label); }
        if (label.broken) return false;
        String msg = self.getMessage();
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
