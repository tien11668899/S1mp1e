package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.asm.ContainerHook;
import dev.s1mp1e.o.glass.asm.HoverHook;
import dev.s1mp1e.o.glass.hook.GlassCreativeGlide;
import dev.s1mp1e.o.glass.hook.ItemFlightHook;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.inventory.slot.InventorySlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod「GuiContainer」：物品飛行（每幀比對、在 labels 前畫飛行中的物品、飛行目標格先不畫）、背景層前後 arm/disarm
 * （BlitSuppressor 只丟掉面板條）、原版白色懸停方塊→玻璃懸停、拖曳分配白方塊→圓角玻璃、創造滑動網格與點擊吸附。
 */
@Mixin(value = InventoryMenuScreen.class, priority = 1100)
public abstract class InventoryMenuScreenMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$observe(int mx, int my, float pt, CallbackInfo ci) {
        ItemFlightHook.observe((InventoryMenuScreen) (Object) this);
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/inventory/menu/InventoryMenuScreen;renderLabels(II)V"))
    private void s1mp1e$flightDraw(int mx, int my, float pt, CallbackInfo ci) {
        ItemFlightHook.draw((InventoryMenuScreen) (Object) this);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/inventory/menu/InventoryMenuScreen;renderMenuBackground(FII)V"))
    private void s1mp1e$bgLayer(InventoryMenuScreen self, float pt, int mx, int my, Operation<Void> op) {
        ContainerHook.arm(self);
        try {
            op.call(self, pt, mx, my);
        } finally {
            ContainerHook.disarm();
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/inventory/menu/InventoryMenuScreen;fillGradient(IIIIII)V"))
    private void s1mp1e$hover(InventoryMenuScreen self, int l, int t, int r, int b, int c0, int c1, Operation<Void> op) {
        HoverHook.slotHighlight(self, l, t, r, b, c0, c1);
    }

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$slotHead(InventorySlot slot, CallbackInfo ci) {
        InventoryMenuScreen self = (InventoryMenuScreen) (Object) this;
        // 先問物品飛行（目標格的物品還在飛就先不畫），再問創造網格滑動
        if (ItemFlightHook.hideSlot(self, slot) || GlassCreativeGlide.onDrawSlot(self, slot)) ci.cancel();
    }

    @WrapOperation(method = "renderSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/inventory/menu/InventoryMenuScreen;fill(IIIII)V"))
    private void s1mp1e$drag(int l, int t, int r, int b, int c, Operation<Void> op) {
        HoverHook.dragHighlight(l, t, r, b, c);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$clickSnap(int mx, int my, int button, CallbackInfo ci) {
        GlassCreativeGlide.onContainerMouseClicked((InventoryMenuScreen) (Object) this);
    }
}
