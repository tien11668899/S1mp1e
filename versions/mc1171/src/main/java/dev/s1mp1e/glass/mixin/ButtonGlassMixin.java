package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GlassSliderHook;
import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every standard menu button becomes a translucent liquid-glass capsule — the 1.17.1 Fabric counterpart of the
 * 1.8.9/1.12.2 GlassButtonPainter and 26.2's ButtonGlassMixin. The BTN program is the capsule shader that samples
 * NO backdrop, so this works on the title screen with no grab. Vanilla's widget sprite is cancelled; the label is
 * redrawn on top, unchanged.
 *
 * <p>Fabric target: {@code ClickableWidget.renderButton(MatrixStack, int, int, float)} at {@code @At("HEAD")},
 * {@code cancellable = true}. On 1.17.1 (as 1.19.2) {@code renderButton} is concrete on {@code ClickableWidget} (in 1.20 it
 * became abstract, forcing mc1201 to {@code @Redirect} the call inside {@code render()}), so a HEAD inject is the
 * clean form. This is the single injector on {@code ClickableWidget.renderButton} in the project.
 *
 * <p>This one method routes three cases:
 * <ul>
 *   <li><b>Vanilla option sliders.</b> {@code SliderWidget} inherits this concrete {@code renderButton} and draws
 *       its knob in {@code renderBackground}. Its mixin implements {@link GlassSliderHook}; we dispatch to
 *       {@code s1mp1e$renderGlass} first and cancel only when it reports it skinned, so the option sliders get the
 *       glass row + lens knob instead of a knob-less capsule. If the skin declines (too small, or an error) we
 *       return and let vanilla draw the handle.</li>
 *   <li><b>Menu-screen buttons.</b> A {@link PressableWidget} outside a {@link HandledScreen} gets the glass
 *       capsule + label.</li>
 *   <li><b>Villager trade buttons</b> (89x20, label-less, in a {@code MerchantScreen}): glass capsules like 26.2, slid
 *       with the trade list while it glides ({@code MerchantGlide}, feature D).</li>
 *   <li><b>Everything else</b> (recipe tabs, result cells, container widgets) draws vanilla: those are owned by the
 *       HandledScreen + recipe mixins, and glassing them here would swallow their item icons.</li>
 * </ul>
 *
 * <p>Knobs (measured off 26.2): corner 1.0 = full capsule, lift 0.81 when hovered/focused (26.2's
 * G=0x30 -> 1-0x30/255) else 0, opacity = the widget's own alpha, and {@code enabled=false} lets the shader dim
 * to 0.4.
 *
 * <p>Two eased channels leave every settled endpoint byte-for-byte identical to the Forge line:
 * <ul>
 *   <li><b>Hover lift.</b> A per-widget {@link Fade} eases the lift 0&rarr;0.81 over {@link #HOVER_FADE_MS}
 *       (100 ms), keyed per widget in a {@link WeakHashMap} so dead widgets evict themselves.</li>
 *   <li><b>Screen open/close opacity.</b> The shared {@link ScreenOpenFade} (also used by the glass option
 *       sliders, so both restart together) eases the capsule opacity 0&rarr;1 over 150 ms, restarting from 0
 *       whenever {@code currentScreen} changes (identity compare, so a resize's reused instance never flashes).
 *       The endpoint is 1.0, so a settled button's opacity is still exactly the widget's alpha.</li>
 * </ul>
 */
@Mixin(ClickableWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 -> 1-0x30/255 ~= 0.81). */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<ClickableWidget, Fade> s1mp1e$hoverFades =
            new WeakHashMap<ClickableWidget, Fade>();

    @Shadow protected float alpha;
    @Shadow public boolean active;
    @Shadow protected boolean hovered;
    @Shadow protected int width;
    @Shadow protected int height;

    /** A villager trade button: the MerchantScreen's label-less 89x20 ButtonWidgets (WidgetButtonPage, javap-verified
     *  size; the class itself is package-private so it is recognised by shape + screen). */
    private static boolean s1mp1e$isTradeButton(ClickableWidget w, MinecraftClient mc) {
        return w instanceof net.minecraft.client.gui.widget.ButtonWidget
                && w.getWidth() == 89 && w.getHeight() == 20
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
                     target = "Lnet/minecraft/client/gui/widget/ClickableWidget;"
                            + "renderButton(Lnet/minecraft/client/util/math/MatrixStack;IIF)V"))
    private void s1mp1e$shellRow(ClickableWidget self, MatrixStack matrices, int mouseX, int mouseY, float delta,
                                 Operation<Void> original) {
        if (SettingsShell.suppresses(self)) return;
        original.call(self, matrices, mouseX, mouseY, delta);
    }

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(MatrixStack matrices, int mouseX, int mouseY,
                                    float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;

        ClickableWidget self = (ClickableWidget) (Object) this;
        MinecraftClient mc = MinecraftClient.getInstance();

        // 1) Vanilla option sliders draw their own glass row + lens knob (label included). Cancel only when the
        //    skin reports it drew; otherwise fall back to vanilla (no capsule for a slider).
        if ((Object) this instanceof GlassSliderHook) {
            if (((GlassSliderHook) (Object) this).s1mp1e$renderGlass(matrices, mouseX, mouseY, delta)) {
                ci.cancel();
            }
            return;
        }

        // 1b) Villager trade buttons (the MerchantScreen's label-less 89x20 WidgetButtonPage ButtonWidgets): 26.2 draws
        //     them as glass capsules (AbstractButton.extractDefaultSprite -> glass, even inside a container screen) and
        //     slides them with the trade list mid-glide (MerchantGlide, armed by MerchantScrollGlassMixin only while the
        //     list glides). The trade items draw on top later (render's trade loop, item z 100), exactly as vanilla.
        if (s1mp1e$isTradeButton(self, mc)) {
            if (!self.visible) return;
            final int bx = self.x, by = self.y, bw = this.width, bh = this.height;
            final boolean gliding = MerchantGlide.handles(self);
            boolean over = !gliding && (this.hovered || self.isFocused());   // no hover lift on a row between positions
            Fade fade = s1mp1e$hoverFades.get(self);
            if (fade == null) {
                fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
                s1mp1e$hoverFades.put(self, fade);
            }
            fade.to(over ? 1f : 0f);
            final float lift = LIFT_ON * fade.value();
            final float opacity = this.alpha * ScreenOpenFade.value(mc.currentScreen);
            final boolean act = this.active;
            MerchantGlide.Painter painter = dy -> GlassRenderer.button(bx, by + dy, bx + bw, by + bh + dy,
                    1.0f, lift, opacity, act);
            if (gliding) MerchantGlide.render(self, painter);
            else painter.paint(0f);
            ci.cancel();
            return;
        }

        // 2) Only menu-screen PressableWidgets get the glass capsule. Container/recipe widgets are owned by the
        //    HandledScreen + recipe mixins; glassing them here would swallow their icons.
        if (!(self instanceof PressableWidget) || mc.currentScreen instanceof HandledScreen) {
            return;
        }

        if (!self.visible) return;

        int x = self.x, y = self.y, w = this.width, h = this.height;
        boolean over = this.hovered || self.isFocused();

        // Per-widget hover-lift ease (fresh entry starts settled to avoid a flash).
        Fade fade = s1mp1e$hoverFades.get(self);
        if (fade == null) {
            fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
            s1mp1e$hoverFades.put(self, fade);
        }
        fade.to(over ? 1f : 0f);
        float lift = LIFT_ON * fade.value();

        // Screen open/close opacity ramp: shared ScreenOpenFade, restarting on every screen change (identity
        // compare, so a resize's reused instance never restarts it). Endpoint 1.0 keeps a settled button's
        // opacity at the widget's own alpha.
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
            matrices.push();
            matrices.translate(cx, cy, 0f);
            matrices.scale(s, s, 1f);
            matrices.translate(-cx, -cy, 0f);
        }
        if (!s1mp1e$rollLabel(matrices, mc, self, x, y, w, h, textColor | a)) {
            DrawableHelper.drawCenteredText(matrices, mc.textRenderer, self.getMessage(),
                    x + w / 2, y + (h - 8) / 2, textColor | a);
        }
        if (s != 1f) matrices.pop();
        ci.cancel();
    }

    // ---- cycle buttons roll their value (26.2's CycleButtonRollMixin) ---------------------------------------------

    /** One label animator per cycle button (weak: dies with the widget). */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.WeakHashMap<ClickableWidget, dev.s1mp1e.glass.render.TypingAnim> s1mp1e$rollers =
            new java.util.WeakHashMap<>();

    /**
     * Cycle buttons ("Difficulty: Normal", "Clouds: Fancy", on/off toggles …) roll their value like an odometer when
     * it changes instead of swapping the label in one frame: the unchanged "Name: " stays put, the old value floats up
     * and fades, the new one rises in glyph by glyph, and the label re-centres by gliding. Drawn at exactly the
     * position/colour of the plain centred draw above; a label too wide for the button, an invisible button or any
     * failure falls back to it (returns false).
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean s1mp1e$rollLabel(MatrixStack matrices, MinecraftClient mc, ClickableWidget self,
                                            int x, int y, int w, int h, int color) {
        if (!(self instanceof net.minecraft.client.gui.widget.CyclingButtonWidget<?>)) return false;
        if ((color >>> 24) <= 1) return false;
        dev.s1mp1e.glass.render.TypingAnim label = s1mp1e$rollers.get(self);
        if (label == null) { label = new dev.s1mp1e.glass.render.TypingAnim(); s1mp1e$rollers.put(self, label); }
        if (label.broken) return false;
        net.minecraft.text.Text msg = self.getMessage();
        net.minecraft.text.OrderedText seq = msg.asOrderedText();
        int x0 = x + 2, x1 = x + w - 2;
        if (mc.textRenderer.getWidth(seq) > x1 - x0) return false;
        try {
            label.extractLabel(matrices, mc.textRenderer, msg.getString(), seq, x + w / 2, y + (h - 8) / 2, x0, x1, color, true);
            return true;
        } catch (Throwable t) {
            label.broken = true;
            System.out.println("[S1mp1e] cycle-button roll disabled: " + t);
            return false;
        }
    }
}
