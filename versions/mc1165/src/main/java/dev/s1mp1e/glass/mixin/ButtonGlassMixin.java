package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
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
 *       the screen (opacity = the widget's own alpha, no ramp). Here a single
 *       shared {@link Fade} eases the capsule opacity 0&rarr;1 over
 *       {@link #OPEN_FADE_MS} (150 ms — the same duration {@code ScreenFade}'s
 *       cross-dissolve uses), restarting from 0 whenever {@code currentScreen}
 *       changes, so a newly opened screen's buttons fade in while the outgoing
 *       frame dissolves out and the screen close reads as the buttons fading with
 *       it. The endpoint is 1.0, so a settled button's opacity is still exactly the
 *       widget's alpha — only the transition eases. A window resize reuses the same
 *       {@code Screen} instance, so it does not restart the fade (no flash), matching
 *       the resize guard the screen-dissolve port uses.</li>
 * </ul>
 */
@Mixin(ClickableWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 → 1-0x30/255 ≈ 0.81). */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;
    /** Screen open/close opacity ease — the 150 ms ScreenFade cross-dissolve uses. */
    private static final float OPEN_FADE_MS = 150f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<ClickableWidget, Fade> s1mp1e$hoverFades =
            new WeakHashMap<ClickableWidget, Fade>();

    /** One shared capsule-opacity fade, restarted whenever the screen changes. */
    private static final Fade s1mp1e$openFade = new Fade(0f, OPEN_FADE_MS);
    /** The screen the opacity fade is currently keyed to (identity compare). */
    private static Screen s1mp1e$lastScreen;

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
        if (!self.visible) return;

        MinecraftClient mc = MinecraftClient.getInstance();
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

        // Screen open/close opacity ramp. Restart the shared fade from 0 on every
        // screen change (identity compare, so a resize's reused instance never
        // restarts it), then ease 0->1 over OPEN_FADE_MS. Endpoint 1.0 keeps a
        // settled button's opacity at the widget's own alpha, exactly the Forge port.
        Screen screen = mc.currentScreen;
        if (screen != s1mp1e$lastScreen) {
            s1mp1e$lastScreen = screen;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
        }
        float opacity = this.alpha * s1mp1e$openFade.value();

        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, opacity, this.active);

        // label on top, vanilla colouring
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        int a = Math.round(this.alpha * 255f) << 24;
        DrawableHelper.drawCenteredText(matrices, mc.textRenderer, self.getMessage(),
                x + w / 2, y + (h - 8) / 2, textColor | a);
        ci.cancel();
    }
}
