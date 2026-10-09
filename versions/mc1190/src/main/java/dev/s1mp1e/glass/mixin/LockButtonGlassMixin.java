package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.widget.LockButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #21 — the difficulty-lock button drew one vanilla padlock sprite ({@code widgets.png}, instance
 * {@code drawTexture(MatrixStack,IIIIII)}). It becomes a frosted round tile with the SF {@code lock.fill} (amber,
 * locked) / {@code lock.open.fill} (white) glyph — the same look as the 1.20.1 / 1.21.1 / 26.x lines (the glyph PNGs in
 * {@code assets/s1mp1e/textures/gui/sf/} are byte-identical to 1.20.1's). HEAD inject, not a Redirect — redirects of a
 * button subclass's blit fail at runtime. If the glyph cannot be loaded the vanilla padlock still draws on the tile.
 * 1.19.2: position from the public {@code x}/{@code y} fields (no getX/getY).
 */
@Mixin(LockButtonWidget.class)
public abstract class LockButtonGlassMixin {

    @Inject(method = "renderButton(Lnet/minecraft/client/util/math/MatrixStack;IIF)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassLock(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LockButtonWidget b = (LockButtonWidget) (Object) this;
        int x = b.x, y = b.y, x1 = x + b.getWidth(), y1 = y + b.getHeight();
        boolean hot = b.isHovered() || b.isFocused();
        AllGlass.scrim(matrices, x, y, x1, y1, 4f, b.active ? (hot ? 0x4DFFFFFF : 0x2EFFFFFF) : 0x14FFFFFF);
        float px = b.getWidth() * 0.22f, py = b.getHeight() * 0.22f;
        boolean drawn = SfIcons.drawGlyph(matrices, b.isLocked() ? "lock.fill" : "lock.open.fill", x + px, y + py, x1 - px, y1 - py,
                b.isLocked() ? 0xFFFFD60A : 0xFFFFFFFF);
        if (drawn) ci.cancel();
    }
}
