package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.glass.render.ItemFlights;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.container.Container;
import net.minecraft.container.Slot;
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
 * stack picked up and put down by hand appears as in vanilla — the hand carried it there already. 1.15.2 port of 26.2's
 * {@code ItemFlightMixin}. Once a frame, before the slots are drawn, the slot contents are compared with the previous
 * frame's: every slot that gained an item is paired with another slot that lost the same item in that frame, and a
 * flight is spawned ({@link ItemFlights}). Picking a stack up only takes from a slot and putting it down only adds to
 * one, so a hand-carried move never forms a pair. A landing slot that was empty (or held something else) keeps its item
 * hidden until the flight arrives. Anything that can't be paired (a crafted result appearing, …) just appears as in
 * vanilla. The creative inventory, which has its own grid glide, is left alone.
 *
 * <h3>Seam map (javap-verified on the 1.15.2 {@code ContainerScreen})</h3>
 * <ul>
 *   <li>{@code render} HEAD — compare the slots with the previous frame.</li>
 *   <li>{@code drawSlot(MatrixStack, Slot)} HEAD, cancellable — hide a landing slot (private here; an inject does not
 *       mind).</li>
 *   <li>BEFORE the {@code drawForeground(MatrixStack, II)} INVOKE in {@code render} — the flights. That point is
 *       inside the {@code RenderSystem} model-view translate to the container origin that the slot loop uses (push at
 *       offset 40 / translate 52, drawForeground 198, pop 492), which is exactly the space GUI item models are drawn
 *       in on 1.15.2.</li>
 * </ul>
 * {@code ItemStack.isSameItemSameComponents} → 1.15.2's {@code Container.canStacksCombine} (same item, same
 * NBT; {@code ItemStack.canCombine} is 1.17's); {@code copyWithCount} does not exist yet ({@code copy} + {@code setCount}).
 */
@Mixin(ContainerScreen.class)
public abstract class ItemFlightMixin {

    @Shadow @Final protected Container container;

    @Unique private final ItemFlights s1mp1e$flights = new ItemFlights();
    @Unique private ItemStack[] s1mp1e$prev;

    @Unique
    private boolean s1mp1e$enabled() {
        return !((Object) this instanceof CreativeInventoryScreen);
    }

    @Unique
    private static boolean s1mp1e$same(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return a.getCount() == b.getCount() && Container.canStacksCombine(a, b);
    }

    /**
     * Once a frame, before the slots are drawn: whatever moved from one slot to another since the last frame flies.
     * A stack going onto the cursor, or coming off it, has no other slot on the far side in that frame, so a move
     * carried by hand pairs with nothing.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$observe(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$enabled()) return;
        List<Slot> slots = this.container.slots;
        int n = slots.size();
        ItemStack[] before = s1mp1e$prev;
        if (before != null && before.length == n) {
            boolean changed = false;
            for (int i = 0; i < n && !changed; i++) changed = !s1mp1e$same(before[i], slots.get(i).getStack());
            if (!changed) return;
        }
        ItemStack[] now = new ItemStack[n];
        for (int i = 0; i < n; i++) now[i] = slots.get(i).getStack().copy();
        s1mp1e$prev = now;
        if (before == null || before.length != n) return;   // first frame, or another slot set: nothing to compare with
        int[] lost = new int[n];
        for (int i = 0; i < n; i++) {
            ItemStack b = before[i], a = slots.get(i).getStack();
            if (b.isEmpty()) continue;
            if (a.isEmpty() || !Container.canStacksCombine(a, b)) lost[i] = b.getCount();
            else if (a.getCount() < b.getCount()) lost[i] = b.getCount() - a.getCount();
        }

        for (int j = 0; j < n; j++) {
            Slot target = slots.get(j);
            ItemStack a = target.getStack(), b = before[j];
            if (a.isEmpty()) continue;
            boolean fresh = b.isEmpty() || !Container.canStacksCombine(a, b);
            int gain = fresh ? a.getCount() : a.getCount() - b.getCount();
            if (gain <= 0) continue;
            // where did it come from? another slot that lost the same item this frame
            int src = -1;
            for (int i = 0; src < 0 && i < n; i++) {
                if (i != j && lost[i] > 0 && Container.canStacksCombine(before[i], a)) src = i;
            }
            ItemStack flying = a.copy();
            flying.setCount(gain);
            if (src >= 0) {
                Slot from = slots.get(src);
                lost[src] = Math.max(0, lost[src] - gain);
                s1mp1e$flights.spawn(flying, from.xPosition, from.yPosition, target.xPosition, target.yPosition, target, fresh);
            }
        }
    }

    /** A slot whose item is still in flight toward it stays empty until it lands. */
    @Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hideLanding(Slot slot, CallbackInfo ci) {
        if (!s1mp1e$flights.isEmpty() && s1mp1e$flights.hides(slot)) ci.cancel();
    }

    /** Flights over the slots (container-local space, like the slots themselves — inside the render translate). */
    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
            target = "Lnet/minecraft/client/gui/screen/ingame/ContainerScreen;drawForeground(II)V"))
    private void s1mp1e$drawFlights(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$flights.draw();
    }
}
