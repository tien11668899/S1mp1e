package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ItemFlights;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Items fly between slots instead of teleporting — only for moves that never ride the cursor: shift-click quick-move
 * and number-key / off-hand swaps. A stack picked up and put down with the mouse (click, drag-distribute, double-click
 * collect) appears as in vanilla — the hand carried it there already. Container clicks are predicted client-side, so
 * the slot contents right after {@code slotClicked} already show the move: they are diffed against a snapshot taken
 * right before it. Every slot that gained an item is paired with where that item came from — the clicked slot, or
 * another slot that lost the same item — and a flight is spawned ({@link ItemFlights}). A landing slot that was empty
 * (or held something else) keeps its item hidden until the flight arrives. Anything that can't be paired (a crafted
 * result appearing, …) just appears as in vanilla. The creative inventory, which has its own grid glide, is left alone.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemFlightMixin {

   @Shadow @Final protected AbstractContainerMenu menu;

   @Unique private final ItemFlights lg$flights = new ItemFlights();
   @Unique private ItemStack[] lg$before;

   @Unique
   private boolean lg$enabled() {
      return !((Object) this instanceof CreativeModeInventoryScreen);
   }

   @Inject(method = "slotClicked", at = @At("HEAD"))
   private void lg$snapshot(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
      if (!lg$enabled()) return;
      // only moves that never ride the cursor: a stack picked up and put down by hand was carried there already
      if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.SWAP) return;
      List<Slot> slots = this.menu.slots;
      lg$before = new ItemStack[slots.size()];
      for (int i = 0; i < slots.size(); i++) lg$before[i] = slots.get(i).getItem().copy();
   }

   @Inject(method = "slotClicked", at = @At("RETURN"))
   private void lg$spawnFlights(Slot clicked, int slotId, int button, ContainerInput input, CallbackInfo ci) {
      ItemStack[] before = lg$before;
      lg$before = null;
      if (!lg$enabled() || before == null) return;
      List<Slot> slots = this.menu.slots;
      int n = Math.min(before.length, slots.size());
      int[] lost = new int[n];
      for (int i = 0; i < n; i++) {
         ItemStack b = before[i], a = slots.get(i).getItem();
         if (b.isEmpty()) continue;
         if (a.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) lost[i] = b.getCount();
         else if (a.getCount() < b.getCount()) lost[i] = b.getCount() - a.getCount();
      }
      int clickedIdx = clicked == null ? -1 : slots.indexOf(clicked);

      for (int j = 0; j < n; j++) {
         Slot target = slots.get(j);
         ItemStack a = target.getItem(), b = before[j];
         if (a.isEmpty()) continue;
         boolean fresh = b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b);
         int gain = fresh ? a.getCount() : a.getCount() - b.getCount();
         if (gain <= 0) continue;
         // where did it come from? the clicked slot, or another slot that lost it
         int src = -1;
         if (clickedIdx >= 0 && clickedIdx != j && lost[clickedIdx] > 0 && ItemStack.isSameItemSameComponents(before[clickedIdx], a)) src = clickedIdx;
         for (int i = 0; src < 0 && i < n; i++) {
            if (i != j && lost[i] > 0 && ItemStack.isSameItemSameComponents(before[i], a)) src = i;
         }
         ItemStack flying = a.copyWithCount(gain);
         if (src >= 0) {
            Slot from = slots.get(src);
            lost[src] = Math.max(0, lost[src] - gain);
            lg$flights.spawn(flying, from.x, from.y, target.x, target.y, target, fresh);
         }
      }
   }

   /** A slot whose item is still in flight toward it stays empty until it lands. */
   @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
   private void lg$hideLanding(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
      if (!lg$flights.isEmpty() && lg$flights.hides(slot)) ci.cancel();
   }

   /** Flights over the slots (container-local space, like the slots themselves). */
   @Inject(method = "extractSlots", at = @At("RETURN"))
   private void lg$drawFlights(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
      lg$flights.draw(g, Minecraft.getInstance().font);
   }
}
