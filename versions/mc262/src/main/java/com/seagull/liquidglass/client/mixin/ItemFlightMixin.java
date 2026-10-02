package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ItemFlights;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
 * Items fly between slots instead of teleporting — whenever a stack moves from one slot to another without riding the
 * cursor: shift-click quick-move, number-key / off-hand swaps, and moves made for the player (Item Scroller's drag and
 * scroll moves, the recipe book filling the grid, a trade being laid out, a result shift-crafted into the inventory). A
 * stack picked up and put down by hand appears as in vanilla — the hand carried it there already. Once a frame, before
 * the slots are drawn, the slot contents are compared with the previous frame's: every slot that gained an item is
 * paired with another slot that lost the same item in that frame, and a flight is spawned ({@link ItemFlights}).
 * Picking a stack up only takes from a slot and putting it down only adds to one, so a hand-carried move never forms a
 * pair. A landing slot that was empty (or held something else) keeps its item hidden until the flight arrives. Anything
 * that can't be paired (a crafted result appearing, …) just appears as in vanilla. The creative inventory, which has
 * its own grid glide, is left alone.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ItemFlightMixin {

   @Shadow @Final protected AbstractContainerMenu menu;

   @Unique private final ItemFlights lg$flights = new ItemFlights();
   @Unique private ItemStack[] lg$prev;

   @Unique
   private boolean lg$enabled() {
      return !((Object) this instanceof CreativeModeInventoryScreen);
   }

   @Unique
   private static boolean lg$same(ItemStack a, ItemStack b) {
      if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
      return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
   }

   /**
    * Once a frame, before the slots are drawn: whatever moved from one slot to another since the last frame flies.
    * A stack going onto the cursor, or coming off it, has no other slot on the far side in that frame, so a move
    * carried by hand pairs with nothing. Hooked on {@code extractContents}, not {@code extractRenderState}: the
    * recipe-book screens (inventory, crafting table, furnace) call the former directly and skip the latter.
    */
   @Inject(method = "extractContents", at = @At("HEAD"))
   private void lg$observe(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!lg$enabled()) return;
      List<Slot> slots = this.menu.slots;
      int n = slots.size();
      ItemStack[] before = lg$prev;
      if (before != null && before.length == n) {
         boolean changed = false;
         for (int i = 0; i < n && !changed; i++) changed = !lg$same(before[i], slots.get(i).getItem());
         if (!changed) return;
      }
      ItemStack[] now = new ItemStack[n];
      for (int i = 0; i < n; i++) now[i] = slots.get(i).getItem().copy();
      lg$prev = now;
      if (before == null || before.length != n) return;   // first frame, or another slot set: nothing to compare with
      int[] lost = new int[n];
      for (int i = 0; i < n; i++) {
         ItemStack b = before[i], a = slots.get(i).getItem();
         if (b.isEmpty()) continue;
         if (a.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) lost[i] = b.getCount();
         else if (a.getCount() < b.getCount()) lost[i] = b.getCount() - a.getCount();
      }

      for (int j = 0; j < n; j++) {
         Slot target = slots.get(j);
         ItemStack a = target.getItem(), b = before[j];
         if (a.isEmpty()) continue;
         boolean fresh = b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b);
         int gain = fresh ? a.getCount() : a.getCount() - b.getCount();
         if (gain <= 0) continue;
         // where did it come from? another slot that lost the same item this frame
         int src = -1;
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
