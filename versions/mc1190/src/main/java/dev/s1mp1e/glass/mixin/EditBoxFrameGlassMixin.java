package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #2/#3 — every bordered single-line input: 1.19.2 {@code renderButton} draws (when {@code drawsBackground()}) an outer
 * border {@code fill(x-1,y-1,x+w+1,y+h+1, focused?0xFFFFFFFF:0xFFA0A0A0)} then an inner black
 * {@code fill(x,y,x+w,y+h,0xFF000000)}. The pair becomes one frosted glass field frame ({@link AllGlass#field},
 * brighter while focused); borderless fields (chat, the anvil name field via {@code setDrawsBackground(false)}) draw no
 * fills so they are untouched. The first {@code DrawableHelper.fill} paints the frame, the second is dropped.
 */
@Mixin(TextFieldWidget.class)
public abstract class EditBoxFrameGlassMixin {

    @Unique private int s1mp1e$fillIdx;

    @Inject(method = "renderButton(Lnet/minecraft/client/util/math/MatrixStack;IIF)V", at = @At("HEAD"))
    private void s1mp1e$reset(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$fillIdx = 0;
    }

    @WrapOperation(method = "renderButton(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$frame(MatrixStack matrices, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (this.s1mp1e$fillIdx++ == 0) {          // the outer border rect -> one glass field frame
            AllGlass.field(matrices, x0, y0, x1, y1, ((TextFieldWidget) (Object) this).isFocused());
        }
        // the inner black fill: dropped
    }
}
