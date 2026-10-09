package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.LockButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The difficulty-lock button drew one vanilla padlock sprite. It becomes a frosted round tile with the SF
 * {@code lock.fill} (amber, locked) / {@code lock.open.fill} (white) glyph. HEAD inject, not a Redirect — redirects of a
 * button subclass's sprite blit fail at runtime. 1.20.1 method name is {@code renderButton}.
 */
@Mixin(LockButtonWidget.class)
public abstract class LockButtonGlassMixin {

    @Inject(method = "renderButton(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassLock(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ci.cancel();
        LockButtonWidget b = (LockButtonWidget) (Object) this;
        ClickableWidget w = b;
        int x = w.getX(), y = w.getY(), x1 = x + w.getWidth(), y1 = y + w.getHeight();
        boolean hot = w.isHovered() || w.isFocused();
        AllGlass.scrim(ctx, x, y, x1, y1, 4f, w.active ? (hot ? 0x4DFFFFFF : 0x2EFFFFFF) : 0x14FFFFFF);
        float px = w.getWidth() * 0.22f, py = w.getHeight() * 0.22f;
        SfIcons.drawGlyph(ctx, b.isLocked() ? "lock.fill" : "lock.open.fill", x + px, y + py, x1 - px, y1 - py,
                b.isLocked() ? 0xFFFFD60A : 0xFFFFFFFF);
    }
}
