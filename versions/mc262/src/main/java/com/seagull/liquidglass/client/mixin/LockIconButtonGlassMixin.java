package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassSurface;
import com.seagull.liquidglass.client.render.SfIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.LockIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The difficulty-lock {@link LockIconButton} (World Options) drew one vanilla lock/unlock sprite. It becomes a glass tile
 * (brighter when hovered or focused) with the SF {@code lock.fill} / {@code lock.open.fill} glyph — amber when locked,
 * white when open. Replaces the whole content draw (the sprite is all vanilla draws there).
 */
@Mixin(LockIconButton.class)
public abstract class LockIconButtonGlassMixin {

   @Inject(method = "extractContents(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true)
   private void lg$glassLock(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      LockIconButton b = (LockIconButton) (Object) this;
      AbstractWidget w = b;
      int x = w.getX(), y = w.getY(), x1 = x + w.getWidth(), y1 = y + w.getHeight();
      ci.cancel();
      boolean hot = w.isHovered() || w.isFocused();
      int base = w.active ? (hot ? 0x4DFFFFFF : 0x2EFFFFFF) : 0x14FFFFFF;
      GlassSurface.scrim(g, x, y, x1, y1, 4.0F, base);
      float px = w.getWidth() * 0.22F, py = w.getHeight() * 0.22F;
      SfIcons.drawGlyph(g, b.isLocked() ? "lock.fill" : "lock.open.fill",
            x + px, y + py, x1 - px, y1 - py, b.isLocked() ? 0xFFFFD60A : 0xFFFFFFFF);
   }
}
