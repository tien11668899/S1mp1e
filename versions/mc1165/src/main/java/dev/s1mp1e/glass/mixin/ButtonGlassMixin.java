package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.ui.GlassSliderPainter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every standard button becomes a translucent liquid-glass capsule — the 1.16.5
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
 * to {@code SliderGlassMixin} via the {@link GlassSliderPainter} duck (1.16.5's
 * {@code SliderWidget} has no {@code renderButton} to inject); non-{@link PressableWidget}
 * widgets (textured buttons, checkboxes, text fields) stay vanilla; and buttons inside a
 * {@link HandledScreen} (container/inventory) stay vanilla so the panel owns the look.
 */
@Mixin(ClickableWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 → 1-0x30/255 ≈ 0.81). */
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

    /** A villager trade button: the MerchantScreen's label-less 89x20 ButtonWidgets (WidgetButtonPage — the class is
     *  package-private, so it is recognised by shape + screen, like the mc1171 sibling). */
    private static boolean s1mp1e$isTradeButton(ClickableWidget w, MinecraftClient mc) {
        return w instanceof net.minecraft.client.gui.widget.ButtonWidget
                && w.getWidth() == 89 && w.getHeight() == 20
                && mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen;
    }

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(MatrixStack matrices, int mouseX, int mouseY,
                                    float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // Vanilla option sliders: 1.16.5 SliderWidget has no renderButton to inject, so
        // SliderGlassMixin paints via this duck. Cancel vanilla only if it painted.
        if ((Object) this instanceof SliderWidget) {
            if (((GlassSliderPainter) (Object) this).s1mp1e$paintGlass(matrices, mouseX, mouseY, delta)) {
                ci.cancel();
            }
            return;
        }
        // Villager trade buttons (the MerchantScreen's label-less 89x20 WidgetButtonPage ButtonWidgets): 26.2 draws
        // them as faint glass capsules (AbstractButton.extractDefaultSprite -> glass, even inside a container screen)
        // and slides them with the trade list mid-glide (MerchantGlide, armed by MerchantScrollGlassMixin only while
        // the list glides — feature D). The trade items draw on top later (render's trade loop, item z 100), exactly
        // as over the vanilla sprite. (mc1171 sibling port.)
        if (s1mp1e$isTradeButton((ClickableWidget) (Object) this, mc)) {
            final ClickableWidget tb = (ClickableWidget) (Object) this;
            if (!tb.visible) return;
            final int bx = tb.x, by = tb.y, bw = this.width, bh = this.height;
            final boolean gliding = MerchantGlide.handles(tb);
            boolean over = !gliding && (this.hovered || tb.isFocused());   // no hover lift on a row between positions
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
        if (!((Object) this instanceof PressableWidget)) return;
        // Inside a container/inventory screen the panel owns the look; buttons stay vanilla.
        if (mc.currentScreen instanceof HandledScreen) return;

        ClickableWidget self = (ClickableWidget) (Object) this;
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

        // Screen open/close opacity ramp — the shared ScreenOpenFade, keyed to the current
        // screen instance so buttons AND the sliders on the same screen fade in together and a
        // window resize (same instance) never restarts it. Endpoint 1.0 keeps a settled
        // button's opacity at the widget's own alpha, exactly the Forge port.
        float opacity = this.alpha * ScreenOpenFade.value(mc.currentScreen);

        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, this.active);

        // label on top, vanilla colouring
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        int a = Math.round(this.alpha * 255f) << 24;
        DrawableHelper.drawCenteredText(matrices, mc.textRenderer, self.getMessage(),
                x + w / 2, y + (h - 8) / 2, textColor | a);
        ci.cancel();
    }
}
