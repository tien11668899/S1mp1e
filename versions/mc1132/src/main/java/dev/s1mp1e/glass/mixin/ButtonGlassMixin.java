package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glass capsule buttons for 1.13.2 (Legacy Fabric). 1.13.2's yarn is incomplete:
 * the widget base is {@code ButtonWidget} (no AbstractButtonWidget/ClickableWidget
 * yet), the render entry is the unmapped {@code method_891(int,int,float)} (pre-1.15,
 * no MatrixStack), there is NO {@code alpha} field (capsule opacity base is fixed
 * 1.0), the hover flag is the {@code hovered} field, and the label is the
 * {@code message} String field (no getMessage()). BTN program samples no backdrop,
 * so it draws on the title screen with no grab.
 *
 * <p><b>System 6 (button fade-in/out).</b> This mixin gains the two eased channels
 * the 1.16.5 {@code ButtonGlassMixin} added, adapted to the 1.13.2 tier, leaving
 * every settled endpoint byte-for-byte identical to the pre-fade line:
 *
 * <ul>
 *   <li><b>Hover lift.</b> The pre-fade line snapped the lift 0&rarr;0.81 on hover.
 *       Here a per-widget {@link Fade} eases the lift over {@link #HOVER_FADE_MS}
 *       (100 ms, the container hover-pill duration), so hover-in/out ramp instead of
 *       popping. Endpoints unchanged (0 resting, {@link #LIFT_ON} hovered). Keyed per
 *       {@code ButtonWidget} in a {@link WeakHashMap} so dead widgets evict
 *       themselves and coexisting buttons never cross-wire.</li>
 *   <li><b>Screen open/close opacity.</b> A single shared {@link Fade} eases the
 *       capsule opacity 0&rarr;1 over {@link #OPEN_FADE_MS} (150 ms — the
 *       {@code ScreenFade} cross-dissolve duration), restarting from 0 whenever
 *       {@code currentScreen} changes (identity compare, so a resize's reused
 *       instance never restarts it — no flash). The endpoint is 1.0, so a settled
 *       button's capsule opacity is still exactly the fixed 1.0 base.</li>
 * </ul>
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 fade edit</h3>
 * 1.16.5 multiplied the capsule opacity AND the label alpha by the widget's own
 * {@code alpha} field. 1.13.2 {@code ButtonWidget} has no {@code alpha} field, so the
 * opacity base is a literal 1.0 and the label stays fully opaque (the existing
 * no-alpha pattern); only the shared open fade and the per-widget hover fade animate.
 * "Hover or focused" collapses to {@code hovered} alone — 1.13.2 {@code ButtonWidget}
 * has no {@code isFocused()}.
 */
@Mixin(ButtonWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 -> 1-0x30/255 ~= 0.81). */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;
    /** Screen open/close opacity ease — the 150 ms ScreenFade cross-dissolve uses. */
    private static final float OPEN_FADE_MS = 150f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<ButtonWidget, Fade> s1mp1e$hoverFades =
            new WeakHashMap<ButtonWidget, Fade>();

    /** One shared capsule-opacity fade, restarted whenever the screen changes. */
    private static final Fade s1mp1e$openFade = new Fade(0f, OPEN_FADE_MS);
    /** The screen the opacity fade is currently keyed to (identity compare). */
    private static Screen s1mp1e$lastScreen;

    @Shadow public boolean active;
    @Shadow protected boolean hovered;
    @Shadow public int width;
    @Shadow public int height;
    @Shadow public int x;
    @Shadow public int y;
    @Shadow public boolean visible;
    @Shadow public String message;

    @Inject(method = "method_891", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;
        if (!this.visible) return;

        ButtonWidget self = (ButtonWidget) (Object) this;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean over = this.hovered;

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
        // settled button's capsule opacity at the fixed 1.0 base (no widget alpha
        // exists on 1.13.2 ButtonWidget).
        Screen screen = mc.currentScreen;
        if (screen != s1mp1e$lastScreen) {
            s1mp1e$lastScreen = screen;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
        }
        float opacity = 1.0f * s1mp1e$openFade.value();

        GlassRenderer.button(this.x, this.y, this.x + this.width, this.y + this.height,
                             1.0f, lift, opacity, this.active);

        // label on top, vanilla colouring (fully opaque — 1.13.2 has no widget alpha)
        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        int tw = mc.textRenderer.getStringWidth(this.message);
        mc.textRenderer.drawWithShadow(this.message,
                this.x + this.width / 2f - tw / 2f, this.y + (this.height - 8) / 2f,
                textColor | 0xFF000000);
        ci.cancel();
    }
}
