package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every standard button becomes a translucent liquid-glass capsule — the 1.14.4
 * Fabric counterpart of the 1.8.9/1.12.2 GlassButtonPainter and 26.2's
 * ButtonGlassMixin. The BTN program is the capsule shader that samples NO backdrop,
 * so this works on the title screen with no grab. Vanilla's widget sprite is
 * cancelled; the label is redrawn on top, unchanged.
 *
 * <p>Knobs (measured off 26.2): corner 1.0 = full capsule, lift 0.81 when
 * hovered/focused (26.2's G=0x30 -> 1-0x30/255) else 0, opacity = the widget's own
 * alpha, and {@code enabled=false} lets the shader dim to 0.4.
 *
 * <p>System 6 (button fade-in/out) — two eased channels ported byte-for-byte from
 * 1.16.5, both adding animation the Forge line lacked while leaving every settled
 * endpoint identical:
 * <ul>
 *   <li><b>Hover lift.</b> A per-widget {@link Fade} eases the lift 0&harr;0.81 over
 *       {@link #HOVER_FADE_MS} (100 ms) so hover-in/out ramps instead of popping.
 *       Keyed per widget in a {@link WeakHashMap} so dead widgets evict themselves.</li>
 *   <li><b>Screen open/close opacity.</b> A single shared {@link Fade} eases the
 *       capsule opacity 0&rarr;1 over {@link #OPEN_FADE_MS} (150 ms), restarting from
 *       0 whenever {@code currentScreen} changes (identity compare, so a resize's
 *       reused instance never restarts it — no flash). Endpoint 1.0, so a settled
 *       button's opacity is exactly the widget's alpha.</li>
 * </ul>
 *
 * <p><b>Tier notes (1.14.4).</b> No MatrixStack (1.15+): {@code renderButton(int,int,
 * float)} ({@code renderButton(IIF)V}) takes no MatrixStack, so the handler drops it.
 * The base widget is {@code AbstractButtonWidget} (class_339, pre-{@code ClickableWidget}
 * rename); {@code hovered}/{@code isFocused()} are the fields {@code isHovered} +
 * {@code focused} here. {@code getMessage()} returns {@code String} at this tier, and
 * {@code TextRenderer} has only String overloads, so the label is centred manually via
 * {@code getStringWidth}/{@code drawWithShadow(String,...)} — unchanged from the
 * existing tier-correct core.
 */
@Mixin(AbstractButtonWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 -> 1-0x30/255 ~= 0.81). */
    private static final float LIFT_ON = 0.81f;
    /** Hover ease duration — the container hover-pill fade (HOVER_FADE_S 0.10). */
    private static final float HOVER_FADE_MS = 100f;
    /** Screen open/close opacity ease — the 150 ms ScreenFade cross-dissolve uses. */
    private static final float OPEN_FADE_MS = 150f;

    /** Per-widget hover-lift fade; WeakHashMap auto-evicts discarded widgets. */
    private static final WeakHashMap<AbstractButtonWidget, Fade> s1mp1e$hoverFades =
            new WeakHashMap<AbstractButtonWidget, Fade>();

    /** One shared capsule-opacity fade, restarted whenever the screen changes. */
    private static final Fade s1mp1e$openFade = new Fade(0f, OPEN_FADE_MS);
    /** The screen the opacity fade is currently keyed to (identity compare). */
    private static Screen s1mp1e$lastScreen;

    @Shadow protected float alpha;
    @Shadow public boolean active;
    @Shadow protected boolean isHovered;
    @Shadow protected boolean focused;
    @Shadow protected int width;
    @Shadow protected int height;

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(int mouseX, int mouseY,
                                    float delta, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;

        AbstractButtonWidget self = (AbstractButtonWidget) (Object) this;
        if (!self.visible) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        int x = self.x, y = self.y, w = this.width, h = this.height;
        boolean over = this.isHovered || this.focused;

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
        String s1mp1e$label = self.getMessage();
        int s1mp1e$tw = mc.textRenderer.getStringWidth(s1mp1e$label);
        mc.textRenderer.drawWithShadow(s1mp1e$label,
                x + w / 2f - s1mp1e$tw / 2f, y + (h - 8) / 2f, textColor | a);
        ci.cancel();
    }
}
