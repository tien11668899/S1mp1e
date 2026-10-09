package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TabButtonWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Liquid-glass tabs — the 1.21.1 counterpart of 26.2's {@code TabGlassMixin} (Video Settings' tabs, the create-world
 * tabs, and every other {@link TabButtonWidget}).
 *
 * <p>A {@code TabButtonWidget} extends {@code ClickableWidget} directly (NOT {@code PressableWidget}), so
 * {@link ButtonGlassMixin} never reaches it and the tabs kept vanilla's square {@code widget/tab*} sprites — plus, for
 * the current tab, a rectangular menu-background block — i.e. a square highlight sitting next to the rounded glass
 * buttons. Verified with javap: {@code TabButtonWidget.renderWidget} makes exactly one
 * {@code drawGuiTexture(Identifier,IIII)} call (the tab sprite) and one {@code renderBackgroundTexture(DrawContext,IIII)}
 * call for the current tab.
 *
 * <p>Each tab is now a rounded glass capsule on the BTN program (the same knobs as {@link ButtonGlassMixin}: corner 1.0
 * = full capsule, an eased hover/select lift, opacity from {@link ScreenOpenFade}):
 * <ul>
 *   <li>current tab → full lift ({@link #LIFT_SELECTED}, the buttons' hovered brightness);</li>
 *   <li>hovered but not current → a lighter lift ({@link #LIFT_HOVER}) so the current tab stays brightest;</li>
 *   <li>resting → faint glass, like a resting button.</li>
 * </ul>
 * The lift eases per tab over {@link #FADE_MS} (100 ms, the buttons' hover fade), so switching / hovering fades instead
 * of popping, and the capsule opacity eases in over the shared 150 ms {@link ScreenOpenFade} when a screen opens. The
 * square menu-background block is suppressed while glass draws; vanilla's 1px underline under the current tab's label is
 * kept and sits just below the pill (the capsule stops 3px above the tab bottom). Falls back to the vanilla sprite when
 * the glass pipeline is unavailable.
 */
@Mixin(TabButtonWidget.class)
public abstract class TabButtonGlassMixin {

    /** Current tab: the same lift a hovered button gets (ButtonGlassMixin.LIFT_ON). */
    private static final float LIFT_SELECTED = 0.81f;
    /** Hovered, not current: visibly lit, but below the current tab. */
    private static final float LIFT_HOVER = 0.5f;
    /** Tab lift ease — the buttons' 100 ms hover fade. */
    private static final float FADE_MS = 100.0f;

    /** Per-tab lift fade; WeakHashMap auto-evicts discarded tabs. Render thread only. */
    private static final WeakHashMap<TabButtonWidget, Fade> s1mp1e$tabFades = new WeakHashMap<>();

    @Redirect(method = "renderWidget",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassTab(DrawContext context, Identifier sprite, int x, int y, int w, int h) {
        TabButtonWidget self = (TabButtonWidget) (Object) this;
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) {
            context.drawGuiTexture(sprite, x, y, w, h);
            return;
        }

        // Lift: current > hovered > resting, eased per tab (retargeted from the LIVE value so it never pops).
        float target = self.isCurrentTab() ? LIFT_SELECTED : ((self.isHovered() || self.isFocused()) ? LIFT_HOVER : 0.0f);
        Fade fade = s1mp1e$tabFades.get(self);
        if (fade == null) {
            fade = new Fade(target, FADE_MS);   // start settled: a freshly built screen doesn't flash
            s1mp1e$tabFades.put(self, fade);
        } else if (fade.target() != target) {
            fade.snap(fade.value());
            fade.to(target);
        }
        float lift = fade.value();

        // Opacity eases in with the screen — shared with the glass buttons/sliders so a return to the same screen
        // restarts them all together. Tabs draw without a tint, so this is the whole capsule opacity.
        float opacity = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
        // segmented-control emphasis (the underline is gone): resting tabs faint, hovered half-lit, current full
        opacity *= self.isCurrentTab() ? 1.0f : ((self.isHovered() || self.isFocused()) ? 0.7f : 0.4f);

        if (dev.s1mp1e.client.gui.SegmentedTabs.active) {
            // Inside the segmented capsule (SegmentedTabNavMixin): the sliding pill marks the selection, so don't draw a
            // per-tab capsule — only a faint glow on a hovered non-current tab.
            if (self.isCurrentTab() || !(self.isHovered() || self.isFocused())) return;
            opacity *= 0.35f;
            lift = 1.0f;
        }
        // Land the deferred menu batch first so the immediate-GL capsule composites over it (layering trap).
        context.draw();
        GlassRenderer.button(x + 2, y + 2, x + w - 2, y + h - 3, 1.0f, lift, opacity, true);
    }

    /** The current tab's rectangular menu-background block would sit square inside the rounded pill — drop it. */
    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noSquareTabBackground(DrawContext context, int x0, int y0, int x1, int y1, CallbackInfo ci) {
        if (GlassProgram.ensureReady() && GlassProgram.btnUsable()) {
            ci.cancel();
        }
    }

    /** No underline under the current tab: the brightest capsule is the selection (Apple segmented control). */
    @Inject(method = "drawCurrentTabLine", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noUnderline(DrawContext context, net.minecraft.client.font.TextRenderer tr, int color, CallbackInfo ci) {
        if (GlassProgram.ensureReady() && GlassProgram.btnUsable()) ci.cancel();
    }

    /**
     * Every tab label at the CURRENT tab's height. Vanilla's drawMessage starts an unselected label 3 px lower
     * ({@code isCurrentTab() ? 0 : 3}), so inside the one segmented capsule the other labels sat lower than the chosen one.
     * That offset is the only use of isCurrentTab() in drawMessage.
     */
    @Redirect(method = "drawMessage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/widget/TabButtonWidget;isCurrentTab()Z"), require = 0)
    private boolean s1mp1e$labelAtCurrentHeight(TabButtonWidget self) {
        return true;
    }
}
