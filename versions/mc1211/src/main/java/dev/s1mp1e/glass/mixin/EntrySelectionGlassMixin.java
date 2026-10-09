package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The selected row of every list was vanilla's box (outline colour + black inset). It becomes a glass highlight on the
 * button material with the hotbar corner — brighter while the list has focus. The glide ({@code ListMotionMixin}) calls
 * this same method at the gliding position, so it keeps gliding.
 */
@Mixin(EntryListWidget.class)
public abstract class EntrySelectionGlassMixin {

    @Inject(method = "drawSelectionHighlight", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassSelection(DrawContext ctx, int y, int entryWidth, int entryHeight, int borderColor, int fillColor,
                                       CallbackInfo ci) {
        ci.cancel();
        ClickableWidget self = (ClickableWidget) (Object) this;
        float x0 = self.getX() + (self.getWidth() - entryWidth) / 2f, x1 = self.getX() + (self.getWidth() + entryWidth) / 2f;
        float y0 = y - 2, y1 = y + entryHeight + 2;
        boolean focused = (borderColor & 0xFFFFFF) == 0xFFFFFF;
        AllGlass.capsule(ctx, x0, y0, x1, y1, AllGlass.hotbarCorner(x1 - x0, y1 - y0), focused ? 0.81f : 0.55f, 1f);
    }
}
