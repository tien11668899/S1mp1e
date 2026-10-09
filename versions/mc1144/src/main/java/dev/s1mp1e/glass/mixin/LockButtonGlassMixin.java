package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.widget.LockButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #21 — the difficulty-lock button drew one vanilla padlock sprite ({@code widgets.png}, instance {@code blit(IIIIII)}).
 * It becomes a frosted round tile with the SF {@code lock.fill} (amber, locked) / {@code lock.open.fill} (white) glyph —
 * the same look as the newer lines (glyph PNGs in {@code assets/s1mp1e/textures/gui/sf/}). HEAD inject, not a Redirect —
 * redirects of a button subclass's blit fail at runtime. If the glyph cannot be loaded the vanilla padlock still draws on
 * the tile. 1.14.4: {@code renderButton(IIF)V}, public {@code x}/{@code y}, height via {@link ClickableWidgetAccessor}
 * (its {@code s1mp1e$getHeight}). Identical to the 1.15.2 mixin except {@code RenderSystem} → {@code GlStateManager}.
 */
@Mixin(LockButtonWidget.class)
public abstract class LockButtonGlassMixin {

    @Inject(method = "renderButton(IIF)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassLock(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LockButtonWidget b = (LockButtonWidget) (Object) this;
        int h = ((ClickableWidgetAccessor) (Object) b).s1mp1e$getHeight();
        int x = b.x, y = b.y, x1 = x + b.getWidth(), y1 = y + h;
        boolean hot = b.isHovered() || b.isFocused();
        AllGlass.scrim(x, y, x1, y1, 4f, b.active ? (hot ? 0x4DFFFFFF : 0x2EFFFFFF) : 0x14FFFFFF);
        float px = b.getWidth() * 0.22f, py = h * 0.22f;
        boolean drawn = SfIcons.drawGlyph(b.isLocked() ? "lock.fill" : "lock.open.fill", x + px, y + py, x1 - px, y1 - py,
                b.isLocked() ? 0xFFFFD60A : 0xFFFFFFFF);
        GlStateManager.enableTexture();
        GlStateManager.color4f(1f, 1f, 1f, 1f);
        if (drawn) ci.cancel();
    }
}
