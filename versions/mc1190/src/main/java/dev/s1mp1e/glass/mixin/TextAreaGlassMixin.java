package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #2 multi-line text areas — every {@code ScrollableWidget} (multi-line edit boxes, the long-text screens): 1.19.2
 * {@code drawBox(MatrixStack)} paints a white/grey border fill plus a black inner fill (DrawableHelper.fill). It becomes
 * the frosted text-field look ({@link AllGlass#field}: 4 px rounded scrim, 0x4DFFFFFF focused / 0x2EFFFFFF otherwise),
 * no hard border.
 *
 * <p>1.19.2 {@code drawBox} takes only the {@link MatrixStack} (no x/y/w/h as the 1.20.1 line did); the box spans the
 * widget's own bounds (public {@code x}/{@code y} fields, {@code getWidth}/{@code getHeight} — 1.19.2 has no
 * {@code getX}/{@code getY}).
 */
@Mixin(ScrollableWidget.class)
public abstract class TextAreaGlassMixin {

    @Inject(method = "drawBox(Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassBox(MatrixStack matrices, CallbackInfo ci) {
        ci.cancel();
        ClickableWidget w = (ClickableWidget) (Object) this;
        AllGlass.field(matrices, w.x, w.y, w.x + w.getWidth(), w.y + w.getHeight(), w.isFocused());
    }
}
