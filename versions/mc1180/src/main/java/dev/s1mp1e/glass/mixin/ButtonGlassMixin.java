package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.GlassSliderHook;
import dev.s1mp1e.client.gui.ScreenOpenFade;
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
 * Every standard menu button becomes a translucent liquid-glass capsule — the 1.18.2 Fabric counterpart of the
 * 1.8.9/1.12.2 GlassButtonPainter and 26.2's ButtonGlassMixin. The BTN program is the capsule shader that samples
 * NO backdrop, so this works on the title screen with no grab. Vanilla's widget sprite is cancelled; the label is
 * redrawn on top, unchanged.
 *
 * <p>Fabric target: {@code ClickableWidget.renderButton(MatrixStack, int, int, float)} at {@code @At("HEAD")},
 * {@code cancellable = true}. On 1.18.2 {@code renderButton} is concrete on {@code ClickableWidget} (in 1.20 it
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

        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, this.active);

        // label on top, vanilla colouring
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        int a = Math.round(this.alpha * 255f) << 24;
        DrawableHelper.drawCenteredText(matrices, mc.textRenderer, self.getMessage(),
                x + w / 2, y + (h - 8) / 2, textColor | a);
        ci.cancel();
    }
}
