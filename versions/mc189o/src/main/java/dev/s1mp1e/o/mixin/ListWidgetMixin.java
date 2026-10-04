package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.asm.MenuBackdropHook;
import dev.s1mp1e.o.glass.hook.ListMotionHook;
import net.minecraft.client.gui.widget.ListWidget;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod「GuiSlot」：清單底的泥土→模糊背景、頭尾泥土條→玻璃、滾輪平滑捲動。
 *
 * <p>原版把泥土直接寫在 render 裡（Forge 才抽成 drawContainerBackground），mixin 不能跳過一段程式，所以：
 * 第一個 begin() 之前先問 MenuBackdropHook 要不要接手（這時還沒在建 buffer，hook 可以自己畫）；接手的話，
 * 原版照樣把泥土頂點寫進 buffer，但第一個 tesselator.end() 改成「結束並清掉、不上傳」。
 */
@Mixin(value = ListWidget.class, priority = 1100)
public abstract class ListWidgetMixin {

    @Unique private boolean s1mp1e$skipDirt;

    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/render/vertex/BufferBuilder;begin(ILnet/minecraft/client/render/vertex/VertexFormat;)V"))
    private void s1mp1e$dirtBegin(BufferBuilder b, int mode, VertexFormat fmt, Operation<Void> op) {
        s1mp1e$skipDirt = MenuBackdropHook.listBackground((ListWidget) (Object) this);
        op.call(b, mode, fmt);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/render/vertex/Tesselator;end()V"))
    private void s1mp1e$dirtEnd(Tesselator t, Operation<Void> op) {
        if (s1mp1e$skipDirt) {
            s1mp1e$skipDirt = false;
            BufferBuilder b = t.getBuffer();
            b.end();
            b.clear();
        } else {
            op.call(t);
        }
    }

    @Inject(method = "renderHoleBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$overlay(int startY, int endY, int a0, int a1, CallbackInfo ci) {
        if (MenuBackdropHook.listOverlay((ListWidget) (Object) this, startY, endY)) ci.cancel();
    }

    @Inject(method = "handleMouse", at = @At("HEAD"))
    private void s1mp1e$wheelBefore(CallbackInfo ci) {
        ListMotionHook.before((ListWidget) (Object) this);
    }

    @Inject(method = "handleMouse", at = @At("RETURN"))
    private void s1mp1e$wheelAfter(CallbackInfo ci) {
        ListMotionHook.after((ListWidget) (Object) this);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$wheelStep(int mx, int my, float pt, CallbackInfo ci) {
        ListMotionHook.step((ListWidget) (Object) this);
    }
}
