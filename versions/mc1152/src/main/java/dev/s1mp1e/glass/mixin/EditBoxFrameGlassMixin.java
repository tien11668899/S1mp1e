package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #3 — every bordered single-line input: 1.15.2 {@code renderButton(IIF)V} draws (when {@code hasBorder()}) an outer
 * border {@code fill(x-1,y-1,x+w+1,y+h+1, focused?0xFFFFFFFF:0xFFA0A0A0)} then an inner black
 * {@code fill(x,y,x+w,y+h,0xFF000000)}. The pair becomes one frosted glass field frame ({@link AllGlass#field},
 * brighter while focused); borderless fields (chat, the anvil name field) draw no fills so they are untouched. The two
 * background fills are the inherited static {@code TextFieldWidget.fill(IIIII)V} (owner = the widget itself since it
 * extends DrawableHelper — javap-verified); the selection-highlight fill uses {@code DrawableHelper.fill} (different
 * owner) and is left alone. The first fill paints the frame, the second is dropped. This wraps {@code EditBoxTypingMixin}'s
 * redirect of the same call (which only glasses the recipe-book / world-select search boxes), so every bordered field
 * now gets the frame.
 */
@Mixin(TextFieldWidget.class)
public abstract class EditBoxFrameGlassMixin {

    @Unique private int s1mp1e$fillIdx;

    @Inject(method = "renderButton(IIF)V", at = @At("HEAD"))
    private void s1mp1e$reset(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.s1mp1e$fillIdx = 0;
    }

    @WrapOperation(method = "renderButton(IIF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;fill(IIIII)V"))
    private void s1mp1e$frame(int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (this.s1mp1e$fillIdx++ == 0) {          // the outer border rect -> one glass field frame
            AllGlass.field(x0, y0, x1, y1, ((TextFieldWidget) (Object) this).isFocused());
        }
        // the inner black fill: dropped. DrawableHelper.fill ENDS with enableTexture() + disableBlend(); the rows drawn
        // after a search box rely on that (without it list icons vanish and their text draws as coloured blocks) —
        // leave the same state the replaced fill would have.
        com.mojang.blaze3d.systems.RenderSystem.enableTexture();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }
}
