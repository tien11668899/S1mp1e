package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #1 for multi-line text areas (every {@code ScrollableWidget}). 1.19.2 {@code drawScrollbar()} takes no
 * {@link MatrixStack} and draws a grey knob (no track) with RAW {@code Tessellator} quads (colours 128/192) just right
 * of the box ({@code x+w .. x+w+8}). HEAD-cancel it and draw the macOS overlay scroller ({@link AppleScroller}) at
 * exactly vanilla's knob rect (same {@code thumbHeight} / {@code scrollY} maths) so dragging stays 1:1; it is only
 * reached when the content overflows, so no "fits" guard is needed. Falls back to vanilla when the glass button
 * program is unavailable.
 */
@Mixin(ScrollableWidget.class)
public abstract class ScrollableWidgetScrollerMixin {

    @Shadow protected abstract double getScrollY();
    @Shadow protected abstract int getMaxScrollY();
    @Shadow private boolean scrollbarDragged;

    @Inject(method = "drawScrollbar", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$overlay(CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
        ci.cancel();
        ClickableWidget w = (ClickableWidget) (Object) this;
        int h = w.getHeight();
        int thumbH = MathHelper.clamp((int) ((float) (h * h) / (float) (getMaxScrollY() + h)), 32, h);
        int maxY = getMaxScrollY();
        int top = maxY <= 0 ? w.y : Math.max(w.y, (int) getScrollY() * (h - thumbH) / maxY + w.y);
        float right = w.x + w.getWidth() + 8;
        AppleScroller.draw(new MatrixStack(), this, right, w.y, w.y + h, top, thumbH,
                getScrollY(), w.isHovered(), this.scrollbarDragged, 1.0F);
    }
}
