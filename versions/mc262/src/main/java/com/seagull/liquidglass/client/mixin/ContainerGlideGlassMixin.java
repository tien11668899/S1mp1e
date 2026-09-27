package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GlassGlideHost;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sub-pixel container-grid glide, shared at the {@code AbstractContainerScreen} level because the creative item grid is
 * drawn by inherited slot code ({@code extractContents → extractSlots → extractSlot}). Everything here is a strict
 * no-op unless the screen is a {@link GlassGlideHost} that reports it is mid-glide this frame — the only such screen is
 * the creative inventory ({@code CreativeGlassMixin}), so every other container (survival inventory, chests, furnaces,
 * …) runs the untouched vanilla path.
 *
 * <p>While the host is gliding:
 * <ul>
 *   <li>vanilla's own drawing of the scrolling grid slots is skipped ({@link #liquidglass$slot}) so the host's eased
 *       overlay is the only item content in the window;</li>
 *   <li>the hovered-slot lookup used for the slot highlight / tooltip returns null for a grid slot
 *       ({@link #liquidglass$hovered}), so no highlight or tooltip is drawn for an item that is mid-glide (the glide is
 *       brief; at rest the vanilla highlight/tooltip return);</li>
 *   <li>the host's eased overlay is drawn at the tail of {@code extractSlots} ({@link #liquidglass$overlay}), inside the
 *       same {@code leftPos/topPos} matrix the slots use.</li>
 * </ul>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerGlideGlassMixin {

   /** Skip vanilla's own drawing of a scrolling grid slot while the host draws that content itself. */
   @WrapOperation(
      method = "extractSlots",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/inventory/Slot;II)V"
      )
   )
   private void liquidglass$slot(AbstractContainerScreen<?> self, GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, Operation<Void> original) {
      if (self instanceof GlassGlideHost host && host.liquidglass$gliding() && host.liquidglass$isGlideSlot(slot)) {
         return;   // suppressed: the host's eased overlay draws this content
      }
      original.call(self, g, slot, mouseX, mouseY);
   }

   /** Null out the hovered grid slot during a glide so no mismatched highlight / tooltip is drawn. Render-only. */
   @WrapOperation(
      method = "extractContents",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;"
      )
   )
   private Slot liquidglass$hovered(AbstractContainerScreen<?> self, double x, double y, Operation<Slot> original) {
      Slot r = original.call(self, x, y);
      if (r != null && self instanceof GlassGlideHost host && host.liquidglass$gliding() && host.liquidglass$isGlideSlot(r)) {
         return null;
      }
      return r;
   }

   /** Draw the host's eased grid overlay in the same translated matrix vanilla drew the slots in. */
   @Inject(method = "extractSlots", at = @At("TAIL"))
   private void liquidglass$overlay(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
      if ((Object) this instanceof GlassGlideHost host && host.liquidglass$gliding()) {
         host.liquidglass$drawGlideOverlay(g);
      }
   }
}
