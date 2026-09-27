package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GlassGlideHost;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sub-pixel container-grid glide (D), shared at the {@code HandledScreen} level because the creative item grid is drawn
 * by inherited slot code ({@code HandledScreen.render} → {@code drawSlot}). Everything here is a strict no-op unless the
 * screen is a {@link GlassGlideHost} that reports it is mid-glide this frame — the only such screen is the creative
 * inventory ({@code CreativeGlassMixin}), so every other container (survival inventory, chests, furnaces, …) runs the
 * untouched vanilla path.
 *
 * <p>While the host is gliding:
 * <ul>
 *   <li>vanilla's own drawing of the scrolling grid slots is skipped ({@link #s1mp1e$slot}) so the host's eased overlay
 *       is the only item content in the window;</li>
 *   <li>the host's eased overlay is drawn at the tail of the slot loop, just before {@code drawForeground}
 *       ({@link #s1mp1e$overlay}), inside the same {@code leftPos/topPos} model-view translate the slots use.</li>
 * </ul>
 * The vanilla logical scroll is left row-aligned (clicks / tooltips act on the row vanilla will hit-test); the glide is
 * brief and settles exactly onto the vanilla row.
 */
@Mixin(HandledScreen.class)
public abstract class ContainerGlideGlassMixin {

    /** Skip vanilla's own drawing of a scrolling grid slot while the host draws that content itself. */
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawSlot(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/screen/slot/Slot;)V"))
    private void s1mp1e$slot(HandledScreen<?> self, MatrixStack matrices, Slot slot, Operation<Void> original) {
        if (self instanceof GlassGlideHost host && host.s1mp1e$gliding() && host.s1mp1e$isGlideSlot(slot)) {
            return;   // suppressed: the host's eased overlay draws this content
        }
        original.call(self, matrices, slot);
    }

    /** Draw the host's eased grid overlay in the same translated model-view matrix vanilla drew the slots in. */
    @Inject(method = "render",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                     target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;"
                            + "drawForeground(Lnet/minecraft/client/util/math/MatrixStack;II)V"))
    private void s1mp1e$overlay(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof GlassGlideHost host && host.s1mp1e$gliding()) {
            host.s1mp1e$drawGlideOverlay(matrices);
        }
    }
}
