package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ScrollableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #2 multi-line text areas — the telemetry event list ({@code ScrollableTextWidget}), multi-line edit boxes
 * ({@code EditBoxWidget}) and every other {@code ScrollableWidget}: 1.20.1 {@code drawBox(ctx, x, y, w, h)} paints a
 * white/grey border fill plus a black inner fill. It becomes the frosted text-field look ({@link AllGlass#field}: 4 px
 * rounded scrim, 0x4DFFFFFF focused / 0x2EFFFFFF otherwise), no hard border. {@code ScrollableTextWidget.drawBox(ctx)}
 * routes here too (it calls this overload or super).
 */
@Mixin(ScrollableWidget.class)
public abstract class TextAreaGlassMixin {

    @Inject(method = "drawBox(Lnet/minecraft/client/gui/DrawContext;IIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassBox(DrawContext ctx, int x, int y, int w, int h, CallbackInfo ci) {
        ci.cancel();
        AllGlass.field(ctx, x, y, x + w, y + h, ((ClickableWidget) (Object) this).isFocused());
    }
}
