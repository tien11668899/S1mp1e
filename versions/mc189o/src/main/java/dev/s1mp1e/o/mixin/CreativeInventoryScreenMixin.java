package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.asm.BlitSuppressor;
import dev.s1mp1e.o.glass.hook.GlassCreative;
import dev.s1mp1e.o.glass.hook.TabSwitchHook;
import net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.inventory.menu.InventoryMenu;
import net.minecraft.item.CreativeModeTab;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod「GuiContainerCreative」：分類切換交叉淡化（第 5 組）；玻璃分類列生效時，分類頁籤只畫圖示（置中在玻璃帶），
 * 不畫原版頁籤底圖。
 *
 * <p>為什麼在 renderTabIcon 開頭整個接手：Argentum 在這個方法裡用 @WrapWithCondition 把頁籤底圖丟進它自己的
 * 批次（繞過 GuiElement.drawTexture，BlitSuppressor 看不到），圖示也走它的 GUI 物品批次；不同 mod 的包裝誰在外層
 * 不可靠。開頭注入＋取消就和包裝順序無關——方法本體（含 Argentum 的包裝）根本不執行。
 * 繼承 InventoryMenuScreen 只是為了直接用父類的 protected 欄位（x、y、backgroundWidth…），建構子不會被呼叫。
 */
@Mixin(value = CreativeInventoryScreen.class, priority = 1100)
public abstract class CreativeInventoryScreenMixin extends InventoryMenuScreen {

    private CreativeInventoryScreenMixin(InventoryMenu menu) {
        super(menu);
    }

    @Inject(method = "setSelectedTab", at = @At("HEAD"))
    private void s1mp1e$tabSwitch(CreativeModeTab tab, CallbackInfo ci) {
        TabSwitchHook.creative((CreativeInventoryScreen) (Object) this, tab);
    }

    @Inject(method = "renderTabIcon", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTab(CreativeModeTab tab, CallbackInfo ci) {
        if (!BlitSuppressor.creativeArmed()) return;
        // 原版 renderTabIcon 的位置算法（底圖略過——玻璃 pill 已經畫好了）
        boolean top = tab.isTopRow();
        int col = tab.getColumn();
        int l = this.x + 28 * col;
        int m = this.y;
        if (col == 5) l = this.x + this.backgroundWidth - 28;
        else if (col > 0) l += col;
        if (top) m -= 28;
        else m += this.backgroundHeight - 4;
        this.drawOffset = 100.0F;
        this.itemRenderer.zOffset = 100.0F;
        l += 6;
        m += 8 + (top ? 1 : -1);
        GlStateManager.enableLighting();
        GlStateManager.enableRescaleNormal();
        ItemStack icon = tab.getIcon();
        GlassCreative.tabIcon(this.itemRenderer, icon, l, m);
        GlassCreative.tabIconOverlay(this.itemRenderer, this.textRenderer, icon, l, m);
        GlStateManager.disableLighting();
        this.itemRenderer.zOffset = 0.0F;
        this.drawOffset = 0.0F;
        ci.cancel();
    }

    // 玻璃沒生效時（例如著色器不可用）走原版，但圖示仍經過 GlassCreative（它自己判斷要不要位移）
    @WrapOperation(method = "renderTabIcon", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/entity/ItemRenderer;renderGuiItem(Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$icon(ItemRenderer ri, ItemStack stack, int x, int y, Operation<Void> op) {
        GlassCreative.tabIcon(ri, stack, x, y);
    }

    @WrapOperation(method = "renderTabIcon", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/entity/ItemRenderer;renderGuiItemDecoration(Lnet/minecraft/client/render/TextRenderer;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$iconOverlay(ItemRenderer ri, TextRenderer font, ItemStack stack, int x, int y, Operation<Void> op) {
        GlassCreative.tabIconOverlay(ri, font, stack, x, y);
    }
}
