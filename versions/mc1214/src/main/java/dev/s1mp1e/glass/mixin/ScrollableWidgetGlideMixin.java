package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ListScrollGlide;
import net.minecraft.client.gui.widget.ScrollableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.4 half of {@code ListMotionMixin}'s smooth scrolling. 1.21.2 moved {@code mouseScrolled} and
 * {@code setScrollY} (1.21.1: {@code EntryListWidget.setScrollAmount}) up into {@code ScrollableWidget}, which every
 * list and multi-line text box extends. The wheel's single {@code setScrollY} is handed to the list's glide, and any
 * other set reports in so it can cancel the glide; widgets that are not lists ({@link ListScrollGlide}) keep vanilla's
 * behaviour exactly.
 */
@Mixin(ScrollableWidget.class)
public abstract class ScrollableWidgetGlideMixin {

    @Redirect(method = "mouseScrolled",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/ScrollableWidget;setScrollY(D)V"))
    private void s1mp1e$wheel(ScrollableWidget self, double requested) {
        if (self instanceof ListScrollGlide glide) glide.s1mp1e$glideTo(requested);
        else self.setScrollY(requested);
    }

    @Inject(method = "setScrollY", at = @At("HEAD"))
    private void s1mp1e$externalSet(double amount, CallbackInfo ci) {
        if ((Object) this instanceof ListScrollGlide glide) glide.s1mp1e$beforeSet();
    }
}
