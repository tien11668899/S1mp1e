package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.glass.render.ItemFlights;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Items fly between slots instead of teleporting (shift-click quick-move, placing the carried stack, number-key swaps,
 * double-click collect …). 1.21.1 port of 26.2's {@code ItemFlightMixin}: container clicks are predicted client-side, so
 * the slot contents right after {@code onMouseClick} already show the move; they are diffed against a snapshot taken
 * right before it. Every slot that gained an item is paired with where that item came from — the clicked slot, another
 * slot that lost the same item, or the carried stack (then the flight starts at the mouse) — and a flight is spawned
 * ({@link ItemFlights}). A landing slot that was empty (or held something else) keeps its item hidden until the flight
 * arrives. Anything that can't be paired (a crafted result appearing, …) just appears as in vanilla. The creative
 * inventory, which has its own grid glide, is left alone.
 *
 * <h3>Seam map (26.2 deferred → 1.21.1 immediate, verified against yarn 1.21.1+build.3)</h3>
 * <ul>
 *   <li>{@code leftPos/topPos} → {@code HandledScreen.x/y}; {@code menu.slots}/{@code menu.getCarried()} →
 *       {@code handler.slots}/{@code handler.getCursorStack()}.</li>
 *   <li>{@code slotClicked} → {@code onMouseClick(Slot,int,int,SlotActionType)} (snapshot HEAD, spawn RETURN).</li>
 *   <li>{@code extractSlot} (hide landing) → {@code drawSlot(DrawContext,Slot)} HEAD, cancellable.</li>
 *   <li>{@code extractSlots} RETURN (draw flights, container-local) → BEFORE the {@code drawForeground} INVOKE in
 *       {@code render}, which runs inside the same {@code translate(x, y)} the slot loop uses (bytecode-verified: push
 *       at offset 28 / translate 42, drawForeground 170, pop 461).</li>
 * </ul>
 * {@code ItemStack.isSameItemSameComponents} → {@code ItemStack.areItemsAndComponentsEqual}.
 */
@Mixin(HandledScreen.class)
public abstract class ItemFlightMixin {

    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected ScreenHandler handler;

    @Unique private final ItemFlights s1mp1e$flights = new ItemFlights();
    @Unique private ItemStack[] s1mp1e$before;
    @Unique private ItemStack s1mp1e$carriedBefore = ItemStack.EMPTY;
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;

    @Unique
    private boolean s1mp1e$enabled() {
        return !((Object) this instanceof CreativeInventoryScreen);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$trackMouse(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
    }

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"))
    private void s1mp1e$snapshot(Slot slot, int slotId, int button, SlotActionType actionType, CallbackInfo ci) {
        if (!s1mp1e$enabled()) return;
        List<Slot> slots = this.handler.slots;
        s1mp1e$before = new ItemStack[slots.size()];
        for (int i = 0; i < slots.size(); i++) s1mp1e$before[i] = slots.get(i).getStack().copy();
        s1mp1e$carriedBefore = this.handler.getCursorStack().copy();
    }

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("RETURN"))
    private void s1mp1e$spawnFlights(Slot clicked, int slotId, int button, SlotActionType actionType, CallbackInfo ci) {
        ItemStack[] before = s1mp1e$before;
        s1mp1e$before = null;
        if (!s1mp1e$enabled() || before == null) return;
        List<Slot> slots = this.handler.slots;
        int n = Math.min(before.length, slots.size());
        int[] lost = new int[n];
        for (int i = 0; i < n; i++) {
            ItemStack b = before[i], a = slots.get(i).getStack();
            if (b.isEmpty()) continue;
            if (a.isEmpty() || !ItemStack.areItemsAndComponentsEqual(a, b)) lost[i] = b.getCount();
            else if (a.getCount() < b.getCount()) lost[i] = b.getCount() - a.getCount();
        }
        ItemStack carriedAfter = this.handler.getCursorStack();
        int carriedLost = 0;
        if (!s1mp1e$carriedBefore.isEmpty()) {
            if (carriedAfter.isEmpty() || !ItemStack.areItemsAndComponentsEqual(carriedAfter, s1mp1e$carriedBefore)) carriedLost = s1mp1e$carriedBefore.getCount();
            else if (carriedAfter.getCount() < s1mp1e$carriedBefore.getCount()) carriedLost = s1mp1e$carriedBefore.getCount() - carriedAfter.getCount();
        }
        int clickedIdx = clicked == null ? -1 : slots.indexOf(clicked);

        for (int j = 0; j < n; j++) {
            Slot target = slots.get(j);
            ItemStack a = target.getStack(), b = before[j];
            if (a.isEmpty()) continue;
            boolean fresh = b.isEmpty() || !ItemStack.areItemsAndComponentsEqual(a, b);
            int gain = fresh ? a.getCount() : a.getCount() - b.getCount();
            if (gain <= 0) continue;
            // where did it come from? the clicked slot, another slot that lost it, or the carried stack
            int src = -1;
            if (clickedIdx >= 0 && clickedIdx != j && lost[clickedIdx] > 0 && ItemStack.areItemsAndComponentsEqual(before[clickedIdx], a)) src = clickedIdx;
            for (int i = 0; src < 0 && i < n; i++) {
                if (i != j && lost[i] > 0 && ItemStack.areItemsAndComponentsEqual(before[i], a)) src = i;
            }
            ItemStack flying = a.copyWithCount(gain);
            if (src >= 0) {
                Slot from = slots.get(src);
                lost[src] = Math.max(0, lost[src] - gain);
                s1mp1e$flights.spawn(flying, from.x, from.y, target.x, target.y, target, fresh);
            } else if (carriedLost > 0 && ItemStack.areItemsAndComponentsEqual(s1mp1e$carriedBefore, a)) {
                carriedLost = Math.max(0, carriedLost - gain);
                float mx = s1mp1e$mouseX - this.x - 8, my = s1mp1e$mouseY - this.y - 8;
                s1mp1e$flights.spawn(flying, mx, my, target.x, target.y, target, fresh);
            }
        }
        s1mp1e$carriedBefore = ItemStack.EMPTY;
    }

    /** A slot whose item is still in flight toward it stays empty until it lands. */
    @Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hideLanding(DrawContext context, Slot slot, CallbackInfo ci) {
        if (!s1mp1e$flights.isEmpty() && s1mp1e$flights.hides(slot)) ci.cancel();
    }

    /** Flights over the slots (container-local space, like the slots themselves — inside the render translate). */
    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
            target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawForeground(Lnet/minecraft/client/gui/DrawContext;II)V"))
    private void s1mp1e$drawFlights(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$flights.draw(context, MinecraftClient.getInstance().textRenderer);
    }
}
