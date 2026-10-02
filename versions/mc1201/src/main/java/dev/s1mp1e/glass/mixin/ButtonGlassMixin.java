package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.PressPulse;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every standard button becomes a translucent liquid-glass capsule — the 1.17.1
 * Fabric counterpart of the 1.8.9/1.12.2 GlassButtonPainter and 26.2's
 * ButtonGlassMixin. The BTN program is the capsule shader that samples NO
 * backdrop, so this works on the title screen with no grab. Vanilla's widget
 * sprite is cancelled; the label is redrawn on top, unchanged.
 *
 * <p>Fabric target: {@code ClickableWidget.renderButton(MatrixStack, int, int,
 * float)} at {@code @At("HEAD")}, {@code cancellable = true}. Signature and shadowed
 * members ({@code alpha}, {@code active}, {@code hovered}, {@code width},
 * {@code height}, plus {@code x/y/isFocused/visible/getMessage}) are identical to
 * 1.16.5 — buttons need NONE of the 1.17.1 core-profile matrix changes (no
 * {@code RenderSystem.pushMatrix}), and {@code DrawableHelper.drawCenteredText(
 * MatrixStack, TextRenderer, Text, int, int, int)} ({@code method_27534}) is
 * unchanged (verified against yarn 1.17.1+build.65). So this is a byte-for-byte
 * adaptation of the 1.16.5 fade version — no signature delta.
 *
 * <p>Knobs (measured off 26.2): corner 1.0 = full capsule, lift 0.81 when
 * hovered/focused (26.2's G=0x30 -> 1-0x30/255) else 0, opacity = the widget's
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
 *       shared {@link ScreenOpenFade} (also used by the glass option sliders) eases the
 *       capsule opacity 0&rarr;1 over 150 ms (the same duration {@code ScreenFade}'s
 *       cross-dissolve uses), restarting from 0 whenever {@code currentScreen}
 *       changes, so a newly opened screen's buttons fade in while the outgoing
 *       frame dissolves out and the screen close reads as the buttons fading with
 *       it. The endpoint is 1.0, so a settled button's opacity is still exactly the
 *       widget's alpha — only the transition eases. A window resize reuses the same
 *       {@code Screen} instance, so it does not restart the fade (no flash), matching
 *       the resize guard the screen-dissolve port uses.</li>
 * </ul>
 *
 * <p><b>1.17.1 status: FULLY FUNCTIONAL.</b> The capsule + both fade channels draw
 * through the core-legal {@link GlassRenderer#button}. Note the opacity channel
 * references {@code ScreenFade}'s 150 ms only as a shared timing constant
 * (150 ms); it does NOT call {@code ScreenFade}, so the button fade
 * works even though {@code ScreenFade}'s own dissolve draw is stubbed on 1.17.1.
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
    // 1.20: renderButton is ABSTRACT on ClickableWidget (the concrete draw moved to
    // subclasses), so @Inject HEAD of it NPEs ("insnNode is null" — no body). Instead
    // @Redirect the renderButton INVOKE inside the CONCRETE render(); @Shadow the
    // abstract method (own declared method of ClickableWidget) for the no-glass
    // fallback, which virtual-dispatches to the subclass impl.
    @Shadow protected abstract void renderButton(DrawContext context, int mouseX, int mouseY, float delta);

    /** A villager trade button: the MerchantScreen's label-less 88x20 ButtonWidgets (WidgetButtonPage). */
    private static boolean s1mp1e$isTradeButton(ClickableWidget w) {
        return w instanceof net.minecraft.client.gui.widget.ButtonWidget
                && w.getWidth() == 88 && w.getHeight() == 20
                && MinecraftClient.getInstance().currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen;
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/ClickableWidget;"
                            + "renderButton(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
    private void s1mp1e$glassButton(ClickableWidget self, DrawContext context,
                                    int mouseX, int mouseY, float delta) {
        if (dev.s1mp1e.client.gui.SettingsShell.suppresses(self)) return;   // a settings-page row stands in for it
        // Villager trade buttons (the 88x20 label-less ButtonWidgets of the MerchantScreen): 26.2 draws them as faint
        // glass capsules (even inside a container screen) and slides them with the trade list mid-glide (MerchantGlide,
        // armed by MerchantScrollGlassMixin only while the list glides).
        if (s1mp1e$isTradeButton(self)) {
            final boolean glass = GlassProgram.ensureReady() && GlassProgram.btnUsable();
            final int bx = self.getX(), by = self.getY(), bw = this.width, bh = this.height;
            dev.s1mp1e.client.gui.MerchantGlide.Painter painter;
            if (glass) {
                boolean over = this.hovered || self.isFocused();
                Fade fade = s1mp1e$hoverFades.get(self);
                if (fade == null) {
                    fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
                    s1mp1e$hoverFades.put(self, fade);
                }
                fade.to(over ? 1f : 0f);
                final float lift = LIFT_ON * fade.value();
                final float opacity = this.alpha * ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
                final boolean act = this.active;
                context.draw();   // land the panel batch first; the capsule is immediate GL (absolute coords)
                painter = dy -> GlassRenderer.button(bx, by + dy, bx + bw, by + bh + dy, 1.0f, lift, opacity, act);
            } else {
                painter = dy -> {
                    context.getMatrices().push();
                    context.getMatrices().translate(0f, dy, 0f);
                    this.renderButton(context, mouseX, mouseY, delta);
                    context.getMatrices().pop();
                };
            }
            if (dev.s1mp1e.client.gui.MerchantGlide.handles(self)) {
                dev.s1mp1e.client.gui.MerchantGlide.render(self, context, painter);
            } else {
                painter.paint(0f);
            }
            return;
        }
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) {
            this.renderButton(context, mouseX, mouseY, delta);
            return;
        }
        // Icon buttons that paint their OWN texture instead of the default button background: 26.2 / 1.21.1 only glass
        // the default background, so these stay vanilla rather than becoming an empty capsule that shows the
        // narration text. In 1.20.1 that is the book page-turn arrows (swapped for chevrons by SfIconMixin), every
        // TexturedButtonWidget (the title screen's language / accessibility buttons, the recipe-book button — their
        // texture IS the whole button, there is no separate icon to put on a capsule), the checkbox (its texture is
        // swapped for the iOS circle by SfIconMixin) and the difficulty lock.
        if (self instanceof net.minecraft.client.gui.widget.PageTurnWidget
                || self instanceof net.minecraft.client.gui.widget.TexturedButtonWidget
                || self instanceof net.minecraft.client.gui.widget.CheckboxWidget
                || self instanceof net.minecraft.client.gui.widget.LockButtonWidget) {
            this.renderButton(context, mouseX, mouseY, delta);
            return;
        }

        // 1.20 catches EVERY ClickableWidget (this @Redirect is on the renderButton
        // INVOKE inside ClickableWidget.render). The recipe book's category tabs
        // (RecipeGroupButtonWidget), result cells (AnimatedResultButton) and the
        // open/close toggle (ToggleButtonWidget) are all ClickableWidgets — glassing
        // them here would SWALLOW their whole renderButton, taking the item icons with
        // it. Any widget inside a container/inventory/crafting screen is owned by the
        // HandledScreen + recipe mixins, so run vanilla renderButton for it (its own
        // glass, if any, comes from those mixins' inner redirects). ButtonGlassMixin
        // only glasses menu-screen buttons (title / options / pause).
        if (!(self instanceof net.minecraft.client.gui.widget.PressableWidget)
                || MinecraftClient.getInstance().currentScreen
                        instanceof net.minecraft.client.gui.screen.ingame.HandledScreen) {
            this.renderButton(context, mouseX, mouseY, delta);
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        // 1.20: ClickableWidget x/y are private; read via the Widget getters.
        int x = self.getX(), y = self.getY(), w = this.width, h = this.height;
        boolean over = this.hovered || self.isFocused();

        // Per-widget hover-lift ease (fresh entry starts settled to avoid a flash).
        Fade fade = s1mp1e$hoverFades.get(self);
        if (fade == null) {
            fade = new Fade(over ? 1f : 0f, HOVER_FADE_MS);
            s1mp1e$hoverFades.put(self, fade);
        }
        fade.to(over ? 1f : 0f);
        float lift = LIFT_ON * fade.value();

        // Screen open/close opacity ramp: ScreenOpenFade, shared with the glass option
        // sliders so both restart together (identity compare, so a resize's reused
        // instance never restarts it). Endpoint 1.0 keeps a settled button's opacity
        // at the widget's own alpha, exactly the Forge port.
        float opacity = this.alpha * ScreenOpenFade.value(mc.currentScreen);

        // Tap-feedback pulse (26.2's ButtonPressPulseMixin): on activation the whole button — glass capsule AND label —
        // dips to ~95% around its centre and springs back over ~0.25 s. ButtonPressMixin stamps the press time; here the
        // scale is 1.0 (no-op) unless recently pressed. The capsule is raw-GL absolute coords (scaled by hand about the
        // centre); the label follows the same scale via the DrawContext matrix so the two stay locked together.
        float s = PressPulse.scale(self);
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
            context.getMatrices().push();
            context.getMatrices().translate(cx, cy, 0f);
            context.getMatrices().scale(s, s, 1f);
            context.getMatrices().translate(-cx, -cy, 0f);
        }
        if (!s1mp1e$rollLabel(context, mc, self, x, y, w, h, textColor | a)) {
            context.drawCenteredTextWithShadow(mc.textRenderer, self.getMessage(),
                    x + w / 2, y + (h - 8) / 2, textColor | a);
        }
        if (s != 1f) context.getMatrices().pop();
    }

    // ---- cycle buttons roll their value (26.2's CycleButtonRollMixin) ---------------------------------------------

    /** One label animator per cycle button (weak: dies with the widget). */
    @Unique
    private static final java.util.WeakHashMap<ClickableWidget, dev.s1mp1e.glass.render.TypingAnim> s1mp1e$rollers =
            new java.util.WeakHashMap<>();

    /**
     * Cycle buttons ("Difficulty: Normal", "Clouds: Fancy", on/off toggles …) roll their value like an odometer when
     * it changes instead of swapping the label in one frame: the unchanged "Name: " stays put, the old value floats up
     * and fades, the new one rises in glyph by glyph, and the label re-centres by gliding. Drawn at exactly the
     * position/colour/shadow of the plain centred draw above; a label too wide for the button, an invisible button or
     * any failure falls back to it (returns false).
     */
    @Unique
    private static boolean s1mp1e$rollLabel(DrawContext context, MinecraftClient mc, ClickableWidget self,
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
            label.extractLabel(context, mc.textRenderer, msg.getString(), seq, x + w / 2, y + (h - 8) / 2, x0, x1, color, true);
            return true;
        } catch (Throwable t) {
            label.broken = true;
            System.out.println("[S1mp1e] cycle-button roll disabled: " + t);
            return false;
        }
    }
}
