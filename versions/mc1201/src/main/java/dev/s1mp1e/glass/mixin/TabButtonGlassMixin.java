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
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Liquid-glass tabs — the 1.20.1 port of the 1.21.1 line's {@code TabButtonGlassMixin} (26.2's {@code TabGlassMixin}):
 * the create-world tabs and every other {@link TabButtonWidget}.
 *
 * <p>A {@code TabButtonWidget} extends {@code ClickableWidget} directly (NOT {@code PressableWidget}), so
 * {@link ButtonGlassMixin} never reaches it and the tabs kept vanilla's square tab texture next to the rounded glass
 * buttons. Decompiled 1.20.1 {@code TabButtonWidget.renderButton}: exactly one
 * {@code drawNineSlicedTexture(TEXTURE, x, y, w, h, 2, 2, 2, 0, 130, 24, 0, v)} (the tab body; 1.20.2+ draws a sprite
 * and, for the current tab, a menu-background block that does not exist here), then the label and — for the current
 * tab — vanilla's 1 px underline. Only the body blit is replaced.
 *
 * <p>Each tab is a rounded glass capsule on the BTN program (the same knobs as {@link ButtonGlassMixin}: corner 1.0 =
 * full capsule, an eased hover/select lift, opacity from {@link ScreenOpenFade}): current tab → full lift
 * ({@link #LIFT_SELECTED}); hovered but not current → a lighter lift ({@link #LIFT_HOVER}); resting → faint glass.
 * The lift eases per tab over {@link #FADE_MS}, so switching / hovering fades instead of popping. The underline is kept
 * and sits just below the pill (the capsule stops 3 px above the tab bottom). Falls back to the vanilla texture when
 * the glass pipeline is unavailable.
 */
@Mixin(TabButtonWidget.class)
public abstract class TabButtonGlassMixin {

    private static final float LIFT_SELECTED = 0.81f;
    private static final float LIFT_HOVER = 0.5f;
    private static final float FADE_MS = 100.0f;

    /** Per-tab lift fade; WeakHashMap auto-evicts discarded tabs. Render thread only. */
    private static final WeakHashMap<TabButtonWidget, Fade> s1mp1e$tabFades = new WeakHashMap<>();

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawNineSlicedTexture("
                            + "Lnet/minecraft/util/Identifier;IIIIIIIIIIII)V"))
    private void s1mp1e$glassTab(DrawContext context, Identifier texture, int x, int y, int w, int h,
                                 int left, int top, int right, int bottom, int centerW, int centerH, int u, int v) {
        TabButtonWidget self = (TabButtonWidget) (Object) this;
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) {
            context.drawNineSlicedTexture(texture, x, y, w, h, left, top, right, bottom, centerW, centerH, u, v);
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
        float opacity = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);

        // Land the queued menu batch first so the immediate-GL capsule composites over it (layering trap).
        context.draw();
        GlassRenderer.button(x + 2, y + 2, x + w - 2, y + h - 3, 1.0f, lift, opacity, true);
    }
}
