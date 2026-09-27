package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.ui.SliderGlassHost;
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
 * Glass capsule buttons for 1.13.2 (Legacy Fabric). 1.13.2's yarn is incomplete:
 * the widget base is {@code ButtonWidget} (no AbstractButtonWidget/ClickableWidget
 * yet), the render entry is the unmapped {@code method_891(int,int,float)} (pre-1.15,
 * no MatrixStack), there is NO {@code alpha} field (capsule opacity base is fixed
 * 1.0), the hover flag is the {@code hovered} field, and the label is the
 * {@code message} String field (no getMessage()). BTN program samples no backdrop,
 * so it draws on the title screen with no grab.
 *
 * <h3>Order of operations (HEAD of {@code method_891}, cancellable)</h3>
 * <ol>
 *   <li>Invisible → return (vanilla returns too).</li>
 *   <li><b>Hover, recomputed and STORED.</b> Vanilla computes {@code hovered} INSIDE
 *       {@code method_891} ({@code putfield} at offset 86, javap-verified), i.e. after this
 *       HEAD. Reading the field here used to see last frame's value — and since the cancel
 *       below skips vanilla's store, it was stale forever (the hover lift never animated and
 *       {@code isHovered()} lied). The exact vanilla test is evaluated and written back first,
 *       so everything downstream (lift, slider skin, {@code isHovered()}) sees this frame's
 *       hover, exactly as if vanilla had run.</li>
 *   <li>No glass pipeline → vanilla.</li>
 *   <li>Inside a {@link HandledScreen} → vanilla: the container's glass panel owns the look
 *       there (beacon, merchant, recipe-book buttons…), the same skip mc1201/mc1144 make.</li>
 *   <li>Vanilla sliders ({@code OptionSliderWidget}, {@code SoundsScreen$SoundButtonWidget}) →
 *       the {@link SliderGlassHost} duck. Neither overrides {@code method_891}, and their
 *       {@code renderBg} holds both the knob AND the drag update, so this HEAD cancel used to
 *       leave a knob-less capsule that could not be dragged. Now the slider paints its full glass
 *       skin (running vanilla {@code renderBg} itself) and we cancel, or it draws completely
 *       vanilla.</li>
 *   <li>{@link DelegatingRealmsButtonWidget} → vanilla: it overrides {@code renderBg} to forward
 *       to the Realms button, which must run.</li>
 *   <li>Everything else → the glass capsule + label, and vanilla is cancelled.</li>
 * </ol>
 *
 * <p><b>Button fade-in/out.</b> Two eased channels:
 * <ul>
 *   <li><b>Hover lift.</b> A per-widget {@link Fade} eases the lift over
 *       {@link #HOVER_FADE_MS} (100 ms, the container hover-pill duration), so hover-in/out
 *       ramp instead of popping. Endpoints 0 resting, {@link #LIFT_ON} hovered. Keyed per
 *       {@code ButtonWidget} in a {@link WeakHashMap} so dead widgets evict themselves.</li>
 *   <li><b>Screen open opacity.</b> The SHARED {@link ScreenOpenFade} (linear 0&rarr;1 over
 *       150 ms, keyed to the current screen instance), the same one the glass sliders read, so
 *       buttons and sliders on one screen fade in together — a private per-mixin "last screen"
 *       let a slider that had not been painted in between pop in at full opacity. A resize
 *       reuses the instance, so it never restarts (no flash). Endpoint 1.0.</li>
 * </ul>
 *
 * <p>1.13.2 {@code ButtonWidget} has no {@code alpha} field, so the opacity base is 1.0 and
 * the label is fully opaque (mc1144's label uses only the widget alpha, which is 1 here);
 * "hover or focused" collapses to {@code hovered} — there is no focus flag. Any failure falls
 * back to vanilla (not cancelled), logged once.
 */
@Mixin(ButtonWidget.class)
public abstract class ButtonGlassMixin {

    /** 26.2's hovered lift (G=0x30 -> 1-0x30/255 ~= 0.81). */
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

        // 2. vanilla's exact hover test, stored BEFORE any early return or cancel (vanilla only
        //    computes it after this HEAD, at offset 86 — without this the flag is always stale)
        this.hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;

        try {
            // 3. no glass pipeline -> vanilla
            if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;

            // 4. inside a container screen the glass panel owns the look -> vanilla widgets
            if (mc.currentScreen instanceof HandledScreen) return;

            Object self = this;

            // 5. vanilla sliders paint their own glass skin (or fall back to fully vanilla)
            if (self instanceof SliderGlassHost) {
                if (((SliderGlassHost) self).s1mp1e$paintSkin(mouseX, mouseY)) ci.cancel();
                return;
            }

            // 6. the Realms delegate overrides renderBg, which must run -> vanilla
            if (self instanceof DelegatingRealmsButtonWidget) return;

            // 7. glass capsule
            ButtonWidget widget = (ButtonWidget) self;
            boolean over = this.hovered;

            // Per-widget hover-lift ease (fresh entry starts settled to avoid a flash).
            Fade fade = s1mp1e$hoverFades.get(widget);
            if (fade == null) {
                fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
                s1mp1e$hoverFades.put(widget, fade);
            }
            fade.to(over ? 1f : 0f);
            float lift = LIFT_ON * fade.value();

            // Shared screen-open ramp (no widget alpha on 1.13.2, so the base is 1.0).
            float opacity = ScreenOpenFade.value(mc.currentScreen);

            GlassRenderer.button(this.x, this.y, this.x + this.width, this.y + this.height,
                                 1.0f, lift, opacity, this.active);

            // label on top, vanilla colouring (fully opaque — 1.13.2 has no widget alpha)
            String label = this.message;
            if (label != null && mc.textRenderer != null) {
                int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
                int tw = mc.textRenderer.getStringWidth(label);
                mc.textRenderer.drawWithShadow(label,
                        this.x + this.width / 2f - tw / 2f, this.y + (this.height - 8) / 2f,
                        textColor | 0xFF000000);
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
}
