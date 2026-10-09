package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every bordered single-line input (#2/#3): vanilla drew an outer border fill + an inner black fill when
 * {@code drawsBackground}. The pair becomes one frosted glass field frame ({@link AllGlass#field}, brighter while
 * focused); borderless fields (chat, anvil-via-sprite) have no fills so they are untouched. 1.20.1 {@code renderButton}
 * draws exactly two {@code fill} calls — outer border then inner — so the first paints the frame and the second is
 * dropped.
 */
@Mixin(TextFieldWidget.class)
public abstract class EditBoxFrameGlassMixin {

    @Unique private int s1mp1e$fillIdx;

    @Inject(method = "renderButton", at = @At("HEAD"))
    private void s1mp1e$reset(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$fillIdx = 0;
    }

    @WrapOperation(method = "renderButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$frame(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (this.s1mp1e$fillIdx++ == 0) {          // the outer border rect -> one glass field frame
            AllGlass.field(ctx, x0, y0, x1, y1, ((TextFieldWidget) (Object) this).isFocused());
        }
        // the inner black fill: dropped
    }
}
